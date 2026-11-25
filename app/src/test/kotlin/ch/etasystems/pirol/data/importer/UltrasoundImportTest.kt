package ch.etasystems.pirol.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** T292: Ultraschall-Import (Rate behalten, Zeitdehnung, Segmentdeckel). */
class UltrasoundImportTest {
    // T297: Fledermaus-Funktionen an
    @org.junit.Before fun flagOn() { ch.etasystems.pirol.data.FeatureFlags.bats = true }
    @org.junit.After fun flagOff() { ch.etasystems.pirol.data.FeatureFlags.bats = false }


    private val zone = ZoneId.of("Europe/Zurich")

    @Test fun ultrasoundRatesAreKept() {
        for (r in listOf(96_000, 192_000, 250_000, 312_500, 384_000, 500_000)) assertEquals(r, ImportPlanner.targetRate(r))
        assertEquals(48_000, ImportPlanner.targetRate(500_001))
        // Vogelraten unveraendert
        assertEquals(48_000, ImportPlanner.targetRate(48_000))
        assertEquals(44_100, ImportPlanner.targetRate(44_100))
    }

    @Test fun timeExpansionGivesTrueRate() {
        assertEquals(441_000, ImportPlanner.effectiveRate(44_100, 10))
        assertEquals(384_000, ImportPlanner.effectiveRate(384_000, 1))
        assertEquals(48_000, ImportPlanner.effectiveRate(48_000, 0))
        // TE=10 auf 44.1 kHz bleibt (441 kHz <= 500 kHz); TE=20 faellt auf die Ersatzrate
        assertEquals(441_000, ImportPlanner.targetRate(ImportPlanner.effectiveRate(44_100, 10)))
        assertEquals(48_000, ImportPlanner.targetRate(ImportPlanner.effectiveRate(44_100, 20)))
    }

    @Test fun guanoTimeExpansion() {
        assertEquals(10, ImportTimeParser.parseGuano("GUANO|Version: 1.0\nTE: 10\nTimestamp: 2024-05-01 05:00:00", zone).timeExpansion)
        assertEquals(1, ImportTimeParser.parseGuano("Timestamp: 2024-05-01 05:00:00", zone).timeExpansion)
        assertEquals(1, ImportTimeParser.parseGuano("TE: 0", zone).timeExpansion)
        assertEquals(1, ImportTimeParser.parseGuano("TE: abc", zone).timeExpansion)
        assertEquals(1, ImportTimeParser.parseGuano("TE: 2.5", zone).timeExpansion)
        assertEquals(10, ImportTimeParser.parseGuano("TE: 10.0", zone).timeExpansion)
    }

    @Test fun segmentCapKeepsWavBelow4Gib() {
        val gib4 = 4L * 1024 * 1024 * 1024
        for (rate in listOf(48_000, 96_000, 192_000, 384_000, 500_000)) {
            for (min in listOf(10, 60, 80)) {
                val bytes = ImportPlanner.maxSegmentFrames(rate, min) * 2
                assertTrue("rate=$rate min=$min", bytes < gib4)
            }
        }
        // Vogelraten: Minuten-Einstellung unveraendert
        assertEquals(48_000L * 60 * 60, ImportPlanner.maxSegmentFrames(48_000, 60))
        // 500 kHz x 80 min waere 4.8 GB: gedeckelt
        assertEquals(ImportPlanner.MAX_SEGMENT_BYTES / 2, ImportPlanner.maxSegmentFrames(500_000, 80))
    }

    @Test fun segmenterSplitsAtCap() {
        val closed = mutableListOf<String>()
        var frames = 0
        val seg = ImportSegmenter(384_000, 1000) { n ->
            object : ImportSegmenter.FrameSink {
                override fun append(samples: ShortArray) { frames += samples.size }
                override fun close() { closed += n }
            }
        }
        seg.startSource(ZonedDateTime.of(2024, 5, 1, 5, 0, 0, 0, zone))
        seg.write(ShortArray(2500))
        val list = seg.finish()
        assertEquals(3, list.size)
        assertEquals(2500, frames)
    }

    @Test fun metadataCarriesTimeExpansion() {
        val t = ZonedDateTime.of(2024, 5, 1, 5, 0, 0, 0, zone)
        val m = ImportPlanner.metadata("id", t, 441_000, 441_000, emptyList(), null, null, 0.5f, emptyList(), timeExpansion = 10)
        assertEquals(10, m.timeExpansion)
        assertEquals(441_000, m.sampleRate)
        assertNull(ImportPlanner.metadata("id", t, 48_000, 48_000, emptyList(), null, null, 0.5f, emptyList(), timeExpansion = 1).timeExpansion)
        assertNull(ImportPlanner.metadata("id", t, 48_000, 48_000, emptyList(), null, null, 0.5f, emptyList()).timeExpansion)
    }
}
