package ch.etasystems.pirol.data.repository

/**
 * T236: Absturzsicheres Ersetzen einer Datei fuer Speicher ohne atomares Umbenennen (SAF).
 *
 * Ablauf "Schattenkopie mit Fertig-Marke":
 *  1. Neuen Inhalt vollstaendig nach `<name>.new` schreiben (inkl. sync).
 *  2. Leere Marke `<name>.new.ok` anlegen = Schattenkopie ist vollstaendig.
 *  3. Original mit dem neuen Inhalt ueberschreiben ("wt", inkl. sync).
 *  4. Marke loeschen, dann `<name>.new` loeschen.
 *
 * Zu jedem Zeitpunkt existiert eine vollstaendige Fassung: bis Schritt 2 das unveraenderte Original,
 * ab Schritt 2 die Schattenkopie. [recover] stellt nach einem Prozess-Kill eindeutig her
 * (Marke vorhanden -> Schattenkopie zurueckkopieren, sonst Schattenkopie verwerfen), ohne Heuristik.
 *
 * Warum nicht Umbenennen: SAF-Provider muessen Umbenennen nicht unterstuetzen, koennen Namen/Endung
 * anpassen oder die URI aendern, und "ersetzen" geht nur ueber Loeschen + Umbenennen (Moment ohne Datei).
 * Die Kopie mit Marke braucht nur Erstellen/Schreiben/Loeschen, die URI des Originals bleibt gleich.
 */
object ShadowReplace {
    const val NEW_SUFFIX = ".new"
    const val OK_SUFFIX = ".new.ok"

    /** Minimaler Dateizugriff im Session-Ordner (Produktiv: SAF, Test: Fake mit Kill-Simulation). */
    interface Store {
        fun exists(name: String): Boolean
        /** null = Datei fehlt */
        fun read(name: String): String?
        /** Datei erstellen bzw. kuerzen, [content] schreiben und auf den Datentraeger bringen */
        fun write(name: String, content: String)
        /** Leere Datei erstellen (Marke) */
        fun create(name: String)
        fun delete(name: String)
    }

    fun shadowName(name: String) = name + NEW_SUFFIX
    fun markerName(name: String) = name + OK_SUFFIX

    /** Liegen Reste eines unterbrochenen Ersetzens von [name] in [names]? */
    fun needsRecovery(names: Collection<String>, name: String): Boolean =
        shadowName(name) in names || markerName(name) in names

    /** Ersetzt [name] durch [content]; raeumt vorher Reste eines frueheren Abbruchs auf. */
    fun replace(store: Store, name: String, content: String) {
        recover(store, name)
        val shadow = shadowName(name)
        val marker = markerName(name)
        store.write(shadow, content)
        store.create(marker)
        store.write(name, content)
        store.delete(marker)
        store.delete(shadow)
    }

    /**
     * Stellt nach einem Abbruch einen eindeutigen Zustand her.
     * @return true wenn etwas aufgeraeumt oder wiederhergestellt wurde
     */
    fun recover(store: Store, name: String): Boolean {
        val shadow = shadowName(name)
        val marker = markerName(name)
        val hasMarker = store.exists(marker)
        val hasShadow = store.exists(shadow)
        if (!hasMarker && !hasShadow) return false
        if (hasMarker && hasShadow) {
            // Schattenkopie ist vollstaendig, das Original evtl. halb ueberschrieben -> zurueckkopieren
            val content = store.read(shadow) ?: ""
            store.write(name, content)
        }
        // Ohne Marke ist die Schattenkopie evtl. unvollstaendig; das Original ist dann unberuehrt
        if (hasMarker) store.delete(marker)
        if (hasShadow) store.delete(shadow)
        return true
    }
}
