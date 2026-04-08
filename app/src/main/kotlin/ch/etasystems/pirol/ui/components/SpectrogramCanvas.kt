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

/**
 * Compose Canvas fuer Rolling-Sonogramm.
 *
 * Rendering-Strategie:
 * - Internes Bitmap (ARGB_8888) als Off-Screen-Pixel-Buffer
 * - Neue Frames werden rechts angefuegt (1 Frame = 1 Pixel-Spalte im Buffer)
 * - Rolling: Ring-Buffer-Index statt Pixel-Shift → kein memcpy
 * - Canvas zeichnet nur drawImage() → minimal Compose-Overhead
 *
 * @param spectrogramState State-Holder mit Ring-Buffer der Mel-Frames
 * @param config SpectrogramConfig fuer numMelBins und minDb
 * @param palette Farbpalette (Default: MAGMA)
 * @param modifier Standard-Compose-Modifier
 */
@Composable
fun SpectrogramCanvas(
    spectrogramState: SpectrogramState,
    config: SpectrogramConfig,
    palette: SpectrogramPalette = SpectrogramPalette.MAGMA,
    modifier: Modifier = Modifier
) {
    val numMelBins = config.numMelBins
    val minDb = config.minDb
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

            // Bitmap aktualisieren
            renderer.renderFrames(frames, numMelBins, minDb, lut)

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
     */
    fun renderFrames(
        frames: List<FloatArray>,
        numMelBins: Int,
        minDb: Float,
        lut: IntArray
    ) {
        val bmp = bitmap ?: return
        val w = bitmapWidth
        val h = bitmapHeight
        if (frames.isEmpty()) return

        val numFrames = frames.size
        val emptyColumns = w - numFrames
        val invRange = 1f / (0f - minDb) // = 1 / abs(minDb)
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

                // dB → normalisiert [0, 1]
                val dbValue = frame[bin]
                val normalized = ((dbValue - minDb) * invRange).coerceIn(0f, 1f)

                // LUT-Lookup
                val lutIdx = (normalized * 255f).toInt().coerceIn(0, 255)
                pixelBuffer[y * w + x] = lut[lutIdx]
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
