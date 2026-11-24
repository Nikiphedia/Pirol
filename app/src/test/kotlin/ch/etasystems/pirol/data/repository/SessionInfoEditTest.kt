package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
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

/** T257: Angaben importierter Sessions bearbeiten (Zeitverschiebung, Ortsregel, Auftrag/Idempotenz). */
class SessionInfoEditTest {

    private val zone = ZoneId.of("Europe/Zurich")
    private val h = 3_600_000L
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("t257").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun meta(lat: Double? = 47.0, lon: Double? = 8.0) = SessionMetadata(
        sessionId = "2026-04-20T14-30-00_abcdef",
        startedAt = "2026-04-20T14:30:00+02:00",
        endedAt = "2026-04-20T15:30:00+02:00",
        sampleRate = 48000,
        latitude = lat,
        longitude = lon,
        confidenceThreshold = 0.5f,
        recordingSegments = listOf(
            RecordingSegment("recording.wav", 0f, 1800f, "2026-04-20T14:30:00+02:00"),
            RecordingSegment("recording-002.wav", 1800f, 1800f, null)
        ),
        importedFrom = listOf(ImportedSource("a.wav", "wav", 48000, 1, 1800f, "2026-04-20T14:30:00+02:00", "filename"))
    )

    private fun det(
        id: String, ts: Long, lat: Double? = null, lon: Double? = null, acc: Float? = null,
        lastDetected: Long = 0L, verifiedAt: Long? = null, firstSeen: Long = ts, lastSeen: Long = ts
    ) = DetectionResult(
        id = id, scientificName = "Turdus merula", commonName = "Amsel", confidence = 0.9f,
        timestampMs = ts, chunkStartSec = 12f, chunkEndSec = 15f, sampleRate = 48000,
        latitude = lat, longitude = lon, verifiedAtMs = verifiedAt, lastDetectedMs = lastDetected,
        firstSeenAtMs = firstSeen, lastSeenAtMs = lastSeen, locationAccuracyM = acc, bestStartSec = 13f, bestEndSec = 16f
    )

    private fun lines(vararg d: DetectionResult) = d.joinToString("") { SessionJson.compact.encodeToString(it) + "\n" }
    private fun evLines(vararg e: VerificationEvent) = e.joinToString("") { SessionJson.compact.encodeToString(it) + "\n" }
    private fun parse(text: String) = SessionTextParser.detections(text)

    private fun plan(newStart: String, lat: Double? = 47.0, lon: Double? = 8.0, old: SessionMetadata = meta()) =
        SessionInfoEdit.plan(old, ZonedDateTime.parse(newStart), lat, lon, old.note, zone)!!

    // ── Zeit ─────────────────────────────────────────────────────────

    @Test fun metadata_all_times_shifted_with_offset_and_manual_source() {
        val p = plan("2026-04-20T16:45:00+02:00")
        assertEquals(2 * h + 15 * 60_000L, p.deltaMs)
        val m = p.metadata
        assertEquals("2026-04-20T16:45:00+02:00", m.startedAt)
        assertEquals("2026-04-20T17:45:00+02:00", m.endedAt)
        assertEquals("2026-04-20T16:45:00+02:00", m.recordingSegments!![0].startedAt)
        assertNull(m.recordingSegments!![1].startedAt)
        // Audio-Offsets bleiben
        assertEquals(1800f, m.recordingSegments!![1].startSec)
        assertEquals("2026-04-20T16:45:00+02:00", m.importedFrom!![0].startedAt)
        assertEquals("manual", m.importedFrom!![0].timeSource)
        assertFalse(p.relocate)
    }

    @Test fun unchanged_start_keeps_time_source_and_strings() {
        val p = plan("2026-04-20T14:30:00+02:00")
        assertEquals(0L, p.deltaMs)
        assertEquals(meta().startedAt, p.metadata.startedAt)
        assertEquals("filename", p.metadata.importedFrom!![0].timeSource)
        assertFalse(SessionInfoEdit.touchesFiles(p))
    }

