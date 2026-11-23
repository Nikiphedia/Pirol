package ch.etasystems.pirol.audio.dsp

import kotlin.math.log10
import kotlin.math.max

/**
 * Zustandsbehaftete Mel-Spektrogramm-Pipeline.
 *
 * Nimmt rohe Audio-Samples (ShortArray) entgegen und produziert
 * Mel-Frames in dBFS (T199). Verwaltet einen internen Overlap-Buffer
 * fuer nahtlose Verarbeitung ueber Chunk-Grenzen hinweg.
 *
 * Ablauf pro process()-Aufruf:
 * 1. Samples an Overlap-Buffer anhaengen
 * 2. Solange Buffer >= fftSize: Fenster extrahieren, um hopSize weiterschieben
 * 3. Pro Fenster: Hann-Window -> FFT -> Power-Spektrum -> Mel-Filterbank -> dBFS
 *
 * T199: Normierung wie [SpectrumAnalyzer]: Leistung * (2/Summe(w))^2, ein Vollaussteuerungs-Sinus
 * ergibt im Mel-Band ca. 0 dBFS (-3..+2 dB je nach Lage zur Bandmitte). Die Inferenz nutzt diese
 * Klasse nicht (nur Anzeige und Sonogramm-Export).
 * 4. Frames als List<FloatArray> zurueckgeben
 *
 * Keine Allokationen im Hot-Path — alle Buffer werden wiederverwendet.
 *
 * @param sampleRate Audio-Samplerate in Hz
 * @param fftSize FFT-Fenstergroesse (muss Zweierpotenz sein)
 * @param hopSize Schrittweite zwischen Fenstern in Samples
 * @param numMelBins Anzahl Mel-Filterbank-Ausgaenge
 * @param fMin Untere Grenzfrequenz in Hz
 * @param fMax Obere Grenzfrequenz in Hz (-1 = Nyquist)
 * @param floorDb Untergrenze (gegen log(0)) in dBFS
 */
