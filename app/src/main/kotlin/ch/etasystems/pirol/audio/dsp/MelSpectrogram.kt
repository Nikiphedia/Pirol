package ch.etasystems.pirol.audio.dsp

import kotlin.math.log10
import kotlin.math.max

/**
 * Zustandsbehaftete Mel-Spektrogramm-Pipeline.
 *
 * Nimmt rohe Audio-Samples (ShortArray) entgegen und produziert
 * Mel-Frames in dB-Skala. Verwaltet einen internen Overlap-Buffer
 * fuer nahtlose Verarbeitung ueber Chunk-Grenzen hinweg.
 *
 * Ablauf pro process()-Aufruf:
 * 1. Samples an Overlap-Buffer anhaengen
 * 2. Solange Buffer >= fftSize: Fenster extrahieren, um hopSize weiterschieben
 * 3. Pro Fenster: Hann-Window -> FFT -> Power-Spektrum -> Mel-Filterbank -> dB
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
 * @param refDb Referenzpegel fuer dB-Skalierung
 * @param minDb Untere Grenze (Clipping) in dB
 */
class MelSpectrogram(
    sampleRate: Int,
    fftSize: Int = 2048,
    hopSize: Int = 512,
    numMelBins: Int = 128,
    fMin: Float = 0f,
    fMax: Float = -1f,
    private val refDb: Float = 80f,
    private val minDb: Float = -80f
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

    /**
     * Sekundaer-Konstruktor mit SpectrogramConfig.
     */
    constructor(sampleRate: Int, config: SpectrogramConfig) : this(
        sampleRate = sampleRate,
        fftSize = config.fftSize,
        hopSize = config.hopSize,
        numMelBins = config.numMelBins,
        fMin = config.fMin,
        fMax = config.fMax,
        refDb = config.refDb,
        minDb = config.minDb
    )

    /**
     * Verarbeitet einen Block von Audio-Samples.
     * Gibt 0..N Mel-Frames zurueck, je nach verfuegbaren Daten.
     * Jeder Frame ist FloatArray(numMelBins) in dB-Skala.
     *
     * Thread-sicher: NEIN — nur von einem Thread aufrufen.
     */
    fun process(samples: ShortArray): List<FloatArray> {
        // Samples in Float konvertieren und an Overlap-Buffer anhaengen
        appendSamples(samples)

        // Frames extrahieren
        val frames = mutableListOf<FloatArray>()
        while (overlapSize >= actualFftSize) {
            frames.add(processOneFrame())
            // Buffer um hopSize weiterschieben
            val remaining = overlapSize - actualHopSize
            if (remaining > 0) {
                System.arraycopy(overlapBuffer, actualHopSize, overlapBuffer, 0, remaining)
            }
            overlapSize = remaining.coerceAtLeast(0)
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
     * Verarbeitet ein einzelnes FFT-Fenster vom Anfang des Overlap-Buffers.
     * Gibt einen neuen FloatArray(numMelBins) in dB-Skala zurueck.
     */
    private fun processOneFrame(): FloatArray {
        // 1. Fenster in Arbeits-Buffer kopieren und Hann-Window anwenden
        System.arraycopy(overlapBuffer, 0, fftReal, 0, actualFftSize)
        WindowFunction.applyWindow(fftReal, hannWindow)

        // 2. Imaginaerteil nullen
        fftImag.fill(0f)

        // 3. FFT
        FFT.fft(fftReal, fftImag)

        // 4. Power-Spektrum (in vorhandenen Buffer)
        FFT.powerSpectrumInto(fftReal, fftImag, powerBuf)

        // 5. Mel-Filterbank
        val melEnergies = melFilterbank.apply(powerBuf)

        // 6. dB-Skalierung + Clipping → neuer Array (Consumer braucht eigene Kopie)
        val frame = FloatArray(actualNumMelBins)
        for (i in frame.indices) {
            // 10 * log10(energy), mit Minimum gegen log(0)
            val db = 10f * log10(max(melEnergies[i], 1e-10f))
            // Relativ zu refDb, geclamped auf [minDb, 0]
            frame[i] = (db - refDb).coerceIn(minDb, 0f)
        }
        return frame
    }
}
