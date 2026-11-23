package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.ml.DetectionResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * T251 (N2): Detektions-Updates der aktiven Session append-only in die Zusatzdatei, Zusammenfuehren
 * einmal beim Session-Ende (bzw. beim naechsten Oeffnen nach Kill/Fehler). detections.jsonl bleibt
 * waehrend der Aufnahme unberuehrt und hat danach dasselbe Format wie mit direktem Rewrite.
 */
class DetectionUpdateLogTest {

    private val json = Json { prettyPrint = false }
    private lateinit var dir: File
    private lateinit var files: FileSessionFiles
    private val main get() = File(dir, "detections.jsonl")
    private val side get() = File(dir, DetectionUpdateLog.FILE)

    @Before fun setUp() {
        dir = Files.createTempDirectory("t251").toFile()
        files = FileSessionFiles(dir)
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun det(id: String, count: Int = 1, note: String? = null) = DetectionResult(
        id = id, scientificName = "Parus major", commonName = "Kohlmeise",
        confidence = 0.9f, timestampMs = 1000L, chunkStartSec = 0f, chunkEndSec = 3f,
        sampleRate = 48000, count = count, note = note
    )

    /** Live-Ablauf: Anhaengen ueber den Log (merkt die IDs) */
    private fun append(log: DetectionUpdateLog, vararg d: DetectionResult) {
        val text = log.takeForAppend(d.toList()).joinToString("") { json.encodeToString(it) + "\n" }
        main.appendText(text)
    }

    private fun flush(log: DetectionUpdateLog) = log.flush { side.appendText(it) }

    private fun readMain() = main.readLines().filter { it.isNotBlank() }.map { json.decodeFromString<DetectionResult>(it) }

    @Test fun flush_schreibt_nur_Zusatzdatei_Hauptdatei_unberuehrt() {
        val log = DetectionUpdateLog()
        append(log, det("A"), det("B"))
        val before = main.readText()
        log.put(det("A", count = 3))
        assertEquals(1, flush(log))
        assertEquals(before, main.readText())
        assertEquals(json.encodeToString(det("A", count = 3)) + "\n", side.readText())
        assertTrue(log.isEmpty())
    }

    @Test fun kosten_flach_jeder_Flush_haengt_nur_die_eigenen_Zeilen_an() {
        val log = DetectionUpdateLog()
        for (i in 0 until 500) append(log, det("D$i"))
        val mainSize = main.length()
        var sideSize = 0L
        for (i in 0 until 50) {
            log.put(det("D$i", count = 2))
            flush(log)
            val grown = side.length() - sideSize
            assertEquals(json.encodeToString(det("D$i", count = 2)).length + 1L, grown)
            sideSize = side.length()
        }
        assertEquals(mainSize, main.length())
    }

    @Test fun update_vor_dem_Anhaengen_bleibt_stehen_und_geht_in_die_Zeile_ein() {
        val log = DetectionUpdateLog()
        log.put(det("A", count = 4))
        assertEquals(0, flush(log))
        assertFalse(side.exists())
        append(log, det("A"))
        assertEquals(4, readMain().single().count)
        assertTrue(log.isEmpty())
    }

    @Test fun fehler_beim_Anhaengen_Eintraege_bleiben() {
        val log = DetectionUpdateLog()
        append(log, det("A"))
        log.put(det("A", count = 2))
        try { log.flush { throw IOException("voll") }; error("erwartet") } catch (_: IOException) {}
        assertEquals(1, log.size())
        assertEquals(1, flush(log))
    }

    @Test fun zusammenfuehren_letzter_Stand_je_ID_gleiches_Format_wie_direkter_Rewrite() {
        val log = DetectionUpdateLog()
        append(log, det("A"))
        log.put(det("A", count = 5)); flush(log)
        append(log, det("B"))
        log.put(det("A", count = 6, note = "n")); log.put(det("B", count = 2)); flush(log)

        assertTrue(DetectionUpdateLog.consolidate(files))
        assertFalse(side.exists())
        val expected = listOf(det("A", count = 6, note = "n"), det("B", count = 2))
            .joinToString("") { json.encodeToString(it) + "\n" }
        assertEquals(expected, main.readText())
    }

    @Test fun ansicht_beim_Lesen_ohne_Schreiben() {
        val log = DetectionUpdateLog()
        append(log, det("A"), det("B"))
        log.put(det("B", count = 9)); flush(log)
        val before = main.readText()
        val view = DetectionUpdateLog.mergedText(files)!!
        assertEquals(before, main.readText())
        assertTrue(side.exists())
        assertEquals(listOf(1, 9), view.lines().filter { it.isNotBlank() }.map { json.decodeFromString<DetectionResult>(it).count })
    }

    @Test fun kill_zwischen_Schreiben_und_Loeschen_idempotent() {
        val log = DetectionUpdateLog()
        append(log, det("A"))
        log.put(det("A", count = 2)); flush(log)
        val sideCopy = side.readText()
        DetectionUpdateLog.consolidate(files)
        side.writeText(sideCopy)  // Loeschen nie passiert
        DetectionUpdateLog.consolidate(files)
        assertEquals(2, readMain().single().count)
        assertFalse(side.exists())
    }

    @Test fun gescheiterter_Flush_am_Ende_Updates_gehen_ins_Zusammenfuehren() {
        val log = DetectionUpdateLog()
        append(log, det("A"), det("B"))
        log.put(det("A", count = 2)); flush(log)
        log.put(det("B", count = 3))  // Zusatzdatei nicht mehr beschreibbar
        assertTrue(DetectionUpdateLog.consolidate(files, log.pendingWithLine()))
        assertEquals(listOf(2, 3), readMain().map { it.count })
    }

    @Test fun zusatzdatei_ohne_Hauptdatei_wird_verworfen() {
        side.writeText(json.encodeToString(det("X")) + "\n")
        assertFalse(DetectionUpdateLog.consolidate(files))
        assertFalse(side.exists())
        assertFalse(main.exists())
    }

    @Test fun nichts_zu_tun_ohne_Zusatzdatei() {
        main.writeText(json.encodeToString(det("A")) + "\n")
        val mod = main.lastModified()
        assertFalse(DetectionUpdateLog.consolidate(files))
        assertEquals(mod, main.lastModified())
    }

    @Test fun aktueller_Stand_Warteschlange_vor_Zusatzdatei_vor_Hauptdatei() {
        val log = DetectionUpdateLog()
        append(log, det("A"), det("B"), det("C"))
        log.put(det("B", count = 2)); flush(log)
        log.put(det("C", count = 3))
        assertEquals(1, log.current("A", files)!!.count)
        assertEquals(2, log.current("B", files)!!.count)
        assertEquals(3, log.current("C", files)!!.count)
        assertNull(log.current("Z", files))
    }

    @Test fun angehaengte_Zeile_von_aussen_wird_als_vorhanden_gemerkt() {
        val log = DetectionUpdateLog()
        val line = json.encodeToString(det("M")) + "\n"
        main.appendText(line)
        log.noteAppendedLine(line)
        log.put(det("M", count = 2))
        assertEquals(1, flush(log))
    }

    @Test fun neue_Session_vergisst_IDs() {
        val log = DetectionUpdateLog()
        append(log, det("A"))
        log.reset()
        log.put(det("A", count = 2))
        assertEquals(0, flush(log))
    }
}
