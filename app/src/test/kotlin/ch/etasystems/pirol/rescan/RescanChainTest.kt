package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.data.repository.FileSessionFiles
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * T221: Rescan-Kette mit Fake-Klassifizierer ueber eine Session mit zwei WAV-Segmenten:
 * Fenster ueber die Segmentgrenze, Cluster wie live, Verifikations-Uebernahme, Variante schreiben,
 * auflisten, loeschen — Original-Dateien bleiben byte-gleich (AC1).
 */
class RescanChainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sr = 48000
    private val json = Json { encodeDefaults = false }

    /** Meldet in jedem Fenster Amsel (0.9) und Rotkehlchen (0.4); zaehlt die Aufrufe. */
    private class FakeClassifier : AudioClassifier {
        var calls = 0
        override fun isModelAvailable() = true
        override fun classify(samples: FloatArray, sampleRate: Int, topK: Int, threshold: Float): List<AudioClassifier.ClassifierResult> {
            calls++
            return listOf(
                AudioClassifier.ClassifierResult("Turdus_merula", "Turdus merula", 0.9f, 0f, 3f),
                AudioClassifier.ClassifierResult("Erithacus_rubecula", "Erithacus rubecula", 0.4f, 0f, 3f)
            ).filter { it.confidence >= threshold }.take(topK)
        }
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

    private fun writeWav(file: File, seconds: Int, seed: Int) {
        val n = seconds * sr
        val data = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) data.putShort((((i + seed) % 2000) - 1000).toShort())
        file.writeBytes(ExcerptWav.header(n, sr) + data.array())
    }

    private fun session(): File {
        val dir = tmp.newFolder("2026-11-23T10-00-00_abc123")
        writeWav(File(dir, "recording.wav"), 7, 0)        // Segment 1: 7 s
        writeWav(File(dir, "recording-002.wav"), 5, 99)   // Segment 2: 5 s → Session 12 s
        File(dir, "session.json").writeText("""{"sessionId":"S","startedAt":"2026-11-23T10:00:00+01:00","sampleRate":48000,"confidenceThreshold":0.5}""" + "\n")
        val originals = listOf(
            DetectionResult("o1", "Turdus_merula", "Amsel", 0.8f, 1_000_000L, 0f, 3f, sr),
            DetectionResult("o2", "Erithacus_rubecula", "Rotkehlchen", 0.7f, 1_006_000L, 6f, 9f, sr)
        )
        File(dir, "detections.jsonl").writeText(originals.joinToString("") { json.encodeToString(it) + "\n" })
        File(dir, "verifications.jsonl").writeText(
            json.encodeToString(VerificationEvent("o1", VerificationStatus.CONFIRMED, verifiedAtMs = 42L)) + "\n"
        )
        File(dir, "recording.selections.txt").writeText("Selection\tView\n1\tSpectrogram 1\n")
        return dir
    }

    private fun snapshot(dir: File): Map<String, ByteArray> =
        dir.listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes() }

    private fun originalsWithVerifications(dir: File): List<DetectionResult> {
        val dets = File(dir, "detections.jsonl").readLines().filter { it.isNotBlank() }
            .map { Json.decodeFromString<DetectionResult>(it) }.toMutableList()
        File(dir, "verifications.jsonl").readLines().filter { it.isNotBlank() }
            .map { Json.decodeFromString<VerificationEvent>(it) }
            .forEach { v ->
                val i = dets.indexOfFirst { it.id == v.detectionId }
                if (i >= 0) dets[i] = dets[i].copy(verificationStatus = v.status, verifiedAtMs = v.verifiedAtMs)
            }
        return dets
    }

    private fun runner(cluster: Boolean, overlap: Int = 0) = RescanRunner(
        classifier = FakeClassifier(),
        regionalFilter = dummyRegionFilter(),
        config = InferenceConfig(confidenceThreshold = 0.5f, regionFilter = null, highpassEnabled = false, overlapPercent = overlap),
        resolveName = { if (it == "Turdus_merula") "Amsel" else it },
        clusterEnabled = cluster,
        clusterRadiusM = 0,
        clusterTimeWindowMin = if (cluster) 5 else 0
    )

    @Test
    fun rescan_writesVariant_originalsByteIdentical() = runBlocking {
        val dir = session()
        val before = snapshot(dir)
        val rec = SegmentedRecording.forSession(dir)!!
        assertEquals(12f, rec.durationSec, 0.001f)

        val epoch0 = RescanPlan.epochAtZero(originalsWithVerifications(dir), null)!!
        assertEquals(1_000_000L, epoch0)
        var lastProgress = 0f
        val result = runner(cluster = false).run(rec, epoch0, { 47.0 to 8.0 }, onProgress = { lastProgress = it })

        // 12 s, 3-s-Fenster ohne Overlap: 0, 3, 6 (ueber die Segmentgrenze bei 7 s), 9
        assertEquals(4, result.windows)
        assertEquals(1f, lastProgress, 0f)
        assertEquals(listOf(0f, 3f, 6f, 9f), result.detections.map { it.chunkStartSec })
        assertTrue(result.detections.all { it.scientificName == "Turdus_merula" && it.commonName == "Amsel" })
        // Rotkehlchen unter der Schwelle → Kandidat der Amsel (wie live)
        assertEquals("Erithacus_rubecula", result.detections.first().candidates.single().scientificName)
        assertEquals(1_006_000L, result.detections[2].timestampMs)  // Wanduhr aus der Session-Achse
        assertEquals(47.0, result.detections.first().latitude!!, 0.0)

        val transfer = RescanVerification.transfer(result.detections, originalsWithVerifications(dir), 1L)
        assertEquals(listOf(result.detections[0].id), transfer.map { it.detectionId })  // nur o1 (Amsel 0-3 s)

        val files = FileSessionFiles(dir)
        val info = RescanInfo(
            id = "20261123-143000", createdAt = "2026-11-23T14:30:00+01:00", modelId = "fake_model", modelName = "fake",
            regionFilter = null, confidenceThreshold = 0.5f, overlapPercent = 0,
            detectionCount = result.detections.size, transferredVerifications = transfer.size
        )
        // T260: Variante wie die Engine schreiben: anlegen, Detektionen anhaengen, Uebernahme, Metadaten
        RescanStore.begin(files, info.copy(status = RescanStatus.RUNNING))
        files.append(RescanFiles.detections(info.id), result.detections.joinToString("") { json.encodeToString(it) + "\n" })
        files.append(RescanFiles.verifications(info.id), (result.mergeEvents + transfer).joinToString("") { json.encodeToString(it) + "\n" })
        RescanStore.writeMeta(files, info)

        // AC1: alle Original-Dateien byte-gleich, nur Variantendateien dazu
        val after = snapshot(dir)
        for ((name, bytes) in before) assertArrayEquals(name, bytes, after.getValue(name))
        assertEquals(
            setOf("detections.rescan-20261123-143000.jsonl", "verifications.rescan-20261123-143000.jsonl", "rescan-20261123-143000.json"),
            after.keys - before.keys
        )
        // Variante lesbar wie das Original
        val variantDets = File(dir, RescanFiles.detections(info.id)).readLines().filter { it.isNotBlank() }
            .map { Json.decodeFromString<DetectionResult>(it) }
        assertEquals(4, variantDets.size)
        assertTrue(variantDets.all { it.verificationStatus == VerificationStatus.UNVERIFIED })  // Rohdaten
        val variantEvents = File(dir, RescanFiles.verifications(info.id)).readLines().filter { it.isNotBlank() }
            .map { Json.decodeFromString<VerificationEvent>(it) }
        assertEquals(VerificationStatus.CONFIRMED, variantEvents.single().status)

        val listed = RescanStore.list(files)
        assertEquals(listOf(info), listed)

        // Loeschen entfernt nur die Variante
        assertTrue(RescanStore.delete(files, info.id))
        val afterDelete = snapshot(dir)
        assertEquals(before.keys, afterDelete.keys)
        for ((name, bytes) in before) assertArrayEquals(name, bytes, afterDelete.getValue(name))
        assertTrue(RescanStore.list(files).isEmpty())
    }

    @Test
    fun rescan_clusterUsesRecordingTime_mergeEventsLikeLive() = runBlocking {
        val dir = session()
        val rec = SegmentedRecording.forSession(dir)!!
        val result = runner(cluster = true, overlap = 50).run(rec, 1_000_000L, { null })
        // 50 %: Hop 1.5 s → Starts 0 .. 9 s = 7 Fenster; alle Amsel-Treffer innerhalb 5 min → ein Cluster
        assertEquals(7, result.windows)
        val anchor = result.detections.single()
        assertEquals(7, anchor.detectionCount)
        assertEquals(0f, anchor.chunkStartSec, 0f)
        assertEquals(1_009_000L, anchor.lastSeenAtMs)
        assertEquals(6, result.mergeEvents.size)
        assertTrue(result.mergeEvents.all { it.actionType == VerificationEvent.ACTION_CLUSTER_MERGED && it.detectionId == anchor.id })
        assertTrue(result.mergeEvents.last().correctedSpecies!!.startsWith("merged:"))
        assertTrue(result.mergeEvents.last().correctedSpecies!!.endsWith("|start:9.000"))
    }

    /** Zweitmodell kennt Amsel mit 0.7, Rotkehlchen nicht; Fenster 3 s wie das Hauptmodell */
    private class FakeSecondary : AudioClassifier {
        var calls = 0
        override fun isModelAvailable() = true
        override fun classify(samples: FloatArray, sampleRate: Int, topK: Int, threshold: Float): List<AudioClassifier.ClassifierResult> {
            calls++
            return listOf(AudioClassifier.ClassifierResult("Turdus_merula", "Turdus merula", 0.7f, 0f, 3f))
        }
        override fun extractEmbedding(samples: FloatArray, sampleRate: Int): FloatArray? = null
        override fun resetSession() {}
        override val labelCount = 1
        override val modelName = "fake2"
        override val modelId = "fake_second"
        override val inputSampleRate = 48000
        override val labelScientificNames = setOf("Turdus_merula")
    }

    @Test
    fun rescan_secondaryModelWritesSecondaryFields() = runBlocking {
        val dir = session()
        val rec = SegmentedRecording.forSession(dir)!!
        val second = FakeSecondary()
        val checker = ch.etasystems.pirol.ml.SecondaryChecker(second, 0.5f) { _, _ -> }
        val result = RescanRunner(
            classifier = FakeClassifier(),
            regionalFilter = dummyRegionFilter(),
            config = InferenceConfig(confidenceThreshold = 0.5f, regionFilter = null, highpassEnabled = false),
            resolveName = { if (it == "Turdus_merula") "Amsel" else it },
            clusterEnabled = false, clusterRadiusM = 0, clusterTimeWindowMin = 0,
            secondary = checker, secondaryOverlapPercent = 0
        ).run(rec, 1_000_000L, { null })
        assertEquals(4, result.detections.size)
        assertTrue(second.calls >= 1)
        // Jede Amsel-Detektion traegt das Ergebnis des Zweitmodells
        assertTrue(result.detections.all {
            it.secondaryModelId == "fake_second" && it.secondaryStatus == "CHECKED" && it.secondaryConfidence == 0.7f
        })
    }

    @Test
    fun rescan_withoutSecondaryLeavesFieldsNull() = runBlocking {
        val dir = session()
        val rec = SegmentedRecording.forSession(dir)!!
        val result = runner(cluster = false).run(rec, 1_000_000L, { null })
        assertTrue(result.detections.all { it.secondaryStatus == null && it.secondaryModelId == null })
    }

    @Test
    fun store_refusesExistingVariant() {
        val dir = session()
        val files = FileSessionFiles(dir)
        val info = RescanInfo(id = "20261123-143000", createdAt = "x", modelName = "fake", confidenceThreshold = 0.5f, overlapPercent = 0)
        RescanStore.begin(files, info)
        val failed = runCatching { RescanStore.begin(files, info) }.isFailure
        assertTrue(failed)
        assertFalse(File(dir, "detections.jsonl").readText().isEmpty())
    }
}
