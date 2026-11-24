package ch.etasystems.pirol.audio.export

import ch.etasystems.pirol.data.repository.SessionJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.max
import kotlin.math.min

/**
 * T261: Eine Zeile der Merkliste `human_windows.jsonl` (Session-Ordner, append-only, wird nie exportiert).
 *
 * - [kind] = `human`: Fenster mit Mensch-Score >= [HumanWindows.RECORD_FLOOR] (Rohscore vor Filter und Schwelle)
 * - [kind] = `scanned`: dieser Bereich wurde bewertet (live, Rescan oder Export-Scan); fehlende Bereiche
 *   werden beim Export nachgescannt
 *
 * Zeiten in Sekunden auf der Session-Achse (session-relativ ueber alle Segmente, inkl. Preroll).
 * Live, Rescan und Export-Scan schreiben in dieselbe Datei; beim Export zaehlt die Vereinigung.
 */
@Serializable
data class HumanWindowLine(
    val kind: String,
    val startSec: Float,
    val endSec: Float,
    val score: Float? = null,
    /** Profil-ID des Modells, das bewertet hat */
    val model: String? = null
)

/** T261: Zeitbereich [startSec, endSec) in Sekunden */
data class TimeSpan(val startSec: Float, val endSec: Float) {
    val durationSec: Float get() = endSec - startSec
}

/** T261: Zeitbereich [startFrame, endFrame) in Samples */
data class FrameSpan(val startFrame: Long, val endFrame: Long) {
    val length: Long get() = endFrame - startFrame
}

/** T261: Merkliste lesen/schreiben und daraus Masken-Abschnitte und Scan-Luecken bilden. Reine Logik. */
object HumanWindows {
    const val FILE = "human_windows.jsonl"
    const val KIND_HUMAN = "human"
    const val KIND_SCANNED = "scanned"

    /** Ab diesem Rohscore wird ein Fenster gemerkt (Schwelle selbst gilt erst beim Export, aenderbar) */
    const val RECORD_FLOOR = 0.02f

    /** Default der Export-Schwelle (Pref `pirol_export_human_threshold`) */
    const val DEFAULT_THRESHOLD = 0.10f

    /** Rand vor und nach jedem Mensch-Abschnitt */
    const val MARGIN_SEC = 0.5f

    /** Luecken bis zu dieser Laenge zwischen bewerteten Bereichen gelten als abgedeckt (Rundung) */
    const val GAP_TOLERANCE_SEC = 0.05f

    fun encode(lines: List<HumanWindowLine>): String =
        lines.joinToString("") { SessionJson.compact.encodeToString(it) + "\n" }

