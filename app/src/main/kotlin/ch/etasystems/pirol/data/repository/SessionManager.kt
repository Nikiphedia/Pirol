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
import kotlinx.coroutines.ensureActive
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import ch.etasystems.pirol.audio.export.SegmentedRecording

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
    val errors: List<String>,
    /** T141: Sessions, die am Ziel schon vorhanden waren (unveraendert uebersprungen) */
    val sessionsSkipped: Int = 0,
    /** T141: Sessions mit Fehler (Quelle bleibt unveraendert) */
    val sessionsFailed: Int = 0
)

/**
 * Verwaltet Aufnahme-Sessions (T51: SAF + Flat-Struktur + Tages-Unterordner).
 *
 * Neue Struktur ab T51:
 * [base]/PIROL/YYYY-MM-DD/{sessionId}/
 *   session.json, detections.jsonl, verifications.jsonl,
 *   recording.wav, recording.selections.txt
 *
 * Kein audio/-Unterordner.
 *
 * Storage-Modi:
 * - SAF (storageBaseUri gesetzt): DocumentFile fuer Writes, File-API fuer Reads (primaerer Speicher)
 * - FileBased (kein SAF): File-API fuer alles; Basis = getExternalFilesDir/PIROL
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

        /** Dateiname fuer Segment-Index: 0 → "recording.wav", 1 → "recording-002.wav", ... */
        internal fun segmentFileName(index: Int): String =
            if (index == 0) "recording.wav" else "recording-${String.format(Locale.ROOT, "%03d", index + 1)}.wav"

        /** T130: Session-relativer Offset = abgeschlossene Segmente + aktuelles Segment. */
        internal fun sessionOffsetSec(completedSamples: Long, currentSegmentSamples: Long, sampleRate: Int): Float =
            if (sampleRate <= 0) 0f
            else ((completedSamples + currentSegmentSamples).toDouble() / sampleRate).toFloat()

        /**
         * T130: Metadaten einer nach Prozess-Kill verwaisten Session aus den Dateien rekonstruieren.
         * Segmentliste + Gesamtsamples aus den WAV-Dateilaengen, Detektionen aus detections.jsonl.
         * endedAt = letzte Schreibzeit der WAV; ist diese unplausibel (0 / vor startedAt, z.B. nach
         * Kopieren), dann startedAt + Aufnahmedauer.
         */
        internal fun recoverMetadata(
            metadata: SessionMetadata,
            segmentFiles: List<File>,
            detectionLineCount: Int?
        ): SessionMetadata {
            val sr = metadata.sampleRate
            val frames = segmentFiles.map { SegmentedRecording.framesOf(it) }
            val totalFrames = frames.sum()
            val segments = if (segmentFiles.size > 1 && sr > 0) {
                var start = 0L
                segmentFiles.mapIndexed { i, f ->
                    RecordingSegment(f.name, start.toFloat() / sr, frames[i].toFloat() / sr).also { start += frames[i] }
                }
            } else null
            val started = runCatching { ZonedDateTime.parse(metadata.startedAt) }.getOrNull()
            val lastWrite = segmentFiles.maxOfOrNull { it.lastModified() } ?: 0L
            val endedAt = when {
                started != null && (lastWrite <= 0L || lastWrite < started.toInstant().toEpochMilli()) ->
                    started.plusNanos(if (sr > 0) totalFrames * 1_000_000_000L / sr else 0L)
                lastWrite > 0L -> java.time.Instant.ofEpochMilli(lastWrite).atZone(ZoneId.systemDefault())
                else -> ZonedDateTime.now(ZoneId.systemDefault())
            }.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            return metadata.copy(
                endedAt = endedAt,
                totalRecordedSamples = if (segmentFiles.isEmpty()) metadata.totalRecordedSamples else totalFrames,
                totalDetections = detectionLineCount ?: metadata.totalDetections,
                recordingSegments = segments ?: metadata.recordingSegments
            )
        }

        /** Pure-Logic-Funktion fuer Unit-Tests (T60). */
        /** T117: Aktionstypen, die einen Verifikationsstatus setzen (Undo-faehig). null = Legacy. */
        private val STATUS_ACTIONS = setOf(
            null,
            VerificationEvent.ACTION_CONFIRMED, VerificationEvent.ACTION_REJECTED,
            VerificationEvent.ACTION_UNCERTAIN, VerificationEvent.ACTION_CORRECTED,
            VerificationEvent.ACTION_REPLACED, VerificationEvent.ACTION_ALTERNATIVE_CONFIRMED
        )

        /**
         * T117: Index des letzten Status-Ereignisses fuer [detectionId], -1 wenn keines.
         * Zeilen, die sich nicht dekodieren lassen, werden uebersprungen.
         */
        internal fun indexOfLastStatusEvent(
            lines: List<String>,
            detectionId: String,
            decode: (String) -> VerificationEvent
        ): Int = lines.indexOfLast { line ->
            line.isNotBlank() && try {
                val ev = decode(line)
                ev.detectionId == detectionId && ev.actionType in STATUS_ACTIONS
            } catch (_: Exception) { false }
        }

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
        // Fallback: getExternalFilesDir
        val base = context.getExternalFilesDir(null) ?: context.filesDir
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
        confidenceThreshold: Float = 0.5f,
        note: String? = null,
        modelId: String? = null  // T103a
    ): String = withContext(Dispatchers.IO) {
        // Alte Session sicherheitshalber schliessen
        if (isActive) endSession()
        lastStartUsedFallback = false

        // Session-ID: Ortszeit (T155) + UUID-Prefix
        val sessionId = buildSessionId(LocalDateTime.now(), UUID.randomUUID().toString().take(6))

        val daySubdir = dailySubdirName()

        val metadata = SessionMetadata(
            sessionId = sessionId,
            startedAt = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            sampleRate = sampleRate,
            latitude = latitude,
            longitude = longitude,
            regionFilter = regionFilter,
            confidenceThreshold = confidenceThreshold,
            note = note?.trim()?.ifEmpty { null },  // T109
            modelId = modelId  // T103a
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
     * T117: nur Status-Ereignisse (Bestaetigen/Ablehnen/...), nie CLUSTER_MERGED, Bemerkung usw. —
     * sonst loeschte ein Undo eine zwischenzeitlich angehaengte Cluster-Zusammenfuehrung.
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
                val lastMatchIdx = indexOfLastStatusEvent(lines, detectionId) { line ->
                    jsonLenient.decodeFromString<VerificationEvent>(line)
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
                val lastMatchIdx = indexOfLastStatusEvent(lines, detectionId) { line ->
                    jsonLenient.decodeFromString<VerificationEvent>(line)
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
        fileMutex.withLock { replaceDetectionLocked(updated) }
    }

    /** Rumpf von [replaceDetection]; Aufrufer haelt den fileMutex (T131: auch fuer Analyse-Tab auf aktiver SAF-Session). */
    private fun replaceDetectionLocked(updated: DetectionResult) {
        run {
            val encodedLine = json.encodeToString(updated) + "\n"
            val sessionDoc = activeSessionDoc
            if (sessionDoc != null) {
                // SAF-Modus
                val detFile = sessionDoc.findFile("detections.jsonl") ?: return
                val lines = try {
                    context.contentResolver.openInputStream(detFile.uri)?.use {
                        it.bufferedReader().readLines()
                    } ?: return
                } catch (_: Exception) { return }
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
                val sessionDir = activeSessionDir ?: return
                val file = File(sessionDir, "detections.jsonl")
                detectionWriter = replaceDetectionInFile(file, updated, detectionWriter) { e ->
                    Log.e(TAG, "replaceDetection fehlgeschlagen: ${updated.id}", e)
                }
            }
        }
    }

    // ── T93b: Zugriffe von aussen (Analyse-Tab) auf beliebige Session-Ordner ──────────
    // Laufen ueber denselben fileMutex wie die Live-Pfade. Ist sessionDir die gerade aktive
    // File-Session, wird deren offener detections-Writer verwendet bzw. neu geoeffnet.
    // Fehler werden geworfen (Aufrufer macht Rollback + Fehlermeldung).

    private fun isActiveDir(sessionDir: File): Boolean =
        SessionFilesLogic.isSameSession(
            activeSessionDir?.absolutePath, activeSessionDoc?.name, sessionDir.absolutePath, sessionDir.name
        )

    // ── T131: SAF-Sessions (Analyse-Tab) ──────────────────────────────
    // SAF-Sessions werden im Analyse-Tab ueber einen synthetischen File-Schluessel (/saf/...) identifiziert;
    // safSessions bildet den Schluessel auf das DocumentFile ab. Die aktive SAF-Session wird ueber ihren
    // Ordnernamen erkannt (auch wenn sie als echter File-Pfad gelistet wird).

    private val safSessions = java.util.concurrent.ConcurrentHashMap<String, DocumentFile>()

    /** DocumentFile der Session, falls SAF-Session (gelistet oder aktiv); sonst null. */
    fun docFor(sessionDir: File): DocumentFile? =
        safSessions[sessionDir.path]
            ?: activeSessionDoc?.takeIf { it.name == sessionDir.name }

    /** Dateizugriff passend zum Speichermodus der Session. */
    private fun accessFor(sessionDir: File): SessionFiles =
        docFor(sessionDir)?.let { DocSessionFiles(context, it) } ?: FileSessionFiles(sessionDir)

    /** Haengt eine fertige JSONL-Zeile (inkl. Zeilenende) an verifications.jsonl der Session an. */
    suspend fun appendVerificationLine(sessionDir: File, line: String) = withContext(Dispatchers.IO) {
        fileMutex.withLock { accessFor(sessionDir).append("verifications.jsonl", line) }
    }

    /** Haengt eine fertige JSONL-Zeile (inkl. Zeilenende) an detections.jsonl der Session an. */
    suspend fun appendDetectionLine(sessionDir: File, line: String) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val writer = detectionWriter
            if (writer != null && isActiveDir(sessionDir)) {
                // aktive Session (File oder SAF): offenen Writer verwenden
                writer.append(line)
                writer.flush()
                detectionCounter++
            } else {
                accessFor(sessionDir).append("detections.jsonl", line)
            }
        }
    }

    /** Ersetzt die Detektion mit gleicher ID in detections.jsonl der Session. No-op falls Datei fehlt. */
    suspend fun replaceDetectionIn(sessionDir: File, updated: DetectionResult) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            if (activeSessionDoc != null && isActiveDir(sessionDir)) {
                // T131: aktive SAF-Session -> offenen Writer/PFD schliessen und neu oeffnen (wie Live-Pfad)
                replaceDetectionLocked(updated)
                return@withLock
            }
            val doc = docFor(sessionDir)
            if (doc != null) {
                val access = DocSessionFiles(context, doc)
                val text = access.readText("detections.jsonl") ?: return@withLock
                access.writeReplacing("detections.jsonl", SessionFilesLogic.replaceDetectionInText(text, updated))
                return@withLock
            }
            val file = File(sessionDir, "detections.jsonl")
            // Fehler erst NACH der Writer-Zuweisung werfen, damit die aktive Session
            // in jedem Fall wieder einen gueltigen Append-Writer hat.
            var error: Exception? = null
            if (isActiveDir(sessionDir)) {
                detectionWriter = replaceDetectionInFile(file, updated, detectionWriter) { e -> error = e }
            } else {
                replaceDetectionInFile(file, updated, null) { e -> error = e }
            }
            error?.let { throw it }
        }
    }

    /** T131: Sprach-Memo einer Detektion loeschen (File oder SAF). */
    suspend fun deleteMemo(sessionDir: File, detectionId: String) = withContext(Dispatchers.IO) {
        val doc = docFor(sessionDir)
        if (doc != null) doc.findFile("memos")?.findFile("$detectionId.wav")?.delete()
        else File(sessionDir, "memos/$detectionId.wav").takeIf { it.exists() }?.delete()
        Unit
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

    /** T118: Bisher in die WAV-Segmente der aktiven Session geschriebene Samples (fuer T118_STATS). */
    val recordedSampleCount: Long
        get() = totalSamplesCompleted + (recordingWriter?.sampleCount ?: 0L)

    /**
     * Aktueller Schreib-Offset der Session in Sekunden (T130: Session-relativ ueber alle
     * WAV-Segmente, vorher nur im aktuellen Segment → nach einer Rotation falsche Positionen).
     * Wird VOR appendAudioSamples() aufgerufen fuer korrekte Detection-Zeitstempel.
     *
     * @return Offset in Sekunden, oder 0f wenn keine Session aktiv
     */
    fun getCurrentRecordingOffsetSec(): Float {
        val writer = recordingWriter ?: return 0f
        val sampleRate = activeMetadata?.sampleRate ?: return 0f
        return sessionOffsetSec(totalSamplesCompleted, writer.sampleCount, sampleRate)
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
                RavenExporter.export(selectionsFile, detections, metadata.startedAt, updatedMetadata.recordingSegments, autoEnabledFields, sessionNote = updatedMetadata.note)
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

    private fun segmentFileName(index: Int): String = Companion.segmentFileName(index)

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

        // 0. T131: SAF-Basis (Downloads/PIROL via Tree-URI) ueber DocumentFile listen. File-Zugriff auf
        //    diese Ordner ist unter Scoped Storage nicht garantiert -> Doc-Sessions haben Vorrang.
        val safNames = mutableSetOf<String>()
        listSafSessions().forEach { (key, doc) ->
            safSessions[key.path] = doc
            safNames += key.name
            results += key
        }

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

        // Duplikate entfernen: File-Sessions, die schon als SAF-Session gelistet sind, fallen weg
        val (safKeys, fileSessions) = results.partition { safSessions.containsKey(it.path) }
        return (safKeys + SessionFilesLogic.dropListedNames(fileSessions, safNames))
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.name }
    }

    /** T131: Sessions unter der SAF-Basis (mit/ohne Tages-Unterordner) als (Schluessel, DocumentFile). */
    private fun listSafSessions(): List<Pair<File, DocumentFile>> {
        if (appPreferences.storageBaseUri == null) return emptyList()
        val base = try { resolveBaseDocFile() } catch (_: Exception) { null } ?: return emptyList()
        if (!base.canRead()) return emptyList()
        val out = mutableListOf<Pair<File, DocumentFile>>()
        try {
            for (child in base.listFiles()) {
                val childName = child.name ?: continue
                if (!child.isDirectory) continue
                if (looksLikeDateDir(childName)) {
                    for (s in child.listFiles()) {
                        val n = s.name ?: continue
                        if (s.isDirectory) out += SessionFilesLogic.safKey(childName, n) to s
                    }
                } else {
                    out += SessionFilesLogic.safKey(null, childName) to child
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SAF-Sessions listen fehlgeschlagen", e)
        }
        return out
    }

    private fun looksLikeDateDir(name: String): Boolean {
        return name.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
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
        // T131: SAF-Session (gelistet) ueber DocumentFile loeschen
        docFor(sessionDir)?.takeIf { activeSessionDoc?.name != it.name }?.let { doc ->
            val ok = doc.delete()
            safSessions.remove(sessionDir.path)
            return@withContext ok
        }
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
            // T130: laufende Session (Analyse-Tab waehrend der Aufnahme geoeffnet) nicht anfassen
            if (isActiveDir(sessionDir) || activeMetadata?.sessionId == metadata.sessionId) return@forEach

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

            // T130: endedAt, Gesamtsamples, Segmentliste und Detektionszahl aus den Dateien
            // (endSession() lief nicht → session.json kennt weder Rotation noch Dauer)
            val detectionLines = runCatching {
                File(sessionDir, "detections.jsonl").takeIf { it.exists() }
                    ?.useLines { lines -> lines.count { it.isNotBlank() } }
            }.getOrNull()
            val recoveredMeta = recoverMetadata(metadata, SegmentedRecording.segmentFiles(sessionDir), detectionLines)
            val endedAt = recoveredMeta.endedAt
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
        try {
            val text = accessFor(sessionDir).readText("session.json") ?: return@withContext null
            jsonLenient.decodeFromString<SessionMetadata>(text)
        } catch (e: Exception) {
            Log.e(TAG, "session.json parsen fehlgeschlagen: ${sessionDir.name}", e)
            null
        }
    }

    /**
     * T109: Aendert die Bemerkung einer bestehenden Session (File-Modus: session.json atomar ersetzen).
     * Die Analyse arbeitet ausschliesslich mit File-Session-Ordnern (listSessions/resolveSessionDirFromDoc);
     * ein reiner SAF-Schreibpfad ist daher hier nicht noetig. Schreibt bei vorhandener
     * recording.selections.txt diese neu (Spalte Session-Bemerkung).
     * @return aktualisierte Metadaten oder null bei Fehler / fehlender session.json
     */
    suspend fun updateSessionNote(
        sessionDir: File,
        note: String?,
        appPreferences: AppPreferences? = null
    ): SessionMetadata? = withContext(Dispatchers.IO) {
        val meta = loadMetadata(sessionDir) ?: return@withContext null
        val updated = meta.copy(note = note?.trim()?.ifEmpty { null })
        val access = accessFor(sessionDir)
        try {
            // T131: File- oder SAF-Session; Schreiben unter dem fileMutex (T93)
            fileMutex.withLock {
                access.writeReplacing("session.json", Json { prettyPrint = true }.encodeToString(updated))
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateSessionNote: session.json Schreiben fehlgeschlagen: ${sessionDir.name}", e)
            return@withContext null
        }
        // Raven-Datei nur neu schreiben, wenn schon eine existiert (Auto-Export bei Stop)
        if (access.exists("recording.selections.txt")) {
            try { exportRavenSelectionTable(sessionDir, appPreferences) }
            catch (e: Exception) { Log.e(TAG, "updateSessionNote: Raven-Re-Export fehlgeschlagen", e) }
        }
        updated
    }

    /**
     * Laedt alle Detektionen einer Session aus detections.jsonl.
     */
    suspend fun loadDetections(sessionDir: File): List<DetectionResult> = withContext(Dispatchers.IO) {
        try {
            (accessFor(sessionDir).readText("detections.jsonl") ?: return@withContext emptyList())
                .lines()
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
            val veriText = try { accessFor(sessionDir).readText("verifications.jsonl") } catch (_: Exception) { null }
                ?: return@withContext detections

            val verifications = try {
                veriText.lines()
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
        val veriText = try { accessFor(sessionDir).readText("verifications.jsonl") } catch (_: Exception) { null }
            ?: return@withContext emptyMap()

        val map = mutableMapOf<String, RavenExporter.SimultaneousLinkSummary>()
        veriText.lineSequence().forEach { line ->
            val event = runCatching {
                jsonLenient.decodeFromString<VerificationEvent>(line)
            }.getOrNull() ?: return@forEach

            val otherId = event.newValue ?: return@forEach
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
     */
    fun getRecordingFile(sessionDir: File): File? =
        File(sessionDir, "recording.wav").takeIf { it.exists() }

    /**
     * T130: Alle WAV-Segmente einer Session als eine Zeitachse (Wiedergabe, Sonogramm, Ausschnitte).
     * null = keine Aufnahme.
     */
    suspend fun loadRecording(sessionDir: File): SegmentedRecording? = withContext(Dispatchers.IO) {
        // T131: File- und SAF-Segmente (content://) ueber SessionFiles
        accessFor(sessionDir).recording()
    }

    /** T131: true, wenn die Session mindestens ein WAV-Segment hat (File oder SAF). */
    suspend fun hasRecording(sessionDir: File): Boolean = withContext(Dispatchers.IO) {
        accessFor(sessionDir).segmentSources().isNotEmpty()
    }

    /** T131: Gesamtgroesse aller WAV-Segmente in Bytes (File oder SAF). */
    suspend fun recordingSizeBytes(sessionDir: File): Long = withContext(Dispatchers.IO) {
        try { accessFor(sessionDir).segmentSources().sumOf { it.length() } } catch (_: Exception) { 0L }
    }

    /**
     * Fuehrt veraltete RIFF-/data-Groessen aller Aufnahme-Segmente nach (T130, Prozess-Kill-Recovery).
     * Dateien mit passendem Header bleiben unveraendert.
     *
     * @return Anzahl reparierter Dateien
     */
    suspend fun repairRecordingHeaders(sessionDir: File): Int = withContext(Dispatchers.IO) {
        // T131: SAF-Sessions werden nicht repariert (kein RandomAccess-Schreibzugriff via ContentResolver);
        // SegmentedRecording liest die Laenge aus der Dateigroesse und ist gegen alte Header robust.
        if (docFor(sessionDir) != null) return@withContext 0
        val candidates = (sessionDir.listFiles { f ->
            f.isFile && f.name.startsWith("recording") && f.name.endsWith(".wav")
        }?.toList() ?: emptyList())
        var repaired = 0
        // T130: Segmente der laufenden Session werden noch geschrieben → nicht anfassen
        val active = isActiveDir(sessionDir)
        for (file in candidates) {
            try {
                if (!active && WavWriter.headerSizesStale(file)) {
                    // T130: nach Prozess-Kill veraltete RIFF-/data-Groessen aus der Dateilaenge setzen
                    StreamingWavWriter.finalizeHeader(file)
                    repaired++
                    Log.w(TAG, "WAV-Groessen nachgefuehrt: ${sessionDir.name}/${file.name}")
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
        val access = accessFor(sessionDir)
        if (access.segmentSources().isEmpty()) return@withContext null
        val isDoc = access is DocSessionFiles
        // SAF: erst in eine Cache-Datei exportieren, dann in den Session-Ordner schreiben
        val outputFile = if (isDoc) File(context.cacheDir, "recording.selections.txt")
                         else File(sessionDir, "recording.selections.txt")
        val detections = loadDetectionsWithVerifications(sessionDir)
        // Metadaten fuer startedAt + recordingSegments laden (T57-B3)
        val meta = runCatching {
            val jsonText = access.readText("session.json") ?: error("session.json fehlt")
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
        RavenExporter.export(outputFile, detections, startedAt, meta?.recordingSegments, enabledFields, linkMap, meta?.note)
        if (isDoc) {
            fileMutex.withLock { access.writeReplacing("recording.selections.txt", outputFile.readText()) }
            outputFile.delete()
        }
        outputFile
    }

    // ── Migrations-Methoden (T41, erweitert T51) ──────────────────────

    // ── Migrations-Methoden (T41, T51, neu T141) ──────────────────────

    /** Standard-Basisordner im App-Speicher (Ziel "zurueck auf App-Speicher"). */
    fun defaultBaseDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, PIROL_DIR)

    /** Eine Datei einer Quell-Session (relativer Pfad, Laenge, Stream-Oeffner). */
    private class SrcEntry(val rel: String, val length: Long, val open: () -> java.io.InputStream)

    private fun collectEntries(sessionDir: File): List<SrcEntry> {
        val doc = docFor(sessionDir)
        val out = mutableListOf<SrcEntry>()
        if (doc != null) {
            val cr = context.contentResolver
            fun walk(dir: DocumentFile, prefix: String) {
                for (child in dir.listFiles()) {
                    val n = child.name ?: continue
                    if (child.isDirectory) walk(child, "$prefix$n/")
                    else out += SrcEntry("$prefix$n", child.length(), {
                        cr.openInputStream(child.uri) ?: throw java.io.IOException("Nicht lesbar: $n")
                    })
                }
            }
            walk(doc, "")
        } else {
            sessionDir.walkTopDown().filter { it.isFile }.forEach { f ->
                out += SrcEntry(f.relativeTo(sessionDir).path.replace('\\', '/'), f.length(), { f.inputStream() })
            }
        }
        return out
    }

    /**
     * Zaehlt Sessions und berechnet Gesamtgroesse am aktuellen Speicherort (File und SAF).
     * Blockierend — vom IO-Dispatcher aufrufen.
     */
    fun getMigrationInfo(): MigrationInfo {
        val sessions = listSessions()
        val totalSize = sessions.sumOf { dir -> collectEntries(dir).sumOf { it.length } }
        return MigrationInfo(sessions.size, totalSize)
    }

    /** Ziel-Schreiber einer Migration (File oder SAF). */
    private interface MigrationSink {
        /** Legt den Session-Ordner an; false = Session existiert am Ziel schon. */
        fun begin(sessionName: String, dayDir: String?): Boolean
        /** Schreibt eine Datei und verifiziert die Groesse; liefert geschriebene Bytes. */
        fun write(entry: SrcEntry): Long
        /** Verwirft die angefangene Session am Ziel. */
        fun rollback()
    }

    private class FileSink(private val base: File) : MigrationSink {
        private var dir: File? = null
        override fun begin(sessionName: String, dayDir: String?): Boolean {
            val parent = if (dayDir != null) File(base, dayDir) else base
            val d = File(parent, sessionName)
            if (d.exists()) return false
            if (!d.mkdirs()) throw java.io.IOException("Verzeichnis nicht erstellbar")
            dir = d
            return true
        }
        override fun write(entry: SrcEntry): Long {
            val f = File(dir!!, entry.rel)
            f.parentFile?.mkdirs()
            val n = entry.open().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            if (f.length() != n || (entry.length > 0 && n != entry.length))
                throw java.io.IOException("${entry.rel}: Groessenabweichung (${f.length()} != ${entry.length})")
            return n
        }
        override fun rollback() { dir?.deleteRecursively() }
    }

    private inner class TreeSink(private val root: DocumentFile) : MigrationSink {
        private var dir: DocumentFile? = null
        private val cr = context.contentResolver
        override fun begin(sessionName: String, dayDir: String?): Boolean {
            val parent = if (dayDir != null) root.findOrCreateDir(dayDir)
                ?: throw java.io.IOException("Tagesordner nicht erstellbar") else root
            if (parent.findFile(sessionName)?.isDirectory == true) return false
            dir = parent.createDirectory(sessionName) ?: throw java.io.IOException("Verzeichnis nicht erstellbar")
            return true
        }
        override fun write(entry: SrcEntry): Long {
            var d = dir!!
            val parts = entry.rel.split('/')
            for (p in parts.dropLast(1)) d = d.findOrCreateDir(p) ?: throw java.io.IOException("$p: Ordner nicht erstellbar")
            val fileName = parts.last()
            val mime = when {
                fileName.endsWith(".json") -> "application/json"
                fileName.endsWith(".wav") -> "audio/wav"
                fileName.endsWith(".txt") -> "text/plain"
                else -> "application/octet-stream"
            }
            val dest = d.createFile(mime, fileName) ?: throw java.io.IOException("$fileName: Datei nicht erstellbar")
            val n = entry.open().use { i ->
                (cr.openOutputStream(dest.uri) ?: throw java.io.IOException("$fileName: nicht beschreibbar"))
                    .use { o -> i.copyTo(o) }
            }
            val destSize = cr.openFileDescriptor(dest.uri, "r")?.use { it.statSize } ?: dest.length()
            if (destSize != n || (entry.length > 0 && n != entry.length))
                throw java.io.IOException("$fileName: Groessenabweichung ($destSize != ${entry.length})")
            return n
        }
        override fun rollback() { dir?.delete() }
    }

    /**
     * T141: Kopiert alle Sessions (File- oder SAF-Quelle) an das Ziel (File oder SAF), mit Groessenpruefung
     * pro Datei. Segmente (recording-00N.wav) und Unterordner (memos/) werden mitkopiert.
     * - Session am Ziel schon vorhanden: uebersprungen (Idempotenz)
     * - Fehler: Zielordner dieser Session wird verworfen, die Quelle bleibt unveraendert
     * - deleteSource: Originale werden erst am Ende und nur geloescht, wenn KEINE Session fehlgeschlagen ist
     * Waehrend einer aktiven Aufnahme wird nichts getan.
     */
    suspend fun migrateSessions(
        target: MigrationTarget,
        deleteSource: Boolean = false,
        onProgress: (MigrationProgress) -> Unit = {}
    ): MigrationResult = withContext(Dispatchers.IO) {
        if (this@SessionManager.isActive) return@withContext MigrationResult(0, 0L, listOf("Aufnahme laeuft"), 0, 0)
        val sink: MigrationSink = when (target) {
            is MigrationTarget.Dir -> { target.base.mkdirs(); FileSink(target.base) }
            is MigrationTarget.Tree -> {
                val root = DocumentFile.fromTreeUri(context, target.uri)
                    ?: return@withContext MigrationResult(0, 0L, listOf("Zielordner nicht erreichbar"), 0, 0)
                TreeSink(root)
            }
        }
        val sessionDirs = listSessions()
        var copied = 0
        var skipped = 0
        var bytesCopied = 0L
        val errors = mutableListOf<String>()
        val copiedSources = mutableListOf<File>()
        onProgress(MigrationProgress(0, sessionDirs.size, 0L))

        sessionDirs.forEachIndexed { index, sessionDir ->
            try {
                if (!sink.begin(sessionDir.name, SessionMigrationLogic.dayDirOf(sessionDir))) {
                    skipped++
                } else {
                    try {
                        var bytes = 0L
                        for (e in collectEntries(sessionDir)) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            bytes += sink.write(e)
                        }
                        bytesCopied += bytes
                        copied++
                        copiedSources += sessionDir
                    } catch (e: Exception) {
                        sink.rollback()
                        throw e
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.add("${sessionDir.name}: ${e.message}")
                Log.e(TAG, "Session-Migration fehlgeschlagen: ${sessionDir.name}", e)
            }
            onProgress(MigrationProgress(index + 1, sessionDirs.size, bytesCopied))
        }

        if (SessionMigrationLogic.shouldDeleteSources(deleteSource, errors.size)) {
            for (src in copiedSources) {
                try { accessFor(src).deleteAll() } catch (e: Exception) { Log.w(TAG, "Original nicht geloescht: ${src.name}", e) }
            }
        }
        MigrationResult(copied, bytesCopied, errors, skipped, errors.size)
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
            fileMutex.withLock {  // T93b
                File(dir, "verifications.jsonl").appendText(json.encodeToString(event) + "\n")
            }
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
            fileMutex.withLock {  // T93b
                File(dir, "verifications.jsonl").appendText(json.encodeToString(event1) + "\n")
                File(dir, "verifications.jsonl").appendText(json.encodeToString(event2) + "\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "recordSimultaneousLink fehlgeschlagen: $detectionId1/$detectionId2", e)
        }
    }
}


/** T155: Session-ID im Format `yyyy-MM-dd'T'HH-mm-ss_uuid6`, Zeit = Ortszeit (wie Tages-Unterordner). */
internal fun buildSessionId(localNow: LocalDateTime, uuidShort: String): String =
    localNow.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")) + "_" + uuidShort
