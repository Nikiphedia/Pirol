package ch.etasystems.pirol.ui.reference

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.data.repository.ReferenceEntry
import ch.etasystems.pirol.data.repository.ReferenceRepository
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ch.etasystems.pirol.audio.AudioFileDecoder
import ch.etasystems.pirol.ml.SpeciesNameResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.AppStrings

/**
 * ViewModel fuer den ReferenceScreen.
 * Verwaltet Artenliste, Aufnahmen-Navigation und Audio-Playback.
 */
class ReferenceViewModel(
    private val repository: ReferenceRepository,
    private val audioPlayer: AudioPlayer,
    private val speciesNameResolver: SpeciesNameResolver,
    private val context: Context,
    private val playback: ch.etasystems.pirol.audio.PlaybackController  // T173
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
            }
        }
        // T173: laufende Referenz aus dem Schluessel der zentralen Wiedergabe ableiten
        viewModelScope.launch {
            playback.playingKey.collect { key ->
                val id = key?.takeIf { it.startsWith("ref:") }?.removePrefix("ref:")
                _uiState.update { it.copy(playingEntryId = id) }
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
        // T173: Start/Stopp-Umschalter; andere Wiedergabe wird verdraengt
        playback.toggle(ch.etasystems.pirol.audio.PlaybackKeys.reference(entry.id)) { audioPlayer.play(wavFile) }
    }

    /** Playback stoppen */
    fun stopPlayback() {
        playback.stop()
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

    /** T123: Index neu laden (beim Oeffnen des Tabs), damit neu gespeicherte Referenzen sofort erscheinen. */
    fun reload() {
        viewModelScope.launch {
            repository.loadIndex()
            refreshState()
            _uiState.value.selectedSpecies?.let { selectSpecies(it) }
        }
    }

    /** T123: Datei per SAF gewaehlt -> Artwahl-Dialog oeffnen. */
    fun onImportPicked(uri: Uri) {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null }
        _uiState.update {
            it.copy(pendingImportUri = uri, pendingImportName = name ?: AppStrings.get(R.string.reference_audiodatei), speciesSearchResults = emptyList())
        }
    }

    fun cancelImport() {
        _uiState.update { it.copy(pendingImportUri = null, pendingImportName = null, speciesSearchResults = emptyList()) }
    }

    /** T123: Artensuche (Autocomplete) ueber SpeciesNameResolver. */
    fun searchSpecies(query: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val results = speciesNameResolver.searchSpecies(query.trim())
            _uiState.update { it.copy(speciesSearchResults = results) }
        }
    }

    /** T123: Gewaehlte Datei dekodieren und fuer die Art als Referenz speichern. */
    fun confirmImport(scientificName: String, commonName: String) {
        val uri = _uiState.value.pendingImportUri ?: return
        val name = _uiState.value.pendingImportName
        _uiState.update { it.copy(pendingImportUri = null, speciesSearchResults = emptyList(), isImporting = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val pcm = AudioFileDecoder.decode(context, uri, name)
            val entry = pcm?.let { repository.addImported(it, scientificName, commonName, name) }
            _uiState.update {
                it.copy(
                    isImporting = false,
                    pendingImportName = null,
                    message = if (entry != null) AppStrings.get(R.string.reference_referenz_importiert, commonName)
                    else AppStrings.get(R.string.reference_import_fehlgeschlagen_dateiformat_ni)
                )
            }
            refreshState()
            _uiState.value.selectedSpecies?.let { selectSpecies(it) }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
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
