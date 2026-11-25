package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.audio.export.MemoExclusion
import ch.etasystems.pirol.data.repository.SessionMemo
import ch.etasystems.pirol.ml.DetectionResult
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * T264: Eintrag im Block "Memos" der Analyse (Session-Memo oder Art-Memo).
 *
 * @param key             eindeutiger Schluessel (`session:<Datei>` bzw. Detektions-Id)
 * @param file            Pfad der Memo-WAV relativ zum Session-Ordner
 * @param startMs         Wanduhr des Beginns; null = unbekannt
 * @param endMs           Wanduhr des Endes; null = unbekannt (Naeherung)
 * @param approximate     true = Zeit ist die Erfassungszeit der Detektion (Art-Memo ohne gespeicherten Zeitraum)
 * @param detectionId     Art-Memo: Detektion; null = Session-Memo
 * @param scientificName  Art-Memo: angezeigte Art (Korrektur vor Original)
 * @param sessionStartSec Session-Memo: Beginn auf der Session-Achse (Sprungziel); null = unbekannt
 * @param sessionEndSec   Session-Memo: Ende auf der Session-Achse
 * @param note            T272: Art-Memo: Bemerkung der Detektion (Teil des Exportnamens)
 */
data class MemoItem(
    val key: String,
    val file: String,
    val startMs: Long?,
    val endMs: Long?,
    val approximate: Boolean,
    val detectionId: String?,
    val scientificName: String?,
    val sessionStartSec: Float?,
    val sessionEndSec: Float?,
    val note: String? = null  // T272: Bemerkung der Detektion (Art-Memo) fuer den Exportnamen
) {
    val isSessionMemo: Boolean get() = detectionId == null

    companion object {
        /** Schluessel eines Session-Memos (Memo-Block, Karte und Wiedergabe teilen ihn) */
        fun sessionKey(file: String): String = "session:$file"
    }
}

/** T264: Liste des Memo-Blocks (reine Logik, unit-testbar). */
object MemoList {

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /**
     * Session-Memos und Art-Memos chronologisch (Beginn; ohne Zeit zuletzt).
     * @param artSpans Zeitraeume der Art-Memos aus MEMO_RECORDED (je Detektions-Id)
     */
    fun build(
        sessionMemos: List<SessionMemo>,
        detections: List<DetectionResult>,
        artSpans: Map<String, MemoExclusion.WallSpan>
    ): List<MemoItem> {
        val session = sessionMemos.map { m ->
            MemoItem(
                key = MemoItem.sessionKey(m.file),
                file = m.file,
                startMs = m.startEpochMs,
                endMs = m.endEpochMs,
                approximate = false,
                detectionId = null,
                scientificName = null,
                sessionStartSec = m.startSec,
                sessionEndSec = m.endSec
            )
        }
        val art = detections.filter { !it.voiceMemoFile.isNullOrBlank() }.map { d ->
            val span = artSpans[d.id]
            MemoItem(
                key = d.id,
                file = d.voiceMemoFile!!,
                startMs = span?.startMs ?: d.timestampMs,
                endMs = span?.endMs,
                approximate = span == null,
                detectionId = d.id,
                scientificName = d.correctedSpecies?.takeIf { it.isNotBlank() } ?: d.scientificName,
                sessionStartSec = null,
                sessionEndSec = null,
                note = d.note
            )
        }
        return (session + art).sortedWith(
            compareBy<MemoItem> { it.startMs == null }.thenBy { it.startMs ?: 0L }.thenBy { it.key }
        )
    }

    /** Uhrzeit `HH:mm:ss–HH:mm:ss` (lokal); nur Beginn, wenn das Ende unbekannt ist; leer ohne Zeit. */
    fun timeLabel(item: MemoItem, zone: ZoneId = ZoneId.systemDefault()): String {
        val start = item.startMs ?: return ""
        val a = CLOCK.format(Instant.ofEpochMilli(start).atZone(zone))
        val end = item.endMs ?: return a
        return a + "–" + CLOCK.format(Instant.ofEpochMilli(end).atZone(zone))
    }
}
