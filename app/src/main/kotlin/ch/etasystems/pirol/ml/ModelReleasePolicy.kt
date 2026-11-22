package ch.etasystems.pirol.ml

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * T187: Entscheidung, ob die ONNX-Session im Hintergrund freigegeben wird (reine Funktion, testbar).
 */
object ModelReleasePolicy {
    /** Wartezeit im Hintergrund, bevor freigegeben wird (gegen Flattern bei kurzem App-Wechsel) */
    const val RELEASE_DELAY_MS = 45_000L

    fun shouldRelease(
        inBackground: Boolean,
        recording: Boolean,
        backgroundSinceMs: Long,
        nowMs: Long,
        delayMs: Long = RELEASE_DELAY_MS
    ): Boolean = inBackground && !recording && nowMs - backgroundSinceMs >= delayMs
}

/**
 * T187: Beobachtet, ob irgendeine Activity sichtbar ist (gestartet), und gibt das Modell nach
 * [ModelReleasePolicy.RELEASE_DELAY_MS] im Hintergrund frei. Bewusst ohne lifecycle-process:
 * Activity-Callbacks genuegen (eine Activity) und vermeiden eine neue Abhaengigkeit.
 * Laeuft auf dem Main-Thread; die Freigabe selbst prueft laufende Inferenz (releaseIfIdle).
 */
class ModelReleaseObserver(
    private val isRecording: () -> Boolean,
    private val release: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() }
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private var startedCount = 0
    private var backgroundSince = 0L

    private val check = object : Runnable {
        override fun run() {
            if (startedCount > 0) return
            val t = now()
            if (isRecording()) {
                // Aufnahme laeuft: Hintergrundzeit neu beginnen, spaeter erneut pruefen
                backgroundSince = t
                handler.postDelayed(this, ModelReleasePolicy.RELEASE_DELAY_MS)
            } else if (ModelReleasePolicy.shouldRelease(true, false, backgroundSince, t)) {
                if (release()) Log.i("ModelRelease", "Modell im Hintergrund freigegeben")
            } else {
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        startedCount++
        handler.removeCallbacks(check)
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedCount > 0) startedCount--
        if (startedCount == 0) {
            backgroundSince = now()
            handler.removeCallbacks(check)
            handler.postDelayed(check, ModelReleasePolicy.RELEASE_DELAY_MS)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
