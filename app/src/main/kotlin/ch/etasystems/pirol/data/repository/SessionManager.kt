package ch.etasystems.pirol.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.export.RavenExporter
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

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
 * Verwaltet Aufnahme-Sessions (T51: SAF + Flat-Struktur + Tages-Unterordner).
 *
 * Neue Struktur ab T51:
 * [base]/PIROL/YYYY-MM-DD/{sessionId}/
 *   session.json, detections.jsonl, verifications.jsonl,
 *   recording.wav, recording.selections.txt
 *
 * Kein audio/-Unterordner mehr (Rueckwaertskompatibilitaet: getRecordingFile() prueft beides).
 *
 * Storage-Modi:
 * - SAF (storageBaseUri gesetzt): DocumentFile fuer Writes, File-API fuer Reads (primaerer Speicher)
 * - FileBased (kein SAF): File-API fuer alles; Basis = getExternalFilesDir/PIROL oder legacyPath/PIROL
 *
 * Lifecycle: startSession() → appendDetections() / appendAudioSamples() → endSession()
 */
class SessionManager(
    private val context: Context,
    private val appPreferences: AppPreferences
) {

    companion object {
        private const val TAG = "SessionManager"
        private const val PIROL_DIR = "PIROL"
        /** Veraltetes Sessions-Verzeichnis (pre-T51, fuer Rueckwaertskompatibilitaet) */
        private const val LEGACY_SESSIONS_DIR = "sessions"

        /** Pure-Logic-Funktion fuer Unit-Tests (T60). */
        fun shouldAutoDelete(
            durationSec: Double,
            detections: Int,
            minSec: Int,
            enabled: Boolean
        ): Boolean = enabled && minSec > 0 && durationSec < minSec && detections == 0

        private val companionJson = Json { prettyPrint = false }
        private val companionJsonLenient = Json { ignoreUnknownKeys = true }

        /**
         * T93: Schreibt [content] atomar nach [target] (tmp-Datei + Move).
         */
        internal fun writeFileReplacing(target: File, content: String) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(content)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        }

        /**
         * T93: Ersetzt die Zeile mit der ID von [updated] in [file] (File-Modus).
         * Gibt den Writer zurueck, der danach fuer Appends zu verwenden ist.
         */
        internal fun replaceDetectionInFile(
            file: File,
            updated: DetectionResult,
            writer: java.io.Writer?,
            onError: (Exception) -> Unit = {}
        ): java.io.Writer? {
            if (!file.exists()) return writer
            val encodedLine = companionJson.encodeToString(updated)
            val lines = try { file.readLines() } catch (_: Exception) { return writer }
            val replaced = lines.map { raw ->
                if (raw.isBlank()) raw
                else try {
                    if (companionJsonLenient.decodeFromString<DetectionResult>(raw).id == updated.id) encodedLine
                    else raw
                } catch (_: Exception) { raw }
            }
            // Offenen Writer vor dem Ersetzen schliessen (sonst zeigt er auf die alte Datei)
            try { writer?.flush(); writer?.close() } catch (e: Exception) { onError(e) }
            try {
                writeFileReplacing(file, if (replaced.isEmpty()) "" else replaced.joinToString("\n") + "\n")
            } catch (e: Exception) {
                onError(e)
            }
            // Immer wieder einen gueltigen Append-Writer liefern
            return if (writer != null) java.io.FileWriter(file, true) else null
        }
    }

    /** T93: Serialisiert alle Schreib-/Rewrite-Zugriffe auf detections.jsonl und verifications.jsonl. */
    private val fileMutex = Mutex()

    private val json = Json { prettyPrint = false }
    private val jsonLenient = Json { ignoreUnknownKeys = true }

    // ── Aktive Session State ──────────────────────────────────────────

    /** Aktives Session-Verzeichnis (File-Modus) */
    private var activeSessionDir: File? = null
    /** Aktives Session-Verzeichnis (SAF-Modus) */
    private var activeSessionDoc: DocumentFile? = null
    /** SAF-URI fuer detections.jsonl (SAF-Modus) */
    private var activeDetectionsUri: Uri? = null
    /** Offene PFD fuer detections.jsonl Appends (SAF-Modus) */
    private var activeDetectionsFd: ParcelFileDescriptor? = null
    private var activeMetadata: SessionMetadata? = null
    private var detectionWriter: java.io.Writer? = null
    private var recordingWriter: StreamingWavWriter? = null
    private var detectionCounter: Int = 0

    // T57-B1: Rotations-State (pro Session initialisiert in startSession*)
    private var recordingSegmentIndex: Int = 0
    private var sessionSegmentStartSec: Float = 0f
    private val activeSegments = mutableListOf<RecordingSegment>()
    private var totalSamplesCompleted: Long = 0L

    // ── Session-ended Signal (T51: Analyse-Tab Auto-Refresh via Commit 8) ──

    private val _sessionEnded = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    /** Emittiert Unit nach jedem endSession()-Aufruf. Subscriber koennen loadSessions() aufrufen. */
    val sessionEnded: SharedFlow<Unit> = _sessionEnded.asSharedFlow()

    /** Ob gerade ein Session-Stop laeuft und der Speicherort nicht erreichbar war (Fallback). */
    var lastStartUsedFallback: Boolean = false
        private set

    /** Ob eine Session aktiv ist */
    val isActive: Boolean get() = activeSessionDir != null || activeSessionDoc != null

    /** T69: Aktives Session-Verzeichnis (File-Modus), lesbar fuer Memo-Pfad-Berechnung */
    val currentSessionDir: File? get() = activeSessionDir

    /** T69: Aktives Session-Dokument (SAF-Modus), lesbar fuer Memo-Pfad-Berechnung */
    val currentSessionDoc: DocumentFile? get() = activeSessionDoc

    // ── Storage-Aufloesung ────────────────────────────────────────────

    /**
     * Gibt das Basis-Verzeichnis zurueck (File-Modus).
     * Fuer SAF-Modus gibt es kein File-Basisverzeichnis — nutze resolveBaseDocFile().
     */
    private fun resolveBaseFile(): File {
        val safUri = appPreferences.storageBaseUri
        if (safUri != null) {
            // SAF-URI in Dateipfad umwandeln (nur primaerer Speicher)
            val resolved = safUriToFile(Uri.parse(safUri))
            if (resolved != null) return resolved
        }
        // Fallback: Legacy-Pfad oder getExternalFilesDir
        val legacyPath = appPreferences.storagePath
        val base = if (legacyPath != null) File(legacyPath) else (context.getExternalFilesDir(null) ?: context.filesDir)
        return File(base, PIROL_DIR)
    }

    /**
     * Gibt die DocumentFile-Basis fuer SAF-Schreiboperationen zurueck.
     * Nur relevant wenn storageBaseUri gesetzt ist.
     */
    private fun resolveBaseDocFile(): DocumentFile? {
        val safUri = appPreferences.storageBaseUri ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(safUri))
    }

    /**
     * Konvertiert eine SAF-Tree-URI in einen File-Pfad (nur primaerer Speicher).
     * Gibt null zurueck bei SD-Karte oder unbekanntem Schema.
     */
    private fun safUriToFile(uri: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            if (parts.size < 2) return null
            val storageType = parts[0]
            val relativePath = parts[1]
            if (storageType.equals("primary", ignoreCase = true)) {
                File(Environment.getExternalStorageDirectory(), relativePath)
            } else {
                null  // SD-Karte: kein direkter Pfad moeglich
            }
        } catch (e: Exception) {
            Log.w(TAG, "SAF URI → File-Pfad fehlgeschlagen: $uri", e)
            null
        }
    }

    /**
     * Gibt den Tages-Unterordner-Namen zurueck (YYYY-MM-DD, Lokalzeit)
     * oder null wenn der Toggle deaktiviert ist.
     */
    private fun dailySubdirName(): String? {
        if (!appPreferences.storageDailySubfolder) return null
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    /**
     * Findet oder erstellt ein DocumentFile-Unterverzeichnis.
     */
    private fun DocumentFile.findOrCreateDir(name: String): DocumentFile? {
        return findFile(name) ?: createDirectory(name)
    }

    // ── Session-Lifecycle ─────────────────────────────────────────────

    /**
     * Neue Session starten. Erstellt Ordnerstruktur + session.json.
     * Unterstuetzt SAF- und File-Modus.
     *
     * @param sampleRate Sample Rate der Aufnahme
     * @param latitude Startposition Breitengrad (kann null sein)
     * @param longitude Startposition Laengengrad (kann null sein)
     * @param regionFilter Aktiver Regionalfilter (z.B. "ch_breeding")
     * @param confidenceThreshold Aktuelle Confidence-Schwelle
     * @return Session-ID
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
        lastStartUsedFallback = false

        // Session-ID: ISO-Datum (UTC) + UUID-Prefix (unveraendert, CLAUDE.md)
        val nowUtc = LocalDateTime.now(ZoneOffset.UTC)
        val dateStr = nowUtc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
        val uuidShort = UUID.randomUUID().toString().take(6)
        val sessionId = "${dateStr}_${uuidShort}"

        val daySubdir = dailySubdirName()

        val metadata = SessionMetadata(
            sessionId = sessionId,
            startedAt = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            sampleRate = sampleRate,
            latitude = latitude,
            longitude = longitude,
            regionFilter = regionFilter,
            confidenceThreshold = confidenceThreshold
        )

        val safUri = appPreferences.storageBaseUri
        val baseDoc = if (safUri != null) resolveBaseDocFile() else null

        if (baseDoc != null && baseDoc.canWrite()) {
            // ── SAF-Modus ──────────────────────────────────────────────
            startSessionSaf(sessionId, daySubdir, metadata, sampleRate, baseDoc)
        } else {
            // ── File-Modus (inkl. SAF-Fallback wenn canWrite() false) ──
            if (safUri != null && baseDoc?.canWrite() == false) {
                Log.w(TAG, "SAF-URI nicht erreichbar — Fallback auf getExternalFilesDir")
                lastStartUsedFallback = true
            }
            startSessionFile(sessionId, daySubdir, metadata, sampleRate)
        }

        sessionId
    }

    private fun startSessionFile(
        sessionId: String,
        daySubdir: String?,
        metadata: SessionMetadata,
        sampleRate: Int
    ) {
        val base = resolveBaseFile()
        val parentDir = if (daySubdir != null) File(base, daySubdir) else base
        val sessionDir = File(parentDir, sessionId)
        sessionDir.mkdirs()

        // session.json schreiben
        val prettyJson = Json { prettyPrint = true }
        File(sessionDir, "session.json").writeText(prettyJson.encodeToString(metadata))

        // JSONL-Writer oeffnen (append-Modus)
        detectionWriter = FileWriter(File(sessionDir, "detections.jsonl"), true)

        // Recording-WAV oeffnen (File-basierter Streaming-Writer)
        val recordingFile = File(sessionDir, "recording.wav")
        recordingWriter = StreamingWavWriter.forFile(recordingFile, sampleRate).also { it.open() }

        activeSessionDir = sessionDir
        activeMetadata = metadata
        detectionCounter = 0
        recordingSegmentIndex = 0
        sessionSegmentStartSec = 0f
        activeSegments.clear()
        totalSamplesCompleted = 0L

        Log.i(TAG, "Session gestartet (File): $sessionId → ${sessionDir.absolutePath}")
    }

    private fun startSessionSaf(
        sessionId: String,
        daySubdir: String?,
        metadata: SessionMetadata,
        sampleRate: Int,
        baseDoc: DocumentFile
    ) {
        val parentDoc = if (daySubdir != null) baseDoc.findOrCreateDir(daySubdir) ?: baseDoc else baseDoc
        val sessionDoc = parentDoc.createDirectory(sessionId)
            ?: throw IllegalStateException("SAF: Session-Ordner konnte nicht erstellt werden: $sessionId")

        // session.json schreiben
        val jsonDocFile = sessionDoc.createFile("application/json", "session.json")
            ?: throw IllegalStateException("SAF: session.json konnte nicht erstellt werden")
        val prettyJson = Json { prettyPrint = true }
        context.contentResolver.openOutputStream(jsonDocFile.uri, "wt")?.use { os ->
            os.write(prettyJson.encodeToString(metadata).toByteArray(Charsets.UTF_8))
        }

        // detections.jsonl: Datei erstellen + im Append-Modus oeffnen
        val detectionsDocFile = sessionDoc.createFile("application/jsonlines", "detections.jsonl")
            ?: throw IllegalStateException("SAF: detections.jsonl konnte nicht erstellt werden")
        val detectionsFd = context.contentResolver.openFileDescriptor(detectionsDocFile.uri, "wa")
            ?: throw IllegalStateException("SAF: detections.jsonl nicht beschreibbar")
        val detectionsWriter = OutputStreamWriter(
            java.io.FileOutputStream(detectionsFd.fileDescriptor), Charsets.UTF_8
        )

        // recording.wav: Datei erstellen + SAF-Writer
        val wavDocFile = sessionDoc.createFile("audio/wav", "recording.wav")
            ?: throw IllegalStateException("SAF: recording.wav konnte nicht erstellt werden")
        val wavWriter = StreamingWavWriter.forSaf(context, wavDocFile.uri, sampleRate).also { it.open() }

        activeSessionDoc = sessionDoc
        activeDetectionsUri = detectionsDocFile.uri
        activeDetectionsFd = detectionsFd
        detectionWriter = detectionsWriter
        recordingWriter = wavWriter
        activeMetadata = metadata
        detectionCounter = 0
        recordingSegmentIndex = 0
        sessionSegmentStartSec = 0f
        activeSegments.clear()
        totalSamplesCompleted = 0L

        Log.i(TAG, "Session gestartet (SAF): $sessionId → ${sessionDoc.uri}")
    }

    /**
     * Detektionen an JSONL anhaengen.
     * Wird vom LiveViewModel nach jeder Inference aufgerufen.
     */
    suspend fun appendDetections(detections: List<DetectionResult>) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val writer = detectionWriter ?: return@withLock
            for (detection in detections) {
                val line = json.encodeToString(detection)
                writer.append(line)
                writer.append('\n')
                detectionCounter++
            }
            writer.flush()
        }
    }

    /**
     * Schreibt ein Verifikations-Event in verifications.jsonl.
     * Separate Datei von detections.jsonl (append-only, crash-sicher).
     */
    suspend fun appendVerification(event: VerificationEvent) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val line = json.encodeToString(event) + "\n"
            val sessionDoc = activeSessionDoc
            if (sessionDoc != null) {
                // SAF-Modus: verifications.jsonl via ContentResolver appendieren
                val veriFile = sessionDoc.findFile("verifications.jsonl")
                    ?: sessionDoc.createFile("application/jsonlines", "verifications.jsonl")
                veriFile?.let { docFile ->
                    context.contentResolver.openOutputStream(docFile.uri, "wa")?.use { os ->
                        os.write(line.toByteArray(Charsets.UTF_8))
                    }
                }
            } else {
                // File-Modus
                val sessionDir = activeSessionDir ?: return@withLock
                File(sessionDir, "verifications.jsonl").appendText(line)
            }
        }
    }

    /**
     * Entfernt den letzten Verifikations-Eintrag fuer eine Detection-ID aus verifications.jsonl.
     * Idempotent: kein Fehler falls kein Eintrag fuer die ID vorhanden.
     * Wird fuer Undo im Live-Tab verwendet (T52).
     */
    suspend fun removeLastVerification(detectionId: String) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val sessionDoc = activeSessionDoc
            if (sessionDoc != null) {
                // SAF-Modus
                val veriFile = sessionDoc.findFile("verifications.jsonl") ?: return@withLock
                val lines = try {
                    context.contentResolver.openInputStream(veriFile.uri)?.use {
                        it.bufferedReader().readLines()
                    } ?: return@withLock
                } catch (_: Exception) { return@withLock }
                val lastMatchIdx = lines.indexOfLast { line ->
                    line.isNotBlank() && try {
                        jsonLenient.decodeFromString<VerificationEvent>(line).detectionId == detectionId
                    } catch (_: Exception) { false }
                }
                if (lastMatchIdx < 0) return@withLock
                val filtered = lines.filterIndexed { idx, _ -> idx != lastMatchIdx }
                try {
                    context.contentResolver.openOutputStream(veriFile.uri, "wt")?.use { os ->
                        val content = if (filtered.isEmpty()) "" else filtered.joinToString("\n") + "\n"
                        os.write(content.toByteArray(Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "removeLastVerification SAF fehlgeschlagen: $detectionId", e)
                }
            } else {
                // File-Modus
                val sessionDir = activeSessionDir ?: return@withLock
                val file = File(sessionDir, "verifications.jsonl")
                if (!file.exists()) return@withLock
                val lines = try { file.readLines() } catch (_: Exception) { return@withLock }
                val lastMatchIdx = lines.indexOfLast { line ->
                    line.isNotBlank() && try {
                        jsonLenient.decodeFromString<VerificationEvent>(line).detectionId == detectionId
                    } catch (_: Exception) { false }
                }
                if (lastMatchIdx < 0) return@withLock
                val filtered = lines.filterIndexed { idx, _ -> idx != lastMatchIdx }
                try {
                    val content = if (filtered.isEmpty()) "" else filtered.joinToString("\n") + "\n"
                    writeFileReplacing(file, content)  // T93: renameTo ersetzt unter Windows nicht
                } catch (e: Exception) {
                    Log.e(TAG, "removeLastVerification fehlgeschlagen: $detectionId", e)
                }
            }
        }
    }

    /**
     * Ersetzt eine Detektion in detections.jsonl durch die aktualisierte Version.
     * File-Modus: atomar via temp-File + Files.move (Writer wird dabei geschlossen und neu geoeffnet).
     * SAF-Modus: "wt"-Rewrite (kein atomarer rename moeglich via ContentResolver).
     * Silent no-op falls die Session-ID nicht gefunden wird.
     */
    suspend fun replaceDetection(updated: DetectionResult) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val encodedLine = json.encodeToString(updated) + "\n"
            val sessionDoc = activeSessionDoc
            if (sessionDoc != null) {
                // SAF-Modus
                val detFile = sessionDoc.findFile("detections.jsonl") ?: return@withLock
                val lines = try {
                    context.contentResolver.openInputStream(detFile.uri)?.use {
                        it.bufferedReader().readLines()
                    } ?: return@withLock
                } catch (_: Exception) { return@withLock }
                val replaced = lines.map { raw ->
                    if (raw.isBlank()) raw
                    else try {
                        if (jsonLenient.decodeFromString<DetectionResult>(raw).id == updated.id) encodedLine.trimEnd('\n')
                        else raw
                    } catch (_: Exception) { raw }
                }
                // T93: offenen Append-Writer + PFD vor dem "wt"-Rewrite schliessen ...
                try { detectionWriter?.close() } catch (e: Exception) { Log.e(TAG, "Detection-Writer close failed", e) }
                detectionWriter = null
                try { activeDetectionsFd?.close() } catch (e: Exception) { Log.e(TAG, "Detections-PFD close failed", e) }
                activeDetectionsFd = null
                try {
                    context.contentResolver.openOutputStream(detFile.uri, "wt")?.use { os ->
                        val content = if (replaced.isEmpty()) "" else replaced.joinToString("\n") + "\n"
                        os.write(content.toByteArray(Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "replaceDetection SAF fehlgeschlagen: ${updated.id}", e)
                } finally {
                    // ... und danach IMMER wieder im Append-Modus oeffnen
                    try {
                        val fd = context.contentResolver.openFileDescriptor(detFile.uri, "wa")
                        if (fd != null) {
                            activeDetectionsFd = fd
                            detectionWriter = OutputStreamWriter(
                                java.io.FileOutputStream(fd.fileDescriptor), Charsets.UTF_8
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "detections.jsonl konnte nicht neu geoeffnet werden", e)
                    }
                }
            } else {
                // File-Modus: Writer schliessen, atomar ersetzen, Writer neu oeffnen (T93)
                val sessionDir = activeSessionDir ?: return@withLock
                val file = File(sessionDir, "detections.jsonl")
                detectionWriter = replaceDetectionInFile(file, updated, detectionWriter) { e ->
                    Log.e(TAG, "replaceDetection fehlgeschlagen: ${updated.id}", e)
                }
            }
        }
    }

    // ── T69: Voice-Memo Pfad-Helpers ─────────────────────────────────

    /**
     * Gibt den File-Pfad fuer ein Sprach-Memo zurueck (File-Modus).
     * Erstellt das memos/-Verzeichnis falls noetig.
     *
     * @param sessionDir Session-Verzeichnis (File-Modus)
     * @param detectionId ID der Detektion
     * @return File-Handle fuer <sessionDir>/memos/<detectionId>.wav
     */
    fun memoFile(sessionDir: File, detectionId: String): File =
        File(sessionDir, "memos/$detectionId.wav").apply { parentFile?.mkdirs() }

    /**
     * Gibt das DocumentFile fuer ein Sprach-Memo zurueck (SAF-Modus).
     * Erstellt den memos/-Ordner und die WAV-Datei falls noetig.
     *
     * @param sessionDoc Session-DocumentFile (SAF-Modus)
     * @param detectionId ID der Detektion
     * @return DocumentFile oder null bei Fehler
     */
    fun memoDocFile(sessionDoc: DocumentFile, detectionId: String): DocumentFile? {
        val memos = sessionDoc.findFile("memos")
            ?: sessionDoc.createDirectory("memos") ?: return null
        return memos.findFile("$detectionId.wav")
            ?: memos.createFile("audio/wav", "$detectionId.wav")
    }

    /**
     * Audio-Samples an recording.wav anhaengen.
     * Wird vom LiveViewModel fuer jeden Chunk aufgerufen (Daueraufnahme, T51).
     *
     * @param samples Audio-Daten als ShortArray (16-bit PCM)
     */
    suspend fun appendAudioSamples(samples: ShortArray) = withContext(Dispatchers.IO) {
        recordingWriter?.append(samples)
        // T57-B1: Rotation auf Chunk-Grenze pruefen
        val sampleRate = activeMetadata?.sampleRate?.toFloat() ?: return@withContext
        val maxSec = appPreferences.maxRecordingMinutes * 60f
        val writerSec = (recordingWriter?.sampleCount ?: 0L) / sampleRate
        if (writerSec >= maxSec) {
            rotateRecordingSync()
        }
    }

    /**
     * Aktueller Schreib-Offset in recording.wav in Sekunden.
     * Wird VOR appendAudioSamples() aufgerufen fuer korrekte Detection-Zeitstempel.
     *
     * @return Offset in Sekunden, oder 0f wenn keine Session aktiv
     */
    fun getCurrentRecordingOffsetSec(): Float {
        val writer = recordingWriter ?: return 0f
        val sampleRate = activeMetadata?.sampleRate ?: return 0f
        return writer.sampleCount.toFloat() / sampleRate.toFloat()
    }

    /**
     * Aktive Session beenden. Schliesst WAV-Writer + Writer, aktualisiert session.json.
     * Schreibt automatisch recording.selections.txt (Auto-Raven-Export, T51).
     *
     * @param gpsStats GPS-Statistiken der Session (T53). Null wenn GPS nicht verwendet wurde.
     */
    suspend fun endSession(gpsStats: GpsStats? = null) = withContext(Dispatchers.IO) {
        val dir = activeSessionDir
        val doc = activeSessionDoc
        val metadata = activeMetadata ?: return@withContext

        // T57-B1: Letztes Segment abschliessen und Gesamt-Samples summieren
        val writer = recordingWriter
        if (writer != null) {
            val sampleRate = metadata.sampleRate.toFloat()
            val durationSec = writer.sampleCount.toFloat() / sampleRate
            activeSegments.add(RecordingSegment(segmentFileName(recordingSegmentIndex), sessionSegmentStartSec, durationSec))
            totalSamplesCompleted += writer.sampleCount
        }
        val recordedSamples = totalSamplesCompleted

        // WAV-Writer schliessen
        try { recordingWriter?.close() } catch (e: Exception) { Log.e(TAG, "WAV close failed", e) }
        recordingWriter = null

        // JSONL-Writer schliessen
        fileMutex.withLock {  // T93: nicht parallel zu append/replace schliessen
            try { detectionWriter?.close() } catch (e: Exception) { Log.e(TAG, "Detection-Writer close failed", e) }
            detectionWriter = null
            try { activeDetectionsFd?.close() } catch (e: Exception) { Log.e(TAG, "Detections-PFD close failed", e) }
            activeDetectionsFd = null
        }

        // session.json aktualisieren (endedAt, Zaehler, gpsStats)
        val updatedMetadata = metadata.copy(
            endedAt = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            totalRecordedSamples = recordedSamples,
            totalDetections = detectionCounter,
            gpsStats = gpsStats,  // T53: GPS-Statistiken
            recordingSegments = if (activeSegments.size > 1) activeSegments.toList() else null  // T57-B1: null bei Single-File
        )
        val prettyJson = Json { prettyPrint = true }
        val updatedJson = prettyJson.encodeToString(updatedMetadata)

        if (doc != null) {
            // SAF-Modus: session.json ueberschreiben
            val jsonFile = doc.findFile("session.json")
            jsonFile?.let { context.contentResolver.openOutputStream(it.uri, "wt")?.use { os ->
                os.write(updatedJson.toByteArray(Charsets.UTF_8))
            }}
        } else if (dir != null) {
            File(dir, "session.json").writeText(updatedJson)
        }

        Log.i(TAG, "Session beendet: ${metadata.sessionId} ($recordedSamples samples, $detectionCounter Detektionen)")

        // Auto-Raven-Export (T51): recording.selections.txt schreiben
        // Detektionen werden direkt aus dem geschlossenen Writer-Stand gelesen
        val sessionDirForExport = dir ?: doc?.let { resolveSessionDirFromDoc(it) }
        if (sessionDirForExport != null) {
            try {
                val detections = loadDetectionsWithVerifications(sessionDirForExport)
                val selectionsFile = File(sessionDirForExport, "recording.selections.txt")
                // T57-B3: sessionStartedAt + recordingSegments fuer Begin Date Time + Begin File
                // T78: Felder-Selektion via AppPreferences
                val autoEnabledFields: Set<String> = buildSet {
                    if (appPreferences.ravenFieldBeginFile)      add(RavenExporter.FIELD_BEGIN_FILE)
                    if (appPreferences.ravenFieldBeginTime)      add(RavenExporter.FIELD_BEGIN_TIME)
                    if (appPreferences.ravenFieldEndTime)        add(RavenExporter.FIELD_END_TIME)
                    if (appPreferences.ravenFieldLowFreq)        add(RavenExporter.FIELD_LOW_FREQ)
                    if (appPreferences.ravenFieldHighFreq)       add(RavenExporter.FIELD_HIGH_FREQ)
                    if (appPreferences.ravenFieldBeginDateTime)  add(RavenExporter.FIELD_BEGIN_DATE_TIME)
                    if (appPreferences.ravenFieldSpecies)        add(RavenExporter.FIELD_SPECIES)
                    if (appPreferences.ravenFieldConfidence)     add(RavenExporter.FIELD_CONFIDENCE)
                    if (appPreferences.ravenFieldLat)            add(RavenExporter.FIELD_LAT)
                    if (appPreferences.ravenFieldLon)            add(RavenExporter.FIELD_LON)
                }
                RavenExporter.export(selectionsFile, detections, metadata.startedAt, updatedMetadata.recordingSegments, autoEnabledFields)
                Log.i(TAG, "Auto-Raven-Export: ${selectionsFile.name} (${detections.size} Detektionen)")
            } catch (e: Exception) {
                Log.e(TAG, "Auto-Raven-Export fehlgeschlagen", e)
            }
        }

        // Auto-Delete kurzer leerer Sessions (T60)
        val autoDeleteEnabled = appPreferences.autoDeleteShortEmptySessions
        val minSec = appPreferences.autoDeleteMinSeconds
        val durationSec = if (updatedMetadata.sampleRate > 0)
            updatedMetadata.totalRecordedSamples.toDouble() / updatedMetadata.sampleRate
        else 0.0
        if (shouldAutoDelete(durationSec, updatedMetadata.totalDetections, minSec, autoDeleteEnabled)) {
            Log.i(TAG, "Auto-Delete: Session=${updatedMetadata.sessionId} " +
                "dauer=${"%.1f".format(durationSec)}s detections=0 < ${minSec}s")
            deleteSessionFolder(dir, doc)
        }

        // State zuruecksetzen
        activeSessionDir = null
        activeSessionDoc = null
        activeDetectionsUri = null
        activeMetadata = null
        detectionCounter = 0
        recordingSegmentIndex = 0
        sessionSegmentStartSec = 0f
        activeSegments.clear()
        totalSamplesCompleted = 0L

        // Signal fuer AnalysisViewModel (T51, Commit 8)
        _sessionEnded.tryEmit(Unit)
    }

    /**
     * Leitet einen DocumentFile-Session-Ordner zu einem File-Pfad auf (primaerer Speicher).
     * Nur fuer Auto-Raven-Export nach endSession() benoetigt.
     */
    private fun resolveSessionDirFromDoc(sessionDoc: DocumentFile): File? {
        val safUri = appPreferences.storageBaseUri ?: return null
        val baseFile = safUriToFile(Uri.parse(safUri)) ?: return null
        // Session-Name aus DocumentFile-Name extrahieren
        val sessionName = sessionDoc.name ?: return null
        // Alle Tagesdirs durchsuchen
        val allDirs = mutableListOf<File>()
        if (appPreferences.storageDailySubfolder) {
            baseFile.listFiles()?.forEach { dayDir ->
                if (dayDir.isDirectory) allDirs.add(File(dayDir, sessionName))
            }
        } else {
            allDirs.add(File(baseFile, sessionName))
        }
        return allDirs.firstOrNull { it.exists() }
    }

    // ── WAV-Rotation (T57-B1) ─────────────────────────────────────────

    /** Dateiname fuer Segment-Index: 0 → "recording.wav", 1 → "recording-002.wav", ... */
    private fun segmentFileName(index: Int): String =
        if (index == 0) "recording.wav" else "recording-${String.format("%03d", index + 1)}.wav"

    /**
     * Schliesst den aktuellen WAV-Writer, speichert das abgeschlossene Segment,
     * und oeffnet eine neue WAV-Datei. Laeuft auf Dispatchers.IO (kein suspend noetig).
     */
    private fun rotateRecordingSync() {
        val meta = activeMetadata ?: return
        val writer = recordingWriter ?: return
        val sampleRate = meta.sampleRate.toFloat()

        // Aktuelles Segment abschliessen
        val durationSec = writer.sampleCount.toFloat() / sampleRate
        activeSegments.add(RecordingSegment(segmentFileName(recordingSegmentIndex), sessionSegmentStartSec, durationSec))
        totalSamplesCompleted += writer.sampleCount
        try { writer.close() } catch (e: Exception) { Log.e(TAG, "WAV rotate close failed", e) }

        // Naechstes Segment starten
        recordingSegmentIndex++
        sessionSegmentStartSec += durationSec
        val newName = segmentFileName(recordingSegmentIndex)
        val dir = activeSessionDir
        val doc = activeSessionDoc
        recordingWriter = when {
            dir != null -> StreamingWavWriter.forFile(File(dir, newName), meta.sampleRate).also { it.open() }
            doc != null -> {
                val wavDoc = doc.createFile("audio/wav", newName)
                    ?: throw IllegalStateException("SAF: $newName konnte nicht erstellt werden")
                StreamingWavWriter.forSaf(context, wavDoc.uri, meta.sampleRate).also { it.open() }
            }
            else -> { Log.e(TAG, "rotateRecordingSync: kein aktives Verzeichnis"); return }
        }
        Log.i(TAG, "WAV-Rotation: Segment $recordingSegmentIndex gestartet → $newName")
    }

    // ── Listierungs- und Lese-Methoden ────────────────────────────────

    /**
     * Gibt alle vorhandenen Session-Ordner zurueck (neueste zuerst).
     * Scannt neue Struktur (PIROL/YYYY-MM-DD/sessionId) + alte Sessions fuer Rueckwaertskompatibilitaet.
     */
    fun listSessions(): List<File> {
        val results = mutableListOf<File>()

        // 1. Neue Struktur: [base]/PIROL/YYYY-MM-DD/{sessionId}/
        val base = resolveBaseFile()
        if (base.exists()) {
            if (appPreferences.storageDailySubfolder || base.listFiles()?.any { it.isDirectory && looksLikeDateDir(it.name) } == true) {
                // Mit Tages-Unterordnern: tief scannen
                base.listFiles()?.forEach { dayDir ->
                    if (dayDir.isDirectory) {
                        dayDir.listFiles()?.filter { it.isDirectory }?.let { results.addAll(it) }
                    }
                }
            }
            // Direkt im base liegende Session-Dirs (kein Tages-Unterordner)
            base.listFiles()?.filter { it.isDirectory && !looksLikeDateDir(it.name) }
                ?.let { results.addAll(it) }
        }

        // 2. Legacy-Pfad: [oldBase]/sessions/{sessionId}/ (pre-T51)
        addLegacySessions(results)

        // Duplikate entfernen (nach absolutePath), sortieren (neueste zuerst nach Session-ID)
        return results.distinctBy { it.absolutePath }
            .sortedByDescending { it.name }
    }

    private fun looksLikeDateDir(name: String): Boolean {
        return name.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
    }

    private fun addLegacySessions(results: MutableList<File>) {
        // Interner Speicher (pre-T51 default)
        val internalLegacy = File(context.filesDir, LEGACY_SESSIONS_DIR)
        internalLegacy.listFiles()?.filter { it.isDirectory }?.let { results.addAll(it) }

        // Externer legacy-Pfad (SD-Karte, T38)
        val legacyPath = appPreferences.storagePath
        if (legacyPath != null) {
            val externalLegacy = File(legacyPath, LEGACY_SESSIONS_DIR)
            externalLegacy.listFiles()?.filter { it.isDirectory }?.let { results.addAll(it) }
        }
    }

    /** Loescht Session-Folder im File- oder SAF-Modus (T60: Auto-Delete). */
    internal fun deleteSessionFolder(dir: File?, doc: DocumentFile?) {
        if (doc != null) {
            val ok = doc.delete()
            Log.i(TAG, "SAF Session-Folder geloescht (ok=$ok): ${doc.uri}")
        } else if (dir != null) {
            val ok = dir.deleteRecursively()
            Log.i(TAG, "File Session-Folder geloescht (ok=$ok): ${dir.name}")
        }
    }

    /** Loescht eine Session (Ordner + alle Dateien) */
    suspend fun deleteSession(sessionDir: File): Boolean = withContext(Dispatchers.IO) {
        if (!sessionDir.exists()) return@withContext false
        val deleted = sessionDir.deleteRecursively()
        Log.i(TAG, "Session geloescht: ${sessionDir.name} (erfolg=$deleted)")
        deleted
    }

    /**
     * T65: Crash-Recovery — finalisiert verwaiste Sessions (endedAt == null) beim App-Start.
     *
     * Fuer jede Session ohne endedAt:
     * - WAV-Header wird via StreamingWavWriter.finalizeHeader() repariert
     * - session.json erhaelt endedAt = last-modified-Zeit der WAV-Datei
     * - Session bleibt in der Liste sichtbar (kein stiller Skip, AC6)
     *
     * @return Anzahl reparierter Sessions
     */
    suspend fun recoverOrphanSessions(): Int = withContext(Dispatchers.IO) {
        var recovered = 0
        listSessions().forEach { sessionDir ->
            val jsonFile = File(sessionDir, "session.json")
            if (!jsonFile.exists()) return@forEach
            val metadata = try {
                jsonLenient.decodeFromString<SessionMetadata>(jsonFile.readText())
            } catch (e: Exception) {
                Log.w(TAG, "recoverOrphan: session.json nicht lesbar: ${sessionDir.name}", e)
                return@forEach
            }
            if (metadata.endedAt != null) return@forEach  // Normal beendet — nichts zu tun

            // Verwaiste Session gefunden
            Log.i(TAG, "recoverOrphan: verwaiste Session gefunden: ${sessionDir.name}")

            // WAV-Header finalisieren (alle Segment-Files)
            val wavFiles = sessionDir.listFiles { f -> f.name.endsWith(".wav") } ?: emptyArray()
            for (wav in wavFiles) {
                try {
                    StreamingWavWriter.finalizeHeader(wav)
                    Log.i(TAG, "recoverOrphan: WAV-Header repariert: ${wav.name} (${wav.length()} bytes)")
                } catch (e: Exception) {
                    Log.e(TAG, "recoverOrphan: WAV-Header Fehler: ${wav.name}", e)
                }
            }

            // session.json: endedAt = last-modified der neuesten WAV (oder json selbst)
            val refFile = wavFiles.maxByOrNull { it.lastModified() } ?: jsonFile
            val endedAt = java.time.Instant.ofEpochMilli(refFile.lastModified())
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val recoveredMeta = metadata.copy(endedAt = endedAt)
            try {
                val prettyJson = Json { prettyPrint = true }
                jsonFile.writeText(prettyJson.encodeToString(recoveredMeta))
                Log.i(TAG, "recoverOrphan: session.json repariert: ${sessionDir.name} → endedAt=$endedAt")
                recovered++
            } catch (e: Exception) {
                Log.e(TAG, "recoverOrphan: session.json Schreiben fehlgeschlagen: ${sessionDir.name}", e)
            }
        }
        recovered
    }

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
     * T85: Laedt alle Simultanlink-Events aus verifications.jsonl und baut eine
     * Map von DetectionId → SimultaneousLinkSummary (simultaneous / probable / sameIndividual).
     */
    private suspend fun loadSimultaneousLinkMap(
        sessionDir: File
    ): Map<String, RavenExporter.SimultaneousLinkSummary> = withContext(Dispatchers.IO) {
        val veriFile = File(sessionDir, "verifications.jsonl")
        if (!veriFile.exists()) return@withContext emptyMap()

        val map = mutableMapOf<String, RavenExporter.SimultaneousLinkSummary>()
        veriFile.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            val event = runCatching {
                jsonLenient.decodeFromString<VerificationEvent>(line)
            }.getOrNull() ?: return@forEachLine

            val otherId = event.newValue ?: return@forEachLine
            val detId = event.detectionId
            val existing = map.getOrDefault(detId, RavenExporter.SimultaneousLinkSummary())
            map[detId] = when (event.actionType) {
                VerificationEvent.ACTION_SIMULTANEOUS_LINK ->
                    existing.copy(simultaneous = existing.simultaneous + otherId)
                VerificationEvent.ACTION_PROBABLE_LINK ->
                    existing.copy(probable = existing.probable + otherId)
                VerificationEvent.ACTION_SAME_INDIVIDUAL ->
                    existing.copy(sameIndividual = existing.sameIndividual + otherId)
                else -> existing
            }
        }
        map
    }

    /**
     * Gibt die Recording-Datei einer Session zurueck.
     * Prueft zuerst flache Struktur (T51), dann Legacy-Pfad audio/ (pre-T51).
     */
    fun getRecordingFile(sessionDir: File): File? {
        // Neue flache Struktur (T51)
        val flat = File(sessionDir, "recording.wav")
        if (flat.exists()) return flat
        // Legacy: audio/recording.wav (pre-T51)
        val legacy = File(sessionDir, "audio/recording.wav")
        return if (legacy.exists()) legacy else null
    }

    /**
     * Repariert defekte WAV-Header aller Aufnahme-Segmente einer Session (T61).
     * Betrifft Aufnahmen aus V0.0.6 (siehe [WavWriter.repairHeaderIfNeeded]).
     * Dateien mit gueltigem Header bleiben unveraendert.
     *
     * @return Anzahl reparierter Dateien
     */
    suspend fun repairRecordingHeaders(sessionDir: File): Int = withContext(Dispatchers.IO) {
        val sampleRate = loadMetadata(sessionDir)?.sampleRate ?: return@withContext 0
        val candidates = (sessionDir.listFiles { f ->
            f.isFile && f.name.startsWith("recording") && f.name.endsWith(".wav")
        }?.toList() ?: emptyList()) + listOfNotNull(File(sessionDir, "audio/recording.wav").takeIf { it.exists() })
        var repaired = 0
        for (file in candidates) {
            try {
                if (WavWriter.repairHeaderIfNeeded(file, sampleRate)) {
                    repaired++
                    Log.w(TAG, "WAV-Header repariert: ${sessionDir.name}/${file.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "WAV-Header-Reparatur fehlgeschlagen: ${file.absolutePath}", e)
            }
        }
        repaired
    }

    /**
     * Schreibt eine Raven Selection Table neben recording.wav (fuer manuellen Re-Export).
     * Laedt session.json fuer startedAt + recordingSegments (T57-B3).
     * T78: appPreferences optional — steuert welche Felder exportiert werden.
     * @return Pfad zur erzeugten Datei oder null wenn keine Aufnahme vorhanden
     */
    suspend fun exportRavenSelectionTable(
        sessionDir: File,
        appPreferences: AppPreferences? = null
    ): File? = withContext(Dispatchers.IO) {
        getRecordingFile(sessionDir) ?: return@withContext null
        val outputFile = File(sessionDir, "recording.selections.txt")
        val detections = loadDetectionsWithVerifications(sessionDir)
        // Metadaten fuer startedAt + recordingSegments laden (T57-B3)
        val meta = runCatching {
            val jsonText = File(sessionDir, "session.json").readText()
            jsonLenient.decodeFromString<SessionMetadata>(jsonText)
        }.getOrNull()
        val startedAt = meta?.startedAt ?: "1970-01-01T00:00:00+00:00"
        // T78: enabledFields aus AppPreferences bauen; null = alle Felder (Fallback)
        val enabledFields: Set<String>? = appPreferences?.let { p ->
            buildSet {
                if (p.ravenFieldBeginFile)      add(RavenExporter.FIELD_BEGIN_FILE)
                if (p.ravenFieldBeginTime)      add(RavenExporter.FIELD_BEGIN_TIME)
                if (p.ravenFieldEndTime)        add(RavenExporter.FIELD_END_TIME)
                if (p.ravenFieldLowFreq)        add(RavenExporter.FIELD_LOW_FREQ)
                if (p.ravenFieldHighFreq)       add(RavenExporter.FIELD_HIGH_FREQ)
                if (p.ravenFieldBeginDateTime)  add(RavenExporter.FIELD_BEGIN_DATE_TIME)
                if (p.ravenFieldSpecies)        add(RavenExporter.FIELD_SPECIES)
                if (p.ravenFieldConfidence)     add(RavenExporter.FIELD_CONFIDENCE)
                if (p.ravenFieldLat)            add(RavenExporter.FIELD_LAT)
                if (p.ravenFieldLon)            add(RavenExporter.FIELD_LON)
                if (p.ravenFieldSimultan)       add(RavenExporter.FIELD_SIMULTAN)
                if (p.ravenFieldMoeglich)       add(RavenExporter.FIELD_MOEGLICH)
                if (p.ravenFieldGleich)         add(RavenExporter.FIELD_GLEICH)
            }
        }
        val linkMap = loadSimultaneousLinkMap(sessionDir)
        RavenExporter.export(outputFile, detections, startedAt, meta?.recordingSegments, enabledFields, linkMap)
        outputFile
    }

    // ── Migrations-Methoden (T41, erweitert T51) ──────────────────────

    /**
     * Zaehlt Sessions und berechnet Gesamtgroesse am aktuellen Speicherort.
     */
    fun getMigrationInfo(): MigrationInfo {
        val sessions = listSessions()
        val totalSize = sessions.sumOf { dir ->
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        return MigrationInfo(sessions.size, totalSize)
    }

    /**
     * Kopiert alle Sessions zum File-basierten Zielort (z.B. filesDir).
     * - Duplikate werden uebersprungen (Idempotenz, AC6)
     * - deleteSource: Quell-Dir erst loeschen nach Groessen-Verifizierung (AC7)
     */
    suspend fun migrateSessionsTo(
        targetBaseDir: File,
        deleteSource: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): MigrationResult = withContext(Dispatchers.IO) {
        val sessionDirs = listSessions()
        if (sessionDirs.isEmpty()) return@withContext MigrationResult(0, 0L, emptyList())

        var copied = 0
        var bytesCopied = 0L
        val errors = mutableListOf<String>()

        sessionDirs.forEachIndexed { index, sessionDir ->
            val targetDir = File(targetBaseDir, sessionDir.name)
            if (targetDir.exists()) {
                Log.i(TAG, "Session ${sessionDir.name} existiert am Ziel — uebersprungen")
                onProgress((index + 1).toFloat() / sessionDirs.size)
                return@forEachIndexed
            }
            try {
                val success = sessionDir.copyRecursively(targetDir, overwrite = false)
                if (success) {
                    val srcSize = sessionDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    val dstSize = targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    if (srcSize == dstSize) {
                        bytesCopied += dstSize
                        copied++
                        if (deleteSource) sessionDir.deleteRecursively()
                        Log.i(TAG, "Session kopiert: ${sessionDir.name}")
                    } else {
                        errors.add("${sessionDir.name}: Grössenabweichung ($dstSize != $srcSize)")
                        targetDir.deleteRecursively()
                    }
                } else {
                    errors.add("${sessionDir.name}: Kopieren fehlgeschlagen")
                    targetDir.deleteRecursively()
                }
            } catch (e: Exception) {
                errors.add("${sessionDir.name}: ${e.message}")
                targetDir.deleteRecursively()
                Log.e(TAG, "Session-Migration fehlgeschlagen: ${sessionDir.name}", e)
            }
            onProgress((index + 1).toFloat() / sessionDirs.size)
        }

        MigrationResult(copied, bytesCopied, errors)
    }

    /**
     * Kopiert alle Sessions in einen SAF-Zielordner via ContentResolver (AC1/AC2).
     * Funktioniert fuer beliebige SAF-URIs inkl. SD-Karte und nicht-primaere Speicher.
     * - Duplikate werden uebersprungen (Idempotenz, AC6)
     * - deleteSource: Quell-Dir erst loeschen nach Groessen-Verifizierung (AC7)
     */
    suspend fun migrateSessionsTo(
        targetUri: Uri,
        deleteSource: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): MigrationResult = withContext(Dispatchers.IO) {
        val targetRoot = DocumentFile.fromTreeUri(context, targetUri)
            ?: return@withContext MigrationResult(0, 0L, listOf("Zielordner nicht erreichbar"))

        val sessionDirs = listSessions()
        if (sessionDirs.isEmpty()) return@withContext MigrationResult(0, 0L, emptyList())

        var copied = 0
        var bytesCopied = 0L
        val errors = mutableListOf<String>()
        val cr = context.contentResolver

        sessionDirs.forEachIndexed { index, sessionDir ->
            if (targetRoot.findFile(sessionDir.name)?.isDirectory == true) {
                Log.i(TAG, "Session ${sessionDir.name} existiert am Ziel — uebersprungen")
                onProgress((index + 1).toFloat() / sessionDirs.size)
                return@forEachIndexed
            }

            val targetDir = targetRoot.createDirectory(sessionDir.name)
            if (targetDir == null) {
                errors.add("${sessionDir.name}: Verzeichnis nicht erstellbar")
                onProgress((index + 1).toFloat() / sessionDirs.size)
                return@forEachIndexed
            }

            try {
                val sessionErrors = mutableListOf<String>()
                var sessionBytes = 0L
                copyDirContentsToDocFile(sessionDir, targetDir, cr, sessionErrors) { bytes -> sessionBytes += bytes }

                if (sessionErrors.isEmpty()) {
                    bytesCopied += sessionBytes
                    copied++
                    if (deleteSource) sessionDir.deleteRecursively()
                    Log.i(TAG, "Session migriert: ${sessionDir.name}")
                } else {
                    errors.addAll(sessionErrors)
                    targetDir.delete()
                }
            } catch (e: Exception) {
                errors.add("${sessionDir.name}: ${e.message}")
                targetDir.delete()
                Log.e(TAG, "Migration fehlgeschlagen: ${sessionDir.name}", e)
            }
            onProgress((index + 1).toFloat() / sessionDirs.size)
        }

        MigrationResult(copied, bytesCopied, errors)
    }

    private fun copyDirContentsToDocFile(
        srcDir: File,
        destDir: DocumentFile,
        cr: android.content.ContentResolver,
        errors: MutableList<String>,
        onBytes: (Long) -> Unit
    ) {
        for (child in srcDir.listFiles() ?: emptyArray()) {
            if (child.isDirectory) {
                val subDest = destDir.createDirectory(child.name)
                if (subDest == null) { errors.add("${child.path}: Ordner nicht erstellbar"); return }
                copyDirContentsToDocFile(child, subDest, cr, errors, onBytes)
            } else {
                val destFile = destDir.createFile("application/octet-stream", child.name)
                if (destFile == null) { errors.add("${child.path}: Datei nicht erstellbar"); return }
                try {
                    cr.openOutputStream(destFile.uri)?.use { out ->
                        child.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    val destSize = cr.openFileDescriptor(destFile.uri, "r")?.use { it.statSize } ?: -1L
                    if (destSize != child.length()) {
                        errors.add("${child.path}: Grössenabweichung ($destSize != ${child.length()})")
                        destFile.delete()
                    } else {
                        onBytes(destSize)
                    }
                } catch (e: Exception) {
                    errors.add("${child.path}: ${e.message}")
                    destFile.delete()
                }
            }
        }
    }

    /**
     * Schreibt ein POSITION_MOVED-Event fuer eine historische Detektion in die
     * verifications.jsonl der betreffenden Session.
     * Wird von MapViewModel.confirmPositionMove() aufgerufen (T79).
     *
     * @param sessionId   ID der Session (aus SessionMetadata.sessionId)
     * @param detectionId ID der Detektion (aus DetectionResult.id)
     * @param newLat      Neuer Breitengrad (WGS84)
     * @param newLon      Neuer Laengengrad (WGS84)
     */
    suspend fun recordPositionMoved(
        sessionId: String,
        detectionId: String,
        newLat: Double,
        newLon: Double
    ) = withContext(Dispatchers.IO) {
        val dir = listSessions().firstOrNull { loadMetadata(it)?.sessionId == sessionId }
            ?: return@withContext
        val event = VerificationEvent(
            detectionId = detectionId,
            status = VerificationStatus.UNVERIFIED,
            verifiedAtMs = System.currentTimeMillis(),
            actionType = VerificationEvent.ACTION_POSITION_MOVED,
            newValue = "$newLat,$newLon"
        )
        try {
            File(dir, "verifications.jsonl").appendText(json.encodeToString(event) + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "recordPositionMoved fehlgeschlagen: $detectionId", e)
        }
    }

    suspend fun recordSimultaneousLink(
        sessionId: String,
        detectionId1: String,
        detectionId2: String,
        actionType: String
    ) = withContext(Dispatchers.IO) {
        val dir = listSessions().firstOrNull { loadMetadata(it)?.sessionId == sessionId }
            ?: return@withContext
        val now = System.currentTimeMillis()
        val event1 = VerificationEvent(
            detectionId = detectionId1,
            status = VerificationStatus.UNVERIFIED,
            verifiedAtMs = now,
            actionType = actionType,
            newValue = detectionId2
        )
        val event2 = VerificationEvent(
            detectionId = detectionId2,
            status = VerificationStatus.UNVERIFIED,
            verifiedAtMs = now,
            actionType = actionType,
            newValue = detectionId1
        )
        try {
            File(dir, "verifications.jsonl").appendText(json.encodeToString(event1) + "\n")
            File(dir, "verifications.jsonl").appendText(json.encodeToString(event2) + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "recordSimultaneousLink fehlgeschlagen: $detectionId1/$detectionId2", e)
        }
    }
}