    /** Unlesbare Zeilen (abgebrochenes Append) werden uebersprungen. */
    fun parse(text: String?): List<HumanWindowLine> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            if (line.isBlank()) null
            else try { SessionJson.lenient.decodeFromString<HumanWindowLine>(line) } catch (_: Exception) { null }
        }.filter { it.endSec > it.startSec && it.startSec.isFinite() && it.endSec.isFinite() }.toList()
    }

    /** Vereinigt ueberlappende bzw. bis [tolerance] auseinanderliegende Bereiche (sortiert). */
    fun union(spans: List<TimeSpan>, tolerance: Float = 0f): List<TimeSpan> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.filter { it.endSec > it.startSec }.sortedBy { it.startSec }
        val out = ArrayList<TimeSpan>()
        for (s in sorted) {
            val last = out.lastOrNull()
            if (last != null && s.startSec <= last.endSec + tolerance) {
                out[out.lastIndex] = TimeSpan(last.startSec, max(last.endSec, s.endSec))
            } else out += s
        }
        return out
    }

    /**
     * Abschnitte, die im Export ausgeblendet werden: Mensch-Fenster mit Score >= [threshold], vereinigt,
     * mit [margin] davor/danach, auf [0, totalSec] begrenzt.
     */
    fun sections(lines: List<HumanWindowLine>, threshold: Float, totalSec: Float, margin: Float = MARGIN_SEC): List<TimeSpan> {
        val raw = lines.filter { it.kind == KIND_HUMAN && (it.score ?: 0f) >= threshold }
            .map { TimeSpan(max(0f, it.startSec - margin), min(totalSec, it.endSec + margin)) }
        return union(raw)
    }

    /** Bereiche von [0, totalSec], die von keiner `scanned`-Zeile abgedeckt sind (muessen nachgescannt werden). */
    fun gaps(lines: List<HumanWindowLine>, totalSec: Float, tolerance: Float = GAP_TOLERANCE_SEC): List<TimeSpan> {
        if (totalSec <= 0f) return emptyList()
        val covered = union(lines.filter { it.kind == KIND_SCANNED }.map { TimeSpan(it.startSec, it.endSec) }, tolerance)
        val out = ArrayList<TimeSpan>()
        var pos = 0f
        for (c in covered) {
            if (c.startSec - pos > tolerance) out += TimeSpan(pos, min(c.startSec, totalSec))
            pos = max(pos, c.endSec)
            if (pos >= totalSec) break
        }
        if (totalSec - pos > tolerance) out += TimeSpan(pos, totalSec)
        return out.filter { it.endSec > it.startSec }
    }

    /**
     * Fensterstarts (s), die [gap] mit Fenstern der Laenge [windowSec] luecklos abdecken; das letzte Fenster
     * endet am Lueckenende (beginnt nie vor 0). Hop = Fensterlaenge.
     */
    fun scanStarts(gap: TimeSpan, windowSec: Float): List<Float> {
        if (gap.durationSec <= 0f || windowSec <= 0f) return emptyList()
        val out = ArrayList<Float>()
        var s = gap.startSec
        while (s + windowSec < gap.endSec) {
            out += s
            s += windowSec
        }
        out += max(0f, gap.endSec - windowSec)
        return out.distinct()
    }

    /** Sekunden-Abschnitte in Frames, begrenzt auf [0, totalFrames]. */
    fun toFrames(spans: List<TimeSpan>, sampleRate: Int, totalFrames: Long): List<FrameSpan> =
        spans.map {
            FrameSpan(
                (it.startSec.toDouble() * sampleRate).toLong().coerceIn(0L, totalFrames),
                Math.ceil(it.endSec.toDouble() * sampleRate).toLong().coerceIn(0L, totalFrames)
            )
        }.filter { it.length > 0 }

    /** true, wenn ein Abschnitt [range] (Frames) schneidet. */
    fun intersects(sections: List<FrameSpan>, range: FrameSpan): Boolean =
        sections.any { it.startFrame < range.endFrame && it.endFrame > range.startFrame }
}

/**
 * T261: Sammelt bewertete Fenster (live oder Rescan) und liefert sie gebuendelt als Zeilen.
 * Zusammenhaengende Fenster werden zu einer `scanned`-Zeile vereinigt. Thread-sicher.
 */
class HumanWindowCollector(private val modelId: String?) {
    private val pending = ArrayList<HumanWindowLine>()
    private var spanStart = -1f
    private var spanEnd = -1f

    /** Ein bewertetes Fenster; [humanScore] null = Modell kennt keine Menschen (nicht als bewertet merken). */
    @Synchronized
    fun add(startSec: Float, endSec: Float, humanScore: Float?) {
        if (humanScore == null || endSec <= startSec) return
        if (humanScore >= HumanWindows.RECORD_FLOOR) {
            pending += HumanWindowLine(HumanWindows.KIND_HUMAN, startSec, endSec, round3(humanScore), modelId)
        }
        if (spanStart < 0f) {
            spanStart = startSec; spanEnd = endSec
        } else if (startSec <= spanEnd + HumanWindows.GAP_TOLERANCE_SEC && endSec >= spanStart - HumanWindows.GAP_TOLERANCE_SEC) {
            spanStart = min(spanStart, startSec); spanEnd = max(spanEnd, endSec)
        } else {
            pending += HumanWindowLine(HumanWindows.KIND_SCANNED, spanStart, spanEnd, model = modelId)
            spanStart = startSec; spanEnd = endSec
        }
    }

    /** Gesammelte Zeilen abholen; der offene Bereich wird bis hierher ausgegeben und laeuft weiter. */
    @Synchronized
    fun drain(): List<HumanWindowLine> {
        if (spanStart >= 0f && spanEnd > spanStart) {
            pending += HumanWindowLine(HumanWindows.KIND_SCANNED, spanStart, spanEnd, model = modelId)
            spanStart = spanEnd
        }
        val out = ArrayList(pending)
        pending.clear()
        return out
    }

    /** Zeilen zurueckgeben, deren Schreiben fehlgeschlagen ist (naechster Versuch). */
    @Synchronized
    fun restore(lines: List<HumanWindowLine>) {
        pending.addAll(0, lines)
    }

    private fun round3(v: Float): Float = Math.round(v * 1000f) / 1000f
}
