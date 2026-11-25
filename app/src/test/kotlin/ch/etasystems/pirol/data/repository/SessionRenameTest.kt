package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.ml.DetectionResult
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

/** T273: Session-Ordner nach Datumsaenderung umbenennen/verschieben (Plan, Ausfuehrung, Wiederherstellung). */
class SessionRenameTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val oldId = "2026-04-20T14-30-00_abcdef"
    private lateinit var base: File

    @Before fun setUp() { base = Files.createTempDirectory("t273").toFile() }
    @After fun tearDown() { base.deleteRecursively() }

    // ── Plan ─────────────────────────────────────────────────────────

    @Test fun newId_keeps_uuid_and_uses_local_time() {
        assertEquals("2026-04-21T06-05-00_abcdef",
            SessionRename.newSessionId(oldId, ZonedDateTime.parse("2026-04-21T04:05:00Z"), zone))
        assertNull(SessionRename.newSessionId("meine-session", ZonedDateTime.parse("2026-04-21T06:05:00+02:00"), zone))
    }

    @Test fun target_day_follows_date_only_with_daily_folders() {
        val start = ZonedDateTime.parse("2026-04-21T06:05:00+02:00")
        val t = SessionRename.target(oldId, "2026-04-20", start, zone, dailySubfolder = true)!!
        assertEquals(SessionRenameTarget(oldId, "2026-04-21T06-05-00_abcdef", "2026-04-20", "2026-04-21"), t)
        val flat = SessionRename.target(oldId, null, start, zone, dailySubfolder = false)!!
        assertNull(flat.newDay)
        // gleiche Startzeit: nichts zu tun
        assertNull(SessionRename.target(oldId, "2026-04-20", ZonedDateTime.parse("2026-04-20T14:30:00+02:00"), zone, true))
        // Uhrzeit am selben Tag: nur umbenennen
        val sameDay = SessionRename.target(oldId, "2026-04-20", ZonedDateTime.parse("2026-04-20T16:00:00+02:00"), zone, true)!!
        assertEquals(listOf(SessionRename.Step.RENAME), SessionRename.remaining(oldId, "2026-04-20", sameDay))
    }

    @Test fun remaining_steps_from_every_state() {
        val t = SessionRenameTarget(oldId, "new_abcdef", "2026-04-20", "2026-04-21")
        assertEquals(listOf(SessionRename.Step.RENAME, SessionRename.Step.MOVE), SessionRename.remaining(oldId, "2026-04-20", t))
        assertEquals(listOf(SessionRename.Step.MOVE), SessionRename.remaining("new_abcdef", "2026-04-20", t))
        assertEquals(emptyList<SessionRename.Step>(), SessionRename.remaining("new_abcdef", "2026-04-21", t))
    }

    // ── Ausfuehrung mit Testordner ───────────────────────────────────

    /** Ordner im Speicher; [failMove] = Provider ohne Move */
    private class FakeFolder(override var name: String, override var day: String?, val occupied: Set<Pair<String?, String>> = emptySet(),
                             val failMove: Boolean = false) : SessionFolder {
        val ops = mutableListOf<String>()
        override val key: File get() = File(day ?: "", name)
        override fun exists(name: String, day: String?) = (day to name) in occupied
        override fun rename(newName: String) { ops += "rename"; name = newName }
        override fun moveTo(day: String?) { if (failMove) throw IllegalStateException("kein Move"); ops += "move"; this.day = day }
        override fun files(): SessionFiles = throw UnsupportedOperationException()
    }

    @Test fun apply_runs_only_missing_steps() {
        val t = SessionRenameTarget(oldId, "new_abcdef", "2026-04-20", "2026-04-21")
        val f = FakeFolder(oldId, "2026-04-20")
        val o = SessionRename.apply(f, t)
        assertTrue(o.renamed && o.moved)
        assertEquals(listOf("rename", "move"), f.ops)
        // Abbruch nach dem Umbenennen (SAF: zwei Schritte): nur noch verschieben
        val half = FakeFolder("new_abcdef", "2026-04-20")
        SessionRename.apply(half, t)
        assertEquals(listOf("move"), half.ops)
        // alles erledigt: nichts mehr
        val done = FakeFolder("new_abcdef", "2026-04-21")
        SessionRename.apply(done, t)
        assertTrue(done.ops.isEmpty())
    }

    @Test fun apply_refuses_occupied_target_before_any_change() {
        val t = SessionRenameTarget(oldId, "new_abcdef", "2026-04-20", "2026-04-21")
        val f = FakeFolder(oldId, "2026-04-20", occupied = setOf("2026-04-20" to "new_abcdef"))
        try { SessionRename.apply(f, t); fail() } catch (_: IllegalStateException) { }
        assertTrue(f.ops.isEmpty())
        // fremder Ordnername: nie umbenennen
        val other = FakeFolder("fremd", "2026-04-20")
        try { SessionRename.apply(other, t); fail() } catch (_: IllegalStateException) { }
        assertTrue(other.ops.isEmpty())
    }

    @Test fun move_failure_keeps_session_valid() {
        val t = SessionRenameTarget(oldId, "new_abcdef", "2026-04-20", "2026-04-21")
        val f = FakeFolder(oldId, "2026-04-20", failMove = true)
        val o = SessionRename.apply(f, t)
        assertTrue(o.renamed)
        assertFalse(o.moved)
        assertNotNull(o.moveError)
        assertEquals("new_abcdef", f.name)
        assertEquals("2026-04-20", f.day)
    }

    // ── Ende-zu-Ende im File-Modus (Auftrag + Ordner) ────────────────

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
        f.writeReplacing("verifications.jsonl", "")
        f.writeReplacing("detections.rescan-20260421-100000.jsonl", SessionJson.compact.encodeToString(det("r", 2_000L)) + "\n")
        f.writeReplacing("rescan-20260421-100000.json", "{}")
        f.writeReplacing("human_windows.jsonl", "{\"kind\":\"scanned\",\"startSec\":0,\"endSec\":3}\n")
        File(dir, "recording.wav").writeBytes(ByteArray(44))
        File(dir, "memos").mkdirs()
        File(dir, "memos/session_20260420-143500.wav").writeBytes(ByteArray(44))
        return dir
    }

    private val newStart = ZonedDateTime.parse("2026-04-21T06:05:00+02:00")
    private val newId = "2026-04-21T06-05-00_abcdef"

    private fun preparedPlan(dir: File): SessionInfoEditPlan {
        val folder = FileSessionFolder.of(dir)!!
        val target = SessionRename.target(folder.name, folder.day, newStart, zone, dailySubfolder = true)
        val plan = SessionInfoEdit.withRename(SessionInfoEdit.plan(meta(), newStart, null, null, null, zone)!!, target)
        return SessionInfoEdit.prepare(FileSessionFiles(dir), plan, "session.json")
    }

    private fun assertMoved(oldDir: File) {
        assertFalse(oldDir.exists())
        val moved = File(File(base, "2026-04-21"), newId)
        assertTrue(moved.isDirectory)
        val f = FileSessionFiles(moved)
        assertEquals(newId, SessionJson.lenient.decodeFromString<SessionMetadata>(f.readText("session.json")!!).sessionId)
        assertEquals("2026-04-21T06:05:00+02:00",
            SessionJson.lenient.decodeFromString<SessionMetadata>(f.readText("session.json")!!).startedAt)
        assertFalse(f.exists(SessionInfoEdit.JOURNAL_FILE))
        // alle Dateien wandern mit (Varianten, Merkliste, Memos, Audio)
        for (n in listOf("detections.rescan-20260421-100000.jsonl", "rescan-20260421-100000.json", "human_windows.jsonl", "recording.wav")) {
            assertTrue(n, f.exists(n))
        }
        assertTrue(File(moved, "memos/session_20260420-143500.wav").exists())
        // Zeitverschiebung genau einmal (+15 h 35 min)
        val shift = 15 * 3_600_000L + 35 * 60_000L
        assertEquals(1_000L + shift, SessionTextParser.detections(f.readText("detections.jsonl")!!)[0].timestampMs)
        assertEquals(2_000L + shift, SessionTextParser.detections(f.readText("detections.rescan-20260421-100000.jsonl")!!)[0].timestampMs)
    }

    @Test fun edit_renames_and_moves_folder() {
        val dir = sessionDir()
        val p = preparedPlan(dir)
        val folder = FileSessionFolder.of(dir)!!
        SessionInfoEdit.execute(FileSessionFiles(dir), p, "session.json", folder)
        assertEquals(newId, folder.key.name)
        assertMoved(dir)
    }

    @Test fun recovery_after_journal_only() {
        val dir = sessionDir()
        preparedPlan(dir)  // Auftrag geschrieben, dann Abbruch
        assertNotNull(SessionInfoEdit.resumePending(FileSessionFiles(dir), "session.json", FileSessionFolder.of(dir)))
        assertMoved(dir)
    }

    @Test fun recovery_after_content_and_session_json_before_rename() {
        val dir = sessionDir()
        val p = preparedPlan(dir)
        SessionInfoEdit.execute(FileSessionFiles(dir), p, "session.json", folder = null)  // ohne Ordner: Auftrag bleibt
        assertTrue(File(dir, SessionInfoEdit.JOURNAL_FILE).exists())
        SessionInfoEdit.resumePending(FileSessionFiles(dir), "session.json", FileSessionFolder.of(dir))
        assertMoved(dir)
    }

    @Test fun recovery_after_rename_before_move() {
        val dir = sessionDir()
        val p = preparedPlan(dir)
        SessionInfoEdit.execute(FileSessionFiles(dir), p, "session.json", folder = null)
        val renamed = File(dir.parentFile, newId)
        assertTrue(dir.renameTo(renamed))  // Abbruch zwischen Umbenennen und Verschieben
        SessionInfoEdit.resumePending(FileSessionFiles(renamed), "session.json", FileSessionFolder.of(renamed))
        assertMoved(dir)
    }

    @Test fun recovery_after_move_before_journal_delete() {
        val dir = sessionDir()
        val p = preparedPlan(dir)
        val folder = FileSessionFolder.of(dir)!!
        SessionInfoEdit.execute(FileSessionFiles(dir), p, "session.json", folder)
        // Auftrag taucht wieder auf (Abbruch vor dem Loeschen): keine zweite Verschiebung, kein Umbenennen
        val moved = folder.key
        FileSessionFiles(moved).writeReplacing(SessionInfoEdit.JOURNAL_FILE, SessionInfoEdit.encodePlan(p))
        SessionInfoEdit.resumePending(FileSessionFiles(moved), "session.json", FileSessionFolder.of(moved))
        assertMoved(dir)
    }

    @Test fun flat_storage_renames_in_place() {
        val dir = File(base, oldId).also { it.mkdirs() }
        FileSessionFiles(dir).writeReplacing("session.json", SessionJson.pretty.encodeToString(meta()))
        val folder = FileSessionFolder.of(dir)!!
        assertNull(folder.day)
        val target = SessionRename.target(folder.name, folder.day, newStart, zone, dailySubfolder = false)!!
        val plan = SessionInfoEdit.prepare(FileSessionFiles(dir),
            SessionInfoEdit.withRename(SessionInfoEdit.plan(meta(), newStart, null, null, null, zone)!!, target), "session.json")
        SessionInfoEdit.execute(FileSessionFiles(dir), plan, "session.json", folder)
        assertTrue(File(base, newId).isDirectory)
        assertFalse(dir.exists())
    }
}
