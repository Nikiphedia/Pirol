package ch.etasystems.pirol.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import kotlin.math.pow

/**
 * Compose Canvas fuer Rolling-Sonogramm.
 *
 * Rendering-Strategie:
 * - Internes Bitmap (ARGB_8888) als Off-Screen-Pixel-Buffer
 * - Neue Frames werden rechts angefuegt (1 Frame = 1 Pixel-Spalte im Buffer)
 * - Rolling: Ring-Buffer-Index statt Pixel-Shift → kein memcpy
 * - Canvas zeichnet nur drawImage() → minimal Compose-Overhead
 *
 * T56: Wert-zu-Palette-Mapping ist jetzt dynamisch steuerbar.
 * - autoContrast=true → Range kommt vom DynamicRangeMapper in SpectrogramState
 *   (Perzentil p5/p95 ueber Rolling-Window). Leise Rufe bleiben sichtbar.
 * - autoContrast=false → Range kommt aus manualMinDb/manualMaxDb (Settings-Slider).
 * - [manualRangeOverride] (nicht-null) erzwingt fixen Bereich unabhaengig vom Toggle —
 *   wird vom Analyse-Tab genutzt (Einmal-Perzentil ueber ganze Session).
 *
 * T56b: Gamma-Kompression auf dem normalisierten Palette-Index.
 * - gamma < 1.0 → leise Anteile werden heller (Ziel: leise Voegel sichtbar).
 * - gamma == 1.0 → identisch zum T56-Verhalten (kein Effekt).
 * - Orthogonal zu autoContrast: wirkt auch bei fixem Range.
 * - Performance: kombinierter Gamma+Farb-LUT (256 Eintraege), lazy recompute nur bei Aenderung.
 *
 * T56b-Ceiling: Lautstärke-Deckel — clippt die Normalisierungs-Obergrenze.
 * - ceilingDb = 0f → kein Effekt (Mel-Werte sind bereits <= 0 dBFS).
 * - ceilingDb < 0f → z.B. -10 dB: Signale ueber -10 dB werden auf Max-Palette-Farbe geclippt.
 * - Wirkt immer: unabhaengig von autoContrast und gamma.
 * - Kombiniert mit Gamma: erst Range + Ceiling, dann Gamma-Kompression.
 *
 * @param spectrogramState State-Holder mit Ring-Buffer + optionalem Mapper
 * @param config SpectrogramConfig fuer numMelBins (minDb wird nur als Fallback verwendet)
 * @param palette Farbpalette (Default: MAGMA)
 * @param autoContrast Toggle fuer Auto-Kontrast (Default: true)
 * @param manualMinDb Manuelle Untergrenze wenn autoContrast=false (Default: -80)
 * @param manualMaxDb Manuelle Obergrenze wenn autoContrast=false (Default: 0)
 * @param manualRangeOverride Optionaler fixer Bereich (z.B. Analyse-Tab Einmal-Perzentil)
 * @param gamma Gamma-Kompression auf normalisiertem Index (< 1 = leise heller, 1.0 = aus). Default=1.
 * @param ceilingDb Obergrenze fuer Normalisierung in dBFS (0 = kein Effekt, negativ = clippt Lautes). Default=0.
 * @param modifier Standard-Compose-Modifier
 */
