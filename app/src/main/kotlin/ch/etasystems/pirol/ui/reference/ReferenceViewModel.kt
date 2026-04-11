package ch.etasystems.pirol.ui.reference

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.data.api.XenoCantoClient
import ch.etasystems.pirol.data.api.XenoCantoRecording
import ch.etasystems.pirol.data.repository.ReferenceDownloader
import ch.etasystems.pirol.data.repository.ReferenceEntry
import ch.etasystems.pirol.data.repository.ReferenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel fuer den ReferenceScreen.
 * Verwaltet Artenliste, Aufnahmen-Navigation, Audio-Playback und Xeno-Canto-Suche.
 */
class ReferenceViewModel(
    private val repository: ReferenceRepository,
    private val audioPlayer: AudioPlayer,
    private val xenoCantoClient: XenoCantoClient,
    private val referenceDownloader: ReferenceDownloader
) : ViewModel() {

    companion object {
        private const val TAG = "ReferenceVM"
        private const val XC_PAGE_SIZE = 50
    }

    private val _uiState = MutableStateFlow(ReferenceUiState())
    val uiState: StateFlow<ReferenceUiState> = _uiState.asStateFlow()

    // Paging-State fuer Xeno-Canto
    private var currentPage = 1
    private var currentXcQuery = ""

    init {
        viewModelScope.launch {
            repository.loadIndex()
            refreshState()
        }
        // Playback-State beobachten
        viewModelScope.launch {
            audioPlayer.state.collect { state ->
                _uiState.update { it.copy(playbackState = state) }
                if (state == AudioPlayer.PlaybackState.IDLE) {
                    _uiState.update { it.copy(playingEntryId = null, previewingId = null) }
                }
            }
        }
        // API-Key Verfuegbarkeit pruefen
        _uiState.update { it.copy(hasApiKey = xenoCantoClient.hasApiKey) }
    }

    // =========================================================================
    // Lokale Referenzen
    // =========================================================================

    /** Art auswaehlen — zeigt Aufnahmen dieser Art */
    fun selectSpecies(scientificName: String) {
        val recordings = repository.getBySpecies(scientificName)
        _uiState.update {
            it.copy(selectedSpecies = scientificName, recordings = recordings)
        }
    }

    /** Zurueck zur Artenliste */
    fun clearSelection() {
        audioPlayer.stop()
        _uiState.update { it.copy(selectedSpecies = null, recordings = emptyList()) }
    }

    /** Referenz-Aufnahme abspielen */
    fun playRecording(entry: ReferenceEntry) {
        val audioFile = repository.getAudioFile(entry) ?: return
        audioPlayer.play(audioFile)
        _uiState.update { it.copy(playingEntryId = entry.id) }
    }

    /** Playback stoppen */
    fun stopPlayback() {
        audioPlayer.stop()
    }

    /** Anzahl Referenzen fuer eine Art aus dem Repository zaehlen */
    fun getSpeciesCount(scientificName: String): Int {
        return repository.getBySpecies(scientificName).size
    }

    /** Referenz-Aufnahme loeschen */
    fun deleteReference(entry: ReferenceEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteReference(entry)
            // Recordings der aktuellen Art neu laden
            val species = _uiState.value.selectedSpecies
            if (species != null) {
                val updated = repository.getBySpecies(species)
                _uiState.update { it.copy(recordings = updated) }
                // Zurueck zur Artenliste falls keine Recordings mehr
                if (updated.isEmpty()) {
                    _uiState.update { it.copy(selectedSpecies = null, recordings = emptyList()) }
                }
            }
            refreshState()
        }
    }

    /** State aus Repository aktualisieren */
    fun refreshState() {
        _uiState.update {
            it.copy(
                speciesList = repository.getSpecies(),
                totalReferences = repository.size,
                hasApiKey = xenoCantoClient.hasApiKey
            )
        }
    }

    // =========================================================================
    // Xeno-Canto Suche
    // =========================================================================

    /** Such-Dialog oeffnen */
    fun showXenoCantoSearch() {
        _uiState.update { it.copy(showXenoCantoSearch = true, searchError = null) }
    }

    /** Such-Dialog schliessen */
    fun hideXenoCantoSearch() {
        audioPlayer.stop()
        _uiState.update {
            it.copy(
                showXenoCantoSearch = false,
                xenoCantoResults = emptyList(),
                searchQuery = "",
                isSearching = false,
                downloadingIds = emptySet(),
                downloadedIds = emptySet(),
                previewingId = null,
                searchError = null
            )
        }
    }

    /** Suchbegriff aktualisieren */
    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    /** Qualitaetsfilter setzen */
    fun setQualityFilter(quality: String) {
        _uiState.update { it.copy(qualityFilter = quality) }
    }

    /** Xeno-Canto-Suche starten */
    fun searchXenoCanto(query: String, quality: String? = "A") {
        if (query.isBlank()) return
        currentPage = 1
        currentXcQuery = query

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSearching = true,
                    xenoCantoResults = emptyList(),
                    searchError = null,
                    downloadedIds = emptySet()
                )
            }
            try {
                val results = xenoCantoClient.searchBySpecies(
                    scientificName = query,
                    quality = quality,
                    page = 1,
                    perPage = XC_PAGE_SIZE
                )
                _uiState.update {
                    it.copy(
                        xenoCantoResults = results,
                        isSearching = false,
                        hasMoreResults = results.size >= XC_PAGE_SIZE
                    )
                }
                Log.i(TAG, "XC-Suche: ${results.size} Ergebnisse fuer '$query'")
            } catch (e: Exception) {
                Log.e(TAG, "XC-Suche fehlgeschlagen", e)
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchError = e.message ?: "Suche fehlgeschlagen"
                    )
                }
            }
        }
    }

    /** Naechste Seite laden */
    fun loadMoreResults() {
        if (_uiState.value.isSearching) return
        currentPage++

        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            try {
                // Qualitaetsfilter fuer API aufbereiten
                val qualityParam = when (_uiState.value.qualityFilter) {
                    "A" -> "A"
                    "A-B" -> ">:B"
                    else -> null
                }
                val results = xenoCantoClient.searchBySpecies(
                    scientificName = currentXcQuery,
                    quality = qualityParam,
                    page = currentPage,
                    perPage = XC_PAGE_SIZE
                )
                _uiState.update {
                    it.copy(
                        xenoCantoResults = it.xenoCantoResults + results,
                        isSearching = false,
                        hasMoreResults = results.size >= XC_PAGE_SIZE
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "XC-Mehr laden fehlgeschlagen", e)
                currentPage--
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchError = e.message ?: "Laden fehlgeschlagen"
                    )
                }
            }
        }
    }

    /** Xeno-Canto-Aufnahme streamen (Preview) */
    fun playXenoCantoPreview(recording: XenoCantoRecording) {
        audioPlayer.stop()
        audioPlayer.playFromUrl(recording.audioUrl)
        _uiState.update { it.copy(previewingId = recording.id) }
    }

    /** Xeno-Canto-Aufnahme herunterladen und lokal speichern */
    fun downloadFromXenoCanto(recording: XenoCantoRecording) {
        val xcId = recording.id
        if (_uiState.value.downloadingIds.contains(xcId)) return

        viewModelScope.launch {
            _uiState.update { it.copy(downloadingIds = it.downloadingIds + xcId) }

            val result = referenceDownloader.downloadRecording(recording)

            result.onSuccess {
                _uiState.update {
                    it.copy(
                        downloadingIds = it.downloadingIds - xcId,
                        downloadedIds = it.downloadedIds + xcId
                    )
                }
                refreshState()
                Log.i(TAG, "XC-Download OK: XC$xcId")
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        downloadingIds = it.downloadingIds - xcId,
                        searchError = "Download fehlgeschlagen: ${error.message}"
                    )
                }
                Log.e(TAG, "XC-Download fehlgeschlagen: XC$xcId", error)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
    }
}
