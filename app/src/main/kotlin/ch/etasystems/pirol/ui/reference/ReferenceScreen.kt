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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.data.api.XenoCantoRecording
import ch.etasystems.pirol.data.repository.ReferenceEntry
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Referenzbibliothek — Zwei-Ebenen Navigation:
 * Ebene 1: Artenliste mit Anzahl Aufnahmen
 * Ebene 2: Aufnahmen einer Art mit Play/Stop
 * Plus: Xeno-Canto Such-Dialog (T50)
 */
@Composable
fun ReferenceScreen(
    viewModel: ReferenceViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Xeno-Canto Such-Dialog
    if (uiState.showXenoCantoSearch) {
        XenoCantoSearchDialog(
            uiState = uiState,
            onDismiss = viewModel::hideXenoCantoSearch,
            onQueryChange = viewModel::updateSearchQuery,
            onSearch = { viewModel.searchXenoCanto(uiState.searchQuery, uiState.qualityFilter) },
            onQualityChange = viewModel::setQualityFilter,
            onPreview = viewModel::playXenoCantoPreview,
            onStopPreview = viewModel::stopPlayback,
            onDownload = viewModel::downloadFromXenoCanto,
            onLoadMore = viewModel::loadMoreResults
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        AnimatedContent(
            targetState = uiState.selectedSpecies,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it } + fadeIn())
                        .togetherWith(slideOutHorizontally { -it } + fadeOut())
                } else {
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
                    hasApiKey = uiState.hasApiKey,
                    recordings = viewModel::selectSpecies,
                    getCommonName = { scientificName ->
                        findCommonName(scientificName, viewModel)
                    },
                    getCount = { scientificName ->
                        viewModel.getSpeciesCount(scientificName)
                    },
                    onXenoCantoSearch = viewModel::showXenoCantoSearch
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
    val entry = viewModel.uiState.value.speciesList.find { it == scientificName }
    if (entry != null) {
        // commonName wird in den Recordings mitgeliefert
    }
    return scientificName.replace('_', ' ')
}

// =============================================================================
// Ebene 1: Artenliste
// =============================================================================

@Composable
private fun SpeciesListView(
    speciesList: List<String>,
    totalReferences: Int,
    hasApiKey: Boolean,
    recordings: (String) -> Unit,
    getCommonName: (String) -> String,
    getCount: (String) -> Int,
    onXenoCantoSearch: () -> Unit
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

        // Xeno-Canto Button (nur mit API-Key)
        if (hasApiKey) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onXenoCantoSearch,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.TravelExplore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Xeno-Canto durchsuchen")
            }
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
                Text("Referenz '${entry.audioFileName}' wirklich loeschen?")
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
            // Zeile 1: Dateiname + Quelle + Datum
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.audioFileName
                        .removeSuffix(".wav")
                        .removeSuffix(".mp3"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (entry.source == "xeno-canto" && entry.xenoCantoId != null) {
                    Text(
                        text = entry.xenoCantoId,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    val confidencePercent = (entry.confidence * 100).toInt()
                    Text(
                        text = "$confidencePercent%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = recordedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Zeile 2: Quelle (Session oder Recordist)
            if (entry.source == "xeno-canto" && entry.recordist != null) {
                Text(
                    text = "Aufnehmer: ${entry.recordist}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (entry.sourceSessionId.isNotBlank()) {
                Text(
                    text = "Session: ${entry.sourceSessionId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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

// =============================================================================
// Xeno-Canto Such-Dialog (T50)
// =============================================================================

@Composable
private fun XenoCantoSearchDialog(
    uiState: ReferenceUiState,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onQualityChange: (String) -> Unit,
    onPreview: (XenoCantoRecording) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: (XenoCantoRecording) -> Unit,
    onLoadMore: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Xeno-Canto Suche")
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Schliessen")
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Suchfeld
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onQueryChange,
                    label = { Text("Artname (z.B. Turdus merula)") },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Qualitaetsfilter
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Alle", "A", "A-B").forEach { quality ->
                        FilterChip(
                            selected = uiState.qualityFilter == quality,
                            onClick = { onQualityChange(quality) },
                            label = { Text(quality) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Such-Button
                Button(
                    onClick = onSearch,
                    enabled = uiState.searchQuery.isNotBlank() && !uiState.isSearching,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isSearching && uiState.xenoCantoResults.isEmpty()) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Suchen")
                }

                // Fehlermeldung
                uiState.searchError?.let { error ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Ergebnisliste
                if (uiState.xenoCantoResults.isNotEmpty()) {
                    Text(
                        text = "${uiState.xenoCantoResults.size} Aufnahmen",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.height(400.dp)
                    ) {
                        items(
                            items = uiState.xenoCantoResults,
                            key = { it.id }
                        ) { recording ->
                            XenoCantoResultCard(
                                recording = recording,
                                isPreviewing = uiState.previewingId == recording.id &&
                                        uiState.playbackState == AudioPlayer.PlaybackState.PLAYING,
                                isDownloading = uiState.downloadingIds.contains(recording.id),
                                isDownloaded = uiState.downloadedIds.contains(recording.id),
                                onPreview = { onPreview(recording) },
                                onStopPreview = onStopPreview,
                                onDownload = { onDownload(recording) }
                            )
                        }

                        // "Mehr laden" Button
                        if (uiState.hasMoreResults) {
                            item {
                                OutlinedButton(
                                    onClick = onLoadMore,
                                    enabled = !uiState.isSearching,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (uiState.isSearching) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text("Mehr laden")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/**
 * Karte fuer ein einzelnes Xeno-Canto Suchergebnis.
 */
@Composable
private fun XenoCantoResultCard(
    recording: XenoCantoRecording,
    isPreviewing: Boolean,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    onPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Zeile 1: Art + Typ
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${recording.gen} ${recording.sp}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.weight(1f)
                )
                if (recording.type.isNotBlank()) {
                    Text(
                        text = recording.type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Zeile 2: Ort
            if (recording.loc.isNotBlank()) {
                Text(
                    text = "${recording.loc}, ${recording.cnt}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Zeile 3: Qualitaet, Dauer, Aufnehmer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Q: ${recording.q}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = recording.length,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = recording.rec,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            // Zeile 4: Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Preview-Button
                IconButton(
                    onClick = if (isPreviewing) onStopPreview else onPreview,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isPreviewing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (isPreviewing) "Stopp" else "Anhoeren",
                        tint = if (isPreviewing)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Download-Button
                IconButton(
                    onClick = onDownload,
                    enabled = !isDownloading && !isDownloaded,
                    modifier = Modifier.size(36.dp)
                ) {
                    when {
                        isDownloading -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        isDownloaded -> Icon(
                            imageVector = Icons.Filled.DownloadDone,
                            contentDescription = "Gespeichert",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        else -> Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Herunterladen",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
