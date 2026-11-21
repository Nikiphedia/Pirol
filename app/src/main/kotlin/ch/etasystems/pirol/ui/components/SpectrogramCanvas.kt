package ch.etasystems.pirol.ui.components

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 * @param fitAllFrames true = alle Frames im State auf die Canvas-Breite skalieren (Uebersicht/Ausschnitt,
 *   T61). false = Rolling-Modus: 1 Frame = 1 Pixel, nur die neuesten Canvas-Breite Frames.
 * @param freqMinHz T107: untere Anzeige-Frequenz (Zuschnitt der Mel-Bins)
 * @param freqMaxHz T107: obere Anzeige-Frequenz
 * @param windowFrames T107: feste Anzahl Frames (Zeitfenster) statt 1 Frame pro Pixel; null = Rolling-Modus
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
    fitAllFrames: Boolean = false,
    freqMinHz: Float = 0f,
    freqMaxHz: Float = 24000f,
    windowFrames: Int? = null,
    modifier: Modifier = Modifier
) {
    // T107: Frequenz-Zuschnitt — nur die sichtbaren Mel-Bins werden gerendert
    val binRange = remember(config, freqMinHz, freqMaxHz) {
        visibleBinRange(
            config.numMelBins, config.fMin,
            if (config.fMax <= 0f) 24000f else config.fMax, freqMinHz, freqMaxHz
        )
    }
    val numMelBins = binRange.last - binRange.first + 1
    val binFirst = binRange.first
    val fallbackMinDb = config.minDb
    val lut = remember(palette) { SpectrogramPalette.lutFor(palette) }

    // Bitmap-Renderer: verwaltet Off-Screen-Bitmap und Ring-Buffer-Logik
    val renderer = remember { BitmapRenderer() }

    // T160: Zeitfenster-Modus (Live) schreibt nur neue Spalten in eine Ring-Bitmap
    val ringMode = windowFrames != null && !fitAllFrames
    val ringRenderer = remember { RingBitmapRenderer() }
    val stats = remember { RenderStats() }

    // T160: Zeichen-Takt. Hoechstens ~25 fps, nur bei neuen Frames und nur solange die Ansicht
    // sichtbar (STARTED) ist. Der State wird nur im draw-Lambda gelesen → keine Recomposition.
    var tick by remember { mutableIntStateOf(0) }
    if (ringMode) {
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(spectrogramState, lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var seen = -1
                while (isActive) {
                    val v = spectrogramState.frameVersion
                    if (v != seen) {
                        seen = v
                        tick++
                    }
                    delay(SPECTROGRAM_RENDER_INTERVAL_MS)
                }
            }
        }
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (!fitAllFrames && windowFrames == null) renderer.onSizeChanged(size, numMelBins)
                }
        ) {
            if (ringMode) {
                // Draw-Dependency auf den Takt (nicht auf frameVersion → kein Zeichnen pro Chunk)
                @Suppress("UNUSED_VARIABLE") val t = tick
                val startNs = System.nanoTime()
                val (minDbUsed, maxDbRaw) = when {
                    manualRangeOverride != null -> manualRangeOverride
                    autoContrast -> spectrogramState.dynamicRangeMapper?.currentRange()
                        ?: (fallbackMinDb to 0f)
                    else -> manualMinDb to manualMaxDb
                }
                val (minDbFinal, maxDbUsed) = effectiveDbRange(minDbUsed, maxDbRaw, ceilingDb)
                val cols = ringRenderer.render(
                    spectrogramState, windowFrames!!, numMelBins, binFirst, minDbFinal, maxDbUsed, lut, gamma
                )
                ringRenderer.draw(this, this.size)
                val line = stats.record(
                    SystemClock.elapsedRealtime(), (System.nanoTime() - startNs) / 1e6f, cols
                )
                if (line != null) Log.i("SpectrogramRender", line)
                return@Canvas
            }

            // version lesen: Draw-Dependency (nur Neuzeichnen, keine Recomposition)
            @Suppress("UNUSED_VARIABLE") val version = spectrogramState.frameVersion

            // Sichtbare Frames holen: Einpass-Modus = alle Frames, Bitmap-Breite = Frame-Anzahl
            // (wird beim Zeichnen auf die Canvas skaliert); Rolling-Modus = max. Bitmap-Breite.
            val frames = if (fitAllFrames) {
                val all = spectrogramState.getVisibleFrames(spectrogramState.maxFrames)
                if (all.isEmpty()) return@Canvas
                renderer.onSizeChanged(IntSize(all.size, 1), numMelBins)
                all
            } else {
                val bitmapWidth = renderer.bitmapWidth
                if (bitmapWidth <= 0) return@Canvas
                spectrogramState.getVisibleFrames(bitmapWidth)
            }

            // T56: Range bestimmen. Prioritaet: override > autoContrast+mapper > manual > fallback.
            val (minDbUsed, maxDbRaw) = when {
                manualRangeOverride != null -> manualRangeOverride
                autoContrast -> spectrogramState.dynamicRangeMapper?.currentRange()
                    ?: (fallbackMinDb to 0f)
                else -> manualMinDb to manualMaxDb
            }

            // T56b-Ceiling: Obergrenze clippen (0f = kein Effekt; negativ = Lautes abschneiden).
            // T107: Ceiling darf den Bereich nicht unter die Mindestspanne zusammenschieben.
            val (minDbFinal, maxDbUsed) = effectiveDbRange(minDbUsed, maxDbRaw, ceilingDb)

            // Bitmap aktualisieren (T56b: gamma + gecapptes maxDb durchreichen)
            renderer.renderFrames(frames, binFirst, minDbFinal, maxDbUsed, lut, gamma)

            // Bitmap auf Canvas zeichnen (skaliert auf volle Canvas-Groesse)
            renderer.bitmap?.let { bmp ->
                drawScaledBitmap(bmp, this.size)
            }
        }
    }
}

