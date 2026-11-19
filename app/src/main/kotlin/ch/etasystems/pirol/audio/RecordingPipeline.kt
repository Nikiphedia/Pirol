package ch.etasystems.pirol.audio

import android.util.Log
import ch.etasystems.pirol.data.repository.GpsStats
import ch.etasystems.pirol.location.LocationData
import ch.etasystems.pirol.ml.AudioBlock
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.ClusterResult
import ch.etasystems.pirol.ml.DetectionCandidate
import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.InferenceWorker
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.VerificationEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * T94: Schreib-/Session-Schnittstelle der Pipeline (Produktiv: SessionManager, Test: Fake).
 */
interface PipelineSession {
    val isActive: Boolean
    val lastStartUsedFallback: Boolean
    val recordedSampleCount: Long
    fun currentRecordingOffsetSec(): Float
    suspend fun appendAudio(samples: ShortArray)
    suspend fun appendDetections(detections: List<DetectionResult>)
    suspend fun replaceDetection(updated: DetectionResult)
    suspend fun appendVerification(event: VerificationEvent)
    suspend fun startSession(
        sampleRate: Int,
        latitude: Double?,
        longitude: Double?,
        regionFilter: String?,
        confidenceThreshold: Float,
        note: String?,
        modelId: String?
    ): String
    suspend fun endSession(gpsStats: GpsStats?)
}

/**
 * T94: Umgebung der Pipeline (GPS, Artnamen, Einstellungen, Alarm, Embeddings, Upload).
 * Produktiv: [AndroidPipelineHooks]; im Test ein Fake ohne Android-Abhaengigkeiten.
 */
interface PipelineHooks {
    fun loadInferenceConfig(): InferenceConfig
    fun overlapPercent(): Int
    fun sessionNote(): String?
    fun currentLocation(): LocationData?
    fun startLocation()
    /** GPS-Statistik erfassen (T53: VOR stop) und GPS stoppen */
    fun stopLocation(): GpsStats?
    /** Wartet, bis die Artnamen geladen sind (T95) */
    suspend fun awaitSpeciesNames()
    fun resolveName(scientificName: String): String
    fun clusterEnabled(): Boolean
    fun clusterRadiusM(): Int
    fun clusterTimeWindowMin(): Int
    /** Watchlist-Pruefung + Alarm (T20/T124) */
    fun checkWatchlist(detection: DetectionResult)
    /** Embedding extrahieren + in der DB ablegen; liefert das Embedding (null = nicht verfuegbar) */
    suspend fun addEmbedding(block: AudioBlock, top: DetectionResult): FloatArray?
    /** Vor startSession auf dem Session-Dispatcher (T103a: Embedding-DB-Wechsel) */
    fun beforeSessionStart()
    /** Nach endSession auf dem Session-Dispatcher (T97 Embedding-DB, Alarm-Cooldowns, Auto-Upload) */
    suspend fun afterSessionEnd()
}

/** T94: Embedding einer Detektion fuer die Aehnlichkeitssuche im Live-UI */
class EmbeddingAdded(val embedding: FloatArray, val species: String)

/**
 * T94: Session-Pipeline unabhaengig vom UI.
 *
 * Wird vom [RecordingService] beim Aufnahmestart/-stopp direkt gesteuert (kein Umweg ueber das
 * ViewModel). Enthaelt Persistenz (T110/T117/T118), Inference, Detektions-Verarbeitung (Kandidaten,
 * Artnamen, GPS, Clustering in [DetectionListState], Watchlist-Alarm, Embeddings) und die
 * Session-Reihenfolge auf dem Single-Thread-[sessionDispatcher] (T54).
 *
 * Eigener Scope (App-Lebensdauer, Koin-Singleton): der Session-Abschluss muss auch laufen, wenn der
 * Dienst direkt nach dem Stopp zerstoert wird. Kein GlobalScope.
 */
