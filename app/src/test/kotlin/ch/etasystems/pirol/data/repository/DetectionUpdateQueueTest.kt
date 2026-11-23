package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.ml.DetectionResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T236: Gebuendeltes Schreiben von detections.jsonl (n Updates -> 1 Rewrite, Reihenfolge Append/Rewrite). */
class DetectionUpdateQueueTest {

    private val json = Json { prettyPrint = false }

    private fun det(id: String, count: Int = 1, lat: Double? = null) = DetectionResult(
        id = id, scientificName = "Parus major", commonName = "Kohlmeise",
        confidence = 0.9f, timestampMs = 1000L, chunkStartSec = 0f, chunkEndSec = 3f,
        sampleRate = 48000, count = count, latitude = lat
    )

    private fun line(d: DetectionResult) = json.encodeToString(d) + "\n"

    /** Datei-Nachbau: Append sofort, Rewrite zaehlt mit */
    private class FakeFile(var text: String = "") {
        var rewrites = 0
        var failNext = false
        fun rewrite(t: String) {
            if (failNext) { failNext = false; throw java.io.IOException("Speicher voll") }
            rewrites++; text = t
        }
    }

    private fun parse(text: String) = text.lines().filter { it.isNotBlank() }
        .map { Json { ignoreUnknownKeys = true }.decodeFromString<DetectionResult>(it) }

    @Test fun nUpdates_einRewrite() {
        val f = FakeFile((1..20).joinToString("") { line(det("d$it")) })
        val q = DetectionUpdateQueue()
        // 60 Updates (Zweitmodell, Merge, Fix) auf 20 Detektionen, mehrfach dieselbe ID
        for (round in 1..3) for (i in 1..20) q.put(det("d$i", count = round * 10 + i))
        val written = q.flush({ f.text }) { f.rewrite(it) }
        assertEquals(1, f.rewrites)
        assertEquals(20, written.size)
        assertTrue(q.isEmpty())
        val out = parse(f.text)
        assertEquals((1..20).map { "d$it" }, out.map { it.id })          // Reihenfolge der Zeilen bleibt
        assertEquals((1..20).map { 30 + it }, out.map { it.count })       // letzter Stand gewinnt
        // Leere Warteschlange: kein weiterer Rewrite
        q.flush({ f.text }) { f.rewrite(it) }
        assertEquals(1, f.rewrites)
    }

    @Test fun formatUnveraendert() {
        val a = det("a"); val b = det("b")
        val f = FakeFile(line(a) + line(b))
        val q = DetectionUpdateQueue()
        val b2 = det("b", count = 4, lat = 47.1)
        q.put(b2)
        q.flush({ f.text }) { f.rewrite(it) }
        assertEquals(line(a) + line(b2), f.text)  // gleiche Kodierung wie appendDetections
    }

    @Test fun updateVorAppend_bleibtStehen_dannNeueFassungBeimAppend() {
        val f = FakeFile(line(det("a")))
        val q = DetectionUpdateQueue()
        q.put(det("b", count = 3))                    // Zeile "b" noch nicht angehaengt
        q.flush({ f.text }) { f.rewrite(it) }
        assertEquals(0, f.rewrites)                   // nichts passt -> kein Rewrite
        assertFalse(q.isEmpty())                       // Update geht nicht verloren
        // Append von "b" (alte Fassung) nimmt die vorgemerkte neue Fassung
        val appended = q.takeForAppend(listOf(det("b"), det("c")))
        assertEquals(listOf(3, 1), appended.map { it.count })
        assertTrue(q.isEmpty())
    }

    @Test fun updateNachAppend_imSelbenRewrite() {
        val f = FakeFile(line(det("a")))
        val q = DetectionUpdateQueue()
        q.put(det("a", count = 2))
        f.text += q.takeForAppend(listOf(det("b"))).joinToString("") { line(it) }  // Append dazwischen
        q.put(det("b", count = 5))
        q.flush({ f.text }) { f.rewrite(it) }
        assertEquals(1, f.rewrites)
        assertEquals(listOf(2, 5), parse(f.text).map { it.count })
    }

    @Test fun fehlerBeimRewrite_updatesBleibenFuerNaechstenVersuch() {
        val f = FakeFile(line(det("a")))
        val q = DetectionUpdateQueue()
        q.put(det("a", count = 7))
        f.failNext = true
        try { q.flush({ f.text }) { f.rewrite(it) } } catch (_: java.io.IOException) {}
        assertFalse(q.isEmpty())
        q.flush({ f.text }) { f.rewrite(it) }
        assertEquals(7, parse(f.text).single().count)
        assertTrue(q.isEmpty())
    }

    @Test fun neuererStandWaehrendRewrite_bleibtStehen() {
        val f = FakeFile(line(det("a")))
        val q = DetectionUpdateQueue()
        q.put(det("a", count = 2))
        q.flush({ f.text }) { t -> q.put(det("a", count = 3)); f.rewrite(t) }  // kommt waehrend des Schreibens
        assertEquals(2, parse(f.text).single().count)
        assertFalse("neuerer Stand darf nicht als geschrieben gelten", q.isEmpty())
        q.flush({ f.text }) { f.rewrite(it) }
        assertEquals(3, parse(f.text).single().count)
    }

    @Test fun idPraefix_undRueckfallAufDecoder() {
        assertEquals("abc-1", SessionFilesLogic.detectionIdOf(json.encodeToString(det("abc-1"))))
        // abweichende Feldreihenfolge (fremder Schreiber) -> Decoder
        val reordered = "{\"scientificName\":\"Parus major\",\"id\":\"x9\",\"commonName\":\"K\",\"confidence\":0.5," +
            "\"timestampMs\":1,\"chunkStartSec\":0.0,\"chunkEndSec\":3.0,\"sampleRate\":48000}"
        assertEquals("x9", SessionFilesLogic.detectionIdOf(reordered))
        assertEquals(null, SessionFilesLogic.detectionIdOf("kaputt"))
        // unlesbare Zeilen bleiben beim Ersetzen unveraendert
        val r = SessionFilesLogic.replaceDetectionsInText("kaputt\n" + line(det("a")), mapOf("a" to det("a", count = 2)))
        assertEquals("kaputt", r.text.lines().first())
        assertEquals(setOf("a"), r.matchedIds)
    }
}
