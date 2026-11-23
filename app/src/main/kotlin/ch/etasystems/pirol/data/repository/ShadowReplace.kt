package ch.etasystems.pirol.data.repository

import java.io.IOException

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

    /** T240: Ist [name] eine Schattenkopie bzw. Marke (nie exportieren/hochladen)? */
    fun isShadowFile(name: String): Boolean = name.endsWith(NEW_SUFFIX) || name.endsWith(OK_SUFFIX)

    /** T240: Zieldatei zu einer Schattenkopie bzw. Marke; null = kein Schattenname */
    fun originalOf(name: String): String? = when {
        name.endsWith(OK_SUFFIX) -> name.removeSuffix(OK_SUFFIX)
        name.endsWith(NEW_SUFFIX) -> name.removeSuffix(NEW_SUFFIX)
        else -> null
    }?.takeIf { it.isNotEmpty() }

    fun shadowName(name: String) = name + NEW_SUFFIX
    fun markerName(name: String) = name + OK_SUFFIX

    /** Liegen Reste eines unterbrochenen Ersetzens von [name] in [names]? */
    fun needsRecovery(names: Collection<String>, name: String): Boolean =
        shadowName(name) in names || markerName(name) in names

    /**
     * T249 (M3b): Der Provider kann [name] nicht unter genau diesem Namen anlegen (haengt z.B. eine Endung an).
     * Dauerhaft, nicht voruebergehend: [replace] faellt dann auf den direkten Rewrite zurueck.
     */
    class UnsupportedNameException(name: String) : IOException("Speicher kann $name nicht anlegen")

    /**
     * Ersetzt [name] durch [content]; raeumt vorher Reste eines frueheren Abbruchs auf.
     * Aufrufer haelt die Schreibsperre der Session.
     *
     * T249 (M3b): Kann der Provider Schattenkopie oder Marke nicht unter ihrem Namen anlegen
     * ([UnsupportedNameException]), wird das Original direkt ueberschrieben (Verhalten vor T236),
     * statt dass jedes Schreiben dauerhaft scheitert. Andere Fehler (voller Speicher, IO) gehen an den
     * Aufrufer; das Original bleibt dann unberuehrt.
     */
    fun replace(store: Store, name: String, content: String) {
        recover(store, name)
        val shadow = shadowName(name)
        val marker = markerName(name)
        try {
            store.write(shadow, content)
            store.create(marker)
        } catch (e: UnsupportedNameException) {
            // Reste ohne Marke sind harmlos, trotzdem entfernen (Marke zuerst)
            runCatching { if (store.exists(marker)) store.delete(marker) }
            runCatching { if (store.exists(shadow)) store.delete(shadow) }
            store.write(name, content)
            return
        }
        store.write(name, content)
        store.delete(marker)
        store.delete(shadow)
    }

    /**
     * T249 (H2): Inhalt von [name] lesen, ohne etwas zu schreiben oder zu loeschen.
     * Liegt die Fertig-Marke, ist die Schattenkopie die gueltige Fassung (das Original kann halb
     * ueberschrieben sein). Ist die Kopie inzwischen weg (Schreiber fertig), gilt das Original.
     * @return Inhalt oder null (Datei fehlt)
     * @throws IOException wenn die Kopie gilt, aber nicht lesbar ist (abgeschnittenes Original nie als gueltig liefern)
     */
    fun read(store: Store, name: String): String? {
        if (store.exists(markerName(name))) {
            val shadow = shadowName(name)
            store.read(shadow)?.let { return it }
            if (store.exists(shadow)) throw IOException("Schattenkopie $shadow nicht lesbar")
        }
        return store.read(name)
    }

    /**
     * Stellt nach einem Abbruch einen eindeutigen Zustand her. Schreibt: nur unter der Schreibsperre
     * der Session aufrufen, nie fuer eine Session, die gerade schreibt (T249, H2).
     * @return true wenn etwas aufgeraeumt oder wiederhergestellt wurde
     * @throws IOException wenn die vollstaendige Schattenkopie nicht lesbar ist; dann bleibt alles liegen
     *   (T249, M7: frueher wurde das Original mit leerem Inhalt ueberschrieben)
     */
    fun recover(store: Store, name: String): Boolean {
        val shadow = shadowName(name)
        val marker = markerName(name)
        val hasMarker = store.exists(marker)
        val hasShadow = store.exists(shadow)
        if (!hasMarker && !hasShadow) return false
        if (hasMarker && hasShadow) {
            // Schattenkopie ist vollstaendig, das Original evtl. halb ueberschrieben -> zurueckkopieren
            val content = store.read(shadow) ?: throw IOException("Schattenkopie $shadow nicht lesbar")
            store.write(name, content)
        }
        // Ohne Marke ist die Schattenkopie evtl. unvollstaendig; das Original ist dann unberuehrt
        if (hasMarker) store.delete(marker)
        if (hasShadow) store.delete(shadow)
        return true
    }
}