    @Test fun shift_across_dst_change_uses_offset_of_new_instant() {
        // Winterzeit (+01:00) um 30 Tage in die Sommerzeit: Offset +02:00, Ortszeit +1 h zusaetzlich sichtbar
        val shifted = SessionInfoEdit.shiftIso("2026-03-20T10:00:00+01:00", 30L * 24 * h, zone)
        assertEquals("2026-04-19T11:00:00+02:00", shifted)
        // Rueckwaerts in die Winterzeit
        assertEquals("2026-03-20T10:00:00+01:00", SessionInfoEdit.shiftIso(shifted, -30L * 24 * h, zone))
        // Instant bleibt exakt um delta verschoben
        val a = ZonedDateTime.parse("2026-03-20T10:00:00+01:00").toInstant()
        val b = ZonedDateTime.parse(shifted).toInstant()
        assertEquals(30L * 24 * h, b.toEpochMilli() - a.toEpochMilli())
    }

    @Test fun shift_iso_unreadable_unchanged_and_utc_input_gets_offset() {
        assertEquals("kaputt", SessionInfoEdit.shiftIso("kaputt", h, zone))
        assertEquals("2026-07-01T14:00:00+02:00", SessionInfoEdit.shiftIso("2026-07-01T11:00:00Z", h, zone))
    }

    @Test fun detection_time_fields_shifted_audio_offsets_kept() {
        val p = plan("2026-04-20T15:30:00+02:00")
        val text = lines(
            det("a", 1000L, lastDetected = 2000L, verifiedAt = 3000L, firstSeen = 900L, lastSeen = 1500L),
            det("b", 5000L)  // lastDetected 0, verifiedAt null
        )
        val out = parse(SessionInfoEdit.detectionsText(text, p, emptySet()))
        val a = out[0]
        assertEquals(1000L + h, a.timestampMs)
        assertEquals(900L + h, a.firstSeenAtMs)
        assertEquals(1500L + h, a.lastSeenAtMs)
        assertEquals(2000L + h, a.lastDetectedMs)
        assertEquals(3000L + h, a.verifiedAtMs)
        assertEquals(12f, a.chunkStartSec); assertEquals(15f, a.chunkEndSec)
        assertEquals(13f, a.bestStartSec); assertEquals(16f, a.bestEndSec)
        val b = out[1]
        assertEquals(5000L + h, b.timestampMs)
        assertEquals(5000L + h, b.firstSeenAtMs)
        assertEquals(0L, b.lastDetectedMs)
        assertNull(b.verifiedAtMs)
    }

    @Test fun unknown_fields_and_unreadable_lines_preserved() {
        val p = plan("2026-04-20T15:30:00+02:00")
        val text = "{\"id\":\"x\",\"scientificName\":\"A b\",\"commonName\":\"A\",\"confidence\":0.5,\"timestampMs\":100," +
            "\"chunkStartSec\":0.0,\"chunkEndSec\":3.0,\"sampleRate\":48000,\"amselExtra\":\"bleibt\"}\nnicht json\n\n"
        val out = SessionInfoEdit.detectionsText(text, p, emptySet())
        val ls = out.split('\n')
        assertTrue(ls[0].startsWith("{\"id\":\"x\""))
        assertTrue(ls[0].contains("\"amselExtra\":\"bleibt\""))
        assertTrue(ls[0].contains("\"timestampMs\":${100 + h}"))
        assertEquals("nicht json", ls[1])
        assertTrue(out.endsWith("\n\n"))
    }