class RecordingPipeline(
    private val classifier: AudioClassifier,
    regionalFilter: RegionalSpeciesFilter,
    private val detectionListState: DetectionListState,
    private val session: PipelineSession,
    private val hooks: PipelineHooks,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "RecordingPipeline"
    }

    // T54-Fix: startSession, alle Audio-Appends (T118: inkl. Preroll) und endSession laufen auf
    // demselben Single-Thread-Kontext, damit endSession() niemals vor einem Append ausgefuehrt wird.
    private val sessionDispatcher = ioDispatcher.limitedParallelism(1)

    private var inferenceJob: Job? = null
    @Volatile private var running = false
    private var currentSessionId: String? = null

    // T137: Persistenz-Zaehler (an die Persistenz uebergebene Samples), pro Session zurueckgesetzt
    private val persistedSamples = AtomicLong(0L)
    @Volatile private var persistSampleRate = 0

    // T118_STATS
    private val prerollSamplesFed = AtomicLong(0L)
    private val liveSamplesFed = AtomicLong(0L)

    private val _lastSessionId = MutableStateFlow<String?>(null)
    /** Session-ID der zuletzt beendeten Session (T15) */
    val lastSessionId: StateFlow<String?> = _lastSessionId.asStateFlow()

    private val _storageFallback = MutableStateFlow(false)
    /** Fallback-Speicher aktiv (T51-Banner) */
    val storageFallback: StateFlow<Boolean> = _storageFallback.asStateFlow()

    private val _overlapReduced = MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** T110: Auto-Fallback hat die Ueberlappung reduziert (neuer Wert in %) */
    val overlapReduced: SharedFlow<Int> = _overlapReduced.asSharedFlow()

    private val _embeddingAdded = MutableSharedFlow<EmbeddingAdded>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Neues Embedding (fuer Aehnlichkeitssuche + Marquee im Live-UI; ohne UI ungenutzt) */
    val embeddingAdded: SharedFlow<EmbeddingAdded> = _embeddingAdded.asSharedFlow()

    /** Laeuft gerade eine Aufnahme durch die Pipeline? */
    val isRunning: Boolean get() = running

    /** Test: bisher in den InferenceWorker eingespeiste Samples (Preroll + Live) */
    internal fun fedSamples(): Long = prerollSamplesFed.get() + liveSamplesFed.get()

    // T110: Audio-Persistenz (jeder Hop genau einmal) getrennt von der Fenster-Analyse.
    private val inferenceWorker = InferenceWorker(
        classifier = classifier,
        regionalFilter = regionalFilter,
        onAudioPersist = { pcm ->
            // T137: Offset aus dem pipeline-eigenen Zaehler (exakt die bisher an die Persistenz
            // uebergebenen Samples), nicht aus der Dateiposition - die hinkt bei Rueckstau hinterher.
            if (session.isActive) {
                val rate = persistSampleRate
                val before = persistedSamples.getAndAdd(pcm.size.toLong())
                val startSec = if (rate > 0) (before.toDouble() / rate).toFloat() else 0f
                // F3: sessionDispatcher serialisiert Preroll + Live-Chunks (StreamingWavWriter nicht thread-safe)
                scope.launch(sessionDispatcher) { session.appendAudio(pcm) }
                startSec
            } else {
                null
            }
        },
        onOverlapReduced = { percent -> _overlapReduced.tryEmit(percent) },
        onWindowAnalyzed = { block, detections -> handleDetections(block, detections) }
    )

    /** Inference-Einstellungen live aendern (Settings/LiveScreen). T110: Overlap aus den Prefs. */
    fun setInferenceConfig(config: InferenceConfig) {
        inferenceWorker.config = config.copy(overlapPercent = hooks.overlapPercent())
    }

    /**
     * Aufnahme gestartet: Session anlegen, Preroll + Live-Chunks persistieren und analysieren.
     * Wird vom Service VOR dem Start des Chunk-Emitters aufgerufen; die Anmeldung am [chunks]-Flow
     * erfolgt synchron (UNDISPATCHED), daher geht kein frueher Chunk verloren.
     */
    fun onRecordingStarted(chunks: SharedFlow<AudioChunk>, sampleRate: Int, preroll: PrerollCapture?) {
        if (running) return
        running = true
        persistSampleRate = sampleRate
        hooks.startLocation()
        setInferenceConfig(hooks.loadInferenceConfig())

        // T118: Session nur starten wenn keine aktive Session. Der InferenceWorker persistiert erst,
        // wenn die Session steht (sessionReady) - sonst wuerde der Preroll-Hop ohne Session verworfen.
        val sessionReady = CompletableDeferred<Unit>()
        var prerollToFeed: PrerollCapture? = null
        if (!session.isActive) {
            prerollToFeed = preroll
            persistedSamples.set(0L)
            prerollSamplesFed.set(0L)
            liveSamplesFed.set(0L)
            val config = inferenceWorker.config
            scope.launch(sessionDispatcher) {
                try {
                    hooks.beforeSessionStart()  // T103a
                    val loc = hooks.currentLocation()
                    currentSessionId = session.startSession(
                        sampleRate = sampleRate,
                        latitude = loc?.latitude,
                        longitude = loc?.longitude,
                        regionFilter = config.regionFilter,
                        confidenceThreshold = config.confidenceThreshold,
                        note = hooks.sessionNote(),  // T109
                        modelId = classifier.modelId  // T103a
                    )
                    _storageFallback.value = session.lastStartUsedFallback
                } catch (e: Exception) {
                    Log.e(TAG, "Session-Start fehlgeschlagen", e)
                } finally {
                    // Appends aus onAudioPersist laufen ebenfalls auf sessionDispatcher -> nach startSession
                    sessionReady.complete(Unit)
                }
            }
        } else {
            // Bereits laufende Session: Zaehler an die bisher geschriebene Laenge anlehnen
            persistedSamples.set(session.recordedSampleCount)
            sessionReady.complete(Unit)
        }

        if (!classifier.isModelAvailable()) {
            // Wie bisher: ohne Modell keine Analyse und kein WAV (Graceful Degradation)
            Log.d(TAG, "ML-Pipeline nicht gestartet — Modell nicht verfuegbar")
            return
        }
        startInference(chunks, prerollToFeed, sessionReady)
    }

    private fun startInference(
        chunks: SharedFlow<AudioChunk>,
        preroll: PrerollCapture?,
        sessionReady: CompletableDeferred<Unit>
    ) {
        inferenceJob?.cancel()
        inferenceJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            Log.d(TAG, "ML-Pipeline gestartet (Overlap ${inferenceWorker.config.overlapPercent} %)")
            // T110: Analyse laeuft parallel; der Collector persistiert Audio ohne auf Inference zu warten
            launch(Dispatchers.Default) { inferenceWorker.runAnalysisLoop() }
            chunks
                .onSubscription {
                    // T118: Laeuft NACH der Anmeldung am SharedFlow - Live-Chunks stauen sich solange im
                    // Puffer. Erst Session abwarten, dann Preroll als ersten Block einspeisen.
                    sessionReady.await()
                    if (preroll != null && preroll.samples.isNotEmpty()) {
                        hooks.awaitSpeciesNames()
                        inferenceWorker.processChunk(
                            AudioChunk(preroll.samples, preroll.sampleRate, 0L),
                            nowEpochMs = preroll.captureEpochMs,
                            backlog = true
                        )
                        prerollSamplesFed.addAndGet(preroll.samples.size.toLong())
                    }
                }
                .collect { chunk ->
                    // T95: kehrt sofort zurueck, sobald geladen
                    hooks.awaitSpeciesNames()
                    inferenceWorker.processChunk(chunk)
                    liveSamplesFed.addAndGet(chunk.samples.size.toLong())
                }
        }
    }

    /**
     * Aufnahme gestoppt: Audio-Rest schreiben (T117), Pipeline leeren, Session beenden.
     * @return Job des Session-Abschlusses (fuer Tests; laeuft auf dem Session-Dispatcher)
     */
    fun onRecordingStopped(): Job? {
        if (!running) return null
        running = false
        // T53: GpsStats VOR stop() erfassen
        val gpsStats = hooks.stopLocation()
        inferenceJob?.cancel()
        inferenceJob = null
        // T117: Audio-Rest schreiben, solange die Session noch aktiv ist (endSession folgt
        // auf demselben sessionDispatcher → Reihenfolge Append vor Schliessen bleibt erhalten)
        inferenceWorker.flushPendingAudio()
        val statsPreroll = prerollSamplesFed.get()
        val statsLive = liveSamplesFed.get()
        inferenceWorker.reset()
        // Detektionsliste leeren — jede Aufnahme startet frisch (T33-AP5)
        detectionListState.clear()
        _storageFallback.value = false
        return scope.launch(sessionDispatcher) {
            // T118: alle Appends (inkl. Preroll + Rest) liegen vor diesem Block auf sessionDispatcher
            Log.i(TAG, "T118_STATS preroll_samples=$statsPreroll live_samples=$statsLive wav_samples=${session.recordedSampleCount}")
            val finishedSessionId = currentSessionId
            currentSessionId = null
            try {
                session.endSession(gpsStats)  // inkl. Raven-Export
            } catch (e: Exception) {
                Log.e(TAG, "Session-Ende fehlgeschlagen", e)
            }
            try {
                hooks.afterSessionEnd()
            } catch (e: Exception) {
                Log.e(TAG, "Nacharbeiten Session-Ende fehlgeschlagen", e)
            }
            if (finishedSessionId != null) _lastSessionId.value = finishedSessionId
        }
    }

    // --- Detektionsverarbeitung (Callback nur bei Treffern) ---

    private fun handleDetections(audioBlock: AudioBlock, detections: List<DetectionResult>) {
        // GPS-Position anhaengen
        val loc = hooks.currentLocation()
        val locDetections = if (loc != null) {
            detections.map { it.copy(latitude = loc.latitude, longitude = loc.longitude) }
        } else {
            detections
        }
        // T27: Top-Ergebnis mit Kandidaten bestuecken, Rest wird zu Kandidaten
        val topWithCandidates = if (locDetections.size > 1) {
            val top = locDetections.first()
            val candidates = locDetections.drop(1).map { alt ->
                DetectionCandidate(
                    scientificName = alt.scientificName,
                    commonName = alt.commonName,
                    confidence = alt.confidence
                )
            }
            listOf(top.copy(candidates = candidates))
        } else {
            locDetections
        }
        // T26/T27: Artnamen in gewaehlter Sprache
        val geoDetections = topWithCandidates.map { det ->
            det.copy(
                commonName = hooks.resolveName(det.scientificName),
                candidates = det.candidates.map { cand ->
                    cand.copy(commonName = hooks.resolveName(cand.scientificName))
                }
            )
        }
        // T70: Cluster-Algorithmus
        val newDetections = mutableListOf<DetectionResult>()
        val mergedAnchors = mutableListOf<DetectionResult>()  // T124: auch Merges koennen alarmieren
        for (det in geoDetections) {
            // T73: gesperrte Art → Detection verwerfen
            val speciesKey = det.scientificName.replace(' ', '_')
            if (detectionListState.isLocked(speciesKey)) continue

            when (val result = detectionListState.addOrCluster(
                det, hooks.clusterEnabled(), hooks.clusterRadiusM(), hooks.clusterTimeWindowMin()
            )) {
                is ClusterResult.New -> newDetections.add(result.detection)
                is ClusterResult.Merged -> {
                    mergedAnchors.add(result.anchor)
                    if (session.isActive) {
                        val anchor = result.anchor
                        val absorbedId = result.absorbedId
                        scope.launch(ioDispatcher) {
                            session.replaceDetection(anchor)
                            session.appendVerification(
                                VerificationEvent(
                                    detectionId = anchor.id,
                                    status = anchor.verificationStatus,
                                    correctedSpecies = "merged:${absorbedId}|count:${anchor.detectionCount}|lastSeenAt:${anchor.lastSeenAtMs}",
                                    verifiedAtMs = System.currentTimeMillis(),
                                    actionType = VerificationEvent.ACTION_CLUSTER_MERGED
                                )
                            )
                        }
                    }
                }
            }
        }

        // T20/T124: Watchlist-Alarm fuer neue Detektionen und Cluster-Merges
        for (detection in newDetections + mergedAnchors) hooks.checkWatchlist(detection)

        // Neue Detektionen in JSONL schreiben (Merges via replaceDetection)
        if (session.isActive && newDetections.isNotEmpty()) {
            scope.launch(ioDispatcher) { session.appendDetections(newDetections) }
        }

        // Embedding-Arm (T61: Embedding aus der Klassifikations-Inference)
        val topDetection = geoDetections.maxByOrNull { it.confidence } ?: return
        scope.launch(Dispatchers.Default) {
            val embedding = try {
                hooks.addEmbedding(audioBlock, topDetection)
            } catch (e: Exception) {
                Log.e(TAG, "Embedding fehlgeschlagen", e)
                null
            }
            if (embedding != null) _embeddingAdded.tryEmit(EmbeddingAdded(embedding, topDetection.scientificName))
        }
    }
}