@Composable
fun SpectrogramCanvas(
    spectrogramState: SpectrogramState,
    config: SpectrogramConfig,
    palette: SpectrogramPalette = SpectrogramPalette.MAGMA,
    autoContrast: Boolean = true,
    manualMinDb: Float = -80f,
    manualMaxDb: Float = 0f,
    manualRangeOverride: Pair<Float, Float>? = null,
    gamma: Float = 1f,
    ceilingDb: Float = 0f,
    modifier: Modifier = Modifier
) {
    val numMelBins = config.numMelBins
    val fallbackMinDb = config.minDb
    val lut = remember(palette) { SpectrogramPalette.lutFor(palette) }

    // Bitmap-Renderer: verwaltet Off-Screen-Bitmap und Ring-Buffer-Logik
    val renderer = remember { BitmapRenderer() }

    // Recomposition-Trigger: liest frameVersion → Canvas wird bei neuen Frames neu gezeichnet
    val version = spectrogramState.frameVersion

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    renderer.onSizeChanged(size, numMelBins)
                }
        ) {
            val bitmapWidth = renderer.bitmapWidth
            if (bitmapWidth <= 0) return@Canvas

            // Sichtbare Frames holen (max = Bitmap-Breite)
            val frames = spectrogramState.getVisibleFrames(bitmapWidth)

            // T56: Range bestimmen. Prioritaet: override > autoContrast+mapper > manual > fallback.
            val (minDbUsed, maxDbRaw) = when {
                manualRangeOverride != null -> manualRangeOverride
                autoContrast -> spectrogramState.dynamicRangeMapper?.currentRange()
                    ?: (fallbackMinDb to 0f)
                else -> manualMinDb to manualMaxDb
            }

            // T56b-Ceiling: Obergrenze clippen (0f = kein Effekt; negativ = Lautes abschneiden).
            val maxDbUsed = if (ceilingDb < maxDbRaw) maxDbRaw.coerceAtMost(ceilingDb) else maxDbRaw

            // Bitmap aktualisieren (T56b: gamma + gecapptes maxDb durchreichen)
            renderer.renderFrames(frames, numMelBins, minDbUsed, maxDbUsed, lut, gamma)

            // Bitmap auf Canvas zeichnen (skaliert auf volle Canvas-Groesse)
            renderer.bitmap?.let { bmp ->
                drawScaledBitmap(bmp, this.size)
            }

            // version-Variable lesen damit Compose die Dependency trackt
            @Suppress("UNUSED_EXPRESSION")
            version
        }
    }
}

/**
 * Off-Screen-Bitmap-Renderer.
 *
 * Verwaltet ein Bitmap mit fester Hoehe (numMelBins) und variabler Breite (Canvas-Breite in Pixel).
 * Keine Allokationen im Steady-State: pixelRow wird wiederverwendet, setPixels() batch.
 */
internal class BitmapRenderer {
    var bitmap: Bitmap? = null
        private set
    var bitmapWidth: Int = 0
        private set
    private var bitmapHeight: Int = 0

    // Wiederverwendbarer Pixel-Zeilen-Buffer fuer setPixels()
    private var pixelColumn = IntArray(0)

    // Voller Pixel-Buffer fuer Batch-Schreibzugriff
    private var pixelBuffer = IntArray(0)

    // T56b: Kombinierter Gamma+Farb-LUT (256 Eintraege).
    // Direkter Pixel-Lookup ohne pow()-Aufruf im Render-Loop.
    // Wird nur neu berechnet wenn sich Gamma oder Farbpalette aendern (lazy).
    private var cachedGamma: Float = Float.NaN
    private var cachedColorLut: IntArray? = null
    private var gammaColorLut: IntArray = IntArray(256)

    /**
     * Aktualisiert den kombinierten Gamma+Farb-LUT bei Aenderung von Gamma oder Palette.
     * Bei gamma == 1.0 wird die Farbpalette direkt kopiert (kein pow()-Overhead).
     */
    private fun updateGammaLutIfNeeded(gamma: Float, colorLut: IntArray) {
        if (gamma == cachedGamma && colorLut === cachedColorLut) return
        cachedGamma = gamma
        cachedColorLut = colorLut
        if (gamma == 1f) {
            colorLut.copyInto(gammaColorLut)
        } else {
            for (i in 0..255) {
                val compressed = (i / 255f).pow(gamma)
                gammaColorLut[i] = colorLut[(compressed * 255f).toInt().coerceIn(0, 255)]
            }
        }
    }

