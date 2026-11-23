package ch.etasystems.pirol.ui.analysis

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.audio.PlaybackKeys
import ch.etasystems.pirol.audio.VoiceMemoRecorder
import ch.etasystems.pirol.audio.export.DetectionExcerpt
import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.data.export.DetectionShareRequest
import ch.etasystems.pirol.audio.dsp.DynamicRangeMapper
import ch.etasystems.pirol.audio.dsp.MelSpectrogram
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.AtlasCodeRepository
import ch.etasystems.pirol.data.repository.ReferenceEntry
import ch.etasystems.pirol.data.repository.ReferenceRepository
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.ml.AtlasCode
import ch.etasystems.pirol.ml.CHUNK_DURATION_MS
import ch.etasystems.pirol.ml.DetectionCandidate
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import ch.etasystems.pirol.ui.components.SpectrogramState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.AppStrings

/**
 * ViewModel fuer den Analyse-Tab.
 * Laedt Sessions, oeffnet Session-Detail, navigiert Audio-Chunks,
 * rendert Sonogramme und persistiert nachtraegliche Verifikationen.
 * Vergleichs-Modus: Detektion vs Referenz (Dual-Sonogramm).
 *
 * T62: Streaming-Sonogramm fuer lange WAV-Files (AC1/AC3/AC8).
 *      SAF-URI-Support fuer Playback und WAV-Lesen (AC5).
 *      MediaPlayer-Fehlerbehandlung via playerError (AC7).
 */
/** T122: Wiedergabe im Analyse-Tab auf -10 dBFS normalisieren (Wunsch Feldtest) */
private const val PLAYBACK_DBFS = -10f

