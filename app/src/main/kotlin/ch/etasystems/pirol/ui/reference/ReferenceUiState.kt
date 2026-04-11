package ch.etasystems.pirol.ui.reference

import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.data.api.XenoCantoRecording
import ch.etasystems.pirol.data.repository.ReferenceEntry

/**
 * UI-State fuer den ReferenceScreen.
 * Zwei Ebenen: Artenliste → Aufnahmen einer Art.
 * Xeno-Canto-Suche als dritte Ebene (Dialog/Sheet).
 */
data class ReferenceUiState(
    // Lokale Referenzen
    val speciesList: List<String> = emptyList(),           // Sortierte Artenliste (scientificName)
    val selectedSpecies: String? = null,                   // Aktuell ausgewaehlte Art
    val recordings: List<ReferenceEntry> = emptyList(),    // Aufnahmen der ausgewaehlten Art
    val totalReferences: Int = 0,
    val playbackState: AudioPlayer.PlaybackState = AudioPlayer.PlaybackState.IDLE,
    val playingEntryId: String? = null,                    // ID der gerade spielenden Referenz

    // Xeno-Canto Suche
    val showXenoCantoSearch: Boolean = false,              // Such-Dialog sichtbar
    val searchQuery: String = "",                          // Aktueller Suchbegriff
    val qualityFilter: String = "A-B",                     // Qualitaetsfilter
    val xenoCantoResults: List<XenoCantoRecording> = emptyList(),
    val isSearching: Boolean = false,                      // Suche laeuft
    val hasMoreResults: Boolean = false,                   // Weitere Seiten vorhanden
    val downloadingIds: Set<String> = emptySet(),          // XC-IDs die gerade laden
    val downloadedIds: Set<String> = emptySet(),           // XC-IDs die bereits gespeichert sind
    val previewingId: String? = null,                      // XC-ID die gerade gestreamt wird
    val searchError: String? = null,                       // Fehlermeldung bei Suche
    val hasApiKey: Boolean = false                         // Xeno-Canto API-Key vorhanden
)
