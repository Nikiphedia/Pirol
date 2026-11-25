package ch.etasystems.pirol.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** T255: Zeit und Ort aus Dateinamen und Metadaten. */
class ImportTimeParserTest {
    // T297: Fledermaus-Funktionen an
    @org.junit.Before fun flagOn() { ch.etasystems.pirol.data.FeatureFlags.bats = true }
    @org.junit.After fun flagOff() { ch.etasystems.pirol.data.FeatureFlags.bats = false }


    private val zone = ZoneId.of("Europe/Zurich")
    private fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int) = ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone)

    @Test fun fileName_audioMoth() {
        assertEquals(local(2024, 5, 1, 5, 0, 0), ImportTimeParser.fromFileName("20240501_050000.WAV", zone))
    }

    @Test fun fileName_dashed_and_prefix() {
        assertEquals(local(2024, 5, 1, 5, 30, 15), ImportTimeParser.fromFileName("Rec_2024-05-01_05-30-15.flac", zone))
        assertEquals(local(2024, 5, 1, 5, 30, 15), ImportTimeParser.fromFileName("2024-05-01 05.30.15.m4a", zone))
        assertEquals(local(2024, 5, 1, 5, 30, 15), ImportTimeParser.fromFileName("Recording_20240501-053015.mp3", zone))
        assertEquals(local(2024, 5, 1, 5, 30, 15), ImportTimeParser.fromFileName("/x/y/2024-05-01T05:30:15.wav", zone))
    }

    @Test fun fileName_invalid() {
        assertNull(ImportTimeParser.fromFileName("ZOOM0001.WAV", zone))
        assertNull(ImportTimeParser.fromFileName("20241301_050000.WAV", zone))  // Monat 13
        assertNull(ImportTimeParser.fromFileName("120240501_050000.WAV", zone))  // eingebettet in laengere Zahl
    }

    @Test fun audioMoth_comment_withZone() {
        val c = "Recorded at 05:00:00 01/05/2024 (UTC+2) by AudioMoth 24E144 at medium gain while battery was 4.5V."
        assertEquals(local(2024, 5, 1, 5, 0, 0), ImportTimeParser.fromAudioMothComment(c, zone))
        val utc = "Recorded at 03:00:00 01/05/2024 (UTC) by AudioMoth"
        assertEquals(local(2024, 5, 1, 5, 0, 0), ImportTimeParser.fromAudioMothComment(utc, zone))
        val half = "Recorded at 08:30:00 01/05/2024 (UTC+5:30) by AudioMoth"
        assertEquals(local(2024, 5, 1, 5, 0, 0), ImportTimeParser.fromAudioMothComment(half, zone))
    }

    @Test fun guano_timestampAndPosition() {
        val g = "GUANO|Version: 1.0\nTimestamp: 2024-05-01T03:00:00Z\nLoc Position: 47.3769 8.5417\nMake: Wildlife Acoustics"
        val info = ImportTimeParser.parseGuano(g, zone)
        assertEquals(local(2024, 5, 1, 5, 0, 0), info.time)
        assertEquals(47.3769, info.latitude!!, 1e-9)
        assertEquals(8.5417, info.longitude!!, 1e-9)
        // ohne Offset = Ortszeit
        assertEquals(local(2024, 5, 1, 5, 0, 0), ImportTimeParser.parseGuano("Timestamp: 2024-05-01 05:00:00", zone).time)
    }

    @Test fun bext_and_info_and_media() {
        assertEquals(local(2024, 5, 1, 5, 0, 0), ImportTimeParser.fromBext("2024-05-01", "05:00:00", zone))
        assertEquals(local(2024, 5, 1, 5, 0, 0), ImportTimeParser.fromBext("2024:05:01", "05-00-00", zone))
        assertNull(ImportTimeParser.fromBext("", "", zone))
        assertNull(ImportTimeParser.fromInfoDate("2024-05-01", zone))  // nur Datum
        assertEquals(local(2024, 5, 1, 5, 0, 0), ImportTimeParser.fromInfoDate("2024-05-01 05:00:00", zone))
        assertEquals(local(2024, 5, 1, 5, 0, 0), ImportTimeParser.fromMediaDate("20240501T030000.000Z", zone))
        assertNull(ImportTimeParser.fromMediaDate("19040101T000000.000Z", zone))  // M4A-Standardwert ohne Datum
    }

    @Test fun iso6709() {
        val p = ImportTimeParser.parseIso6709("+47.3769+008.5417/")!!
        assertEquals(47.3769, p.first, 1e-9)
        assertEquals(8.5417, p.second, 1e-9)
        assertEquals(-33.5, ImportTimeParser.parseIso6709("-33.5-070.25+0500.000/")!!.first, 1e-9)
        assertNull(ImportTimeParser.parseIso6709("+00.0000+000.0000/"))
        assertNull(ImportTimeParser.parseIso6709(null))
    }

    @Test fun modified_minusDuration_and_priority() {
        val end = local(2024, 5, 1, 6, 0, 0).toInstant().toEpochMilli()
        val t = ImportTimeParser.fromModified(end, 3600.0, zone)!!
        assertEquals(local(2024, 5, 1, 5, 0, 0), t)
        val best = ImportTimeParser.best(listOf(
            TimeGuess(t, TimeSource.MODIFIED),
            TimeGuess(t.plusHours(1), TimeSource.FILENAME),
            TimeGuess(t.plusHours(2), TimeSource.AUDIOMOTH)
        ))!!
        assertEquals(TimeSource.AUDIOMOTH, best.source)
    }

    @Test fun input_roundtrip() {
        val t = local(2024, 5, 1, 5, 0, 7)
        val s = ImportTimeParser.formatInput(t, zone)
        assertEquals("2024-05-01 05:00:07", s)
        assertEquals(t, ImportTimeParser.parseInput(s, zone))
        assertEquals(local(2024, 5, 1, 5, 0, 0), ImportTimeParser.parseInput("2024-05-01 5:00", zone))
        assertNull(ImportTimeParser.parseInput("01.05.2024 05:00", zone))
        assertNull(ImportTimeParser.parseInput("2024-02-30 05:00", zone))
    }

    @Test fun offset_inSummer_isLocal() {
        val t = ImportTimeParser.fromFileName("20240701_120000.wav", zone)!!
        assertEquals("+02:00", t.offset.id)
        val w = ImportTimeParser.fromFileName("20240101_120000.wav", zone)!!
        assertEquals("+01:00", w.offset.id)
    }
}
