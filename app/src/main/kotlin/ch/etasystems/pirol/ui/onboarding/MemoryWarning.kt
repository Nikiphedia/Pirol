package ch.etasystems.pirol.ui.onboarding

import android.app.ActivityManager
import android.content.Context

/** T254: Speicherwarnung fuer Zweitmodelle auf Geraeten mit wenig Arbeitsspeicher. */
object MemoryWarning {
    /** Unterhalb dieser Gesamt-RAM-Menge (ca. 6 GB, Android meldet etwas weniger) wird gewarnt. */
    const val LOW_RAM_BYTES = 5_500_000_000L

    /** Reine Logik: true, wenn der Gesamtspeicher unter der Schwelle liegt. */
    fun isLowRam(totalMemBytes: Long): Boolean = totalMemBytes in 1 until LOW_RAM_BYTES

    fun isLowRam(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return isLowRam(info.totalMem)
    }
}
