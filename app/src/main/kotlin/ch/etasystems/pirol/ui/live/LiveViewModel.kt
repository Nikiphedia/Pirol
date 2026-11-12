package ch.etasystems.pirol.ui.live

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.audio.RecordingPhase
import ch.etasystems.pirol.audio.RecordingService
import ch.etasystems.pirol.audio.VoiceMemoRecorder
import ch.etasystems.pirol.audio.dsp.DynamicRangeMapper
import ch.etasystems.pirol.audio.dsp.MelSpectrogram
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.AtlasCodeRepository
import ch.etasystems.pirol.data.repository.ReferenceRepository
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.data.repository.SessionMetadata
import ch.etasystems.pirol.data.sync.UploadManager
import kotlinx.serialization.json.Json as KJson
import ch.etasystems.pirol.location.LocationProvider
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.CHUNK_DURATION_MS
import ch.etasystems.pirol.ml.AtlasCode
import ch.etasystems.pirol.ml.DetectionCandidate
import ch.etasystems.pirol.ml.ClusterResult
import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.EmbeddingDatabase
import ch.etasystems.pirol.ml.EmbeddingExtractor
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.InferenceWorker
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ml.WatchlistManager
import ch.etasystems.pirol.audio.AlarmService
import ch.etasystems.pirol.ui.components.SpectrogramPalette
import ch.etasystems.pirol.ui.components.SpectrogramState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel fuer den LiveScreen.
 *
 * Verwaltet die gesamte Audio→DSP→Sonogramm-Pipeline UND die ML-Inference-Pipeline:
 *
 * Pipeline-Architektur (Dual-Arm):
 *
 *   RecordingService.audioChunkFlow
 *         │
 *         ├──→ [DSP-Arm] MelSpectrogram → SpectrogramState (Canvas)
 *         │
 *         └──→ [ML-Arm]  InferenceWorker → DetectionListState (LazyColumn)
 *                                        → EmbeddingExtractor → EmbeddingDatabase
 *
 * Beide Arms laufen parallel auf Dispatchers.Default.
 *
 * @param classifier Audio-Klassifizierer (via Koin)
 * @param regionalFilter Regionaler Artenfilter (via Koin)
 * @param detectionListState Thread-safe Detektions-Speicher (via Koin Singleton)
 * @param embeddingExtractor Embedding-Extraktion via BirdNET + MFCC-Fallback (via Koin)
 * @param embeddingDatabase Lokale Embedding-Datenbank (via Koin Singleton)
 * @param locationProvider GPS-Location-Provider (via Koin Singleton)
 * @param sessionManager Session-Manager fuer Aufnahme-Persistierung (via Koin Singleton)
 * @param uploadManager Upload-Manager fuer Session-Export (via Koin Singleton)
 * @param referenceRepository Referenzbibliothek fuer verifizierte Aufnahmen (via Koin Singleton)
 */
