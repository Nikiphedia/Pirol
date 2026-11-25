package ch.etasystems.pirol.data

/**
 * T297: Zentrale Schalter fuer versteckte Funktionen. Wert kommt aus [AppPreferences.experimentalBats]
 * (beim Start und bei jeder Aenderung gesetzt); Default aus = Verhalten wie v0.1.2.
 */
object FeatureFlags {
    /** Fledermaus-/Ultraschall-Funktionen (T292-T295) */
    @Volatile
    var bats: Boolean = false
}
