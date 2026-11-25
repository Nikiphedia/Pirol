package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.SecondaryChecker
import ch.etasystems.pirol.ml.VerificationEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * T289: Zweitmodell parallel zum Hauptmodell. Die Ereignisse (neu, Update, Merge) muessen unabhaengig von der
 * Rechenzeit des Zweitmodells in derselben Reihenfolge und mit denselben Werten ankommen.
 */
class RescanSecondaryParallelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sr = 48000

    private class Main : AudioClassifier {
        override fun isModelAvailable() = true
        override fun classify(samples: FloatArray, sampleRate: Int, topK: Int, threshold: Float) = listOf(
            AudioClassifier.ClassifierResult("Turdus_merula", "Turdus merula", 0.9f, 0f, 3f),
            AudioClassifier.ClassifierResult("Erithacus_rubecula", "Erithacus rubecula", 0.6f, 0f, 3f)
        ).filter { it.confidence >= threshold }.take(topK)
        override fun extractEmbedding(samples: FloatArray, sampleRate: Int): FloatArray? = null
        override fun resetSession() {}
        override val labelCount = 2
        override val modelName = "fake"
        override val modelId = "fake_model"
    }

    /** Antwortet je Aufruf mit steigendem Score (sichtbare Reihenfolge) nach zufaelliger Wartezeit */
    private class Second(private val maxDelayMs: Long) : AudioClassifier {
        private val rnd = Random(7)
        var calls = 0
        override fun isModelAvailable() = true
        override fun classify(samples: FloatArray, sampleRate: Int, topK: Int, threshold: Float): List<AudioClassifier.ClassifierResult> {
            val n = ++calls
            if (maxDelayMs > 0) Thread.sleep(rnd.nextInt(maxDelayMs.toInt() + 1).toLong())
            return listOf(
                AudioClassifier.ClassifierResult("Turdus_merula", "Turdus merula", 0.5f + n / 100f, 0f, 3f),
                AudioClassifier.ClassifierResult("Erithacus_rubecula", "Erithacus rubecula", 0.5f + n / 100f, 0f, 3f)
            )
        }
        override fun extractEmbedding(samples: FloatArray, sampleRate: Int): FloatArray? = null
        override fun resetSession() {}
        override val labelCount = 2
        override val modelName = "fake2"
        override val modelId = "fake_second"
        override val inputSampleRate = 48000
        override val labelScientificNames = setOf("Turdus_merula", "Erithacus_rubecula")
    }

    private class Log : RescanRunner.Sink {
        val events = mutableListOf<String>()
        override fun onNew(detection: DetectionResult) { events += "new ${detection.id.length > 0} ${detection.scientificName} ${detection.chunkStartSec}" }
        override fun onUpdate(detection: DetectionResult) {
            events += "upd ${detection.scientificName} start=${detection.chunkStartSec} sec=${detection.secondaryStatus}/${detection.secondaryConfidence}"
        }
        override fun onMerge(event: VerificationEvent) { events += "merge ${event.correctedSpecies?.substringAfter("|start:")}" }
    }

    private fun filter(): RegionalSpeciesFilter {
        val cls = Class.forName("sun.misc.Unsafe")
        val unsafe = cls.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return cls.getMethod("allocateInstance", Class::class.java).invoke(unsafe, RegionalSpeciesFilter::class.java) as RegionalSpeciesFilter
    }

    private fun recording(seconds: Int): SegmentedRecording {
        val dir = tmp.newFolder()
        val n = seconds * sr
        val data = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) data.putShort((((i) % 2000) - 1000).toShort())
        File(dir, "recording.wav").writeBytes(ExcerptWav.header(n, sr) + data.array())
        return SegmentedRecording.forSession(dir)!!
    }

    private fun scan(delayMs: Long, cluster: Boolean): Pair<List<String>, RescanRunner.Result> = runBlocking {
        val log = Log()
        val checker = SecondaryChecker(Second(delayMs), 0.5f) { _, _ -> }
        val result = RescanRunner(
            classifier = Main(), regionalFilter = filter(),
            config = InferenceConfig(confidenceThreshold = 0.5f, regionFilter = null, highpassEnabled = false),
            resolveName = { it }, clusterEnabled = cluster, clusterRadiusM = 0, clusterTimeWindowMin = if (cluster) 5 else 0,
            secondary = checker, secondaryOverlapPercent = 0, threads = 4
        ).run(recording(30), 1_000_000L, { null }, sink = log)
        log.events to result
    }

    @Test
    fun updatesKeepWindowOrderRegardlessOfSecondarySpeed() {
        for (cluster in listOf(false, true)) {
            val (fast, fastResult) = scan(0, cluster)
            val (slow, slowResult) = scan(25, cluster)
            assertEquals("cluster=$cluster", fast, slow)
            assertEquals(fastResult.detections.map { it.secondaryConfidence }, slowResult.detections.map { it.secondaryConfidence })
            assertEquals(fastResult.windows, slowResult.windows)
            assertTrue(fast.any { it.startsWith("upd") })
        }
    }

    @Test
    fun updatesOfAWindowComeBeforeNewDetectionsOfTheNext() {
        val (events, _) = scan(15, cluster = false)
        // Ohne Cluster: je Fenster zwei neue Detektionen, danach zwei Updates, dann das naechste Fenster
        val starts = events.filter { it.startsWith("upd") }.map { it.substringAfter("start=").substringBefore(" ").toFloat() }
        assertEquals(starts.sorted(), starts)
        val firstUpdate = events.indexOfFirst { it.startsWith("upd") }
        val newAfter = events.drop(firstUpdate).first { it.startsWith("new") }
        assertTrue(newAfter.endsWith("3.0"))
    }

    @Test
    fun statsLineHasAllParts() {
        val s = RescanStats().apply { windows = 10; readNs = 1_000_000; primaryNs = 500_000_000; secondaryNs = 300_000_000 }
        val line = s.format(1_000_000_000L, 30f, 4, true)
        assertTrue(line.startsWith("T282_RESCAN_STATS windows=10 total_ms=1000"))
        assertTrue(line.contains("rtf=0.033"))
        assertTrue(line.contains("primary_ms=500 (50.0%)"))
    }
}
