package ch.etasystems.pirol.audio

import ch.etasystems.pirol.data.repository.GpsStats
import ch.etasystems.pirol.location.LocationData
import ch.etasystems.pirol.ml.AudioBlock
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.VerificationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * T94: Session-Pipeline ohne ViewModel — Chunks erreichen die Persistenz, Stopp schreibt den Rest,
 * Detektionen landen in DetectionListState.
 */
class RecordingPipelineTest {

    private val sr = 48000

    private class FakeClassifier(private val results: List<AudioClassifier.ClassifierResult>) : AudioClassifier {
        constructor(hit: Boolean) : this(
            if (hit) listOf(
                AudioClassifier.ClassifierResult("Turdus_merula", "Turdus merula", 0.9f, 0f, 3f),
                AudioClassifier.ClassifierResult("Erithacus_rubecula", "Erithacus rubecula", 0.4f, 0f, 3f)
            ) else emptyList()
        )
        override fun isModelAvailable() = true
        override fun classify(samples: FloatArray, sampleRate: Int, topK: Int, threshold: Float) =
            results.filter { it.confidence >= threshold }.take(topK)
        override fun extractEmbedding(samples: FloatArray, sampleRate: Int): FloatArray? = null
        override fun resetSession() {}
        override val labelCount = 2
        override val modelName = "fake"
    }

    private class FakeSession : PipelineSession {
        @Volatile var active = false
        val audio: MutableList<ShortArray> = Collections.synchronizedList(mutableListOf())
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val detections: MutableList<DetectionResult> = Collections.synchronizedList(mutableListOf())
        @Volatile var samples = 0L
        @Volatile var appendDelayMs = 0L  // T137: Rueckstau simulieren
        override val isActive get() = active
        override val lastStartUsedFallback = false
        override val recordedSampleCount get() = samples
        override fun currentRecordingOffsetSec() = samples.toFloat() / 48000
        override suspend fun appendAudio(samples: ShortArray) {
            check(active) { "Append ohne Session" }
            if (appendDelayMs > 0) Thread.sleep(appendDelayMs)  // blockierendes IO wie der echte Writer (kein Suspend-Interleaving)
            audio += samples
            this.samples += samples.size
            events += "append"
        }
        override suspend fun appendDetections(detections: List<DetectionResult>) { this.detections += detections }
        override suspend fun replaceDetection(updated: DetectionResult) {}
        override suspend fun appendVerification(event: VerificationEvent) {}
        override suspend fun startSession(
            sampleRate: Int, latitude: Double?, longitude: Double?, regionFilter: String?,
            confidenceThreshold: Float, note: String?, modelId: String?
        ): String { active = true; events += "start"; return "S1" }
        override suspend fun endSession(gpsStats: GpsStats?) { active = false; events += "end" }
    }

    private class FakeHooks(
        private val overlap: Int = 0,
        private val cluster: Boolean = false
    ) : PipelineHooks {
        val watchlistChecked: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var afterEndCalls = 0
        override fun loadInferenceConfig() = InferenceConfig(regionFilter = null, highpassEnabled = false)
        override fun overlapPercent() = overlap
        override fun sessionNote(): String? = null
        override fun currentLocation() = LocationData(47.0, 8.0, null, 5f, 0L)
        override fun startLocation() {}
        override fun stopLocation(): GpsStats? = null
        override suspend fun awaitSpeciesNames() {}
        override fun resolveName(scientificName: String) = if (scientificName == "Turdus_merula") "Amsel" else scientificName
        override fun clusterEnabled() = cluster
        override fun clusterRadiusM() = 0
        override fun clusterTimeWindowMin() = if (cluster) 5 else 0
        override fun checkWatchlist(detection: DetectionResult) { watchlistChecked += detection.scientificName }
        override suspend fun addEmbedding(block: AudioBlock, top: DetectionResult): FloatArray? = null
        override fun beforeSessionStart() {}
        override suspend fun afterSessionEnd() { afterEndCalls++ }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun dummyRegionFilter(): RegionalSpeciesFilter {
        // Ohne Konstruktor anlegen (braucht Context); bleibt ungenutzt, da regionFilter = null.
        // sun.misc.Unsafe per Reflection (nicht im Android-Compile-Classpath)
        val cls = Class.forName("sun.misc.Unsafe")
        val unsafe = cls.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return cls.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, RegionalSpeciesFilter::class.java) as RegionalSpeciesFilter
    }

    private fun pattern(from: Int, n: Int) = ShortArray(n) { k -> ((from + k) % 30000).toShort() }