    @Test fun events_shift_verified_at_and_cluster_merged_payload() {
        val d = 2 * h
        val text = evLines(
            VerificationEvent("a", VerificationStatus.CONFIRMED, verifiedAtMs = 1000L, actionType = "CONFIRMED"),
            VerificationEvent("a", VerificationStatus.UNVERIFIED, "merged:b|count:2|lastSeenAt:5000|conf:0.8|start:12.5",
                verifiedAtMs = 6000L, actionType = VerificationEvent.ACTION_CLUSTER_MERGED),
            // alt: ohne conf/start
            VerificationEvent("a", VerificationStatus.UNVERIFIED, "merged:c|count:3|lastSeenAt:7000",
                verifiedAtMs = 7000L, actionType = VerificationEvent.ACTION_CLUSTER_MERGED),
            // ganz alt: ohne lastSeenAt
            VerificationEvent("a", VerificationStatus.UNVERIFIED, "merged:d|count:4",
                verifiedAtMs = 8000L, actionType = VerificationEvent.ACTION_CLUSTER_MERGED),
            // andere Nutzlast mit gleichem Muster wird nicht angefasst
            VerificationEvent("a", VerificationStatus.CORRECTED, "lastSeenAt:1", verifiedAtMs = 9000L, actionType = "CORRECTED")
        )
        val ev = SessionTextParser.events(SessionInfoEdit.eventsText(text, d))
        assertEquals(1000L + d, ev[0].verifiedAtMs)
        assertEquals("merged:b|count:2|lastSeenAt:${5000 + d}|conf:0.8|start:12.5", ev[1].correctedSpecies)
        assertEquals(6000L + d, ev[1].verifiedAtMs)
        assertEquals("merged:c|count:3|lastSeenAt:${7000 + d}", ev[2].correctedSpecies)
        assertEquals("merged:d|count:4", ev[3].correctedSpecies)
        assertEquals("lastSeenAt:1", ev[4].correctedSpecies)
        assertEquals(9000L + d, ev[4].verifiedAtMs)
    }

    @Test fun track_time_shifted() {
        val text = "{\"time\":\"2026-04-20T14:30:00+02:00\",\"lat\":47.0,\"lon\":8.0}\n"
        val out = SessionInfoEdit.trackText(text, h, zone)
        assertEquals("{\"time\":\"2026-04-20T15:30:00+02:00\",\"lat\":47.0,\"lon\":8.0}\n", out)
    }

    @Test fun format_delta() {
        assertEquals("+2 h 15 min", SessionInfoEdit.formatDelta(2 * h + 15 * 60_000L))
        assertEquals("-30 min", SessionInfoEdit.formatDelta(-30 * 60_000L))
        assertEquals("+1 d 3 h", SessionInfoEdit.formatDelta(27 * h))
        assertEquals("+5 s", SessionInfoEdit.formatDelta(5_000L))
        assertEquals("0 min", SessionInfoEdit.formatDelta(0L))
    }

    // ── Ort ──────────────────────────────────────────────────────────

    @Test fun location_rule() {
        val moved = setOf("m")
        // eigener Fix bleibt
        assertTrue(SessionInfoEdit.keepsOwnPosition("a", 46.5, 7.5, 8f, 47.0, 8.0, moved))
        // POSITION_MOVED bleibt (auch ohne Position)
        assertTrue(SessionInfoEdit.keepsOwnPosition("m", null, null, null, 47.0, 8.0, moved))
        // ohne Position -> neu
        assertFalse(SessionInfoEdit.keepsOwnPosition("b", null, null, null, 47.0, 8.0, moved))
        // vom Session-Ort uebernommen (ohne Genauigkeit) -> neu
        assertFalse(SessionInfoEdit.keepsOwnPosition("c", 47.0, 8.0, null, 47.0, 8.0, moved))
        // eigene abweichende Position ohne Genauigkeit (manuell/Route) bleibt
        assertTrue(SessionInfoEdit.keepsOwnPosition("d", 46.9, 8.1, null, 47.0, 8.0, moved))
        // alter Session-Ort fehlte: vorhandene Position bleibt
        assertTrue(SessionInfoEdit.keepsOwnPosition("e", 46.9, 8.1, null, null, null, moved))
    }

    @Test fun relocate_detections_by_rule() {
        val p = plan("2026-04-20T14:30:00+02:00", lat = 46.0, lon = 7.0)
        assertTrue(p.relocate)
        assertEquals(46.0, p.metadata.latitude!!, 0.0)
        val text = lines(
            det("fix", 1L, 46.5, 7.5, acc = 5f),
            det("moved", 2L, 47.0, 8.0),
            det("none", 3L),
            det("session", 4L, 47.0, 8.0),
            det("manual", 5L, 46.9, 8.1)
        )
        val events = evLines(VerificationEvent("moved", VerificationStatus.UNVERIFIED, verifiedAtMs = 9L,
            actionType = VerificationEvent.ACTION_POSITION_MOVED, newValue = "47.1,8.1"))
        val out = parse(SessionInfoEdit.detectionsText(text, p, SessionInfoEdit.movedIds(events))).associateBy { it.id }
        assertEquals(46.5, out["fix"]!!.latitude!!, 0.0)
        assertEquals(47.0, out["moved"]!!.latitude!!, 0.0)
        assertEquals(46.0, out["none"]!!.latitude!!, 0.0); assertEquals(7.0, out["none"]!!.longitude!!, 0.0)
        assertEquals(46.0, out["session"]!!.latitude!!, 0.0)
        assertEquals(46.9, out["manual"]!!.latitude!!, 0.0)
        // Zeit unveraendert (delta 0)
        assertEquals(4L, out["session"]!!.timestampMs)
    }

