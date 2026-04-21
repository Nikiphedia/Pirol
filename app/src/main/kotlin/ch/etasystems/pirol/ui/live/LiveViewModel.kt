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
import ch.etasystems.pirol.audio.RecordingPhase
import ch.etasystems.pirol.audio.RecordingService
import ch.etasystems.pirol.audio.dsp.DynamicRangeMapper
import ch.etasystems.pirol.audio.dsp.MelSpectrogram
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.ReferenceRepository
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.data.repository.SessionMetadata
import ch.etasystems.pirol.data.sync.UploadManager
import kotlinx.serialization.json.Json as KJson
import ch.etasystems.pirol.location.LocationProvider
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.CHUNK_DURATION_MS
import ch.etasystems.pirol.ml.DetectionCandidate
import ch.etasystems.pirol.ml.DetectionListState
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
    private val speciesNameResolver: SpeciesNameResolver
) : ViewModel() {

    companion object {
        private const val TAG = "LiveViewModel"
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

    // FAB-Debounce (T52): verhindert Doppel-Taps innerhalb 500 ms
    private var lastFabTapAt = 0L

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
            val shortSamples = ShortArray(audioBlock.samples.size) { i ->
                (audioBlock.samples[i] * Short.MAX_VALUE).toInt().coerceIn(
                    Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()
                ).toShort()
            }
            viewModelScope.launch(Dispatchers.IO) {
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
            detectionListState.addDetections(sessionRelativeDetections)

            // Watchlist-Check: Alarm ausloesen fuer Watchlist-Arten (T20)
            for (detection in sessionRelativeDetections) {
                val priority = watchlistManager.getPriority(detection.scientificName)
                if (priority != null) {
                    Log.i(TAG, "WATCHLIST-MATCH: ${detection.commonName} ($priority)")
                    alarmService.triggerAlarm(detection, priority)
                }
            }

            // Detektionen in JSONL schreiben
            if (sessionManager.isActive) {
                viewModelScope.launch(Dispatchers.IO) {
                    sessionManager.appendDetections(sessionRelativeDetections)
                }
            }

            // Embedding-Arm: Bei erfolgreicher Detektion Embedding extrahieren
            viewModelScope.launch(Dispatchers.Default) {
                val embedding = embeddingExtractor.extract(audioBlock.samples, audioBlock.sampleRate)
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
                spectrogramCeilingDb = savedCeilingDb
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
    }

    // --- Service Connection ---
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val recordingBinder = binder as? RecordingService.RecordingBinder
            service = recordingBinder?.getService()
            _uiState.update { it.copy(isServiceBound = true) }

            // Preroll-Buffer starten falls aktiviert (T35)
            initPrerollIfEnabled()

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
            // T54-Fix-2: Sonogramm-Darstellung auf max. 5 s begrenzen.
            // process() laeuft synchron auf dem Main-Thread. Bei 30 s Preroll (1.440.000 Samples)
            // blockiert es den Main-Thread mehrere Sekunden → ANR. Die WAV enthaelt weiterhin alle
            // Preroll-Samples; nur die Darstellung wird auf die letzten 5 s gekuerzt.
            val maxDisplaySamples = 5 * rate
            val displaySamples = if (prerollSamples.size > maxDisplaySamples) {
                prerollSamples.copyOfRange(prerollSamples.size - maxDisplaySamples, prerollSamples.size)
            } else {
                prerollSamples
            }
            val prerollMel = MelSpectrogram(sampleRate = rate, config = config)
            val frames = prerollMel.process(displaySamples)
            if (frames.isNotEmpty()) {
                spectrogramState.appendFrames(frames)
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
            viewModelScope.launch(Dispatchers.IO) {
                sessionManager.appendVerification(
                    VerificationEvent(
                        detectionId = detectionId,
                        status = status,
                        correctedSpecies = correctedSpecies,
                        verifiedAtMs = System.currentTimeMillis()
                    )
                )
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
                sessionManager.removeLastVerification(undo.detectionId)
            }
        }
    }

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
                sessionManager.appendVerification(
                    VerificationEvent(
                        detectionId = detectionId,
                        status = VerificationStatus.REPLACED,
                        correctedSpecies = candidate.scientificName,
                        verifiedAtMs = System.currentTimeMillis()
                    )
                )
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
        _uiState.update {
            it.copy(
                spectrogramAutoContrast = autoContrast,
                spectrogramMinDb = minDb,
                spectrogramMaxDb = maxDb,
                spectrogramGamma = gamma,
                spectrogramCeilingDb = ceilingDb
            )
        }
    }

    /** Permission-Status setzen (vom PermissionHandler aufgerufen). */
    fun setPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionGranted = granted) }
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
        val filter = speciesFilter?.let { setOf(it) }
        val matches = embeddingDatabase.search(
            query = embedding,
            topN = 5,
            speciesFilter = filter
        )
        _uiState.update { it.copy(similarMatches = matches) }
    }

    // --- Embedding-DB Persistenz ---

    private fun loadEmbeddingDatabase() {
        val file = getEmbeddingDbFile() ?: return
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
                    // Session starten + Preroll anhaengen (sessionDispatcher: sequentiell mit endSession, T54)
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
                } else {
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
                        sessionManager.endSession()
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
                        isLocationAvailable = loc != null
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
    }
}
