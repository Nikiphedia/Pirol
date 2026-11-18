package ch.etasystems.pirol.ui.reference

import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.data.repository.ReferenceEntry

/**
 * UI-State fuer den ReferenceScreen.
 * Zwei Ebenen: Artenliste → Aufnahmen einer Art.
 */
data class ReferenceUiState(
    val speciesList: List<String> = emptyList(),           // Sortierte Artenliste (scientificName)
    val selectedSpecies: String? = null,                   // Aktuell ausgewaehlte Art
    val recordings: List<ReferenceEntry> = emptyList(),    // Aufnahmen der ausgewaehlten Art
    val totalReferences: Int = 0,
    val playbackState: AudioPlayer.PlaybackState = AudioPlayer.PlaybackState.IDLE,
    val playingEntryId: String? = null,                    // ID der gerade spielenden Referenz
    // T123: Import
    val pendingImportUri: android.net.Uri? = null,         // gewaehlte Datei, wartet auf Artwahl
    val pendingImportName: String? = null,
    val speciesSearchResults: List<Pair<String, String>> = emptyList(),  // (wiss. Name mit Leerzeichen, Anzeigename)
    val isImporting: Boolean = false,
    val message: String? = null                            // Snackbar/Hinweis
)
