package ch.etasystems.pirol.data.repository

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import ch.etasystems.pirol.ml.DetectionResult
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * T251 (N7): Die laufende Aufnahme-Session (aus SessionManager herausgeloest): offene Writer fuer
 * detections.jsonl, Zusatzdatei der Updates (N2) und WAV, Segment-Rotation (T57-B1, T130).
 * Genau einer von [dir] (File-Modus) und [doc] (SAF-Modus) ist gesetzt.
 *
 * Alle Methoden mit Suffix `Locked` laufen unter dem fileMutex des SessionManagers. Audio-Methoden laufen
 * auf dem Audio-Pfad (nur WAV-Writer, wie bisher ohne fileMutex).
 */
internal class ActiveSession private constructor(
    private val context: Context,
    val metadata: SessionMetadata,
    val dir: File?,
    val doc: DocumentFile?,
    private val updates: DetectionUpdateLog,
    private var detectionWriter: Writer?,
    private var detectionsFd: ParcelFileDescriptor?,
    @Volatile private var recordingWriter: StreamingWavWriter?
) {
    /** Zusatzdatei der Updates, lazy geoeffnet (T251, N2) */
    private var sideWriter: Writer? = null
    private var sideFd: ParcelFileDescriptor? = null
    /** Provider legt die Zusatzdatei nicht unter ihrem Namen an -> Rewrite statt Append */
    private var sideUnsupported = false
    /** Writer beim Session-Ende geschlossen */
    private var finished = false

    var detectionCounter: Int = 0
        private set

    // T57-B1: Rotations-State
    private var segmentIndex = 0
    private var segmentStartSec = 0f
    private val segments = mutableListOf<RecordingSegment>()
    private var samplesCompleted = 0L

    /** Dateizugriff auf den Session-Ordner (neue Instanz je Vorgang, SAF-Listing wird darin wiederverwendet). */
    fun files(): SessionFiles = doc?.let { DocSessionFiles(context, it) } ?: FileSessionFiles(dir!!)

    /** Ist [sessionDir] diese Session? File ueber den Pfad, SAF ueber den Ordnernamen (= Session-ID). */
    fun matches(sessionDir: File): Boolean =
        SessionFilesLogic.isSameSession(dir?.absolutePath, doc?.name, sessionDir.absolutePath, sessionDir.name)

    // ── Detektionen ───────────────────────────────────────────────────

    /** Neue Detektionen anhaengen; liegt schon ein Update vor, gleich die neue Fassung (T236). */
    fun appendDetectionsLocked(detections: List<DetectionResult>) {
        val writer = detectionWriter ?: return
        for (d in updates.takeForAppend(detections)) {
            writer.append(SessionJson.compact.encodeToString(d)).append('\n')
            detectionCounter++
        }
        writer.flush()
    }

    /** Fertige Zeile von aussen (Analyse, manuelle Detektion) anhaengen. */
    fun appendDetectionLineLocked(line: String): Boolean {
        val writer = detectionWriter ?: return false
        writer.append(line)
        writer.flush()
        updates.noteAppendedLine(line)
        detectionCounter++
        return true
    }

    /**
     * T251 (N2): Vorgemerkte Updates an die Zusatzdatei haengen (nur die geaenderten Zeilen, keine
     * Neuschreibung von detections.jsonl). Fehler: Updates bleiben vorgemerkt.
     * @return Fehler oder null
     */
    fun flushUpdatesLocked(): Exception? {
        if (finished || updates.isEmpty()) return null
        return try {
            if (sideUnsupported) rewriteMainLocked()
            else updates.flush { text ->
                val w = sideWriter ?: openSideWriter().also { sideWriter = it }
                w.append(text)
                w.flush()
            }
            null
        } catch (e: ShadowReplace.UnsupportedNameException) {
            // Provider legt die Zusatzdatei nicht unter ihrem Namen an (vgl. T249 M3b): Rewrite wie T236
            Log.w(TAG, "${DetectionUpdateLog.FILE} nicht anlegbar, Rueckfall auf Neuschreiben von detections.jsonl", e)
            sideUnsupported = true
            closeSideWriter()
            try { rewriteMainLocked(); null } catch (e2: Exception) {
                Log.e(TAG, "detections.jsonl: ${updates.size()} Updates nicht geschrieben (neuer Versuch beim naechsten Flush)", e2)
                e2
            }
        } catch (e: Exception) {
            Log.e(TAG, "${DetectionUpdateLog.FILE}: ${updates.size()} Updates nicht geschrieben (neuer Versuch beim naechsten Flush)", e)
            closeSideWriter()  // beim naechsten Versuch neu oeffnen
            e
        }
    }

    /** Rueckfall ohne Zusatzdatei: vorgemerkte Updates per Rewrite in detections.jsonl (Writer zu und wieder auf). */
    private fun rewriteMainLocked() {
        val batch = updates.pendingWithLine()
        if (batch.isEmpty()) return
        val hadWriter = detectionWriter != null
        closeDetectionWriter()
        try {
            DetectionUpdateLog.consolidate(files(), batch)
            updates.markWritten(batch)
        } finally {
            if (hadWriter) {
                try { reopenDetectionWriter() } catch (e: Exception) { Log.e(TAG, "detections.jsonl konnte nicht neu geoeffnet werden", e) }
            }
        }
    }

    private fun reopenDetectionWriter() {
        val d = doc
        if (d == null) { detectionWriter = FileWriter(File(dir!!, DETECTIONS), true); return }
        val f = d.findFile(DETECTIONS) ?: return
        val fd = context.contentResolver.openFileDescriptor(f.uri, "wa") ?: return
        detectionsFd = fd
        detectionWriter = OutputStreamWriter(FileOutputStream(fd.fileDescriptor), Charsets.UTF_8)
    }

    private fun openSideWriter(): Writer {
        val d = doc
        if (d == null) return FileWriter(File(dir!!, DetectionUpdateLog.FILE), true)
        val f = d.findFile(DetectionUpdateLog.FILE)
            ?: d.createFile("application/octet-stream", DetectionUpdateLog.FILE)?.also { created ->
                if (created.name != DetectionUpdateLog.FILE) {
                    created.delete()
                    throw ShadowReplace.UnsupportedNameException(DetectionUpdateLog.FILE)
                }
            }
            ?: throw IllegalStateException("SAF: ${DetectionUpdateLog.FILE} konnte nicht erstellt werden")
        val fd = context.contentResolver.openFileDescriptor(f.uri, "wa")
            ?: throw IllegalStateException("SAF: ${DetectionUpdateLog.FILE} nicht beschreibbar")
        sideFd = fd
        return OutputStreamWriter(FileOutputStream(fd.fileDescriptor), Charsets.UTF_8)
    }

    private fun closeSideWriter() {
        try { sideWriter?.close() } catch (e: Exception) { Log.e(TAG, "Zusatzdatei close failed", e) }
        sideWriter = null
        try { sideFd?.close() } catch (e: Exception) { Log.e(TAG, "Zusatzdatei-PFD close failed", e) }
        sideFd = null
    }

    /** Aktueller Stand einer Detektion (Warteschlange, Zusatzdatei, Hauptdatei); Writer vorher geleert. */
    fun currentLocked(id: String): DetectionResult? {
        detectionWriter?.flush()
        sideWriter?.flush()
        return updates.current(id, files())
    }

    /** Session-Ende: alle Writer der Detektionsdateien schliessen; danach keine Flushes mehr (spaete Updates verfallen). */
    fun closeDetectionWritersLocked() {
        finished = true
        closeSideWriter()
        closeDetectionWriter()
    }

    private fun closeDetectionWriter() {
        try { detectionWriter?.close() } catch (e: Exception) { Log.e(TAG, "Detection-Writer close failed", e) }
        detectionWriter = null
        try { detectionsFd?.close() } catch (e: Exception) { Log.e(TAG, "Detections-PFD close failed", e) }
        detectionsFd = null
    }

    /** Ausstehende Writes sichtbar machen (vor Lesezugriffen auf die eigenen Dateien). */
    fun flushWritersLocked() {
        try { detectionWriter?.flush() } catch (e: Exception) { Log.w(TAG, "Detection-Writer flush failed", e) }
        try { sideWriter?.flush() } catch (e: Exception) { Log.w(TAG, "Zusatzdatei flush failed", e) }
    }

    // ── Verifikationen / Route ────────────────────────────────────────

    /** Zeile an verifications.jsonl / track.jsonl anhaengen (SAF: vorher Reste eines Ersetzens aufloesen, T249). */
    fun appendLocked(fileName: String, line: String) = files().append(fileName, line)

    // ── Audio ─────────────────────────────────────────────────────────

    val recordedSampleCount: Long get() = samplesCompleted + (recordingWriter?.sampleCount ?: 0L)

    fun offsetSec(): Float {
        val writer = recordingWriter ?: return 0f
        return SessionManager.sessionOffsetSec(samplesCompleted, writer.sampleCount, metadata.sampleRate)
    }

    /** Samples anhaengen; nach [maxSec] im aktuellen Segment neue Datei beginnen (T57-B1). */
    fun appendAudio(samples: ShortArray, maxSec: Float) {
        val writer = recordingWriter ?: return
        writer.append(samples)
        val sampleRate = metadata.sampleRate.toFloat()
        if (sampleRate > 0f && writer.sampleCount / sampleRate >= maxSec) rotateRecording()
    }

    private fun rotateRecording() {
        val writer = recordingWriter ?: return
        val sampleRate = metadata.sampleRate.toFloat()
        val durationSec = writer.sampleCount.toFloat() / sampleRate
        segments.add(RecordingSegment(SessionManager.segmentFileName(segmentIndex), segmentStartSec, durationSec))
        samplesCompleted += writer.sampleCount
        try { writer.close() } catch (e: Exception) { Log.e(TAG, "WAV rotate close failed", e) }

        segmentIndex++
        segmentStartSec += durationSec
        val newName = SessionManager.segmentFileName(segmentIndex)
        recordingWriter = when {
            dir != null -> StreamingWavWriter.forFile(File(dir, newName), metadata.sampleRate).also { it.open() }
            doc != null -> {
                val wavDoc = doc.createFile("audio/wav", newName)
                    ?: throw IllegalStateException("SAF: $newName konnte nicht erstellt werden")
                StreamingWavWriter.forSaf(context, wavDoc.uri, metadata.sampleRate).also { it.open() }
            }
            else -> null
        }
        Log.i(TAG, "WAV-Rotation: Segment $segmentIndex gestartet → $newName")
    }

    /** Letztes Segment abschliessen und WAV schliessen. @return (Gesamtsamples, Segmentliste oder null bei einer Datei) */
    fun closeRecording(): Pair<Long, List<RecordingSegment>?> {
        val writer = recordingWriter
        if (writer != null) {
            val durationSec = writer.sampleCount.toFloat() / metadata.sampleRate.toFloat()
            segments.add(RecordingSegment(SessionManager.segmentFileName(segmentIndex), segmentStartSec, durationSec))
            samplesCompleted += writer.sampleCount
        }
        try { writer?.close() } catch (e: Exception) { Log.e(TAG, "WAV close failed", e) }
        recordingWriter = null
        return samplesCompleted to (if (segments.size > 1) segments.toList() else null)
    }

    companion object {
        private const val TAG = "SessionManager"

        /** File-Modus: Ordner, session.json, detections.jsonl (Append) und recording.wav anlegen. */
        fun openFile(context: Context, sessionDir: File, metadata: SessionMetadata, updates: DetectionUpdateLog): ActiveSession {
            sessionDir.mkdirs()
            File(sessionDir, SESSION_JSON).writeText(SessionJson.pretty.encodeToString(metadata))
            val writer = FileWriter(File(sessionDir, DETECTIONS), true)
            val wav = StreamingWavWriter.forFile(File(sessionDir, "recording.wav"), metadata.sampleRate).also { it.open() }
            return ActiveSession(context, metadata, sessionDir, null, updates, writer, null, wav)
        }

        /** SAF-Modus: dieselben Dateien ueber DocumentFile/ContentResolver. */
        fun openSaf(context: Context, parentDoc: DocumentFile, metadata: SessionMetadata, updates: DetectionUpdateLog): ActiveSession {
            val sessionDoc = parentDoc.createDirectory(metadata.sessionId)
                ?: throw IllegalStateException("SAF: Session-Ordner konnte nicht erstellt werden: ${metadata.sessionId}")
            val jsonDocFile = sessionDoc.createFile("application/json", SESSION_JSON)
                ?: throw IllegalStateException("SAF: session.json konnte nicht erstellt werden")
            context.contentResolver.openOutputStream(jsonDocFile.uri, "wt")?.use { os ->
                os.write(SessionJson.pretty.encodeToString(metadata).toByteArray(Charsets.UTF_8))
            }
            val detectionsDocFile = sessionDoc.createFile("application/jsonlines", DETECTIONS)
                ?: throw IllegalStateException("SAF: detections.jsonl konnte nicht erstellt werden")
            val fd = context.contentResolver.openFileDescriptor(detectionsDocFile.uri, "wa")
                ?: throw IllegalStateException("SAF: detections.jsonl nicht beschreibbar")
            val writer = OutputStreamWriter(FileOutputStream(fd.fileDescriptor), Charsets.UTF_8)
            val wavDocFile = sessionDoc.createFile("audio/wav", "recording.wav")
                ?: throw IllegalStateException("SAF: recording.wav konnte nicht erstellt werden")
            val wav = StreamingWavWriter.forSaf(context, wavDocFile.uri, metadata.sampleRate).also { it.open() }
            return ActiveSession(context, metadata, null, sessionDoc, updates, writer, fd, wav)
        }

        const val SESSION_JSON = "session.json"
        private const val DETECTIONS = "detections.jsonl"
    }
}
