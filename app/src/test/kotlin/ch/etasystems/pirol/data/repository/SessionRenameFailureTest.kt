package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.ml.DetectionResult
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * T303 (Befund 2): Scheitert das Umbenennen des Session-Ordners, bleibt die Session nicht haengen:
 * session.json traegt wieder den Ordnernamen als ID, Zeitdaten bleiben umgerechnet, Auftrag ist weg.
 * Ein Abbruch in jedem Schritt fuehrt beim naechsten Oeffnen zu einem konsistenten Zustand.
 */
class SessionRenameFailureTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val oldId = "2026-04-20T14-30-00_abcdef"
    private val newId = "2026-04-21T06-05-00_abcdef"
    private val newStart = ZonedDateTime.parse("2026-04-21T06:05:00+02:00")
    private val shift = 15 * 3_600_000L + 35 * 60_000L
    private lateinit var base: File

    @Before fun setUp() { base = Files.createTempDirectory("t303r").toFile() }
    @After fun tearDown() { base.deleteRecursively() }

    /** Echter File-Ordner, dessen Umbenennen auf Wunsch scheitert (Provider/Dateisystem) */
    private class FlakyFolder(private val inner: FileSessionFolder, var failRename: Boolean) : SessionFolder by inner {
        override fun rename(newName: String) {
            if (failRename) throw IllegalStateException("Umbenennen fehlgeschlagen (Test)")
            inner.rename(newName)
        }
    }

    private fun meta() = SessionMetadata(
        sessionId = oldId,
        startedAt = "2026-04-20T14:30:00+02:00",
        endedAt = "2026-04-20T15:30:00+02:00",
        sampleRate = 48000,
        confidenceThreshold = 0.5f,
        importedFrom = listOf(ImportedSource("a.wav", "wav", 48000, 1, 3600f, "2026-04-20T14:30:00+02:00", "filename"))
    )

    private fun det(id: String, ts: Long) = DetectionResult(
        id = id, scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.9f,
        timestampMs = ts, chunkStartSec = 12f, chunkEndSec = 15f, sampleRate = 48000
    )

    private fun sessionDir(): File {
        val dir = File(File(base, "2026-04-20"), oldId).also { it.mkdirs() }
        val f = FileSessionFiles(dir)
        f.writeReplacing("session.json", SessionJson.pretty.encodeToString(meta()))
        f.writeReplacing("detections.jsonl", SessionJson.compact.encodeToString(det("a", 1_000L)) + "\n")
        return dir
    }

    private fun prepared(dir: File): SessionInfoEditPlan {
        val folder = FileSessionFolder.of(dir)!!
        val target = SessionRename.target(folder.name, folder.day, newStart, zone, dailySubfolder = true)
        val plan = SessionInfoEdit.withRename(SessionInfoEdit.plan(meta(), newStart, null, null, null, zone)!!, target)
        return SessionInfoEdit.prepare(FileSessionFiles(dir), plan, "session.json")
    }

    private fun metaIn(dir: File): SessionMetadata =
        SessionJson.lenient.decodeFromString<SessionMetadata>(FileSessionFiles(dir).readText("session.json")!!)

    /** Konsistent: Ordnername == sessionId, neue Startzeit, Zeiten genau einmal verschoben, kein Auftrag */
    private fun assertConsistent(dir: File) {
        assertTrue(dir.isDirectory)
        val m = metaIn(dir)
        assertEquals(dir.name, m.sessionId)
        assertEquals("2026-04-21T06:05:00+02:00", m.startedAt)
        assertEquals(1_000L + shift, SessionTextParser.detections(FileSessionFiles(dir).readText("detections.jsonl")!!)[0].timestampMs)
        assertFalse(File(dir, SessionInfoEdit.JOURNAL_FILE).exists())
    }

    @Test fun rename_failure_falls_back_to_folder_name() {
        val dir = sessionDir()
        val p = prepared(dir)
        val folder = FlakyFolder(FileSessionFolder.of(dir)!!, failRename = true)
        var outcome: SessionRename.Outcome? = null
        SessionInfoEdit.execute(FileSessionFiles(dir), p, "session.json", folder) { outcome = it }
        assertNotNull(outcome?.renameError)
        assertFalse(outcome!!.moved)
        assertEquals(oldId, folder.name)
        assertConsistent(dir)
        assertEquals(oldId, metaIn(dir).sessionId)
    }

    @Test fun occupied_target_falls_back_to_folder_name() {
        val dir = sessionDir()
        val p = prepared(dir)
        File(dir.parentFile, newId).mkdirs()  // Ziel belegt (nicht gelistete Session/fremder Ordner)
        SessionInfoEdit.execute(FileSessionFiles(dir), p, "session.json", FileSessionFolder.of(dir)!!)
        assertConsistent(dir)
        assertEquals(oldId, metaIn(dir).sessionId)
    }

    @Test fun resume_with_failing_rename_stays_consistent() {
        val dir = sessionDir()
        val p = prepared(dir)
        // Abbruch nach Inhalt + session.json (neue ID), vor dem Umbenennen
        SessionInfoEdit.execute(FileSessionFiles(dir), p, "session.json", folder = null)
        assertEquals(newId, metaIn(dir).sessionId)
        // Naechstes Oeffnen: Umbenennen scheitert wieder -> Rueckfall, kein Fehler, kein Auftrag mehr
        val plan = SessionInfoEdit.resumePending(FileSessionFiles(dir), "session.json",
            FlakyFolder(FileSessionFolder.of(dir)!!, failRename = true))
        assertNotNull(plan)
        assertConsistent(dir)
        assertEquals(oldId, metaIn(dir).sessionId)
        // und kein weiterer Auftrag
        assertNull(SessionInfoEdit.resumePending(FileSessionFiles(dir), "session.json", FileSessionFolder.of(dir)))
    }

    @Test fun abort_after_fallback_before_journal_delete_aligns_after_later_rename() {
        val dir = sessionDir()
        val p = prepared(dir)
        SessionInfoEdit.execute(FileSessionFiles(dir), p, "session.json", FlakyFolder(FileSessionFolder.of(dir)!!, true))
        // Auftrag taucht wieder auf (Abbruch vor dem Loeschen); diesmal klappt das Umbenennen
        FileSessionFiles(dir).writeReplacing(SessionInfoEdit.JOURNAL_FILE, SessionInfoEdit.encodePlan(p))
        val folder = FileSessionFolder.of(dir)!!
        SessionInfoEdit.resumePending(FileSessionFiles(dir), "session.json", folder)
        val moved = folder.key
        assertEquals(newId, moved.name)
        assertConsistent(moved)  // ID folgt dem neuen Ordnernamen, Zeiten nicht doppelt verschoben
    }

    @Test fun align_session_id_only_when_different() {
        val dir = sessionDir()
        val f = FileSessionFiles(dir)
        assertFalse(SessionInfoEdit.alignSessionId(f, "session.json", oldId))
        assertTrue(SessionInfoEdit.alignSessionId(f, "session.json", "anders_abcdef"))
        assertEquals("anders_abcdef", metaIn(dir).sessionId)
        f.writeReplacing("session.json", "kaputt")
        assertFalse(SessionInfoEdit.alignSessionId(f, "session.json", oldId))
        assertEquals("kaputt", f.readText("session.json"))
    }
}
