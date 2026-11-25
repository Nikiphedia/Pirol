package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.ml.ModelManager.InstalledModel
import ch.etasystems.pirol.ml.ModelProfile

/**
 * T295: Welche Modelle der Rescan-Dialog fuer eine Session anbietet (reine Logik).
 * Modelle mit Mindest-Quellrate ueber der Abtastrate der Session (Ultraschall-Modelle, T293)
 * werden nicht angeboten; sonst kaeme der Fehler erst beim Start.
 */
object RescanModelOffer {

    /** [offered] = anwendbar, [hidden] = wegen zu kleiner Rate ausgeblendet */
    data class Offer(val offered: List<InstalledModel>, val hidden: List<InstalledModel>)

    /** [sessionRateHz] <= 0 = unbekannt: alle Modelle anbieten (der Start meldet dann ggf. den Fehler). */
    fun split(models: List<InstalledModel>, sessionRateHz: Int): Offer {
        if (sessionRateHz <= 0) return Offer(models, emptyList())
        val (ok, no) = models.partition { ModelProfile.sourceRateSupported(it.profile.minSourceRate, sessionRateHz) }
        return Offer(ok, no)
    }

    /** Voreinstellung: das Standardmodell, wenn angeboten, sonst das erste angebotene (null = keines). */
    fun defaultModel(offered: List<InstalledModel>, preferred: String): String? =
        if (offered.any { it.fileName == preferred }) preferred else offered.firstOrNull()?.fileName
}
