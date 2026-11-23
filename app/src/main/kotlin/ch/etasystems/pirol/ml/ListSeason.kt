package ch.etasystems.pirol.ml

import java.time.Instant
import java.time.ZoneId

/** T190: Zeitraum einer Artenlisten-Saison (v4), Grenzen inklusiv, ohne Jahr. */
data class SeasonPeriod(val fromMonth: Int, val fromDay: Int, val toMonth: Int, val toDay: Int)

/** T190: Reine Saison-Logik fuer Artenlisten v4 (Spec Abschnitt 4), ohne Android-Abhaengigkeit. */
object ListSeason {

    private val MONTH_LENGTH = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    /**
     * Parst "MM" oder "MM-DD". [isEnd] = true: "MM" bedeutet letzter Tag des Monats.
     * @return (Monat, Tag) oder null bei ungueltigem Wert
     */
    internal fun parseBound(text: String?, isEnd: Boolean): Pair<Int, Int>? {
        val t = text?.trim() ?: return null
        val m = Regex("""^(\d{2})(?:-(\d{2}))?$""").matchEntire(t) ?: return null
        val month = m.groupValues[1].toInt()
        if (month !in 1..12) return null
        val dayText = m.groupValues[2]
        val day = if (dayText.isEmpty()) {
            if (isEnd) MONTH_LENGTH[month - 1] else 1
        } else dayText.toInt()
        if (day !in 1..MONTH_LENGTH[month - 1]) return null
        return month to day
    }

    /** Parst einen Zeitraum; null wenn von/bis ungueltig. */
    fun parsePeriod(from: String?, to: String?): SeasonPeriod? {
        val f = parseBound(from, false) ?: return null
        val t = parseBound(to, true) ?: return null
        return SeasonPeriod(f.first, f.second, t.first, t.second)
    }

    /** Liegt (Monat, Tag) im Zeitraum? Jahreswechsel-Zeitraeume (von > bis) werden unterstuetzt. */
    fun inPeriod(p: SeasonPeriod, month: Int, day: Int): Boolean {
        val d = month * 100 + day
        val f = p.fromMonth * 100 + p.fromDay
        val t = p.toMonth * 100 + p.toDay
        return if (f <= t) d in f..t else d >= f || d <= t
    }

    /**
     * Saison-Pruefung: leere Liste = ganzjaehrig; sonst Vereinigung aller Zeitraeume.
     * Datum fehlt (null) = nicht auswerten (plausibel).
     */
    fun isInSeason(periods: List<SeasonPeriod>?, monthDay: Pair<Int, Int>?): Boolean {
        if (periods.isNullOrEmpty() || monthDay == null) return true
        return periods.any { inPeriod(it, monthDay.first, monthDay.second) }
    }

    /** Lokales Kalenderdatum (Monat, Tag) eines Zeitstempels in der angegebenen Zeitzone. */
    fun monthDay(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Int> {
        val d = Instant.ofEpochMilli(epochMs).atZone(zone)
        return d.monthValue to d.dayOfMonth
    }
}
