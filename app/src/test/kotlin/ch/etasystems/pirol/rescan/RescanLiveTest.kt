package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.data.repository.DetectionUpdateLog
import ch.etasystems.pirol.data.repository.FileSessionFiles
import ch.etasystems.pirol.data.repository.SessionFiles
import ch.etasystems.pirol.data.repository.SessionJson
import ch.etasystems.pirol.ml.AlternativeSelection
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.DetectionCandidate
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * T260: Rescan schreibt die Variante waehrend des Scans (RUNNING, append-only, Flush), Abbruch/Kill
 * behalten das Teilergebnis, Updates des Rescans ueberschreiben keine Nutzer-Aenderungen.
 */
class RescanLiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sr = 48000
    private val id = "20261124-101500"

    private class FakeClassifier : AudioClassifier {
        override fun isModelAvailable() = true
        override fun classify(samples: FloatArray, sampleRate: Int, topK: Int, threshold: Float): List<AudioClassifier.ClassifierResult> =
            listOf(
                AudioClassifier.ClassifierResult("Turdus_merula", "Turdus merula", 0.9f, 0f, 3f),
                AudioClassifier.ClassifierResult("Erithacus_rubecula", "Erithacus rubecula", 0.6f, 0f, 3f)
            ).filter { it.confidence >= threshold }.take(topK)
        override fun extractEmbedding(samples: FloatArray, sampleRate: Int): FloatArray? = null
        override fun resetSession() {}
        override val labelCount = 2
        override val modelName = "fake"
        override val modelId = "fake_model"
    }

    private fun dummyRegionFilter(): RegionalSpeciesFilter {
        val cls = Class.forName("sun.misc.Unsafe")
        val unsafe = cls.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return cls.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, RegionalSpeciesFilter::class.java) as RegionalSpeciesFilter
    }

    private fun session(seconds: Int = 12): File {
        val dir = tmp.newFolder("2026-11-24T10-00-00_abc123")
        val n = seconds * sr
        val data = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) data.putShort(((i % 2000) - 1000).toShort())
        File(dir, "recording.wav").writeBytes(ExcerptWav.header(n, sr) + data.array())
        File(dir, "detections.jsonl").writeText("")
        return dir
    }

    private fun runner(cluster: Boolean) = RescanRunner(
        classifier = FakeClassifier(),
        regionalFilter = dummyRegionFilter(),
        config = InferenceConfig(confidenceThreshold = 0.5f, regionFilter = null, highpassEnabled = false, overlapPercent = 50),
        resolveName = { it },
        clusterEnabled = cluster,
        clusterRadiusM = 0,
        clusterTimeWindowMin = if (cluster) 5 else 0
    )

    private fun info(status: String = RescanStatus.RUNNING) = RescanInfo(
        id = id, createdAt = "2026-11-24T10:15:00+01:00", modelName = "fake",
        confidenceThreshold = 0.5f, overlapPercent = 50, status = status, scannedSec = 0f, totalSec = 12f
    )

    private fun variantDetections(files: SessionFiles): List<DetectionResult> =
        DetectionUpdateLog.mergedText(files, RescanFiles.detections(id))!!.lineSequence().filter { it.isNotBlank() }
            .map { SessionJson.lenient.decodeFromString<DetectionResult>(it) }.toList()

    private fun variantEvents(files: SessionFiles): List<VerificationEvent> =
        files.readText(RescanFiles.verifications(id)).orEmpty().lineSequence().filter { it.isNotBlank() }
            .map { SessionJson.lenient.decodeFromString<VerificationEvent>(it) }.toList()

    // ── Status ──────────────────────────────────────────────────────

    @Test
    fun status_transitions() {
        val running = info()
        // laeuft in diesem Prozess: bleibt RUNNING; sonst (Kill) ABORTED
        assertEquals(RescanStatus.RUNNING, RescanStatus.effective(running, id))
        assertEquals(RescanStatus.ABORTED, RescanStatus.effective(running, null))
        assertEquals(listOf(running), RescanStatus.stale(listOf(running, info(RescanStatus.DONE).copy(id = "x")), null))
        assertTrue(RescanStatus.stale(listOf(running), id).isEmpty())

        val done = RescanStatus.finished(running, RescanStatus.DONE, 12f, 4, 1)
        assertEquals(RescanStatus.DONE, done.status)
        assertEquals(12f, done.scannedSec!!, 0f)
        assertEquals(4, done.detectionCount)
        // Kill-Wiederherstellung: letzter Flush-Stand bleibt
        val aborted = RescanStatus.finished(running.copy(scannedSec = 125f), RescanStatus.ABORTED, null, 2, 0)
        assertEquals(RescanStatus.ABORTED, aborted.status)
        assertEquals(125f, aborted.scannedSec!!, 0f)
        assertEquals(2, RescanStatus.minutes(aborted.scannedSec))
        // DONE/ABORTED werden nie wieder angefasst
        assertEquals(RescanStatus.DONE, RescanStatus.effective(done, null))
        assertEquals(RescanStatus.ABORTED, RescanStatus.effective(aborted, null))
    }

    @Test
    fun oldMetadataWithoutStatus_isDone() {
        val old = SessionJson.lenient.decodeFromString<RescanInfo>(
            """{"id":"$id","createdAt":"x","modelName":"m","confidenceThreshold":0.5,"overlapPercent":0}"""
        )
        assertEquals(RescanStatus.DONE, old.status)
        assertNull(old.scannedSec)
    }

    @Test
    fun files_updatesFileBelongsToVariant() {
        assertEquals("detections.updates.jsonl", RescanFiles.updatesFor("detections.jsonl"))
        assertEquals("detections.updates.rescan-$id.jsonl", RescanFiles.updatesFor("detections.rescan-$id.jsonl"))
        assertNull(RescanFiles.updatesFor("verifications.jsonl"))
        assertTrue(RescanFiles.updates(id) in RescanFiles.filesOf(id))
        // Zusatzdatei ist keine Variante und keine Detektionsdatei
        assertTrue(RescanFiles.idsIn(listOf(RescanFiles.updates(id))).isEmpty())
        assertNull(RescanFiles.updatesFor(RescanFiles.updates(id)))
    }

    // ── Nutzer-Aenderungen vs. Rescan-Updates ───────────────────────

    @Test
    fun runnerUpdate_keepsUserFields() {
        val runnerOld = DetectionResult("a", "Turdus_merula", "Amsel", 0.6f, 1_000L, 0f, 3f, sr)
        val user = runnerOld.copy(
            verificationStatus = VerificationStatus.CONFIRMED, verifiedAtMs = 5L, count = 3,
            atlasCode = "A2", note = "Gesang", latitude = 47.0, longitude = 8.0
        )
        val runnerNew = runnerOld.copy(
            detectionCount = 4, lastSeenAtMs = 10_000L, confidence = 0.9f, bestStartSec = 6f, bestEndSec = 9f,
            secondaryModelId = "m2", secondaryStatus = "CHECKED", secondaryConfidence = 0.7f
        )
        val out = RescanLiveMerge.applyRunnerUpdate(user, runnerNew)
        assertEquals(VerificationStatus.CONFIRMED, out.verificationStatus)
        assertEquals(5L, out.verifiedAtMs)
        assertEquals(3, out.count)
        assertEquals("A2", out.atlasCode)
        assertEquals("Gesang", out.note)
        assertEquals(47.0, out.latitude!!, 0.0)
        assertEquals(4, out.detectionCount)
        assertEquals(10_000L, out.lastSeenAtMs)
        assertEquals(0.9f, out.confidence, 0f)
        assertEquals(6f, out.bestStartSec!!, 0f)
        assertEquals("CHECKED", out.secondaryStatus)
    }

    @Test
    fun runnerUpdate_afterAlternative_keepsChosenSpecies() {
        val runnerOld = DetectionResult(
            "a", "Turdus_merula", "Amsel", 0.6f, 1_000L, 0f, 3f, sr,
            candidates = listOf(DetectionCandidate("Turdus_philomelos", "Singdrossel", 0.4f))
        )
        val user = AlternativeSelection.apply(runnerOld, runnerOld.candidates.first(), 7L)
        val runnerNew = runnerOld.copy(detectionCount = 2, lastSeenAtMs = 4_000L, confidence = 0.95f, secondaryStatus = "CHECKED")
        val out = RescanLiveMerge.applyRunnerUpdate(user, runnerNew)
        assertEquals("Turdus_philomelos", out.scientificName)
        assertEquals(0.4f, out.confidence, 0f)          // Konfidenz der gewaehlten Art
        assertNull(out.secondaryStatus)                   // Zweitmodell galt der alten Art
        assertEquals(VerificationStatus.CONFIRMED, out.verificationStatus)
        assertEquals(2, out.detectionCount)               // Cluster waechst weiter
        assertEquals(4_000L, out.lastSeenAtMs)
    }

    /** Nutzer aendert die Variante waehrend des Scans (Weg von SessionManager.modifyDetectionIn mit variant). */
    private fun userModify(files: SessionFiles, detId: String, transform: (DetectionResult) -> DetectionResult) {
        val name = RescanFiles.detections(id)
        val current = DetectionUpdateLog.currentOf(DetectionUpdateLog.mergedText(files, name)!!, setOf(detId)).getValue(detId)
        DetectionUpdateLog.consolidate(files, mapOf(detId to transform(current)), name)
    }

    @Test
    fun liveWriter_userEditsSurviveLaterMerges() = runBlocking {
        val dir = session()
        val files = FileSessionFiles(dir)
        RescanStore.begin(files, info())
        val writer = RescanLiveWriter(id)
        val rec = SegmentedRecording.forSession(dir)!!
        var windows = 0
        var anchorId: String? = null
        runner(cluster = true).run(rec, 1_000_000L, { null }, sink = writer, onWindow = {
            windows++
            writer.flushLocked(files)
            if (windows == 2) {
                // Nutzer: Stueckzahl/Bemerkung in der Zeile, Bestaetigung als Event
                anchorId = variantDetections(files).first { it.scientificName == "Turdus_merula" }.id
                userModify(files, anchorId!!) { it.copy(count = 2, note = "zwei Amseln") }
                files.append(
                    RescanFiles.verifications(id),
                    SessionJson.compact.encodeToString(VerificationEvent(anchorId!!, VerificationStatus.CONFIRMED, verifiedAtMs = 9L)) + "\n"
                )
            }
        })
        writer.flushLocked(files)
        DetectionUpdateLog.consolidate(files, detectionsName = RescanFiles.detections(id))
        assertFalse(files.exists(RescanFiles.updates(id)))

        val anchor = variantDetections(files).single { it.id == anchorId }
        assertEquals(2, anchor.count)
        assertEquals("zwei Amseln", anchor.note)
        assertEquals(7, anchor.detectionCount)            // spaetere Merges kamen trotzdem an
        // Event des Nutzers steht, Uebernahme am Ende ueberspringt die angefasste Detektion
        val withVeri = ch.etasystems.pirol.data.repository.SessionTextParser.applyVerifications(variantDetections(files), variantEvents(files))
        assertEquals(VerificationStatus.CONFIRMED, withVeri.single { it.id == anchorId }.verificationStatus)
        val originals = listOf(
            DetectionResult("o1", "Turdus_merula", "Amsel", 0.8f, 1_000_000L, 0f, 3f, sr,
                verificationStatus = VerificationStatus.REJECTED, verifiedAtMs = 1L)
        )
        assertTrue(RescanVerification.transferUntouched(withVeri, originals, 2L).none { it.detectionId == anchorId })
        assertEquals(1, RescanVerification.transfer(withVeri, originals, 2L).count { it.detectionId == anchorId })
    }

    // ── Endstand gleich wie bisher (alles im Speicher) ──────────────

    @Test
    fun liveWriter_endStateEqualsResult() = runBlocking {
        for (cluster in listOf(true, false)) {
            for (every in listOf(1, 3, Int.MAX_VALUE)) {
                tmp.root.listFiles()?.forEach { it.deleteRecursively() }
                val dir = session()
                val files = FileSessionFiles(dir)
                RescanStore.begin(files, info())
                val writer = RescanLiveWriter(id)
                var n = 0
                val result = runner(cluster).run(SegmentedRecording.forSession(dir)!!, 1_000_000L, { null },
                    nowMs = { 42L }, sink = writer, onWindow = { if (++n % every == 0) writer.flushLocked(files) })
                writer.flushLocked(files)
                DetectionUpdateLog.consolidate(files, detectionsName = RescanFiles.detections(id))

                val label = "cluster=$cluster every=$every"
                assertEquals(label, result.detections, variantDetections(files))
                assertEquals(label, result.mergeEvents, variantEvents(files))
                assertEquals(label, result.detections.size, writer.detectionCount)
            }
        }
    }

    // ── Abbruch und Kill ────────────────────────────────────────────

    @Test
    fun abort_keepsPartialResult() = runBlocking {
        val dir = session()
        val files = FileSessionFiles(dir)
        RescanStore.begin(files, info())
        val writer = RescanLiveWriter(id)
        var n = 0
        val failed = runCatching {
            runner(cluster = false).run(SegmentedRecording.forSession(dir)!!, 1_000_000L, { null },
                isAborted = { n >= 3 }, sink = writer, onWindow = { n++ })
        }.exceptionOrNull()
        assertTrue(failed is RescanAbortedException)
        // Engine: letzter Flush, dann abschliessen
        writer.flushLocked(files)
        DetectionUpdateLog.consolidate(files, detectionsName = RescanFiles.detections(id))
        RescanStore.writeMeta(files, RescanStatus.finished(info(), RescanStatus.ABORTED, 6f, variantDetections(files).size, 0))
        val listed = RescanStore.list(files).single()
        assertEquals(RescanStatus.ABORTED, listed.status)
        assertEquals(6f, listed.scannedSec!!, 0f)
        // 3 Fenster (0, 1.5, 3 s) mit je Amsel + Rotkehlchen
        assertEquals(6, variantDetections(files).size)
    }

    @Test
    fun kill_leavesReadableRunningVariant_recoveredAsAborted() = runBlocking {
        val dir = session()
        val files = FileSessionFiles(dir)
        RescanStore.begin(files, info())
        val writer = RescanLiveWriter(id)
        var n = 0
        // "Kill": im 4. Fenster bricht alles ab, ohne letzten Flush und ohne Abschluss
        runCatching {
            runner(cluster = true).run(SegmentedRecording.forSession(dir)!!, 1_000_000L, { null }, sink = writer, onWindow = {
                n++
                if (n == 2) {
                    writer.flushLocked(files)
                    RescanStore.writeMeta(files, info().copy(scannedSec = it, detectionCount = writer.detectionCount))
                }
                if (n == 3) writer.flushLocked(files)   // Updates in der Zusatzdatei, Meta noch vom 2. Fenster
                if (n == 4) throw IllegalStateException("kill")
            })
        }
        // Teilergebnis lesbar (Hauptdatei + Zusatzdatei), Variante sichtbar
        assertTrue(files.exists(RescanFiles.updates(id)))
        val beforeRecovery = variantDetections(files)
        assertEquals(2, beforeRecovery.size)  // Amsel- und Rotkehlchen-Cluster
        assertEquals(3, beforeRecovery.first { it.scientificName == "Turdus_merula" }.detectionCount)

        // Neuer Prozess: nichts laeuft -> liegengeblieben -> ABORTED mit Stand des letzten Flushs
        val listed = RescanStore.list(files)
        val stale = RescanStatus.stale(listed, runningId = null).single()
        assertEquals(4.5f, stale.scannedSec!!, 0f)  // Fensterende des 2. Fensters (Start 1.5 s + 3 s)
        DetectionUpdateLog.consolidate(files, detectionsName = RescanFiles.detections(id))
        RescanStore.writeMeta(files, RescanStatus.finished(stale, RescanStatus.ABORTED, null, variantDetections(files).size, 0))
        assertFalse(files.exists(RescanFiles.updates(id)))
        assertEquals(beforeRecovery, variantDetections(files))
        assertEquals(RescanStatus.ABORTED, RescanStore.list(files).single().status)
        assertTrue(RescanStatus.stale(RescanStore.list(files), null).isEmpty())
    }
}
