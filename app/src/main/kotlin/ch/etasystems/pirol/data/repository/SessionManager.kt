package ch.etasystems.pirol.data.repository

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.export.ExportNames
import ch.etasystems.pirol.data.export.GpxExporter
import ch.etasystems.pirol.data.export.RavenExporter
import ch.etasystems.pirol.location.TrackLogic
import ch.etasystems.pirol.location.TrackPoint
import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import ch.etasystems.pirol.rescan.RescanFiles
import ch.etasystems.pirol.rescan.VariantWriteTarget
import ch.etasystems.pirol.ui.analysis.DetectionOccurrences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Info ueber vorhandene Sessions am aktuellen Speicherort (Bestaetigungsdialog vor der Migration). */
data class MigrationInfo(
    val sessionCount: Int,
    val totalSizeBytes: Long
)

/** Ergebnis einer Session-Migration. */
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
 * [base]/PIROL/YYYY-MM-DD/{sessionId}/
 *   session.json, detections.jsonl, verifications.jsonl, recording.wav, recording.selections.txt,
 *   track.jsonl, track.gpx (T191), detections.updates.jsonl (T251, nur waehrend/nach unterbrochener Aufnahme)
 *
 * T251 (N7): Fassade. Aufgeteilt in [ActiveSession] (laufende Aufnahme: Writer, Rotation, Zusatzdatei),
 * [SessionLocator] (Speicherort, Listen, Lookup), [SessionMigrator] (Migration), [SessionTextParser]
 * (Auswertung), [DetectionUpdateLog] (Updates append-only, N2), [SessionJson] (JSON-Konfiguration).
 * Alle Schreibzugriffe auf Session-Dateien laufen hier unter dem [fileMutex].
 *
 * Lifecycle: startSession() → appendDetections() / appendAudioSamples() → endSession()
 */
