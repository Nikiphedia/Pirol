package ch.etasystems.pirol.ml

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** T224: Warteschlange und Rechnung des Zweitmodells mit Fake-Klassifizierer (ohne ONNX). */
class SecondaryCheckerTest {

    private class FakeClassifier(
        private val results: List<AudioClassifier.ClassifierResult>,
        private val fail: Boolean = false
    ) : AudioClassifier {
        @Volatile var calls = 0
        @Volatile var lastSize = 0
        override fun isModelAvailable() = true
        override fun classify(samples: FloatArray, sampleRate: Int, topK: Int, threshold: Float): List<AudioClassifier.ClassifierResult> {
            calls++
            lastSize = samples.size
            if (fail) throw IllegalStateException("kaputt")
            return results.filter { it.confidence >= threshold }.take(topK)
        }
        override fun extractEmbedding(samples: FloatArray, sampleRate: Int): FloatArray? = null
        override fun resetSession() {}
        override val labelCount = 2
        override val modelName = "BirdNET 2.4 (Global 6K)"
        override val modelId = "birdnet24_global6k"
        override val inputSampleRate = 48000
        override val labelScientificNames = setOf("Turdus_merula", "Parus_major")
    }

    private fun cr(name: String, conf: Float) = AudioClassifier.ClassifierResult(name, name, conf, 0f, 3f)

    private fun window(start: Float, vararg species: String) = SecondaryChecker.SecondaryWindow(
        pcm = ShortArray(144_000) { (it % 100).toShort() },
        sampleRate = 48000,
        windowStartSec = start,
        highpass = false,
        targets = species.mapIndexed { i, s -> SecondaryChecker.SecondaryWindow.Target("id$i", s) }
    )

    @Test
    fun queueBounded_dropsOldest() = runBlocking {
        val fake = FakeClassifier(listOf(cr("Turdus_merula", 0.6f)))
        val seen = Collections.synchronizedList(mutableListOf<Float>())
        val done = CompletableDeferred<Unit>()
        val checker = SecondaryChecker(fake, 0.5f, capacity = 2) { w, _ ->
            seen += w.windowStartSec
            if (seen.size == 2) done.complete(Unit)
        }
        assertTrue(checker.submit(window(0f, "Turdus merula")))
        assertTrue(checker.submit(window(3f, "Turdus merula")))
        assertFalse(checker.submit(window(6f, "Turdus merula")))
        assertFalse(checker.submit(window(9f, "Turdus merula")))
        assertEquals(2, checker.dropped)

        val job = launch(Dispatchers.Default) { checker.runLoop() }
        withTimeout(5_000) { done.await() }
        job.cancelAndJoin()
        // die beiden neuesten Fenster wurden gerechnet
        assertEquals(listOf(6f, 9f), seen.toList())
    }

    @Test
    fun clear_discardsWaiting() {
        val checker = SecondaryChecker(FakeClassifier(emptyList()), 0.5f) { _, _ -> }
        checker.submit(window(0f, "Turdus merula"))
        checker.clear()
        assertTrue(checker.submit(window(3f, "Turdus merula")))
        assertTrue(checker.submit(window(6f, "Turdus merula")))
        assertEquals(0, checker.dropped)
    }

    @Test
    fun process_mapsAllTargets_withAllScores() {
        val fake = FakeClassifier(listOf(cr("Parus_major", 0.8f), cr("Turdus_merula", 0.1f)))
        val checker = SecondaryChecker(fake, 0.5f) { _, _ -> }
        val out = checker.process(window(0f, "Turdus merula", "Aquila chrysaetos", "Parus major"))
        assertEquals(listOf("id0", "id1", "id2"), out.map { it.first.id })
        assertEquals(SecondaryCheck.STATUS_DISAGREES, out[0].second.status)
        assertEquals(0.1f, out[0].second.confidence!!, 1e-6f)   // Score auch unter jeder Schwelle
        assertEquals(SecondaryCheck.STATUS_NOT_CHECKABLE, out[1].second.status)
        assertEquals(SecondaryCheck.STATUS_CHECKED, out[2].second.status)
        assertEquals(144_000, fake.lastSize)  // 3 s @ 48 kHz, ohne Resampling
    }

    @Test
    fun process_classifierError_emptyResult() {
        val checker = SecondaryChecker(FakeClassifier(emptyList(), fail = true), 0.5f) { _, _ -> }
        assertTrue(checker.process(window(0f, "Turdus merula")).isEmpty())
        assertEquals(1, checker.checked)
    }
}
