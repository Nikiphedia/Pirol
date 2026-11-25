package ch.etasystems.pirol.ui.analysis

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * T276: Reine Zoom-Mathe der Zeitachse (Sekunden auf der Session-Achse), ohne Compose, damit testbar.
 */
object TimeZoom {

    /** Kleinster Ausschnitt: ein Analysefenster (3 s), hoechstens aber die ganze Session. */
    const val MIN_WIDTH_SEC = 3f

    /**
     * Neuer Ausschnitt nach Pinch. Der Zeitpunkt [focusSec] bleibt an derselben relativen Stelle.
     * @param scale > 1 = hineinzoomen (Ausschnitt schmaler), < 1 = herauszoomen
     * @return (Start, Breite); Breite in [min(MIN_WIDTH_SEC, total), total], Start in [0, total - Breite]
     */
    fun zoom(totalSec: Float, startSec: Float, widthSec: Float, scale: Float, focusSec: Float): Pair<Float, Float> {
        if (totalSec <= 0f) return 0f to 0f
        if (scale <= 0f || scale.isNaN() || widthSec <= 0f) return startSec to widthSec
        val newWidth = (widthSec / scale).coerceIn(minOf(MIN_WIDTH_SEC, totalSec), totalSec)
        val rel = ((focusSec - startSec) / widthSec).coerceIn(0f, 1f)
        val newStart = (focusSec - rel * newWidth).coerceIn(0f, (totalSec - newWidth).coerceAtLeast(0f))
        return newStart to newWidth
    }

    /** true, wenn der Ausschnitt praktisch die ganze Session zeigt (dann Zoom aufheben). */
    fun isWhole(totalSec: Float, widthSec: Float): Boolean = widthSec >= totalSec - 0.01f
}

/**
 * T276: Zwei-Finger-Pinch. Greift erst ab zwei gedrueckten Fingern ein (und verbraucht dann die Ereignisse);
 * Einzelfinger-Tippen, Ziehen und Doppeltippen der anderen Detektoren bleiben unberuehrt. Bereits im
 * Initial-Pass verbrauchte Ereignisse (Hilfemodus) werden ignoriert.
 * @param onPinch Zoomfaktor seit dem letzten Ereignis und Schwerpunkt als Anteil 0..1 der Breite
 * @param onEnd Geste beendet (nach einem Pinch)
 */
fun Modifier.pinchZoom(
    onPinch: (zoom: Float, centroidFraction: Float) -> Unit,
    onEnd: () -> Unit = {}
): Modifier = composed {
    val pinchState = rememberUpdatedState(onPinch)
    val endState = rememberUpdatedState(onEnd)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val ignore = down.isConsumed
            var pinched = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!ignore && event.changes.count { it.pressed } >= 2 && size.width > 0) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f && zoom > 0f) {
                        pinched = true
                        pinchState.value(zoom, (event.calculateCentroid().x / size.width).coerceIn(0f, 1f))
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (pinched) endState.value()
        }
    }
}
