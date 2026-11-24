package ch.etasystems.pirol.ui.live

/**
 * T265: Anzeige-Reihenfolge der Live-Artkarten (reine Logik, keine Compose-Abhaengigkeit).
 *
 * Die Reihenfolge aendert sich hoechstens alle [intervalMs] (5 s):
 * - Neue Karten werden gesammelt und beim naechsten Wechsel gemeinsam oben eingefuegt (neueste zuoberst).
 * - Erneut erkannte Art in den Top [TOP_COUNT]: bleibt stehen, Blink-Signal sofort.
 * - Erneut erkannte Art darunter: rueckt beim naechsten Wechsel nach oben und blinkt dann.
 * - Entfernte Karten verschwinden sofort; Daten und Alarm bleiben unberuehrt.
 * - Gesperrte Arten (lockSpecies) stehen immer zuerst (wirkt sofort, wie T73).
 *
 * Nicht thread-safe: nur aus dem UI-Thread benutzen.
 */
class LiveCardOrder(private val intervalMs: Long = INTERVAL_MS) {

    /** Eingabe pro Karte; die Liste kommt neueste zuerst. */
    data class Entry(val id: String, val locked: Boolean, val hitMs: Long)

    private val displayed = mutableListOf<String>()
    private val pendingNew = mutableListOf<String>()      // neueste zuerst
    private val pendingPromote = mutableListOf<String>()  // zuletzt erkannte zuerst
    private val seenHit = HashMap<String, Long>()
    private val blinkAt = HashMap<String, Long>()
    private var lastSwitchMs = 0L

    /** Blink-Zeitpunkt der Karte (0 = nicht blinken). Ersetzt den rohen Treffer-Zeitpunkt der Liste. */
    fun blinkSignalMs(id: String): Long = blinkAt[id] ?: 0L

    /** Wartezeit bis zum naechsten Wechsel, null wenn nichts ansteht. */
    fun nextDueInMs(nowMs: Long): Long? =
        if (pendingNew.isEmpty() && pendingPromote.isEmpty()) null
        else (lastSwitchMs + intervalMs - nowMs).coerceAtLeast(0L)

    /** Neue Session / Liste geleert. */
    fun reset() {
        displayed.clear(); pendingNew.clear(); pendingPromote.clear()
        seenHit.clear(); blinkAt.clear(); lastSwitchMs = 0L
    }

    /** Aktualisiert mit dem aktuellen Datenstand und liefert die Anzeige-Reihenfolge (Ids). */
    fun update(entries: List<Entry>, nowMs: Long): List<String> {
        val present = entries.mapTo(HashSet()) { it.id }

        // Entfernte Karten sofort raus
        displayed.removeAll { it !in present }
        pendingNew.removeAll { it !in present }
        pendingPromote.removeAll { it !in present }
        seenHit.keys.retainAll(present)
        blinkAt.keys.retainAll(present)

        // Neue Karten sammeln (Batch behaelt Reihenfolge neueste zuerst, spaetere Batches davor)
        val shown = displayed.toHashSet()
        val fresh = entries.filter { it.id !in shown && it.id !in pendingNew }
        if (fresh.isNotEmpty()) pendingNew.addAll(0, fresh.map { it.id })

        // Treffer auf bekannten Karten
        for (e in entries) {
            val prev = seenHit[e.id]
            seenHit[e.id] = e.hitMs
            if (prev == null || e.hitMs <= prev) continue
            val pos = displayed.indexOf(e.id)
            if (pos < 0) continue
            if (pos < TOP_COUNT) {
                blinkAt[e.id] = e.hitMs
            } else {
                pendingPromote.remove(e.id)
                pendingPromote.add(0, e.id)
            }
        }

        val hasPending = pendingNew.isNotEmpty() || pendingPromote.isNotEmpty()
        if (hasPending && (displayed.isEmpty() || nowMs - lastSwitchMs >= intervalMs)) {
            val hits = entries.associate { it.id to it.hitMs }
            val promoted = pendingPromote.toList()
            val inserted = pendingNew.toList()
            displayed.removeAll(promoted.toSet())
            displayed.addAll(0, promoted)
            displayed.addAll(0, inserted)
            // Eingefuegte und hochgerueckte Karten leuchten beim Wechsel
            for (id in inserted + promoted) blinkAt[id] = maxOf(hits[id] ?: 0L, nowMs)
            pendingNew.clear(); pendingPromote.clear()
            lastSwitchMs = nowMs
        }

        // Gesperrte Arten zuerst (stabil)
        val locked = entries.filter { it.locked }.mapTo(HashSet()) { it.id }
        return displayed.filter { it in locked } + displayed.filter { it !in locked }
    }

    companion object {
        const val INTERVAL_MS = 5_000L
        const val TOP_COUNT = 5
    }
}
