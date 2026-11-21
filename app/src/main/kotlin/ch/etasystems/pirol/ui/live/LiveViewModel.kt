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
import ch.etasystems.pirol.audio.SampleRing
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
import ch.etasystems.pirol.data.sync.UploadStatus
import ch.etasystems.pirol.data.sync.UploadTargetFactory
import kotlinx.serialization.json.Json as KJson
import ch.etasystems.pirol.location.LocationProvider
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.AtlasCode
import ch.etasystems.pirol.ml.DetectionCandidate
import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.EmbeddingDatabase
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ml.WatchlistManager
import ch.etasystems.pirol.audio.AlarmService
import ch.etasystems.pirol.audio.EmbeddingDbStore
import ch.etasystems.pirol.audio.RecordingPipeline
import ch.etasystems.pirol.ui.components.SpectrogramState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.AppStrings

/**
 * ViewModel fuer den LiveScreen.
 *
 * Verwaltet die gesamte Audio→DSP→Sonogramm-Pipeline UND die ML-Inference-Pipeline:
 *
 * T94: Persistenz, Inference, Detektions-Verarbeitung, Alarm und GPS-Tagging laufen in der
 * [RecordingPipeline], die der RecordingService steuert (laeuft weiter ohne UI). Das ViewModel
 * beobachtet nur (Detektionen, Status, Embedding-Ereignisse) und sendet Befehle
 * (Start/Stopp, Preroll, Verifikation). Das Sonogramm (DSP-Arm) bleibt hier:
 *
 *   RecordingService.audioChunkFlow ──→ [DSP-Arm] MelSpectrogram → SpectrogramState (Canvas)
 *
 *
 * @param classifier Audio-Klassifizierer (via Koin)
 * @param regionalFilter Regionaler Artenfilter (via Koin)
 * @param detectionListState Thread-safe Detektions-Speicher (via Koin Singleton)
 * @param pipeline T94: Session-Pipeline im RecordingService (Persistenz, Inference, Detektionen)
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
    private val pipeline: RecordingPipeline,
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
    private val embeddingDbStore: EmbeddingDbStore,  // T94
    private val verificationService: ch.etasystems.pirol.ml.VerificationService,  // T137
    private val context: android.content.Context  // T69: fuer VoiceMemoRecorder SAF + memoAudioPlayer
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

    // --- T157: Undo nach Bestaetigen ueber den Alarm ---
    @Volatile private var screenVisible = false  // T157; T160b: steuert auch die Sonogramm-Berechnung

    init {
        verificationService.alarmUndo.onRecorded = { viewModelScope.launch { offerAlarmUndo() } }
    }

    /** Vom LiveScreen bei ON_START/ON_STOP gesetzt; beim Sichtbarwerden wird ein frischer Eintrag angeboten. */
    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        if (visible) viewModelScope.launch { offerAlarmUndo() }
    }

    private suspend fun offerAlarmUndo() {
        if (!screenVisible) return
        val entry = verificationService.alarmUndo.take() ?: return
        _snackbarChannel.send(SnackbarEvent(
            message = AppStrings.get(R.string.live_bestaetigt, entry.commonName),
            actionLabel = AppStrings.get(R.string.common_undo),
            onAction = { viewModelScope.launch(Dispatchers.IO) { verificationService.undoAlarmConfirm(entry) } }
        ))
    }

    // --- T116: Detektion teilen ---
    private val _shareRequest = MutableStateFlow<ch.etasystems.pirol.data.export.DetectionShareRequest?>(null)
    val shareRequest: StateFlow<ch.etasystems.pirol.data.export.DetectionShareRequest?> = _shareRequest.asStateFlow()

    /** Oeffnet den Share-Dialog; Aufnahme der laufenden Session, nach Stopp die der letzten Session (T128). */
    fun requestShare(detectionId: String) {
        val detection = detectionListState.getDetections().firstOrNull { it.id == detectionId } ?: return
        val lastId = _uiState.value.lastSessionId
        // T136: Dateizugriffe (listSessions, Metadaten, Segmente) auf IO, UI bleibt reaktiv
        viewModelScope.launch(Dispatchers.IO) {
            val dir = sessionManager.currentSessionDir
                ?: lastId?.let { id -> sessionManager.listSessions().firstOrNull { it.name == id } }
            val wav = dir?.let { sessionManager.getRecordingFile(it) }
            if (dir == null || wav == null || !wav.exists()) {
                _snackbarChannel.trySend(SnackbarEvent(AppStrings.get(R.string.live_aufnahme_nicht_verfuegbar_teilen_in)))
                return@launch
            }
            val note = sessionManager.loadMetadata(dir)?.note
            // T130: alle WAV-Segmente der Session
            val recording = sessionManager.loadRecording(dir)
            _shareRequest.value = ch.etasystems.pirol.data.export.DetectionShareRequest(detection, wav, note, recording)
        }
    }

    fun clearShareRequest() { _shareRequest.value = null }

    fun postSnackbar(message: String) { _snackbarChannel.trySend(SnackbarEvent(message)) }

    // --- T69: Voice-Memo ---

    /** Sprach-Memo Recorder — separater AudioRecord, blockiert RecordingService nicht. */
    val voiceMemoRecorder = VoiceMemoRecorder()

    /** Separater AudioPlayer fuer Memo-Wiedergabe (kein Sharing mit Session-Player). */
    val memoAudioPlayer by lazy { AudioPlayer(context) }

    // --- T164: Detektion direkt auf der Artkarte anhoeren ---

    /** Eigener Player fuer Detektions-Ausschnitte (getrennt vom Memo-Player). */
    private val detectionPlayer by lazy { AudioPlayer(context) }
    private val _playingDetectionId = MutableStateFlow<String?>(null)
    /** Id der Detektion, deren Ausschnitt gerade spielt (null = keine). */
    val playingDetectionId: StateFlow<String?> = _playingDetectionId.asStateFlow()
    private var playerStateJob: Job? = null

    /** Play/Stopp fuer den Ausschnitt einer Detektion (3 s davor, 5 s danach, wie in der Analyse). */
    fun toggleDetectionPlayback(detectionId: String) {
        if (_playingDetectionId.value == detectionId) {
            detectionPlayer.stop()
            _playingDetectionId.value = null
            return
        }
        val detection = detectionListState.getDetections().firstOrNull { it.id == detectionId } ?: return
        val lastId = _uiState.value.lastSessionId
        viewModelScope.launch(Dispatchers.IO) {
            val dir = sessionManager.currentSessionDir
                ?: lastId?.let { id -> sessionManager.listSessions().firstOrNull { it.name == id } }
            val rec = dir?.let { sessionManager.loadRecording(it) }
            if (rec == null || rec.durationSec <= 0f) {
                _snackbarChannel.trySend(SnackbarEvent(AppStrings.get(R.string.live_aufnahme_nicht_verfuegbar_teilen_in)))
                return@launch
            }
            val range = rec.excerptRange(detection, appPreferences.excerptMaxSec.toFloat())
            detectionPlayer.playFromOffset(rec, startSec = range.startSec, endSec = range.endSec, normalizeDbfs = DETECTION_PLAYBACK_DBFS)
            _playingDetectionId.value = detectionId
            playerStateJob?.cancel()
            playerStateJob = viewModelScope.launch {
                // Zustand beobachten: bei IDLE (Ende/Stopp) Anzeige zuruecksetzen
                detectionPlayer.state.collect { st ->
                    if (st == AudioPlayer.PlaybackState.IDLE && _playingDetectionId.value == detectionId) {
                        _playingDetectionId.value = null
                    }
                }
            }
        }
    }

    // FAB-Debounce (T52): verhindert Doppel-Taps innerhalb 500 ms
    private var lastFabTapAt = 0L

    // Serialisiert Verifikations-IO: verhindert Race bei Schnell-Taps (AC6)
    private val verificationMutex = verificationService.mutex  // T137: geteilt mit dem Alarm-Pfad

    // T73: Referenz auf lockedSpecies-StateFlow in DetectionListState (fuer toggleLock)
    private val lockedSpecies = detectionListState.lockedSpecies

    // --- Oeffentlicher State ---
    private val _uiState = MutableStateFlow(LiveUiState())
    val uiState: StateFlow<LiveUiState> = _uiState.asStateFlow()

    // --- Interne Referenzen ---
    private var service: RecordingService? = null
    private var applicationContext: Context? = null
    private var collectionJob: Job? = null
    private var recordingObserverJob: Job? = null
    private var locationObserverJob: Job? = null

    // DSP-Pipeline (zustandsbehaftet, nicht in UiState)
    private var melSpectrogram: MelSpectrogram? = null
    private var currentSampleRate: Int = 0

    // F5: User hat Preroll explizit gestartet
    private var prerollRunning = false
    private var prerollRequested = false  // T122

    // SpectrogramState lebt im ViewModel, wird per Referenz in UiState geteilt.
    // T56: DynamicRangeMapper haengt an den State, wird ueber appendFrames() gefuettert.
    private val spectrogramState = SpectrogramState(maxFrames = 3072).apply {  // T163: 30 s bei 48 kHz/Hop 512 = 2813 Frames
        dynamicRangeMapper = DynamicRangeMapper()
    }


    init {
        // Modell-Verfuegbarkeit pruefen
        val modelAvailable = classifier.isModelAvailable()
        if (!modelAvailable) {
            Log.w(TAG, "BirdNET-Modell nicht verfuegbar — ML-Pipeline deaktiviert")
        }

        // InferenceConfig aus SharedPreferences laden (statt Default)
        val savedConfig = appPreferences.loadInferenceConfig()
        // T110: Ueberlappung separat aus den Prefs (wird zudem bei jedem Pipeline-Start neu gelesen)
        pipeline.setInferenceConfig(savedConfig)

        // Region laden (aus gespeicherter Config)
        val region = savedConfig.regionFilter ?: "ch_breeding"
        regionalFilter.loadRegion(region)
        Log.d(TAG, "Regionaler Filter geladen: ${regionalFilter.getCurrentRegionId()} (${regionalFilter.getSpeciesList().size} Arten)")

        // Artennamen-Sprache laden + species_master.json parsen (T26, T32-Fix)
        // T95: Im Hintergrund laden (species_master.json ist ~10 MB) statt den Main-Thread zu blockieren.
        // Die ML-Pipeline wartet vor der Verarbeitung darauf (siehe startInferencePipeline),
        // damit Detektionen nie mit lateinischem Namen angezeigt werden.
        speciesNameResolver.language = appPreferences.effectiveSpeciesLanguage()
        viewModelScope.launch { speciesNameResolver.load() }

        // Upload-Einstellungen aus SharedPreferences laden
        uploadManager.wifiOnly = appPreferences.wifiOnly
        uploadManager.autoUpload = appPreferences.autoUpload

        // SpectrogramConfig + Palette aus AppPreferences laden (T30)
        val savedSpecConfig = when (appPreferences.spectrogramConfigName) {
            "BATS" -> SpectrogramConfig.BATS
            "WIDEBAND" -> SpectrogramConfig.WIDEBAND
            else -> SpectrogramConfig.BIRDS
        }
        // T52: Top-N Kandidaten-Anzeige aus Prefs.
        val savedShowTopN = appPreferences.showTopNCandidates

        // Initiale State-Werte setzen
        _uiState.update {
            it.copy(
                spectrogramState = spectrogramState,
                spectrogramConfig = savedSpecConfig,
                detectionListState = detectionListState,
                isModelAvailable = modelAvailable,
                inferenceConfig = savedConfig,
                isEmbeddingAvailable = modelAvailable,
                embeddingDbSize = embeddingDatabase.size,
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

        // T94: Signale der Session-Pipeline (Dienst) ins UI spiegeln
        observePipeline()

        // T69: 60s-Cap-Signal → Snackbar
        viewModelScope.launch {
            voiceMemoRecorder.capReached.collect {
                _snackbarChannel.trySend(SnackbarEvent(AppStrings.get(R.string.analysis_memo_auf_60s_gekuerzt)))
            }
        }
    }

    // --- Service Connection ---
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val recordingBinder = binder as? RecordingService.RecordingBinder
            service = recordingBinder?.getService()
            _uiState.update { it.copy(isServiceBound = true) }
            if (prerollRequested) userStartPreroll()  // T122

            // Recording-State vom Service beobachten
            observeRecordingState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            stopCollectionJob()
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

    // --- T109: Session-Bemerkung (Vorbelegung = zuletzt verwendete, persistiert) ---
    private val _sessionNote = MutableStateFlow(appPreferences.lastSessionNote)
    val sessionNote: StateFlow<String> = _sessionNote.asStateFlow()

    /** Bemerkung fuer die naechste Session setzen (wird sofort als "zuletzt verwendet" gespeichert). */
    fun setSessionNote(text: String) {
        val v = text.take(200).replace('\n', ' ')
        _sessionNote.value = v
        appPreferences.lastSessionNote = v
    }

    /**
     * Aufnahme starten. Erstellt Foreground-Service und startet Audio-Capture.
     * Bei aktivem Preroll (T118): Service erfasst den Ring-Buffer-Inhalt; er wird als erster Block
     * in recording.wav geschrieben, analysiert und hier im Sonogramm dargestellt.
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

        // T118: Preroll wird im Service beim Start erfasst (Lese-Cursor direkt dahinter -> lueckenlos).
        // Persistenz + Analyse: observeRecordingState() holt ihn per takePrerollCapture() ab.
        val prerollMs = if (appPreferences.prerollEnabled) appPreferences.prerollDurationSec * 1000 else 0

        // Foreground-Service starten + Aufnahme beginnen
        val intent = Intent(ctx, RecordingService::class.java)
        ctx.startForegroundService(intent)
        svc.startRecording(sampleRate, prerollMs)

        // Sonogramm-Darstellung des Prerolls (Datei + Analyse laufen ueber den InferenceWorker)
        val prerollSamples = svc.lastPrerollCapture?.samples ?: ShortArray(0)
        if (prerollSamples.isNotEmpty()) {
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
            // Begrenzt auf die Kapazitaet des Sonogramm-Buffers (~33 s) — mehr waere unsichtbar.
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
        // T122: Preroll war aktiv → nach dem Stopp wieder puffern (vorher musste man ihn zweimal umschalten)
        if (prerollRunning) userStartPreroll()
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
        Log.i(TAG, "T122 userStartPreroll: service=${service != null} recording=${service?.isRecording?.value}")
        val svc = service ?: run {
            // T122: Dienst noch nicht verbunden → Wunsch merken, in onServiceConnected nachholen
            prerollRequested = true
            return
        }
        prerollRequested = false
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
            _snackbarChannel.send(SnackbarEvent(message = AppStrings.get(R.string.live_lang_druecken_zum_stoppen)))
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
            VerificationStatus.CONFIRMED  -> AppStrings.get(R.string.live_bestaetigt, name)
            VerificationStatus.REJECTED   -> AppStrings.get(R.string.live_abgelehnt, name)
            VerificationStatus.UNCERTAIN  -> AppStrings.get(R.string.live_als_unsicher_markiert, name)
            VerificationStatus.CORRECTED  -> "\u270E $name \u2192 ${correctedSpecies ?: "?"}"
            else -> name
        }
        viewModelScope.launch {
            _snackbarChannel.send(SnackbarEvent(
                message = message,
                actionLabel = AppStrings.get(R.string.common_undo),
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
                            _snackbarChannel.send(SnackbarEvent(message = AppStrings.get(R.string.common_save_failed)))
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
                        _snackbarChannel.trySend(SnackbarEvent(AppStrings.get(R.string.common_save_failed)))
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
                        _snackbarChannel.trySend(SnackbarEvent(AppStrings.get(R.string.common_save_failed)))
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
                    message = AppStrings.get(R.string.live_alternative_gewaehlt, candidate.commonName),
                    actionLabel = AppStrings.get(R.string.common_undo),
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
                            _snackbarChannel.send(SnackbarEvent(message = AppStrings.get(R.string.common_save_failed)))
                        }
                    }
                }
            }
        }
    }

    /**
     * Speichert eine verifizierte Detektion als Referenz-Aufnahme (T123).
     * Schneidet den Ausschnitt (T116: 3 s davor, 5 s danach) aus recording.wav der Session.
     */
    /** T127: bereits als Referenz gespeicherte Detektionen */
    val referencedDetectionIds = referenceRepository.referencedDetectionIds

    fun saveAsReference(detectionId: String) {
        val detection = detectionListState.getDetections().find { it.id == detectionId } ?: return

        viewModelScope.launch(Dispatchers.IO) {
            // T127: Umschalten — vorhandene Referenz entfernen statt doppelt zu speichern
            referenceRepository.findByDetection(detectionId)?.let { existing ->
                referenceRepository.deleteReference(existing)
                Log.i(TAG, "Referenz entfernt: ${existing.commonName}")
                return@launch
            }
            val sessionDir = sessionManager.currentSessionDir ?: sessionManager.listSessions().firstOrNull()
            // T130: alle WAV-Segmente der Session (Session-Zeitachse)
            val recording = sessionDir?.let { sessionManager.loadRecording(it) }
            if (sessionDir == null || recording == null) {
                Log.e(TAG, "Referenz: keine Aufnahme gefunden")
                return@launch
            }
            // Bei laufender Aufnahme ist die Datei evtl. noch kuerzer als der Ausschnitt -> auf Dauer begrenzen
            val range = recording.excerptRange(detection, appPreferences.excerptMaxSec.toFloat())
            val result = referenceRepository.addFromDetection(detection, recording, sessionDir.name, range)
            if (result != null) {
                Log.i(TAG, "Referenz gespeichert: ${result.commonName} (${result.wavFileName})")
            } else {
                Log.e(TAG, "Referenz speichern fehlgeschlagen")
            }
        }
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
     * Liest Top-N-Anzeige und Embedding-DB-Limit erneut aus den SharedPreferences.
     * Wird vom LiveScreen bei ON_RESUME aufgerufen. T107: Sonogramm-Einstellungen sind
     * nicht mehr hier, sondern live im SpectrogramSettingsRepository.
     */
    fun reloadPrefs() {
        val showTopN = appPreferences.showTopNCandidates  // T52: bei Resume neu lesen
        // T97: Obergrenze der Embedding-DB bei Resume neu lesen (Settings-Aenderung wirkt sofort)
        embeddingDatabase.maxEntries = appPreferences.embeddingDbMaxEntries
        _uiState.update { it.copy(embeddingDbSize = embeddingDatabase.size) }
        _uiState.update { it.copy(showTopNCandidates = showTopN) }
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
        pipeline.setInferenceConfig(config)  // T94: Worker lebt in der Pipeline (T110: Overlap aus Prefs)
        _uiState.update { it.copy(inferenceConfig = config) }
        // In SharedPreferences persistieren
        appPreferences.saveInferenceConfig(config)
        Log.d(TAG, "InferenceConfig aktualisiert: threshold=${config.confidenceThreshold}, topK=${config.topK}, region=${config.regionFilter}")
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
        embeddingDbStore.loadOnce()  // T94: idempotent, Datei-Logik im EmbeddingDbStore
        _uiState.update { it.copy(embeddingDbSize = embeddingDatabase.size) }
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
                        actualSampleRate = service?.actualSampleRate ?: 0,
                        // T94: nach Rueckkehr in die App Timer aus dem Dienst uebernehmen
                        recordingStartElapsedRealtime = if (recording) {
                            service?.recordingStartElapsedRealtime?.takeIf { t -> t > 0L } ?: it.recordingStartElapsedRealtime
                        } else it.recordingStartElapsedRealtime
                    )
                }
                // T94: Session, Persistenz und Analyse steuert der RecordingService (RecordingPipeline).
                // Hier nur noch die Sonogramm-Darstellung (UI).
                if (recording) {
                    startCollectionPipeline()
                } else {
                    stopCollectionJob()
                    resetMelSpectrogram()
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
            // T107: Messwerte fuer die Empfindlichkeits-Analyse (alle ~2 s ein Log)
            var statsSum = 0.0
            var statsCount = 0L
            var statsMax = -200f
            var statsLastMs = android.os.SystemClock.elapsedRealtime()
            var skipped = false
            // T163: Rohsamples waehrend die Ansicht unsichtbar ist (max. 30 s bzw. Sonogramm-Kapazitaet);
            // gehoert dem Job, wird also bei Aufnahme-Start/-Stopp automatisch verworfen.
            val hiddenRing = SampleRing(
                minOf(30 * sampleRate, spectrogramState.maxFrames * config.hopSize).coerceAtLeast(1)
            )
            svc.audioChunkFlow.collect { chunk ->
                // T160b: Sonogramm nur berechnen, solange die Live-Ansicht sichtbar ist.
                // Aufnahme und Erkennung (RecordingPipeline im Service) laufen davon unabhaengig.
                if (!screenVisible) {
                    // T163: nur billig mitschneiden (Ringpuffer-Kopie), Rechnen erst beim Zurueckkehren
                    hiddenRing.write(chunk.samples)
                    skipped = true
                    return@collect
                }
                if (skipped) {
                    skipped = false
                    // Ueberlauf = Luecke im Verlauf: alte Frames verwerfen, Mel-Zustand frisch starten.
                    // Sonst schliesst der Ringinhalt lueckenlos an den Mel-Zustand an (keine Doppel-Spalten).
                    if (hiddenRing.overflowed) {
                        spectrogramState.clear()
                        mel.reset()
                    }
                    if (hiddenRing.size > 0) {
                        val backfill = mel.process(hiddenRing.toArray())
                        if (backfill.isNotEmpty()) spectrogramState.appendFrames(backfill)
                    }
                    hiddenRing.clear()
                }
                val frames = mel.process(chunk.samples)
                if (frames.isNotEmpty()) {
                    spectrogramState.appendFrames(frames)
                    for (f in frames) for (v in f) {
                        statsSum += v
                        if (v > statsMax) statsMax = v
                    }
                    statsCount += frames.size.toLong() * frames[0].size
                }
                val nowMs = android.os.SystemClock.elapsedRealtime()
                if (nowMs - statsLastMs >= 2000L && statsCount > 0) {
                    val range = spectrogramState.dynamicRangeMapper?.currentRange()
                    Log.i(
                        "SpectrogramStats",
                        "T107_STATS mean_db=%.1f max_db=%.1f range_lo=%.1f range_hi=%.1f".format(
                            statsSum / statsCount, statsMax, range?.first ?: Float.NaN, range?.second ?: Float.NaN
                        )
                    )
                    statsSum = 0.0; statsCount = 0; statsMax = -200f; statsLastMs = nowMs
                }
            }
        }
    }

    /** Collection-Job stoppen (DSP-Arm). */
    private fun stopCollectionJob() {
        collectionJob?.cancel()
        collectionJob = null
    }

    /** MelSpectrogram-Buffer zuruecksetzen. */
    private fun resetMelSpectrogram() {
        melSpectrogram?.reset()
    }

    // --- T94: Pipeline-Observer ---

    private fun observePipeline() {
        viewModelScope.launch {
            pipeline.overlapReduced.collect { percent ->
                _snackbarChannel.trySend(
                    SnackbarEvent(AppStrings.get(R.string.live_analyse_zu_langsam_ueberlappung_auf, percent))
                )
            }
        }
        viewModelScope.launch {
            pipeline.storageFallback.collect { fb -> _uiState.update { it.copy(storageUnavailableFallback = fb) } }
        }
        viewModelScope.launch {
            pipeline.lastSessionId.collect { id ->
                if (id != null) _uiState.update { it.copy(lastSessionId = id) }
            }
        }
        // Embedding-Arm (UI-Teil): Aehnlichkeitssuche + Marquee; Extraktion/DB-Eintrag macht die Pipeline
        viewModelScope.launch(Dispatchers.Default) {
            pipeline.embeddingAdded.collect { e ->
                _uiState.update { it.copy(embeddingDbSize = embeddingDatabase.size) }
                findSimilar(e.embedding, e.species)
                val recent = detectionListState.getDetections()
                    .take(5)
                    .map { speciesNameResolver.resolve(it.scientificName) }
                _uiState.update { it.copy(recentSpecies = recent) }
            }
        }
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
            var previous: UploadStatus? = null
            uploadManager.status.collect { status ->
                _uiState.update { it.copy(uploadStatus = status) }
                // T125: Snackbar nur bei Uebergang (nicht fuer veralteten Anfangszustand)
                if (previous != null && previous != status) {
                    when (status) {
                        is UploadStatus.Success -> postSnackbar(AppStrings.get(R.string.live_upload_abgeschlossen))
                        is UploadStatus.Failed -> postSnackbar(AppStrings.get(R.string.live_upload_fehlgeschlagen, status.error))
                        else -> Unit
                    }
                }
                previous = status
            }
        }
    }

    // --- Lifecycle ---

    override fun onCleared() {
        // T94: Session, Persistenz, GPS und Analyse gehoeren der RecordingPipeline im Dienst und laufen
        // weiter, wenn das UI verschwindet. Hier nur UI-Ressourcen freigeben.
        stopCollectionJob()
        recordingObserverJob?.cancel()
        locationObserverJob?.cancel()
        super.onCleared()
        // Service-Unbinding muss vom Screen gemacht werden (braucht Context)
        // T69: Voice-Memo Cleanup
        voiceMemoRecorder.release()
        memoAudioPlayer.release()
        detectionPlayer.release()
    }
}

/** T164: Zielpegel fuer die Wiedergabe von Detektions-Ausschnitten (wie Analyse). */
private const val DETECTION_PLAYBACK_DBFS = -10f
