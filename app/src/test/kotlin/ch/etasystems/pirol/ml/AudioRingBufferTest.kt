package ch.etasystems.pirol.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T250/N9: Blockkopien im Ringpuffer (Umbruch am Pufferende, Ueberlauf, Lesen ueber das Ende). */
class AudioRingBufferTest {
    private fun seq(from: Int, n: Int) = ShortArray(n) { (from + it).toShort() }

    @Test
    fun appendAndRead_acrossWrap() {
        val ring = AudioRingBuffer(48000, 10)
        ring.append(seq(0, 7))
        ring.append(seq(7, 6))          // 13 Samples gesamt, Puffer 10 -> umgebrochen
        assertEquals(13L, ring.endIndex.value)
        assertEquals(3L, ring.oldestIndex())
        assertArrayEquals(seq(3, 10), ring.read(3, 13))
        assertArrayEquals(seq(8, 3), ring.read(8, 11))
        assertNull(ring.read(2, 5))     // zu alt
        assertNull(ring.read(10, 14))   // noch nicht da
    }

    @Test
    fun appendLargerThanCapacity_keepsTail() {
        val ring = AudioRingBuffer(48000, 8, originIndex = 100)
        ring.append(seq(0, 20))
        assertEquals(120L, ring.endIndex.value)
        assertArrayEquals(seq(12, 8), ring.read(112, 120))
    }

    @Test
    fun manySmallAppends_matchSequence() {
        val ring = AudioRingBuffer(48000, 16)
        var n = 0
        repeat(50) { ring.append(seq(n, 5)); n += 5 }
        assertArrayEquals(seq(n - 16, 16), ring.read((n - 16).toLong(), n.toLong()))
    }
}