class MelSpectrogram(
    sampleRate: Int,
    fftSize: Int = 2048,
    hopSize: Int = 512,
    numMelBins: Int = 128,
    fMin: Float = 0f,
    fMax: Float = -1f,
    private val floorDb: Float = MEL_FLOOR_DBFS
) {
    private val actualFftSize = fftSize
    private val actualHopSize = hopSize
    private val actualNumMelBins = numMelBins
    private val actualFMax: Float = if (fMax < 0f) sampleRate / 2f else fMax
    private val actualFMin: Float = fMin

    /** Tatsaechlicher Frequenzbereich */
    val frequencyRange: Pair<Float, Float> get() = Pair(actualFMin, actualFMax)

    // Interne Komponenten
    private val hannWindow = WindowFunction.hann(actualFftSize)
    private val melFilterbank = MelFilterbank(
        sampleRate = sampleRate,
        fftSize = actualFftSize,
        numMelBins = actualNumMelBins,
        fMin = actualFMin,
        fMax = actualFMax
    )

    // Overlap-Buffer: dynamisch wachsend, wird nach Verarbeitung kompaktiert
    private var overlapBuffer = FloatArray(actualFftSize * 2)
    private var overlapSize = 0

    // Wiederverwendbare Arbeits-Buffer (zero-alloc im Hot-Path)
    private val fftReal = FloatArray(actualFftSize)
    private val fftImag = FloatArray(actualFftSize)
    private val powerBuf = FloatArray(actualFftSize / 2 + 1)

    // T199: dBFS-Offset 10*log10((2/Summe(w))^2), gleiche Normierung wie SpectrumAnalyzer
    private val dbfsOffset: Float =
        (20.0 * log10(2.0 / hannWindow.sumOf { it.toDouble() })).toFloat()

    /**
     * Sekundaer-Konstruktor mit SpectrogramConfig.
     */
    constructor(sampleRate: Int, config: SpectrogramConfig) : this(
        sampleRate = sampleRate,
        fftSize = config.fftSize,
        hopSize = config.hopSize,
        numMelBins = config.numMelBins,
        fMin = config.fMin,
        fMax = config.fMax
    )

    /**
     * Verarbeitet einen Block von Audio-Samples.
     * Gibt 0..N Mel-Frames zurueck, je nach verfuegbaren Daten.
     * Jeder Frame ist FloatArray(numMelBins) in dBFS.
     *
     * Thread-sicher: NEIN — nur von einem Thread aufrufen.
     */
    fun process(samples: ShortArray): List<FloatArray> {
        // Samples in Float konvertieren und an Overlap-Buffer anhaengen
        appendSamples(samples)

        // Frames extrahieren: Lese-Offset statt Buffer-Shift pro Frame.
        // Vorher wurde nach jedem Frame der gesamte Rest-Buffer per arraycopy verschoben
        // (O(n^2/hop) bei grossen Bloecken, z.B. Preroll oder ganze WAV im Analyse-Tab).
        // Jetzt wird nur einmal am Ende kompaktiert.
        val frameCount = if (overlapSize >= actualFftSize) {
            (overlapSize - actualFftSize) / actualHopSize + 1
        } else 0
        val frames = ArrayList<FloatArray>(frameCount)
        var readOffset = 0
        while (overlapSize - readOffset >= actualFftSize) {
            frames.add(processOneFrame(readOffset))
            readOffset += actualHopSize
        }
        // Rest an den Buffer-Anfang verschieben (einmalig)
        if (readOffset > 0) {
            val remaining = (overlapSize - readOffset).coerceAtLeast(0)
            if (remaining > 0) {
                System.arraycopy(overlapBuffer, readOffset, overlapBuffer, 0, remaining)
            }
            overlapSize = remaining
        }
        return frames
    }

    /**
     * Internen Overlap-Buffer leeren (bei Aufnahme-Neustart).
     */
    fun reset() {
        overlapSize = 0
    }

    /**
     * Haengt Short-Samples an den Overlap-Buffer an (normalisiert auf [-1, 1]).
     */
    private fun appendSamples(samples: ShortArray) {
        val needed = overlapSize + samples.size
        if (needed > overlapBuffer.size) {
            // Buffer vergroessern (naechste Zweierpotenz oder needed * 2)
            val newSize = maxOf(overlapBuffer.size * 2, needed)
            val newBuf = FloatArray(newSize)
            System.arraycopy(overlapBuffer, 0, newBuf, 0, overlapSize)
            overlapBuffer = newBuf
        }
        // Short → Float [-1.0, 1.0]
        for (i in samples.indices) {
            overlapBuffer[overlapSize + i] = samples[i] / 32768f
        }
        overlapSize += samples.size
    }

    /**
     * Verarbeitet ein einzelnes FFT-Fenster ab [offset] im Overlap-Buffer.
     * Gibt einen neuen FloatArray(numMelBins) in dBFS zurueck.
     */
    private fun processOneFrame(offset: Int): FloatArray {
        // 1. Fenster in Arbeits-Buffer kopieren und Hann-Window anwenden
        System.arraycopy(overlapBuffer, offset, fftReal, 0, actualFftSize)
        WindowFunction.applyWindow(fftReal, hannWindow)

        // 2. Imaginaerteil nullen
        fftImag.fill(0f)

        // 3. FFT
        FFT.fft(fftReal, fftImag)

        // 4. Power-Spektrum (in vorhandenen Buffer)
        FFT.powerSpectrumInto(fftReal, fftImag, powerBuf)

        // 5. Mel-Filterbank
        val melEnergies = melFilterbank.apply(powerBuf)

        // 6. dBFS + Boden → neuer Array (Consumer braucht eigene Kopie)
        val frame = FloatArray(actualNumMelBins)
        for (i in frame.indices) {
            // 10 * log10(energy) + Normierung, mit Minimum gegen log(0); oben kein Clipping
            val db = 10f * log10(max(melEnergies[i], 1e-30f)) + dbfsOffset
            frame[i] = db.coerceAtLeast(floorDb)
        }
        return frame
    }
}
