package ch.etasystems.pirol.ui.reference

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.etasystems.pirol.audio.AudioPlayer
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
 * Verwaltet Artenliste, Aufnahmen-Navigation und Audio-Playback.
 */
class ReferenceViewModel(
    private val repository: ReferenceRepository,
    private val audioPlayer: AudioPlayer
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReferenceUiState())
    val uiState: StateFlow<ReferenceUiState> = _uiState.asStateFlow()

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
                    _uiState.update { it.copy(playingEntryId = null) }
                }
            }
        }
    }

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
        val wavFile = repository.getWavFile(entry) ?: return
        audioPlayer.play(wavFile)
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
                totalReferences = repository.size
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.stop()
    }
}
