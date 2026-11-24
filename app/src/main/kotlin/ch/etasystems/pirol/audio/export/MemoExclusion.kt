package ch.etasystems.pirol.audio.export

import ch.etasystems.pirol.data.repository.SessionMemo
import ch.etasystems.pirol.data.repository.SessionMemos
import ch.etasystems.pirol.ml.VerificationEvent
import kotlin.math.max
import kotlin.math.min

/**
 * T263: Zeitraum eines waehrend der Aufnahme gesprochenen Sprachmemos auf der Session-Achse.
 *
 * Die eigene Stimme im Memo liegt auch in recording.wav. Dieser Zeitraum (plus Rand) wird von den
 * Mensch-Abschnitten abgezogen, damit der Export die Stimme der aufnehmenden Person nicht daempft.
 * Nur wenn der Zeitraum sicher bestimmbar ist (Live aufgenommen, Beginn und Ende gespeichert, plausible
 * Dauer, Wanduhr der Session-Achse bekannt); sonst wird nichts abgezogen. Reine Logik (unit-testbar).
 */
object MemoExclusion {

    /** Obergrenze der Memo-Dauer (Recorder-Cap 60 s) mit Toleranz; laenger = unplausibel, nicht verwenden */
    const val MAX_MEMO_SEC = 70f

    /** T264: Obergrenze eines Session-Memos (Recorder-Cap 180 s) mit Toleranz */
    const val MAX_SESSION_MEMO_SEC = SessionMemos.MAX_DURATION_SEC + 10f

    /** Wanduhr-Zeitraum (Epoch ms) einer Memo-Aufnahme */
    data class WallSpan(val startMs: Long, val endMs: Long)

    /**
     * Zeitraeume der noch vorhandenen Memos aus den Verifikations-Events (Reihenfolge = Dateireihenfolge):
     * MEMO_RECORDED mit Beginn und Ende setzt, MEMO_DELETED entfernt (geloeschtes Memo = kein Ausnahme).
     */
    fun wallSpans(events: List<VerificationEvent>): List<WallSpan> = wallSpansByDetection(events).values.toList()

    /** T264: wie [wallSpans], je Detektions-Id (fuer die Memo-Liste der Analyse). */
    fun wallSpansByDetection(events: List<VerificationEvent>): Map<String, WallSpan> {
        val byDetection = LinkedHashMap<String, WallSpan>()
        for (e in events) {
            when (e.actionType) {
                VerificationEvent.ACTION_MEMO_RECORDED -> {
                    val s = e.memoStartMs
                    val t = e.memoEndMs
                    if (s != null && t != null && t > s) byDetection[e.detectionId] = WallSpan(s, t)
                    else byDetection.remove(e.detectionId)  // Zeit unbekannt: nichts abziehen
                }
                VerificationEvent.ACTION_MEMO_DELETED -> byDetection.remove(e.detectionId)
            }
        }
        return byDetection
    }

    /**
     * Zeitraeume auf der Session-Achse (s), je mit [margin] davor/danach, auf [0, totalSec] begrenzt.
     * Memos ausserhalb der Aufnahme (nach dem Ende bzw. vor dem Beginn) entfallen.
     *
     * @param epochZeroMs Wanduhr (Epoch ms) von Sekunde 0 der Session-Achse; null = unbekannt -> leer
     */
    fun toSessionSpans(
        spans: List<WallSpan>,
        epochZeroMs: Long?,
        totalSec: Float,
        margin: Float = HumanWindows.MARGIN_SEC
    ): List<TimeSpan> {
        if (epochZeroMs == null || totalSec <= 0f) return emptyList()
        return spans.mapNotNull { w ->
            val a = (w.startMs - epochZeroMs) / 1000f
            val b = (w.endMs - epochZeroMs) / 1000f
            if (b - a > MAX_MEMO_SEC) return@mapNotNull null
            if (b <= 0f || a >= totalSec) return@mapNotNull null  // nicht in der Aufnahme
            TimeSpan(max(0f, a - margin), min(totalSec, b + margin))
        }.let { HumanWindows.union(it) }
    }

    /**
     * T264: Session-Memos auf der Session-Achse (s), je mit [margin] davor/danach, auf [0, totalSec] begrenzt.
     * Die Lage ist direkt gespeichert (startSec/endSec); Memos ohne Lage, mit unplausibler Dauer oder
     * ausserhalb der Aufnahme entfallen.
     */
    fun sessionMemoSpans(
        memos: List<SessionMemo>,
        totalSec: Float,
        margin: Float = HumanWindows.MARGIN_SEC
    ): List<TimeSpan> {
        if (totalSec <= 0f) return emptyList()
        return memos.mapNotNull { m ->
            val a = m.startSec ?: return@mapNotNull null
            val b = m.endSec ?: return@mapNotNull null
            if (b <= a || b - a > MAX_SESSION_MEMO_SEC) return@mapNotNull null
            if (b <= 0f || a >= totalSec) return@mapNotNull null
            TimeSpan(max(0f, a - margin), min(totalSec, b + margin))
        }.let { HumanWindows.union(it) }
    }

    /** [sections] ohne die Bereiche [cuts]. */
    fun subtract(sections: List<TimeSpan>, cuts: List<TimeSpan>): List<TimeSpan> {
        if (cuts.isEmpty()) return sections
        val cutsSorted = HumanWindows.union(cuts)
        val out = ArrayList<TimeSpan>()
        for (s in sections) {
            var pieces = listOf(s)
            for (c in cutsSorted) {
                pieces = pieces.flatMap { p ->
                    if (c.endSec <= p.startSec || c.startSec >= p.endSec) listOf(p)
                    else buildList {
                        if (c.startSec > p.startSec) add(TimeSpan(p.startSec, c.startSec))
                        if (c.endSec < p.endSec) add(TimeSpan(c.endSec, p.endSec))
                    }
                }
            }
            out += pieces
        }
        return out
    }
}
