package ch.etasystems.pirol.ml

import ch.etasystems.pirol.audio.AudioChunk
import ch.etasystems.pirol.rescan.RescanPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T231: Perch mit 5-s-Fenster — Hop, Fensterplan, Ringpuffer und Sekundaerfenster (reine Logik). */
class PerchWindowTest {

    // --- Hop ---

    @Test
    fun hop_longWindow_cappedAt3s_birdnetUnchanged() {
        val max = InferenceConfig.DEFAULT_MAX_HOP_MS
        assertEquals(3000, OverlapFallback.hopMsFor(0, 5000, max))
        assertEquals(2500, OverlapFallback.hopMsFor(50, 5000, max))
        assertEquals(1667, OverlapFallback.hopMsFor(67, 5000, max))
        assertEquals(5000, OverlapFallback.hopMsFor(0, 5000, 5000))
        // BirdNET 3 s (und kuerzere Fenster) unveraendert
        for (p in listOf(0, 50, 67)) {
            assertEquals(OverlapFallback.hopMsFor(p, 3000), OverlapFallback.hopMsFor(p, 3000, max))
            assertEquals(OverlapFallback.hopMsFor(p, 1000), OverlapFallback.hopMsFor(p, 1000, max))
        }
        assertEquals(3000, InferenceConfig().hopMsFor(0, 5000))
        assertEquals(3000, InferenceConfig().hopMsFor(0, 3000))
    }

    // --- Fensterplan live (ChunkAccumulator) und Rescan (ueber Segmente = eine Achse) ---

    @Test
    fun liveAccumulator_5sWindow_hop3s() {
        val sr = 1000
        val acc = ChunkAccumulator(windowDurationMs = 5000, hopMs = OverlapFallback.hopMsFor(0, 5000, 3000))
        val windows = mutableListOf<AnalysisWindow>()
        var persisted = 0
        var n = 0
        repeat(14) {  // 14 s in 1-s-Chunks
            val r = acc.feed(AudioChunk(ShortArray(sr) { (n++).toShort() }, sr, 0L))
            windows += r.windows
            persisted += r.hops.sumOf { it.pcm.size }
        }
        assertEquals(listOf(0L, 3000L, 6000L, 9000L), windows.map { it.startIndex })
        assertTrue(windows.all { it.pcm.size == 5000 })
        // Fensterinhalt = Stream ab startIndex
        assertEquals(3000, windows[1].pcm[0].toInt())
        // Persistenz weiterhin jedes Sample genau einmal (ganze Hops)
        assertEquals(12_000, persisted)
    }

    @Test
    fun rescanPlan_5sWindow_hop3s_overSegments() {
        val sr = 48_000
        // zwei Segmente 12 s + 8 s = 20 s auf einer Achse
        val total = sr.toLong() * 12 + sr.toLong() * 8
        val starts = RescanPlan.windowStarts(total, sr, 5000, 0, InferenceConfig.DEFAULT_MAX_HOP_MS)
        assertEquals(listOf(0, 3, 6, 9, 12, 15).map { it.toLong() * sr }, starts)
        // ohne Begrenzung (alt): Hop = Fenster
        assertEquals(listOf(0, 5, 10, 15).map { it.toLong() * sr }, RescanPlan.windowStarts(total, sr, 5000, 0))
        // BirdNET 3 s unveraendert
        assertEquals(
            RescanPlan.windowStarts(total, sr, 3000, 50),
            RescanPlan.windowStarts(total, sr, 3000, 50, InferenceConfig.DEFAULT_MAX_HOP_MS)
        )
    }

    // --- Ringpuffer ---

    @Test
    fun ring_readAppendWrap() {
        val ring = AudioRingBuffer(sampleRate = 10, capacity = 8)
        ring.append(ShortArray(5) { it.toShort() })            // 0..4
        assertArrayEquals(shortArrayOf(1, 2, 3), ring.read(1, 4))
        assertNull(ring.read(3, 6))                            // Zukunft
        ring.append(ShortArray(7) { (5 + it).toShort() })      // 5..11, aeltestes = 4
        assertEquals(12L, ring.endIndex.value)
        assertEquals(4L, ring.oldestIndex())
        assertNull(ring.read(3, 6))                            // ueberschrieben
        assertArrayEquals(ShortArray(8) { (4 + it).toShort() }, ring.read(4, 12))
        ring.append(ShortArray(20) { (12 + it).toShort() })    // groesser als Kapazitaet
        assertArrayEquals(ShortArray(8) { (24 + it).toShort() }, ring.read(24, 32))
    }