    private suspend fun waitUntil(what: String, cond: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!cond()) delay(5) }
        } catch (e: Exception) {
            throw AssertionError("Timeout: $what", e)
        }
    }

    private fun pipeline(session: FakeSession, hooks: FakeHooks, list: DetectionListState, hit: Boolean) =
        RecordingPipeline(FakeClassifier(hit), dummyRegionFilter(), list, session, hooks, scope)

    @Test
    fun chunksReachPersistenceWithoutViewModel_stopFlushesTail() = runBlocking {
        val session = FakeSession()
        val hooks = FakeHooks()
        val p = pipeline(session, hooks, DetectionListState(), hit = false)
        val flow = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 64)

        val prerollN = sr * 2 + 17
        val preroll = PrerollCapture(pattern(0, prerollN), sr, 0L)
        p.onRecordingStarted(flow, sr, preroll)

        // Live-Chunks direkt nach dem Start (vor Session-Bereitschaft) — duerfen nicht verloren gehen
        val sizes = intArrayOf(4800, 1023, 9600, 777)
        val liveN = sr * 7 + 333
        var pos = prerollN
        var i = 0
        while (pos < prerollN + liveN) {
            val n = minOf(sizes[i++ % sizes.size], prerollN + liveN - pos)
            flow.emit(AudioChunk(pattern(pos, n), sr, 0L))
            pos += n
        }
        val total = prerollN + liveN
        waitUntil("alle Samples eingespeist") { p.fedSamples() == total.toLong() }

        p.onRecordingStopped()!!.join()

        // Genau einmal, lueckenlos, Preroll zuerst, inkl. Rest < 1 Hop (T117)
        assertEquals(total.toLong(), session.samples)
        val wav = ShortArray(total)
        var off = 0
        for (a in session.audio) { a.copyInto(wav, off); off += a.size }
        assertArrayEquals(pattern(0, total), wav)
        // Reihenfolge auf dem Session-Dispatcher: start → appends → end
        assertEquals("start", session.events.first())
        assertEquals("end", session.events.last())
        assertEquals(1, session.events.count { it == "end" })
        assertEquals(1, hooks.afterEndCalls)
        assertEquals("S1", p.lastSessionId.value)
        assertTrue(!p.isRunning)
    }

    @Test
    fun detectionsReachDetectionListState() = runBlocking {
        val session = FakeSession()
        val hooks = FakeHooks()
        val list = DetectionListState()
        val p = pipeline(session, hooks, list, hit = true)
        val flow = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 64)
        p.onRecordingStarted(flow, sr, null)

        var pos = 0
        while (pos < sr * 4) {
            flow.emit(AudioChunk(pattern(pos, 4800), sr, 0L))
            pos += 4800
        }
        waitUntil("Detektion in DetectionListState") { list.getDetections().isNotEmpty() }
        waitUntil("Detektion persistiert") { session.detections.isNotEmpty() }

        val det = list.getDetections().first()
        assertEquals("Turdus_merula", det.scientificName)
        assertEquals("Amsel", det.commonName)                       // Artname via Hooks (T26)
        assertEquals(47.0, det.latitude!!, 0.0)                     // GPS-Tagging
        assertEquals(listOf("Erithacus_rubecula"), det.candidates.map { it.scientificName })  // T27
        assertEquals(0f, det.chunkStartSec, 0.001f)                 // Fenster ab WAV-Offset 0
        assertTrue(hooks.watchlistChecked.contains("Turdus_merula")) // Watchlist/Alarm-Pruefung

        p.onRecordingStopped()!!.join()
        assertTrue("Liste nach Stopp leer", list.getDetections().isEmpty())
        assertEquals("end", session.events.last())
    }

    /** T137: Persistenz hinkt hinterher (Rueckstau) - chunkStartSec stammt aus dem Pipeline-Zaehler. */
    @Test
    fun detectionOffsetsCorrectUnderPersistenceBacklog() = runBlocking {
        val session = FakeSession().apply { appendDelayMs = 150 }
        val hooks = FakeHooks()
        val list = DetectionListState()
        val p = pipeline(session, hooks, list, hit = true)
        val flow = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 64)
        p.onRecordingStarted(flow, sr, null)

        var pos = 0
        while (pos < sr * 7) {
            flow.emit(AudioChunk(pattern(pos, sr), sr, 0L))
            pos += sr
        }
        waitUntil("zwei Fenster analysiert") { session.detections.size >= 2 }
        // Datei liegt hinter der Pipeline zurueck (Backlog), die Offsets duerfen es nicht sein
        val starts = session.detections.toList().map { it.chunkStartSec }.sorted()
        assertEquals(0f, starts[0], 0.001f)
        assertEquals(3f, starts[1], 0.001f)

        waitUntil("alle Samples eingespeist") { p.fedSamples() == (sr * 7).toLong() }
        p.onRecordingStopped()!!.join()
        assertEquals((sr * 7).toLong(), session.samples)  // genau einmal
    }

    // --- T207: Mehrfachdetektion im selben Fenster ---

    private fun cr(name: String, conf: Float) = AudioClassifier.ClassifierResult(name, name.replace('_', ' '), conf, 0f, 3f)

    /** Zwei Arten ueber der Schwelle (0.5) plus eine knapp darunter im selben Fenster. */
    private val twoSpecies = listOf(
        cr("Turdus_merula", 0.9f),
        cr("Fringilla_coelebs", 0.7f),
        cr("Erithacus_rubecula", 0.45f)
    )

    private suspend fun feed(flow: MutableSharedFlow<AudioChunk>, seconds: Int) {
        var pos = 0
        while (pos < sr * seconds) {
            flow.emit(AudioChunk(pattern(pos, 4800), sr, 0L))
            pos += 4800
        }
    }

    @Test
    fun twoSpeciesInSameWindow_bothDetectedAndPersisted() = runBlocking {
        val session = FakeSession()
        val hooks = FakeHooks()
        val list = DetectionListState()
        val p = RecordingPipeline(FakeClassifier(twoSpecies), dummyRegionFilter(), list, session, hooks, scope)
        val flow = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 64)
        p.onRecordingStarted(flow, sr, null)
        feed(flow, 4)  // genau ein 3-s-Fenster

        waitUntil("Fenster persistiert") { session.detections.isNotEmpty() }
        delay(200)
        val listNames = list.getDetections().map { it.scientificName }.toSet()
        assertEquals(setOf("Turdus_merula", "Fringilla_coelebs"), listNames)
        val jsonlNames = session.detections.toList().map { it.scientificName }
        assertEquals(listOf("Turdus_merula", "Fringilla_coelebs"), jsonlNames)
        // Gleiches Fenster fuer beide
        assertTrue(session.detections.all { it.chunkStartSec == 0f })
        // Knapp unter der Schwelle: nur Kandidat der Top-Art, keine eigene Detektion
        val top = list.getDetections().first { it.scientificName == "Turdus_merula" }
        assertEquals(listOf("Erithacus_rubecula"), top.candidates.map { it.scientificName })
        assertTrue(hooks.watchlistChecked.containsAll(listOf("Turdus_merula", "Fringilla_coelebs")))

        p.onRecordingStopped()!!.join()
    }

    /** Overlap 50/67 %: gleiche Art in Nachbarfenstern wird zusammengefasst, verschiedene Arten nie. */
    @Test
    fun overlapWindows_sameSpeciesMerged_differentSpeciesKept() {
        for (overlap in intArrayOf(50, 67)) runBlocking {
            val session = FakeSession()
            val hooks = FakeHooks(overlap = overlap, cluster = true)
            val list = DetectionListState()
            val p = RecordingPipeline(FakeClassifier(twoSpecies), dummyRegionFilter(), list, session, hooks, scope)
            val flow = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 64)
            p.onRecordingStarted(flow, sr, null)
            feed(flow, 6)  // mehrere ueberlappende Fenster

            waitUntil("alle Samples eingespeist ($overlap %)") { p.fedSamples() == (sr * 6).toLong() }
            waitUntil("Merge ueber mehrere Fenster ($overlap %)") {
                list.getDetections().any { it.detectionCount >= 2 }
            }
            val dets = list.getDetections()
            assertEquals("overlap=$overlap", setOf("Turdus_merula", "Fringilla_coelebs"), dets.map { it.scientificName }.toSet())
            assertEquals("overlap=$overlap", 2, dets.size)
            assertEquals("overlap=$overlap", 2, session.detections.size)
            p.onRecordingStopped()!!.join()
        }
    }

    // --- T211: eine Schwelle fuer alle Arten ---

    private fun det(name: String, conf: Float) = DetectionResult(
        id = name, scientificName = name, commonName = name, confidence = conf,
        timestampMs = 0L, chunkStartSec = 0f, chunkEndSec = 3f, sampleRate = 48000
    )

    @Test
    fun topSpeciesBelowThreshold_noDetection() {
        val out = RecordingPipeline.splitByThreshold(listOf(det("A", 0.45f), det("B", 0.3f)), 0.5f)
        assertTrue(out.isEmpty())
        assertTrue(RecordingPipeline.splitByThreshold(listOf(det("A", 0.4f)), 0.5f).isEmpty())
    }

    @Test
    fun twoSpeciesAboveThreshold_twoDetections_candidatesKept() {
        val out = RecordingPipeline.splitByThreshold(
            listOf(det("B", 0.7f), det("A", 0.9f), det("C", 0.4f)), 0.5f
        )
        assertEquals(listOf("A", "B"), out.map { it.scientificName })
        assertTrue(out.all { d -> d.candidates.map { it.scientificName } == listOf("C") })
    }
}