    @Test fun remove_location_clears_session_and_detections_without_fix() {
        val p = plan("2026-04-20T14:30:00+02:00", lat = null, lon = null)
        assertTrue(p.relocate)
        assertNull(p.metadata.latitude); assertNull(p.metadata.longitude)
        val text = lines(det("session", 4L, 47.0, 8.0), det("fix", 1L, 46.5, 7.5, acc = 5f))
        val out = parse(SessionInfoEdit.detectionsText(text, p, emptySet())).associateBy { it.id }
        assertNull(out["session"]!!.latitude); assertNull(out["session"]!!.longitude)
        assertEquals(46.5, out["fix"]!!.latitude!!, 0.0)
    }

    @Test fun same_location_is_no_relocation() {
        val p = plan("2026-04-20T14:30:00+02:00", lat = 47.0, lon = 8.0)
        assertFalse(p.relocate)
    }

    // ── Ausfuehrung, Reihenfolge, Idempotenz ─────────────────────────

    private fun writeSession(files: FileSessionFiles) {
        files.writeReplacing("session.json", SessionJson.pretty.encodeToString(meta()))
        files.writeReplacing("detections.jsonl", lines(det("a", 1000L, 47.0, 8.0), det("b", 2000L, 46.5, 7.5, acc = 3f)))
        files.writeReplacing("verifications.jsonl", evLines(
            VerificationEvent("a", VerificationStatus.CONFIRMED, verifiedAtMs = 1500L, actionType = "CONFIRMED")))
        files.writeReplacing("detections.rescan-20260421-100000.jsonl", lines(det("r", 3000L, 47.0, 8.0)))
        files.writeReplacing("verifications.rescan-20260421-100000.jsonl", "")
        files.writeReplacing("rescan-20260421-100000.json", "{}")
        // Variante ohne Metadaten (unsichtbar, abgebrochen) wird nicht angefasst
        files.writeReplacing("detections.rescan-20260422-100000.jsonl", lines(det("z", 3000L)))
        files.writeReplacing("track.jsonl", "{\"time\":\"2026-04-20T14:30:00+02:00\",\"lat\":47.0,\"lon\":8.0}\n")
    }

    private fun loadMeta(files: SessionFiles) =
        SessionJson.lenient.decodeFromString<SessionMetadata>(files.readText("session.json")!!)

    @Test fun execute_shifts_all_files_and_writes_session_json_last() {
        val files = FileSessionFiles(dir)
        writeSession(files)
        val p = SessionInfoEdit.prepare(files, plan("2026-04-20T15:30:00+02:00", 46.0, 7.0), "session.json")
        assertTrue(files.exists(SessionInfoEdit.JOURNAL_FILE))
        assertEquals(
            setOf("detections.jsonl", "verifications.jsonl", "detections.rescan-20260421-100000.jsonl",
                "verifications.rescan-20260421-100000.jsonl", "track.jsonl"),
            p.originalHashes.keys
        )
        SessionInfoEdit.execute(files, p, "session.json")
        assertFalse(files.exists(SessionInfoEdit.JOURNAL_FILE))
        val d = parse(files.readText("detections.jsonl")!!).associateBy { it.id }
        assertEquals(1000L + h, d["a"]!!.timestampMs)
        assertEquals(46.0, d["a"]!!.latitude!!, 0.0)
        assertEquals(46.5, d["b"]!!.latitude!!, 0.0)
        assertEquals(1500L + h, SessionTextParser.events(files.readText("verifications.jsonl")!!)[0].verifiedAtMs)
        assertEquals(3000L + h, parse(files.readText("detections.rescan-20260421-100000.jsonl")!!)[0].timestampMs)
        assertEquals(3000L, parse(files.readText("detections.rescan-20260422-100000.jsonl")!!)[0].timestampMs)
        assertTrue(files.readText("track.jsonl")!!.contains("15:30:00+02:00"))
        assertEquals("{}", files.readText("rescan-20260421-100000.json"))
        val m = loadMeta(files)
        assertEquals("2026-04-20T15:30:00+02:00", m.startedAt)
        assertEquals(46.0, m.latitude!!, 0.0)
    }

