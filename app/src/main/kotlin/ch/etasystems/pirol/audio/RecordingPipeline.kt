package ch.etasystems.pirol.audio

import android.util.Log
import ch.etasystems.pirol.data.repository.GpsStats
import ch.etasystems.pirol.location.DetectionFixGate
import ch.etasystems.pirol.location.DetectionFixPolicy
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
    /** T143: Frischen High-Accuracy-Fix anfordern (null = Timeout/nicht verfuegbar) */
    suspend fun requestFreshFix(timeoutMs: Long): LocationData? = null
    /** T143: Genauigkeitsschwelle aus den Einstellungen (Meter) */
    fun maxFixAccuracyM(): Float = Float.MAX_VALUE
    /** T152: GPS-Modus "Nur bei Detektion" (Fix nur bei Detektion, mind. 30 s Abstand) */
    fun gpsOnDetectionOnly(): Boolean = false
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
    /** T191: Routenaufzeichnung starten (nach startSession; Setting "Route aufzeichnen" prueft der Hook) */
    fun startTrack(sessionId: String) {}
    /** T191: Routenaufzeichnung beenden, VOR endSession (alle Punkte in track.jsonl, dann GPX-Export) */
    suspend fun stopTrack() {}
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
        /** T152: Timeout des Startfixes (Audio-Puffer des Service ist begrenzt) */
        private const val STARTUP_FIX_TIMEOUT_MS = 3_000L

        /**
         * T207: Ergebnisse eines Fensters aufteilen. Jede Art mit Konfidenz >= [threshold] wird eigene
         * Detektion (Mehrfachdetektion); die Top-Art bleibt immer (wie bisher, auch unter der Schwelle).
         * Arten unter der Schwelle haengen als Kandidaten (T27) an der Top-Art.
         */
        internal fun splitByThreshold(detections: List<DetectionResult>, threshold: Float): List<DetectionResult> {
            if (detections.size <= 1) return detections
            val sorted = detections.sortedByDescending { it.confidence }
            val top = sorted.first()
            val (primary, rest) = sorted.drop(1).partition { it.confidence >= threshold }
            val candidates = rest.map { alt ->
                DetectionCandidate(
                    scientificName = alt.scientificName,
                    commonName = alt.commonName,
                    confidence = alt.confidence
                )
            }
            return listOf(top.copy(candidates = candidates)) + primary
        }
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

    // T143_STATS
    private val detectionFixes = AtomicLong(0L)
    // T152: Regel "Nur bei Detektion"; pro Aufnahme neu
    @Volatile private var fixGate = DetectionFixGate()
    @Volatile private var detectionOnlyGps = false
    private val staleAttached = AtomicLong(0L)

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
        detectionOnlyGps = hooks.gpsOnDetectionOnly()
        fixGate = DetectionFixGate()
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
            detectionFixes.set(0L)
            staleAttached.set(0L)
            liveSamplesFed.set(0L)
            val config = inferenceWorker.config
            scope.launch(sessionDispatcher) {
                try {
                    hooks.beforeSessionStart()  // T103a
                    // T152: Startfix (kurzer Timeout, Audio-Puffer des Service ist begrenzt)
                    if (detectionOnlyGps) {
                        val start = try { hooks.requestFreshFix(STARTUP_FIX_TIMEOUT_MS) } catch (e: Exception) { null }
                        if (start != null) {
                            fixGate.seed(start)
                            Log.i(TAG, "T152_FIX age_ms=${DetectionFixPolicy.ageMs(start, System.currentTimeMillis())} acc_m=${start.accuracyM} (Start)")
                        }
                    }
                    val loc = hooks.currentLocation() ?: fixGate.latestFix()
                    currentSessionId = session.startSession(
                        sampleRate = sampleRate,
                        latitude = loc?.latitude,
                        longitude = loc?.longitude,
                        regionFilter = config.regionFilter,
                        confidenceThreshold = InferenceConfig.effectiveThreshold(config.confidenceThreshold, classifier.minConfidence),
                        note = hooks.sessionNote(),  // T109
                        modelId = classifier.modelId  // T103a
                    )
                    _storageFallback.value = session.lastStartUsedFallback
                    currentSessionId?.let { id ->
                        try { hooks.startTrack(id) } catch (e: Exception) { Log.w(TAG, "Routenaufzeichnung nicht gestartet", e) }  // T191
                    }
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
        if (detectionOnlyGps) Log.i(TAG, "T152_STATS fixes=${fixGate.freshCount} reused=${fixGate.reusedCount}")
        Log.i(TAG, "T143_STATS fixes=${gpsStats?.fixCount ?: 0} detection_fixes=${detectionFixes.get()} stale_attached=${staleAttached.get()}")
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
                hooks.stopTrack()  // T191: vor endSession (GPX-Export liest track.jsonl)
            } catch (e: Exception) {
                Log.e(TAG, "Routenaufzeichnung beenden fehlgeschlagen", e)
            }
            try {
                session.endSession(gpsStats)  // inkl. Raven- und GPX-Export
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

    /**
     * T143: Neue Detektionen mit frischer Position versehen, wenn der letzte Fix zu alt/ungenau ist
     * (z.B. Bildschirm aus, Updates gedrosselt). Aktualisiert Liste und detections.jsonl.
     */
    private suspend fun refreshFixes(newDetections: List<DetectionResult>, lastFix: LocationData?) {
        val maxAcc = hooks.maxFixAccuracyM()
        val choice: DetectionFixPolicy.Choice
        if (detectionOnlyGps) {
            choice = obtainDetectionFix(lastFix, DetectionFixPolicy.FRESH_FIX_TIMEOUT_MS)
        } else {
            if (!DetectionFixPolicy.needsFreshFix(lastFix, System.currentTimeMillis(), maxAcc)) return
            val fresh = try {
                hooks.requestFreshFix(DetectionFixPolicy.FRESH_FIX_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Frischer Fix fehlgeschlagen", e)
                null
            }
            choice = DetectionFixPolicy.choose(lastFix, fresh, System.currentTimeMillis(), maxAcc)
        }
        val now = System.currentTimeMillis()
        val fix = choice.fix ?: return
        if (choice.stale) staleAttached.incrementAndGet()
        if (choice.source == DetectionFixPolicy.Source.FRESH) detectionFixes.incrementAndGet()
        for (det in newDetections) {
            // aktuellen Stand holen (Nutzer koennte inzwischen verifiziert haben): nur Positionsfelder setzen
            val current = detectionListState.getDetections().firstOrNull { it.id == det.id } ?: continue
            val updated = current.copy(
                latitude = fix.latitude, longitude = fix.longitude,
                locationAgeMs = DetectionFixPolicy.ageMs(fix, now), locationAccuracyM = fix.accuracyM
            )
            if (detectionListState.updateDetection(updated) && session.isActive) session.replaceDetection(updated)
        }
    }

    /** T152: Fix ueber [DetectionFixGate] holen (Wiederverwendung < 30 s, mind. 30 s Abstand, ein Fix gleichzeitig). */
    private suspend fun obtainDetectionFix(lastFix: LocationData?, timeoutMs: Long): DetectionFixPolicy.Choice {
        val r = fixGate.obtain(lastFix, hooks.maxFixAccuracyM()) { hooks.requestFreshFix(timeoutMs) }
        val fix = r.choice.fix
        if (r.fetched && fix != null) {
            Log.i(TAG, "T152_FIX age_ms=${DetectionFixPolicy.ageMs(fix, System.currentTimeMillis())} acc_m=${fix.accuracyM}")
        }
        return r.choice
    }

    // --- Detektionsverarbeitung (Callback nur bei Treffern) ---

    private fun handleDetections(audioBlock: AudioBlock, detections: List<DetectionResult>) {
        // GPS-Position anhaengen
        val loc = hooks.currentLocation()
        val nowMs = System.currentTimeMillis()
        val locDetections = if (loc != null) {
            detections.map {
                it.copy(
                    latitude = loc.latitude, longitude = loc.longitude,
                    locationAgeMs = DetectionFixPolicy.ageMs(loc, nowMs), locationAccuracyM = loc.accuracyM
                )
            }
        } else {
            detections
        }
        // T27/T207: jede Art ueber der Schwelle wird eigene Detektion, Rest wird Kandidat der Top-Art
        val config = inferenceWorker.config
        val topWithCandidates = splitByThreshold(
            locDetections,
            InferenceConfig.effectiveThreshold(config.confidenceThreshold, classifier.minConfidence)
        )
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
                        // T181: Konfidenz/Start des absorbierten Fensters (Punkt als Dezimaltrenner)
                        val absorbedConf = String.format(java.util.Locale.ROOT, "%.4f", result.absorbedConfidence)
                        val absorbedStart = String.format(java.util.Locale.ROOT, "%.3f", result.absorbedStartSec)
                        scope.launch(ioDispatcher) {
                            session.replaceDetection(anchor)
                            session.appendVerification(
                                VerificationEvent(
                                    detectionId = anchor.id,
                                    status = anchor.verificationStatus,
                                    correctedSpecies = "merged:${absorbedId}|count:${anchor.detectionCount}|lastSeenAt:${anchor.lastSeenAtMs}|conf:${absorbedConf}|start:${absorbedStart}",
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
            scope.launch(ioDispatcher) {
                session.appendDetections(newDetections)
                // T143: erst nach dem Append, damit replaceDetection die Zeile findet
                refreshFixes(newDetections, loc)
            }
        } else if (newDetections.isNotEmpty()) {
            scope.launch(ioDispatcher) { refreshFixes(newDetections, loc) }
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
