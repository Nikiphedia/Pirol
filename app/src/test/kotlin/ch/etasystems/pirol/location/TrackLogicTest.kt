package ch.etasystems.pirol.location

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TrackLogicTest {
    private val start = 1_700_000_000_000L
    // ca. 0.0001 Grad Breite = 11.1 m
    private fun fix(dtMs: Long, dLat: Double = 0.0, acc: Float? = 5f, alt: Double? = null) =
        LocationData(47.0 + dLat, 8.0, alt, acc, start + dtMs)

    @Test fun distance_isPlausible() {
        val d = TrackLogic.distanceM(47.0, 8.0, 47.0001, 8.0)
        assertEquals(11.1, d, 0.2)
    }

    @Test fun firstPoint_accepted_whenAccurate() {
        assertTrue(TrackLogic.accept(null, fix(0), start))
    }

    @Test fun inaccurateOrUnknownAccuracy_rejected() {
        assertFalse(TrackLogic.accept(null, fix(0, acc = 51f), start))
        assertFalse(TrackLogic.accept(null, fix(0, acc = null), start))
        assertTrue(TrackLogic.accept(null, fix(0, acc = 50f), start))
    }

    @Test fun staleFixFromEarlierRecording_rejected() {
        assertFalse(TrackLogic.accept(null, fix(-TrackLogic.MAX_START_AGE_MS - 1), start))
        assertTrue(TrackLogic.accept(null, fix(-60_000), start))
    }

    @Test fun smallMovement_shortTime_rejected() {
        val last = fix(0)
        assertFalse(TrackLogic.accept(last, fix(10_000, dLat = 0.00005), start))  // ~5.5 m
    }

    @Test fun movement10m_accepted() {
        val last = fix(0)
        assertTrue(TrackLogic.accept(last, fix(5_000, dLat = 0.0001), start))  // ~11 m
    }

    @Test fun standingStill_60s_accepted() {
        val last = fix(0)
        assertFalse(TrackLogic.accept(last, fix(59_999), start))
        assertTrue(TrackLogic.accept(last, fix(60_000), start))
    }

    @Test fun olderOrSameTimestamp_rejected() {
        val last = fix(10_000)
        assertFalse(TrackLogic.accept(last, fix(10_000, dLat = 0.001), start))
        assertFalse(TrackLogic.accept(last, fix(5_000, dLat = 0.001), start))
    }

    @Test fun thin_sequence() {
        val fixes = listOf(
            fix(0),
            fix(10_000, dLat = 0.00002),       // 2 m → weg
            fix(20_000, dLat = 0.00015),       // 16 m → ja
            fix(30_000, dLat = 0.00015, acc = 80f),  // ungenau → weg
            fix(85_000, dLat = 0.00016),       // 65 s → ja
        )
        val out = TrackLogic.thin(fixes, start)
        assertEquals(listOf(0L, 20_000L, 85_000L), out.map { it.timestampMs - start })
    }

    @Test fun toPoint_hasOffsetTime_andRoundTrips() {
        val p = TrackLogic.toPoint(fix(0, alt = 512.3), ZoneId.of("Europe/Zurich"), detectionFix = false)
        assertTrue(p.time, p.time.endsWith("+01:00"))  // November: MEZ
        assertEquals(start, TrackLogic.epochMs(p))
        val line = TrackLogic.encodeLine(p)
        assertTrue(line.endsWith("\n"))
        assertFalse(line.contains("detectionFix"))  // Default wird nicht geschrieben
        assertEquals(listOf(p), TrackLogic.parseLines(line))
    }

    @Test fun parse_brokenLastLine_isSkipped() {
        val zone = ZoneId.of("Europe/Zurich")
        val a = TrackLogic.encodeLine(TrackLogic.toPoint(fix(0), zone, false))
        val b = TrackLogic.encodeLine(TrackLogic.toPoint(fix(60_000), zone, true))
        val text = a + b + "{\"time\":\"2026-11-22T10:00:00+01:00\",\"lat\":47.1"  // Kill mitten in der Zeile
        val pts = TrackLogic.parseLines(text)
        assertEquals(2, pts.size)
        assertTrue(pts[1].detectionFix)
    }

    @Test fun parse_garbageAndInvalidCoords_skipped() {
        val text = "\n\nkein json\n{\"time\":\"x\",\"lat\":95.0,\"lon\":8.0}\n{\"time\":\"2026-11-22T10:00:00+01:00\",\"lat\":47.0,\"lon\":8.0,\"neu\":1}\n"
        val pts = TrackLogic.parseLines(text)
        assertEquals(1, pts.size)
        assertNull(TrackLogic.epochMs(pts[0].copy(time = "x")))
    }

    @Test fun sessionTrack_sparse_onlyIfAllDetectionFixes() {
        val z = ZoneId.of("UTC")
        val d = TrackLogic.toPoint(fix(0), z, true)
        val c = TrackLogic.toPoint(fix(0), z, false)
        assertTrue(SessionTrack("s", listOf(d, d)).sparse)
        assertFalse(SessionTrack("s", listOf(d, c)).sparse)
        assertFalse(SessionTrack("s", emptyList()).sparse)
    }

    @Test fun recorder_appendsThinnedPoints_andClearsOnStop() = runBlocking {
        val flow = MutableStateFlow<LocationData?>(fix(-3_600_000))  // Rest einer alten Aufnahme
        val lines = mutableListOf<String>()
        val rec = TrackRecorder(
            locations = flow,
            appendLine = { synchronized(lines) { lines += it } },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock = { start },
            zone = { ZoneId.of("UTC") }
        )
        rec.start("s1", detectionOnly = false)
        delay(50)
        flow.value = fix(0)
        delay(50)
        flow.value = fix(5_000, dLat = 0.00001)  // zu nah
        delay(50)
        flow.value = fix(10_000, dLat = 0.0002)
        delay(100)
        assertEquals(2, rec.liveTrack.value?.points?.size)
        rec.stop()
        assertNull(rec.liveTrack.value)
        assertEquals(2, lines.size)
        flow.value = fix(200_000, dLat = 0.01)
        delay(50)
        assertEquals(2, lines.size)  // nach stop nichts mehr
    }
}