/**
 * T160: Ring-Bitmap fuer das Live-Sonogramm (Zeitfenster-Modus).
 *
 * Breite = Anzahl Frames im Fenster, 1 Frame = 1 Spalte. Neue Frames werden nur in ihre Spalten
 * geschrieben; beim Zeichnen werden zwei Teilbereiche (alt|neu) ausgegeben. Vollstaendiger
 * Neuaufbau nur bei Groessen-, Palette-, Gamma-, Frequenz-/dB-Bereichs- oder Strukturwechsel.
 */
internal class RingBitmapRenderer {
    private var bitmap: Bitmap? = null
    private var image: androidx.compose.ui.graphics.ImageBitmap? = null
    private var ring: ColumnRing? = null
    private var width = 0
    private var height = 0
    private var pixels = IntArray(0)

    private var lastAppended = 0L
    private var lastStructure = -1
    private var lastMin = Float.NaN
    private var lastMax = Float.NaN
    private var lastGamma = Float.NaN
    private var lastLut: IntArray? = null
    private var lastBinFirst = -1
    private var needsFull = true
    private val gammaColorLut = IntArray(256)

    private fun updateLut(gamma: Float, colorLut: IntArray) {
        if (gamma == 1f) {
            colorLut.copyInto(gammaColorLut)
        } else {
            for (i in 0..255) {
                val compressed = (i / 255f).pow(gamma)
                gammaColorLut[i] = colorLut[(compressed * 255f).toInt().coerceIn(0, 255)]
            }
        }
    }

