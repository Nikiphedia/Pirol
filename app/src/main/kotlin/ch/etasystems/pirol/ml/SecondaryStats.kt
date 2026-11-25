package ch.etasystems.pirol.ml

/**
 * T279: Zaehllogik der Zweitmodell-Statistik (Grundlage Go/No-Go T213). Rein, ohne IO.
 * Kreuztabelle Verifikationsstatus x Zweitmodell-Status, je Zweitmodell-Id getrennt und gesamt.
 * Manuelle Eintraege werden nicht gezaehlt (kein Modelllauf). Rescan-Varianten liest der Aufrufer
 * nicht ein (sie bewerten dieselbe Aufnahme erneut und wuerden doppelt zaehlen).
 */
object SecondaryStats {
    const val GOAL_CONFIRMED = 200
    const val GOAL_REJECTED = 50

    /** Zeilen der Tabelle. OTHER = korrigiert / ersetzt. */
    enum class Verification { CONFIRMED, REJECTED, UNCERTAIN, OPEN, OTHER }

    /** Spalten der Tabelle. */
    enum class Secondary { CHECKED, NOT_CHECKABLE, DISAGREES, NONE }

    class Table(val counts: Map<Pair<Verification, Secondary>, Int> = emptyMap()) {
        fun get(v: Verification, s: Secondary): Int = counts[v to s] ?: 0
        fun rowSum(v: Verification): Int = Secondary.entries.sumOf { get(v, it) }
        fun colSum(s: Secondary): Int = Verification.entries.sumOf { get(it, s) }
        val total: Int get() = counts.values.sum()

        /** Detektionen mit Zweitmodell-Ergebnis (ohne NONE) in einer Zeile. */
        fun withResult(v: Verification): Int = rowSum(v) - get(v, Secondary.NONE)

        /** Anteil DISAGREES an den Detektionen mit Ergebnis der Zeile; null = keine Daten. */
        fun disagreeRate(v: Verification): Double? =
            withResult(v).takeIf { it > 0 }?.let { get(v, Secondary.DISAGREES).toDouble() / it }

        /** Wie [disagreeRate], aber nur CHECKED + DISAGREES (bei NOT_CHECKABLE kann das Modell nichts sagen). */
        fun disagreeRateCheckable(v: Verification): Double? {
            val n = get(v, Secondary.CHECKED) + get(v, Secondary.DISAGREES)
            return n.takeIf { it > 0 }?.let { get(v, Secondary.DISAGREES).toDouble() / it }
        }
    }

    fun verificationOf(status: VerificationStatus): Verification = when (status) {
        VerificationStatus.CONFIRMED -> Verification.CONFIRMED
        VerificationStatus.REJECTED -> Verification.REJECTED
        VerificationStatus.UNCERTAIN -> Verification.UNCERTAIN
        VerificationStatus.UNVERIFIED -> Verification.OPEN
        VerificationStatus.CORRECTED, VerificationStatus.REPLACED -> Verification.OTHER
    }

    fun secondaryOf(status: String?): Secondary = when (status) {
        SecondaryCheck.STATUS_CHECKED -> Secondary.CHECKED
        SecondaryCheck.STATUS_NOT_CHECKABLE -> Secondary.NOT_CHECKABLE
        SecondaryCheck.STATUS_DISAGREES -> Secondary.DISAGREES
        else -> Secondary.NONE
    }

    /** Gesamtergebnis: Tabelle gesamt und je Zweitmodell-Id (Detektionen ohne Id nur in gesamt). */
    class Result(val total: Table, val perModel: Map<String, Table>)

    /** Sammelt Detektionen (verifizierter Stand) session-weise auf. */
    class Accumulator {
        private val all = HashMap<Pair<Verification, Secondary>, Int>()
        private val models = HashMap<String, HashMap<Pair<Verification, Secondary>, Int>>()

        fun add(detections: List<DetectionResult>) {
            for (d in detections) {
                if (d.manualEntry) continue
                val key = verificationOf(d.verificationStatus) to secondaryOf(d.secondaryStatus)
                all.merge(key, 1, Int::plus)
                val id = d.secondaryModelId
                if (!id.isNullOrBlank()) models.getOrPut(id) { HashMap() }.merge(key, 1, Int::plus)
            }
        }

        fun result(): Result = Result(Table(HashMap(all)), models.mapValues { Table(HashMap(it.value)) })
    }

    fun count(detections: List<DetectionResult>): Result =
        Accumulator().also { it.add(detections) }.result()

    /** Fortschritt zum Ziel (0..1, begrenzt). */
    fun progress(count: Int, goal: Int): Float = if (goal <= 0) 1f else (count.toFloat() / goal).coerceIn(0f, 1f)

    /** CSV (Semikolon, ASCII-Kopf): Zeilen je Modell-Id, "all" fuer gesamt. */
    fun toCsv(result: Result): String {
        val sb = StringBuilder()
        sb.append("model;verification;checked;not_checkable;disagrees;none;total\n")
        fun rows(name: String, t: Table) {
            for (v in Verification.entries) {
                sb.append(name).append(';').append(v.name.lowercase()).append(';')
                    .append(t.get(v, Secondary.CHECKED)).append(';')
                    .append(t.get(v, Secondary.NOT_CHECKABLE)).append(';')
                    .append(t.get(v, Secondary.DISAGREES)).append(';')
                    .append(t.get(v, Secondary.NONE)).append(';')
                    .append(t.rowSum(v)).append('\n')
            }
        }
        rows("all", result.total)
        result.perModel.toSortedMap().forEach { (id, t) -> rows(id, t) }
        return sb.toString()
    }
}