class LiveViewModel(
    private val classifier: AudioClassifier,
    private val regionalFilter: RegionalSpeciesFilter,
    private val detectionListState: DetectionListState,
    private val embeddingExtractor: EmbeddingExtractor,
    private val embeddingDatabase: EmbeddingDatabase,
    private val locationProvider: LocationProvider,
    private val sessionManager: SessionManager,
    private val uploadManager: UploadManager,
    private val referenceRepository: ReferenceRepository,
    private val watchlistManager: WatchlistManager,
    private val alarmService: AlarmService,
    private val appPreferences: AppPreferences,
    private val speciesNameResolver: SpeciesNameResolver,
    private val atlasCodeRepository: AtlasCodeRepository,
    private val context: android.content.Context  // T69: fuer VoiceMemoRecorder SAF + memoAudioPlayer
) : ViewModel() {

    companion object {
        private const val TAG = "LiveViewModel"

        /**
         * Aktuelle Embedding-Basis (T61): Embeddings stammen aus dem Klassifikations-Durchlauf
         * (vorverarbeitetes 32-kHz-Audio) statt aus einer separaten Inference auf Roh-Audio.
         * Aeltere DBs werden einmalig geleert, damit die Aehnlichkeitssuche konsistent bleibt.
         */
        private const val EMBEDDING_DB_VERSION = 2
    }

    // --- Snackbar-Infrastruktur (T52) ---

    /** Snackbar-Ereignis fuer LiveScreen. */
    data class SnackbarEvent(
        val message: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null
    )

    private data class UndoEntry(
        val detectionId: String,
        val previousStatus: VerificationStatus,
        val previousCorrected: String?
    )

    private val _snackbarChannel = Channel<SnackbarEvent>(Channel.CONFLATED)
    val snackbarEvents = _snackbarChannel.receiveAsFlow()
    private var pendingUndo: UndoEntry? = null

    // --- T69: Voice-Memo ---

    /** Sprach-Memo Recorder — separater AudioRecord, blockiert RecordingService nicht. */
    val voiceMemoRecorder = VoiceMemoRecorder()

    /** Separater AudioPlayer fuer Memo-Wiedergabe (kein Sharing mit Session-Player). */
    val memoAudioPlayer by lazy { AudioPlayer(context) }

    // FAB-Debounce (T52): verhindert Doppel-Taps innerhalb 500 ms
    private var lastFabTapAt = 0L

    // Serialisiert Verifikations-IO: verhindert Race bei Schnell-Taps (AC6)
    private val verificationMutex = Mutex()

    // T73: Referenz auf lockedSpecies-StateFlow in DetectionListState (fuer toggleLock)
    private val lockedSpecies = detectionListState.lockedSpecies

    // --- Oeffentlicher State ---
    private val _uiState = MutableStateFlow(LiveUiState())
    val uiState: StateFlow<LiveUiState> = _uiState.asStateFlow()

    // --- Interne Referenzen ---
    private var service: RecordingService? = null
    private var applicationContext: Context? = null
    private var collectionJob: Job? = null
    private var inferenceJob: Job? = null
    private var recordingObserverJob: Job? = null
    private var locationObserverJob: Job? = null
    private var embeddingDbLoaded = false

    // DSP-Pipeline (zustandsbehaftet, nicht in UiState)
    private var melSpectrogram: MelSpectrogram? = null
    private var currentSampleRate: Int = 0

    // Session-ID der aktuellen/letzten Aufnahme (T15)
    private var currentSessionId: String? = null

    // Preroll-Samples die nach startSession() angehaengt werden (T46)
    private var pendingPrerollSamples: ShortArray = ShortArray(0)

    // F5: User hat Preroll explizit gestartet
    private var prerollRunning = false

    // T54-Fix: Sequentieller Dispatcher fuer Session-Lifecycle-Operationen.
    // startSession+appendPreroll und endSession laufen auf demselben Single-Thread-Kontext,
    // damit endSession() niemals vor appendPreroll() ausgefuehrt wird (Race-Condition fix).
    private val sessionDispatcher = Dispatchers.IO.limitedParallelism(1)

    // SpectrogramState lebt im ViewModel, wird per Referenz in UiState geteilt.
    // T56: DynamicRangeMapper haengt an den State, wird ueber appendFrames() gefuettert.
    private val spectrogramState = SpectrogramState(maxFrames = 2048).apply {
        dynamicRangeMapper = DynamicRangeMapper()
    }

    // ML-Pipeline: InferenceWorker mit Callback → DetectionListState + Embedding-Arm
    // T51: Callback wird bei jedem vollstaendigen 3s-Block aufgerufen (Daueraufnahme).
    // detections == null → Interval-Skip oder keine Treffer; Audio wird trotzdem geschrieben.
    private val inferenceWorker = InferenceWorker(classifier, regionalFilter) { audioBlock, detections ->

        // --- Audio immer speichern (T51: Daueraufnahme) ---
        // startSec VOR dem Append erfassen fuer korrekte Detection-Zeitstempel.
        val startSec = if (sessionManager.isActive) sessionManager.getCurrentRecordingOffsetSec() else 0f
        if (sessionManager.isActive) {
            // T61: Original-PCM verwenden statt Float→Short-Rueckkonvertierung (spart 144k Konvertierungen pro Block)
            val shortSamples = audioBlock.pcm ?: ShortArray(audioBlock.samples.size) { i ->
                (audioBlock.samples[i] * Short.MAX_VALUE).toInt().coerceIn(
                    Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
                ).toShort()
            }
            // F3: sessionDispatcher (Single-Thread) serialisiert Preroll + Live-Chunks (T63)
            // Verhindert concurrent append() auf StreamingWavWriter (nicht thread-safe)
            viewModelScope.launch(sessionDispatcher) {
                sessionManager.appendAudioSamples(shortSamples)
            }
        }

        // --- Detektionsverarbeitung (nur wenn Ergebnisse vorliegen) ---
        if (detections != null && detections.isNotEmpty()) {
            // GPS-Position an Detektionen anhaengen
            val loc = locationProvider.location.value
            val locDetections = if (loc != null) {
                detections.map { it.copy(latitude = loc.latitude, longitude = loc.longitude) }
            } else {
                detections
            }
            // Top-Ergebnis mit Kandidaten bestücken (T27)
            // InferenceWorker liefert Top-K als separate DetectionResults.
            // Nur das Top-Ergebnis behalten, Rest wird zu Kandidaten.
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
            // Artnamen in gewaehlter Sprache uebersetzen (T26) + Kandidaten-Namen (T27)
            val geoDetections = topWithCandidates.map { det ->
                det.copy(
                    commonName = speciesNameResolver.resolve(det.scientificName),
                    candidates = det.candidates.map { cand ->
                        cand.copy(commonName = speciesNameResolver.resolve(cand.scientificName))
                    }
                )
            }
            // Session-relative Zeitstempel setzen (T49)
            // startSec wurde vor dem Audio-Append erfasst → korrekte Offset-Berechnung.
            val durationSec = audioBlock.samples.size.toFloat() / audioBlock.sampleRate.toFloat()
            val sessionRelativeDetections = if (sessionManager.isActive) {
                geoDetections.map { det ->
                    det.copy(
                        chunkStartSec = startSec,
                        chunkEndSec = startSec + durationSec
                    )
                }
            } else {
                geoDetections
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Detection-Offset: chunkStartSec=${sessionRelativeDetections.firstOrNull()?.chunkStartSec}")
            }
            // T70: Cluster-Algorithmus — addOrCluster statt addDetections
            val newDetections = mutableListOf<DetectionResult>()
            for (det in sessionRelativeDetections) {
                // T73: gesperrte Art → Detection verwerfen (AC3)
                val speciesKey = det.scientificName.replace(' ', '_')
                if (detectionListState.isLocked(speciesKey)) continue

                val result = detectionListState.addOrCluster(
                    det,
                    appPreferences.clusterEnabled,
                    appPreferences.clusterRadiusM,
                    appPreferences.clusterTimeWindowMin
                )
                when (result) {
                    is ClusterResult.New -> newDetections.add(result.detection)
                    is ClusterResult.Merged -> {
                        if (sessionManager.isActive) {
                            val anchor = result.anchor
                            val absorbedId = result.absorbedId
                            viewModelScope.launch(Dispatchers.IO) {
                                sessionManager.replaceDetection(anchor)
                                sessionManager.appendVerification(
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

            // Watchlist-Check: Alarm ausloesen fuer neue Detektionen (T20)
            // Cluster-Merges alarmieren nicht erneut (Cooldown via alarmCooldownMinutes als Fallback).
            for (detection in newDetections) {
                val priority = watchlistManager.getPriority(detection.scientificName)
                if (priority != null) {
                    Log.i(TAG, "WATCHLIST-MATCH: ${detection.commonName} ($priority)")
                    alarmService.triggerAlarm(detection, priority)
                }
            }

            // Neue Detektionen in JSONL schreiben (Cluster-Merges werden via replaceDetection persistiert)
            if (sessionManager.isActive && newDetections.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) {
                    sessionManager.appendDetections(newDetections)
                }
            }

            // Embedding-Arm: Bei erfolgreicher Detektion Embedding extrahieren
            viewModelScope.launch(Dispatchers.Default) {
                // T61: Embedding aus der Klassifikations-Inference wiederverwenden (keine zweite Inference)
                val embedding = embeddingExtractor.extract(
                    audioBlock.samples, audioBlock.sampleRate, precomputed = audioBlock.embedding
                )
                val topDetection = sessionRelativeDetections.maxByOrNull { it.confidence }!!
                embeddingDatabase.add(
                    recordingId = topDetection.id,
                    species = topDetection.scientificName,
                    embedding = embedding
                )
                // UiState aktualisieren
                _uiState.update { it.copy(embeddingDbSize = embeddingDatabase.size) }
                // Aehnlichkeitssuche ausloesen (filtert auf gleiche Art)
                findSimilar(embedding, topDetection.scientificName)
                // recentSpecies fuer Marquee-Mode aktualisieren
                val recent = detectionListState.getDetections()
                    .take(5)
                    .map { speciesNameResolver.resolve(it.scientificName) }
                _uiState.update { it.copy(recentSpecies = recent) }
                // Periodisch persistieren (alle 10 neue Eintraege)
                if (embeddingDatabase.size % 10 == 0) {
                    saveEmbeddingDatabase()
                }
            }
        }
    }

    init {
        // Modell-Verfuegbarkeit pruefen
        val modelAvailable = classifier.isModelAvailable()
        if (!modelAvailable) {
            Log.w(TAG, "BirdNET-Modell nicht verfuegbar — ML-Pipeline deaktiviert")
        }

        // InferenceConfig aus SharedPreferences laden (statt Default)
        val savedConfig = appPreferences.loadInferenceConfig()
        inferenceWorker.config = savedConfig

        // Region laden (aus gespeicherter Config)
        val region = savedConfig.regionFilter ?: "ch_breeding"
        regionalFilter.loadRegion(region)
        Log.d(TAG, "Regionaler Filter geladen: ${regionalFilter.getCurrentRegionId()} " +
                "(${regionalFilter.getSpeciesList().size} Arten)")

        // Artennamen-Sprache laden + species_master.json parsen (T26, T32-Fix)
        // Synchron laden: species_master.json ist in assets (~200ms), muss VOR erster
        // Inference abgeschlossen sein, sonst werden lateinische Namen angezeigt.
        runBlocking(Dispatchers.IO) { speciesNameResolver.load() }
        speciesNameResolver.language = appPreferences.speciesLanguage
        Log.i(TAG, "SpeciesNameResolver: ${speciesNameResolver.resolve("Turdus merula")}")

        // Upload-Einstellungen aus SharedPreferences laden
        uploadManager.wifiOnly = appPreferences.wifiOnly
        uploadManager.autoUpload = appPreferences.autoUpload

        // SpectrogramConfig + Palette aus AppPreferences laden (T30)
        val savedSpecConfig = when (appPreferences.spectrogramConfigName) {
            "BATS" -> SpectrogramConfig.BATS
            "WIDEBAND" -> SpectrogramConfig.WIDEBAND
            else -> SpectrogramConfig.BIRDS
        }
        val savedPalette = try {
            SpectrogramPalette.valueOf(appPreferences.paletteName)
        } catch (_: IllegalArgumentException) {
            SpectrogramPalette.MAGMA
        }

        // T56: Sonogramm-Dynamik (Auto-Kontrast / manuelle Range) aus Prefs.
        val savedAutoContrast = appPreferences.spectrogramAutoContrast
        val savedMinDb = appPreferences.spectrogramMinDb
        val savedMaxDb = appPreferences.spectrogramMaxDb
        // T56b: Gamma-Kompression + Lautstärke-Deckel aus Prefs.
        val savedGamma = appPreferences.spectrogramGamma
        val savedCeilingDb = appPreferences.spectrogramCeilingDb
        // T52: Top-N Kandidaten-Anzeige aus Prefs.
        val savedShowTopN = appPreferences.showTopNCandidates

        // Initiale State-Werte setzen
        _uiState.update {
            it.copy(
                spectrogramState = spectrogramState,
                spectrogramConfig = savedSpecConfig,
                palette = savedPalette,
                detectionListState = detectionListState,
                isModelAvailable = modelAvailable,
                inferenceConfig = savedConfig,
                isEmbeddingAvailable = modelAvailable,
                embeddingDbSize = embeddingDatabase.size,
                spectrogramAutoContrast = savedAutoContrast,
                spectrogramMinDb = savedMinDb,
                spectrogramMaxDb = savedMaxDb,
                spectrogramGamma = savedGamma,
                spectrogramCeilingDb = savedCeilingDb,
                showTopNCandidates = savedShowTopN,
                similarEnabled = appPreferences.similarEnabled,
                similarLines = appPreferences.similarLines,
                isPrerollEnabled = appPreferences.prerollEnabled
            )
        }

        // Watchlist laden (T20) + reaktiv beobachten (T21)
        viewModelScope.launch(Dispatchers.IO) {
            watchlistManager.load()
        }
        viewModelScope.launch {
            watchlistManager.watchedSpeciesFlow.collect { species ->
                _uiState.update { it.copy(watchlistSpecies = species) }
            }
        }

        // Location-StateFlow beobachten → UiState aktualisieren
        startLocationObserver()

        // Upload-Status beobachten → UiState aktualisieren
        startUploadStatusObserver()

        // T69: 60s-Cap-Signal → Snackbar
        viewModelScope.launch {
            voiceMemoRecorder.capReached.collect {
                _snackbarChannel.trySend(SnackbarEvent("Memo auf 60s gekürzt"))
            }
        }
    }

    // --- Service Connection ---
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val recordingBinder = binder as? RecordingService.RecordingBinder
            service = recordingBinder?.getService()
            _uiState.update { it.copy(isServiceBound = true) }

            // Recording-State vom Service beobachten
            observeRecordingState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            stopCollectionJob()
            stopInferenceJob()
            recordingObserverJob?.cancel()
            recordingObserverJob = null
            _uiState.update { it.copy(isServiceBound = false, isRecording = false) }
        }
    }

    // --- Actions (aufgerufen von LiveScreen) ---

    /**
     * Preroll-Buffer im RecordingService initialisieren (T35).
     * Startet die Oboe-Engine leise, damit der Ring-Buffer sich fuellt.
     */
    private fun initPrerollIfEnabled() {
        val svc = service ?: return
        if (appPreferences.prerollEnabled) {
            svc.initPreroll(
                sampleRate = 48000,
                prerollDurationSec = appPreferences.prerollDurationSec
            )
        }
    }

    /**
     * Aufnahme starten. Erstellt Foreground-Service und startet Audio-Capture.
     * Bei aktivem Preroll: gepufferte Samples als chunk_000 speichern + im Sonogramm darstellen.
     * @param sampleRate Ziel-Samplerate (Standard: 48kHz fuer Voegel)
     */
    fun startRecording(sampleRate: Int = 48000) {
        val svc = service ?: return
        val ctx = applicationContext ?: return
        if (svc.isRecording.value) return

        // F4: RECORD_AUDIO pruefen VOR Service-Start (verhindert startForegroundService ohne Mic-Permission, T63)
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return  // AudioPermissionHandler zeigt Permission-Dialog
        }

        // Sonogramm-Buffer leeren fuer Neustart
        spectrogramState.clear()
        resetMelSpectrogram()

        // Preroll-Samples VOR Aufnahmestart holen (T35)
        val prerollSamples = if (appPreferences.prerollEnabled) {
            svc.getPrerollSamples(appPreferences.prerollDurationSec * 1000)
        } else {
            ShortArray(0)
        }

        // Foreground-Service starten + Aufnahme beginnen
        val intent = Intent(ctx, RecordingService::class.java)
        ctx.startForegroundService(intent)
        svc.startRecording(sampleRate)

        // Preroll vormerken: wird nach startSession() angehaengt (T46 — Reihenfolge sicherstellen)
        // Sonogramm-Darstellung passiert hier, Datei-Schreiben in observeRecordingState
        if (prerollSamples.isNotEmpty()) {
            pendingPrerollSamples = prerollSamples
            // Preroll im Sonogramm darstellen
            val config = _uiState.value.spectrogramConfig
            val rate = svc.actualSampleRate.let { if (it > 0) it else sampleRate }
            // currentSampleRate setzen damit startCollectionPipeline() den Buffer nicht cleared (T54)
            currentSampleRate = rate
            // T54-Fix: Eigenes MelSpectrogram nur fuer einmalige Preroll-Anzeige verwenden.
            // Das shared melSpectrogram-Feld NICHT benutzen: observeRecordingState() kann via
            // Dispatchers.Main.immediate bereits waehrend svc.startRecording() feuern und dort
            // startCollectionPipeline() starten, welche mel.process() auf Dispatchers.Default ruft.
            // Zwei concurrent process()-Aufrufe auf demselben Objekt → ArrayIndexOutOfBoundsException.
            //
            // T61: Ganzer Preroll statt nur 5 s (T54-Fix-2). Der ANR kam vom quadratischen
            // process() (behoben in T61). Berechnung laeuft trotzdem im Hintergrund; die Frames
            // werden per prependFrames() VOR bereits eingetroffene Live-Frames gesetzt.
            // Begrenzt auf die Kapazitaet des Sonogramm-Buffers (~22 s) — mehr waere unsichtbar.
            val maxDisplaySamples = spectrogramState.maxFrames * config.hopSize + config.fftSize
            val displaySamples = if (prerollSamples.size > maxDisplaySamples) {
                prerollSamples.copyOfRange(prerollSamples.size - maxDisplaySamples, prerollSamples.size)
            } else {
                prerollSamples
            }
            val generation = spectrogramState.currentGeneration
            viewModelScope.launch(Dispatchers.Default) {
                val frames = MelSpectrogram(sampleRate = rate, config = config).process(displaySamples)
                spectrogramState.prependFrames(frames, expectedGeneration = generation)
            }
        }

        _uiState.update {
            it.copy(recordingStartElapsedRealtime = SystemClock.elapsedRealtime())
        }
    }

    /** Aufnahme stoppen. */
    fun stopRecording() {
        val svc = service ?: return
        if (!svc.isRecording.value) return
        svc.stopRecording()
        // Collection-Job und MelSpectrogram werden via Recording-Observer gestoppt
    }

    /** Toggle-Convenience (fuer FAB). */
    fun toggleRecording(sampleRate: Int = 48000) {
        val svc = service
        if (svc != null && svc.isRecording.value) {
            stopRecording()
        } else {
            startRecording(sampleRate)
        }
    }

    /** Preroll-Engine explizit starten (F5). Nur wenn kein Recording laeuft. */
    fun userStartPreroll() {
        val svc = service ?: return
        if (svc.isRecording.value) return
        svc.initPreroll(
            sampleRate = 48000,
            prerollDurationSec = appPreferences.prerollDurationSec
        )
        prerollRunning = true
        _uiState.update { it.copy(isPrerollRunning = true) }
    }

    /** Preroll-Engine explizit stoppen (F5). */
    fun userStopPreroll() {
        val svc = service ?: return
        svc.stopPreroll()
        prerollRunning = false
        _uiState.update { it.copy(isPrerollRunning = false) }
    }

    /** Zeigt Hinweis-Snackbar wenn Stop-FAB kurz getippt wird (F7). */
    fun showLongPressHint() {
        viewModelScope.launch {
            _snackbarChannel.send(SnackbarEvent(message = "Lang drücken zum Stoppen"))
        }
    }

    /**
     * Reiht die letzte abgeschlossene Session zum Upload ein.
     */
    fun uploadLastSession() {
        val sessions = sessionManager.listSessions()
        if (sessions.isEmpty()) return
        uploadManager.enqueue(sessions.first())
    }

    /**
     * Verifiziert eine Detektion (Bestaetigen/Ablehnen/Korrigieren).
     * Aktualisiert DetectionListState + persistiert in verifications.jsonl.
     * Zeigt Snackbar mit Undo-Action (T52).
     */
    fun verifyDetection(
        detectionId: String,
        status: VerificationStatus,
        correctedSpecies: String? = null
    ) {
        // Aktuellen Zustand fuer Undo merken
        val detection = detectionListState.getDetections().find { it.id == detectionId }
        pendingUndo = detection?.let {
            UndoEntry(detectionId, it.verificationStatus, it.correctedSpecies)
        }

        // In-Memory aktualisieren
        detectionListState.updateVerification(detectionId, status, correctedSpecies)

        // Snackbar senden (T52)
        val name = detection?.commonName ?: ""
        val message = when (status) {
            VerificationStatus.CONFIRMED  -> "\u2713 $name bestaetigt"
            VerificationStatus.REJECTED   -> "\u2717 $name abgelehnt"
            VerificationStatus.UNCERTAIN  -> "? $name als unsicher markiert"
            VerificationStatus.CORRECTED  -> "\u270E $name \u2192 ${correctedSpecies ?: "?"}"
            else -> name
        }
        viewModelScope.launch {
            _snackbarChannel.send(SnackbarEvent(
                message = message,
                actionLabel = "R\u00FCckg\u00E4ngig",
                onAction = { undoLastVerification() }
            ))
        }

        // Persistieren (falls Session aktiv)
        if (sessionManager.isActive) {
            val undoSnapshot = pendingUndo
            viewModelScope.launch(Dispatchers.IO) {
                verificationMutex.withLock {
                    try {
                        sessionManager.appendVerification(
                            VerificationEvent(
                                detectionId = detectionId,
                                status = status,
                                correctedSpecies = correctedSpecies,
                                verifiedAtMs = System.currentTimeMillis()
                            )
                        )
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            if (undoSnapshot != null && undoSnapshot.detectionId == detectionId) {
                                detectionListState.updateVerification(
                                    undoSnapshot.detectionId,
                                    undoSnapshot.previousStatus,
                                    undoSnapshot.previousCorrected
                                )
                            }
                            _snackbarChannel.send(SnackbarEvent(message = "Speichern fehlgeschlagen"))
                        }
                    }
                }
            }
        }
    }

    /**
     * Macht die letzte Verifikations-Aktion rueckgaengig (T52).
     * Stellt vorherigen Status wieder her + entfernt letzten verifications.jsonl-Eintrag.
     */
    fun undoLastVerification() {
        val undo = pendingUndo ?: return
        pendingUndo = null
        detectionListState.updateVerification(undo.detectionId, undo.previousStatus, undo.previousCorrected)
        if (sessionManager.isActive) {
            viewModelScope.launch(Dispatchers.IO) {
                verificationMutex.withLock {
                    try {
                        sessionManager.removeLastVerification(undo.detectionId)
                    } catch (_: Exception) { /* Undo-IO-Fehler still ignorieren */ }
                }
            }
        }
    }

    /** Atlas-Codes aus assets (T68), lazy geladen, fuer EditDetectionDialog */
    val atlasCodes: List<AtlasCode> by lazy { atlasCodeRepository.loadCodes() }

    /**
     * Bearbeitet Stückzahl, Atlascode und Bemerkung einer Detektion (T68).
     * Optimistisches UI-Update + atomares IO + Rollback bei Fehler.
     */
    fun editDetection(
        detectionId: String,
        count: Int,
        atlasCode: String?,
        note: String?,
        voiceMemoFile: String? = null  // T69: relativer Pfad oder null (kein/gelöschtes Memo)
    ) {
        val current = detectionListState.getDetections().find { it.id == detectionId } ?: return
        val updated = current.copy(
            count = count,
            atlasCode = atlasCode,
            note = note,
            voiceMemoFile = voiceMemoFile
        )
        detectionListState.updateDetection(updated)
        if (!sessionManager.isActive) return
        viewModelScope.launch(Dispatchers.IO) {
            verificationMutex.withLock {
                try {
                    sessionManager.replaceDetection(updated)
                    val now = System.currentTimeMillis()
                    if (current.count != count) sessionManager.appendVerification(
                        VerificationEvent(detectionId, current.verificationStatus,
                            verifiedAtMs = now, actionType = VerificationEvent.ACTION_COUNT_CHANGED))
                    if (current.atlasCode != atlasCode) sessionManager.appendVerification(
                        VerificationEvent(detectionId, current.verificationStatus,
                            verifiedAtMs = now, actionType = VerificationEvent.ACTION_ATLAS_SET))
                    if (current.note != note) sessionManager.appendVerification(
                        VerificationEvent(detectionId, current.verificationStatus,
                            verifiedAtMs = now, actionType = VerificationEvent.ACTION_NOTE_ADDED))
                    // T69: Memo-Events
                    if (current.voiceMemoFile == null && voiceMemoFile != null) {
                        sessionManager.appendVerification(VerificationEvent(
                            detectionId, current.verificationStatus,
                            verifiedAtMs = now,
                            actionType = VerificationEvent.ACTION_MEMO_RECORDED,
                            newValue = voiceMemoFile))
                    }
                    if (current.voiceMemoFile != null && voiceMemoFile == null) {
                        getMemoTargetFile(detectionId)?.takeIf { it.exists() }?.delete()
                        sessionManager.appendVerification(VerificationEvent(
                            detectionId, current.verificationStatus,
                            verifiedAtMs = now,
                            actionType = VerificationEvent.ACTION_MEMO_DELETED))
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        detectionListState.updateDetection(current)
                        _snackbarChannel.trySend(SnackbarEvent("Speichern fehlgeschlagen"))
                    }
                }
            }
        }
    }

    /**
     * Sperrt oder entsperrt eine Art (T73).
     * Gesperrte Art: weitere Inference-Detektionen dieser Art werden unterdrueckt (AC3).
     * Persistiert lockSpecies-Flag + loggt ACTION_SPECIES_LOCKED (AC5/AC7).
     */
    fun toggleLock(detectionId: String) {
        val det = detectionListState.getDetections().find { it.id == detectionId } ?: return
        val updated = det.copy(lockSpecies = !det.lockSpecies)
        detectionListState.updateDetection(updated)

        val speciesKey = det.scientificName.replace(' ', '_')
        val newLocked = if (updated.lockSpecies) lockedSpecies.value + speciesKey
                        else lockedSpecies.value - speciesKey
        lockedSpecies.value = newLocked

        if (!sessionManager.isActive) return
        viewModelScope.launch(Dispatchers.IO) {
            verificationMutex.withLock {
                try {
                    sessionManager.replaceDetection(updated)
                    sessionManager.appendVerification(
                        VerificationEvent(
                            detectionId = detectionId,
                            status = det.verificationStatus,
                            verifiedAtMs = System.currentTimeMillis(),
                            actionType = VerificationEvent.ACTION_SPECIES_LOCKED,
                            newValue = updated.lockSpecies.toString()
                        )
                    )
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        // Rollback: State zurueck auf vorherigen Wert
                        detectionListState.updateDetection(det)
                        lockedSpecies.value = if (det.lockSpecies) lockedSpecies.value + speciesKey
                                              else lockedSpecies.value - speciesKey
                        _snackbarChannel.trySend(SnackbarEvent("Speichern fehlgeschlagen"))
                    }
                }
            }
        }
    }

    /** T69: Memo-Zieldatei fuer File-Modus berechnen. */
    fun getMemoTargetFile(detectionId: String): File? =
        sessionManager.currentSessionDir?.let { sessionManager.memoFile(it, detectionId) }

    /** T69: Memo-Ziel-URI fuer SAF-Modus berechnen. */
    fun getMemoTargetUri(detectionId: String): android.net.Uri? =
        sessionManager.currentSessionDoc?.let { sessionManager.memoDocFile(it, detectionId)?.uri }

    /**
     * Fuegt eine Art manuell zur Detektions-Liste hinzu (T72).
     * Bypass der Cluster-Logik, manualEntry=true, confidence=1.0.
     */
    fun addManualDetection(
        speciesKey: String,
        count: Int = 1,
        atlasCode: String? = null,
        note: String? = null,
        lat: Double? = _uiState.value.currentLatitude,
        lon: Double? = _uiState.value.currentLongitude
    ) {
        val now = System.currentTimeMillis()
        val det = DetectionResult(
            id = java.util.UUID.randomUUID().toString(),
            scientificName = speciesKey,
            commonName = speciesNameResolver.resolve(speciesKey),
            confidence = 1.0f,
            timestampMs = now,
            chunkStartSec = 0f,
            chunkEndSec = 0f,
            sampleRate = 0,
            latitude = lat,
            longitude = lon,
            firstSeenAtMs = now,
            lastSeenAtMs = now,
            lastDetectedMs = now,
            count = count,
            atlasCode = atlasCode,
            note = note,
            manualEntry = true
        )
        detectionListState.addManualDetection(det)
        if (!sessionManager.isActive) return
        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.appendDetections(listOf(det))
            sessionManager.appendVerification(
                VerificationEvent(
                    detectionId = det.id,
                    status = VerificationStatus.CONFIRMED,
                    verifiedAtMs = now,
                    actionType = VerificationEvent.ACTION_MANUAL_ADD,
                    newValue = "species:$speciesKey|count:$count"
                )
            )
        }
    }

    /** Artsuche fuer AddManualDetectionDialog (T72). */
    fun searchSpecies(query: String): List<Pair<String, String>> =
        speciesNameResolver.searchSpecies(query)

    /**
     * FAB-Tap mit 500 ms Debounce (T52).
     * @return true wenn der Tap verarbeitet werden soll, false wenn gedebounct.
     */
    fun onFabTap(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastFabTapAt < 500L) return false
        lastFabTapAt = now
        return true
    }

    /**
     * Ersetzt eine Detektion durch eine vom Nutzer gewaehlte Alternative (T45).
     *
     * Markiert das Original als REPLACED, fuegt die Alternative als neue Detektion
     * an Index 0 ein und schreibt ein VerificationEvent in verifications.jsonl.
     * Zeigt Snackbar mit Undo-Action (T52).
     */
    fun selectAlternative(detectionId: String, candidate: DetectionCandidate) {
        // Aktuellen Zustand fuer Undo merken
        val original = detectionListState.getDetections().find { it.id == detectionId }
        pendingUndo = original?.let {
            UndoEntry(detectionId, it.verificationStatus, it.correctedSpecies)
        }

        val success = detectionListState.selectAlternative(detectionId, candidate)
        if (success) {
            // Snackbar senden (T52)
            viewModelScope.launch {
                _snackbarChannel.send(SnackbarEvent(
                    message = "Alternative: ${candidate.commonName} gew\u00E4hlt",
                    actionLabel = "R\u00FCckg\u00E4ngig",
                    onAction = { undoLastVerification() }
                ))
            }
            viewModelScope.launch(Dispatchers.IO) {
                verificationMutex.withLock {
                    try {
                        sessionManager.appendVerification(
                            VerificationEvent(
                                detectionId = detectionId,
                                status = VerificationStatus.REPLACED,
                                correctedSpecies = candidate.scientificName,
                                verifiedAtMs = System.currentTimeMillis(),
                                actionType = VerificationEvent.ACTION_ALTERNATIVE_CONFIRMED  // T72
                            )
                        )
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            _snackbarChannel.send(SnackbarEvent(message = "Speichern fehlgeschlagen"))
                        }
                    }
                }
            }
        }
    }

    /**
     * Speichert eine verifizierte Detektion als Referenz-Aufnahme.
     * Kopiert den zugehoerigen WAV-Chunk in die Referenzbibliothek.
     */
    fun saveAsReference(detectionId: String) {
        val detection = detectionListState.getDetections().find { it.id == detectionId } ?: return

        val sessions = sessionManager.listSessions()
        if (sessions.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            // Chunk-Index aus Timestamp ableiten (jeder Chunk = 3s)
            val sessionDir = sessions.first()
            val metadataFile = File(sessionDir, "session.json")
            if (!metadataFile.exists()) return@launch

            val metadata = try {
                val metaJson = KJson { ignoreUnknownKeys = true }
                metaJson.decodeFromString<SessionMetadata>(metadataFile.readText())
            } catch (e: Exception) {
                Log.e(TAG, "Session-Metadata lesen fehlgeschlagen", e)
                return@launch
            }

            // Chunk-Index: (detectionTime - sessionStart) / 3000ms
            val startMs = try {
                java.time.OffsetDateTime.parse(metadata.startedAt).toInstant().toEpochMilli()
            } catch (_: Exception) {
                java.time.Instant.parse(metadata.startedAt).toEpochMilli()
            }
            val chunkIndex = ((detection.timestampMs - startMs) / CHUNK_DURATION_MS).toInt().coerceAtLeast(0)

            val result = referenceRepository.addFromDetection(detection, sessionDir, chunkIndex)
            if (result != null) {
                Log.i(TAG, "Referenz gespeichert: ${result.commonName} (${result.wavFileName})")
            }
        }
    }

    /** Palette zykeln: MAGMA → VIRIDIS → GRAYSCALE → MAGMA */
    fun setPalette(palette: SpectrogramPalette) {
        _uiState.update { it.copy(palette = palette) }
    }

    /** SpectrogramConfig wechseln (z.B. BIRDS → BATS). Leert Sonogramm da FFT-Parameter sich aendern. */
    fun setSpectrogramConfig(config: SpectrogramConfig) {
        _uiState.update { it.copy(spectrogramConfig = config) }
        // Buffer leeren — FFT-Parameter aendern sich, alte Frames passen nicht mehr
        spectrogramState.clear()
        // MelSpectrogram muss mit neuer Config neu erstellt werden
        val rate = currentSampleRate
        if (rate > 0) {
            melSpectrogram = MelSpectrogram(sampleRate = rate, config = config)
        }
    }

    /** Sonogramm leeren (ohne Aufnahme zu stoppen). */
    fun clearSpectrogram() {
        spectrogramState.clear()
    }

    /**
     * T56/T56b: Liest die Sonogramm-Dynamik-Einstellungen erneut aus den SharedPreferences
     * und pusht sie in den UiState. Wird vom LiveScreen bei ON_RESUME aufgerufen, damit
     * Settings-Aenderungen ohne App-Neustart wirksam werden.
     */
    fun reloadSpectrogramPrefs() {
        val autoContrast = appPreferences.spectrogramAutoContrast
        val minDb = appPreferences.spectrogramMinDb
        val maxDb = appPreferences.spectrogramMaxDb
        val gamma = appPreferences.spectrogramGamma
        val ceilingDb = appPreferences.spectrogramCeilingDb
        val showTopN = appPreferences.showTopNCandidates  // T52: bei Resume neu lesen
        _uiState.update {
            it.copy(
                spectrogramAutoContrast = autoContrast,
                spectrogramMinDb = minDb,
                spectrogramMaxDb = maxDb,
                spectrogramGamma = gamma,
                spectrogramCeilingDb = ceilingDb,
                showTopNCandidates = showTopN
            )
        }
    }

    /** Permission-Status setzen (vom PermissionHandler aufgerufen). */
    fun setPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionGranted = granted) }
    }

    /** Location-Permission-Status setzen (T52: GPS-Bar Tap-Aktion). */
    fun setLocationPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(locationPermissionGranted = granted) }
    }

    /** Confidence-Schwelle aendern (0.0 – 1.0) */
    fun setConfidenceThreshold(threshold: Float) {
        val clamped = threshold.coerceIn(0.1f, 0.9f)
        val newConfig = _uiState.value.inferenceConfig.copy(confidenceThreshold = clamped)
        setInferenceConfig(newConfig)
    }

    /** Regionalen Artenfilter setzen oder deaktivieren */
    fun setRegionFilter(regionId: String?) {
        if (regionId != null) {
            regionalFilter.loadRegion(regionId)
        }
        val newConfig = _uiState.value.inferenceConfig.copy(regionFilter = regionId)
        setInferenceConfig(newConfig)
    }

    /** Gesamte InferenceConfig setzen (z.B. fuer Presets) */
    fun setInferenceConfig(config: InferenceConfig) {
        // Region laden falls geaendert
        if (config.regionFilter != null && config.regionFilter != _uiState.value.inferenceConfig.regionFilter) {
            regionalFilter.loadRegion(config.regionFilter)
        }
        inferenceWorker.config = config
        _uiState.update { it.copy(inferenceConfig = config) }
        // In SharedPreferences persistieren
        appPreferences.saveInferenceConfig(config)
        Log.d(TAG, "InferenceConfig aktualisiert: threshold=${config.confidenceThreshold}, " +
                "topK=${config.topK}, region=${config.regionFilter}")
    }

    // --- Service-Binding ---

    /**
     * Service binden. Wird von LiveScreen in DisposableEffect aufgerufen.
     * Nutzt Application-Context um Context-Leak zu vermeiden.
     */
    fun bindService(context: Context) {
        val appCtx = context.applicationContext
        applicationContext = appCtx
        val intent = Intent(appCtx, RecordingService::class.java)
        appCtx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Embedding-DB einmalig laden (applicationContext ist jetzt gesetzt)
        if (!embeddingDbLoaded) {
            embeddingDbLoaded = true
            loadEmbeddingDatabase()
        }
    }

    /**
     * Service entbinden. Wird von LiveScreen in DisposableEffect.onDispose aufgerufen.
     */
    fun unbindService(context: Context) {
        val appCtx = context.applicationContext
        try {
            appCtx.unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
            // Service war nicht gebunden — ignorieren
        }
        service = null
        _uiState.update { it.copy(isServiceBound = false) }
    }

    // --- Embedding-Aehnlichkeitssuche ---

    /**
     * Sucht aehnliche Referenz-Aufnahmen fuer das uebergebene Embedding.
     * Ergebnis wird in LiveUiState.similarMatches geschrieben.
     */
    private suspend fun findSimilar(embedding: FloatArray, speciesFilter: String? = null) {
        val lines = appPreferences.similarLines
        val enabled = appPreferences.similarEnabled
        if (!enabled || lines == 0f) {
            _uiState.update { it.copy(similarMatches = emptyList()) }
            return
        }
        val fetchN = maxOf(20, (lines * 4).toInt())
        val filter = speciesFilter?.let { setOf(it) }
        val raw = embeddingDatabase.search(query = embedding, topN = fetchN, speciesFilter = filter)
        val displayN = if (lines < 1f) 5 else lines.toInt()
        val deduped = raw
            .groupBy { it.species }
            .map { (_, group) -> group.maxByOrNull { it.similarity }!! }
            .sortedByDescending { it.similarity }
            .take(displayN)
            .mapIndexed { i, m -> m.copy(rank = i + 1) }
        _uiState.update { it.copy(similarMatches = deduped) }
    }

    // --- Embedding-DB Persistenz ---

    private fun loadEmbeddingDatabase() {
        val file = getEmbeddingDbFile() ?: return
        // T61: Einmalig alte Embedding-Basis verwerfen (Backup bleibt als .v1.bak liegen)
        if (appPreferences.embeddingDbVersion < EMBEDDING_DB_VERSION) {
            if (file.exists()) {
                val backup = File(file.parentFile, file.name + ".v1.bak")
                val moved = file.renameTo(backup)
                if (!moved) file.delete()  // Backup existiert schon o.ae. → alte DB trotzdem verwerfen
                Log.i(TAG, "Embedding-DB auf neue Basis umgestellt — alte DB gesichert: $moved")
            }
            embeddingDatabase.clear()
            appPreferences.embeddingDbVersion = EMBEDDING_DB_VERSION
            _uiState.update { it.copy(embeddingDbSize = 0) }
        }
        if (file.exists()) {
            try {
                embeddingDatabase.load(file)
                _uiState.update { it.copy(embeddingDbSize = embeddingDatabase.size) }
                Log.i(TAG, "Embedding-DB geladen: ${embeddingDatabase.size} Eintraege")
            } catch (e: Exception) {
                Log.e(TAG, "Embedding-DB laden fehlgeschlagen", e)
            }
        }
    }

    private fun saveEmbeddingDatabase() {
        viewModelScope.launch(Dispatchers.IO) {
            val file = getEmbeddingDbFile() ?: return@launch
            file.parentFile?.mkdirs()
            try {
                embeddingDatabase.save(file)
                Log.d(TAG, "Embedding-DB gespeichert: ${embeddingDatabase.size} Eintraege")
            } catch (e: Exception) {
                Log.e(TAG, "Embedding-DB speichern fehlgeschlagen", e)
            }
        }
    }

    private fun getEmbeddingDbFile(): File? {
        val ctx = applicationContext ?: return null
        return File(ctx.filesDir, "embeddings/embeddings.bsed")
    }

    // --- Interne Pipeline ---

    /**
     * Beobachtet den Recording-State vom Service.
     * Startet/stoppt die Collection-Pipeline entsprechend.
     * T52: Auch RecordingPhase beobachten fuer FAB-3-State.
     */
    private fun observeRecordingState() {
        recordingObserverJob?.cancel()
        recordingObserverJob = viewModelScope.launch {
            // T52: RecordingPhase → UiState
            launch {
                service?.recordingPhase?.collect { phase ->
                    _uiState.update { it.copy(recordingPhase = phase) }
                }
            }
            // T63 AC5: Audio-Engine-Fehler als Snackbar anzeigen
            launch {
                service?.audioStartError?.collect { msg ->
                    _snackbarChannel.send(SnackbarEvent(message = msg))
                }
            }
            service?.isRecording?.collect { recording ->
                _uiState.update {
                    it.copy(
                        isRecording = recording,
                        actualSampleRate = service?.actualSampleRate ?: 0
                    )
                }
                if (recording) {
                    locationProvider.start()
                    startCollectionPipeline()
                    startInferencePipeline()
                    // Session nur starten wenn keine aktive Session (verhindert Doppel-Session bei Tab-Wechsel, F1+F2)
                    if (!sessionManager.isActive) {
                        viewModelScope.launch(sessionDispatcher) {
                            val loc = locationProvider.location.value
                            val config = _uiState.value.inferenceConfig
                            val sessionId = sessionManager.startSession(
                                sampleRate = service?.actualSampleRate ?: 48000,
                                latitude = loc?.latitude,
                                longitude = loc?.longitude,
                                regionFilter = config.regionFilter,
                                confidenceThreshold = config.confidenceThreshold
                            )
                            currentSessionId = sessionId
                            // Fallback-Banner aktualisieren (T51)
                            _uiState.update { it.copy(storageUnavailableFallback = sessionManager.lastStartUsedFallback) }
                            // Preroll nach startSession() anhaengen (T46)
                            val preroll = pendingPrerollSamples
                            pendingPrerollSamples = ShortArray(0)
                            if (preroll.isNotEmpty()) {
                                sessionManager.appendAudioSamples(preroll)
                            }
                        }
                    }
                } else {
                    // T53: GpsStats VOR stop() erfassen — Zaehler bleiben bis naechsten start() erhalten
                    val gpsStats = locationProvider.buildGpsStats(
                        appPreferences.gpsIntervalSeconds * 1000L
                    )
                    locationProvider.stop()
                    stopCollectionJob()
                    stopInferenceJob()
                    resetMelSpectrogram()
                    // InferenceWorker Accumulator leeren
                    inferenceWorker.reset()
                    // Detektionsliste leeren — jede Aufnahme startet frisch (T33-AP5)
                    detectionListState.clear()
                    // Fallback-Banner zuruecksetzen (T51)
                    _uiState.update { it.copy(storageUnavailableFallback = false) }
                    // Session beenden + lastSessionId setzen (T15)
                    // sessionDispatcher: wartet auf startSession+appendPreroll, bevor endSession laeuft (T54)
                    val finishedSessionId = currentSessionId
                    viewModelScope.launch(sessionDispatcher) {
                        sessionManager.endSession(gpsStats)  // T53: GPS-Statistiken mitgeben
                        alarmService.clearCooldowns()  // AC6: Cooldown-Map pro Session leeren
                        if (finishedSessionId != null) {
                            _uiState.update { it.copy(lastSessionId = finishedSessionId) }
                        }
                        // Auto-Upload pruefen (T17)
                        if (uploadManager.autoUpload) {
                            val sessions = sessionManager.listSessions()
                            if (sessions.isNotEmpty()) {
                                uploadManager.enqueue(sessions.first())
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Startet die AudioChunk → MelSpectrogram → SpectrogramState Pipeline (DSP-Arm).
     * Laeuft auf Dispatchers.Default fuer DSP-Berechnungen.
     */
    private fun startCollectionPipeline() {
        stopCollectionJob()

        val svc = service ?: return
        val sampleRate = svc.actualSampleRate
        val config = _uiState.value.spectrogramConfig

        // MelSpectrogram erstellen/neu erstellen bei SampleRate-Aenderung
        if (sampleRate != currentSampleRate || melSpectrogram == null) {
            if (sampleRate != currentSampleRate) {
                // SampleRate hat sich geaendert (z.B. USB-Mikrofon) → Buffer resetten
                spectrogramState.clear()
            }
            currentSampleRate = sampleRate
            melSpectrogram = MelSpectrogram(sampleRate = sampleRate, config = config)
        }

        collectionJob = viewModelScope.launch(Dispatchers.Default) {
            val mel = melSpectrogram ?: return@launch
            svc.audioChunkFlow.collect { chunk ->
                val frames = mel.process(chunk.samples)
                if (frames.isNotEmpty()) {
                    spectrogramState.appendFrames(frames)
                }
            }
        }
    }

    /**
     * Startet die AudioChunk → InferenceWorker → DetectionListState Pipeline (ML-Arm).
     * Laeuft auf Dispatchers.Default — blockiert nicht den UI-Thread.
     *
     * Graceful Degradation: Wenn BirdNET-Modell nicht verfuegbar, wird der Job nicht gestartet.
     */
    private fun startInferencePipeline() {
        stopInferenceJob()

        if (!_uiState.value.isModelAvailable) {
            Log.d(TAG, "ML-Pipeline nicht gestartet — Modell nicht verfuegbar")
            return
        }

        val svc = service ?: return

        inferenceJob = viewModelScope.launch(Dispatchers.Default) {
            Log.d(TAG, "ML-Pipeline gestartet")
            svc.audioChunkFlow.collect { chunk ->
                inferenceWorker.processChunk(chunk)
            }
        }
    }

    /** Collection-Job stoppen (DSP-Arm). */
    private fun stopCollectionJob() {
        collectionJob?.cancel()
        collectionJob = null
    }

    /** Inference-Job stoppen (ML-Arm). */
    private fun stopInferenceJob() {
        inferenceJob?.cancel()
        inferenceJob = null
    }

    /** MelSpectrogram-Buffer zuruecksetzen. */
    private fun resetMelSpectrogram() {
        melSpectrogram?.reset()
    }

    // --- Location-Observer ---

    /**
     * Beobachtet LocationProvider.location und schreibt Aenderungen in UiState.
     */
    private fun startLocationObserver() {
        locationObserverJob?.cancel()
        locationObserverJob = viewModelScope.launch {
            locationProvider.location.collect { loc ->
                _uiState.update {
                    it.copy(
                        currentLatitude = loc?.latitude,
                        currentLongitude = loc?.longitude,
                        locationAccuracyM = loc?.accuracyM,
                        isLocationAvailable = loc != null,
                        lastLocationFixMs = loc?.timestampMs  // T52: GPS-Fix-Alter
                    )
                }
            }
        }
    }

    // --- Upload-Status-Observer ---

    /**
     * Beobachtet UploadManager.status und schreibt Aenderungen in UiState.
     */
    private fun startUploadStatusObserver() {
        viewModelScope.launch {
            uploadManager.status.collect { status ->
                _uiState.update { it.copy(uploadStatus = status) }
            }
        }
    }

    // --- Lifecycle ---

    override fun onCleared() {
        stopCollectionJob()
        stopInferenceJob()
        recordingObserverJob?.cancel()
        locationObserverJob?.cancel()
        locationProvider.stop()
        inferenceWorker.reset()
        // Cleanup-Scope BEVOR super.onCleared() den viewModelScope cancelt
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanupScope.launch {
            try {
                sessionManager.endSession()
                alarmService.clearCooldowns()
                // Finale Embedding-DB Sicherung
                val file = getEmbeddingDbFile() ?: return@launch
                file.parentFile?.mkdirs()
                try {
                    embeddingDatabase.save(file)
                    Log.d(TAG, "Embedding-DB gespeichert: ${embeddingDatabase.size} Eintraege")
                } catch (e: Exception) {
                    Log.e(TAG, "Embedding-DB speichern fehlgeschlagen", e)
                }
            } finally {
                cleanupScope.cancel()
            }
        }
        super.onCleared()
        // Service-Unbinding muss vom Screen gemacht werden (braucht Context)
        // T69: Voice-Memo Cleanup
        voiceMemoRecorder.release()
        memoAudioPlayer.release()
    }
}