    @Test
    fun ring_origin_forResumedSession() {
        val ring = AudioRingBuffer(sampleRate = 10, capacity = 100, originIndex = 1000)
        ring.append(ShortArray(10) { it.toShort() })
        assertNull(ring.read(995, 1005))
        assertArrayEquals(ShortArray(10) { it.toShort() }, ring.read(1000, 1010))
    }

    // --- Sekundaerfenster: zentriert, Rand, Zuordnung ---

    @Test
    fun contextRange_centered_and_shiftedAtStart() {
        val sr = 48_000
        val win = 3 * sr
        val target = 5 * sr
        // zentriert: 1 s davor, 1 s danach
        assertEquals(SecondaryCheck.SampleRange(9L * sr, 14L * sr), SecondaryCheck.contextRange(10L * sr, win, target))
        // Sessionanfang: nach rechts geschoben statt Stille, Hauptfenster bleibt drin
        assertEquals(SecondaryCheck.SampleRange(0, 5L * sr), SecondaryCheck.contextRange(0, win, target))
        assertEquals(SecondaryCheck.SampleRange(0, 5L * sr), SecondaryCheck.contextRange(sr / 2L, win, target))
        // fortgesetzte Session: nicht vor dem Pufferbeginn
        assertEquals(SecondaryCheck.SampleRange(100L * sr, 105L * sr), SecondaryCheck.contextRange(100L * sr, win, target, 100L * sr))
        // Rueckfall rechtsbuendig
        assertEquals(SecondaryCheck.SampleRange(8L * sr, 13L * sr), SecondaryCheck.rightAlignedRange(13L * sr, target))
        assertEquals(SecondaryCheck.SampleRange(0, 4L * sr), SecondaryCheck.rightAlignedRange(4L * sr, target))
        // Zuordnung Fensterstart (s) → Index
        assertEquals(10L * sr, SecondaryCheck.startIndexOf(10f, sr))
        assertEquals(1_500L * sr, SecondaryCheck.startIndexOf(1500f, sr))
    }

    @Test
    fun overlapGate_perch_everyHop3s() {
        val gate = SecondaryCheck.OverlapGate(5000, 0, InferenceConfig.DEFAULT_MAX_HOP_MS)
        assertTrue(gate.accept("Turdus merula", 0f))
        assertTrue(gate.accept("Turdus merula", 3f))    // frueher (Hop 5 s) verworfen
        assertTrue(!gate.accept("Turdus merula", 4.5f))
        assertTrue(gate.accept("Turdus merula", 6f))
    }

    // --- SecondaryChecker mit Ringpuffer (Fake-Zweitmodell, 1 kHz fuer kurze Wartezeiten) ---

    private class LongWindowClassifier(override val windowMs: Int) : AudioClassifier {
        @Volatile var lastSize = 0
        override fun isModelAvailable() = true
        override fun classify(samples: FloatArray, sampleRate: Int, topK: Int, threshold: Float): List<AudioClassifier.ClassifierResult> {
            lastSize = samples.size
            return listOf(AudioClassifier.ClassifierResult("Turdus_merula", "Turdus_merula", 0.7f, 0f, 5f))
        }
        override fun extractEmbedding(samples: FloatArray, sampleRate: Int): FloatArray? = null
        override fun resetSession() {}
        override val labelCount = 1
        override val modelName = "Perch v2 FP16 (Google)"
        override val modelId = "perch_v2_fp16"
        override val inputSampleRate = 1000
        override val labelScientificNames = setOf("Turdus_merula")
    }

    private val sr = 1000

    private fun ramp(from: Int, n: Int) = ShortArray(n) { (from + it).toShort() }

    private fun mainWindow(startSec: Float) = SecondaryChecker.SecondaryWindow(
        pcm = ramp((startSec * sr).toInt(), 3 * sr),
        sampleRate = sr,
        windowStartSec = startSec,
        highpass = false,
        targets = listOf(SecondaryChecker.SecondaryWindow.Target("id0", "Turdus merula"))
    )