class SessionManager(
    private val context: Context,
    private val appPreferences: AppPreferences,
    /** T251 (Zusatz T249, M1): Live-Liste der aktiven Session; Aenderungen von aussen setzen dort auf. */
    private val liveDetections: DetectionListState? = null,
    /** T304: WorkManager-Abfrage fuer eingereihte Uploads (Tests: Fake) */
    private val uploadQueue: ch.etasystems.pirol.data.sync.UploadQueueProbe =
        ch.etasystems.pirol.data.sync.WorkManagerUploadQueue(context)
) {

    companion object {
        private const val TAG = "SessionManager"
        private const val DETECTIONS_FILE = RescanFiles.DETECTIONS
        private const val VERIFICATIONS_FILE = RescanFiles.VERIFICATIONS
        private const val SESSION_JSON = ActiveSession.SESSION_JSON

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
            /** T249 (M6): nur Ereignisse genau dieses Typs (z.B. ALTERNATIVE_CONFIRMED); null = jedes Status-Ereignis */
            actionType: String? = null,
            decode: (String) -> VerificationEvent
        ): Int = lines.indexOfLast { line ->
            line.isNotBlank() && try {
                val ev = decode(line)
                ev.detectionId == detectionId &&
                    (if (actionType != null) ev.actionType == actionType else ev.actionType in STATUS_ACTIONS)
            } catch (_: Exception) { false }
        }

        /** T237: JSONL-Text ohne das letzte Status-Ereignis von [detectionId]; null wenn keines vorhanden. */
        internal fun removeLastStatusEventText(text: String, detectionId: String, actionType: String? = null): String? {
            val lines = text.lines().filter { it.isNotBlank() }
            val idx = indexOfLastStatusEvent(lines, detectionId, actionType) {
                SessionJson.lenient.decodeFromString<VerificationEvent>(it)
            }
            if (idx < 0) return null
            val kept = lines.filterIndexed { i, _ -> i != idx }
            return if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n"
        }

        /** T249 (M3): Versuche fuer den letzten Flush beim Session-Ende */
        internal const val END_FLUSH_ATTEMPTS = 3
        internal const val END_FLUSH_RETRY_MS = 300L

        /**
         * T249 (M3): [flush] bis zu [attempts]-mal versuchen (Pause [delayMs] dazwischen).
         * @return letzter Fehler oder null bei Erfolg
         */
        internal suspend fun retryFlush(attempts: Int, delayMs: Long, flush: () -> Exception?): Exception? {
            var last: Exception? = null
            repeat(attempts) { i ->
                if (i > 0) kotlinx.coroutines.delay(delayMs)
                last = flush() ?: return null
            }
            return last
        }

        fun shouldAutoDelete(
            durationSec: Double,
            detections: Int,
            minSec: Int,
            enabled: Boolean
        ): Boolean = enabled && minSec > 0 && durationSec < minSec && detections == 0

        /** T93: Schreibt [content] atomar nach [target] (tmp-Datei + Move). */
        internal fun writeFileReplacing(target: File, content: String) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            // T236: vor dem Move auf den Datentraeger bringen (sonst nach Stromausfall evtl. leere Datei)
            java.io.FileOutputStream(tmp).use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
                os.flush()
                try { os.fd.sync() } catch (_: java.io.SyncFailedException) { /* Dateisystem ohne sync */ }
            }
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }

        /** Lesen einer Session-Datei; detections.jsonl mit der Zusatzdatei zusammengefuehrt (T251, nur lesen). */
        private fun readMerged(files: SessionFiles, name: String): String? =
            // T260: auch Rescan-Varianten (laufender Rescan schreibt Updates in die Zusatzdatei der Variante)
            if (RescanFiles.updatesFor(name) != null) DetectionUpdateLog.mergedText(files, name) else files.readText(name)

        private fun findDetection(text: String, id: String): DetectionResult? =
            text.lineSequence().lastOrNull { it.isNotBlank() && SessionFilesLogic.detectionIdOf(it) == id }
                ?.let { runCatching { SessionJson.lenient.decodeFromString<DetectionResult>(it) }.getOrNull() }
    }

    /** T93: Serialisiert alle Schreib-/Rewrite-Zugriffe auf die Session-Dateien. */
    private val fileMutex = Mutex()

    /** T236/T251: vorgemerkte Detektions-Updates der aktiven Session (append-only in die Zusatzdatei) */
    private val updates = DetectionUpdateLog()

    private val locator = SessionLocator(context, appPreferences)
    private val migrator = SessionMigrator(context, ::listSessions, ::docFor, ::accessFor)

    /** Laufende Aufnahme-Session oder null */
    @Volatile private var active: ActiveSession? = null

    private val _sessionEnded = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    /** Emittiert Unit nach jedem endSession()-Aufruf. Subscriber koennen loadSessions() aufrufen. */
    val sessionEnded: SharedFlow<Unit> = _sessionEnded.asSharedFlow()

    private val _unsavedUpdatesAtEnd = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 1)
    /** T249 (M3): Anzahl Detektions-Updates, die beim Session-Ende trotz Wiederholung verloren gingen. */
    val unsavedUpdatesAtEnd: SharedFlow<Int> = _unsavedUpdatesAtEnd.asSharedFlow()

    private val _sessionMemoEvents = MutableSharedFlow<SessionMemoEvent>(replay = 0, extraBufferCapacity = 16)
    /** T299: neues bzw. geloeschtes Session-Memo (Karte haengt Marker live an/entfernt sie). */
    val sessionMemoEvents: SharedFlow<SessionMemoEvent> = _sessionMemoEvents.asSharedFlow()

    /** Ob beim letzten Start der Speicherort nicht erreichbar war (Fallback). */
    var lastStartUsedFallback: Boolean = false
        private set

    val isActive: Boolean get() = active != null

    /** T69: Aktives Session-Verzeichnis (File-Modus) */
    val currentSessionDir: File? get() = active?.dir

    /** T69: Aktives Session-Dokument (SAF-Modus) */
    val currentSessionDoc: DocumentFile? get() = active?.doc

    // ── Session-Lifecycle ─────────────────────────────────────────────

    /**
     * Neue Session starten. Erstellt Ordnerstruktur + session.json (SAF- oder File-Modus).
     * @return Session-ID
     */
    suspend fun startSession(
        sampleRate: Int,
        latitude: Double? = null,
        longitude: Double? = null,
        regionFilter: String? = null,
        confidenceThreshold: Float = 0.5f,
        note: String? = null,
        modelId: String? = null,  // T103a
        speciesFilter: SpeciesFilterInfo? = null  // T218
    ): String = withContext(Dispatchers.IO) {
        if (isActive) endSession()  // alte Session sicherheitshalber schliessen
        lastStartUsedFallback = false
        // T236: Updates, die nach dem letzten Session-Ende noch eintrafen, gehoeren nicht zur neuen Session
        fileMutex.withLock { updates.reset() }

        val sessionId = buildSessionId(LocalDateTime.now(), UUID.randomUUID().toString().take(6))  // T155
        val daySubdir = locator.dailySubdirName()
        val metadata = SessionMetadata(
            sessionId = sessionId,
            startedAt = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            sampleRate = sampleRate,
            latitude = latitude,
            longitude = longitude,
            regionFilter = regionFilter,
            confidenceThreshold = confidenceThreshold,
            note = note?.trim()?.ifEmpty { null },  // T109
            modelId = modelId,  // T103a
            speciesFilter = speciesFilter  // T218
        )

        val safUri = appPreferences.storageBaseUri
        val baseDoc = if (safUri != null) locator.resolveBaseDocFile() else null
        active = if (baseDoc != null && baseDoc.canWrite()) {
            val parentDoc = daySubdir?.let { baseDoc.findOrCreateDir(it) } ?: baseDoc
            ActiveSession.openSaf(context, parentDoc, metadata, updates).also {
                Log.i(TAG, "Session gestartet (SAF): $sessionId → ${it.doc?.uri}")
            }
        } else {
            if (safUri != null && baseDoc?.canWrite() == false) {
                Log.w(TAG, "SAF-URI nicht erreichbar — Fallback auf getExternalFilesDir")
                lastStartUsedFallback = true
            }
            val base = locator.resolveBaseFile()
            val sessionDir = File(if (daySubdir != null) File(base, daySubdir) else base, sessionId)
            ActiveSession.openFile(context, sessionDir, metadata, updates).also {
                Log.i(TAG, "Session gestartet (File): $sessionId → ${sessionDir.absolutePath}")
            }
        }
        sessionId
    }

    /**
     * Aktive Session beenden: WAV und Writer schliessen, Detektions-Updates zusammenfuehren (T251),
     * session.json aktualisieren, Raven- und GPX-Export, ggf. Auto-Delete.
     * @param gpsStats GPS-Statistiken der Session (T53). Null wenn GPS nicht verwendet wurde.
     */
    suspend fun endSession(gpsStats: GpsStats? = null) = withContext(Dispatchers.IO) {
        val a = active ?: return@withContext
        val metadata = a.metadata
        val (recordedSamples, segments) = a.closeRecording()

        val lost = fileMutex.withLock { finishDetectionsLocked(a) }
        if (lost > 0) _unsavedUpdatesAtEnd.tryEmit(lost)

        val updatedMetadata = metadata.copy(
            endedAt = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            totalRecordedSamples = recordedSamples,
            totalDetections = a.detectionCounter,
            gpsStats = gpsStats,  // T53
            recordingSegments = segments  // T57-B1: null bei Single-File
        )
        try {
            // T251 (Zusatz T249): auch im SAF-Modus sicher ersetzen (Schattenkopie), File: temp + Move
            fileMutex.withLock { a.files().writeReplacing(SESSION_JSON, SessionJson.pretty.encodeToString(updatedMetadata)) }
        } catch (e: Exception) {
            Log.e(TAG, "session.json beim Session-Ende nicht geschrieben: ${metadata.sessionId}", e)
        }
        Log.i(TAG, "Session beendet: ${metadata.sessionId} ($recordedSamples samples, ${a.detectionCounter} Detektionen)")

        // Auto-Raven-Export (T51); T249 (M2): derselbe Exportweg wie der manuelle Export
        val sessionDirForExport = a.dir ?: a.doc?.let { d ->
            locator.resolveSessionDirFromDoc(d) ?: d.name?.let { SessionFilesLogic.safKey(null, it) }
        }
        if (sessionDirForExport != null) {
            try {
                val out = writeRavenTable(sessionDirForExport, appPreferences, requestedVariant = null,
                    requireRecording = false, knownMeta = updatedMetadata)
                Log.i(TAG, "Auto-Raven-Export: ${out?.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Auto-Raven-Export fehlgeschlagen", e)
            }
        }

        // T191: Route als track.gpx (aus track.jsonl, nur wenn Punkte vorhanden)
        try {
            val n = GpxExporter.exportFrom(a.files(), metadata.sessionId)
            if (n > 0) Log.i(TAG, "GPX-Export: ${GpxExporter.GPX_FILE} ($n Punkte)")
        } catch (e: Exception) {
            Log.e(TAG, "GPX-Export fehlgeschlagen", e)
        }

        // Auto-Delete kurzer leerer Sessions (T60)
        val minSec = appPreferences.autoDeleteMinSeconds
        val durationSec = if (updatedMetadata.sampleRate > 0)
            updatedMetadata.totalRecordedSamples.toDouble() / updatedMetadata.sampleRate else 0.0
        if (shouldAutoDelete(durationSec, updatedMetadata.totalDetections, minSec, appPreferences.autoDeleteShortEmptySessions)) {
            Log.i(TAG, "Auto-Delete: Session=${updatedMetadata.sessionId} " +
                "dauer=${"%.1f".format(durationSec)}s detections=0 < ${minSec}s")
            deleteSessionFolder(a.dir, a.doc)
        }

        active = null
        _sessionEnded.tryEmit(Unit)  // T51: Analyse-Tab Auto-Refresh
    }

    /**
     * T251 (N2, Zusatz T249): Letzte Updates in die Zusatzdatei, Writer schliessen, Zusatzdatei einmal in
     * detections.jsonl zusammenfuehren. Scheitert das Zusammenfuehren, bleibt die Zusatzdatei liegen und wird
     * beim naechsten Oeffnen nachgeholt (nichts verloren). War schon die Zusatzdatei nicht beschreibbar, gehen
     * die restlichen Updates direkt ins Zusammenfuehren. Aufrufer haelt den fileMutex.
     * @return Anzahl endgueltig verlorener Updates
     */
    private suspend fun finishDetectionsLocked(a: ActiveSession): Int {
        val sideError = retryFlush(END_FLUSH_ATTEMPTS, END_FLUSH_RETRY_MS) { a.flushUpdatesLocked() }
        a.closeDetectionWritersLocked()
        val extra = if (sideError != null) updates.pendingWithLine() else emptyMap()
        val withoutLine = updates.size() - extra.size
        val mergeError = retryFlush(END_FLUSH_ATTEMPTS, END_FLUSH_RETRY_MS) {
            try { DetectionUpdateLog.consolidate(a.files(), extra); null } catch (e: Exception) { e }
        }
        updates.reset()
        if (withoutLine > 0) {
            // Zeile nie angehaengt (z.B. Detektion nach dem letzten Append verworfen): nichts zu ersetzen
            Log.w(TAG, "Session-Ende: $withoutLine Updates ohne Zeile in detections.jsonl verworfen")
        }
        if (mergeError == null) return 0
        return if (extra.isNotEmpty()) {
            Log.e(TAG, "Session-Ende: ${extra.size} Updates nach $END_FLUSH_ATTEMPTS Versuchen nicht geschrieben", mergeError)
            extra.size
        } else {
            Log.w(TAG, "Session-Ende: ${DetectionUpdateLog.FILE} bleibt, wird beim naechsten Oeffnen zusammengefuehrt", mergeError)
            0
        }
    }

    // ── Aktive Session: Detektionen und Verifikationen ────────────────

    /** Detektionen an detections.jsonl anhaengen (nach jeder Inferenz). */
    suspend fun appendDetections(detections: List<DetectionResult>) = withContext(Dispatchers.IO) {
        fileMutex.withLock { active?.appendDetectionsLocked(detections) }
        Unit
    }

    /** Verifikations-Event an verifications.jsonl der aktiven Session anhaengen (append-only). */
    suspend fun appendVerification(event: VerificationEvent) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            active?.appendLocked(VERIFICATIONS_FILE, SessionJson.compact.encodeToString(event) + "\n")
        }
        Unit
    }

    /**
     * Letztes Status-Ereignis einer Detektion aus verifications.jsonl der aktiven Session entfernen (Undo
     * Live-Tab, T52/T117). Idempotent, Fehler werden geloggt.
     */
    suspend fun removeLastVerification(
        detectionId: String,
        /** T249 (M6): nur das letzte Ereignis genau dieses Typs entfernen (Undo der Alternative) */
        actionType: String? = null
    ) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val a = active ?: return@withLock
            try {
                removeLastStatusEventLocked(a.files(), VERIFICATIONS_FILE, detectionId, actionType)
            } catch (e: Exception) {
                Log.e(TAG, "removeLastVerification fehlgeschlagen: $detectionId", e)
            }
        }
    }

    /** T237/T251 (N7): eine Fassung fuer Live und Analyse; schreibt atomar (File temp + Move, SAF Schattenkopie). */
    private fun removeLastStatusEventLocked(files: SessionFiles, name: String, detectionId: String, actionType: String?) {
        val text = files.readText(name) ?: return
        val out = removeLastStatusEventText(text, detectionId, actionType) ?: return
        files.writeReplacing(name, out)
    }

    /**
     * T236: Update einer bereits geschriebenen Detektion vormerken (Zweitmodell, Cluster-Merge, GPS-Fix).
     * Kein Dateizugriff. Geschrieben wird gebuendelt mit [flushDetectionUpdates] (Pipeline ca. alle 10 s),
     * T251: append-only in die Zusatzdatei. Pro ID gilt der zuletzt gemeldete Stand.
     */
    fun queueDetectionUpdate(updated: DetectionResult) {
        if (!isActive) return
        updates.put(updated)
    }

    /** T236: Gesammelte Updates jetzt schreiben (no-op ohne Updates). */
    suspend fun flushDetectionUpdates() = withContext(Dispatchers.IO) {
        if (updates.isEmpty()) return@withContext
        fileMutex.withLock { active?.flushUpdatesLocked() }
        Unit
    }

    /**
     * T249 (M1): Nutzer-Aktionen im Live-Tab. Der Aufrufer merkt den aktuellen Listenstand vorher unter der
     * Listen-Sperre vor, dann wird hier sofort geschrieben. Bei Fehler bleibt das Update vorgemerkt und der
     * Fehler wird geworfen.
     */
    suspend fun flushDetectionUpdatesOrThrow() = withContext(Dispatchers.IO) {
        if (updates.isEmpty()) return@withContext
        fileMutex.withLock { active?.flushUpdatesLocked() }?.let { throw it }
        Unit
    }

    /**
     * Detektion der aktiven Session sofort ersetzen (zusammen mit allen gesammelten Updates).
     * Silent no-op ohne aktive Session; ist die Zeile noch nicht angehaengt, bleibt das Update vorgemerkt.
     */
    suspend fun replaceDetection(updated: DetectionResult) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val a = active ?: return@withLock
            updates.put(updated)
            a.flushUpdatesLocked()
        }
        Unit
    }

    // ── Zugriffe auf beliebige Session-Ordner (Analyse, Karte, Rescan) ────
    // Laufen ueber denselben fileMutex wie die Live-Pfade. Fehler werden geworfen (Aufrufer: Rollback + Meldung).

    private fun isActiveDir(sessionDir: File): Boolean = active?.matches(sessionDir) == true

    /** DocumentFile der Session, falls SAF-Session (gelistet oder aktiv); sonst null. */
    fun docFor(sessionDir: File): DocumentFile? =
        locator.listedDoc(sessionDir) ?: active?.doc?.takeIf { it.name == sessionDir.name }

    /** Dateizugriff passend zum Speichermodus der Session. */
    private fun accessFor(sessionDir: File): SessionFiles =
        docFor(sessionDir)?.let { DocSessionFiles(context, it) } ?: FileSessionFiles(sessionDir)

    /** Dokument-IDs der PIROL-Basis (SAF-Basis und/oder Datei-Basis), T181. */
    fun baseDocumentIds(): List<String> = locator.baseDocumentIds()

    /** Dokument-ID des Session-Ordners (SAF-Dokument oder Dateipfad), null wenn nicht ermittelbar. */
    fun sessionDocumentId(sessionDir: File): String? = locator.sessionDocumentId(sessionDir, docFor(sessionDir))

    /**
     * T249 (H2): Datei einer Session lesen; Lesen schreibt nie. detections.jsonl kommt mit den Updates
     * der Zusatzdatei (T251). Aktive Session: unter dem fileMutex, vorher eigene Updates anhaengen.
     * T251 (Zusatz T249): SAF-Sessions ebenfalls unter dem fileMutex — sonst kann ein Leser zwischen
     * Marken-Pruefung und Lesen ein gerade ueberschriebenes Original halb sehen. File-Modus ersetzt per
     * atomarem Move, dort genuegt Lesen ohne Sperre. Nicht unter dem fileMutex aufrufen (nicht reentrant).
     */
    private suspend fun readSessionText(sessionDir: File, name: String): String? {
        val a = active
        if (a != null && a.matches(sessionDir)) {
            return fileMutex.withLock {
                if (name == DETECTIONS_FILE) a.flushUpdatesLocked()
                a.flushWritersLocked()
                readMerged(a.files(), name)
            }
        }
        val access = accessFor(sessionDir)
        return if (access is DocSessionFiles) fileMutex.withLock { readMerged(access, name) } else readMerged(access, name)
    }

    /**
     * T253: Zusammengefuehrte detections.jsonl fuer ZIP-Upload/-Export. Nicht aktive Session: liegengebliebene
     * Zusatzdatei vorher in detections.jsonl uebernehmen (unter Sperre, wie beim Oeffnen). Aktive Session:
     * vorher flushen und den zusammengefuehrten Stand liefern, die laufende Datei bleibt unveraendert.
     * Scheitert das Uebernehmen, liefert das Lesen trotzdem den zusammengefuehrten Stand. null = Datei fehlt.
     */
    suspend fun detectionsForExport(sessionDir: File): String? = withContext(Dispatchers.IO) {
        if (!isActiveDir(sessionDir)) {
            try {
                fileMutex.withLock {
                    if (isActiveDir(sessionDir)) return@withLock
                    val access = accessFor(sessionDir)
                    if (access.exists(DetectionUpdateLog.FILE)) DetectionUpdateLog.consolidate(access)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Export: Detektions-Updates nicht uebernommen: ${sessionDir.name}", e)
            }
        }
        readSessionText(sessionDir, DETECTIONS_FILE)
    }

    /** T221: Zugriff auf die Dateien einer Session unter dem fileMutex (Rescan-Varianten schreiben/loeschen). */
    suspend fun <T> withSessionFiles(sessionDir: File, block: (SessionFiles) -> T): T = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            active?.takeIf { it.matches(sessionDir) }?.flushUpdatesLocked()
            block(accessFor(sessionDir))
        }
    }

    /**
     * T303 (Befund 3): tatsaechliches Schreibziel fuer [variant] (unter dem fileMutex): Variante vorhanden -> sie,
     * ins Original uebernommen -> Original (Write(null)), geloescht -> Drop (Aktion verwerfen, nichts anlegen).
     */
    private fun writeTargetLocked(sessionDir: File, variant: String?): VariantWriteTarget.Target {
        if (variant == null) return VariantWriteTarget.Target.Write(null)
        val t = VariantWriteTarget.resolve(
            variant,
            metaExists = accessFor(sessionDir).exists(RescanFiles.meta(variant)),
            promoted = ch.etasystems.pirol.rescan.PromotedVariants.contains(sessionDir, variant)
        )
        when (t) {
            is VariantWriteTarget.Target.Write ->
                if (t.variant == null) Log.i(TAG, "T303: Variante $variant ist uebernommen, schreibe ins Original")
            VariantWriteTarget.Target.Drop -> Log.w(TAG, "T303: Variante $variant existiert nicht mehr, Aktion verworfen")
        }
        return t
    }

    /** Fertige JSONL-Zeile (inkl. Zeilenende) an verifications.jsonl der Session (T221: oder Variante) anhaengen. */
    suspend fun appendVerificationLine(sessionDir: File, line: String, variant: String? = null) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val t = writeTargetLocked(sessionDir, variant)
            if (t is VariantWriteTarget.Target.Write) accessFor(sessionDir).append(RescanFiles.verifications(t.variant), line)
        }
    }

    /** Fertige JSONL-Zeile an detections.jsonl der Session (T221: oder der Variante) anhaengen. */
    suspend fun appendDetectionLine(sessionDir: File, line: String, variant: String? = null) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val a = active
            val t = writeTargetLocked(sessionDir, variant)  // T303
            val v = (t as? VariantWriteTarget.Target.Write)?.variant
            when {
                t !is VariantWriteTarget.Target.Write -> Unit
                v != null -> accessFor(sessionDir).append(RescanFiles.detections(v), line)
                a != null && a.matches(sessionDir) && a.appendDetectionLineLocked(line) -> Unit  // offener Writer
                else -> accessFor(sessionDir).append(DETECTIONS_FILE, line)
            }
        }
    }

    /** T237: Letztes Status-Ereignis aus verifications.jsonl der Session (T221: oder Variante) entfernen (Analyse-Undo). */
    suspend fun removeLastVerificationIn(
        sessionDir: File,
        detectionId: String,
        variant: String? = null,
        /** T249 (M6): nur das letzte Ereignis genau dieses Typs entfernen; null = letztes Status-Ereignis */
        actionType: String? = null
    ) = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            val t = writeTargetLocked(sessionDir, variant)  // T303
            if (t is VariantWriteTarget.Target.Write) {
                removeLastStatusEventLocked(accessFor(sessionDir), RescanFiles.verifications(t.variant), detectionId, actionType)
            }
        }
    }

    /**
     * T251 (Zusatz T249, M1): Detektion [id] der Session (T221: oder Variante) aendern. [transform] wird auf
     * den AKTUELLEN Stand unter der Sperre angewendet, nie auf eine aeltere Kopie des Aufrufers:
     * - aktive Session: auf die Live-Liste (falls dort vorhanden), sonst auf Warteschlange/Zusatzdatei/Datei;
     *   geschrieben append-only in die Zusatzdatei
     * - andere Session: auf den gelesenen Stand, ein Rewrite (Zusatzdatei wird dabei mit uebernommen)
     * @return neue Fassung oder null (Datei/Detektion fehlt)
     */
    suspend fun modifyDetectionIn(
        sessionDir: File,
        id: String,
        variant: String? = null,
        transform: (DetectionResult) -> DetectionResult
    ): DetectionResult? = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            // T303: uebernommene Variante -> Original, geloeschte -> nichts schreiben
            val target = writeTargetLocked(sessionDir, variant) as? VariantWriteTarget.Target.Write ?: return@withLock null
            val v = target.variant
            val a = active
            if (v == null && a != null && a.matches(sessionDir)) {
                val list = liveDetections
                val live = list?.modifyDetection(id) { transform(it) }
                val next = if (list != null && live != null) {
                    // Listenstand unter der Listen-Sperre vormerken (Reihenfolge wie die Pipeline, T236)
                    list.withCurrent(id) { updates.put(it) }
                    live
                } else {
                    a.currentLocked(id)?.let(transform)?.also { updates.put(it) } ?: return@withLock null
                }
                a.flushUpdatesLocked()?.let { throw it }
                return@withLock next
            }
            val access = accessFor(sessionDir)
            if (v != null) {
                // T221/T260: Variante (auch waehrend des Rescans) auf dem zusammengefuehrten Stand aendern und
                // mit ihrer Zusatzdatei in einem Rewrite uebernehmen (File atomar, SAF Schattenkopie)
                val name = RescanFiles.detections(v)
                val text = DetectionUpdateLog.mergedText(access, name) ?: return@withLock null
                val next = findDetection(text, id)?.let(transform) ?: return@withLock null
                DetectionUpdateLog.consolidate(access, mapOf(id to next), name)
                return@withLock next
            }
            val text = DetectionUpdateLog.mergedText(access) ?: return@withLock null
            val next = findDetection(text, id)?.let(transform) ?: return@withLock null
            DetectionUpdateLog.consolidate(access, mapOf(id to next))
            next
        }
    }

    /** T191: Fertige Zeile an track.jsonl der aktiven Session anhaengen (no-op ohne Session). */
    suspend fun appendTrackLine(line: String) = withContext(Dispatchers.IO) {
        fileMutex.withLock { active?.appendLocked(TrackLogic.TRACK_FILE, line) }
        Unit
    }

    /** T261: Zeilen an human_windows.jsonl der aktiven Session anhaengen (no-op ohne Session). */
    suspend fun appendHumanWindowLines(text: String) = withContext(Dispatchers.IO) {
        if (text.isEmpty()) return@withContext
        fileMutex.withLock { active?.appendLocked(ch.etasystems.pirol.audio.export.HumanWindows.FILE, text) }
        Unit
    }

    /** T261: Zeilen an human_windows.jsonl einer beliebigen Session anhaengen (Rescan nutzt [withSessionFiles], Export-Scan dies). */
    suspend fun appendHumanWindowLinesTo(sessionDir: File, text: String) = withContext(Dispatchers.IO) {
        if (text.isEmpty()) return@withContext
        fileMutex.withLock { accessFor(sessionDir).append(ch.etasystems.pirol.audio.export.HumanWindows.FILE, text) }
    }

    /** T261: Merkliste der Mensch-Fenster (null = keine). */
    suspend fun readHumanWindows(sessionDir: File): String? = withContext(Dispatchers.IO) {
        try { readSessionText(sessionDir, ch.etasystems.pirol.audio.export.HumanWindows.FILE) } catch (_: Exception) { null }
    }

    /** T263: Zeitraeume (Wanduhr) der noch vorhandenen, live aufgenommenen Sprachmemos (leer = keine/unbekannt). */
    suspend fun loadMemoWallSpans(sessionDir: File): List<ch.etasystems.pirol.audio.export.MemoExclusion.WallSpan> =
        withContext(Dispatchers.IO) {
            val text = readVerifications(sessionDir, null) ?: return@withContext emptyList()
            ch.etasystems.pirol.audio.export.MemoExclusion.wallSpans(SessionTextParser.events(text))
        }

    // ── T264: Session-Memos ───────────────────────────────────────────

    /**
     * T264: Ziel einer neuen Session-Memo-Aufnahme in der aktiven Session.
     * @param sessionKey Session-Ordner (File-Modus) bzw. Platzhalter mit dem Ordnernamen (SAF), fuer [appendSessionMemo]
     * @param fileName   Dateiname in memos/ (`session_<yyyyMMdd-HHmmss>[-n].wav`)
     * @param file       Zieldatei (File-Modus) oder null
     * @param uri        Ziel-Dokument (SAF-Modus) oder null
     */
    class SessionMemoTarget(val sessionKey: File, val fileName: String, val file: File?, val uri: android.net.Uri?)

    /** T264: Ziel fuer ein Session-Memo anlegen (Name nach Startzeit, Kollision -2, -3 ...); null ohne aktive Session. */
    suspend fun newSessionMemoTarget(epochMs: Long): SessionMemoTarget? = withContext(Dispatchers.IO) {
        val a = active ?: return@withContext null
        val zone = ZoneId.systemDefault()
        val dir = a.dir
        val doc = a.doc
        if (doc != null) {
            val memos = doc.findFile(SessionMemos.DIR) ?: doc.createDirectory(SessionMemos.DIR) ?: return@withContext null
            val name = SessionMemos.fileName(epochMs, zone) { memos.findFile(it) != null }
            val created = memos.createFile("audio/wav", name) ?: return@withContext null
            // Provider koennen den Namen anpassen; der tatsaechliche Name zaehlt
            val key = locator.resolveSessionDirFromDoc(doc) ?: doc.name?.let { SessionFilesLogic.safKey(null, it) }
                ?: return@withContext null
            SessionMemoTarget(key, created.name ?: name, null, created.uri)
        } else if (dir != null) {
            val memos = File(dir, SessionMemos.DIR).apply { mkdirs() }
            val name = SessionMemos.fileName(epochMs, zone) { File(memos, it).exists() }
            SessionMemoTarget(dir, name, File(memos, name), null)
        } else null
    }

    /** T264: Memo-Datei wieder loeschen (zu kurz/fehlgeschlagen). */
    suspend fun discardSessionMemo(target: SessionMemoTarget) = withContext(Dispatchers.IO) {
        try {
            target.file?.delete()
            target.uri?.let { DocumentFile.fromSingleUri(context, it)?.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "T264: Memo-Datei nicht geloescht", e)
        }
        Unit
    }

    /** T264: Eintrag an memos.jsonl anhaengen (aktive Session; nach dem Ende direkt in den Ordner). */
    suspend fun appendSessionMemo(sessionKey: File, memo: SessionMemo) = withContext(Dispatchers.IO) {
        val line = SessionMemos.encode(memo)
        fileMutex.withLock {
            val a = active
            if (a != null && a.matches(sessionKey)) a.appendLocked(SessionMemos.FILE, line)
            else accessFor(sessionKey).append(SessionMemos.FILE, line)
        }
        _sessionMemoEvents.tryEmit(SessionMemoEvent.Added(sessionKey.name, memo))  // T299
    }

    /** T264: Session-Memos einer Session (leer = keine/unlesbar). */
    suspend fun loadSessionMemos(sessionDir: File): List<SessionMemo> = withContext(Dispatchers.IO) {
        SessionMemos.parse(try { readSessionText(sessionDir, SessionMemos.FILE) } catch (_: Exception) { null })
    }

    /**
     * T272: Session-Memo endgueltig loeschen: Eintrag aus memos.jsonl (atomar neu geschrieben, keine Marker-Zeile)
     * und memos/session_*.wav. Datei zuerst aus dem Verzeichnis nehmen waere unumkehrbar; deshalb zuerst die Liste,
     * dann die WAV. Nur Dateien unter memos/ werden angefasst.
     * T303: Scheitert das Loeschen der WAV (delete() = false), wird der Eintrag wiederhergestellt und false
     * gemeldet (Memo bleibt sichtbar und erneut loeschbar). Fehlt die Datei schon, gilt das Memo als geloescht;
     * eine nicht referenzierte WAV geht nie in einen Export ([SessionExportRules.referencedMemos]).
     * Danach Raven-Dateien (Original und Varianten, nur vorhandene) neu schreiben (Memo-Zeile entfaellt).
     */
    suspend fun deleteSessionMemo(sessionDir: File, memoFile: String): Boolean = withContext(Dispatchers.IO) {
        if (!memoFile.startsWith(SessionMemos.DIR + "/") || memoFile.contains("..")) return@withContext false
        // Die gerade aufgenommene Session wird nie umgeschrieben: ein Neuschreiben koennte ein parallel angehaengtes Memo verlieren
        if (isActiveSession(sessionDir)) return@withContext false
        var removedLines = ""
        var entryRemoved = false
        try {
            fileMutex.withLock {
                val a = active
                val files = if (a != null && a.matches(sessionDir)) a.files().also { a.flushWritersLocked() } else accessFor(sessionDir)
                val old = files.readText(SessionMemos.FILE)
                val updated = SessionMemos.withoutFile(old, memoFile)
                removedLines = SessionMemos.linesOfFile(old, memoFile)
                if (updated != (old ?: "")) {
                    files.writeReplacing(SessionMemos.FILE, updated)
                    entryRemoved = true
                }
            }
            val doc = docFor(sessionDir)
            val deleted = if (doc != null) {
                var cur: DocumentFile? = doc
                for (part in memoFile.split('/').filter { it.isNotEmpty() }) cur = cur?.findFile(part)
                cur?.delete() ?: true  // nicht (mehr) vorhanden
            } else {
                File(sessionDir, memoFile).takeIf { it.isFile }?.delete() ?: true
            }
            if (!deleted) {
                // T303: Eintrag zurueck, damit das Memo sichtbar und erneut loeschbar bleibt (kein stilles Weiterexportieren)
                restoreMemoEntry(sessionDir, entryRemoved, removedLines)
                Log.e(TAG, "T303: Memo-Datei nicht geloescht, Eintrag wiederhergestellt: $memoFile")
                return@withContext false
            }
            _sessionMemoEvents.tryEmit(SessionMemoEvent.Removed(sessionDir.name, memoFile))  // T299
        } catch (e: Exception) {
            Log.e(TAG, "T272: Session-Memo nicht geloescht", e)
            restoreMemoEntry(sessionDir, entryRemoved, removedLines)  // T303: auch wenn das Loeschen wirft
            return@withContext false
        }
        rewriteRavenTables(sessionDir)  // T303 (Befund 6)
        true
    }

    /** T303: entfernten memos.jsonl-Eintrag wieder anhaengen (WAV liegt noch da); Fehler nur protokolliert. */
    private suspend fun restoreMemoEntry(sessionDir: File, entryRemoved: Boolean, lines: String) {
        if (!entryRemoved || lines.isEmpty()) return
        try { fileMutex.withLock { accessFor(sessionDir).append(SessionMemos.FILE, lines) } }
        catch (e: Exception) { Log.e(TAG, "T303: Memo-Eintrag nicht wiederhergestellt (WAV wird nicht exportiert)", e) }
    }

    /** T303: vorhandene Raven-Dateien (Original und Varianten) neu schreiben; Fehler nur protokolliert. */
    private suspend fun rewriteRavenTables(sessionDir: File) {
        val names = try { accessFor(sessionDir).listNames() } catch (e: Exception) { return }
        for (v in listOf<String?>(null) + RescanFiles.idsIn(names)) {
            if (RescanFiles.raven(v) !in names) continue
            try { exportRavenSelectionTable(sessionDir, appPreferences, v) }
            catch (e: Exception) { Log.e(TAG, "Raven-Re-Export nach Memo-Loeschen fehlgeschlagen (${v ?: "Original"})", e) }
        }
    }

    /**
     * T264: Art-Memo-Zeitraeume (Wanduhr) je Detektion aus den MEMO_RECORDED-Events des Originals
     * (nur noch vorhandene Memos mit gespeichertem Beginn und Ende).
     */
    suspend fun loadMemoWallSpansByDetection(sessionDir: File): Map<String, ch.etasystems.pirol.audio.export.MemoExclusion.WallSpan> =
        withContext(Dispatchers.IO) {
            val text = readVerifications(sessionDir, null) ?: return@withContext emptyMap()
            ch.etasystems.pirol.audio.export.MemoExclusion.wallSpansByDetection(SessionTextParser.events(text))
        }

    /** T264: Abspielquelle einer Memo-Datei ([relPath] relativ zum Session-Ordner): File, Uri (SAF) oder null. */
    fun memoSource(sessionDir: File, relPath: String): Any? {
        val doc = docFor(sessionDir)
        if (doc != null) {
            var cur: DocumentFile = doc
            for (part in relPath.split('/').filter { it.isNotEmpty() }) cur = cur.findFile(part) ?: return null
            return cur.uri
        }
        return File(sessionDir, relPath).takeIf { it.isFile }
    }

    /** T191: Route einer Session aus track.jsonl (leer, wenn keine Spur oder unlesbar). */
    suspend fun loadTrack(sessionDir: File): List<TrackPoint> = withContext(Dispatchers.IO) {
        val text = try { readSessionText(sessionDir, TrackLogic.TRACK_FILE) } catch (_: Exception) { null }
        text?.let { TrackLogic.parseLines(it) } ?: emptyList()
    }

    /** T131: Sprach-Memo einer Detektion loeschen (File oder SAF). */
    suspend fun deleteMemo(sessionDir: File, detectionId: String) = withContext(Dispatchers.IO) {
        val doc = docFor(sessionDir)
        if (doc != null) doc.findFile("memos")?.findFile("$detectionId.wav")?.delete()
        else File(sessionDir, "memos/$detectionId.wav").takeIf { it.exists() }?.delete()
        Unit
    }

    /** T69: File-Pfad fuer ein Sprach-Memo (File-Modus); erstellt memos/ falls noetig. */
    fun memoFile(sessionDir: File, detectionId: String): File =
        File(sessionDir, "memos/$detectionId.wav").apply { parentFile?.mkdirs() }

    /** T69: DocumentFile fuer ein Sprach-Memo (SAF-Modus); erstellt memos/ und die WAV-Datei falls noetig. */
    fun memoDocFile(sessionDoc: DocumentFile, detectionId: String): DocumentFile? {
        val memos = sessionDoc.findFile("memos")
            ?: sessionDoc.createDirectory("memos") ?: return null
        return memos.findFile("$detectionId.wav")
            ?: memos.createFile("audio/wav", "$detectionId.wav")
    }

    // ── Audio ─────────────────────────────────────────────────────────

    /** Audio-Samples an die WAV der aktiven Session anhaengen (Daueraufnahme, T51; Rotation T57-B1). */
    suspend fun appendAudioSamples(samples: ShortArray) = withContext(Dispatchers.IO) {
        active?.appendAudio(samples, appPreferences.maxRecordingMinutes * 60f)
        Unit
    }

    /** T118: Bisher in die WAV-Segmente der aktiven Session geschriebene Samples. */
    val recordedSampleCount: Long get() = active?.recordedSampleCount ?: 0L

    /** Aktueller Schreib-Offset der Session in Sekunden (T130: session-relativ ueber alle Segmente); 0 ohne Session. */
    fun getCurrentRecordingOffsetSec(): Float = active?.offsetSec() ?: 0f

    // ── Listen, Loeschen, Wiederherstellen ────────────────────────────

    /** Alle vorhandenen Session-Ordner (neueste zuerst). */
    fun listSessions(): List<File> = locator.listSessions()

    /**
     * T251 (M5): Session-Ordner zu einer Session-ID (Ordnername = Session-ID), ohne alle session.json zu lesen.
     * null = nicht gefunden.
     */
    suspend fun findSessionDir(sessionId: String): File? = withContext(Dispatchers.IO) {
        locator.findBySessionId(sessionId)
    }

    // ── Import (T255) ─────────────────────────────────────────────────

    /**
     * T255: Arbeitsordner fuer einen Import in der Basis des Speicherorts neuer Sessions (SAF bevorzugt, sonst
     * Datei-Basis, wie [startSession]). Der Tagesordner wird erst beim Fertigstellen gewaehlt ([importDayDir]).
     */
    suspend fun createImportStaging(): ImportStaging = withContext(Dispatchers.IO) {
        val name = ImportStaging.PREFIX + UUID.randomUUID().toString().take(8)
        val baseDoc = if (appPreferences.storageBaseUri != null) locator.resolveBaseDocFile() else null
        if (baseDoc != null && baseDoc.canWrite()) {
            val doc = baseDoc.createDirectory(name) ?: throw IllegalStateException("SAF: Import-Ordner nicht erstellbar")
            DocImportStaging(context, baseDoc, doc)
        } else {
            val base = locator.resolveBaseFile()
            val dir = File(base, name)
            if (!dir.mkdirs()) throw IllegalStateException("Import-Ordner nicht erstellbar: ${dir.absolutePath}")
            FileImportStaging(base, dir)
        }
    }

    /** T255: Tagesordner fuer eine importierte Session (Aufnahmedatum), null wenn Tagesordner ausgeschaltet. */
    fun importDayDir(recordingDate: String?): String? = recordingDate?.takeIf { appPreferences.storageDailySubfolder }

    /** T255: Gibt es schon eine Session mit dieser ID (= Ordnername)? */
    suspend fun isSessionIdTaken(sessionId: String): Boolean = findSessionDir(sessionId) != null

    /** T255: Reste abgebrochener Importe entfernen (nur ohne laufenden Import). */
    suspend fun removeImportStaging() = withContext(Dispatchers.IO) { locator.removeStaging() }

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

    /** T180: true, wenn [sessionDir] die gerade laufende Aufnahme-Session ist (nicht loesch-/verschiebbar). */
    fun isActiveSession(sessionDir: File): Boolean {
        val a = active ?: return false
        return a.dir?.path == sessionDir.path || a.doc?.name == sessionDir.name
    }

    /** T180: Entfernt leere Tagesordner (YYYY-MM-DD) in der Basis (SAF und File). */
    suspend fun removeEmptyDayDirs() = withContext(Dispatchers.IO) { locator.removeEmptyDayDirs() }

    /** Loescht eine Session (Ordner + alle Dateien). */
    suspend fun deleteSession(sessionDir: File): Boolean = withContext(Dispatchers.IO) {
        // T131: SAF-Session (gelistet) ueber DocumentFile loeschen
        docFor(sessionDir)?.takeIf { active?.doc?.name != it.name }?.let { doc ->
            val ok = doc.delete()
            locator.forget(sessionDir)
            return@withContext ok
        }
        if (!sessionDir.exists()) return@withContext false
        val deleted = sessionDir.deleteRecursively()
        locator.forget(sessionDir)
        Log.i(TAG, "Session geloescht: ${sessionDir.name} (erfolg=$deleted)")
        deleted
    }

    /**
     * T65: Crash-Recovery beim Analyse-Start (nie fuer die aktive Session):
     * - T249 (H2): Reste unterbrochener Schattenkopien (SAF) aufraeumen
     * - T251 (N2): liegengebliebene Zusatzdatei der Detektions-Updates in detections.jsonl uebernehmen
     * - verwaiste Sessions (endedAt == null): WAV-Header reparieren, session.json mit endedAt (T130)
     * @return Anzahl reparierter Sessions
     */
    suspend fun recoverOrphanSessions(): Int = withContext(Dispatchers.IO) {
        var recovered = 0
        val sessions = listSessions()
        val editsResumed = mutableListOf<Triple<File, String, String?>>()  // T257; T273: + alte ID
        for (sessionDir in sessions) {
            try {
                fileMutex.withLock {
                    if (isActiveDir(sessionDir)) return@withLock
                    val access = accessFor(sessionDir)
                    val n = (access as? DocSessionFiles)?.recoverAll() ?: 0
                    if (n > 0) Log.i(TAG, "recoverOrphan: $n unterbrochene Ersetzungen aufgeloest: ${sessionDir.name}")
                    if (access.exists(DetectionUpdateLog.FILE)) {
                        DetectionUpdateLog.consolidate(access)
                        Log.i(TAG, "recoverOrphan: Detektions-Updates zusammengefuehrt: ${sessionDir.name}")
                    }
                    // T257: abgebrochene "Angaben bearbeiten" zu Ende fuehren (ohne doppelte Verschiebung)
                    if (access.exists(SessionInfoEdit.JOURNAL_FILE)) {
                        // T273: evtl. mit Ordner-Umbenennung (Auftrag wandert mit dem Ordner)
                        val folder = folderFor(sessionDir)
                        SessionInfoEdit.resumePending(access, SESSION_JSON, folder) { logRename(sessionDir, it) }?.let {
                            editsResumed += Triple(folder?.key ?: sessionDir, it.metadata.sessionId, it.rename?.oldName)
                        }
                        folder?.let { relocated(sessionDir, it) }
                        Log.i(TAG, "recoverOrphan: Bearbeitung der Angaben abgeschlossen: ${sessionDir.name}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "recoverOrphan: Reste pruefen fehlgeschlagen: ${sessionDir.name}", e)
            }
        }
        // T257: Exporte der fertig bearbeiteten Sessions nachziehen (Upload-Namen verwerfen; Name evtl. geaendert)
        editsResumed.forEach { (dir, id, oldId) -> afterInfoEdit(dir, id, appPreferences, forgetNames = true, oldSessionId = oldId) }
        if (editsResumed.any { it.third != null }) locator.removeEmptyDayDirs()
        sessions.forEach { sessionDir ->
            val jsonFile = File(sessionDir, SESSION_JSON)
            if (!jsonFile.exists()) return@forEach
            val metadata = try {
                SessionJson.lenient.decodeFromString<SessionMetadata>(jsonFile.readText())
            } catch (e: Exception) {
                Log.w(TAG, "recoverOrphan: session.json nicht lesbar: ${sessionDir.name}", e)
                return@forEach
            }
            if (metadata.endedAt != null) return@forEach  // Normal beendet
            // T130: laufende Session (Analyse-Tab waehrend der Aufnahme geoeffnet) nicht anfassen
            if (isActiveDir(sessionDir) || active?.metadata?.sessionId == metadata.sessionId) return@forEach

            Log.i(TAG, "recoverOrphan: verwaiste Session gefunden: ${sessionDir.name}")
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
            val detectionLines = runCatching {
                File(sessionDir, DETECTIONS_FILE).takeIf { it.exists() }
                    ?.useLines { lines -> lines.count { it.isNotBlank() } }
            }.getOrNull()
            val recoveredMeta = recoverMetadata(metadata, SegmentedRecording.segmentFiles(sessionDir), detectionLines)
            try {
                writeFileReplacing(jsonFile, SessionJson.pretty.encodeToString(recoveredMeta))
                Log.i(TAG, "recoverOrphan: session.json repariert: ${sessionDir.name} → endedAt=${recoveredMeta.endedAt}")
                recovered++
            } catch (e: Exception) {
                Log.e(TAG, "recoverOrphan: session.json Schreiben fehlgeschlagen: ${sessionDir.name}", e)
            }
        }
        recovered
    }

    // ── Lesen ─────────────────────────────────────────────────────────

    /** Metadaten einer Session aus session.json (T249: Lesen schreibt nie). */
    suspend fun loadMetadata(sessionDir: File): SessionMetadata? = withContext(Dispatchers.IO) {
        try {
            val text = readSessionText(sessionDir, SESSION_JSON) ?: return@withContext null
            SessionJson.lenient.decodeFromString<SessionMetadata>(text)
        } catch (e: Exception) {
            Log.e(TAG, "session.json parsen fehlgeschlagen: ${sessionDir.name}", e)
            null
        }
    }

    /**
     * T109: Bemerkung einer bestehenden Session aendern (session.json atomar ersetzen, File oder SAF).
     * Schreibt eine vorhandene recording.selections.txt neu (Spalte Session-Bemerkung).
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
            fileMutex.withLock { access.writeReplacing(SESSION_JSON, SessionJson.pretty.encodeToString(updated)) }
        } catch (e: Exception) {
            Log.e(TAG, "updateSessionNote: session.json Schreiben fehlgeschlagen: ${sessionDir.name}", e)
            return@withContext null
        }
        if (access.exists(RescanFiles.RAVEN)) {
            try { exportRavenSelectionTable(sessionDir, appPreferences) }
            catch (e: Exception) { Log.e(TAG, "updateSessionNote: Raven-Re-Export fehlgeschlagen", e) }
        }
        updated
    }

    /** T257: Ergebnis von [updateImportedSessionInfo] */
    sealed class InfoEditResult {
        /**
         * T273: [sessionDir] = Schluessel nach Umbenennen/Verschieben (sonst der bisherige).
         * T303: [renameFailed] = Zeitdaten gespeichert, Ordner aber nicht umbenannt (Session behaelt ihre ID).
         */
        data class Ok(val metadata: SessionMetadata, val sessionDir: File? = null, val renameFailed: Boolean = false) : InfoEditResult()
        /** T273: Upload dieser Session laeuft/wartet; Datum (Ordnername) erst danach aenderbar */
        data object Busy : InfoEditResult()
        /** Session ist die laufende Aufnahme */
        data object Active : InfoEditResult()
        /** keine importierte Session (importedFrom == null) oder session.json/Startzeit nicht lesbar */
        data object NotEditable : InfoEditResult()
        data class Failed(val error: Exception) : InfoEditResult()
    }

    /**
     * T257: Startzeit, Ort und Bemerkung einer importierten Session aendern ([SessionInfoEdit]).
     * Unter dem fileMutex: liegengebliebenen Auftrag abschliessen, Detektions-Updates uebernehmen, Auftrag
     * schreiben, Dateien umrechnen (je atomar), session.json zuletzt. Danach Raven-Tabellen (Original und
     * Varianten, nur vorhandene) und track.gpx neu, reservierte Upload-Namen der Session verwerfen.
     * @param newLatitude/newLongitude beide null = Ort entfernen
     */
    suspend fun updateImportedSessionInfo(
        sessionDir: File,
        newStart: ZonedDateTime,
        newLatitude: Double?,
        newLongitude: Double?,
        note: String?,
        appPreferences: AppPreferences? = null
    ): InfoEditResult = withContext(Dispatchers.IO) {
        if (isActiveDir(sessionDir)) return@withContext InfoEditResult.Active
        var dir = sessionDir
        var access = accessFor(dir)
        var exportNameChanged = false
        var oldSessionId: String? = null
        val meta: InfoEditResult = try {
            fileMutex.withLock {
                if (isActiveDir(dir)) return@withLock InfoEditResult.Active
                if (access.exists(SessionInfoEdit.JOURNAL_FILE)) {
                    // T273: liegengebliebener Auftrag kann den Ordner umbenennen -> am neuen Ort weiterarbeiten
                    val pending = folderFor(dir)
                    SessionInfoEdit.resumePending(access, SESSION_JSON, pending) { logRename(dir, it) }
                    if (pending != null && pending.key.path != dir.path) {
                        relocated(dir, pending)
                        dir = pending.key
                        access = pending.files()
                    }
                }
                if (access.exists(DetectionUpdateLog.FILE)) DetectionUpdateLog.consolidate(access)
                val old = access.readText(SESSION_JSON)
                    ?.let { runCatching { SessionJson.lenient.decodeFromString<SessionMetadata>(it) }.getOrNull() }
                    ?: return@withLock InfoEditResult.NotEditable
                if (old.importedFrom == null) return@withLock InfoEditResult.NotEditable
                if (active?.metadata?.sessionId == old.sessionId) return@withLock InfoEditResult.Active
                val zone = ZoneId.systemDefault()
                val basePlan = SessionInfoEdit.plan(old, newStart, newLatitude, newLongitude, note, zone)
                    ?: return@withLock InfoEditResult.NotEditable
                // T273: neue Startzeit -> neue Session-ID (uuid6 bleibt) und Tagesordner; Ziel muss frei sein
                val folder = folderFor(dir)
                val target = folder?.let { f ->
                    SessionRename.target(f.name, f.day, newStart, zone, this@SessionManager.appPreferences.storageDailySubfolder)
                }?.takeIf { t -> t.newName == t.oldName || locator.findBySessionId(t.newName) == null }
                if (target != null && uploadPending(target.oldName)) return@withLock InfoEditResult.Busy
                val plan = SessionInfoEdit.withRename(basePlan, target)
                val prepared = SessionInfoEdit.prepare(access, plan, SESSION_JSON)
                var renameFailed = false  // T303
                val n = SessionInfoEdit.execute(access, prepared, SESSION_JSON, folder) {
                    logRename(dir, it)
                    if (it.renameError != null) renameFailed = true
                }
                if (folder != null && target != null) {
                    relocated(dir, folder)
                    dir = folder.key
                    if (!renameFailed) oldSessionId = target.oldName
                }
                exportNameChanged = target != null || ExportNames.sessionName(old.startedAt, old.note, old.sessionId) !=
                    ExportNames.sessionName(prepared.metadata.startedAt, prepared.metadata.note, old.sessionId)
                Log.i(TAG, "Angaben bearbeitet: ${old.sessionId} -> ${dir.name} delta=${plan.deltaMs} ms, Ort=${plan.relocate}, $n Dateien")
                // T303: gescheitertes Umbenennen -> session.json traegt den Ordnernamen als ID
                if (renameFailed && folder != null) InfoEditResult.Ok(prepared.metadata.copy(sessionId = folder.name), dir, renameFailed = true)
                else InfoEditResult.Ok(prepared.metadata, dir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateImportedSessionInfo fehlgeschlagen: ${sessionDir.name}", e)
            return@withContext InfoEditResult.Failed(e)
        }
        if (meta is InfoEditResult.Ok) {
            afterInfoEdit(dir, meta.metadata.sessionId, appPreferences, exportNameChanged, oldSessionId)
            if (oldSessionId != null) locator.removeEmptyDayDirs()
        }
        meta
    }

    /** T273: umbenenn-/verschiebbarer Ordner einer Session (File oder SAF); null = nicht ermittelbar */
    private fun folderFor(sessionDir: File): SessionFolder? {
        docFor(sessionDir)?.let { doc ->
            val base = locator.resolveBaseDocFile() ?: return null
            return DocSessionFolder(context, base, doc, DocSessionFolder.dayOfKey(sessionDir))
        }
        return FileSessionFolder.of(sessionDir)
    }

    /** T273: neuen Schluessel nach Umbenennen/Verschieben bekannt machen, alten vergessen */
    private fun relocated(old: File, folder: SessionFolder) {
        if (folder.key.path == old.path) return
        locator.forget(old)
        (folder as? DocSessionFolder)?.let { locator.remember(it.key, it.doc) }
    }

    private fun logRename(dir: File, o: SessionRename.Outcome) {
        if (o.renameError != null) Log.w(TAG, "T303: Session-Ordner ${dir.name} nicht umbenannt, Session behaelt ihre ID", o.renameError)
        else if (o.moveError != null) Log.w(TAG, "T273: Session ${dir.name} umbenannt, Verschieben in den Tagesordner nicht moeglich", o.moveError)
        else Log.i(TAG, "T273: Session-Ordner ${dir.name} umbenannt=${o.renamed} verschoben=${o.moved}")
    }

    /** T273: Upload dieser Session eingereiht, laufend oder wartend (Worker arbeitet mit dem Ordnerpfad) */
    private fun uploadPending(sessionName: String): Boolean {
        val s = ch.etasystems.pirol.data.sync.UploadStatusHolder.status.value
        return (s is ch.etasystems.pirol.data.sync.UploadStatus.InProgress && s.sessionId == sessionName) ||
            (s is ch.etasystems.pirol.data.sync.UploadStatus.AwaitingDecision && s.sessionId == sessionName) ||
            uploadQueue.hasPendingUpload(sessionName)  // T304: auch eingereihte/wartende Uploads
    }

    /**
     * T257: Exporte nach einer Bearbeitung neu schreiben (Fehler nur protokolliert). Aendert sich der Exportname
     * (Startzeit/Bemerkung), reservierte Upload-Namen der Session verwerfen (naechster Upload mit neuem Namen).
     */
    private suspend fun afterInfoEdit(
        sessionDir: File,
        sessionId: String,
        appPreferences: AppPreferences?,
        forgetNames: Boolean,
        /** T273: ID vor dem Umbenennen (deren reservierte Upload-Namen ebenfalls verwerfen) */
        oldSessionId: String? = null
    ) {
        val access = accessFor(sessionDir)
        val names = runCatching { access.listNames() }.getOrDefault(emptyList())
        val variants: List<String?> = listOf<String?>(null) + RescanFiles.idsIn(names)
        for (v in variants) {
            if (RescanFiles.raven(v) !in names) continue
            try { exportRavenSelectionTable(sessionDir, appPreferences, v) }
            catch (e: Exception) { Log.e(TAG, "Raven-Re-Export nach Bearbeitung fehlgeschlagen (${v ?: "Original"})", e) }
        }
        if (TrackLogic.TRACK_FILE in names) {
            try { fileMutex.withLock { GpxExporter.exportFrom(access, sessionId) } }
            catch (e: Exception) { Log.e(TAG, "GPX-Export nach Bearbeitung fehlgeschlagen", e) }
        }
        if (forgetNames) ch.etasystems.pirol.data.sync.SessionUploadWorker.forgetExportNames(context, sessionId)
        if (oldSessionId != null && oldSessionId != sessionId) {
            ch.etasystems.pirol.data.sync.SessionUploadWorker.forgetExportNames(context, oldSessionId)
        }
    }

    /** Alle Detektionen einer Session (T251: inkl. Updates der Zusatzdatei). */
    suspend fun loadDetections(sessionDir: File, variant: String? = null): List<DetectionResult> = withContext(Dispatchers.IO) {
        try {
            readSessionText(sessionDir, RescanFiles.detections(variant))?.let { SessionTextParser.detections(it) } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "detections.jsonl lesen fehlgeschlagen: ${sessionDir.name}", e)
            emptyList()
        }
    }

    private suspend fun readVerifications(sessionDir: File, variant: String?): String? =
        try { readSessionText(sessionDir, RescanFiles.verifications(variant)) } catch (_: Exception) { null }

    /** Detektionen mit angewendeten Verifikations-Events. */
    suspend fun loadDetectionsWithVerifications(sessionDir: File, variant: String? = null): List<DetectionResult> =
        withContext(Dispatchers.IO) {
            val detections = loadDetections(sessionDir, variant)
            val veriText = readVerifications(sessionDir, variant) ?: return@withContext detections
            SessionTextParser.applyVerifications(detections, SessionTextParser.events(veriText))
        }

    /** T167: Wanduhr-Zeiten der absorbierten Fenster je Anker-Detektion (aus CLUSTER_MERGED-Events). */
    suspend fun loadMergeTimestamps(sessionDir: File, variant: String? = null): Map<String, List<DetectionOccurrences.MergeInfo>> =
        withContext(Dispatchers.IO) {
            readVerifications(sessionDir, variant)?.let { SessionTextParser.mergeTimestamps(it) } ?: emptyMap()
        }

    /** Recording-Datei einer Session (File-Modus). */
    fun getRecordingFile(sessionDir: File): File? =
        File(sessionDir, "recording.wav").takeIf { it.exists() }

    /** T130: Alle WAV-Segmente einer Session als eine Zeitachse; null = keine Aufnahme. */
    suspend fun loadRecording(sessionDir: File): SegmentedRecording? = withContext(Dispatchers.IO) {
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
     * T130: Veraltete RIFF-/data-Groessen aller Segmente nachfuehren (Prozess-Kill). SAF-Sessions und die
     * laufende Session werden nicht angefasst. @return Anzahl reparierter Dateien
     */
    suspend fun repairRecordingHeaders(sessionDir: File): Int = withContext(Dispatchers.IO) {
        if (docFor(sessionDir) != null) return@withContext 0
        if (isActiveDir(sessionDir)) return@withContext 0
        val candidates = sessionDir.listFiles { f ->
            f.isFile && f.name.startsWith("recording") && f.name.endsWith(".wav")
        }?.toList() ?: emptyList()
        var repaired = 0
        for (file in candidates) {
            try {
                if (WavWriter.headerSizesStale(file)) {
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

    // ── Raven-Export ──────────────────────────────────────────────────

    /**
     * Raven Selection Table neben recording.wav schreiben (manueller Re-Export, T57-B3, T78).
     * @return erzeugte Datei oder null wenn keine Aufnahme vorhanden
     */
    suspend fun exportRavenSelectionTable(
        sessionDir: File,
        appPreferences: AppPreferences? = null,
        variant: String? = null  // T221: Rescan-Variante -> recording.rescan-<id>.selections.txt
    ): File? = writeRavenTable(sessionDir, appPreferences, variant, requireRecording = true)

    /**
     * T249 (M2): Einziger Raven-Exportweg (Auto-Export beim Stopp und manueller Export).
     * @param requireRecording true = ohne Aufnahme nichts schreiben (manuell); Auto-Export schreibt immer
     */
    private suspend fun writeRavenTable(
        sessionDir: File,
        appPreferences: AppPreferences?,
        requestedVariant: String?,
        requireRecording: Boolean,
        /** Auto-Export: gerade geschriebene Metadaten (sonst aus session.json) */
        knownMeta: SessionMetadata? = null
    ): File? = withContext(Dispatchers.IO) {
        // T305 (N3): Schreibziel wie bei Verifikationen (T303): uebernommene Variante -> Original, geloeschte -> nichts schreiben
        val variant: String? = when (val t = fileMutex.withLock { writeTargetLocked(sessionDir, requestedVariant) }) {
            is VariantWriteTarget.Target.Write -> t.variant
            VariantWriteTarget.Target.Drop -> return@withContext null
        }
        val access = accessFor(sessionDir)
        if (requireRecording && access.segmentSources().isEmpty()) return@withContext null
        val isDoc = access is DocSessionFiles
        val ravenName = RescanFiles.raven(variant)
        // SAF: erst in eine Cache-Datei exportieren, dann in den Session-Ordner schreiben
        val outputFile = if (isDoc) File(context.cacheDir, ravenName) else File(sessionDir, ravenName)
        val detections = loadDetectionsWithVerifications(sessionDir, variant)
        val meta = knownMeta ?: runCatching {
            val jsonText = readSessionText(sessionDir, SESSION_JSON) ?: error("session.json fehlt")
            SessionJson.lenient.decodeFromString<SessionMetadata>(jsonText)
        }.getOrNull()
        val startedAt = meta?.startedAt ?: "1970-01-01T00:00:00+00:00"
        // T78/T249: enabledFields aus AppPreferences (eine Liste); null = alle Felder (Fallback)
        val enabledFields: Set<String>? = appPreferences?.let { RavenExporter.enabledFields(it) }
        val veriText = readVerifications(sessionDir, variant)
        val linkMap = veriText?.let { SessionTextParser.simultaneousLinks(it) } ?: emptyMap()
        // T259: absorbierte Fenster (CLUSTER_MERGED) -> eine Raven-Zeile pro Episode
        val merges = veriText?.let { SessionTextParser.mergeTimestamps(it) } ?: emptyMap()
        // T264: Session-Memos als eigene Zeilen (Species "Memo")
        val memos = SessionMemos.parse(try { readSessionText(sessionDir, SessionMemos.FILE) } catch (_: Exception) { null })
        RavenExporter.export(outputFile, detections, startedAt, meta?.recordingSegments, enabledFields, linkMap, meta?.note, merges, memos,
            RavenExporter.freqBandFor(meta?.sampleRate))  // T294: Ultraschall-Band, bis 96 kHz unveraendert
        if (isDoc) {
            fileMutex.withLock { access.writeReplacing(ravenName, outputFile.readText()) }
            outputFile.delete()
        }
        outputFile
    }

    /** T194: Text der Raven-Datei der Session (auch bei SAF-Sessions); null = Datei fehlt. */
    suspend fun readRavenText(sessionDir: File, variant: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching { readSessionText(sessionDir, RescanFiles.raven(variant)) }.getOrNull()
    }

    // ── Migration (T41, T51, T141) ────────────────────────────────────

    /** Standard-Basisordner im App-Speicher (Ziel "zurueck auf App-Speicher"). */
    fun defaultBaseDir(): File = locator.defaultBaseDir()

    /** Zaehlt Sessions und Gesamtgroesse am aktuellen Speicherort (blockierend, IO-Dispatcher). */
    fun getMigrationInfo(): MigrationInfo = migrator.info()

    /** T141: Alle Sessions an das Ziel kopieren (siehe [SessionMigrator.migrate]); nicht waehrend einer Aufnahme. */
    suspend fun migrateSessions(
        target: MigrationTarget,
        deleteSource: Boolean = false,
        onProgress: (MigrationProgress) -> Unit = {}
    ): MigrationResult = withContext(Dispatchers.IO) {
        if (isActive) return@withContext MigrationResult(0, 0L, listOf("Aufnahme laeuft"), 0, 0)
        migrator.migrate(target, deleteSource, onProgress)
    }

    // ── Karte (T79, T85) ──────────────────────────────────────────────

    /** Events an verifications.jsonl der Session mit [sessionId] anhaengen (T251: auch SAF). */
    private suspend fun appendEventsTo(sessionId: String, events: List<VerificationEvent>) {
        val dir = findSessionDir(sessionId) ?: return
        val text = events.joinToString("") { SessionJson.compact.encodeToString(it) + "\n" }
        fileMutex.withLock { accessFor(dir).append(VERIFICATIONS_FILE, text) }  // T93b
    }

    /** POSITION_MOVED fuer eine historische Detektion (MapViewModel, T79). */
    suspend fun recordPositionMoved(
        sessionId: String,
        detectionId: String,
        newLat: Double,
        newLon: Double,
        status: VerificationStatus = VerificationStatus.UNVERIFIED
    ) = withContext(Dispatchers.IO) {
        val event = VerificationEvent(
            detectionId = detectionId,
            status = status,
            verifiedAtMs = System.currentTimeMillis(),
            actionType = VerificationEvent.ACTION_POSITION_MOVED,
            newValue = "$newLat,$newLon"
        )
        try { appendEventsTo(sessionId, listOf(event)) }
        catch (e: Exception) { Log.e(TAG, "recordPositionMoved fehlgeschlagen: $detectionId", e) }
    }

    /** Simultanlink zwischen zwei Detektionen (beide Richtungen, T85). */
    suspend fun recordSimultaneousLink(
        sessionId: String,
        detectionId1: String,
        detectionId2: String,
        actionType: String
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val events = listOf(
            VerificationEvent(detectionId1, VerificationStatus.UNVERIFIED, verifiedAtMs = now, actionType = actionType, newValue = detectionId2),
            VerificationEvent(detectionId2, VerificationStatus.UNVERIFIED, verifiedAtMs = now, actionType = actionType, newValue = detectionId1)
        )
        try { appendEventsTo(sessionId, events) }
        catch (e: Exception) { Log.e(TAG, "recordSimultaneousLink fehlgeschlagen: $detectionId1/$detectionId2", e) }
    }
}

/** T155: Session-ID im Format `yyyy-MM-dd'T'HH-mm-ss_uuid6`, Zeit = Ortszeit (wie Tages-Unterordner). */
internal fun buildSessionId(localNow: LocalDateTime, uuidShort: String): String =
    localNow.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")) + "_" + uuidShort
