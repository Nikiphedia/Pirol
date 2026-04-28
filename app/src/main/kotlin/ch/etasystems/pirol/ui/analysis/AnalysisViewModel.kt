package ch.etasystems.pirol.ui.analysis

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.audio.VoiceMemoRecorder
import ch.etasystems.pirol.audio.dsp.DynamicRangeMapper
import ch.etasystems.pirol.audio.dsp.MelSpectrogram
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.AtlasCodeRepository
import ch.etasystems.pirol.data.repository.ReferenceEntry
import ch.etasystems.pirol.data.repository.ReferenceRepository
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.ml.AtlasCode
import ch.etasystems.pirol.ml.CHUNK_DURATION_MS
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import ch.etasystems.pirol.ui.components.SpectrogramState
import kotlinx.coroutines.Dispatchers
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
class AnalysisViewModel(
    private val sessionManager: SessionManager,
    private val audioPlayer: AudioPlayer,
    private val referenceRepository: ReferenceRepository,
    private val appPreferences: AppPreferences,
    private val atlasCodeRepository: AtlasCodeRepository,
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

    init {
        // T56: Sonogramm-Dynamik-Prefs laden
        reloadSpectrogramPrefs()
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
                    _uiState.update { it.copy(playingChunkIndex = null, playingSource = null) }
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
                _uiState.update { it.copy(saveErrorMessage = "Memo auf 60s gekürzt") }
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
                // T52: WAV-Dateigrösse ermitteln (recording.wav)
                val wavBytes = try {
                    File(dir, "recording.wav").length()
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
            val recFile = sessionManager.getRecordingFile(summary.sessionDir)
            // T62: URI aus File ableiten (fuer SAF-Pfade auf SD-Karte via content://)
            val recUri  = recFile?.let { Uri.fromFile(it) }
            val durationSec = recFile?.let { readWavDurationSec(it) } ?: 0f
            _uiState.update {
                it.copy(
                    selectedSession = summary,
                    detections = detections,
                    recordingFile = recFile,
                    recordingUri  = recUri,
                    recordingDurationSec = durationSec
                )
            }
            // T62: Sonogramm streaming rendern (kein readBytes() auf ganzes File)
            if (recFile != null) {
                renderSessionSonogramStreaming { recFile.inputStream() }
            }
        }
    }

    /** Zurueck zur Session-Liste */
    fun closeSession() {
        audioPlayer.stop()
        _uiState.update {
            it.copy(
                selectedSession = null,
                detections = emptyList(),
                recordingFile = null,
                recordingUri = null,
                spectrogramState = SpectrogramState(),
                spectrogramRange = null
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
        when {
            uri != null -> audioPlayer.play(uri)
            f   != null -> audioPlayer.play(f)
            else -> return
        }
        _uiState.update { it.copy(playingChunkIndex = 0) }
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
        when {
            uri != null -> audioPlayer.playFromOffset(uri, startSec = detection.chunkStartSec, endSec = detection.chunkEndSec)
            f   != null -> audioPlayer.playFromOffset(f,   startSec = detection.chunkStartSec, endSec = detection.chunkEndSec)
            else -> return
        }
        _uiState.update { it.copy(playingChunkIndex = null) }
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
                detectionSpectrogramRange = null,
                referenceSpectrogramState = SpectrogramState(),
                referenceSpectrogramRange = null,
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
        when {
            uri != null -> audioPlayer.playFromOffset(uri, startSec = det.chunkStartSec, endSec = det.chunkEndSec)
            f   != null -> audioPlayer.playFromOffset(f,   startSec = det.chunkStartSec, endSec = det.chunkEndSec)
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
        viewModelScope.launch(Dispatchers.Default) {
            val samples = readWavSamplesRange(
                openStream = { recFile.inputStream() },
                fileLength  = recFile.length(),
                startSec    = detection.chunkStartSec,
                endSec      = detection.chunkEndSec
            ) ?: return@launch
            val state  = SpectrogramState(maxFrames = 2048)
            val mel    = MelSpectrogram(sampleRate = 48000)
            val frames = mel.process(samples)
            if (frames.isNotEmpty()) state.appendFrames(frames)
            val range  = if (frames.isNotEmpty()) DynamicRangeMapper.computeStatic(frames) else null
            _uiState.update {
                it.copy(detectionSpectrogramState = state, detectionSpectrogramRange = range)
            }
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
            val range  = if (frames.isNotEmpty()) DynamicRangeMapper.computeStatic(frames) else null
            _uiState.update {
                it.copy(referenceSpectrogramState = state, referenceSpectrogramRange = range)
            }
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
    private fun renderSessionSonogramStreaming(openStream: () -> InputStream?) {
        viewModelScope.launch(Dispatchers.Default) {
            val TARGET_FRAMES = 2048
            val CHUNK_SAMPLES = 480_000   // 10 Sekunden @ 48kHz
            val HOP_SIZE      = 512       // MelSpectrogram-Hop-Groesse

            // Pass 1: Header lesen → sampleRate + totalSamples
            val header = ByteArray(44)
            val sampleRate: Int
            val totalSamples: Long
            try {
                openStream()?.use { stream -> stream.read(header) } ?: return@launch
                val buf      = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                sampleRate   = buf.getInt(24).takeIf { it > 0 } ?: return@launch
                val dataSize = buf.getInt(40).toLong().coerceAtLeast(0L)
                totalSamples = dataSize / 2L  // 16-bit Mono
            } catch (e: Exception) {
                Log.e("AnalysisVM", "Sonogramm-Header-Fehler", e)
                return@launch
            }

            // Stride: gleichmaessige Auswahl auf TARGET_FRAMES
            val totalFramesEst  = (totalSamples / HOP_SIZE).toInt().coerceAtLeast(1)
            val stride          = maxOf(1, totalFramesEst / TARGET_FRAMES)

            // Pass 2: Chunks streamen, Mel berechnen, Stride filtern
            val mel             = MelSpectrogram(sampleRate = sampleRate)
            val collectedFrames = mutableListOf<FloatArray>()
            var frameCounter    = 0  // laufender Zaehler fuer Stride (ueber Chunk-Grenzen)

            try {
                openStream()?.use { stream ->
                    stream.skip(44)  // RIFF-Header ueberspringen
                    val chunkBytes = ByteArray(CHUNK_SAMPLES * 2)
                    var bytesRead: Int
                    while (stream.read(chunkBytes).also { bytesRead = it } > 0) {
                        val numSamples = bytesRead / 2
                        val samples    = ShortArray(numSamples)
                        val bbuf = java.nio.ByteBuffer
                            .wrap(chunkBytes, 0, bytesRead)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until numSamples) samples[i] = bbuf.short

                        val frames = mel.process(samples)
                        for (frame in frames) {
                            if (frameCounter % stride == 0) collectedFrames.add(frame)
                            frameCounter++
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AnalysisVM", "Sonogramm-Streaming-Fehler", e)
                if (collectedFrames.isEmpty()) return@launch
                // Teilweise gesammelte Frames verwenden
            }

            // Sicherheitsnetz: nie mehr als TARGET_FRAMES
            val limited = collectedFrames.take(TARGET_FRAMES)
            if (limited.isEmpty()) return@launch

            val state = SpectrogramState(maxFrames = TARGET_FRAMES)
            state.appendFrames(limited)
            val range = DynamicRangeMapper.computeStatic(limited)
            _uiState.update {
                it.copy(spectrogramState = state, spectrogramRange = range)
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

    // ── Settings / Prefs ────────────────────────────────────────────

    /**
     * T56/T56b: Liest Sonogramm-Dynamik-Prefs neu ein (Toggle + manueller dB-Range + Gamma aus Settings).
     * Wird beim ViewModel-Start und bei ON_RESUME des Analyse-Tabs aufgerufen.
     */
    fun reloadSpectrogramPrefs() {
        val autoContrast = appPreferences.spectrogramAutoContrast
        val minDb        = appPreferences.spectrogramMinDb
        val maxDb        = appPreferences.spectrogramMaxDb
        val gamma        = appPreferences.spectrogramGamma
        val ceilingDb    = appPreferences.spectrogramCeilingDb
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
                    val veriFile = File(session.sessionDir, "verifications.jsonl")
                    val event = VerificationEvent(
                        detectionId = detectionId,
                        status = status,
                        correctedSpecies = correctedSpecies,
                        verifiedAtMs = System.currentTimeMillis()
                    )
                    veriFile.appendText(Json.encodeToString(event) + "\n")
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            detections = previousDetections,
                            saveErrorMessage = "Speichern fehlgeschlagen"
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

    /** Raven Selection Table exportieren */
    fun exportRavenTable() {
        val dir = _uiState.value.selectedSession?.sessionDir ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val file = sessionManager.exportRavenSelectionTable(dir)
            val msg = if (file != null) "Raven-Tabelle gespeichert: ${file.name}"
                      else "Kein recording.wav — Export nicht moeglich"
            _uiState.update { it.copy(ravenExportMessage = msg) }
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
                    // detections.jsonl: Zeile ersetzen (temp + rename fuer Atomaritaet)
                    val detFile = File(session.sessionDir, "detections.jsonl")
                    if (detFile.exists()) {
                        val encodedLine = Json.encodeToString(updated)
                        val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
                        val lines = detFile.readLines().map { raw ->
                            if (raw.isBlank()) raw
                            else try {
                                if (lenient.decodeFromString<DetectionResult>(raw).id == detectionId) encodedLine
                                else raw
                            } catch (_: Exception) { raw }
                        }
                        val tmp = File(session.sessionDir, "detections.jsonl.tmp")
                        tmp.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")
                        tmp.renameTo(detFile)
                    }

                    // verifications.jsonl: granular appenden
                    val veriFile = File(session.sessionDir, "verifications.jsonl")
                    val now = System.currentTimeMillis()
                    if (current.count != count) veriFile.appendText(
                        Json.encodeToString(VerificationEvent(detectionId, current.verificationStatus,
                            verifiedAtMs = now, actionType = VerificationEvent.ACTION_COUNT_CHANGED)) + "\n")
                    if (current.atlasCode != atlasCode) veriFile.appendText(
                        Json.encodeToString(VerificationEvent(detectionId, current.verificationStatus,
                            verifiedAtMs = now, actionType = VerificationEvent.ACTION_ATLAS_SET)) + "\n")
                    if (current.note != note) veriFile.appendText(
                        Json.encodeToString(VerificationEvent(detectionId, current.verificationStatus,
                            verifiedAtMs = now, actionType = VerificationEvent.ACTION_NOTE_ADDED)) + "\n")
                    // T69: Memo-Events
                    if (current.voiceMemoFile == null && voiceMemoFile != null) {
                        veriFile.appendText(Json.encodeToString(VerificationEvent(
                            detectionId, current.verificationStatus,
                            verifiedAtMs = now,
                            actionType = VerificationEvent.ACTION_MEMO_RECORDED,
                            newValue = voiceMemoFile)) + "\n")
                    }
                    if (current.voiceMemoFile != null && voiceMemoFile == null) {
                        getMemoTargetFile(detectionId, session.sessionDir)?.takeIf { it.exists() }?.delete()
                        veriFile.appendText(Json.encodeToString(VerificationEvent(
                            detectionId, current.verificationStatus,
                            verifiedAtMs = now,
                            actionType = VerificationEvent.ACTION_MEMO_DELETED)) + "\n")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            detections = previousDetections,
                            saveErrorMessage = "Speichern fehlgeschlagen"
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

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
        // T69: Voice-Memo Cleanup
        voiceMemoRecorder.release()
        memoAudioPlayer.release()
    }
}