    @Test
    fun checker_centeredContext_fromRing() = runBlocking {
        val ring = AudioRingBuffer(sr, 30 * sr)
        ring.append(ramp(0, 10 * sr))
        val fake = LongWindowClassifier(5000)
        val checker = SecondaryChecker(fake, 0.5f, ring = ring, waitMarginMs = 0) { _, _ -> }
        val ctx = checker.contextFor(mainWindow(5f))
        assertNotNull(ctx)
        assertEquals("CENTERED", ctx!!.mode)
        assertArrayEquals(ramp(4 * sr, 5 * sr), ctx.pcm)
        val out = checker.process(mainWindow(5f), ctx)
        assertEquals(5 * sr, fake.lastSize)
        assertEquals(SecondaryCheck.STATUS_CHECKED, out.single().second.status)
    }

    @Test
    fun checker_waitsForAudioAfterWindow() = runBlocking {
        val ring = AudioRingBuffer(sr, 30 * sr)
        ring.append(ramp(0, 8 * sr))   // Hauptfenster 5..8 s fertig, 8..9 s fehlt noch
        val checker = SecondaryChecker(LongWindowClassifier(5000), 0.5f, ring = ring, waitMarginMs = 5000) { _, _ -> }
        val pending = async(Dispatchers.Default) { checker.contextFor(mainWindow(5f)) }
        delay(100)
        ring.append(ramp(8 * sr, 2 * sr))
        val ctx = pending.await()!!
        assertEquals("CENTERED", ctx.mode)
        assertArrayEquals(ramp(4 * sr, 5 * sr), ctx.pcm)
    }

    @Test
    fun checker_sessionStart_shiftedRight_noSilence() = runBlocking {
        val ring = AudioRingBuffer(sr, 30 * sr)
        ring.append(ramp(0, 6 * sr))
        val checker = SecondaryChecker(LongWindowClassifier(5000), 0.5f, ring = ring, waitMarginMs = 0) { _, _ -> }
        val ctx = checker.contextFor(mainWindow(0f))!!
        assertEquals("CENTERED", ctx.mode)
        assertArrayEquals(ramp(0, 5 * sr), ctx.pcm)
    }

    @Test
    fun checker_stoppedEarly_rightAligned_thenSilence() = runBlocking {
        // Zweitmodell 3.2 s (Ueberhang 0.1 s je Seite → kurze Wartezeit im Test)
        val ring = AudioRingBuffer(sr, 30 * sr)
        ring.append(ramp(0, 8 * sr + 50))   // nach dem Fenster nur 50 ms statt 100 ms
        val checker = SecondaryChecker(LongWindowClassifier(3200), 0.5f, ring = ring, waitMarginMs = 0) { _, _ -> }
        val ctx = checker.contextFor(mainWindow(5f))!!
        assertEquals("RIGHT", ctx.mode)
        assertArrayEquals(ramp(8 * sr + 50 - 3200, 3200), ctx.pcm)

        // Aufnahme insgesamt kuerzer als das Zweitmodell-Fenster → Stille (Hinweis)
        val shortRing = AudioRingBuffer(sr, 30 * sr)
        shortRing.append(ramp(0, 3 * sr))
        val c2 = SecondaryChecker(LongWindowClassifier(3200), 0.5f, ring = shortRing, waitMarginMs = 0) { _, _ -> }
        val ctx2 = c2.contextFor(mainWindow(0f))!!
        assertEquals("SILENCE", ctx2.mode)
        assertEquals(3200, ctx2.pcm.size)
        assertEquals(0, ctx2.pcm[0].toInt())        // Stille vorne
        assertEquals(0, ctx2.pcm[100].toInt())      // erstes echtes Sample (Wert 0) zentriert
        assertEquals(1, ctx2.pcm[101].toInt())
    }

    @Test
    fun checker_sameLength_noRingNeeded() = runBlocking {
        val ring = AudioRingBuffer(sr, 30 * sr)
        val checker = SecondaryChecker(LongWindowClassifier(3000), 0.5f, ring = ring) { _, _ -> }
        assertNull(checker.contextFor(mainWindow(5f)))
    }
}