    /**
     * Wird bei Canvas-Groessenaenderung aufgerufen. Alloziert neues Bitmap falls noetig.
     */
    fun onSizeChanged(size: IntSize, numMelBins: Int) {
        val newWidth = size.width
        val newHeight = numMelBins

        if (newWidth <= 0 || newHeight <= 0) return
        if (newWidth == bitmapWidth && newHeight == bitmapHeight && bitmap != null) return

        // Neues Bitmap allozieren
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        bitmapWidth = newWidth
        bitmapHeight = newHeight
        pixelColumn = IntArray(newHeight)
        pixelBuffer = IntArray(newWidth * newHeight)

        // Schwarz fuellen (Stille)
        val black = (0xFF shl 24) // ARGB schwarz
        pixelBuffer.fill(black)
        bitmap?.setPixels(pixelBuffer, 0, newWidth, 0, 0, newWidth, newHeight)
    }

    /**
     * Rendert alle sichtbaren Frames ins Bitmap.
     *
     * Strategie: Komplettes Neuschreiben des Pixel-Buffers (frames.size <= bitmapWidth).
     * Links werden leere Spalten (schwarz) geschrieben wenn weniger Frames als Bitmap-Breite.
     * Kein memcpy/Shift noetig — die Frame-Liste ist bereits zeitlich sortiert.
     *
     * T56: Mapping-Range [minDb, maxDb] wird vom Aufrufer bestimmt (Auto-Kontrast
     * oder manueller Slider). minDb < maxDb ist Voraussetzung; sonst Division-by-zero Schutz.
     */
    fun renderFrames(
        frames: List<FloatArray>,
        numMelBins: Int,
        minDb: Float,
        maxDb: Float,
        lut: IntArray,
        gamma: Float = 1f
    ) {
        val bmp = bitmap ?: return
        val w = bitmapWidth
        val h = bitmapHeight
        if (frames.isEmpty()) return

        // T56b: Gamma+Farb-LUT bei Aenderung aktualisieren (lazy, max. 256 Iterationen).
        updateGammaLutIfNeeded(gamma, lut)

        val numFrames = frames.size
        val emptyColumns = w - numFrames
        // Division-by-zero Schutz: minimal 1 dB Spanne
        val range = (maxDb - minDb).coerceAtLeast(1f)
        val invRange = 1f / range
        val black = (0xFF shl 24)

        // Leere Spalten links (schwarz)
        for (x in 0 until emptyColumns) {
            for (y in 0 until h) {
                pixelBuffer[y * w + x] = black
            }
        }

        // Frame-Spalten rechts
        for (frameIdx in 0 until numFrames) {
            val frame = frames[frameIdx]
            val x = emptyColumns + frameIdx

            for (bin in 0 until minOf(h, frame.size)) {
                // Y-Achse invertieren: bin 0 (fMin) → unten (y = h-1)
                val y = h - 1 - bin

                // dB → normalisiert [0, 1] ueber [minDb, maxDb]
                val dbValue = frame[bin]
                val normalized = ((dbValue - minDb) * invRange).coerceIn(0f, 1f)

                // T56b: Gamma+Farb-LUT-Lookup (kombiniert Gamma-Kompression + Farbpalette)
                val normalizedIdx = (normalized * 255f).toInt().coerceIn(0, 255)
                pixelBuffer[y * w + x] = gammaColorLut[normalizedIdx]
            }
        }

        // Batch-Schreibzugriff auf Bitmap
        bmp.setPixels(pixelBuffer, 0, w, 0, 0, w, h)
    }
}

/**
 * Zeichnet ein Bitmap skaliert auf die volle Canvas-Groesse.
 */
private fun DrawScope.drawScaledBitmap(
    bitmap: Bitmap,
    targetSize: androidx.compose.ui.geometry.Size
) {
    val imageBitmap = bitmap.asImageBitmap()

    drawImage(
        image = imageBitmap,
        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
        srcSize = androidx.compose.ui.unit.IntSize(bitmap.width, bitmap.height),
        dstOffset = Offset.Zero.let {
            androidx.compose.ui.unit.IntOffset(it.x.toInt(), it.y.toInt())
        },
        dstSize = androidx.compose.ui.unit.IntSize(
            targetSize.width.toInt(),
            targetSize.height.toInt()
        )
    )
}
