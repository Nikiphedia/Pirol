package ch.etasystems.pirol.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/**
 * T194: Reine Geometrie der Uebersichtsleiste (ohne Compose, damit testbar).
 * Alle Zeiten in Sekunden auf der Session-Achse (ueber alle WAV-Segmente).
 */
object OverviewBarGeometry {

    /** Mindestbreite des Ausschnitt-Rechtecks als Anteil der Leistenbreite, damit es greifbar bleibt. */
    const val MIN_RECT_FRACTION = 0.02f

    /** Standardbreite des Ausschnitts, wenn aus der Gesamtansicht heraus getippt wird. */
    const val DEFAULT_WINDOW_SEC = 30f

    /** Rechteck als Anteile (links, rechts) 0..1; Gesamtansicht (kein Zoom) = (0, 1). */
    fun windowFractions(totalSec: Float, startSec: Float, endSec: Float): Pair<Float, Float> {
        if (totalSec <= 0f) return 0f to 1f
        val l = (startSec / totalSec).coerceIn(0f, 1f)
        var r = (endSec / totalSec).coerceIn(0f, 1f)
        if (r - l < MIN_RECT_FRACTION) r = (l + MIN_RECT_FRACTION).coerceAtMost(1f)
        return (r - MIN_RECT_FRACTION).coerceAtLeast(0f).coerceAtMost(l) to r
    }

    /** Fensterbreite beim Verschieben: bestehender Zoom bleibt, sonst Standard (hoechstens Gesamtdauer). */
    fun windowWidth(totalSec: Float, currentWidthSec: Float?): Float =
        (currentWidthSec ?: DEFAULT_WINDOW_SEC).coerceIn(0f, totalSec)

    /** Startsekunde so, dass das Fenster auf [xFraction] zentriert ist; auf [0, total - Breite] begrenzt. */
    fun startForCenter(totalSec: Float, widthSec: Float, xFraction: Float): Float =
        clampStart(totalSec, widthSec, xFraction.coerceIn(0f, 1f) * totalSec - widthSec / 2f)

    /** Startsekunde nach Ziehen um [dxFraction] der Leistenbreite; auf [0, total - Breite] begrenzt. */
    fun startAfterDrag(totalSec: Float, widthSec: Float, startSec: Float, dxFraction: Float): Float =
        clampStart(totalSec, widthSec, startSec + dxFraction * totalSec)

    fun clampStart(totalSec: Float, widthSec: Float, startSec: Float): Float =
        startSec.coerceIn(0f, (totalSec - widthSec).coerceAtLeast(0f))
}

/**
 * T194: Schmale Uebersichtsleiste ueber die ganze Session. Zeigt den aktuell angezeigten Ausschnitt als
 * Rechteck und die Detektionen als Striche; Tippen zentriert den Ausschnitt, Ziehen verschiebt ihn.
 *
 * @param viewStartSec/viewEndSec aktueller Ausschnitt; null = ganze Session sichtbar
 * @param onWindowChange neuer Ausschnittsbeginn (Sekunden) und Breite; wird beim Loslassen/Tippen gemeldet
 */
@Composable
fun OverviewBar(
    totalSec: Float,
    viewStartSec: Float?,
    viewEndSec: Float?,
    markers: List<Pair<Float, Float>>,
    contentDescription: String,
    onWindowChange: (startSec: Float, widthSec: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (totalSec <= 0f) return
    val currentWidth = if (viewStartSec != null && viewEndSec != null) viewEndSec - viewStartSec else null
    // waehrend des Ziehens lokaler Start, damit das Rechteck mitlaeuft; erst beim Loslassen wird neu gerendert
    var dragStart by remember { mutableStateOf<Float?>(null) }
    val width = OverviewBarGeometry.windowWidth(totalSec, currentWidth)
    val shownStart = dragStart ?: viewStartSec ?: 0f
    val shownEnd = if (dragStart != null || currentWidth != null) shownStart + width else totalSec
    val primary = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val markColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)  // 48 dp Tap-Ziel
            .clip(RoundedCornerShape(6.dp))
            .background(trackColor)
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(totalSec, currentWidth) {
                detectTapGestures { pos ->
                    val w = OverviewBarGeometry.windowWidth(totalSec, currentWidth)
                    onWindowChange(
                        OverviewBarGeometry.startForCenter(totalSec, w, pos.x / size.width),
                        w
                    )
                }
            }
            .pointerInput(totalSec, currentWidth, viewStartSec) {
                detectDragGestures(
                    onDragStart = { dragStart = viewStartSec ?: 0f },
                    onDragEnd = {
                        val s = dragStart
                        dragStart = null
                        if (s != null) onWindowChange(s, OverviewBarGeometry.windowWidth(totalSec, currentWidth))
                    },
                    onDragCancel = { dragStart = null }
                ) { change, drag ->
                    change.consume()
                    val w = OverviewBarGeometry.windowWidth(totalSec, currentWidth)
                    val cur = dragStart ?: viewStartSec ?: 0f
                    dragStart = OverviewBarGeometry.startAfterDrag(totalSec, w, cur, drag.x / size.width)
                }
            }
    ) {
        // Detektions-Striche
        for ((s, e) in markers) {
            val x0 = (s / totalSec).coerceIn(0f, 1f) * size.width
            val x1 = (e / totalSec).coerceIn(0f, 1f) * size.width
            drawRect(markColor, Offset(x0, size.height * 0.55f), Size((x1 - x0).coerceAtLeast(2f), size.height * 0.4f))
        }
        // Ausschnitt
        val (l, r) = OverviewBarGeometry.windowFractions(totalSec, shownStart, shownEnd)
        drawRect(primary.copy(alpha = 0.25f), Offset(l * size.width, 0f), Size((r - l) * size.width, size.height))
        drawRect(primary, Offset(l * size.width, 0f), Size((r - l) * size.width, size.height), style = Stroke(width = 3f))
    }
}
