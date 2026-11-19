package ch.etasystems.pirol.audio

/** Wie ein Alarm auf dem Geraet praesentiert wird (T135). */
enum class AlarmPresentation {
    /** Notification mit Full-Screen-Intent (weckt den Bildschirm, zeigt AlarmActivity) */
    FULL_SCREEN,

    /** Nur Heads-up-Notification (Full-Screen-Intent nicht erlaubt) */
    HEADS_UP,

    /** Keine Notification (Ausgang aus oder Berechtigung fehlt) */
    NONE
}

/** Reine Entscheidungslogik fuer den Alarm-Ausgang (T135), ohne Android-Abhaengigkeit. */
object AlarmPolicy {

    /**
     * @param notificationEnabled Settings-Schalter "Benachrichtigung"
     * @param notificationsAllowed POST_NOTIFICATIONS / Kanal nicht gesperrt
     * @param sdkInt Android-API-Level des Geraets
     * @param canUseFullScreenIntent Ergebnis von NotificationManager.canUseFullScreenIntent() (nur ab API 34 relevant)
     */
    fun decide(
        notificationEnabled: Boolean,
        notificationsAllowed: Boolean,
        sdkInt: Int,
        canUseFullScreenIntent: Boolean
    ): AlarmPresentation {
        if (!notificationEnabled || !notificationsAllowed) return AlarmPresentation.NONE
        val fullScreenOk = sdkInt < 34 || canUseFullScreenIntent
        return if (fullScreenOk) AlarmPresentation.FULL_SCREEN else AlarmPresentation.HEADS_UP
    }

    /** true wenn in den Settings ein Hinweis auf die Full-Screen-Berechtigung gezeigt werden soll. */
    fun needsFullScreenHint(sdkInt: Int, canUseFullScreenIntent: Boolean): Boolean =
        sdkInt >= 34 && !canUseFullScreenIntent
}
