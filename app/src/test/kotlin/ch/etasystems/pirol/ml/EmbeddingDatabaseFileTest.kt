package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** T196: BSED-Datei robust laden, eine DB = eine Vektorlaenge. */
class EmbeddingDatabaseFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun vec(dim: Int, seed: Int) = FloatArray(dim) { ((it + seed) % 7) / 7f + 0.1f }

    private fun assertLoadFails(file: File) {
        try {
            EmbeddingDatabase().load(file)
            fail("load() haette eine Exception werfen muessen")
        } catch (e: Exception) {
            // erwartet: Exception statt OutOfMemoryError
        }
    }

    @Test
    fun `gemischte Vektorlaengen ergeben eine ladbare Datei`() {
        // Nachstellung Geraet: 1280-dim ONNX-Embeddings + 43-dim MFCC-Fallback in einer DB
        val db = EmbeddingDatabase()
        db.add("a", "Parus_major", vec(1280, 1))
        db.add("b", "Parus_major", vec(43, 2))   // MFCC-Fallback
        db.add("c", "Turdus_merula", vec(1280, 3))
        db.add("d", "Turdus_merula", vec(1536, 4)) // Vektor eines anderen Modells
        val file = tmp.newFile("mixed.bsed")
        db.save(file)

        val loaded = EmbeddingDatabase()
        loaded.load(file)
        assertEquals(2, loaded.size)
        assertEquals(setOf("a", "c"), loaded.search(vec(1280, 1), topN = 10).map { it.recordingId }.toSet())
    }

    @Test
    fun `add mit abweichender Laenge wird verworfen`() {
        val db = EmbeddingDatabase()
        db.add("a", "Parus_major", vec(1280, 1))
        db.add("b", "Parus_major", vec(43, 2))
        assertEquals(1, db.size)
        // nach clear() darf eine neue Laenge beginnen (Modellwechsel)
        db.clear()
        db.add("b", "Parus_major", vec(43, 2))
        assertEquals(1, db.size)
    }

    @Test
    fun `Roundtrip mit einheitlicher Laenge`() {
        val db = EmbeddingDatabase()
        for (i in 0 until 5) db.add("r$i", "Parus_major", vec(16, i))
        val file = tmp.newFile("ok.bsed")
        db.save(file)
        assertFalse(File(file.parentFile, "ok.bsed.tmp").exists())
        val loaded = EmbeddingDatabase()
        loaded.load(file)
        assertEquals(5, loaded.size)
    }

    @Test
    fun `alte gemischte Datei wirft Exception statt OOM`() {
        // Datei so, wie save() sie vor T196 geschrieben hat: Header-dim vom ersten Eintrag,
        // jeder Vektor mit eigener Laenge
        val file = tmp.newFile("legacy_mixed.bsed")
        val entries = listOf("a" to vec(1280, 1), "b" to vec(43, 2), "c" to vec(1280, 3))
        file.writeBytes(bsed(dim = 1280, numEntries = entries.size) { buf ->
            for ((id, v) in entries) entry(buf, id, "Parus_major", v)
        })
        assertLoadFails(file)
    }

    @Test
    fun `abgeschnittene Datei wirft Exception`() {
        val db = EmbeddingDatabase()
        for (i in 0 until 3) db.add("r$i", "Parus_major", vec(32, i))
        val file = tmp.newFile("cut.bsed")
        db.save(file)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 10))
        assertLoadFails(file)
        file.writeBytes(bytes.copyOf(10)) // mitten im Header
        assertLoadFails(file)
    }

    @Test
    fun `unsinnige Laengenfelder werfen Exception`() {
        val f1 = tmp.newFile("hugeId.bsed")
        f1.writeBytes(bsed(dim = 4, numEntries = 1) { buf -> buf.putInt(1_765_608_608) })
        assertLoadFails(f1)

        val f2 = tmp.newFile("negId.bsed")
        f2.writeBytes(bsed(dim = 4, numEntries = 1) { buf -> buf.putInt(-5) })
        assertLoadFails(f2)

        val f3 = tmp.newFile("hugeDim.bsed")
        f3.writeBytes(bsed(dim = Int.MAX_VALUE, numEntries = 1) { })
        assertLoadFails(f3)

        val f4 = tmp.newFile("hugeCount.bsed")
        f4.writeBytes(bsed(dim = 4, numEntries = 50_000_000) { buf -> entry(buf, "a", "b", vec(4, 0)) })
        assertLoadFails(f4)
    }

    @Test
    fun `fehlende Datei laedt nichts`() {
        val db = EmbeddingDatabase()
        db.load(File(tmp.root, "gibtsnicht.bsed"))
        assertTrue(db.size == 0)
    }

    // --- Hilfen: BSED-Bytes von Hand bauen ---

    private fun bsed(dim: Int, numEntries: Int, body: (ByteBuffer) -> Unit): ByteArray {
        val buf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("BSED".toByteArray(Charsets.US_ASCII))
        buf.putInt(1)
        buf.putInt(dim)
        buf.putInt(numEntries)
        body(buf)
        return buf.array().copyOf(buf.position())
    }

    private fun entry(buf: ByteBuffer, id: String, species: String, v: FloatArray) {
        val idB = id.toByteArray(Charsets.UTF_8)
        buf.putInt(idB.size); buf.put(idB)
        val spB = species.toByteArray(Charsets.UTF_8)
        buf.putInt(spB.size); buf.put(spB)
        for (x in v) buf.putFloat(x)
    }
}
