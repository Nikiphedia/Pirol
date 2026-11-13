package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T97: Obergrenze der Embedding-DB — aelteste Eintraege fallen heraus. */
class EmbeddingDatabaseLimitTest {

    private fun vec(i: Int) = FloatArray(4) { if (it == i % 4) 1f else 0f }

    private fun ids(db: EmbeddingDatabase): Set<String> =
        db.search(vec(0), topN = 1000).map { it.recordingId }.toSet()

    @Test
    fun `ohne Obergrenze bleiben alle Eintraege`() {
        val db = EmbeddingDatabase()
        for (i in 0 until 50) db.add("r$i", "Parus_major", vec(i))
        assertEquals(50, db.size)
    }

    @Test
    fun `add verdraengt die aeltesten Eintraege`() {
        val db = EmbeddingDatabase()
        db.maxEntries = 10
        for (i in 0 until 25) db.add("r$i", "Parus_major", vec(i))
        assertEquals(10, db.size)
        assertEquals((15 until 25).map { "r$it" }.toSet(), ids(db))
    }

    @Test
    fun `Obergrenze senken kuerzt sofort`() {
        val db = EmbeddingDatabase()
        for (i in 0 until 20) db.add("r$i", "Parus_major", vec(i))
        db.maxEntries = 5
        assertEquals(5, db.size)
        assertEquals((15 until 20).map { "r$it" }.toSet(), ids(db))
    }

    @Test
    fun `Ersetzen eines vorhandenen Eintrags verdraengt nichts`() {
        val db = EmbeddingDatabase()
        db.maxEntries = 3
        for (i in 0 until 3) db.add("r$i", "Parus_major", vec(i))
        db.add("r1", "Turdus_merula", vec(2))
        assertEquals(3, db.size)
        assertTrue(ids(db).containsAll(listOf("r0", "r1", "r2")))
    }

    @Test
    fun `Obergrenze unter 1 wird auf 1 angehoben`() {
        val db = EmbeddingDatabase()
        db.maxEntries = 0
        db.add("a", "Parus_major", vec(0))
        db.add("b", "Parus_major", vec(1))
        assertEquals(1, db.size)
        assertEquals(setOf("b"), ids(db))
    }
}
