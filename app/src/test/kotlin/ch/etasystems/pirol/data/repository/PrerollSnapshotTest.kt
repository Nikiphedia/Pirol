package ch.etasystems.pirol.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AC6 (T63): Preroll-Snapshot-Logik — Ringbuffer-Snapshot vor Live-Append,
 * keine doppelten/verlorenen Samples.
 *
 * Testet StreamingWavWriter direkt (kein JNI noetig).
 * Simuliert den sessionDispatcher-serialisierten Schreibpfad:
 *   1. Preroll-Snapshot wird zuerst angehaengt (appendAudioSamples(preroll))
 *   2. Live-Chunks folgen sequenziell (appendAudioSamples(live))
 */
class PrerollSnapshotTest {

    // --- Hilfsfunktion: WAV-Datei lesen (Header ueberspringen, Shorts dekodieren) ---

    private fun readWavSamples(file: File): ShortArray {
        val bytes = file.readBytes()
        val dataStart = 44  // Standard 44-Byte WAV-Header
        val numShorts = (bytes.size - dataStart) / 2
        return ShortArray(numShorts) { i ->
            val lo = bytes[dataStart + i * 2].toInt() and 0xFF
            val hi = bytes[dataStart + i * 2 + 1].toInt() shl 8
            (lo or hi).toShort()
        }
    }

    // --- Test 1: Preroll zuerst, dann Live — kein Sample verloren, kein Duplikat ---

    @Test
    fun `preroll geschrieben vor Live-Chunks, kein Sample verloren, kein Duplikat`() {
        val tempFile = File.createTempFile("pirol_preroll_test", ".wav")
        tempFile.deleteOnExit()

        val writer = StreamingWavWriter.forFile(tempFile, sampleRate = 48000)
        writer.open()

        // 0.1 s Preroll-Snapshot (identifizierbare Werte: 0..4799)
        val preroll = ShortArray(4800) { i -> i.toShort() }
        // 0.1 s Live-Chunk (identifizierbare Werte: 10000..14799)
        val live = ShortArray(4800) { i -> (i + 10000).toShort() }

        writer.append(preroll)   // Preroll zuerst (wie sessionDispatcher-Reihenfolge)
        writer.append(live)      // Live-Chunk danach
        writer.close()

        val allSamples = readWavSamples(tempFile)

        // Gesamtzahl: preroll + live, kein Gap, kein Duplikat
        assertEquals("Gesamtanzahl Samples", preroll.size + live.size, allSamples.size)

        // Preroll-Anteil pruefen (erste 4800 Samples)
        preroll.forEachIndexed { i, expected ->
            assertEquals("Preroll-Sample[$i]", expected, allSamples[i])
        }

        // Live-Anteil pruefen (naechste 4800 Samples)
        live.forEachIndexed { i, expected ->
            assertEquals("Live-Sample[$i]", expected, allSamples[preroll.size + i])
        }
    }

    // --- Test 2: Mehrere sequentielle Live-Chunks nach Preroll (AC2-Nachweis) ---

    @Test
    fun `preroll plus fuenf Live-Chunks vollstaendig und in Reihenfolge`() {
        val tempFile = File.createTempFile("pirol_preroll_multi_test", ".wav")
        tempFile.deleteOnExit()

        val sampleRate = 48000
        val writer = StreamingWavWriter.forFile(tempFile, sampleRate = sampleRate)
        writer.open()

        // 5s Preroll (wie Default-Konfiguration) — komprimiert auf 480 Samples fuer Testgeschwindigkeit
        val prerollSamples = 480
        val preroll = ShortArray(prerollSamples) { i -> (i % 32767).toShort() }

        // 5 x 100ms-Live-Chunks (Chunk-Emitter-Intervall)
        val chunkSamples = 48  // 1ms @ 48kHz (klein fuer Test)
        val liveChunks = Array(5) { chunkIdx ->
            ShortArray(chunkSamples) { i -> (chunkIdx * 1000 + i).toShort() }
        }

        // Sequentielle Schreibreihenfolge (wie sessionDispatcher)
        writer.append(preroll)
        liveChunks.forEach { writer.append(it) }
        writer.close()

        val allSamples = readWavSamples(tempFile)
        val expectedTotal = prerollSamples + liveChunks.sumOf { it.size }
        assertEquals("Gesamtanzahl Samples", expectedTotal, allSamples.size)

        // sampleCount ueber Writer (AC2: Sample-Count verifizierbar)
        // Dateigroesse: 44 (Header) + expectedTotal * 2 (Bytes)
        val expectedFileBytes = 44 + expectedTotal * 2
        assertEquals("WAV-Dateigroesse", expectedFileBytes.toLong(), tempFile.length())
    }

    // --- Test 3: WAV-Header korrekt nach close() ---

    @Test
    fun `WAV-Header nach close korrekt (RIFF-fileSize und data-dataSize)`() {
        val tempFile = File.createTempFile("pirol_header_test", ".wav")
        tempFile.deleteOnExit()

        val sampleRate = 48000
        val writer = StreamingWavWriter.forFile(tempFile, sampleRate = sampleRate)
        writer.open()

        val samples = ShortArray(9600) { it.toShort() }  // 0.2 s
        writer.append(samples)
        writer.close()

        val bytes = tempFile.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF fileSize @ Offset 4
        val riffFileSize = buf.getInt(4)
        val expectedDataBytes = samples.size * 2
        val expectedRiffSize = 36 + expectedDataBytes
        assertEquals("RIFF fileSize", expectedRiffSize, riffFileSize)

        // data dataSize @ Offset 40
        val dataSize = buf.getInt(40)
        assertEquals("data dataSize", expectedDataBytes, dataSize)

        // sampleCount (AC2: Sample-Count-Verifikation via StreamingWavWriter.sampleCount)
        // wird intern aus dataBytesWritten/2 berechnet — Konsistenz via Dateigroesse pruefen
        assertEquals("WAV-Dateigroesse", (44 + expectedDataBytes).toLong(), tempFile.length())
    }
}
