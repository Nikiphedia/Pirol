package ch.etasystems.pirol.ml

/**
 * T238: Haelt die native Modell-Session eines Classifiers und zaehlt laufende Inferenzen.
 *
 * - Eine Session wird nie waehrend eines Laufs geschlossen: [detach] stellt sie zurueck,
 *   [end] schliesst sie nach dem letzten Lauf.
 * - [install] ersetzt eine vorhandene Session nie stillschweigend (keine verwaiste Session).
 *
 * Reine Logik ohne ORT-Abhaengigkeit (Tests mit Fake-Sessions).
 */
internal class SessionGuard<T : Any>(private val closer: (T) -> Unit) {
    private var current: T? = null
    private var active = 0
    private val retired = ArrayList<T>(1)

    @get:Synchronized
    val session: T? get() = current

    @get:Synchronized
    val activeRuns: Int get() = active

    /** Zurueckgestellte Sessions, die nach dem laufenden Lauf geschlossen werden */
    @get:Synchronized
    val pendingClose: Int get() = retired.size

    /** Neue Session uebernehmen; eine vorhandene wird wie bei [detach] geschlossen bzw. zurueckgestellt. */
    @Synchronized
    fun install(s: T) {
        current?.let { if (it !== s) retire(it) }
        current = s
    }

    /** Lauf beginnen (Session muss die aktuelle sein); jedem [begin] folgt genau ein [end]. */
    @Synchronized
    fun begin(): T? {
        val s = current ?: return null
        active++
        return s
    }

    /** Lauf beenden; nach dem letzten Lauf zurueckgestellte Sessions schliessen. */
    @Synchronized
    fun end() {
        if (active > 0) active--
        if (active == 0 && retired.isNotEmpty()) {
            val list = retired.toList()
            retired.clear()
            list.forEach { closeQuietly(it) }
        }
    }

    /**
     * Aktuelle Session abgeben (Modellwechsel, Freigabe). Ohne laufende Inferenz sofort geschlossen,
     * sonst nach dem letzten Lauf. @return true, wenn eine Session vorhanden war
     */
    @Synchronized
    fun detach(): Boolean {
        val s = current ?: return false
        current = null
        retire(s)
        return true
    }

    /** T187: nur ohne laufende Inferenz freigeben. @return true, wenn geschlossen wurde */
    @Synchronized
    fun releaseIfIdle(): Boolean {
        if (active > 0 || current == null) return false
        return detach()
    }

    private fun retire(s: T) {
        if (active == 0) closeQuietly(s) else retired.add(s)
    }

    private fun closeQuietly(s: T) {
        try { closer(s) } catch (_: Exception) { /* Schliessen darf den Aufrufer nicht stoeren */ }
    }
}