    /** Rendert neue Frames ins Bitmap. Rueckgabe: Anzahl geschriebener Spalten (Neuaufbau = Fensterbreite). */
    fun render(
        state: SpectrogramState,
        windowFrames: Int,
        numBins: Int,
        binFirst: Int,
        minDb: Float,
        maxDb: Float,
        colorLut: IntArray,
        gamma: Float
    ): Int {
        if (windowFrames <= 0 || numBins <= 0) return 0
        if (windowFrames != width || numBins != height || bitmap == null) {
            bitmap?.recycle()
            val bmp = Bitmap.createBitmap(windowFrames, numBins, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            image = bmp.asImageBitmap()
            ring = ColumnRing(windowFrames)
            width = windowFrames
            height = numBins
            pixels = IntArray(windowFrames * numBins)
            needsFull = true
        }
        val lutChanged = gamma != lastGamma || colorLut !== lastLut
        if (lutChanged) {
            updateLut(gamma, colorLut)
            lastGamma = gamma
            lastLut = colorLut
        }
        val full = needsFull || lutChanged || binFirst != lastBinFirst ||
            rangeNeedsRebuild(lastMin, lastMax, minDb, maxDb)

        val snap = state.snapshotForRender(lastAppended, lastStructure, width, full)
        val invRange = 1f / (maxDb - minDb).coerceAtLeast(1f)
        val ring = ring ?: return 0
        val bmp = bitmap ?: return 0
        var written = 0
        if (snap.full) {
            // Stille-Farbe (T144) links, Frames rechtsbuendig, aeltester Frame bei head = 0
            pixels.fill(gammaColorLut[0])
            ring.reset()
            val k = snap.frames.size
            writeColumns(snap.frames, 0, k, width - k, width, minDb, invRange, binFirst)
            bmp.setPixels(pixels, 0, width, 0, 0, width, height)
            lastMin = minDb
            lastMax = maxDb
            lastBinFirst = binFirst
            needsFull = false
            written = width
        } else if (snap.frames.isNotEmpty()) {
            var idx = 0
            for (run in ring.write(snap.frames.size)) {
                val len = run[1]
                writeColumns(snap.frames, idx, len, 0, len, minDb, invRange, binFirst)
                bmp.setPixels(pixels, 0, len, run[0], 0, len, height)
                idx += len
            }
            written = snap.frames.size
        }
        lastAppended = snap.appendedTotal
        lastStructure = snap.structureVersion
        return written
    }

    /** Schreibt [count] Frames ab [from] in den Pixelpuffer (Zeilen-Stride [stride]) ab Spalte [col0]. */
    private fun writeColumns(
        frames: List<FloatArray>, from: Int, count: Int, col0: Int, stride: Int,
        minDb: Float, invRange: Float, binFirst: Int
    ) {
        for (i in 0 until count) {
            val frame = frames[from + i]
            val x = col0 + i
            for (bin in 0 until height) {
                val srcBin = binFirst + bin
                if (srcBin >= frame.size) break
                val normalized = ((frame[srcBin] - minDb) * invRange).coerceIn(0f, 1f)
                val idx = (normalized * 255f).toInt().coerceIn(0, 255)
                // Y-Achse invertieren: bin 0 (fMin) → unten
                pixels[(height - 1 - bin) * stride + x] = gammaColorLut[idx]
            }
        }
    }

    /** Zeichnet das Ring-Bitmap als zwei Teilbereiche (alt|neu), skaliert auf die Canvas. */
    fun draw(scope: DrawScope, target: androidx.compose.ui.geometry.Size) {
        val img = image ?: return
        val ring = ring ?: return
        val dstW = target.width.toInt()
        val dstH = target.height.toInt()
        var srcDone = 0
        for (seg in ring.drawSegments()) {
            val x0 = (dstW.toLong() * srcDone / width).toInt()
            val x1 = (dstW.toLong() * (srcDone + seg[1]) / width).toInt()
            scope.drawImage(
                image = img,
                srcOffset = androidx.compose.ui.unit.IntOffset(seg[0], 0),
                srcSize = IntSize(seg[1], height),
                dstOffset = androidx.compose.ui.unit.IntOffset(x0, 0),
                dstSize = IntSize(x1 - x0, dstH)
            )
            srcDone += seg[1]
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
        binFirst: Int,
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
        // T144: Stille-Farbe = erster LUT-Eintrag (schwarz bei dunklen Paletten, weiss bei PAPER)
        val black = gammaColorLut[0]

        // Leere Spalten links (Stille-Farbe)
        for (x in 0 until emptyColumns) {
            for (y in 0 until h) {
                pixelBuffer[y * w + x] = black
            }
        }

        // Frame-Spalten rechts
        for (frameIdx in 0 until numFrames) {
            val frame = frames[frameIdx]
            val x = emptyColumns + frameIdx

            for (bin in 0 until h) {
                // T107: Quell-Bin = sichtbarer Bin + Frequenz-Offset
                val srcBin = binFirst + bin
                if (srcBin >= frame.size) break
                // Y-Achse invertieren: bin 0 (fMin) → unten (y = h-1)
                val y = h - 1 - bin

                // dB → normalisiert [0, 1] ueber [minDb, maxDb]
                val dbValue = frame[srcBin]
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
