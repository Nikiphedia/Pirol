package ch.etasystems.pirol.ml

/** T227: reine Zustandslogik einer Zeile der Modelltabelle (ohne Android-Abhaengigkeit). */
object ModelTableRow {

    /** Symbol am Zeilenende: Papierkorb, Papierkorb gesperrt, Lade-Symbol, Lade-Symbol gesperrt, Fortschritt. */
    enum class Trailing { DELETE, DELETE_DISABLED, DOWNLOAD, DOWNLOAD_DISABLED, PROGRESS }

    fun trailing(installed: Boolean, downloadingThis: Boolean, downloadingOther: Boolean, block: ModelRoles.DeleteBlock): Trailing = when {
        downloadingThis -> Trailing.PROGRESS
        installed -> if (block == ModelRoles.DeleteBlock.NONE) Trailing.DELETE else Trailing.DELETE_DISABLED
        downloadingOther -> Trailing.DOWNLOAD_DISABLED
        else -> Trailing.DOWNLOAD
    }

    /** Primaer (Radio) und Sekundaer (Checkbox) sind nur bei installierten Modellen waehlbar; Sekundaer nie beim Primaermodell. */
    fun primaryEnabled(installed: Boolean): Boolean = installed

    fun secondaryEnabled(installed: Boolean, isPrimary: Boolean, rateAllowed: Boolean = true): Boolean =
        installed && !isPrimary && rateAllowed  // T293: rateAllowed = ModelRoles.secondaryAllowed

    /** Katalogmodelle mit Download-URL, die noch nicht installiert sind (erscheinen als Zeile mit Lade-Symbol). */
    fun missingCatalog(catalog: List<ModelProfile>, installedFiles: Set<String>): List<ModelProfile> =
        catalog.filter { it.downloadUrl != null && it.fileName !in installedFiles }
}
