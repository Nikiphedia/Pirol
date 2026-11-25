package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.ml.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackNavTest {
    private fun det(id: String, start: Float, manual: Boolean = false, best: Float? = null) = DetectionResult(
        id = id, scientificName = "Parus_major", commonName = "x", confidence = 0.9f,
        chunkStartSec = start, chunkEndSec = start + 3f, timestampMs = 0L, sampleRate = 48000,
        manualEntry = manual, bestStartSec = best, bestEndSec = best?.plus(3f)
    )

    private val list = PlaybackNav.sortedByTime(
        listOf(det("c", 30f), det("a", 5f), det("m", 12f, manual = true), det("b", 10f))
    )

    @Test fun sortiertNachZeitOhneManuelle() {
        assertEquals(listOf("a", "b", "c"), list.map { it.id })
    }

    @Test fun naechsteUndVorherige() {
        assertEquals("b", PlaybackNav.next(list, 5f)?.id)
        assertEquals("a", PlaybackNav.next(list, 0f)?.id)
        assertEquals("a", PlaybackNav.previous(list, 10f)?.id)
        assertEquals("b", PlaybackNav.previous(list, 20f)?.id)
    }

    @Test fun endeUndAnfang() {
        assertNull(PlaybackNav.next(list, 30f))
        assertNull(PlaybackNav.next(list, 100f))
        assertNull(PlaybackNav.previous(list, 5f))
        assertNull(PlaybackNav.previous(list, 0f))
    }

    @Test fun leereListe() {
        assertNull(PlaybackNav.next(emptyList(), 0f))
        assertNull(PlaybackNav.previous(emptyList(), 10f))
    }

    @Test fun gleicheZeitUeberspringt() {
        val l = PlaybackNav.sortedByTime(listOf(det("a", 5f), det("b", 5f), det("c", 9f)))
        assertEquals("c", PlaybackNav.next(l, 5f)?.id)
        assertNull(PlaybackNav.previous(l, 5f))
    }

    @Test fun gefilterteListeNurDarin() {
        val filtered = PlaybackNav.sortedByTime(list.filter { it.id != "b" })
        assertEquals("c", PlaybackNav.next(filtered, 5f)?.id)
    }

    @Test fun bestesFensterZaehlt() {
        val l = PlaybackNav.sortedByTime(listOf(det("a", 5f, best = 20f), det("b", 10f)))
        assertEquals(listOf("b", "a"), l.map { it.id })
    }

    @Test fun tapZuSekunde() {
        // ganze Session 100 s auf 200 px mit 20 px linkem Rand
        assertEquals(50f, PlaybackNav.tapToSec(120f, 20f, 200f, 0f, 100f, 100f)!!, 0.001f)
        // Zoom 40..60 s
        assertEquals(50f, PlaybackNav.tapToSec(120f, 20f, 200f, 40f, 20f, 100f)!!, 0.001f)
        // ausserhalb begrenzt
        assertEquals(0f, PlaybackNav.tapToSec(0f, 20f, 200f, 0f, 100f, 100f)!!, 0.001f)
        assertEquals(100f, PlaybackNav.tapToSec(500f, 20f, 200f, 0f, 100f, 100f)!!, 0.001f)
        assertNull(PlaybackNav.tapToSec(10f, 0f, 0f, 0f, 10f, 10f))
    }

    @Test
    fun playheadFraction_insideOutsideNull() {
        assertEquals(0.5f, PlaybackNav.playheadFraction(15f, 10f, 10f)!!, 1e-6f)
        assertEquals(0f, PlaybackNav.playheadFraction(10f, 10f, 10f)!!, 1e-6f)
        assertEquals(null, PlaybackNav.playheadFraction(9f, 10f, 10f))
        assertEquals(null, PlaybackNav.playheadFraction(21f, 10f, 10f))
        assertEquals(null, PlaybackNav.playheadFraction(null, 10f, 10f))
        assertEquals(null, PlaybackNav.playheadFraction(5f, 0f, 0f))
    }

    @Test
    fun `T301 Memo-Zeiger auf Session-Achse`() {
        assertEquals(17.5f, PlaybackNav.memoPlayheadSec(2.5f, 15f)!!, 1e-4f)
        assertNull(PlaybackNav.memoPlayheadSec(2.5f, null))
        assertNull(PlaybackNav.memoPlayheadSec(null, 15f))
    }
}
