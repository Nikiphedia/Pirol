package ch.etasystems.pirol.ml

/** T223: reine Regeln fuer Primaer-/Sekundaermodell und Loeschbarkeit (ohne Android-Abhaengigkeit). */
object ModelRoles {

    enum class DeleteBlock { NONE, PRIMARY, RECORDING }

    /** Sekundaer-Auswahl: erneutes Antippen waehlt ab, das Primaermodell kann nie Sekundaer sein. */
    fun toggleSecondary(primary: String, secondary: String?, tapped: String): String? = when {
        tapped == primary -> secondary
        tapped == secondary -> null
        else -> tapped
    }

    /** Nach Primaer-Wechsel: war das neue Primaermodell das Sekundaere, wird die Sekundaer-Auswahl geleert. */
    fun secondaryAfterPrimaryChange(newPrimary: String, secondary: String?): String? =
        if (secondary == newPrimary) null else secondary

    /**
     * T293: Ultraschall-Modell als Zweitmodell nur, wenn auch das Primaermodell Ultraschall-Quellen braucht.
     * Ohne Ultraschall-Primaermodell ist die Quelle live 48 kHz (und Rescans laufen mit dessen Audio): das
     * Zweitmodell koennte nie rechnen (Mindestrate nicht erreicht) und die Detektionen blieben ungeprueft.
     */
    fun secondaryAllowed(primary: ModelProfile?, secondary: ModelProfile?): Boolean =
        secondary == null || (secondary.minSourceRate ?: 0) <= (primary?.minSourceRate ?: ModelProfile.LIVE_SOURCE_RATE)

    /** Primaermodell ist nicht loeschbar; waehrend einer Aufnahme ebenfalls nicht. */
    fun deleteBlock(fileName: String, primary: String, recording: Boolean): DeleteBlock = when {
        fileName == primary -> DeleteBlock.PRIMARY
        recording -> DeleteBlock.RECORDING
        else -> DeleteBlock.NONE
    }

    fun secondaryAfterDelete(deleted: String, secondary: String?): String? =
        if (secondary == deleted) null else secondary
}
