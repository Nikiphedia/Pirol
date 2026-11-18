package ch.etasystems.pirol.ui.analysis

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.audio.VoiceMemoRecorder
import ch.etasystems.pirol.audio.export.DetectionExcerpt
import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.data.export.DetectionShareRequest
import ch.etasystems.pirol.audio.dsp.DynamicRangeMapper
import ch.etasystems.pirol.audio.dsp.MelSpectrogram
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
    private val context: Context  // T69: fuer VoiceMemoRecorder SAF + memoAudioPlayer
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    // Serialisiert Verifikations-IO: verhindert Race bei Schnell-Taps (AC6)
    private val verificationMutex = Mutex()

    // --- T69: Voice-Memo ---

    /** Sprach-Memo Recorder — separater AudioRecord, blockiert RecordingService nicht. */
    val voiceMemoRecorder = VoiceMemoRecorder()

    /** Separater AudioPlayer fuer Memo-Wiedergabe (kein Sharing mit Session-Player). */
    val memoAudioPlayer by lazy { AudioPlayer(context) }

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
        // Playback-State beobachten
        viewModelScope.launch {
            audioPlayer.state.collect { state ->
                _uiState.update { it.copy(playbackState = state) }
                if (state == AudioPlayer.PlaybackState.IDLE) {
                    _uiState.update {
                        it.copy(
                            playingChunkIndex = null, playingSource = null,
                            playingDetectionId = null, playbackPositionSec = null
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
                val wavBytes = try {
                    SegmentedRecording.segmentFiles(dir).sumOf { it.length() }
                } catch (_: Exception) { 0L }
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

    /** Session oeffnen (Detail-Ansicht) */
    fun openSession(summary: SessionSummary) {
        viewModelScope.launch(Dispatchers.IO) {
            val detections = sessionManager.loadDetectionsWithVerifications(summary.sessionDir)
            // T61: Defekte WAV-Header aus V0.0.6 reparieren, bevor Dauer/Sonogramm/Playback die Datei lesen
            sessionManager.repairRecordingHeaders(summary.sessionDir)
            val recFile = sessionManager.getRecordingFile(summary.sessionDir)
            // T62: URI aus File ableiten (fuer SAF-Pfade auf SD-Karte via content://)
            val recUri  = recFile?.let { Uri.fromFile(it) }
            // T130: alle Segmente; Dauer = ganze Session (vorher nur recording.wav)
            val rec = sessionManager.loadRecording(summary.sessionDir)
            recording = rec
            val durationSec = rec?.durationSec?.takeIf { it > 0f } ?: recFile?.let { readWavDurationSec(it) } ?: 0f
            _uiState.update {
                it.copy(
                    selectedSession = summary,
                    detections = detections,
                    recordingFile = recFile,
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
                recordingFile = null,
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
        when {
            rec != null && rec.parts.size > 1 -> audioPlayer.play(rec)  // T130: Segmente nacheinander
            uri != null -> audioPlayer.play(uri)
            f   != null -> audioPlayer.play(f)
            else -> return
        }
        _uiState.update { it.copy(playingChunkIndex = 0, playingDetectionId = null) }
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
    fun playDetection(detection: DetectionResult) {
        val uri = _uiState.value.recordingUri
        val f   = _uiState.value.recordingFile
        val rec = recording
        // T116: Ausschnitt mit Kontext (3 s davor, 5 s danach, max. excerptMaxSec)
        // T130: auf der Session-Achse ueber alle Segmente
        val range = rec?.excerptRange(detection, appPreferences.excerptMaxSec.toFloat())
            ?: DetectionExcerpt.compute(detection, _uiState.value.recordingDurationSec, appPreferences.excerptMaxSec.toFloat())
        when {
            rec != null -> audioPlayer.playFromOffset(rec, startSec = range.startSec, endSec = range.endSec, normalizeDbfs = PLAYBACK_DBFS)
            uri != null -> audioPlayer.playFromOffset(uri, startSec = range.startSec, endSec = range.endSec, normalizeDbfs = PLAYBACK_DBFS)
            f   != null -> audioPlayer.playFromOffset(f,   startSec = range.startSec, endSec = range.endSec, normalizeDbfs = PLAYBACK_DBFS)
            else -> return
        }
        _uiState.update { it.copy(playingChunkIndex = null, playingDetectionId = detection.id) }
        if (rec != null) renderZoomSonogram(range) { rec.readRange(range.startSec, range.endSec) }
        else if (f != null) renderZoomSonogram(range) { ExcerptWav.readRange(f, range.startSec, range.endSec) }
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
            val frames = try {
                val pcm = read() ?: return@launch
                MelSpectrogram(sampleRate = pcm.sampleRate).process(pcm.samples)
            } catch (e: Exception) {
                Log.e("AnalysisVM", "T116 Zoom-Sonogramm fehlgeschlagen", e)
                return@launch
            }
            if (frames.isEmpty() || _uiState.value.selectedSession == null) return@launch
            val state = SpectrogramState(maxFrames = frames.size)
            state.appendFrames(frames)
            _uiState.update { it.copy(zoomRange = range, zoomSpectrogramState = state) }
        }
    }

    /** T116: Share-Dialog fuer eine Detektion oeffnen. */
    fun requestShare(detection: DetectionResult) {
        val f = _uiState.value.recordingFile ?: return
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
        if (file == null || session == null) {
            reportError(AppStrings.get(R.string.analysis_referenz_keine_aufnahme_vorhanden))
            return
        }
        val duration = _uiState.value.recordingDurationSec
        // T130: alle Segmente der Session
        val rec = recording ?: SegmentedRecording.forRecordingFile(file)
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
        when {
            // T130: Session-Achse ueber alle Segmente
            rec != null -> rec.sessionRelative(det).let { d ->
                audioPlayer.playFromOffset(rec, startSec = d.chunkStartSec, endSec = d.chunkEndSec, normalizeDbfs = PLAYBACK_DBFS)
            }
            uri != null -> audioPlayer.playFromOffset(uri, startSec = det.chunkStartSec, endSec = det.chunkEndSec, normalizeDbfs = PLAYBACK_DBFS)
            f   != null -> audioPlayer.playFromOffset(f,   startSec = det.chunkStartSec, endSec = det.chunkEndSec, normalizeDbfs = PLAYBACK_DBFS)
            else -> return
        }
        _uiState.update { it.copy(playingSource = PlayingSource.DETECTION) }
    }

    /** Referenz-Audio abspielen */
    fun playReference() {
        val entry = _uiState.value.selectedReference ?: return
        val wavFile = referenceRepository.getWavFile(entry) ?: return
        audioPlayer.play(wavFile)
        _uiState.update { it.copy(playingSource = PlayingSource.REFERENCE) }
    }

    // ── Sonogramm-Rendering ─────────────────────────────────────────

    /**
     * T62: Detektions-Sonogramm — liest nur das 3s-Fenster (chunkStart..chunkEnd).
     * Verhindert OOM bei langen Sessions (vorher: gesamte recording.wav in RAM).
     * Kurze Clips (≤3 s @ 48kHz Mono) = ≤288 KB → kein OOM-Risiko.
     */
    private fun renderDetectionSonogram(detection: DetectionResult) {
        val recFile = _uiState.value.recordingFile ?: return
        val rec = recording
        viewModelScope.launch(Dispatchers.Default) {
            // T130: bei mehreren Segmenten ueber die Session-Achse lesen
            val d = rec?.sessionRelative(detection) ?: detection
            val samples = readWavSamplesRange(
                openStream = { rec?.openWavStream() ?: recFile.inputStream() },
                fileLength  = rec?.virtualWavLength ?: recFile.length(),
                startSec    = d.chunkStartSec,
                endSec      = d.chunkEndSec
            ) ?: return@launch
            val state  = SpectrogramState(maxFrames = 2048)
            val mel    = MelSpectrogram(sampleRate = 48000)
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
            val mel    = MelSpectrogram(sampleRate = 48000)
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
                        val mel = MelSpectrogram(sampleRate = sampleRate)
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

            val state = SpectrogramState(maxFrames = TARGET_FRAMES)
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

    /** Raven Selection Table exportieren (T78: Felder-Selektion via AppPreferences) */
    fun exportRavenTable() {
        val dir = _uiState.value.selectedSession?.sessionDir ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val file = sessionManager.exportRavenSelectionTable(dir, appPreferences)
            val msg = if (file != null) AppStrings.get(R.string.analysis_raven_tabelle_gespeichert, file.name)
                      else AppStrings.get(R.string.analysis_kein_recording_wav_export_nicht)
            _uiState.update { it.copy(ravenExportMessage = msg) }
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
                        getMemoTargetFile(detectionId, session.sessionDir)?.takeIf { it.exists() }?.delete()
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
        return sessionManager.memoFile(dir, detectionId)
    }

    /** T69: Memo-Ziel-URI fuer SAF-Modus (Analyse-Tab: aus Session-Verzeichnis via DocumentFile). */
    fun getMemoTargetUri(detectionId: String): Uri? {
        val sessionDir = _uiState.value.selectedSession?.sessionDir ?: return null
        val doc = androidx.documentfile.provider.DocumentFile.fromFile(sessionDir)
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
        memoAudioPlayer.release()
    }
}
