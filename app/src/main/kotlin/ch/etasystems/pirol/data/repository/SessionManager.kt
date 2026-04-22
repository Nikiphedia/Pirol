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
    }

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
        val writer = detectionWriter ?: return@withContext
        for (detection in detections) {
            val line = json.encodeToString(detection)
            writer.append(line)
            writer.append('\n')
            detectionCounter++
        }
        writer.flush()
    }

    /**
     * Schreibt ein Verifikations-Event in verifications.jsonl.
     * Separate Datei von detections.jsonl (append-only, crash-sicher).
     */
    suspend fun appendVerification(event: VerificationEvent) = withContext(Dispatchers.IO) {
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
            val sessionDir = activeSessionDir ?: return@withContext
            File(sessionDir, "verifications.jsonl").appendText(line)
        }
    }

    /**
     * Entfernt den letzten Verifikations-Eintrag fuer eine Detection-ID aus verifications.jsonl.
     * Idempotent: kein Fehler falls kein Eintrag fuer die ID vorhanden.
     * Wird fuer Undo im Live-Tab verwendet (T52).
     */
    suspend fun removeLastVerification(detectionId: String) = withContext(Dispatchers.IO) {
        val sessionDoc = activeSessionDoc
        if (sessionDoc != null) {
            // SAF-Modus
            val veriFile = sessionDoc.findFile("verifications.jsonl") ?: return@withContext
            val lines = try {
                context.contentResolver.openInputStream(veriFile.uri)?.use {
                    it.bufferedReader().readLines()
                } ?: return@withContext
            } catch (_: Exception) { return@withContext }
            val lastMatchIdx = lines.indexOfLast { line ->
                line.isNotBlank() && try {
                    jsonLenient.decodeFromString<VerificationEvent>(line).detectionId == detectionId
                } catch (_: Exception) { false }
            }
            if (lastMatchIdx < 0) return@withContext
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
            val sessionDir = activeSessionDir ?: return@withContext
            val file = File(sessionDir, "verifications.jsonl")
            if (!file.exists()) return@withContext
            val lines = try { file.readLines() } catch (_: Exception) { return@withContext }
            val lastMatchIdx = lines.indexOfLast { line ->
                line.isNotBlank() && try {
                    jsonLenient.decodeFromString<VerificationEvent>(line).detectionId == detectionId
                } catch (_: Exception) { false }
            }
            if (lastMatchIdx < 0) return@withContext
            val filtered = lines.filterIndexed { idx, _ -> idx != lastMatchIdx }
            try {
                val content = if (filtered.isEmpty()) "" else filtered.joinToString("\n") + "\n"
                file.writeText(content)
            } catch (e: Exception) {
                Log.e(TAG, "removeLastVerification fehlgeschlagen: $detectionId", e)
            }
        }
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
        try { detectionWriter?.close() } catch (e: Exception) { Log.e(TAG, "Detection-Writer close failed", e) }
        detectionWriter = null
        try { activeDetectionsFd?.close() } catch (e: Exception) { Log.e(TAG, "Detections-PFD close failed", e) }
        activeDetectionsFd = null

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
                RavenExporter.export(selectionsFile, detections)
                Log.i(TAG, "Auto-Raven-Export: ${selectionsFile.name} (${detections.size} Detektionen)")
            } catch (e: Exception) {
                Log.e(TAG, "Auto-Raven-Export fehlgeschlagen", e)
            }
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

    /** Loescht eine Session (Ordner + alle Dateien) */
    suspend fun deleteSession(sessionDir: File): Boolean = withContext(Dispatchers.IO) {
        if (!sessionDir.exists()) return@withContext false
        val deleted = sessionDir.deleteRecursively()
        Log.i(TAG, "Session geloescht: ${sessionDir.name} (erfolg=$deleted)")
        deleted
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
     * Schreibt eine Raven Selection Table neben recording.wav (fuer manuellen Re-Export).
     * @return Pfad zur erzeugten Datei oder null wenn keine Aufnahme vorhanden
     */
    suspend fun exportRavenSelectionTable(sessionDir: File): File? = withContext(Dispatchers.IO) {
        getRecordingFile(sessionDir) ?: return@withContext null
        val outputFile = File(sessionDir, "recording.selections.txt")
        val detections = loadDetectionsWithVerifications(sessionDir)
        RavenExporter.export(outputFile, detections)
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
     * Kopiert alle Sessions zum File-basierten Zielort.
     * - Duplikate werden uebersprungen (Idempotenz)
     * - Quell-Sessions werden NICHT geloescht
     */
    suspend fun migrateSessionsTo(
        targetBaseDir: File,
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
                    bytesCopied += targetDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    copied++
                    Log.i(TAG, "Session kopiert: ${sessionDir.name}")
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
}
