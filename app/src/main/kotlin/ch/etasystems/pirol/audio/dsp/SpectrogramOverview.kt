package ch.etasystems.pirol.audio.dsp

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Uebersichts-Sonogramm ueber eine komplette Aufnahme (T61).
 *
 * Liest 16-bit-PCM-Mono gestreamt (kein readBytes() der ganzen WAV) und verdichtet die
 * Mel-Frames per Max-Pooling auf hoechstens [maxColumns] Spalten. Max-Pooling statt Mittelwert,
 * damit kurze Rufe in der Uebersicht sichtbar bleiben.
 *
 * Speicherbedarf: O(maxColumns * numMelBins) statt O(Aufnahmelaenge).
 */
object SpectrogramOverview {

    /** Groesse des WAV-Headers (PCM, 16-bit, Mono — wie von WavWriter geschrieben) */
    const val WAV_HEADER_SIZE = 44

    private const val READ_BUFFER_BYTES = 64 * 1024

    /**
     * Berechnet die Uebersicht einer WAV-Datei.
     * Samplerate kommt aus dem Header (Offset 24), Fallback [fallbackSampleRate].
     * Datenlaenge aus der Dateigroesse (robust gegen falsche dataSize im Header).
     */
    fun computeFromWav(
        wavFile: File,
        config: SpectrogramConfig,
        maxColumns: Int,
        fallbackSampleRate: Int = 48000
    ): List<FloatArray> {
        val sampleRate = readSampleRate(wavFile) ?: fallbackSampleRate
        val totalSamples = (wavFile.length() - WAV_HEADER_SIZE).coerceAtLeast(0L) / 2
        return wavFile.inputStream().buffered(READ_BUFFER_BYTES).use { input ->
            var skipped = 0L
            while (skipped < WAV_HEADER_SIZE) {
                val n = input.skip(WAV_HEADER_SIZE - skipped)
                if (n <= 0) return emptyList()
                skipped += n
            }
            compute(input, totalSamples, sampleRate, config, maxColumns)
        }
    }

    /**
     * Berechnet die Uebersicht aus einem PCM-Stream (16-bit Little-Endian, Mono, ohne Header).
     *
     * @param totalSamples Erwartete Sample-Anzahl (bestimmt die Spalten-Zuordnung)
     */
    fun compute(
        input: InputStream,
        totalSamples: Long,
        sampleRate: Int,
        config: SpectrogramConfig,
        maxColumns: Int
    ): List<FloatArray> {
        if (totalSamples < config.fftSize || maxColumns <= 0) return emptyList()
        // Lange Aufnahmen: Hop bis auf fftSize vergroessern (Fenster liegen dann lueckenlos
        // aneinander, kein Ruf faellt durch) — spart bis zu 4x Rechenzeit, da ohnehin viele
        // Frames pro Spalte zusammengefasst werden. Kurze Aufnahmen bleiben 1:1 (Original-Hop).
        val effectiveConfig = overviewConfig(config, totalSamples, maxColumns)
        val expectedFrames = (totalSamples - effectiveConfig.fftSize) / effectiveConfig.hopSize + 1
        val columns = minOf(expectedFrames, maxColumns.toLong()).toInt()

        val numMelBins = config.numMelBins
        val pooled = Array(columns) { FloatArray(numMelBins) { Float.NEGATIVE_INFINITY } }
        val mel = MelSpectrogram(sampleRate, effectiveConfig)

        val bytes = ByteArray(READ_BUFFER_BYTES)
        var pending = -1          // uebriggebliebenes Low-Byte bei ungerader Leselaenge
        var frameIndex = 0L
        var lastColumn = -1

        while (frameIndex < expectedFrames) {
            val read = input.read(bytes)
            if (read <= 0) break

            // Bytes → Shorts (Little Endian), ungerade Restbytes ueber Leseaufrufe mitnehmen
            var start = 0
            val first: Short? = if (pending >= 0 && read > 0) {
                start = 1
                ((bytes[0].toInt() shl 8) or pending).toShort()
            } else null
            val usable = read - start
            val shortCount = usable / 2 + (if (first != null) 1 else 0)
            val samples = ShortArray(shortCount)
            var s = 0
            if (first != null) samples[s++] = first
            var i = start
            while (i + 1 < read) {
                samples[s++] = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
                i += 2
            }
            pending = if (i < read) bytes[i].toInt() and 0xFF else -1

            for (frame in mel.process(samples)) {
                if (frameIndex >= expectedFrames) break
                // Gleichmaessige Zuordnung Frame → Spalte
                val column = (frameIndex * columns / expectedFrames).toInt()
                val target = pooled[column]
                for (b in 0 until minOf(numMelBins, frame.size)) {
                    if (frame[b] > target[b]) target[b] = frame[b]
                }
                lastColumn = column
                frameIndex++
            }
        }

        // Bei vorzeitigem Stream-Ende: nur befuellte Spalten zurueckgeben
        return if (lastColumn + 1 < columns) pooled.take(lastColumn + 1) else pooled.toList()
    }

    /**
     * Hop fuer die Uebersicht: so gross, dass etwa [maxColumns] Frames entstehen,
     * aber nie kleiner als der konfigurierte Hop und nie groesser als fftSize (keine Luecken).
     */
    internal fun overviewConfig(config: SpectrogramConfig, totalSamples: Long, maxColumns: Int): SpectrogramConfig {
        val framesAtConfigHop = (totalSamples - config.fftSize) / config.hopSize + 1
        if (framesAtConfigHop <= maxColumns) return config
        val idealHop = ((totalSamples - config.fftSize) / maxColumns).toInt()
        val hop = idealHop.coerceIn(config.hopSize, config.fftSize)
        return config.copy(hopSize = hop)
    }

    /**
     * Liest einen Zeitbereich [startSec, endSec) als Samples (Random Access, kein Komplett-Lesen).
     */
    fun readWavRange(wavFile: File, startSec: Float, endSec: Float, fallbackSampleRate: Int = 48000): ShortArray {
        val sampleRate = readSampleRate(wavFile) ?: fallbackSampleRate
        val totalSamples = (wavFile.length() - WAV_HEADER_SIZE).coerceAtLeast(0L) / 2
        val startSample = (startSec * sampleRate).toLong().coerceIn(0L, totalSamples)
        val endSample = (endSec * sampleRate).toLong().coerceIn(startSample, totalSamples)
        val count = (endSample - startSample).toInt()
        if (count <= 0) return ShortArray(0)

        val raw = ByteArray(count * 2)
        RandomAccessFile(wavFile, "r").use { raf ->
            raf.seek(WAV_HEADER_SIZE + startSample * 2)
            raf.readFully(raw)
        }
        val samples = ShortArray(count)
        java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return samples
    }

    /** Samplerate aus dem WAV-Header (Offset 24), null wenn Header ungueltig */
    fun readSampleRate(wavFile: File): Int? {
        if (wavFile.length() < WAV_HEADER_SIZE) return null
        val header = ByteArray(WAV_HEADER_SIZE)
        RandomAccessFile(wavFile, "r").use { it.readFully(header) }
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        val rate = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(24)
        return rate.takeIf { it in 1000..1_000_000 }
    }
}