class AnalysisViewModel(
    private val sessionManager: SessionManager,
    private val audioPlayer: AudioPlayer,
    private val referenceRepository: ReferenceRepository,
    private val appPreferences: AppPreferences,
    private val atlasCodeRepository: AtlasCodeRepository,
    private val speciesNameResolver: SpeciesNameResolver,  // T72: Artsuche fuer AddManualDetectionDialog
    private val context: Context,  // T69: fuer VoiceMemoRecorder SAF
    private val playback: ch.etasystems.pirol.audio.PlaybackController,  // T173: zentrale Wiedergabe
    private val uploadManager: ch.etasystems.pirol.data.sync.UploadManager  // T194: Upload aus der Session-Kopfzeile
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    // Serialisiert Verifikations-IO: verhindert Race bei Schnell-Taps (AC6)
    private val verificationMutex = Mutex()

    // --- T69: Voice-Memo ---

    /** Sprach-Memo Recorder — separater AudioRecord, blockiert RecordingService nicht. */
    val voiceMemoRecorder = VoiceMemoRecorder()

    /** T173: Memo-Wiedergabe nutzt den geteilten Player (Start/Stopp ueber [PlaybackController]). */
    val memoAudioPlayer: AudioPlayer get() = audioPlayer

    /** T107: Polling-Job fuer die Abspielposition. */
    private var positionJob: Job? = null

    /** T130: Aufnahme der geoeffneten Session ueber alle WAV-Segmente (Session-Zeitachse). */
    @Volatile private var recording: SegmentedRecording? = null

    init {
        // T65: Crash-Recovery vor erstem Session-Laden (verwaiste Sessions reparieren)
        viewModelScope.launch {
            sessionManager.recoverOrphanSessions()
            loadSessions()
        }
        // T173: Schluessel der laufenden Wiedergabe in den UI-State spiegeln
        viewModelScope.launch {
            playback.playingKey.collect { key -> _uiState.update { it.copy(playingKey = key) } }
        }
        // Playback-State beobachten
        viewModelScope.launch {
            audioPlayer.state.collect { state ->
                _uiState.update { it.copy(playbackState = state) }
                if (state == AudioPlayer.PlaybackState.IDLE) {
                    _uiState.update {
                        it.copy(
                            playingChunkIndex = null, playingSource = null,
                            playingDetectionId = null, playbackPositionSec = null,
                            playingOccurrenceStartSec = null
                        )
                    }
                }
                // T107: Position fuer den Zeiger im Sonogramm pollen (~10 Hz), solange etwas spielt
                positionJob?.cancel()
                if (state == AudioPlayer.PlaybackState.PLAYING) {
                    positionJob = viewModelScope.launch {
                        while (isActive) {
                            _uiState.update { it.copy(playbackPositionSec = audioPlayer.currentPositionSec()) }
                            delay(100)
                        }
                    }
                }
            }
        }
        // T62: MediaPlayer-Fehler als playerError in UiState weiterleiten (AC7)
        viewModelScope.launch {
            audioPlayer.playerError.collect { msg ->
                _uiState.update { it.copy(playerError = msg) }
            }
        }
        // Auto-Refresh nach Session-Stop (T51)
        viewModelScope.launch {
            sessionManager.sessionEnded.collect { loadSessions() }
        }
        // T69: 60s-Cap-Signal → saveErrorMessage (wird als Snackbar angezeigt)
        viewModelScope.launch {
            voiceMemoRecorder.capReached.collect {
                _uiState.update { it.copy(saveErrorMessage = AppStrings.get(R.string.analysis_memo_auf_60s_gekuerzt)) }
            }
        }
    }

    /** Alle Sessions laden (fuer Liste) */
    fun loadSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            val dirs = sessionManager.listSessions()
            val summaries = dirs.mapNotNull { dir ->
                val meta = sessionManager.loadMetadata(dir) ?: return@mapNotNull null
                val detections = sessionManager.loadDetectionsWithVerifications(dir)
                // T52: WAV-Dateigrösse ermitteln (T130: Summe aller Segmente)
                val wavBytes = sessionManager.recordingSizeBytes(dir)  // T131: File + SAF
                SessionSummary(
                    sessionDir = dir,
                    metadata = meta,
                    detectionCount = detections.size,
                    verifiedCount = detections.count {
                        it.verificationStatus != VerificationStatus.UNVERIFIED
                    },
                    recordingSizeBytes = wavBytes
                )
            }
            _uiState.update { it.copy(sessions = summaries, isLoading = false) }
        }
    }

    /** Session loeschen (mit anschliessendem Neuladen der Liste) */
    fun deleteSession(summary: SessionSummary) {
        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.deleteSession(summary.sessionDir)
            loadSessions()
        }
    }

    // ── T180: Mehrfachauswahl, Verschieben, Loeschen ─────────────────

    private val sessionMover = ch.etasystems.pirol.data.repository.SessionMover(context)
    private var batchJob: Job? = null

    /** Laufende Aufnahme-Session ist nicht auswaehlbar. */
    private fun isSelectable(s: SessionSummary) = !sessionManager.isActiveSession(s.sessionDir)

    fun startSelection(summary: SessionSummary) {
        if (!isSelectable(summary) || _uiState.value.batchRunning) return
        _uiState.update { it.copy(selectionMode = true, selectedIds = setOf(summary.metadata.sessionId)) }
    }

    fun toggleSelection(summary: SessionSummary) {
        if (!isSelectable(summary) || _uiState.value.batchRunning) return
        _uiState.update {
            val next = ch.etasystems.pirol.data.repository.SessionBatchLogic.toggle(it.selectedIds, summary.metadata.sessionId)
            it.copy(selectedIds = next)
        }
    }

    fun selectAllSessions() {
        _uiState.update { st ->
            st.copy(selectedIds = ch.etasystems.pirol.data.repository.SessionBatchLogic.selectAll(
                st.sessions.filter { isSelectable(it) }.map { it.metadata.sessionId }
            ))
        }
    }

    fun clearSelection() {
        if (_uiState.value.batchRunning) return
        _uiState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    fun consumeBatchMessage() { _uiState.update { it.copy(batchMessage = null) } }

    /** Laufende Verschiebung abbrechen (Teilkopie wird entfernt, Original bleibt). */
    fun cancelBatch() { batchJob?.cancel() }

    private fun selectedSummaries(): List<SessionSummary> {
        val st = _uiState.value
        return st.sessions.filter { it.metadata.sessionId in st.selectedIds && isSelectable(it) }
    }

    private fun finishBatch(result: ch.etasystems.pirol.data.repository.BatchResult, move: Boolean) {
        val msg = when {
            result.cancelled -> AppStrings.get(R.string.analysis_batch_abgebrochen, result.succeeded, result.failed)
            move -> AppStrings.get(R.string.analysis_batch_verschoben, result.succeeded, result.failed)
            else -> AppStrings.get(R.string.analysis_batch_geloescht, result.succeeded, result.failed)
        }
        _uiState.update {
            it.copy(batchRunning = false, batchProgress = null, selectionMode = false,
                selectedIds = emptySet(), batchMessage = msg)
        }
        loadSessions()
    }

    /** Ausgewaehlte Sessions loeschen (Bestaetigung erfolgt in der UI). */
    fun deleteSelected() {
        val list = selectedSummaries()
        if (list.isEmpty() || _uiState.value.batchRunning) return
        _uiState.update { it.copy(batchRunning = true, batchIsMove = false) }
        batchJob = viewModelScope.launch(Dispatchers.IO) {
            var ok = 0
            val failed = mutableListOf<String>()
            list.forEachIndexed { i, s ->
                _uiState.update {
                    it.copy(batchProgress = ch.etasystems.pirol.data.repository.MoveProgress(
                        i, list.size, s.sessionDir.name, 0, 0, i, list.size))
                }
                if (sessionManager.deleteSession(s.sessionDir)) ok++ else failed += s.sessionDir.name
            }
            sessionManager.removeEmptyDayDirs()
            finishBatch(ch.etasystems.pirol.data.repository.BatchResult(ok, failed.size, failed), move = false)
        }
    }

    /** Ausgewaehlte Sessions in den per SAF gewaehlten Ordner [treeUri] verschieben. */
    fun moveSelected(treeUri: Uri) {
        val list = selectedSummaries()
        if (list.isEmpty() || _uiState.value.batchRunning) return
        // T181: Ziel in der PIROL-Basis oder gleich Quellordner -> nichts verschieben
        val targetId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (targetId != null) {
            val verdict = ch.etasystems.pirol.data.repository.MoveTargetGuard.check(
                targetId, sessionManager.baseDocumentIds(), list.mapNotNull { sessionManager.sessionDocumentId(it.sessionDir) }
            )
            val msgRes = when (verdict) {
                ch.etasystems.pirol.data.repository.MoveTargetGuard.Verdict.INSIDE_BASE -> R.string.analysis_batch_ziel_in_basis
                ch.etasystems.pirol.data.repository.MoveTargetGuard.Verdict.SAME_AS_SOURCE -> R.string.analysis_batch_ziel_gleich_quelle
                else -> null
            }
            if (msgRes != null) {
                _uiState.update { it.copy(batchMessage = AppStrings.get(msgRes)) }
                return
            }
        }
        val root = sessionMover.targetRoot(treeUri)
        if (root == null) {
            _uiState.update { it.copy(batchMessage = AppStrings.get(R.string.analysis_batch_ziel_nicht_schreibbar)) }
            return
        }
        _uiState.update { it.copy(batchRunning = true, batchIsMove = true) }
        batchJob = viewModelScope.launch(Dispatchers.IO) {
            var ok = 0
            val failed = mutableListOf<String>()
            var cancelled = false
            // Gesamtumfang vorab ermitteln
            val sources = list.map { it to sessionMover.sourceFor(it.sessionDir, sessionManager.docFor(it.sessionDir)) }
            var filesTotal = 0
            var bytesTotal = 0L
            sources.forEach { (_, src) -> src?.let { val (f, b) = sessionMover.measure(it); filesTotal += f; bytesTotal += b } }
            var filesDone = 0
            var bytesDone = 0L
            try {
                for ((i, pair) in sources.withIndex()) {
                    val (s, src) = pair
                    val name = s.sessionDir.name
                    if (src == null) { failed += name; continue }
                    fun publish() = _uiState.update {
                        it.copy(batchProgress = ch.etasystems.pirol.data.repository.MoveProgress(
                            i, list.size, name, bytesDone, bytesTotal, filesDone, filesTotal))
                    }
                    publish()
                    try {
                        sessionMover.copySession(src, root,
                            onBytes = { bytesDone += it; publish() },
                            onFile = { filesDone++ })
                        // Kopie vollstaendig und groessengeprueft -> Original loeschen
                        if (sessionManager.deleteSession(s.sessionDir)) ok++ else failed += name
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        cancelled = true
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("AnalysisViewModel", "Verschieben fehlgeschlagen: $name", e)
                        failed += name
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                sessionManager.removeEmptyDayDirs()
                finishBatch(ch.etasystems.pirol.data.repository.BatchResult(ok, failed.size, failed, cancelled), move = true)
            }
        }
    }

    /** Session oeffnen (Detail-Ansicht) */
    fun openSession(summary: SessionSummary) {
        viewModelScope.launch(Dispatchers.IO) {
            val detections = sessionManager.loadDetectionsWithVerifications(summary.sessionDir)
            // T130: Veraltete WAV-Header-Groessen (Prozess-Kill) nachfuehren, bevor Dauer/Sonogramm/Playback die Datei lesen
            sessionManager.repairRecordingHeaders(summary.sessionDir)
            // T131: SAF-Session → kein File-Pfad; Aufnahme nur ueber content://-Segmente (rec)
            val isDoc = sessionManager.docFor(summary.sessionDir) != null
            val recFile = if (isDoc) null else sessionManager.getRecordingFile(summary.sessionDir)
            // T130: alle Segmente; Dauer = ganze Session (vorher nur recording.wav)
            val rec = sessionManager.loadRecording(summary.sessionDir)
            recording = rec
            val mergeTimes = sessionManager.loadMergeTimestamps(summary.sessionDir)  // T167
            // T62: URI aus File ableiten; T131: bei SAF content://-URI des ersten Segments
            val recUri  = recFile?.let { Uri.fromFile(it) }
                ?: rec?.firstSource?.uriString?.let { Uri.parse(it) }
            val durationSec = rec?.durationSec?.takeIf { it > 0f } ?: recFile?.let { readWavDurationSec(it) } ?: 0f
            _uiState.update {
                it.copy(
                    selectedSession = summary,
                    // T186: bestes Fenster auch fuer Sessions vor T186 aus den Merge-Events ableiten
                    detections = detections.map { d -> DetectionOccurrences.withBestWindow(d, mergeTimes[d.id].orEmpty()) },
                    mergeTimestamps = mergeTimes,
                    recordingFile = recFile,
                    hasRecording = rec != null || recFile != null,
                    recordingUri  = recUri,
                    recordingDurationSec = durationSec,
                    zoomRange = null,
                    zoomSpectrogramState = SpectrogramState()
                )
            }
            // T62: Sonogramm streaming rendern (kein readBytes() auf ganzes File)
            if (rec != null && rec.parts.size > 1) {
                renderSessionSonogramStreaming(rec.virtualWavLength) { rec.openWavStream() }
            } else if (recFile != null) {
                renderSessionSonogramStreaming(recFile.length()) { recFile.inputStream() }
            } else if (rec != null) {
                renderSessionSonogramStreaming(rec.virtualWavLength) { rec.openWavStream() }  // T131: SAF
            }
        }
    }

    /** Zurueck zur Session-Liste */
    fun closeSession() {
        audioPlayer.stop()
        recording = null
        _uiState.update {
            it.copy(
                selectedSession = null,
                detections = emptyList(),
                mergeTimestamps = emptyMap(),
                recordingFile = null,
                hasRecording = false,
                recordingUri = null,
                spectrogramState = SpectrogramState(),
                sonogramLoading = false,
                sonogramError = null,
                zoomRange = null,
                zoomSpectrogramState = SpectrogramState()
            )
        }
    }

    /** Chunk-Navigation: No-op in T46 — Timeline-Navigation kommt in T48. */
    fun navigateChunk(delta: Int) { /* T48: Timeline-Navigation */ }

    /**
     * Aufnahme-Datei abspielen.
     * T62: bevorzugt Uri-Variante fuer SAF-Kompatibilitaet (AC5).
     */
    fun playChunk(chunkIndex: Int) {
        val uri = _uiState.value.recordingUri
        val f   = _uiState.value.recordingFile
        val rec = recording
        // T173: Start/Stopp-Umschalten ueber den zentralen Controller
        val start: () -> Unit = when {
            rec != null && rec.parts.size > 1 -> { { audioPlayer.play(rec) } }  // T130: Segmente nacheinander
            uri != null -> { { audioPlayer.play(uri) } }
            f   != null -> { { audioPlayer.play(f) } }
            else -> return
        }
        if (playback.toggle(PlaybackKeys.SESSION, start)) return
        _uiState.update { it.copy(playingChunkIndex = 0, playingDetectionId = null, playingOccurrenceStartSec = null) }
    }

    /** Aufnahme abspielen (spielt recording.wav) */
    fun playCurrentChunk() {
        playChunk(0)
    }

    /**
     * Zur Detektion springen und ab chunkStartSec abspielen.
     * Wird von onJumpToChunk in der Detektionsliste aufgerufen.
     */
    fun jumpToDetection(detection: DetectionResult) {
        playDetection(detection)
    }

    /**
     * Detektion ab chunkStartSec bis chunkEndSec abspielen.
     * T62: bevorzugt Uri-Variante fuer SAF-Kompatibilitaet (AC5).
     */
    fun playDetection(
        detection: DetectionResult,
        maxSec: Float = appPreferences.excerptMaxSec.toFloat(),
        key: String = PlaybackKeys.detection(detection.id)
    ) {
        // T173: laeuft genau dieser Ausschnitt, wird er gestoppt; ein anderer Ausschnitt verdraengt den laufenden
        if (playback.isPlaying(key)) {
            playback.stop()
            return
        }
        val uri = _uiState.value.recordingUri
        val f   = _uiState.value.recordingFile
        val rec = recording
        // T116: Ausschnitt mit Kontext (3 s davor, 5 s danach, max. excerptMaxSec)
        // T130: auf der Session-Achse ueber alle Segmente
        val range = rec?.excerptRange(detection, maxSec)
            ?: DetectionExcerpt.compute(detection, _uiState.value.recordingDurationSec, maxSec)
        val start: () -> Unit = when {
            rec != null -> { { audioPlayer.playFromOffset(rec, startSec = range.startSec, endSec = range.endSec, normalizeDbfs = PLAYBACK_DBFS) } }
            uri != null -> { { audioPlayer.playFromOffset(uri, startSec = range.startSec, endSec = range.endSec, normalizeDbfs = PLAYBACK_DBFS) } }
            f   != null -> { { audioPlayer.playFromOffset(f,   startSec = range.startSec, endSec = range.endSec, normalizeDbfs = PLAYBACK_DBFS) } }
            else -> return
        }
        playback.play(key, start)
        if (!playback.isPlaying(key)) return
        _uiState.update { it.copy(playingChunkIndex = null, playingDetectionId = detection.id, playingOccurrenceStartSec = null) }
        if (rec != null) renderZoomSonogram(range) { rec.readRange(range.startSec, range.endSec) }
        else if (f != null) renderZoomSonogram(range) { ExcerptWav.readRange(f, range.startSec, range.endSec) }
    }

    /**
     * T171: Episode (zusammenhaengende Fenster) einer Detektion am Stueck abspielen, mit dem ueblichen
     * Vor-/Nachlauf von [playDetection], aber ohne Kappung durch excerptMaxSec. Laeuft diese Episode
     * schon, stoppt der Aufruf die Wiedergabe.
     */
    fun playEpisode(detection: DetectionResult, episode: DetectionOccurrences.Episode) {
        playDetection(
            detection.copy(
                chunkStartSec = episode.startSec,
                chunkEndSec = episode.startSec + episode.windowSec,
                timestampMs = episode.timestampMs,
                lastSeenAtMs = episode.lastTimestampMs
            ),
            maxSec = Float.MAX_VALUE,
            key = PlaybackKeys.episode(detection.id, episode.startSec)
        )
        if (playback.isPlaying(PlaybackKeys.episode(detection.id, episode.startSec))) {
            _uiState.update { it.copy(playingOccurrenceStartSec = episode.startSec) }
        }
    }

    private var zoomJob: Job? = null

    /** T116: Zoom beenden, Sonogramm zeigt wieder die ganze Session. */
    fun clearZoom() {
        zoomJob?.cancel()
        _uiState.update { it.copy(zoomRange = null, zoomSpectrogramState = SpectrogramState()) }
    }

    /** T116: Mel-Frames nur fuer den Ausschnitt berechnen; Anzeige zoomt darauf. */
    private fun renderZoomSonogram(range: DetectionExcerpt.Range, read: () -> ExcerptWav.Pcm?) {
        zoomJob?.cancel()
        zoomJob = viewModelScope.launch(Dispatchers.Default) {
            val result = try {
                val pcm = read() ?: return@launch
                // T200: Anzeige-Config (Mel bis Nyquist), passend zu Achse und Bin-Zuschnitt
                MelSpectrogram(pcm.sampleRate, SpectrogramConfig.BIRDS).process(pcm.samples) to pcm.sampleRate
            } catch (e: Exception) {
                Log.e("AnalysisVM", "T116 Zoom-Sonogramm fehlgeschlagen", e)
                return@launch
            }
            val (frames, rate) = result
            if (frames.isEmpty() || _uiState.value.selectedSession == null) return@launch
            val state = SpectrogramState(maxFrames = frames.size, sampleRate = rate)
            state.appendFrames(frames)
            _uiState.update { it.copy(zoomRange = range, zoomSpectrogramState = state) }
        }
    }

    /** T116: Share-Dialog fuer eine Detektion oeffnen. */
    fun requestShare(detection: DetectionResult) {
        val f = _uiState.value.recordingFile
        if (f == null && recording == null) return  // T131: SAF → nur ueber recording
        val dir = _uiState.value.selectedSession?.sessionDir
        viewModelScope.launch {
            val note = dir?.let { sessionManager.loadMetadata(it)?.note }
            _uiState.update { it.copy(shareRequest = DetectionShareRequest(detection, f, note, recording)) }
        }
    }

    fun clearShareRequest() {
        _uiState.update { it.copy(shareRequest = null) }
    }

    /**
     * T123: Bestaetigte/korrigierte Detektion als Referenz speichern (Ausschnitt gemaess T116).
     * Rueckmeldung ueber den Snackbar-Kanal.
     */
    /** T127: bereits als Referenz gespeicherte Detektionen */
    val referencedDetectionIds = referenceRepository.referencedDetectionIds

    fun saveAsReference(detection: DetectionResult) {
        val file = _uiState.value.recordingFile
        val session = _uiState.value.selectedSession
        if ((file == null && recording == null) || session == null) {
            reportError(AppStrings.get(R.string.analysis_referenz_keine_aufnahme_vorhanden))
            return
        }
        val duration = _uiState.value.recordingDurationSec
        // T130: alle Segmente der Session
        val rec = recording ?: SegmentedRecording.forRecordingFile(file!!)
        viewModelScope.launch(Dispatchers.IO) {
            // T127: Umschalten — vorhandene Referenz entfernen statt doppelt zu speichern
            referenceRepository.findByDetection(detection.id)?.let { existing ->
                val ok = referenceRepository.deleteReference(existing)
                withContext(Dispatchers.Main) {
                    reportError(if (ok) AppStrings.get(R.string.analysis_referenz_entfernt, existing.commonName) else AppStrings.get(R.string.analysis_referenz_entfernen_fehlgeschlagen))
                }
                return@launch
            }
            val range = if (rec.durationSec > 0f) rec.excerptRange(detection, appPreferences.excerptMaxSec.toFloat())
                else DetectionExcerpt.compute(detection, duration, appPreferences.excerptMaxSec.toFloat())
            val entry = referenceRepository.addFromDetection(detection, rec, session.sessionDir.name, range)
            withContext(Dispatchers.Main) {
                reportError(
                    if (entry != null) AppStrings.get(R.string.analysis_als_referenz_gespeichert, entry.commonName)
                    else AppStrings.get(R.string.analysis_referenz_speichern_fehlgeschlagen)
                )
            }
        }
    }

    /** T116: Fehlermeldung ueber den bestehenden Snackbar-Kanal anzeigen. */
    fun reportError(message: String) {
        _uiState.update { it.copy(saveErrorMessage = message) }
    }

    /** Scroll-to-Top Flag zuruecksetzen (nach consume durch UI) */
    fun consumeScrollToTop() {
        _uiState.update { it.copy(scrollToTop = false) }
    }

    /** Playback stoppen */
    fun stopPlayback() {
        audioPlayer.stop()
    }

    /** T62: playerError nach Anzeige zuruecksetzen (AC7) */
    fun consumePlayerError() {
        _uiState.update { it.copy(playerError = null) }
    }

    // ── Vergleichs-Modus (Ebene 3) ─────────────────────────────────

    /**
     * Vergleichs-Modus oeffnen fuer eine Detektion.
     * Laedt passende Referenzen der gleichen Art.
     */
    fun openCompare(detection: DetectionResult) {
        val speciesName = detection.scientificName.replace(' ', '_')
        val references = referenceRepository.getBySpecies(speciesName)

        // Chunk-Index berechnen
        val state = _uiState.value
        val session = state.selectedSession ?: return
        // OffsetDateTime akzeptiert sowohl "+02:00" als auch "Z" (Offset 0).
        // Fallback auf Instant.parse() fuer sehr alte Sessions ohne Offset.
        val startMs = try {
            java.time.OffsetDateTime.parse(session.metadata.startedAt).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.Instant.parse(session.metadata.startedAt).toEpochMilli()
            } catch (_: Exception) { return }
        }
        val chunkIndex = ((detection.timestampMs - startMs) / CHUNK_DURATION_MS).toInt()
            .coerceAtLeast(0)

        _uiState.update {
            it.copy(
                isCompareMode = true,
                compareDetection = detection,
                compareDetectionChunkIndex = chunkIndex,
                referenceEntries = references,
                selectedReference = references.firstOrNull()
            )
        }

        // T62: Detektions-Sonogramm nur fuer das 3s-Fenster rendern
        renderDetectionSonogram(detection)

        // Erste Referenz-Sonogramm rendern (falls vorhanden)
        if (references.isNotEmpty()) {
            renderReferenceSonogram(references.first())
        }
    }

    /** Vergleichs-Modus schliessen */
    fun closeCompare() {
        audioPlayer.stop()
        _uiState.update {
            it.copy(
                isCompareMode = false,
                compareDetection = null,
                compareDetectionChunkIndex = null,
                referenceEntries = emptyList(),
                selectedReference = null,
                detectionSpectrogramState = SpectrogramState(),
                referenceSpectrogramState = SpectrogramState(),
                playingSource = null
            )
        }
    }

    /** Andere Referenz auswaehlen */
    fun selectReference(entry: ReferenceEntry) {
        audioPlayer.stop()
        _uiState.update { it.copy(selectedReference = entry, playingSource = null) }
        renderReferenceSonogram(entry)
    }

    /**
     * Detektions-Audio abspielen (CompareView: chunkStartSec bis chunkEndSec).
     * T62: bevorzugt Uri-Variante fuer SAF-Kompatibilitaet (AC5).
     */
    fun playDetection() {
        val uri = _uiState.value.recordingUri
        val f   = _uiState.value.recordingFile
        val det = _uiState.value.compareDetection ?: return
        val rec = recording
        val start: () -> Unit = when {
            // T130: Session-Achse ueber alle Segmente
            rec != null -> { { audioPlayer.playFromOffset(rec, startSec = det.bestWindowStartSec, endSec = det.bestWindowEndSec, normalizeDbfs = PLAYBACK_DBFS) } }
            uri != null -> { { audioPlayer.playFromOffset(uri, startSec = det.bestWindowStartSec, endSec = det.bestWindowEndSec, normalizeDbfs = PLAYBACK_DBFS) } }
            f   != null -> { { audioPlayer.playFromOffset(f,   startSec = det.bestWindowStartSec, endSec = det.bestWindowEndSec, normalizeDbfs = PLAYBACK_DBFS) } }
            else -> return
        }
        if (playback.toggle(PlaybackKeys.COMPARE_DETECTION, start)) return  // T173
        _uiState.update { it.copy(playingSource = PlayingSource.DETECTION) }
    }

    /** Referenz-Audio abspielen */
    fun playReference() {
        val entry = _uiState.value.selectedReference ?: return
        val wavFile = referenceRepository.getWavFile(entry) ?: return
        if (playback.toggle(PlaybackKeys.COMPARE_REFERENCE) { audioPlayer.play(wavFile) }) return  // T173
        _uiState.update { it.copy(playingSource = PlayingSource.REFERENCE) }
    }

    // ── Sonogramm-Rendering ─────────────────────────────────────────

    /**
     * T62: Detektions-Sonogramm — liest nur das 3s-Fenster (chunkStart..chunkEnd).
     * Verhindert OOM bei langen Sessions (vorher: gesamte recording.wav in RAM).
     * Kurze Clips (≤3 s @ 48kHz Mono) = ≤288 KB → kein OOM-Risiko.
     */
    private fun renderDetectionSonogram(detection: DetectionResult) {
        val recFile = _uiState.value.recordingFile
        val rec = recording
        if (recFile == null && rec == null) return  // T131: SAF → nur ueber recording
        viewModelScope.launch(Dispatchers.Default) {
            // T130: bei mehreren Segmenten ueber die Session-Achse lesen
            val d = detection
            val samples = readWavSamplesRange(
                openStream = { rec?.openWavStream() ?: recFile!!.inputStream() },
                fileLength  = rec?.virtualWavLength ?: recFile!!.length(),
                startSec    = d.bestWindowStartSec,  // T186
                endSec      = d.bestWindowEndSec
            ) ?: return@launch
            val state  = SpectrogramState(maxFrames = 2048)
            val mel    = MelSpectrogram(48000, SpectrogramConfig.BIRDS)  // T200: wie Achse
            val frames = mel.process(samples)
            if (frames.isNotEmpty()) state.appendFrames(frames)
            _uiState.update { it.copy(detectionSpectrogramState = state) }
        }
    }

    /** Referenz-Sonogramm rendern (kurze Referenz-WAV, kein OOM-Risiko). */
    private fun renderReferenceSonogram(entry: ReferenceEntry) {
        val wavFile = referenceRepository.getWavFile(entry) ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val samples = readWavSamplesRange(
                openStream = { wavFile.inputStream() },
                fileLength  = wavFile.length(),
                startSec    = 0f,
                endSec      = null
            ) ?: return@launch
            val state  = SpectrogramState(maxFrames = 2048)
            val mel    = MelSpectrogram(48000, SpectrogramConfig.BIRDS)  // T200: wie Achse
            val frames = mel.process(samples)
            if (frames.isNotEmpty()) state.appendFrames(frames)
            _uiState.update { it.copy(referenceSpectrogramState = state) }
        }
    }

    /**
     * T62: Streaming-Sonogramm fuer die Recording-Datei (ersetzt renderChunkSonogram).
     *
     * Liest die WAV in 10s-Chunks, berechnet Mel-Spektrogramm pro Block und
     * reduziert via Stride auf TARGET_FRAMES. Kein vollstaendiges In-Memory-
     * Decoding → kein OOM bei Files bis 80 min (AC1/AC8).
     *
     * Algorithmus:
     * 1. Pass 1: WAV-Header → sampleRate + totalSamples
     * 2. stride = max(1, totalFramesEst / TARGET_FRAMES)
     * 3. Pass 2: Stream in CHUNK_SAMPLES-Paketen, Mel + Stride-Filterung
     * 4. SpectrogramState.appendFrames(collected), DynamicRange berechnen
     *
     * Memory-Budget: 1 Chunk = ~960 KB (kurzlebig); gesammelte Frames ≤ 1 MB.
     *
     * @param openStream Lambda das einen frischen InputStream oeffnet (wird 2× aufgerufen).
     */
    private fun renderSessionSonogramStreaming(fileLength: Long, openStream: () -> InputStream?) {
        // T107: Ladeanzeige + Fehlertext statt stillem Abbruch
        _uiState.update { it.copy(sonogramLoading = true, sonogramError = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            val TARGET_FRAMES = 2048
            val CHUNK_SAMPLES = 480_000   // 10 Sekunden @ 48kHz
            val HOP_SIZE      = 512       // MelSpectrogram-Hop-Groesse

            val collectedFrames = mutableListOf<FloatArray>()
            var error: String? = null
            var totalSamples = 0L
            var stride = 1
            var sessionRate = 48000  // T200: fuer Achse/Bin-Zuschnitt der Anzeige
            try {
                // Pass 1: Header lesen -> sampleRate + totalSamples
                val header = ByteArray(44)
                val read = openStream()?.use { it.read(header) } ?: -1
                if (read < 44) {
                    error = AppStrings.get(R.string.analysis_aufnahme_nicht_lesbar_header_unvolls)
                } else {
                    val buf        = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val sampleRate = buf.getInt(24)
                    val headerData = buf.getInt(40).toLong().coerceAtLeast(0L) / 2L
                    // T107: Datenlaenge zusaetzlich aus der Dateigroesse — ein Header mit dataSize=0
                    // (nicht finalisierte Aufnahme) wuerde sonst Stride 1 und nur die ersten ~22 s ergeben.
                    val fileSamples = ((fileLength - 44L) / 2L).coerceAtLeast(0L)
                    totalSamples = if (headerData in 1..fileSamples) headerData else fileSamples
                    if (sampleRate <= 0) {
                        error = AppStrings.get(R.string.analysis_ungueltige_abtastrate_im_wav_header)
                    } else if (totalSamples <= 0L) {
                        error = AppStrings.get(R.string.analysis_aufnahme_enthaelt_keine_audio_daten)
                    } else {
                        // Aufrunden, damit hoechstens TARGET_FRAMES Frames die GANZE Session abdecken
                        val totalFramesEst = (totalSamples / HOP_SIZE).toInt().coerceAtLeast(1)
                        stride = maxOf(1, (totalFramesEst + TARGET_FRAMES - 1) / TARGET_FRAMES)

                        // Pass 2: Chunks streamen, Mel berechnen, Stride filtern
                        sessionRate = sampleRate
                        val mel = MelSpectrogram(sampleRate, SpectrogramConfig.BIRDS)  // T200: wie Achse
                        var frameCounter = 0
                        try {
                            openStream()?.use { stream ->
                                stream.skip(44)
                                val chunkBytes = ByteArray(CHUNK_SAMPLES * 2)
                                var bytesRead: Int
                                while (stream.read(chunkBytes).also { bytesRead = it } > 0) {
                                    val numSamples = bytesRead / 2
                                    val samples    = ShortArray(numSamples)
                                    val bbuf = java.nio.ByteBuffer
                                        .wrap(chunkBytes, 0, bytesRead)
                                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    for (i in 0 until numSamples) samples[i] = bbuf.short
                                    for (frame in mel.process(samples)) {
                                        if (frameCounter % stride == 0) collectedFrames.add(frame)
                                        frameCounter++
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AnalysisVM", "Sonogramm-Streaming-Fehler", e)
                            // Teilweise gesammelte Frames werden weiter verwendet
                            if (collectedFrames.isEmpty()) error = AppStrings.get(R.string.analysis_sonogramm_fehler, e.message ?: e.javaClass.simpleName)
                        }
                        if (error == null && collectedFrames.isEmpty()) error = AppStrings.get(R.string.analysis_keine_spektrogramm_frames_berechnet)
                    }
                }
            } catch (e: Exception) {
                Log.e("AnalysisVM", "Sonogramm-Header-Fehler", e)
                error = AppStrings.get(R.string.analysis_sonogramm_fehler, e.message ?: e.javaClass.simpleName)
            }

            val limited = collectedFrames.take(TARGET_FRAMES)
            val ms = SystemClock.elapsedRealtime() - t0
            if (limited.isEmpty()) {
                Log.w("AnalysisVM", "T107 sonogram FAILED ms=$ms samples=$totalSamples error=$error")
                _uiState.update { it.copy(sonogramLoading = false, sonogramError = error ?: AppStrings.get(R.string.analysis_kein_sonogramm)) }
                return@launch
            }

            val state = SpectrogramState(maxFrames = TARGET_FRAMES, sampleRate = sessionRate)
            state.appendFrames(limited)
            // Diagnose: Pegelverteilung (ein komplett stummes Audio ergibt ein schwarzes Bild)
            val range = DynamicRangeMapper.computeStatic(limited)
            var sum = 0.0; var cnt = 0L; var mx = -200f
            for (f in limited) for (v in f) { sum += v; cnt++; if (v > mx) mx = v }
            Log.i(
                "AnalysisVM",
                "T107 sonogram frames=${limited.size} ms=$ms range=%.1f..%.1f mean_db=%.1f max_db=%.1f stride=$stride samples=$totalSamples"
                    .format(range.first, range.second, sum / cnt, mx)
            )
            // Session wurde waehrend der Berechnung geschlossen: Ergebnis verwerfen
            if (_uiState.value.selectedSession == null) return@launch
            _uiState.update {
                it.copy(spectrogramState = state, sonogramLoading = false, sonogramError = error)
            }
        }
    }

    // ── WAV-Lese-Hilfsmethoden ──────────────────────────────────────

    /**
     * Liest Gesamtdauer einer WAV-Datei aus dem Header.
     * Bytes 24-27: sampleRate, Bytes 40-43: dataSize (PCM-Nutzdaten).
     * Dauer = dataSize / (sampleRate * bytesPerSample).
     */
    private fun readWavDurationSec(file: File): Float {
        return try {
            val header = ByteArray(44)
            file.inputStream().use { it.read(header) }
            val buf = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val sampleRate = buf.getInt(24)
            val dataSize   = buf.getInt(40)  // Bytes 40-43: data-Chunk Groesse
            if (sampleRate <= 0) 0f else dataSize / (sampleRate * 2f)  // 16-bit Mono = 2 Bytes/Sample
        } catch (_: Exception) { 0f }
    }

    /**
     * T62: Liest einen Bereich [startSec, endSec) aus einer WAV-Datei.
     * Offset-basiertes Lesen — kein readBytes() auf die gesamte Datei.
     * Geeignet fuer Detektions-Clips (≤3 s → ≤288 KB RAM) und kurze Referenz-WAVs.
     *
     * @param openStream  Lambda das einen frischen InputStream oeffnet (wird 2× aufgerufen)
     * @param fileLength  Laenge der Datei in Bytes (fuer Bereichsberechnung)
     * @param startSec    Startposition (0f = Dateianfang)
     * @param endSec      Endposition (null = bis Dateiende)
     */
    private fun readWavSamplesRange(
        openStream: () -> InputStream?,
        fileLength: Long,
        startSec:   Float,
        endSec:     Float?
    ): ShortArray? {
        return try {
            // Header-Pass: sampleRate + Kanalzahl ermitteln
            val header = ByteArray(44)
            openStream()?.use { it.read(header) } ?: return null
            val buf           = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val sampleRate    = buf.getInt(24).takeIf { it > 0 } ?: return null
            val numChannels   = buf.getShort(22).toInt().coerceIn(1, 2)
            val bytesPerFrame = numChannels * 2

            val fileDataBytes = (fileLength - 44L).coerceAtLeast(0L)
            val totalFrames   = fileDataBytes / bytesPerFrame
            val startFrame    = (startSec * sampleRate).toLong().coerceIn(0L, totalFrames)
            val endFrame      = if (endSec != null) {
                (endSec * sampleRate).toLong().coerceIn(startFrame, totalFrames)
            } else {
                totalFrames
            }

            val startByte = 44L + startFrame * bytesPerFrame
            val dataSize  = ((endFrame - startFrame) * bytesPerFrame).coerceAtLeast(0L).toInt()
            if (dataSize <= 0) return null

            // Daten-Pass: nur den angeforderten Bereich lesen
            val bytes = ByteArray(dataSize)
            openStream()?.use { stream ->
                stream.skip(startByte)
                var read = 0
                while (read < dataSize) {
                    val n = stream.read(bytes, read, dataSize - read)
                    if (n < 0) break
                    read += n
                }
            } ?: return null

            val numSamples = dataSize / 2
            val samples    = ShortArray(numSamples)
            val sbuf       = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) samples[i] = sbuf.short
            samples
        } catch (e: Exception) {
            Log.e("AnalysisVM", "WAV-Range-Lesen fehlgeschlagen", e)
            null
        }
    }

    // ── Verifikation / Export ────────────────────────────────────────

    /** Verifikation einer Detektion (nachtraeglich in Analyse) */
    fun verifyDetection(
        detectionId: String,
        status: VerificationStatus,
        correctedSpecies: String? = null
    ) {
        val session = _uiState.value.selectedSession ?: return
        val previousDetections = _uiState.value.detections

        // In-Memory aktualisieren (optimistisch)
        val updated = previousDetections.map { det ->
            if (det.id == detectionId) {
                det.copy(
                    verificationStatus = status,
                    correctedSpecies = if (status == VerificationStatus.CORRECTED) correctedSpecies else null,
                    verifiedAtMs = System.currentTimeMillis()
                )
            } else det
        }
        _uiState.update { it.copy(detections = updated) }

        // Persistieren (verifications.jsonl)
        viewModelScope.launch(Dispatchers.IO) {
            verificationMutex.withLock {
                try {
                    val event = VerificationEvent(
                        detectionId = detectionId,
                        status = status,
                        correctedSpecies = correctedSpecies,
                        verifiedAtMs = System.currentTimeMillis()
                    )
                    sessionManager.appendVerificationLine(session.sessionDir, Json.encodeToString(event) + "\n")
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            detections = previousDetections,
                            saveErrorMessage = AppStrings.get(R.string.common_save_failed)
                        ) }
                    }
                }
            }
        }
    }

    /** saveErrorMessage nach Anzeige zuruecksetzen */
    fun consumeSaveErrorMessage() {
        _uiState.update { it.copy(saveErrorMessage = null) }
    }

    /**
     * T194: Raven-Tabelle der Session oeffnen. Die Datei wird dafuer frisch erzeugt (wie beim Session-Stopp),
     * damit spaetere Verifikationen/Bemerkungen enthalten sind; scheitert das (z.B. keine Aufnahme), wird
     * eine vorhandene Datei gelesen. Gibt es keine, kommt ein Hinweis.
     */
    fun openRavenTable() {
        val dir = _uiState.value.selectedSession?.sessionDir ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sessionManager.exportRavenSelectionTable(dir, appPreferences) }
            val table = sessionManager.readRavenText(dir)?.let { RavenTable.parse(it) }
            if (table == null) {
                _uiState.update { it.copy(ravenExportMessage = AppStrings.get(R.string.analysis_raven_keine_datei)) }
            } else {
                _uiState.update { it.copy(ravenTable = table) }
            }
        }
    }

    fun closeRavenTable() {
        _uiState.update { it.copy(ravenTable = null) }
    }

    /** T194: Upload-Status fuer den Knopf in der Kopfzeile. */
    val uploadStatus get() = uploadManager.status

    /** T194: geoeffnete Session zum Upload einreihen (gleiche Einstellungen wie im Live-Tab). */
    fun uploadSession() {
        val dir = _uiState.value.selectedSession?.sessionDir ?: return
        uploadManager.wifiOnly = appPreferences.wifiOnly
        uploadManager.autoUpload = appPreferences.autoUpload
        uploadManager.enqueue(dir)
        reportError(AppStrings.get(R.string.analysis_upload_eingereiht))
    }

    /**
     * T194: Ausschnitt auf der Session-Achse verschieben/setzen (Uebersichtsleiste).
     * Rendert nur den Ausschnitt neu (wie der Detektions-Zoom).
     */
    fun showWindow(startSec: Float, widthSec: Float) {
        val total = _uiState.value.recordingDurationSec
        if (total <= 0f || widthSec <= 0f) return
        val start = startSec.coerceIn(0f, (total - widthSec).coerceAtLeast(0f))
        val range = DetectionExcerpt.Range(start, start + widthSec, start)
        val f = _uiState.value.recordingFile
        val rec = recording
        when {
            rec != null -> renderZoomSonogram(range) { rec.readRange(range.startSec, range.endSec) }
            f != null -> renderZoomSonogram(range) { ExcerptWav.readRange(f, range.startSec, range.endSec) }
        }
    }

    /** T109: Bemerkung der geoeffneten Session aendern (session.json + Raven-Datei neu schreiben). */
    fun updateSessionNote(text: String) {
        val current = _uiState.value.selectedSession ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = sessionManager.updateSessionNote(current.sessionDir, text.take(200), appPreferences)
            if (updated == null) {
                _uiState.update { it.copy(saveErrorMessage = AppStrings.get(R.string.analysis_bemerkung_konnte_nicht_gespeichert_w)) }
                return@launch
            }
            _uiState.update { st ->
                st.copy(
                    selectedSession = st.selectedSession?.takeIf { it.sessionDir == current.sessionDir }
                        ?.copy(metadata = updated) ?: st.selectedSession,
                    sessions = st.sessions.map {
                        if (it.sessionDir == current.sessionDir) it.copy(metadata = updated) else it
                    }
                )
            }
        }
    }

    /** Raven-Export-Feedback nach Anzeige zuruecksetzen */
    fun consumeRavenExportMessage() {
        _uiState.update { it.copy(ravenExportMessage = null) }
    }

    /** Atlas-Codes aus assets (T68), lazy geladen, fuer EditDetectionDialog */
    val atlasCodes: List<AtlasCode> by lazy { atlasCodeRepository.loadCodes() }

    /**
     * Bearbeitet Stückzahl, Atlascode und Bemerkung einer Detektion (T68).
     * Direktschreib-Muster analog zu verifyDetection() in AnalysisViewModel.
     */
    fun editDetection(
        detectionId: String,
        count: Int,
        atlasCode: String?,
        note: String?,
        voiceMemoFile: String? = null  // T69: relativer Pfad oder null
    ) {
        val session = _uiState.value.selectedSession ?: return
        val previousDetections = _uiState.value.detections
        val current = previousDetections.find { it.id == detectionId } ?: return

        // Optimistisches In-Memory-Update
        val updated = current.copy(
            count = count,
            atlasCode = atlasCode,
            note = note,
            voiceMemoFile = voiceMemoFile
        )
        _uiState.update { it.copy(detections = previousDetections.map { det ->
            if (det.id == detectionId) updated else det
        }) }

        viewModelScope.launch(Dispatchers.IO) {
            verificationMutex.withLock {
                try {
                    // detections.jsonl: Zeile ersetzen — ueber SessionManager (T93b: gemeinsamer Mutex,
                    // offener Writer der laufenden Session wird beruecksichtigt)
                    sessionManager.replaceDetectionIn(session.sessionDir, updated)

                    // verifications.jsonl: granular appenden
                    val now = System.currentTimeMillis()
                    if (current.count != count) sessionManager.appendVerificationLine(session.sessionDir,
                        Json.encodeToString(VerificationEvent(detectionId, current.verificationStatus,
                            verifiedAtMs = now, actionType = VerificationEvent.ACTION_COUNT_CHANGED)) + "\n")
                    if (current.atlasCode != atlasCode) sessionManager.appendVerificationLine(session.sessionDir,
                        Json.encodeToString(VerificationEvent(detectionId, current.verificationStatus,
                            verifiedAtMs = now, actionType = VerificationEvent.ACTION_ATLAS_SET)) + "\n")
                    if (current.note != note) sessionManager.appendVerificationLine(session.sessionDir,
                        Json.encodeToString(VerificationEvent(detectionId, current.verificationStatus,
                            verifiedAtMs = now, actionType = VerificationEvent.ACTION_NOTE_ADDED)) + "\n")
                    // T69: Memo-Events
                    if (current.voiceMemoFile == null && voiceMemoFile != null) {
                        sessionManager.appendVerificationLine(session.sessionDir, Json.encodeToString(VerificationEvent(
                            detectionId, current.verificationStatus,
                            verifiedAtMs = now,
                            actionType = VerificationEvent.ACTION_MEMO_RECORDED,
                            newValue = voiceMemoFile)) + "\n")
                    }
                    if (current.voiceMemoFile != null && voiceMemoFile == null) {
                        sessionManager.deleteMemo(session.sessionDir, detectionId)  // T131: File + SAF
                        sessionManager.appendVerificationLine(session.sessionDir, Json.encodeToString(VerificationEvent(
                            detectionId, current.verificationStatus,
                            verifiedAtMs = now,
                            actionType = VerificationEvent.ACTION_MEMO_DELETED)) + "\n")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            detections = previousDetections,
                            saveErrorMessage = AppStrings.get(R.string.common_save_failed)
                        ) }
                    }
                }
            }
        }
    }

    /** T69: Memo-Zieldatei fuer File-Modus (Analyse-Tab: aus Session-Verzeichnis). */
    fun getMemoTargetFile(detectionId: String, sessionDir: File? = _uiState.value.selectedSession?.sessionDir): File? {
        val dir = sessionDir ?: return null
        if (sessionManager.docFor(dir) != null) return null  // T131: SAF-Session → getMemoTargetUri
        return sessionManager.memoFile(dir, detectionId)
    }

    /** T69: Memo-Ziel-URI fuer SAF-Modus (Analyse-Tab: aus Session-Verzeichnis via DocumentFile). */
    fun getMemoTargetUri(detectionId: String): Uri? {
        val sessionDir = _uiState.value.selectedSession?.sessionDir ?: return null
        // T131: SAF-Session → echtes DocumentFile statt fromFile(virtueller Pfad)
        val doc = sessionManager.docFor(sessionDir)
            ?: androidx.documentfile.provider.DocumentFile.fromFile(sessionDir)
        return sessionManager.memoDocFile(doc, detectionId)?.uri
    }

    /** Artsuche fuer AddManualDetectionDialog (T72). */
    fun searchSpecies(query: String): List<Pair<String, String>> =
        speciesNameResolver.searchSpecies(query)

    /**
     * Fuegt eine Art manuell zur Session-Detektion hinzu (T72, Analyse-Tab).
     * Schreibt direkt in detections.jsonl + verifications.jsonl der ausgewaehlten Session.
     */
    fun addManualDetection(
        speciesKey: String,
        count: Int = 1,
        atlasCode: String? = null,
        note: String? = null,
        lat: Double? = null,
        lon: Double? = null
    ) {
        val session = _uiState.value.selectedSession ?: return
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
        // In-Memory: an den Anfang der Liste
        _uiState.update { it.copy(detections = listOf(det) + it.detections) }
        // Persistenz
        viewModelScope.launch(Dispatchers.IO) {
            verificationMutex.withLock {
                try {
                    sessionManager.appendDetectionLine(session.sessionDir, Json.encodeToString(det) + "\n")
                    sessionManager.appendVerificationLine(session.sessionDir, Json.encodeToString(VerificationEvent(
                        detectionId = det.id,
                        status = VerificationStatus.CONFIRMED,
                        verifiedAtMs = now,
                        actionType = VerificationEvent.ACTION_MANUAL_ADD,
                        newValue = "species:$speciesKey|count:$count"
                    )) + "\n")
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(saveErrorMessage = AppStrings.get(R.string.common_save_failed)) }
                    }
                }
            }
        }
    }

    /**
     * Sperrt oder entsperrt eine Art im Analyse-Tab (T73).
     * Persistiert lockSpecies-Flag + loggt ACTION_SPECIES_LOCKED (AC5/AC7).
     * Kein lockedSpecies-StateFlow noetig: AnalysisViewModel hat keine Inference-Pipeline.
     * Direktschreib-Muster analog zu editDetection() (kein sessionManager.replaceDetection).
     */
    fun toggleLock(detectionId: String) {
        val session = _uiState.value.selectedSession ?: return
        val previousDetections = _uiState.value.detections
        val det = previousDetections.find { it.id == detectionId } ?: return
        val updated = det.copy(lockSpecies = !det.lockSpecies)

        // Optimistisches In-Memory-Update
        _uiState.update {
            it.copy(detections = previousDetections.map { d -> if (d.id == detectionId) updated else d })
        }

        viewModelScope.launch(Dispatchers.IO) {
            verificationMutex.withLock {
                try {
                    // detections.jsonl: Zeile ersetzen — ueber SessionManager (T93b: gemeinsamer Mutex,
                    // offener Writer der laufenden Session wird beruecksichtigt)
                    sessionManager.replaceDetectionIn(session.sessionDir, updated)
                    // verifications.jsonl: SPECIES_LOCKED-Event appenden
                    sessionManager.appendVerificationLine(session.sessionDir,
                        Json.encodeToString(VerificationEvent(
                            detectionId = detectionId,
                            status = det.verificationStatus,
                            verifiedAtMs = System.currentTimeMillis(),
                            actionType = VerificationEvent.ACTION_SPECIES_LOCKED,
                            newValue = updated.lockSpecies.toString()
                        )) + "\n"
                    )
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                detections = previousDetections,
                                saveErrorMessage = AppStrings.get(R.string.common_save_failed)
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Ersetzt eine Detektion durch eine vom Nutzer gewaehlte Alternative (T72, Analyse-Tab).
     * Analog zu LiveViewModel.selectAlternative, aber direktes Schreiben in verifications.jsonl.
     */
    fun selectAlternative(detectionId: String, candidate: DetectionCandidate) {
        val session = _uiState.value.selectedSession ?: return
        val previousDetections = _uiState.value.detections
        val original = previousDetections.find { it.id == detectionId } ?: return
        val now = System.currentTimeMillis()

        // Neue Detection aus der Alternative erzeugen
        val newDet = DetectionResult(
            id = java.util.UUID.randomUUID().toString(),
            scientificName = candidate.scientificName,
            commonName = candidate.commonName,
            confidence = candidate.confidence,
            timestampMs = now,
            chunkStartSec = original.chunkStartSec,
            chunkEndSec = original.chunkEndSec,
            sampleRate = original.sampleRate,
            latitude = original.latitude,
            longitude = original.longitude,
            lastDetectedMs = now
        )
        // In-Memory: Original REPLACED, Alternative an Index 0
        val updated = previousDetections.map { det ->
            if (det.id == detectionId) det.copy(
                verificationStatus = VerificationStatus.REPLACED,
                correctedSpecies = candidate.scientificName,
                verifiedAtMs = now
            ) else det
        }.filter { it.scientificName != candidate.scientificName || it.id == detectionId }
        _uiState.update { it.copy(detections = listOf(newDet) + updated) }

        viewModelScope.launch(Dispatchers.IO) {
            verificationMutex.withLock {
                try {
                    sessionManager.appendVerificationLine(session.sessionDir, Json.encodeToString(VerificationEvent(
                        detectionId = detectionId,
                        status = VerificationStatus.REPLACED,
                        correctedSpecies = candidate.scientificName,
                        verifiedAtMs = now,
                        actionType = VerificationEvent.ACTION_ALTERNATIVE_CONFIRMED
                    )) + "\n")
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(saveErrorMessage = AppStrings.get(R.string.common_save_failed)) }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
        // T69: Voice-Memo Cleanup
        voiceMemoRecorder.release()
    }
}
