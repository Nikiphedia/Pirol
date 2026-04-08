package ch.etasystems.pirol.ui.analysis

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.audio.dsp.MelSpectrogram
import ch.etasystems.pirol.data.repository.ReferenceEntry
import ch.etasystems.pirol.data.repository.ReferenceRepository
import ch.etasystems.pirol.data.repository.SessionManager
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * ViewModel fuer den Analyse-Tab.
 * Laedt Sessions, oeffnet Session-Detail, navigiert Audio-Chunks,
 * rendert Sonogramme und persistiert nachtraegliche Verifikationen.
 * Vergleichs-Modus: Detektion vs Referenz (Dual-Sonogramm).
 */
class AnalysisViewModel(
    private val sessionManager: SessionManager,
    private val audioPlayer: AudioPlayer,
    private val referenceRepository: ReferenceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()

    init {
        loadSessions()
        // Playback-State beobachten
        viewModelScope.launch {
            audioPlayer.state.collect { state ->
                _uiState.update { it.copy(playbackState = state) }
                if (state == AudioPlayer.PlaybackState.IDLE) {
                    _uiState.update { it.copy(playingChunkIndex = null, playingSource = null) }
                }
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
                SessionSummary(
                    sessionDir = dir,
                    metadata = meta,
                    detectionCount = detections.size,
                    verifiedCount = detections.count {
                        it.verificationStatus != VerificationStatus.UNVERIFIED
                    }
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
            val chunks = sessionManager.getAudioChunks(summary.sessionDir)
            _uiState.update {
                it.copy(
                    selectedSession = summary,
                    detections = detections,
                    audioChunks = chunks,
                    currentChunkIndex = 0
                )
            }
            // Erstes Chunk-Sonogramm generieren
            if (chunks.isNotEmpty()) {
                renderChunkSonogram(0)
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
                audioChunks = emptyList(),
                spectrogramState = SpectrogramState()
            )
        }
    }

    /** Chunk-Navigation: Vorwaerts/Rueckwaerts */
    fun navigateChunk(delta: Int) {
        val state = _uiState.value
        val newIndex = (state.currentChunkIndex + delta)
            .coerceIn(0, (state.audioChunks.size - 1).coerceAtLeast(0))
        if (newIndex != state.currentChunkIndex) {
            _uiState.update { it.copy(currentChunkIndex = newIndex) }
            renderChunkSonogram(newIndex)
        }
    }

    /** Audio-Chunk abspielen */
    fun playChunk(chunkIndex: Int) {
        val chunks = _uiState.value.audioChunks
        if (chunkIndex !in chunks.indices) return
        audioPlayer.play(chunks[chunkIndex])
        _uiState.update { it.copy(playingChunkIndex = chunkIndex) }
    }

    /** Aktuellen Chunk abspielen */
    fun playCurrentChunk() {
        playChunk(_uiState.value.currentChunkIndex)
    }

    /** Zur Detektion springen: Chunk mit ~5s Vorlauf ansteuern, Sonogramm rendern + abspielen (T39) */
    fun jumpToDetection(detection: DetectionResult) {
        val session = _uiState.value.selectedSession ?: return
        val startMs = try {
            java.time.Instant.parse(session.metadata.startedAt).toEpochMilli()
        } catch (_: Exception) { return }

        val detectionOffsetMs = detection.timestampMs - startMs
        val chunkIndex = (detectionOffsetMs / CHUNK_DURATION_MS).toInt()

        // 2 Chunks Vorlauf = 6s (naechste Annaeherung an 5s bei 3s-Chunks)
        val vorlaufChunks = 2
        val targetChunk = (chunkIndex - vorlaufChunks).coerceAtLeast(0)
            .coerceAtMost((_uiState.value.audioChunks.size - 1).coerceAtLeast(0))

        _uiState.update { it.copy(currentChunkIndex = targetChunk, scrollToTop = true) }
        renderChunkSonogram(targetChunk)
        playChunk(targetChunk)
    }

    /** Scroll-to-Top Flag zuruecksetzen (nach consume durch UI) */
    fun consumeScrollToTop() {
        _uiState.update { it.copy(scrollToTop = false) }
    }

    /** Playback stoppen */
    fun stopPlayback() {
        audioPlayer.stop()
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
        val startMs = try {
            java.time.Instant.parse(session.metadata.startedAt).toEpochMilli()
        } catch (_: Exception) { return }
        val chunkIndex = ((detection.timestampMs - startMs) / CHUNK_DURATION_MS).toInt()
            .coerceIn(0, (state.audioChunks.size - 1).coerceAtLeast(0))

        _uiState.update {
            it.copy(
                isCompareMode = true,
                compareDetection = detection,
                compareDetectionChunkIndex = chunkIndex,
                referenceEntries = references,
                selectedReference = references.firstOrNull()
            )
        }

        // Detektions-Sonogramm rendern
        renderDetectionSonogram(chunkIndex)

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

    /** Detektions-Audio abspielen */
    fun playDetection() {
        val state = _uiState.value
        val chunkIndex = state.compareDetectionChunkIndex ?: return
        if (chunkIndex !in state.audioChunks.indices) return
        audioPlayer.play(state.audioChunks[chunkIndex])
        _uiState.update { it.copy(playingSource = PlayingSource.DETECTION) }
    }

    /** Referenz-Audio abspielen */
    fun playReference() {
        val entry = _uiState.value.selectedReference ?: return
        val wavFile = referenceRepository.getWavFile(entry) ?: return
        audioPlayer.play(wavFile)
        _uiState.update { it.copy(playingSource = PlayingSource.REFERENCE) }
    }

    /** Detektions-Sonogramm rendern */
    private fun renderDetectionSonogram(chunkIndex: Int) {
        val chunks = _uiState.value.audioChunks
        if (chunkIndex !in chunks.indices) return
        viewModelScope.launch(Dispatchers.Default) {
            val samples = readWavSamples(chunks[chunkIndex]) ?: return@launch
            val state = SpectrogramState(maxFrames = 2048)
            val mel = MelSpectrogram(sampleRate = 48000)
            val frames = mel.process(samples)
            if (frames.isNotEmpty()) state.appendFrames(frames)
            _uiState.update { it.copy(detectionSpectrogramState = state) }
        }
    }

    /** Referenz-Sonogramm rendern */
    private fun renderReferenceSonogram(entry: ReferenceEntry) {
        val wavFile = referenceRepository.getWavFile(entry) ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val samples = readWavSamples(wavFile) ?: return@launch
            val state = SpectrogramState(maxFrames = 2048)
            val mel = MelSpectrogram(sampleRate = 48000)
            val frames = mel.process(samples)
            if (frames.isNotEmpty()) state.appendFrames(frames)
            _uiState.update { it.copy(referenceSpectrogramState = state) }
        }
    }

    // ── Bestehende Methoden ─────────────────────────────────────────

    /**
     * Sonogramm fuer einen WAV-Chunk rendern.
     * Liest WAV, konvertiert zu Samples, prozessiert durch MelSpectrogram.
     */
    private fun renderChunkSonogram(chunkIndex: Int) {
        val chunks = _uiState.value.audioChunks
        if (chunkIndex !in chunks.indices) return

        viewModelScope.launch(Dispatchers.Default) {
            val wavFile = chunks[chunkIndex]
            val samples = readWavSamples(wavFile) ?: return@launch

            // MelSpectrogram auf gesamten Chunk anwenden
            val newSpectrogramState = SpectrogramState(maxFrames = 2048)
            val mel = MelSpectrogram(sampleRate = 48000)
            val frames = mel.process(samples)
            if (frames.isNotEmpty()) {
                newSpectrogramState.appendFrames(frames)
            }

            _uiState.update { it.copy(spectrogramState = newSpectrogramState) }
        }
    }

    /**
     * Liest WAV-Datei und gibt ShortArray zurueck.
     * Ueberspringt den 44-Byte RIFF-Header.
     */
    private fun readWavSamples(wavFile: File): ShortArray? {
        try {
            val bytes = wavFile.readBytes()
            if (bytes.size < 44) return null
            // RIFF Header = 44 Bytes, danach 16-bit PCM LE Samples
            val dataSize = bytes.size - 44
            val numSamples = dataSize / 2
            val samples = ShortArray(numSamples)
            val buf = java.nio.ByteBuffer.wrap(bytes, 44, dataSize)
            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                samples[i] = buf.short
            }
            return samples
        } catch (e: Exception) {
            Log.e("AnalysisVM", "WAV lesen fehlgeschlagen: ${wavFile.name}", e)
            return null
        }
    }

    /** Verifikation einer Detektion (nachtraeglich in Analyse) */
    fun verifyDetection(
        detectionId: String,
        status: VerificationStatus,
        correctedSpecies: String? = null
    ) {
        val session = _uiState.value.selectedSession ?: return
        // In-Memory aktualisieren
        val updated = _uiState.value.detections.map { det ->
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
            val veriFile = File(session.sessionDir, "verifications.jsonl")
            val event = VerificationEvent(
                detectionId = detectionId,
                status = status,
                correctedSpecies = correctedSpecies,
                verifiedAtMs = System.currentTimeMillis()
            )
            veriFile.appendText(Json.encodeToString(event) + "\n")
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
    }
}
