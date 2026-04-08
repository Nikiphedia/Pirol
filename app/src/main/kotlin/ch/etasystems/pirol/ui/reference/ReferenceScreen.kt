package ch.etasystems.pirol.ui.reference

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.data.repository.ReferenceEntry
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Referenzbibliothek — Zwei-Ebenen Navigation:
 * Ebene 1: Artenliste mit Anzahl Aufnahmen
 * Ebene 2: Aufnahmen einer Art mit Play/Stop
 */
@Composable
fun ReferenceScreen(
    viewModel: ReferenceViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        AnimatedContent(
            targetState = uiState.selectedSpecies,
            transitionSpec = {
                if (targetState != null) {
                    // Vorwaerts: reinsliden von rechts
                    (slideInHorizontally { it } + fadeIn())
                        .togetherWith(slideOutHorizontally { -it } + fadeOut())
                } else {
                    // Zurueck: reinsliden von links
                    (slideInHorizontally { -it } + fadeIn())
                        .togetherWith(slideOutHorizontally { it } + fadeOut())
                }
            },
            label = "referenceNavigation"
        ) { selectedSpecies ->
            if (selectedSpecies == null) {
                SpeciesListView(
                    speciesList = uiState.speciesList,
                    totalReferences = uiState.totalReferences,
                    recordings = viewModel::selectSpecies,
                    // commonName aus den Entries holen
                    getCommonName = { scientificName ->
                        viewModel.uiState.value.let {
                            // Aus Repository-Cache den commonName suchen
                            findCommonName(scientificName, viewModel)
                        }
                    },
                    getCount = { scientificName ->
                        viewModel.uiState.value.let {
                            getSpeciesCount(scientificName, viewModel)
                        }
                    }
                )
            } else {
                RecordingsListView(
                    speciesName = selectedSpecies,
                    commonName = findCommonName(selectedSpecies, viewModel),
                    recordings = uiState.recordings,
                    playbackState = uiState.playbackState,
                    playingEntryId = uiState.playingEntryId,
                    onPlay = viewModel::playRecording,
                    onStop = viewModel::stopPlayback,
                    onBack = viewModel::clearSelection,
                    onDelete = viewModel::deleteReference
                )
            }
        }
    }
}

/** commonName fuer eine Art aus dem Repository holen */
private fun findCommonName(scientificName: String, viewModel: ReferenceViewModel): String {
    // Aus dem ViewModel/Repository die erste Referenz dieser Art holen
    val entry = viewModel.uiState.value.speciesList.find { it == scientificName }
    if (entry != null) {
        // Wir brauchen den commonName — hole ihn aus den recordings
        // Da wir keinen direkten Zugriff auf alle Entries haben, nutzen wir selectSpecies temporaer nicht
        // Stattdessen: Der commonName wird in den Recordings mitgeliefert
    }
    return scientificName.replace('_', ' ')
}

/** Anzahl Referenzen fuer eine Art */
private fun getSpeciesCount(scientificName: String, viewModel: ReferenceViewModel): Int {
    return viewModel.getSpeciesCount(scientificName)
}

// =============================================================================
// Ebene 1: Artenliste
// =============================================================================

@Composable
private fun SpeciesListView(
    speciesList: List<String>,
    totalReferences: Int,
    recordings: (String) -> Unit,
    getCommonName: (String) -> String,
    getCount: (String) -> Int
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Referenzbibliothek",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$totalReferences Ref.",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (speciesList.isEmpty()) {
            // Leerer Zustand
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Noch keine Referenzen gespeichert",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Bestaetigen Sie Detektionen im Live-Tab\nund speichern Sie diese als Referenz",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            // Artenliste
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(
                    items = speciesList,
                    key = { it }
                ) { scientificName ->
                    val commonName = getCommonName(scientificName)
                    SpeciesListItem(
                        scientificName = scientificName,
                        commonName = commonName,
                        onClick = { recordings(scientificName) }
                    )
                }
            }
        }
    }
}

/**
 * Listeneintrag fuer eine Art in der Referenzbibliothek.
 */
@Composable
private fun SpeciesListItem(
    scientificName: String,
    commonName: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = commonName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = scientificName.replace('_', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// =============================================================================
// Ebene 2: Aufnahmen einer Art
// =============================================================================

@Composable
private fun RecordingsListView(
    speciesName: String,
    commonName: String,
    recordings: List<ReferenceEntry>,
    playbackState: AudioPlayer.PlaybackState,
    playingEntryId: String?,
    onPlay: (ReferenceEntry) -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
    onDelete: (ReferenceEntry) -> Unit
) {
    var entryToDelete by remember { mutableStateOf<ReferenceEntry?>(null) }

    // Bestaetigungsdialog
    entryToDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = { Text("Referenz loeschen?") },
            text = {
                Text("Referenz '${entry.wavFileName}' wirklich loeschen?")
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(entry)
                    entryToDelete = null
                }) {
                    Text("Loeschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header mit Zurueck-Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Zurueck"
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = commonName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = speciesName.replace('_', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Aufnahmenliste
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(
                items = recordings,
                key = { it.id }
            ) { entry ->
                val isPlaying = playingEntryId == entry.id &&
                        playbackState == AudioPlayer.PlaybackState.PLAYING
                RecordingCard(
                    entry = entry,
                    isPlaying = isPlaying,
                    onPlay = { onPlay(entry) },
                    onStop = onStop,
                    onDelete = { entryToDelete = entry }
                )
            }
        }
    }
}

/**
 * Karte fuer eine einzelne Referenz-Aufnahme.
 */
@Composable
private fun RecordingCard(
    entry: ReferenceEntry,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit = {}
) {
    val dateFormat = remember {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    }
    val recordedDate = remember(entry.recordedAtMs) {
        dateFormat.format(Date(entry.recordedAtMs))
    }
    val confidencePercent = (entry.confidence * 100).toInt()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Zeile 1: Dateiname + Konfidenz + Datum
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.wavFileName.removeSuffix(".wav"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$confidencePercent%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = recordedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Zeile 2: Session-ID
            Text(
                text = "Session: ${entry.sourceSessionId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Zeile 3: GPS-Koordinaten (falls vorhanden)
            if (entry.latitude != null && entry.longitude != null) {
                Text(
                    text = String.format(
                        "%.4f\u00B0N, %.4f\u00B0E",
                        entry.latitude, entry.longitude
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Zeile 4: Play/Stop + Delete Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isPlaying) {
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Stopp",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Wird abgespielt\u2026",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        IconButton(
                            onClick = onPlay,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Abspielen",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Loeschen",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
