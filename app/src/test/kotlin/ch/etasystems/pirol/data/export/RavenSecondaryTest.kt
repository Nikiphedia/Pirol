package ch.etasystems.pirol.data.export

import ch.etasystems.pirol.ml.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T224: Raven-Spalten des Zweitmodells nur, wenn Ergebnisse vorhanden und Feld aktiv. */
class RavenSecondaryTest {

    private fun det(id: String, withSecondary: Boolean) = DetectionResult(
        id = id, scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.82f,
        timestampMs = 0L, chunkStartSec = 0f, chunkEndSec = 3f, sampleRate = 48000,
        secondaryModelId = if (withSecondary) "birdnet24_global6k" else null,
        secondaryStatus = if (withSecondary) "DISAGREES" else null,
        secondaryConfidence = if (withSecondary) 0.2f else null,
        secondaryTopSpecies = if (withSecondary) "Parus_major" else null,
        secondaryTopConfidence = if (withSecondary) 0.71f else null
    )

    private fun export(dets: List<DetectionResult>, fields: Set<String>?): List<List<String>> {
        val f = File.createTempFile("raven", ".txt")
        try {
            RavenExporter.export(f, dets, "2026-11-23T10:00:00+01:00", enabledFields = fields)
            return f.readLines().map { it.split('\t') }
        } finally {
            f.delete()
        }
    }

    @Test
    fun withoutSecondaryResults_noColumns() {
        val rows = export(listOf(det("a", false)), null)
        assertFalse(rows[0].any { it.startsWith("Second") })
        assertEquals(rows[0].size, rows[1].size)
    }

    @Test
    fun withSecondaryResults_fiveColumns() {
        val rows = export(listOf(det("a", true), det("b", false)), null)
        val h = rows[0]
        val i = h.indexOf("Second Model")
        assertTrue(i > 0)
        assertEquals(listOf("Second Model", "Second Status", "Second Score", "Second Top Species", "Second Top Score"), h.subList(i, i + 5))
        assertEquals(listOf("birdnet24_global6k", "DISAGREES", "0.200", "Parus_major", "0.710"), rows[1].subList(i, i + 5))
        assertEquals(listOf("", "", "", "", ""), rows[2].subList(i, i + 5))
        assertEquals("Status", h[i + 5])
    }

    @Test
    fun fieldDisabled_noColumns() {
        val rows = export(listOf(det("a", true)), setOf(RavenExporter.FIELD_SPECIES))
        assertFalse(rows[0].any { it.startsWith("Second") })
    }
}
