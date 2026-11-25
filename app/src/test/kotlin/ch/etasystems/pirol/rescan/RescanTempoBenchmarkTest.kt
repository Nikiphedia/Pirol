package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.audio.dsp.AudioResampler
import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.BirdNetV3Classifier
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.InferenceWorker
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * T282: JVM-Messung der Rescan-Stufen ohne ONNX (Resampling, DSP, Lesen, Nachbearbeitung, Kette).
 * Reine Messung, prueft nur grobe Obergrenzen (damit der Test auf langsamen Rechnern nicht kippt).
 * Ergebnisse stehen in der Testausgabe (Praefix `T282_BENCH`), PC-Werte; Tablet grob Faktor 3-6 langsamer.
 */
class RescanTempoBenchmarkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sr = 48000

    /** Median in ms ueber [runs] Laeufe nach [warmup] Aufwaermlaeufen */
    private inline fun medianMs(warmup: Int = 5, runs: Int = 21, block: () -> Unit): Double {
        repeat(warmup) { block() }
        val t = DoubleArray(runs) {
            val t0 = System.nanoTime()
            block()
            (System.nanoTime() - t0) / 1e6
        }
        t.sort()
        return t[runs / 2]
    }

    private fun noisePcm(seconds: Double, seed: Int): ShortArray {
        val rnd = Random(seed)
        return ShortArray((seconds * sr).toInt()) { (rnd.nextInt(-8000, 8000)).toShort() }
    }

    @Test
    fun resamplerAndDsp_perWindow() {
        val pcm3 = noisePcm(3.0, 1)
        val pcm5 = noisePcm(5.0, 2)
        val poly3 = medianMs { AudioResampler.resample(pcm3, sr, 32000, AudioResampler.Mode.POLYPHASE) }
        val lin3 = medianMs { AudioResampler.resample(pcm3, sr, 32000, AudioResampler.Mode.LINEAR) }
        val poly5 = medianMs { AudioResampler.resample(pcm5, sr, 32000, AudioResampler.Mode.POLYPHASE) }
        val prep3 = medianMs {
            InferenceWorker.prepareInput(pcm3, sr, 32000, AudioResampler.Mode.POLYPHASE, highpass = true)
        }
        val prep5 = medianMs {
            InferenceWorker.prepareInput(pcm5, sr, 32000, AudioResampler.Mode.POLYPHASE, highpass = true)
        }
        println("T282_BENCH resample_3s poly=%.2f ms linear=%.2f ms taps=%d".format(
            poly3, lin3, AudioResampler.tapsFor(sr, 32000)))
        println("T282_BENCH resample_5s poly=%.2f ms".format(poly5))
        println("T282_BENCH prepareInput(resample+highpass+norm+limit) 3s=%.2f ms 5s=%.2f ms".format(prep3, prep5))
        assertTrue(prep3 < 500.0)
    }

    @Test
    fun postProcessing_scores() {
        // BirdNET 3.0 final: 11'725 Klassen; Rang-Auswahl wie im Klassifizierer (Untergrenze 0.10)
        val n = 11_725
        val rnd = Random(3)
        val scores = FloatArray(n) { rnd.nextFloat() * 0.12f }
        val labels = List(n) { "Species_$it" to "Art $it" }
        val ms = medianMs { BirdNetV3Classifier.filterAndRank(scores, labels, 10, 0.10f, 3f) }
        // Kopie der Scores wie beim Lesen des ORT-Ergebnisses (float[1][N] -> FloatArray)
        val nested = arrayOf(scores.copyOf())
        val copy = medianMs { nested[0].copyOf() }
        println("T282_BENCH filterAndRank n=%d ms=%.3f copy=%.4f ms".format(n, ms, copy))
        assertTrue(ms < 200.0)
    }

    /** Fake ohne Rechenzeit: misst alles am Rescan ausser der ONNX-Inferenz */
    private class InstantClassifier : AudioClassifier {
        override fun isModelAvailable() = true
        override fun classify(samples: FloatArray, sampleRate: Int, topK: Int, threshold: Float) = listOf(
            AudioClassifier.ClassifierResult("Turdus_merula", "Turdus merula", 0.9f, 0f, 3f),
            AudioClassifier.ClassifierResult("Fringilla_coelebs", "Fringilla coelebs", 0.6f, 0f, 3f),
            AudioClassifier.ClassifierResult("Erithacus_rubecula", "Erithacus rubecula", 0.2f, 0f, 3f)
        ).filter { it.confidence >= threshold }.take(topK)
        override fun extractEmbedding(samples: FloatArray, sampleRate: Int): FloatArray? = null
        override fun resetSession() {}
        override val labelCount = 3
        override val modelName = "instant"
        override val modelId = "instant_model"
    }

    private fun dummyRegionFilter(): RegionalSpeciesFilter {
        val cls = Class.forName("sun.misc.Unsafe")
        val unsafe = cls.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return cls.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, RegionalSpeciesFilter::class.java) as RegionalSpeciesFilter
    }

    private fun writeWav(file: File, seconds: Int) {
        val pcm = noisePcm(seconds.toDouble(), 7)
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        file.writeBytes(ExcerptWav.header(pcm.size, sr) + data.array())
    }

    @Test
    fun rescanChain_withoutOnnx() = runBlocking {
        val dir = tmp.newFolder("2026-11-25T10-00-00_bench1")
        val seconds = 180
        writeWav(File(dir, "recording.wav"), seconds)
        val rec = SegmentedRecording.forSession(dir)!!

        // Lesen allein (RandomAccessFile je Fenster, wie FileSegment)
        val starts = RescanPlan.windowStarts(rec.totalFrames, sr, 3000, 50)
        val windowFrames = sr * 3L
        val readMs = medianMs(warmup = 1, runs = 5) { for (s in starts) rec.readFrames(s, s + windowFrames) } / starts.size

        for (overlap in intArrayOf(0, 50)) {
            val runner = RescanRunner(
                classifier = InstantClassifier(),
                regionalFilter = dummyRegionFilter(),
                config = InferenceConfig(confidenceThreshold = 0.5f, regionFilter = null,
                    highpassEnabled = true, overlapPercent = overlap),
                resolveName = { it },
                clusterEnabled = true,
                clusterRadiusM = 0,
                clusterTimeWindowMin = 5
            )
            runner.run(rec, 1_000_000L, { null })  // Aufwaermen
            val t0 = System.nanoTime()
            val result = runner.run(rec, 1_000_000L, { null })
            val totalMs = (System.nanoTime() - t0) / 1e6
            println("T282_BENCH chain_without_onnx overlap=%d windows=%d total=%.1f ms per_window=%.2f ms audio=%d s rtf=%.5f"
                .format(overlap, result.windows, totalMs, totalMs / result.windows, seconds, totalMs / 1000.0 / seconds))
            assertTrue(result.windows > 0)
        }
        println("T282_BENCH readFrames_3s per_window=%.3f ms".format(readMs))
    }
}
