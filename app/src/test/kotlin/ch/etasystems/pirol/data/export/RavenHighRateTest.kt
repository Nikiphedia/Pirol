package ch.etasystems.pirol.data.export

import ch.etasystems.pirol.ml.AudioRingBuffer
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ui.analysis.ImportRateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** T294: Raven-Frequenzband, Ringpuffer-Groesse, Import-Info bei hohen Raten; 48 kHz unveraendert. */
class RavenHighRateTest {

    private val det = DetectionResult(
        id = "d1", scientificName = "Pipistrellus pipistrellus", commonName = "Zwergfledermaus", confidence = 0.8f,
        timestampMs = 0L, chunkStartSec = 3f, chunkEndSec = 6f, sampleRate = 48000
    )

    private fun export(band: Pair<Int, Int>?, passParam: Boolean): String {
        val f = File.createTempFile("raven", ".txt")
        try {
            if (passParam) RavenExporter.export(f, listOf(det), "2026-11-25T21:00:00+01:00", freqBandHz = band)
            else RavenExporter.export(f, listOf(det), "2026-11-25T21:00:00+01:00")
            return f.readText()
        } finally {
            f.delete()
        }
    }

    @Test
    fun bandForRate() {
        assertNull(RavenExporter.freqBandFor(null))
        assertNull(RavenExporter.freqBandFor(48_000))
        assertNull(RavenExporter.freqBandFor(96_000))
        assertEquals(10_000 to 150_000, RavenExporter.freqBandFor(384_000))
        assertEquals(10_000 to 96_000, RavenExporter.freqBandFor(192_000))
    }

    @Test
    fun regression48kHzUnchanged() {
        // 48-kHz-Session: bitgleich zum Export ohne neuen Parameter, Band 150..12000
        val old = export(null, passParam = false)
        assertEquals(old, export(RavenExporter.freqBandFor(48_000), passParam = true))
        val row = old.lines()[1].split('\t')
        val header = old.lines()[0].split('\t')
        assertEquals("150", row[header.indexOf("Low Freq (Hz)")])
        assertEquals("12000", row[header.indexOf("High Freq (Hz)")])
    }

    @Test
    fun ultrasoundBandInRows() {
        val text = export(RavenExporter.freqBandFor(384_000), passParam = true)
        val header = text.lines()[0].split('\t')
        val row = text.lines()[1].split('\t')
        assertEquals("10000", row[header.indexOf("Low Freq (Hz)")])
        assertEquals("150000", row[header.indexOf("High Freq (Hz)")])
    }

    @Test
    fun ringCapacity() {
        assertEquals(48_000 * 30, AudioRingBuffer.capacityFor(48_000, 30))
        assertEquals(96_000 * 30, AudioRingBuffer.capacityFor(96_000, 30))
        assertEquals(384_000 * 10, AudioRingBuffer.capacityFor(384_000, 30))
        assertEquals(500_000 * 10, AudioRingBuffer.capacityFor(500_000, 30))
        assertEquals(48_000 * 5, AudioRingBuffer.capacityFor(48_000, 5))
    }

    @Test
    fun importRateInfo() {
        assertEquals(ImportRateInfo.TimeExpanded(10, "384"), ImportRateInfo.of(38_400, 10))
        assertEquals(ImportRateInfo.Ultrasound("312.5"), ImportRateInfo.of(312_500, 1))
        assertNull(ImportRateInfo.of(48_000, 1))
        assertNull(ImportRateInfo.of(96_000, 1))
    }
}
