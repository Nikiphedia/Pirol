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
import ch.etasystems.pirol.ml.AlternativeSelection
import ch.etasystems.pirol.ml.DetectionCandidate
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import ch.etasystems.pirol.ui.components.SpectrogramState
import ch.etasystems.pirol.data.importer.SessionImporter
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
/** T264: Rand links/rechts um ein Session-Memo beim Sprung (s) */
private const val MEMO_VIEW_MARGIN_SEC = 2f

/** T272: Frist, in der das Loeschen eines Session-Memos rueckgaengig gemacht werden kann (>= 5 s) */
private const val MEMO_DELETE_GRACE_MS = 10_000L
/** T264: Mindestbreite des Ausschnitts beim Sprung zu einem Memo (s) */
private const val MEMO_VIEW_MIN_SEC = 10f

class AnalysisViewModel(
    private val sessionManager: SessionManager,
    private val audioPlayer: AudioPlayer,
    private val referenceRepository: ReferenceRepository,
    private val appPreferences: AppPreferences,
    private val atlasCodeRepository: AtlasCodeRepository,
    private val speciesNameResolver: SpeciesNameResolver,  // T72: Artsuche fuer AddManualDetectionDialog
    private val context: Context,  // T69: fuer VoiceMemoRecorder SAF
    private val playback: ch.etasystems.pirol.audio.PlaybackController,  // T173: zentrale Wiedergabe
    private val uploadManager: ch.etasystems.pirol.data.sync.UploadManager,  // T194: Upload aus der Session-Kopfzeile
    private val rescanEngine: ch.etasystems.pirol.rescan.RescanEngine,  // T221: Rescan einer Aufnahme
    private val importer: ch.etasystems.pirol.data.importer.SessionImporter  // T255: Import (ZIP, Ordner, Fremdaudio)
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
        observeRescan()  // T250/M4
        observeImport()  // T255
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
                            _uiState.update { s ->
                                val p = playheadForCurrent(audioPlayer.currentPositionSec())
                                s.copy(playbackPositionSec = p, lastPlaybackPositionSec = p ?: s.lastPlaybackPositionSec)
                            }
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
        viewModelScope.launch(Dispatchers.IO) { loadSessionsNow() }
    }

    /** Liste laden und abwarten (T255: danach importierte Session oeffnen) */
    private suspend fun loadSessionsNow() {
        withContext(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            val dirs = sessionManager.listSessions()
            val speciesIndex = HashMap<String, List<String>>()
            val summaries = dirs.mapNotNull { dir ->
                val meta = sessionManager.loadMetadata(dir) ?: return@mapNotNull null
                val detections = sessionManager.loadDetectionsWithVerifications(dir)
                // T52: WAV-Dateigrösse ermitteln (T130: Summe aller Segmente)
                val wavBytes = sessionManager.recordingSizeBytes(dir)  // T131: File + SAF
                speciesIndex[meta.sessionId] = detections.map { it.scientificName }
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
            _uiState.update { it.copy(sessions = summaries, sessionSpecies = speciesIndex, isLoading = false) }
        }
    }

    /** T202: Suchtext der Session-Liste */
    fun setSessionSearch(query: String) {
        _uiState.update { it.copy(sessionSearch = query) }
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
            val sessionMemos = sessionManager.loadSessionMemos(summary.sessionDir)  // T264
            val artMemoSpans = sessionManager.loadMemoWallSpansByDetection(summary.sessionDir)  // T264
            val variants = rescanEngine.variants(summary.sessionDir)  // T221
            // T62: URI aus File ableiten; T131: bei SAF content://-URI des ersten Segments
            val recUri  = recFile?.let { Uri.fromFile(it) }
                ?: rec?.firstSource?.uriString?.let { Uri.parse(it) }
            val durationSec = rec?.durationSec?.takeIf { it > 0f } ?: recFile?.let { readWavDurationSec(it) } ?: 0f
            _uiState.update {
                it.copy(
                    selectedSession = summary,
                    variants = variants,
                    activeVariant = null,
                    // T186: bestes Fenster auch fuer Sessions vor T186 aus den Merge-Events ableiten
                    detections = detections.map { d -> DetectionOccurrences.withBestWindow(d, mergeTimes[d.id].orEmpty()) },
                    mergeTimestamps = mergeTimes,
                    sessionMemos = sessionMemos,
                    artMemoSpans = artMemoSpans,
                    recordingFile = recFile,
                    hasRecording = rec != null || recFile != null,
                    recordingUri  = recUri,
                    recordingDurationSec = durationSec,
                    lastPlaybackPositionSec = null,
                    pendingStartSec = null,
                    zoomRange = null,
                    zoomSpectrogramState = SpectrogramState(),
                    zoomPcm = null
                )
            }
            // T260: laeuft fuer diese Session ein Rescan, gleich dessen Variante zeigen (waechst je Flush)
            rescanEngine.runningVariant(summary.sessionDir)?.let { loadVariant(summary.sessionDir, it) }
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
                variants = emptyList(),
                activeVariant = null,
                detections = emptyList(),
                mergeTimestamps = emptyMap(),
                sessionMemos = emptyList(),
                artMemoSpans = emptyMap(),
                recordingFile = null,
                hasRecording = false,
                recordingUri = null,
                spectrogramState = SpectrogramState(),
                sonogramLoading = false,
                sonogramError = null,
                zoomRange = null,
                zoomSpectrogramState = SpectrogramState(),
                zoomPcm = null
            )
        }
    }

    /** Chunk-Navigation: No-op in T46 — Timeline-Navigation kommt in T48. */
    fun navigateChunk(delta: Int) { /* T48: Timeline-Navigation */ }

    /**
     * Aufnahme-Datei abspielen (Start/Stopp-Umschalter).
     * T62: bevorzugt Uri-Variante fuer SAF-Kompatibilitaet (AC5).
     * T210: startet an der zuletzt angetippten Position (sonst Anfang).
     */
    fun playChunk(chunkIndex: Int) {
        if (playback.isPlaying(PlaybackKeys.SESSION)) {
            playback.stop()
            return
        }
        startSessionFrom(_uiState.value.pendingStartSec ?: 0f)
    }

    /** T210: ganze Aufnahme ab [startSec] spielen (T173: ueber den zentralen Controller, verdraengt Laufendes). */
    private fun startSessionFrom(startSec: Float) {
        val uri = _uiState.value.recordingUri
        val f   = _uiState.value.recordingFile
        val rec = recording
        val start: () -> Unit = when {
            rec != null && rec.parts.size > 1 -> { { audioPlayer.play(rec, startSec) } }  // T130: Segmente nacheinander
            uri != null -> { { audioPlayer.play(uri, startSec) } }
            f   != null -> { { audioPlayer.play(f, startSec) } }
            else -> return
        }
        playback.play(PlaybackKeys.SESSION, start)
        _uiState.update {
            it.copy(playingChunkIndex = 0, playingDetectionId = null, playingOccurrenceStartSec = null, pendingStartSec = null)
        }
    }

    /**
     * T210: Tippen in Sonogramm/Oszillogramm. Laeuft eine Wiedergabe, springt sie an [sec] und spielt weiter;
     * sonst wird nur die Startposition fuer das naechste Play gemerkt.
     */
    fun seekTo(sec: Float) {
        val s = _uiState.value
        if (!s.hasRecording) return
        val pos = sec.coerceIn(0f, s.recordingDurationSec.coerceAtLeast(0f))
        if (playback.playingKey.value != null) {
            startSessionFrom(pos)
        } else {
            _uiState.update { it.copy(pendingStartSec = pos, lastPlaybackPositionSec = pos) }
        }
    }

    /**
     * T210: zur naechsten/vorherigen Detektion (nach Zeit) springen und den Ausschnitt abspielen.
     * [visible] = gerade angezeigte (ggf. nach Art gefilterte) Detektionen. Bezug ist die laufende Detektion,
     * sonst Abspiel-/Cursorposition; ohne Bezug: erste (vor) bzw. letzte (zurueck).
     */
    fun playAdjacent(visible: List<DetectionResult>, forward: Boolean) {
        val s = _uiState.value
        val sorted = PlaybackNav.sortedByTime(visible)
        val from = s.detections.firstOrNull { it.id == s.playingDetectionId }?.bestWindowStartSec
            ?: s.playbackPositionSec ?: s.pendingStartSec ?: s.lastPlaybackPositionSec
            ?: if (forward) -1f else Float.MAX_VALUE / 2
        val target = (if (forward) PlaybackNav.next(sorted, from) else PlaybackNav.previous(sorted, from)) ?: return
        playDetection(target)
    }

    /**
     * T264: Memo aus dem Block "Memos" abspielen (Start/Stopp ueber den zentralen Controller).
     * Art-Memos nutzen denselben Schluessel wie die Memo-Zeile der Artkarte.
     */
    fun playMemo(item: MemoItem) {
        val dir = _uiState.value.selectedSession?.sessionDir ?: return
        val key = PlaybackKeys.memo(item.key)
        if (playback.isPlaying(key)) {
            playback.stop()
            return
        }
        // T301: Zeiger auf die Session-Zeit des Memos legen (Memo-Datei beginnt bei 0 s)
        memoPlayhead = key to item.sessionStartSec
        viewModelScope.launch(Dispatchers.IO) {
            val source = sessionManager.memoSource(dir, item.file)
            withContext(Dispatchers.Main) {
                when (source) {
                    is File -> playback.play(key) { audioPlayer.play(source) }
                    is Uri -> playback.play(key) { audioPlayer.play(source) }
                    else -> reportError(AppStrings.get(R.string.analysis_memo_nicht_gefunden))
                }
            }
        }
    }

    /** T301: Schluessel der Memo-Wiedergabe und Session-Sekunde des Memo-Beginns (null = keine Lage bekannt). */
    private var memoPlayhead: Pair<String, Float?>? = null

    /**
     * T301: Zeigerposition fuer das Sonogramm. Bei Memo-Wiedergabe = Memo-Beginn (Session-Achse) + Position in
     * der Memo-Datei; ohne bekannte Lage kein Zeiger. Sonst unveraendert die Player-Position.
     */
    private fun playheadForCurrent(posSec: Float?): Float? {
        val mp = memoPlayhead ?: return posSec
        if (playback.playingKey.value != mp.first) return posSec
        return PlaybackNav.memoPlayheadSec(posSec, mp.second)
    }

    // --- T272: Session-Memo loeschen, teilen, speichern ---

    private val memoExporter = ch.etasystems.pirol.data.export.MemoExporter(context)

    /** Ausstehendes Loeschen je Memo-Datei; Undo bricht den Job ab. */
    private val pendingMemoDeletes = mutableMapOf<String, Job>()

    /**
     * T272: Session-Memo loeschen. Sofort aus der Anzeige nehmen, Snackbar mit Undo (10 s, >= 5 s). Erst nach
     * Ablauf werden Eintrag und WAV endgueltig entfernt; bei Undo bleibt auf der Platte alles unveraendert.
     * Wird die App in der Frist beendet, bleibt das Memo erhalten (sicherer Fehlerfall).
     */
    fun deleteSessionMemo(item: MemoItem) {
        if (!item.isSessionMemo) return
        val session = _uiState.value.selectedSession ?: return
        if (sessionManager.isActiveSession(session.sessionDir)) return  // Sperre: laufende Aufnahme
        val removed = _uiState.value.sessionMemos.firstOrNull { it.file == item.file } ?: return
        if (pendingMemoDeletes.containsKey(item.file)) return
        if (playback.isPlaying(PlaybackKeys.memo(item.key))) playback.stop()
        _uiState.update { st ->
            st.copy(
                sessionMemos = st.sessionMemos.filter { it.file != item.file },
                undoOffer = UndoOffer(
                    message = AppStrings.get(R.string.analysis_memo_geloescht),
                    actionLabel = AppStrings.get(R.string.common_undo),
                    onUndo = { undoDeleteSessionMemo(session.sessionDir, removed) }
                )
            )
        }
        pendingMemoDeletes[item.file] = viewModelScope.launch {
            delay(MEMO_DELETE_GRACE_MS)
            val ok = sessionManager.deleteSessionMemo(session.sessionDir, item.file)
            pendingMemoDeletes.remove(item.file)
            if (!ok) {
                if (_uiState.value.selectedSession?.sessionDir == session.sessionDir) {
                    _uiState.update { it.copy(sessionMemos = (it.sessionMemos + removed).sortedBy { m -> m.startedAt }) }
                }
                reportError(AppStrings.get(R.string.analysis_memo_loeschen_fehler))
            }
        }
    }

    private fun undoDeleteSessionMemo(sessionDir: File, memo: ch.etasystems.pirol.data.repository.SessionMemo) {
        val job = pendingMemoDeletes.remove(memo.file) ?: return  // schon endgueltig geloescht: nichts mehr zu tun
        job.cancel()
        if (_uiState.value.selectedSession?.sessionDir == sessionDir) {
            _uiState.update { it.copy(sessionMemos = (it.sessionMemos + memo).sortedBy { m -> m.startedAt }) }
        }
    }

    /** T272: true = Loeschen gesperrt, weil die Session gerade aufgenommen wird. */
    fun isMemoDeleteLocked(): Boolean =
        _uiState.value.selectedSession?.sessionDir?.let { sessionManager.isActiveSession(it) } == true

    /** T272: Name des Memo-Exports `yyyyMMddHHmmss_Memo_Bemerkung` (ohne Endung); Zeit unbekannt = jetzt. */
    fun memoExportName(item: MemoItem): String =
        ch.etasystems.pirol.data.export.ExportNames.memoName(item.startMs ?: System.currentTimeMillis(), item.note)

    /** T272: Einzelnes Memo ueber das Share-Sheet teilen (Datei unveraendert). */
    fun shareMemo(item: MemoItem) {
        val dir = _uiState.value.selectedSession?.sessionDir ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val source = sessionManager.memoSource(dir, item.file)
                if (source == null) { reportError(AppStrings.get(R.string.analysis_memo_nicht_gefunden)); return@launch }
                val copy = memoExporter.copyToShareCache(source, memoExportName(item))
                withContext(Dispatchers.Main) { memoExporter.share(copy) }
            } catch (e: Exception) {
                Log.e("AnalysisVM", "T272: Memo teilen fehlgeschlagen", e)
                reportError(AppStrings.get(R.string.analysis_memo_export_fehler))
            }
        }
    }

    /** T272: Einzelnes Memo in die per System-Dialog gewaehlte Datei speichern. */
    fun saveMemoTo(item: MemoItem, target: Uri) {
        val dir = _uiState.value.selectedSession?.sessionDir ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val source = sessionManager.memoSource(dir, item.file)
                if (source == null) { reportError(AppStrings.get(R.string.analysis_memo_nicht_gefunden)); return@launch }
                memoExporter.saveTo(source, target)
                reportError(AppStrings.get(R.string.analysis_memo_gespeichert))  // reportError = Hinweis-Snackbar
            } catch (e: Exception) {
                Log.e("AnalysisVM", "T272: Memo speichern fehlgeschlagen", e)
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) }
                reportError(AppStrings.get(R.string.analysis_memo_export_fehler))
            }
        }
    }

    /** T264: an die Stelle eines Session-Memos springen (Ausschnitt um das Memo, Startposition auf den Beginn). */
    fun jumpToSessionMemo(item: MemoItem) {
        val start = item.sessionStartSec ?: return
        val s = _uiState.value
        if (!s.hasRecording || start >= s.recordingDurationSec) return
        val end = (item.sessionEndSec ?: start).coerceAtLeast(start)
        val width = (end - start + 2 * MEMO_VIEW_MARGIN_SEC).coerceAtLeast(MEMO_VIEW_MIN_SEC)
            .coerceAtMost(s.recordingDurationSec)
        showWindow(start - MEMO_VIEW_MARGIN_SEC, width)
        seekTo(start)
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
            DetectionOccurrences.forEpisode(detection, episode),
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
        _uiState.update { it.copy(zoomRange = null, zoomSpectrogramState = SpectrogramState(), zoomPcm = null) }
    }

    /** T116: Mel-Frames nur fuer den Ausschnitt berechnen; Anzeige zoomt darauf. */
    private fun renderZoomSonogram(range: DetectionExcerpt.Range, read: () -> ExcerptWav.Pcm?) {
        zoomJob?.cancel()
        // T294: langer Ausschnitt einer Ultraschall-Session -> gestreamt, ohne PCM im Speicher
        val zoomRate = recording?.sampleRate ?: _uiState.value.spectrogramState.sampleRate
        if (HighRateZoom.needsStreaming(zoomRate, range.durationSec)) {
            renderZoomSonogramStreaming(range, zoomRate)
            return
        }
        zoomJob = viewModelScope.launch(Dispatchers.Default) {
            var excerpt: ExcerptWav.Pcm? = null
            val result = try {
                val pcm = read() ?: return@launch
                excerpt = pcm
                // T200: Anzeige-Config (Mel bis Nyquist), passend zu Achse und Bin-Zuschnitt
                MelSpectrogram(pcm.sampleRate, SpectrogramConfig.forSessionRate(pcm.sampleRate)).process(pcm.samples) to pcm.sampleRate  // T294
            } catch (e: Exception) {
                Log.e("AnalysisVM", "T116 Zoom-Sonogramm fehlgeschlagen", e)
                return@launch
            }
            val (frames, rate) = result
            if (frames.isEmpty() || _uiState.value.selectedSession == null) return@launch
            val state = SpectrogramState(maxFrames = frames.size, sampleRate = rate)
            state.appendFrames(frames)
            _uiState.update { it.copy(zoomRange = range, zoomSpectrogramState = state, zoomPcm = excerpt) }
        }
    }

    /** T294: Zoom-Sonogramm blockweise mit Stride (Ultraschall, langer Ausschnitt); kein Ausschnitt-PCM. */
    private fun renderZoomSonogramStreaming(range: DetectionExcerpt.Range, rate: Int) {
        val rec = recording
        val f = _uiState.value.recordingFile
        val read: (Long, Long) -> ShortArray? = when {
            rec != null -> { a, b -> rec.readFrames(a, b)?.samples }
            f != null -> { a, b -> ExcerptWav.readRange(f, a.toFloat() / rate, b.toFloat() / rate)?.samples }
            else -> return
        }
        zoomJob = viewModelScope.launch(Dispatchers.Default) {
            val frames = try {
                val start = (range.startSec.toDouble() * rate).toLong()
                val end = (range.endSec.toDouble() * rate).toLong()
                HighRateZoom.stridedFrames(rate, start, end, read) { isActive }
            } catch (e: Exception) {
                Log.e("AnalysisVM", "T294 Zoom-Sonogramm (Stream) fehlgeschlagen", e)
                return@launch
            }
            if (frames.isEmpty() || _uiState.value.selectedSession == null || !isActive) return@launch
            val state = SpectrogramState(maxFrames = frames.size, sampleRate = rate)
            state.appendFrames(frames)
            _uiState.update { it.copy(zoomRange = range, zoomSpectrogramState = state, zoomPcm = null) }
        }
    }

    /** T116: Share-Dialog fuer eine Detektion oeffnen. */
    fun requestShare(detection: DetectionResult) {
        val f = _uiState.value.recordingFile
        if (f == null && recording == null) return  // T131: SAF → nur ueber recording
        val dir = _uiState.value.selectedSession?.sessionDir
        viewModelScope.launch {
            val note = dir?.let { sessionManager.loadMetadata(it)?.note }
            _uiState.update { it.copy(shareRequest = DetectionShareRequest(detection, f, note, recording, sessionDir = dir)) }
        }
    }

    /** T222: Share-Dialog fuer genau eine Episode (Zeitbereich der Episode statt des ganzen Clusters). */
    fun requestShareEpisode(detection: DetectionResult, episode: DetectionOccurrences.Episode) =
        requestShare(DetectionOccurrences.forEpisode(detection, episode))

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

        // T237: Startsekunde des Fensters (Session-Zeitachse) statt 3-s-Chunk-Nummer aus der Wanduhr
        if (_uiState.value.selectedSession == null) return

        _uiState.update {
            it.copy(
                isCompareMode = true,
                compareDetection = detection,
                compareDetectionStartSec = detection.bestWindowStartSec,  // T237
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
                compareDetectionStartSec = null,
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
            val wav = readWavSamplesRange(
                openStream = { rec?.openWavStream() ?: recFile!!.inputStream() },
                fileLength  = rec?.virtualWavLength ?: recFile!!.length(),
                startSec    = d.bestWindowStartSec,  // T186
                endSec      = d.bestWindowEndSec
            ) ?: return@launch
            val state  = SpectrogramState(maxFrames = 2048, sampleRate = wav.sampleRate)
            val mel    = MelSpectrogram(wav.sampleRate, SpectrogramConfig.forSessionRate(wav.sampleRate))  // T247: echte Abtastrate; T294: Ultraschall linear
            val frames = mel.process(wav.samples)
            if (frames.isNotEmpty()) state.appendFrames(frames)
            _uiState.update { it.copy(detectionSpectrogramState = state) }
        }
    }

    /** Referenz-Sonogramm rendern (kurze Referenz-WAV, kein OOM-Risiko). */
    private fun renderReferenceSonogram(entry: ReferenceEntry) {
        val wavFile = referenceRepository.getWavFile(entry) ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val wav = readWavSamplesRange(
                openStream = { wavFile.inputStream() },
                fileLength  = wavFile.length(),
                startSec    = 0f,
                endSec      = null
            ) ?: return@launch
            val state  = SpectrogramState(maxFrames = 2048, sampleRate = wav.sampleRate)
            val mel    = MelSpectrogram(wav.sampleRate, SpectrogramConfig.forSessionRate(wav.sampleRate))  // T247: echte Abtastrate; T294: Ultraschall linear
            val frames = mel.process(wav.samples)
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
                        val mel = MelSpectrogram(sampleRate, SpectrogramConfig.forSessionRate(sampleRate))  // T200: wie Achse; T294: Ultraschall linear
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
                                    // T294: nur behaltene Frames rechnen (gleiche Frames, bei 384 kHz 8x mehr Audio)
                                    collectedFrames.addAll(mel.process(samples) { it % stride == 0L })
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
    /** T247: gelesener WAV-Bereich samt Abtastrate aus dem Header. */
    private class WavRange(val samples: ShortArray, val sampleRate: Int)

    private fun readWavSamplesRange(
        openStream: () -> InputStream?,
        fileLength: Long,
        startSec:   Float,
        endSec:     Float?
    ): WavRange? {
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
            WavRange(samples, sampleRate)
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
        val variant = _uiState.value.activeVariant  // T221: Original (null) oder Rescan-Variante
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
                    sessionManager.appendVerificationLine(session.sessionDir, variant = variant, line = Json.encodeToString(event) + "\n")
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
        val variant = _uiState.value.activeVariant  // T221: Raven der angezeigten Variante
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { sessionManager.exportRavenSelectionTable(dir, appPreferences, variant) }
            val table = sessionManager.readRavenText(dir, variant)?.let { RavenTable.parse(it) }
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

    /**
     * T194: geoeffnete Session zum Upload einreihen (gleiche Einstellungen wie im Live-Tab).
     * T263: manuell ausgeloest; kennt das Modell keine Menschen, fragt der Dialog nach ([resolveMaskChoice]).
     * @param maskChoice Entscheid aus dem Dialog (null = noch keiner)
     */
    fun uploadSession(maskChoice: String? = null) {
        val dir = _uiState.value.selectedSession?.sessionDir ?: return
        uploadManager.wifiOnly = appPreferences.wifiOnly
        uploadManager.autoUpload = appPreferences.autoUpload
        uploadManager.enqueue(dir, manual = true, maskChoice = maskChoice)
        awaitingUploadDecision = true
        uploadFromHere = true
        lastReportedUpload = null
        if (uploadDecisionObserver == null) {
            uploadDecisionObserver = viewModelScope.launch {
                uploadManager.status.collect { s ->
                    if (awaitingUploadDecision && s is ch.etasystems.pirol.data.sync.UploadStatus.AwaitingDecision && s.manual) {
                        awaitingUploadDecision = false
                        _uiState.update { it.copy(pendingMaskChoice = PendingMaskExport.Upload) }
                    } else if (s is ch.etasystems.pirol.data.sync.UploadStatus.Success ||
                        s is ch.etasystems.pirol.data.sync.UploadStatus.Failed
                    ) awaitingUploadDecision = false
                    // T276: Rueckmeldung nur fuer von hier ausgeloeste Uploads (Status-Wechsel, einmal pro Status)
                    if (uploadFromHere && s != lastReportedUpload) {
                        when (s) {
                            is ch.etasystems.pirol.data.sync.UploadStatus.Success -> {
                                uploadFromHere = false
                                reportError(AppStrings.get(R.string.analysis_upload_hochgeladen))
                            }
                            is ch.etasystems.pirol.data.sync.UploadStatus.Failed -> {
                                uploadFromHere = false
                                reportError(AppStrings.get(R.string.analysis_upload_fehlgeschlagen, s.error))
                            }
                            is ch.etasystems.pirol.data.sync.UploadStatus.AwaitingDecision -> if (!s.manual) {
                                uploadFromHere = false
                                reportError(AppStrings.get(R.string.live_upload_wartet_entscheid))
                            }
                            else -> {}
                        }
                        lastReportedUpload = s
                    }
                }
            }
        }
        reportError(AppStrings.get(R.string.analysis_upload_eingereiht))
    }

    // T263: Warten auf den Entscheid bei Modell ohne Mensch-Klasse (nur fuer von hier ausgeloeste Uploads)
    private var awaitingUploadDecision = false
    private var uploadFromHere = false  // T276
    private var lastReportedUpload: ch.etasystems.pirol.data.sync.UploadStatus? = null
    private var uploadDecisionObserver: Job? = null

    /** T263: BirdNET-3-Datei fuer den Ersatz-Scan (null = nicht installiert oder Aufnahme laeuft) */
    fun birdNetScanModelFile(): String? = humanMask.birdNetScanModelFile()

    /** T263: Antwort des Dialogs "Modell ohne Mensch-Klasse"; [choice] null = Abbrechen. */
    fun resolveMaskChoice(choice: ch.etasystems.pirol.data.export.HumanExportMask.Choice?) {
        val pending = _uiState.value.pendingMaskChoice ?: return
        _uiState.update { it.copy(pendingMaskChoice = null) }
        when (pending) {
            is PendingMaskExport.Zip -> {
                if (choice == null) {
                    // Zieldatei war schon angelegt (leer): nicht liegen lassen
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, pending.target) }
                    }
                    reportError(AppStrings.get(R.string.human_mask_abgebrochen))
                } else saveSessionAsZip(pending.target, choice)
            }
            is PendingMaskExport.Folder ->
                if (choice == null) reportError(AppStrings.get(R.string.human_mask_abgebrochen))
                else saveSessionToFolder(pending.treeUri, choice)
            PendingMaskExport.Upload ->
                if (choice == null) reportError(AppStrings.get(R.string.human_mask_abgebrochen))
                else uploadSession(ch.etasystems.pirol.data.export.HumanExportMask.Choice.encode(choice))
        }
    }

    // ── T258: Speichern unter (ZIP oder Ordner) ──────────────────────

    private val saveAs = ch.etasystems.pirol.data.export.SessionSaveAs(context)
    private var saveAsJob: Job? = null
    // T261: Menschen im Export ausblenden (Koin-Singleton, ohne Konstruktor-Aenderung)
    private val humanMask: ch.etasystems.pirol.data.export.HumanExportMask by lazy {
        org.koin.core.context.GlobalContext.get().get()
    }

    /** T261: Stimmen werden beim Speichern ausgeblendet (Hinweis im Dialog) */
    val masksHumans: Boolean get() = humanMask.enabled

    /** T261: laufendes Speichern (inkl. Mensch-Scan) abbrechen */
    fun cancelSaveAs() {
        saveAsJob?.cancel()
    }

    /**
     * T261: Exporteintraege mit ausgeblendeten Stimmen (vorher ggf. scannen, Fortschritt im UiState).
     * Wirft [ch.etasystems.pirol.data.export.HumanExportMask.UnavailableException].
     */
    private suspend fun maskedEntries(
        sessionDir: java.io.File,
        entries: List<ch.etasystems.pirol.data.repository.ExportEntry>,
        choice: ch.etasystems.pirol.data.export.HumanExportMask.Choice = ch.etasystems.pirol.data.export.HumanExportMask.Choice.Active
    ): List<ch.etasystems.pirol.data.repository.ExportEntry> {
        try {
            val plan = humanMask.prepare(sessionDir, choice = choice, onProgress = { p -> _uiState.update { it.copy(humanScanProgress = p) } })
            return humanMask.wrap(entries, plan)
        } finally {
            _uiState.update { it.copy(humanScanProgress = null) }
        }
    }

    /** T261: Meldung fuer einen fehlgeschlagenen/abgebrochenen Speichervorgang */
    private fun saveAsFailure(e: Throwable): String = when (e) {
        is kotlinx.coroutines.CancellationException -> AppStrings.get(R.string.human_mask_abgebrochen)
        is ch.etasystems.pirol.data.export.HumanExportMask.UnavailableException -> AppStrings.get(R.string.human_mask_nicht_moeglich)
        else -> AppStrings.get(R.string.analysis_saveas_fehler)
    }

    /** Vorgeschlagener ZIP-Name der geoeffneten Session (`yyyyMMddHHmmss_Bemerkung.zip`). */
    fun suggestedZipName(): String {
        val m = _uiState.value.selectedSession?.metadata ?: return "session.zip"
        return ch.etasystems.pirol.data.export.ExportNames.sessionZipName(m.startedAt, m.note, m.sessionId)
    }

    /** Speichern unter ist fuer die laufende Aufnahme-Session und waehrend eines laufenden Speicherns gesperrt. */
    fun canSaveAs(): Boolean {
        val dir = _uiState.value.selectedSession?.sessionDir ?: return false
        return !sessionManager.isActiveSession(dir) && saveAsJob?.isActive != true
    }

    /** Geoeffnete Session als ZIP (Inhalt wie Upload-ZIP) in die per SAF gewaehlte Datei [target] schreiben. */
    fun saveSessionAsZip(target: Uri, choice: ch.etasystems.pirol.data.export.HumanExportMask.Choice = ch.etasystems.pirol.data.export.HumanExportMask.Choice.Active) {
        val session = _uiState.value.selectedSession ?: return
        if (!canSaveAs()) return
        val label = target.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: suggestedZipName()
        reportError(AppStrings.get(R.string.analysis_saveas_laeuft))
        saveAsJob = viewModelScope.launch(Dispatchers.IO) {
            val failure: Throwable? = try {
                val detections = try { sessionManager.detectionsForExport(session.sessionDir) } catch (e: Exception) { null }
                val entries = maskedEntries(session.sessionDir, saveAs.entriesOf(session.sessionDir, sessionManager.docFor(session.sessionDir)), choice)
                saveAs.saveZip(entries, detections, target, session.metadata.sessionId)
                null
            } catch (e: Exception) {
                Log.e("AnalysisVM", "Speichern unter (ZIP) fehlgeschlagen", e)
                e
            }
            // T263: Modell ohne Mensch-Klasse: Dialog (Zieldatei bleibt fuer den zweiten Anlauf stehen)
            if (failure is ch.etasystems.pirol.data.export.HumanExportMask.UnavailableException && choice is ch.etasystems.pirol.data.export.HumanExportMask.Choice.Active) {
                _uiState.update { it.copy(pendingMaskChoice = PendingMaskExport.Zip(target)) }
                return@launch
            }
            // T261: nach Abbruch des Scans bzw. ohne Mensch-Erkennung die leere Zieldatei nicht liegen lassen
            if (failure is kotlinx.coroutines.CancellationException ||
                failure is ch.etasystems.pirol.data.export.HumanExportMask.UnavailableException
            ) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) }
            reportError(
                if (failure == null) AppStrings.get(R.string.analysis_saveas_ok, label)
                else saveAsFailure(failure)
            )
        }
    }

    /** Geoeffnete Session als Unterordner im per SAF gewaehlten Ordner [treeUri] ablegen. */
    fun saveSessionToFolder(treeUri: Uri, choice: ch.etasystems.pirol.data.export.HumanExportMask.Choice = ch.etasystems.pirol.data.export.HumanExportMask.Choice.Active) {
        val session = _uiState.value.selectedSession ?: return
        if (!canSaveAs()) return
        // Ziel in der PIROL-Basis oder in der Session selbst: ablehnen (wie beim Verschieben, T181)
        val targetId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (targetId != null) {
            val verdict = ch.etasystems.pirol.data.repository.MoveTargetGuard.check(
                targetId, sessionManager.baseDocumentIds(), listOfNotNull(sessionManager.sessionDocumentId(session.sessionDir))
            )
            val msgRes = when (verdict) {
                ch.etasystems.pirol.data.repository.MoveTargetGuard.Verdict.INSIDE_BASE -> R.string.analysis_saveas_ziel_in_basis
                ch.etasystems.pirol.data.repository.MoveTargetGuard.Verdict.SAME_AS_SOURCE -> R.string.analysis_saveas_ziel_gleich_quelle
                else -> null
            }
            if (msgRes != null) { reportError(AppStrings.get(msgRes)); return }
        }
        val root = saveAs.targetRoot(treeUri)
        if (root == null) { reportError(AppStrings.get(R.string.analysis_batch_ziel_nicht_schreibbar)); return }
        val m = session.metadata
        val base = ch.etasystems.pirol.data.export.ExportNames.sessionName(m.startedAt, m.note, m.sessionId)
        reportError(AppStrings.get(R.string.analysis_saveas_laeuft))
        saveAsJob = viewModelScope.launch(Dispatchers.IO) {
            var failure: Throwable? = null
            val name = try {
                val detections = try { sessionManager.detectionsForExport(session.sessionDir) } catch (e: Exception) { null }
                val entries = maskedEntries(session.sessionDir, saveAs.entriesOf(session.sessionDir, sessionManager.docFor(session.sessionDir)), choice)
                saveAs.saveFolder(root, base, entries, detections)
            } catch (e: Exception) {
                Log.e("AnalysisVM", "Speichern unter (Ordner) fehlgeschlagen", e)
                failure = e
                null
            }
            // T263: Modell ohne Mensch-Klasse: Dialog (noch nichts angelegt)
            if (failure is ch.etasystems.pirol.data.export.HumanExportMask.UnavailableException && choice is ch.etasystems.pirol.data.export.HumanExportMask.Choice.Active) {
                _uiState.update { it.copy(pendingMaskChoice = PendingMaskExport.Folder(treeUri)) }
                return@launch
            }
            reportError(
                if (name != null) AppStrings.get(R.string.analysis_saveas_ok, name)
                else saveAsFailure(failure ?: IllegalStateException())
            )
        }
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

    /**
     * T257: Startzeit, Ort und Bemerkung einer importierten Session aendern; danach Kopfzeile, Liste und
     * angezeigte Variante neu laden. [lat]/[lon] beide null = Ort entfernen. Fehler als Snackbar.
     */
    fun updateImportedSessionInfo(start: java.time.ZonedDateTime, lat: Double?, lon: Double?, note: String) {
        val current = _uiState.value.selectedSession ?: return
        if (_uiState.value.rescanProgress != null) {
            reportError(AppStrings.get(R.string.analysis_angaben_rescan_laeuft))
            return
        }
        // T273: neue Startzeit benennt den Ordner um -> laufende Wiedergabe vorher stoppen
        if (ch.etasystems.pirol.data.repository.SessionInfoEdit.deltaMs(current.metadata.startedAt, start)?.let { it != 0L } == true) playback.stop()
        viewModelScope.launch(Dispatchers.IO) {
            val dir = current.sessionDir
            val result = sessionManager.updateImportedSessionInfo(dir, start, lat, lon, note.take(200), appPreferences)
            val updated = when (result) {
                is SessionManager.InfoEditResult.Ok -> result.metadata
                SessionManager.InfoEditResult.Active -> {
                    reportError(AppStrings.get(R.string.analysis_angaben_aufnahme_laeuft)); return@launch
                }
                SessionManager.InfoEditResult.Busy -> {
                    reportError(AppStrings.get(R.string.analysis_angaben_upload_laeuft)); return@launch
                }
                else -> {
                    reportError(AppStrings.get(R.string.analysis_angaben_fehler)); return@launch
                }
            }
            // T273: Ordner umbenannt/verschoben -> Liste neu laden und die Session am neuen Ort oeffnen
            val newDir = (result as SessionManager.InfoEditResult.Ok).sessionDir
            if (newDir != null && newDir.path != dir.path) {
                loadSessionsNow()
                val summary = _uiState.value.sessions.firstOrNull { it.sessionDir.path == newDir.path }
                if (summary != null && _uiState.value.selectedSession?.sessionDir == dir) openSession(summary)
                return@launch
            }
            _uiState.update { st ->
                st.copy(
                    selectedSession = st.selectedSession?.takeIf { it.sessionDir == dir }
                        ?.copy(metadata = updated) ?: st.selectedSession,
                    sessions = st.sessions.map { if (it.sessionDir == dir) it.copy(metadata = updated) else it }
                )
            }
            if (_uiState.value.selectedSession?.sessionDir == dir) {
                loadVariant(dir, _uiState.value.activeVariant)
                val variants = rescanEngine.variants(dir)
                _uiState.update { st -> if (st.selectedSession?.sessionDir != dir) st else st.copy(variants = variants) }
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
        val variant = _uiState.value.activeVariant  // T221: Original (null) oder Rescan-Variante
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
                    // detections.jsonl: ueber SessionManager (T93b: gemeinsamer Mutex). T251 (M1): nur die eigenen
                    // Felder auf den aktuellen Stand (laufende Session: Live-Liste, Merges bleiben erhalten)
                    sessionManager.modifyDetectionIn(session.sessionDir, detectionId, variant) {
                        it.copy(count = count, atlasCode = atlasCode, note = note, voiceMemoFile = voiceMemoFile)
                    }

                    // verifications.jsonl: granular appenden
                    val now = System.currentTimeMillis()
                    if (current.count != count) sessionManager.appendVerificationLine(session.sessionDir, variant = variant, line =
                        Json.encodeToString(VerificationEvent(detectionId, current.verificationStatus,
                            verifiedAtMs = now, actionType = VerificationEvent.ACTION_COUNT_CHANGED)) + "\n")
                    if (current.atlasCode != atlasCode) sessionManager.appendVerificationLine(session.sessionDir, variant = variant, line =
                        Json.encodeToString(VerificationEvent(detectionId, current.verificationStatus,
                            verifiedAtMs = now, actionType = VerificationEvent.ACTION_ATLAS_SET)) + "\n")
                    if (current.note != note) sessionManager.appendVerificationLine(session.sessionDir, variant = variant, line =
                        Json.encodeToString(VerificationEvent(detectionId, current.verificationStatus,
                            verifiedAtMs = now, actionType = VerificationEvent.ACTION_NOTE_ADDED)) + "\n")
                    // T69: Memo-Events
                    if (current.voiceMemoFile == null && voiceMemoFile != null) {
                        sessionManager.appendVerificationLine(session.sessionDir, variant = variant, line = Json.encodeToString(VerificationEvent(
                            detectionId, current.verificationStatus,
                            verifiedAtMs = now,
                            actionType = VerificationEvent.ACTION_MEMO_RECORDED,
                            newValue = voiceMemoFile)) + "\n")
                    }
                    if (current.voiceMemoFile != null && voiceMemoFile == null) {
                        sessionManager.deleteMemo(session.sessionDir, detectionId)  // T131: File + SAF
                        sessionManager.appendVerificationLine(session.sessionDir, variant = variant, line = Json.encodeToString(VerificationEvent(
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

    /** T203: Zeitfenster fuer einen manuellen Eintrag aus der Abspielposition (session-relativ). */
    fun manualEntryTime(positionSec: Float?, nowMs: Long = System.currentTimeMillis()): ManualEntryTime {
        val state = _uiState.value
        val startMs = state.selectedSession?.metadata?.startedAt?.let {
            try {
                java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try { java.time.Instant.parse(it).toEpochMilli() } catch (_: Exception) { null }
            }
        }
        return ManualEntryTime.compute(positionSec, state.recordingDurationSec, startMs, nowMs,
            state.selectedSession?.metadata?.recordingSegments)  // T274: Segmentluecken
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
        lon: Double? = null,
        positionSec: Float? = null
    ) {
        val session = _uiState.value.selectedSession ?: return
        val variant = _uiState.value.activeVariant  // T221: Original (null) oder Rescan-Variante
        val now = System.currentTimeMillis()
        // T203: Zeit aus Abspielposition +/- 10 s (ohne Position: jetzt, kein Audio-Bezug)
        val time = manualEntryTime(positionSec, now)
        val det = DetectionResult(
            id = java.util.UUID.randomUUID().toString(),
            scientificName = speciesKey,
            commonName = speciesNameResolver.commonName(speciesKey),
            confidence = 1.0f,
            timestampMs = time.timestampMs,
            chunkStartSec = time.chunkStartSec,
            chunkEndSec = time.chunkEndSec,
            sampleRate = if (time.hasPosition) session.metadata.sampleRate else 0,
            latitude = lat,
            longitude = lon,
            firstSeenAtMs = time.timestampMs,
            lastSeenAtMs = time.timestampMs,
            lastDetectedMs = time.timestampMs,
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
                    sessionManager.appendDetectionLine(session.sessionDir, variant = variant, line = Json.encodeToString(det) + "\n")
                    sessionManager.appendVerificationLine(session.sessionDir, variant = variant, line = Json.encodeToString(VerificationEvent(
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
        val variant = _uiState.value.activeVariant  // T221: Original (null) oder Rescan-Variante
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
                    // detections.jsonl: T251 (M1) nur lockSpecies auf den aktuellen Stand
                    sessionManager.modifyDetectionIn(session.sessionDir, detectionId, variant) {
                        it.copy(lockSpecies = updated.lockSpecies)
                    }
                    // verifications.jsonl: SPECIES_LOCKED-Event appenden
                    sessionManager.appendVerificationLine(session.sessionDir, variant = variant, line =
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
     * T237: Ersetzt die Art der bestehenden Detektion durch die gewaehlte Alternative (gleiche ID,
     * Aufnahmezeit, Fenster; Original oder Rescan-Variante). detections.jsonl: Zeile ersetzt;
     * verifications.jsonl: ALTERNATIVE_CONFIRMED (alte Art in newValue). Snackbar mit Undo.
     */
    fun selectAlternative(detectionId: String, candidate: DetectionCandidate) {
        val session = _uiState.value.selectedSession ?: return
        val variant = _uiState.value.activeVariant  // T221: Original (null) oder Rescan-Variante
        val before = _uiState.value.detections.find { it.id == detectionId } ?: return
        val updated = AlternativeSelection.apply(before, candidate, System.currentTimeMillis())
        _uiState.update { st ->
            st.copy(
                // revert(current, x) schreibt die Alternativ-Felder von x in den aktuellen Stand
                detections = st.detections.map { if (it.id == detectionId) AlternativeSelection.revert(it, updated) else it },
                undoOffer = UndoOffer(
                    message = AppStrings.get(R.string.live_alternative_gewaehlt, candidate.commonName),
                    actionLabel = AppStrings.get(R.string.common_undo),
                    onUndo = { undoAlternative(session, variant, before, updated) }
                )
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            verificationMutex.withLock {
                try {
                    // T251 (M1): nur die Alternativ-Felder auf den aktuellen Stand
                    sessionManager.modifyDetectionIn(session.sessionDir, detectionId, variant) {
                        AlternativeSelection.revert(it, updated)
                    }
                    sessionManager.appendVerificationLine(session.sessionDir, variant = variant, line = Json.encodeToString(VerificationEvent(
                        detectionId = detectionId,
                        status = VerificationStatus.CONFIRMED,
                        verifiedAtMs = updated.verifiedAtMs ?: System.currentTimeMillis(),
                        actionType = VerificationEvent.ACTION_ALTERNATIVE_CONFIRMED,
                        newValue = before.scientificName
                    )) + "\n")
                } catch (e: Exception) {
                    rollbackAlternative(before)
                    runCatching {
                        sessionManager.modifyDetectionIn(session.sessionDir, detectionId, variant) { AlternativeSelection.revert(it, before) }
                    }
                    _uiState.update { it.copy(undoOffer = null, saveErrorMessage = AppStrings.get(R.string.common_save_failed)) }
                }
            }
        }
    }

    /** T237: Felder der Detektion im State auf den Stand vor der Alternative zurueck, liefert die neue Fassung. */
    private fun rollbackAlternative(before: DetectionResult): DetectionResult? {
        var restored: DetectionResult? = null
        _uiState.update { st ->
            st.copy(detections = st.detections.map {
                if (it.id == before.id) AlternativeSelection.revert(it, before).also { r -> restored = r } else it
            })
        }
        return restored
    }

    private fun undoAlternative(session: SessionSummary, variant: String?, before: DetectionResult, applied: DetectionResult) {
        // T251 (Zusatz T249): zwischenzeitliche Verifikation bleibt (wie in verifications.jsonl nach dem Undo)
        var found = false
        _uiState.update { st ->
            st.copy(detections = st.detections.map {
                if (it.id == before.id) { found = true; AlternativeSelection.undo(it, before, applied) } else it
            })
        }
        if (!found) return
        viewModelScope.launch(Dispatchers.IO) {
            verificationMutex.withLock {
                try {
                    sessionManager.modifyDetectionIn(session.sessionDir, before.id, variant) {
                        AlternativeSelection.undo(it, before, applied)
                    }
                    sessionManager.removeLastVerificationIn(
                        session.sessionDir, before.id, variant, VerificationEvent.ACTION_ALTERNATIVE_CONFIRMED  // T249 (M6)
                    )
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(saveErrorMessage = AppStrings.get(R.string.common_save_failed)) }
                    }
                }
            }
        }
    }

    /** T237: Undo-Hinweis nach Anzeige zuruecksetzen */
    fun consumeUndoOffer() {
        _uiState.update { it.copy(undoOffer = null) }
    }


    // ── T255: Import ─────────────────────────────────────────────────

    /** Laufender Import (Fortschritt), null = keiner */
    val importActive: StateFlow<ch.etasystems.pirol.data.importer.SessionImporter.Active?> get() = importer.active

    /** PIROL-Session aus ZIP oder Ordner importieren (Hintergrund) */
    fun importPackage(uri: android.net.Uri, isFolder: Boolean) {
        importer.startPackage(uri, isFolder)
        startImportService()
    }

    /** Vordergrund-Dienst haelt den Prozess bei langen Importen am Leben (wie Rescan, T252) */
    private fun startImportService() {
        runCatching { ch.etasystems.pirol.data.importer.ImportService.start(context) }
            .onFailure { Log.w("AnalysisViewModel", "Import-Dienst nicht gestartet", it) }
    }

    /** Gewaehlte Audiodateien vorpruefen; danach zeigt der Dialog Zeiten/Ort ([AnalysisUiState.audioImportProbes]) */
    fun probeAudioImport(uris: List<android.net.Uri>) {
        _uiState.update { it.copy(audioImportOpen = true, audioImportProbes = null) }
        viewModelScope.launch {
            val probes = runCatching { importer.probe(uris) }.getOrElse { emptyList() }
            _uiState.update { if (it.audioImportOpen) it.copy(audioImportProbes = probes) else it }
        }
    }

    fun dismissAudioImport() { _uiState.update { it.copy(audioImportOpen = false, audioImportProbes = null) } }

    fun startAudioImport(request: ch.etasystems.pirol.data.importer.AudioImportRequest) {
        dismissAudioImport()
        importer.startAudio(request)
        startImportService()
    }

    fun cancelImport() = importer.cancel()

    /** Rescan-Dialog wurde geoeffnet (Anfrage nach Fremdaudio-Import) */
    fun consumeRescanRequest() { _uiState.update { it.copy(rescanRequest = false) } }

    /** T273 (B8): Sessions des letzten Fremdaudio-Imports (erste = geoeffnete); der Rescan-Dialog scannt alle */
    @Volatile private var importBatch: List<File> = emptyList()

    /** T273: Zahl der Sessions, die der Rescan-Dialog scannt (1 = nur die geoeffnete) */
    fun rescanBatchSize(): Int {
        val dir = _uiState.value.selectedSession?.sessionDir ?: return 1
        return importBatch.takeIf { it.firstOrNull() == dir }?.size ?: 1
    }

    /** T273: Rescan-Dialog ohne Start geschlossen -> Import-Warteschlange verwerfen */
    fun clearImportBatch() { importBatch = emptyList() }

    /** Ende des Imports: Liste neu laden, erste neue Session oeffnen; nach Fremdaudio den Rescan-Dialog anbieten. */
    private fun observeImport() {
        viewModelScope.launch {
            importer.outcomes.collect { o ->
                loadSessionsNow()
                val msg = when (o) {
                    is SessionImporter.Outcome.Done -> if (o.renamed && o.sessionIds.size == 1)
                        AppStrings.get(R.string.import_fertig_neue_id, o.sessionIds.first())
                        else AppStrings.get(R.string.import_fertig, o.sessionIds.size)
                    is SessionImporter.Outcome.Cancelled -> AppStrings.get(R.string.import_abgebrochen, o.sessionIds.size)
                    is SessionImporter.Outcome.Failed -> when (o.failure) {
                        SessionImporter.Failure.NOT_A_SESSION -> AppStrings.get(R.string.import_fehler_keine_session)
                        SessionImporter.Failure.NO_AUDIO -> AppStrings.get(R.string.import_fehler_kein_audio, o.detail ?: "")
                        SessionImporter.Failure.READ -> AppStrings.get(R.string.import_fehler_lesen, o.detail ?: "")
                        SessionImporter.Failure.WRITE -> AppStrings.get(R.string.import_fehler_schreiben)
                    }
                }
                val first = o.sessionIds.firstOrNull()?.let { id -> _uiState.value.sessions.firstOrNull { it.metadata.sessionId == id } }
                // T273 (B8): alle importierten Sessions fuer den Rescan-Dialog merken (Reihenfolge des Imports)
                importBatch = if (o is SessionImporter.Outcome.Done && o.kind == SessionImporter.Kind.AUDIO) {
                    o.sessionIds.mapNotNull { id -> _uiState.value.sessions.firstOrNull { it.metadata.sessionId == id }?.sessionDir }
                } else emptyList()
                if (o is SessionImporter.Outcome.Done && first != null && !_uiState.value.selectionMode) {
                    _uiState.update { it.copy(rescanRequest = o.kind == SessionImporter.Kind.AUDIO) }
                    openSession(first)
                    reportError(msg)  // Snackbar der Detailansicht
                } else {
                    _uiState.update { it.copy(batchMessage = msg) }  // Snackbar der Liste
                    if (_uiState.value.selectedSession != null) reportError(msg)
                }
            }
        }
    }

    // ── T221: Rescan und Varianten ───────────────────────────────────

    /** Aufnahme laeuft -> Rescan-Knopf deaktiviert */
    val recordingActive: StateFlow<Boolean> get() = rescanEngine.recording


    /** Installierte Modelle fuer den Rescan-Dialog */
    fun rescanModels(): List<ch.etasystems.pirol.ml.ModelManager.InstalledModel> = rescanModelOffer().offered

    /** T295: Abtastrate der geoeffneten Session in Hz (0 = unbekannt) */
    fun rescanSessionRateHz(): Int = _uiState.value.selectedSession?.metadata?.sampleRate ?: 0

    /** T295: Modelle nach Abtastrate der Session aufgeteilt (Ultraschall-Modelle fuer zu kleine Rate ausgeblendet) */
    fun rescanModelOffer(): ch.etasystems.pirol.rescan.RescanModelOffer.Offer =
        ch.etasystems.pirol.rescan.RescanModelOffer.split(
            rescanEngine.models(), _uiState.value.selectedSession?.metadata?.sampleRate ?: 0)

    /** Listen (Assets und eigene) fuer den Rescan-Dialog */
    suspend fun rescanRegions(): List<ch.etasystems.pirol.ml.RegionInfo> = rescanEngine.regions()

    /** Voreinstellungen: Gruppenwahl und Schwelle der Session, aktives Modell, Overlap aus den Einstellungen */
    fun rescanDefaults(): ch.etasystems.pirol.rescan.RescanOptions? {
        val meta = _uiState.value.selectedSession?.metadata ?: return null
        // T239: Gruppenwahl der Session vorbelegen
        return rescanEngine.defaults(meta.confidenceThreshold, meta.speciesFilter?.groups)
    }

    /** Rescan der geoeffneten Session im Hintergrund starten (Fortschritt im UI-State, abbrechbar). */
    fun startRescan(options: ch.etasystems.pirol.rescan.RescanOptions) {
        val dir = _uiState.value.selectedSession?.sessionDir ?: return
        if (rescanEngine.active.value != null) return
        if (rescanEngine.recording.value) {
            reportError(AppStrings.get(R.string.analysis_rescan_aufnahme_laeuft))
            return
        }
        playback.stop()
        _uiState.update { it.copy(rescanProgress = 0f, rescanSessionDir = dir) }
        // T273 (B8): nach Import mehrerer Dateien alle neuen Sessions nacheinander scannen
        val more = importBatch.takeIf { it.firstOrNull() == dir }?.drop(1) ?: emptyList()
        importBatch = emptyList()
        rescanEngine.start(dir, options, more)  // T250/M4: laeuft im Engine-Scope, ueberlebt das ViewModel
        // T252: Vordergrund-Dienst haelt den Prozess am Leben (auch bei ausgeschaltetem Bildschirm)
        runCatching { ch.etasystems.pirol.rescan.RescanService.start(context) }
            .onFailure { Log.w("AnalysisViewModel", "Rescan-Dienst nicht gestartet", it) }
    }

    /** T260: zuletzt gesehene laufende Variante / Flush-Stand (automatisch oeffnen bzw. neu laden) */
    private var seenRescanVariant: String? = null
    private var seenRescanVersion = -1
    private var rescanReloadJob: kotlinx.coroutines.Job? = null

    /** T250/M4: Zustand und Ende des Rescans aus dem Singleton in den UI-State spiegeln (auch nach neuem ViewModel) */
    private fun observeRescan() {
        viewModelScope.launch {
            rescanEngine.active.collect { a ->
                _uiState.update { it.copy(rescanProgress = a?.progress, rescanSessionDir = a?.sessionDir) }
                onRescanFlush(a)
            }
        }
        viewModelScope.launch {
            rescanEngine.outcomes.collect { o ->
                when (o) {
                    is ch.etasystems.pirol.rescan.RescanEngine.Outcome.Done -> {
                        val info = o.info
                        if (_uiState.value.selectedSession?.sessionDir == o.sessionDir) {
                            rescanReloadJob?.cancel()
                            val variants = rescanEngine.variants(o.sessionDir)
                            _uiState.update { it.copy(variants = variants) }
                            // T273: erster Scan einer importierten Session ist jetzt das Original
                            verificationMutex.withLock { loadVariant(o.sessionDir, if (o.promoted) null else info.id) }
                            if (o.promoted) sessionManager.loadMetadata(o.sessionDir)?.let { m ->
                                _uiState.update { st ->
                                    if (st.selectedSession?.sessionDir != o.sessionDir) st
                                    else st.copy(selectedSession = st.selectedSession.copy(metadata = m))
                                }
                            }
                        }
                        if (o.promoted) loadSessions()
                        reportError(AppStrings.get(R.string.analysis_rescan_fertig, info.detectionCount, info.transferredVerifications))
                    }
                    is ch.etasystems.pirol.rescan.RescanEngine.Outcome.Cancelled -> {
                        refreshAfterRescan(o.sessionDir, o.variantId)  // T260: Teilergebnis bleibt sichtbar
                        reportError(AppStrings.get(R.string.analysis_rescan_abgebrochen))
                    }
                    is ch.etasystems.pirol.rescan.RescanEngine.Outcome.Failed -> {
                        refreshAfterRescan(o.sessionDir, o.variantId)
                        when (o.error) {
                        is ch.etasystems.pirol.rescan.RescanAbortedException,
                        is ch.etasystems.pirol.rescan.RescanEngine.RecordingActiveException ->
                            reportError(AppStrings.get(R.string.analysis_rescan_aufnahme_laeuft))
                        is ch.etasystems.pirol.rescan.RescanEngine.NoRecordingException ->
                            reportError(AppStrings.get(R.string.analysis_rescan_keine_aufnahme))
                        is ch.etasystems.pirol.rescan.RescanEngine.ModelUnavailableException ->
                            reportError(AppStrings.get(R.string.analysis_rescan_modell_fehlt))
                        is ch.etasystems.pirol.rescan.RescanEngine.SourceRateTooLowException ->
                            reportError(AppStrings.get(R.string.analysis_rescan_rate_zu_tief, o.error.minRate / 1000, o.error.rate / 1000))
                        else -> reportError(AppStrings.get(R.string.analysis_rescan_fehler))
                        }
                    }
                }
            }
        }
    }

    /**
     * T260: Laufende Variante der geoeffneten Session: beim Anlegen automatisch oeffnen, danach je Flush neu
     * laden (nur wenn sie angezeigt wird). Laden unter der Verifikations-Sperre, damit gerade gespeicherte
     * Nutzer-Aktionen schon in der Datei stehen.
     */
    private fun onRescanFlush(a: ch.etasystems.pirol.rescan.RescanEngine.Active?) {
        val id = a?.variantId ?: return
        val dir = a.sessionDir
        if (_uiState.value.selectedSession?.sessionDir != dir) return
        val opened = id != seenRescanVariant
        if (!opened && a.version == seenRescanVersion) return
        seenRescanVariant = id
        seenRescanVersion = a.version
        if (!opened && _uiState.value.activeVariant != id) return
        rescanReloadJob?.cancel()
        rescanReloadJob = viewModelScope.launch(Dispatchers.IO) {
            val variants = rescanEngine.variants(dir)
            _uiState.update { st -> if (st.selectedSession?.sessionDir != dir) st else st.copy(variants = variants) }
            verificationMutex.withLock { loadVariant(dir, id) }
        }
    }

    /** T260: nach Abbruch/Fehler die (abgebrochene) Variante zeigen */
    private fun refreshAfterRescan(dir: File, variantId: String?) {
        if (_uiState.value.selectedSession?.sessionDir != dir) return
        rescanReloadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val variants = rescanEngine.variants(dir)
            _uiState.update { st -> if (st.selectedSession?.sessionDir != dir) st else st.copy(variants = variants) }
            if (variantId != null && variants.any { it.id == variantId } &&
                _uiState.value.activeVariant == variantId) {
                verificationMutex.withLock { loadVariant(dir, variantId) }
            }
        }
    }

    /** Laufenden Rescan abbrechen (T260: Teilergebnis bleibt als abgebrochene Variante). */
    fun cancelRescan() { rescanEngine.cancel() }

    /** Variante anzeigen: null = Original, sonst Rescan-Kennung. */
    fun selectVariant(id: String?) {
        val dir = _uiState.value.selectedSession?.sessionDir ?: return
        if (id == _uiState.value.activeVariant) return
        viewModelScope.launch(Dispatchers.IO) { loadVariant(dir, id) }
    }

    private suspend fun loadVariant(dir: File, id: String?) {
        val detections = sessionManager.loadDetectionsWithVerifications(dir, id)
        val mergeTimes = sessionManager.loadMergeTimestamps(dir, id)
        _uiState.update { st ->
            if (st.selectedSession?.sessionDir != dir) st else st.copy(
                activeVariant = id,
                detections = detections.map { d -> DetectionOccurrences.withBestWindow(d, mergeTimes[d.id].orEmpty()) },
                mergeTimestamps = mergeTimes
            )
        }
    }

    /** Variante loeschen (Bestaetigung in der UI); zeigt danach das Original. */
    fun deleteVariant(id: String) {
        val dir = _uiState.value.selectedSession?.sessionDir ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { rescanEngine.deleteVariant(dir, id) }.getOrDefault(false)
            if (!ok) reportError(AppStrings.get(R.string.common_save_failed))
            if (_uiState.value.activeVariant == id) loadVariant(dir, null)
            val variants = rescanEngine.variants(dir)
            _uiState.update { st -> if (st.selectedSession?.sessionDir != dir) st else st.copy(variants = variants) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
        // T69: Voice-Memo Cleanup
        voiceMemoRecorder.release()
    }
}
