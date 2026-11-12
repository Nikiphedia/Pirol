package ch.etasystems.pirol.audio

import ch.etasystems.pirol.data.repository.StreamingWavWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV-Header-Verifikation fuer Sprach-Memos (T69).
 *
 * VoiceMemoRecorder nutzt AudioRecord (nicht in Unit-Tests verfuegbar) und
 * StreamingWavWriter (pure Kotlin, testbar). Die Tests pruefen daher den
 * StreamingWavWriter direkt mit den gleichen Parametern wie VoiceMemoRecorder:
 * 16 kHz, Mono, 16-bit PCM.
 *
 * AC10: wavHeader_16k_mono_16bit muss gruen sein.
 */
class VoiceMemoRecorderTest {

    @Test
    fun wavHeader_16k_mono_16bit() {
        val file = File.createTempFile("memo_test", ".wav")
        try {
            val writer = StreamingWavWriter.forFile(file, sampleRate = 16_000)
            writer.open()
            // 0.1s = 1600 Samples bei 16 kHz
            writer.append(ShortArray(1600) { 0 })
            writer.close()

            val bytes = file.readBytes()
            // Mindest-Groesse pruefen
            assert(bytes.size >= 44) { "WAV-Datei zu klein: ${bytes.size} Bytes" }

            // RIFF-Signatur (Offset 0–3)
            assertEquals('R', bytes[0].toInt().toChar())
            assertEquals('I', bytes[1].toInt().toChar())
            assertEquals('F', bytes[2].toInt().toChar())
            assertEquals('F', bytes[3].toInt().toChar())

            // WAVE-Signatur (Offset 8–11)
            assertEquals('W', bytes[8].toInt().toChar())
            assertEquals('A', bytes[9].toInt().toChar())
            assertEquals('V', bytes[10].toInt().toChar())
            assertEquals('E', bytes[11].toInt().toChar())

            // numChannels = 1 (Mono) — Offset 22, Little-Endian Short
            val numChannels = ByteBuffer.wrap(bytes, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short
            assertEquals("numChannels muss 1 (Mono) sein", 1.toShort(), numChannels)

            // sampleRate = 16000 — Offset 24, Little-Endian Int
            val sampleRate = ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals("sampleRate muss 16000 Hz sein", 16_000, sampleRate)

            // bitsPerSample = 16 — Offset 34, Little-Endian Short
            val bitsPerSample = ByteBuffer.wrap(bytes, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short
            assertEquals("bitsPerSample muss 16 sein", 16.toShort(), bitsPerSample)

            // PCM-Format = 1 — Offset 20, Little-Endian Short
            val audioFormat = ByteBuffer.wrap(bytes, 20, 2).order(ByteOrder.LITTLE_ENDIAN).short
            assertEquals("audioFormat muss 1 (PCM) sein", 1.toShort(), audioFormat)

            // dataSize = 1600 * 2 = 3200 Bytes — Offset 40, Little-Endian Int
            val dataSize = ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals("dataSize muss 3200 Bytes sein (1600 Samples * 2)", 3200, dataSize)

            // RIFF-fileSize = 36 + dataSize = 3236 — Offset 4, Little-Endian Int
            val riffFileSize = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals("RIFF-fileSize muss 36 + dataSize = 3236 sein", 36 + 3200, riffFileSize)
        } finally {
            file.delete()
        }
    }

    @Test
    fun wavHeader_emptyRecording() {
        val file = File.createTempFile("memo_empty", ".wav")
        try {
            val writer = StreamingWavWriter.forFile(file, sampleRate = 16_000)
            writer.open()
            writer.close()  // 0 Samples

            val bytes = file.readBytes()
            assertEquals("WAV-Header muss 44 Bytes gross sein", 44, bytes.size)

            val dataSize = ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals("dataSize muss 0 sein bei leerer Aufnahme", 0, dataSize)
        } finally {
            file.delete()
        }
    }

    @Test
    fun byteRateCalculation() {
        // byteRate = sampleRate * numChannels * bitsPerSample / 8 = 16000 * 1 * 16 / 8 = 32000
        val file = File.createTempFile("memo_byterate", ".wav")
        try {
            val writer = StreamingWavWriter.forFile(file, sampleRate = 16_000)
            writer.open()
            writer.close()

            val bytes = file.readBytes()
            val byteRate = ByteBuffer.wrap(bytes, 28, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals("byteRate muss 32000 sein (16kHz mono 16bit)", 32_000, byteRate)

            // blockAlign = numChannels * bitsPerSample / 8 = 1 * 16 / 8 = 2
            val blockAlign = ByteBuffer.wrap(bytes, 32, 2).order(ByteOrder.LITTLE_ENDIAN).short
            assertEquals("blockAlign muss 2 sein", 2.toShort(), blockAlign)
        } finally {
            file.delete()
        }
    }
}
