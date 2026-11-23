package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.ml.DetectionResult

/**
 * T236: Gesammelte Aenderungen an bereits geschriebenen Detektionen (Zweitmodell, Cluster-Merge, GPS-Fix).
 * Pro ID gilt der zuletzt gemeldete Stand. Geschrieben wird gebuendelt (ein Rewrite fuer alle),
 * neue Detektionen werden weiterhin sofort angehaengt.
 *
 * Reihenfolge Append/Rewrite: Ein Update fuer eine noch nicht angehaengte Detektion bleibt stehen
 * ([markWritten] entfernt nur gefundene IDs) bzw. ersetzt beim Anhaengen direkt die alte Fassung
 * ([takeForAppend]). Thread-sicher, keine Ein-/Ausgabe.
 */
class DetectionUpdateQueue {
    private val pending = LinkedHashMap<String, DetectionResult>()

    @Synchronized fun put(updated: DetectionResult) { pending[updated.id] = updated }

    @Synchronized fun isEmpty(): Boolean = pending.isEmpty()

    @Synchronized fun size(): Int = pending.size

    /** Schnappschuss fuer einen Rewrite (Eintraege bleiben bis [markWritten] stehen). */
    @Synchronized fun snapshot(): Map<String, DetectionResult> = LinkedHashMap(pending)

    /**
     * Nach erfolgreichem Rewrite: geschriebene Eintraege entfernen, sofern seither kein neuerer kam.
     * Nicht gefundene IDs bleiben stehen (Zeile noch nicht angehaengt).
     */
    @Synchronized fun markWritten(batch: Map<String, DetectionResult>, matchedIds: Set<String>) {
        for (id in matchedIds) {
            if (pending[id] === batch[id]) pending.remove(id)
        }
    }

    /** Beim Anhaengen: neuere gesammelte Fassung statt der alten verwenden und aus der Warteschlange nehmen. */
    @Synchronized fun takeForAppend(detections: List<DetectionResult>): List<DetectionResult> =
        detections.map { d -> pending.remove(d.id) ?: d }

    /**
     * Alle vorgemerkten Updates in EINEM Rewrite schreiben: [read] liefert den aktuellen Dateiinhalt
     * (null = Datei fehlt), [rewrite] schreibt den neuen (nur wenn mindestens eine Zeile passt).
     * Wirft [rewrite] eine Exception, bleiben die Eintraege fuer den naechsten Versuch stehen.
     * @return IDs der geschriebenen Updates
     */
    fun flush(read: () -> String?, rewrite: (String) -> Unit): Set<String> {
        if (isEmpty()) return emptySet()
        val batch = snapshot()
        val text = read() ?: return emptySet()
        val result = SessionFilesLogic.replaceDetectionsInText(text, batch)
        if (result.matchedIds.isEmpty()) return emptySet()
        rewrite(result.text)
        markWritten(batch, result.matchedIds)
        return result.matchedIds
    }

    /** Alles verwerfen (Session-Ende); liefert die Zahl verworfener Eintraege. */
    @Synchronized fun clear(): Int = pending.size.also { pending.clear() }
}
