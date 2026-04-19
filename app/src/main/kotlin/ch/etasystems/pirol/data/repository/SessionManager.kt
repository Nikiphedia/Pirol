package ch.etasystems.pirol.data.repository

import android.content.Context
import android.util.Log
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import ch.etasystems.pirol.data.export.RavenExporter
import ch.etasystems.pirol.ml.VerificationStatus

/**
 * Info ueber vorhandene Sessions am aktuellen Speicherort.
 * Wird vor der Migration im Bestaetigungsdialog angezeigt.
 */
data class MigrationInfo(
    val sessionCount: Int,
    val totalSizeBytes: Long
)

/**
 * Ergebnis einer Session-Migration.
 */
data class MigrationResult(
    val sessionsCopied: Int,
    val bytesCopied: Long,
    val errors: List<String>
)

/**
 * Verwaltet Aufnahme-Sessions.
 *
 * Jede Session = ein Ordner unter filesDir/sessions/ mit:
 * - session.json (Metadaten)
 * - detections.jsonl (Detektionen, eine pro Zeile)
 * - audio/chunk_NNN.wav (3-Sekunden Audio-Chunks)
 *
 * Lifecycle: startSession() → appendDetections() / appendAudioSamples() → endSession()
 */
class SessionManager(
    private val context: Context,
    private val appPreferences: AppPreferences
) {

    companion object {
        private const val TAG = "SessionManager"
        private const val SESSIONS_DIR = "sessions"
    }

    /** Basispfad: gespeicherter Speicherort oder interner Speicher */
    private fun getBaseDir(): File {
        val customPath = appPreferences.storagePath
        return if (customPath != null) File(customPath) else context.filesDir
    }

    private val json = Json { prettyPrint = false }
    private val jsonLenient = Json { ignoreUnknownKeys = true }

    // Aktive Session (null = keine Session laeuft)
    private var activeSessionDir: File? = null
    private var activeMetadata: SessionMetadata? = null
    private var detectionWriter: FileWriter? = null
    private var recordingWriter: StreamingWavWriter? = null
    private var detectionCounter: Int = 0

    /** Ob eine Session aktiv ist */
    val isActive: Boolean get() = activeSessionDir != null

    /**
     * Neue Session starten. Erstellt Ordnerstruktur + session.json.
     *
     * @param sampleRate Sample Rate der Aufnahme
     * @param latitude Startposition Breitengrad (kann null sein)
     * @param longitude Startposition Laengengrad (kann null sein)
     * @param regionFilter Aktiver Regionalfilter (z.B. "ch_breeding")
     * @param confidenceThreshold Aktuelle Confidence-Schwelle
     */
    suspend fun startSession(
        sampleRate: Int,
        latitude: Double? = null,
        longitude: Double? = null,
        regionFilter: String? = null,
        confidenceThreshold: Float = 0.5f
    ): String = withContext(Dispatchers.IO) {
        // Alte Session sicherheitshalber schliessen
        if (isActive) endSession()

        // Session-ID: ISO-Datum + UUID-Prefix
        val now = LocalDateTime.now(ZoneOffset.UTC)
        val dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
        val uuidShort = UUID.randomUUID().toString().take(6)
        val sessionId = "${dateStr}_${uuidShort}"

        // Ordner erstellen
        val sessionsRoot = File(getBaseDir(), SESSIONS_DIR)
        val sessionDir = File(sessionsRoot, sessionId)
        val audioDir = File(sessionDir, "audio")
        audioDir.mkdirs()

        // Metadaten
        val metadata = SessionMetadata(
            sessionId = sessionId,
            startedAt = Instant.now().toString(),
            sampleRate = sampleRate,
            latitude = latitude,
            longitude = longitude,
            regionFilter = regionFilter,
            confidenceThreshold = confidenceThreshold
        )

        // session.json schreiben
        val prettyJson = Json { prettyPrint = true }
        File(sessionDir, "session.json").writeText(
            prettyJson.encodeToString(metadata)
        )

        // JSONL-Writer oeffnen (append-Modus)
        detectionWriter = FileWriter(File(sessionDir, "detections.jsonl"), true)

        // Recording-WAV oeffnen (Streaming-Writer)
        val recordingFile = File(audioDir, "recording.wav")
        recordingWriter = StreamingWavWriter(recordingFile, sampleRate).also { it.open() }

        activeSessionDir = sessionDir
        activeMetadata = metadata
        detectionCounter = 0

        Log.i(TAG, "Session gestartet: $sessionId → ${sessionDir.absolutePath}")
        sessionId
    }

    /**
     * Detektionen an JSONL anhaengen.
     * Wird vom LiveViewModel nach jeder Inference aufgerufen.
     */
    suspend fun appendDetections(detections: List<DetectionResult>) = withContext(Dispatchers.IO) {
        val writer = detectionWriter ?: return@withContext
        for (detection in detections) {
            val line = json.encodeToString(detection)
            writer.appendLine(line)
            detectionCounter++
        }
        writer.flush()
    }

    /**
     * Schreibt ein Verifikations-Event in verifications.jsonl.
     * Separate Datei von detections.jsonl (append-only, crash-sicher).
     */
    suspend fun appendVerification(event: VerificationEvent) = withContext(Dispatchers.IO) {
        val sessionDir = activeSessionDir ?: return@withContext
        val file = File(sessionDir, "verifications.jsonl")
        file.appendText(json.encodeToString(event) + "\n")
    }

    /**
     * Audio-Samples an recording.wav anhaengen.
     * Wird vom LiveViewModel fuer jeden Inference-Block aufgerufen.
     *
     * @param samples Audio-Daten als ShortArray (16-bit PCM)
     */
    suspend fun appendAudioSamples(samples: ShortArray) = withContext(Dispatchers.IO) {
        recordingWriter?.append(samples)
    }

    /**
     * Aktueller Schreib-Offset in recording.wav in Sekunden.
     *
     * Ist gleich der Sekunden-Position ab der der naechste appendAudioSamples()-Block
     * in der WAV-Datei landen wird. Enthaelt bereits Preroll-Samples wenn diese
     * ueber appendAudioSamples() geschrieben wurden.
     *
     * @return Offset in Sekunden, oder 0f wenn keine Session aktiv
     */
    fun getCurrentRecordingOffsetSec(): Float {
        val writer = recordingWriter ?: return 0f
        val sampleRate = activeMetadata?.sampleRate ?: return 0f
        return writer.sampleCount.toFloat() / sampleRate.toFloat()
    }

    /**
     * Aktive Session beenden. Schliesst WAV-Writer + JSONL-Writer, aktualisiert session.json.
     */
    suspend fun endSession() = withContext(Dispatchers.IO) {
        val dir = activeSessionDir ?: return@withContext
        val metadata = activeMetadata ?: return@withContext

        // WAV-Writer schliessen (zuerst, damit finale dataSize korrekt ist)
        val recordedSamples = recordingWriter?.sampleCount ?: 0L
        try {
            recordingWriter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "WAV close failed", e)
        }
        recordingWriter = null

        // JSONL-Writer schliessen
        try {
            detectionWriter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "JSONL-Writer schliessen fehlgeschlagen", e)
        }
        detectionWriter = null

        // session.json aktualisieren (endedAt, Zaehler)
        val updatedMetadata = metadata.copy(
            endedAt = Instant.now().toString(),
            totalRecordedSamples = recordedSamples,
            totalDetections = detectionCounter
        )
        val prettyJson = Json { prettyPrint = true }
        File(dir, "session.json").writeText(
            prettyJson.encodeToString(updatedMetadata)
        )

        Log.i(TAG, "Session beendet: ${metadata.sessionId} " +
                "($recordedSamples samples, $detectionCounter Detektionen)")

        activeSessionDir = null
        activeMetadata = null
        detectionCounter = 0
    }

    /**
     * Gibt alle vorhandenen Session-Ordner zurueck (neueste zuerst).
     */
    fun listSessions(): List<File> {
        val sessionsRoot = File(getBaseDir(), SESSIONS_DIR)
        if (!sessionsRoot.exists()) return emptyList()
        return sessionsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /** Loescht eine Session (Ordner + alle Dateien) */
    suspend fun deleteSession(sessionDir: File): Boolean = withContext(Dispatchers.IO) {
        if (!sessionDir.exists()) return@withContext false
        val deleted = sessionDir.deleteRecursively()
        Log.i(TAG, "Session geloescht: ${sessionDir.name} (erfolg=$deleted)")
        deleted
    }

    // ── Lese-Methoden fuer abgeschlossene Sessions ───────────────────

    /**
     * Laedt Metadaten einer Session aus session.json.
     */
    suspend fun loadMetadata(sessionDir: File): SessionMetadata? = withContext(Dispatchers.IO) {
        val file = File(sessionDir, "session.json")
        if (!file.exists()) return@withContext null
        try {
            jsonLenient.decodeFromString<SessionMetadata>(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "session.json parsen fehlgeschlagen: ${sessionDir.name}", e)
            null
        }
    }

    /**
     * Laedt alle Detektionen einer Session aus detections.jsonl.
     */
    suspend fun loadDetections(sessionDir: File): List<DetectionResult> = withContext(Dispatchers.IO) {
        val file = File(sessionDir, "detections.jsonl")
        if (!file.exists()) return@withContext emptyList()
        try {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try { jsonLenient.decodeFromString<DetectionResult>(line) }
                    catch (_: Exception) { null }
                }
        } catch (e: Exception) {
            Log.e(TAG, "detections.jsonl lesen fehlgeschlagen: ${sessionDir.name}", e)
            emptyList()
        }
    }

    /**
     * Laedt Verifikations-Events und wendet sie auf Detektionen an.
     * Gibt die Detektionen mit aktualisiertem VerificationStatus zurueck.
     */
    suspend fun loadDetectionsWithVerifications(sessionDir: File): List<DetectionResult> =
        withContext(Dispatchers.IO) {
            val detections = loadDetections(sessionDir).toMutableList()
            val veriFile = File(sessionDir, "verifications.jsonl")
            if (!veriFile.exists()) return@withContext detections

            val verifications = try {
                veriFile.readLines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        try { jsonLenient.decodeFromString<VerificationEvent>(line) }
                        catch (_: Exception) { null }
                    }
            } catch (_: Exception) { emptyList() }

            // Verifikationen auf Detektionen anwenden
            for (v in verifications) {
                val idx = detections.indexOfFirst { it.id == v.detectionId }
                if (idx >= 0) {
                    detections[idx] = detections[idx].copy(
                        verificationStatus = v.status,
                        correctedSpecies = v.correctedSpecies,
                        verifiedAtMs = v.verifiedAtMs
                    )
                }
            }
            detections
        }

    /**
     * Gibt die Recording-Datei einer Session zurueck (oder null wenn nicht vorhanden).
     */
    fun getRecordingFile(sessionDir: File): File? {
        val f = File(sessionDir, "audio/recording.wav")
        return if (f.exists()) f else null
    }

    /**
     * Schreibt eine Raven Selection Table neben recording.wav.
     * @return Pfad zur erzeugten Datei oder null wenn keine recording.wav existiert
     */
    suspend fun exportRavenSelectionTable(sessionDir: File): File? = withContext(Dispatchers.IO) {
        val recording = getRecordingFile(sessionDir) ?: return@withContext null
        val outputFile = File(sessionDir, "audio/recording.selections.txt")
        val detections = loadDetectionsWithVerifications(sessionDir)
        RavenExporter.export(outputFile, detections)
        outputFile
    }

    // ── Migrations-Methoden (T41) ────────────────────────────────────

    /**
     * Zaehlt Sessions und berechnet Gesamtgroesse am aktuellen Speicherort.
     */
    fun getMigrationInfo(): MigrationInfo {
        val sessionsRoot = File(getBaseDir(), SESSIONS_DIR)
        if (!sessionsRoot.exists()) return MigrationInfo(0, 0L)
        val dirs = sessionsRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val totalSize = dirs.sumOf { dir ->
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        return MigrationInfo(dirs.size, totalSize)
    }

    /**
     * Kopiert alle Sessions vom aktuellen Speicherort zum Zielort.
     *
     * - Duplikate (gleiche sessionId am Ziel) werden uebersprungen
     * - Quell-Sessions werden NICHT geloescht
     * - Fortschritt wird als Float 0..1 gemeldet
     *
     * @param targetBaseDir Neues Basisverzeichnis (z.B. SD-Karte oder filesDir)
     * @param onProgress Callback fuer Fortschritt (0.0 .. 1.0)
     * @return MigrationResult mit Statistik und eventuellen Fehlern
     */
    suspend fun migrateSessionsTo(
        targetBaseDir: File,
        onProgress: (Float) -> Unit = {}
    ): MigrationResult = withContext(Dispatchers.IO) {
        val sourceRoot = File(getBaseDir(), SESSIONS_DIR)
        val targetRoot = File(targetBaseDir, SESSIONS_DIR)
        targetRoot.mkdirs()

        if (!sourceRoot.exists()) {
            return@withContext MigrationResult(0, 0L, emptyList())
        }

        val sessionDirs = sourceRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (sessionDirs.isEmpty()) {
            return@withContext MigrationResult(0, 0L, emptyList())
        }

        var copied = 0
        var bytesCopied = 0L
        val errors = mutableListOf<String>()

        sessionDirs.forEachIndexed { index, sessionDir ->
            val targetDir = File(targetRoot, sessionDir.name)

            // Duplikat pruefen: wenn Ziel-Ordner existiert, ueberspringen
            if (targetDir.exists()) {
                Log.i(TAG, "Session ${sessionDir.name} existiert am Ziel — uebersprungen")
                onProgress((index + 1).toFloat() / sessionDirs.size)
                return@forEachIndexed
            }

            try {
                val success = sessionDir.copyRecursively(targetDir, overwrite = false)
                if (success) {
                    val sessionSize = targetDir.walkTopDown()
                        .filter { it.isFile }
                        .sumOf { it.length() }
                    bytesCopied += sessionSize
                    copied++
                    Log.i(TAG, "Session kopiert: ${sessionDir.name} ($sessionSize Bytes)")
                } else {
                    errors.add("${sessionDir.name}: Kopieren fehlgeschlagen")
                    // Teilkopie aufraemen
                    targetDir.deleteRecursively()
                }
            } catch (e: Exception) {
                errors.add("${sessionDir.name}: ${e.message}")
                // Teilkopie aufraemen
                targetDir.deleteRecursively()
                Log.e(TAG, "Session-Migration fehlgeschlagen: ${sessionDir.name}", e)
            }

            onProgress((index + 1).toFloat() / sessionDirs.size)
        }

        MigrationResult(copied, bytesCopied, errors)
    }
}