    @Test fun resume_after_abort_never_shifts_twice() {
        val files = FileSessionFiles(dir)
        writeSession(files)
        val p = SessionInfoEdit.prepare(files, plan("2026-04-20T15:30:00+02:00", 46.0, 7.0), "session.json")
        // Abbruch nach der ersten Datei: nur detections.jsonl umgerechnet, session.json noch alt
        files.writeReplacing("detections.jsonl",
            SessionInfoEdit.transform("detections.jsonl", files.readText("detections.jsonl")!!, p, files.readText("verifications.jsonl")))
        assertEquals("2026-04-20T14:30:00+02:00", loadMeta(files).startedAt)

        val resumed = SessionInfoEdit.resumePending(files, "session.json")
        assertNotNull(resumed)
        assertEquals(1000L + h, parse(files.readText("detections.jsonl")!!).first { it.id == "a" }.timestampMs)
        assertEquals(1500L + h, SessionTextParser.events(files.readText("verifications.jsonl")!!)[0].verifiedAtMs)
        assertEquals("2026-04-20T15:30:00+02:00", loadMeta(files).startedAt)
        assertFalse(files.exists(SessionInfoEdit.JOURNAL_FILE))

        // Nochmal ausfuehren (z.B. Abbruch vor dem Loeschen des Auftrags): keine weitere Verschiebung
        files.writeReplacing(SessionInfoEdit.JOURNAL_FILE, SessionInfoEdit.encodePlan(p))
        SessionInfoEdit.resumePending(files, "session.json")
        assertEquals(1000L + h, parse(files.readText("detections.jsonl")!!).first { it.id == "a" }.timestampMs)
        assertEquals("2026-04-20T15:30:00+02:00", loadMeta(files).startedAt)
    }

    @Test fun stale_journal_does_not_overwrite_later_session_json() {
        val files = FileSessionFiles(dir)
        writeSession(files)
        val p = SessionInfoEdit.prepare(files, plan("2026-04-20T15:30:00+02:00"), "session.json")
        SessionInfoEdit.execute(files, p, "session.json")
        // spaeter Bemerkung geaendert, dann taucht der alte Auftrag nochmal auf
        files.writeReplacing("session.json", SessionJson.pretty.encodeToString(loadMeta(files).copy(note = "neu")))
        files.writeReplacing(SessionInfoEdit.JOURNAL_FILE, SessionInfoEdit.encodePlan(p))
        SessionInfoEdit.resumePending(files, "session.json")
        assertEquals("neu", loadMeta(files).note)
    }

    @Test fun note_only_writes_no_journal() {
        val files = FileSessionFiles(dir)
        writeSession(files)
        val before = files.readText("detections.jsonl")
        val p = SessionInfoEdit.prepare(files,
            SessionInfoEdit.plan(meta(), ZonedDateTime.parse("2026-04-20T14:30:00+02:00"), 47.0, 8.0, "  Waldrand ", zone)!!,
            "session.json")
        assertFalse(files.exists(SessionInfoEdit.JOURNAL_FILE))
        SessionInfoEdit.execute(files, p, "session.json")
        assertEquals(before, files.readText("detections.jsonl"))
        assertEquals("Waldrand", loadMeta(files).note)
    }

    @Test fun unreadable_journal_is_discarded() {
        val files = FileSessionFiles(dir)
        writeSession(files)
        files.writeReplacing(SessionInfoEdit.JOURNAL_FILE, "kaputt")
        assertNull(SessionInfoEdit.resumePending(files, "session.json"))
        assertFalse(files.exists(SessionInfoEdit.JOURNAL_FILE))
    }
}
