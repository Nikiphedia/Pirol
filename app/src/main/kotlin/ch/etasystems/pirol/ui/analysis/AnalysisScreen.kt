package ch.etasystems.pirol.ui.analysis

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.ml.VerificationStatus
import ch.etasystems.pirol.ui.components.SessionCard
import ch.etasystems.pirol.ui.components.SpeciesCard
import ch.etasystems.pirol.ui.components.SpectrogramCanvas
import ch.etasystems.pirol.ui.components.SpectrogramPalette
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun AnalysisScreen(
    viewModel: AnalysisViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    // 3-Ebenen-Navigation: Liste → Detail → Vergleich
    val navLevel = when {
        state.isCompareMode -> 2
        state.selectedSession != null -> 1
        else -> 0
    }

    AnimatedContent(
        targetState = navLevel,
        transitionSpec = {
            if (targetState > initialState) {
                // Oeffnen: von rechts rein
                (slideInHorizontally { it } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it } + fadeOut())
            } else {
                // Schliessen: von links rein
                (slideInHorizontally { -it } + fadeIn()) togetherWith
                    (slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "analysis_nav"
    ) { level ->
        when (level) {
            2 -> CompareView(state = state, viewModel = viewModel)
            1 -> SessionDetailView(state = state, viewModel = viewModel)
            else -> SessionListView(state = state, viewModel = viewModel)
        }
    }
}

// ── Ebene 1: Session-Liste ──────────────────────────────────────

@Composable
private fun SessionListView(
    state: AnalysisUiState,
    viewModel: AnalysisViewModel
) {
    // Bestaetigungsdialog-State
    var sessionToDelete by remember { mutableStateOf<SessionSummary?>(null) }

    // Bestaetigungsdialog
    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Session loeschen?") },
            text = {
                Text(
                    "Session '${session.metadata.startedAt.take(10)}' " +
                        "mit ${session.detectionCount} Detektionen wirklich loeschen?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(session)
                    sessionToDelete = null
                }) {
                    Text("Loeschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Aufnahmen",
                style = MaterialTheme.typography.headlineSmall
            )
            if (state.sessions.isNotEmpty()) {
                Text(
                    text = "${state.sessions.size} Ses.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            state.sessions.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Noch keine Aufnahmen\nStarte eine Aufnahme im Live-Tab",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = state.sessions,
                        key = { it.metadata.sessionId }
                    ) { summary ->
                        SessionCard(
                            summary = summary,
                            onClick = { viewModel.openSession(summary) },
                            onDelete = { sessionToDelete = summary }
                        )
                    }
                }
            }
        }
    }
}

// ── Ebene 2: Session-Detail ─────────────────────────────────────

@Composable
private fun SessionDetailView(
    state: AnalysisUiState,
    viewModel: AnalysisViewModel
) {
    val session = state.selectedSession ?: return
    val context = LocalContext.current

    // Raven-Export Feedback
    LaunchedEffect(state.ravenExportMessage) {
        state.ravenExportMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeRavenExportMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Zurueck-Button + Session-Titel
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        ) {
            IconButton(onClick = { viewModel.closeSession() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurueck")
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = session.metadata.startedAt.take(10), // Kurzform Datum
                style = MaterialTheme.typography.titleMedium
            )
        }

        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Banner: alte Session ohne recording.wav (chunk_*.wav Format vor T46)
        if (state.recordingFile == null) {
            val audioDir = java.io.File(session.sessionDir, "audio")
            val hasOldChunks = audioDir.exists() &&
                audioDir.listFiles()?.any { it.name.startsWith("chunk_") } == true
            if (hasOldChunks) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Text(
                        text = "Diese Session wurde im alten Chunk-Format gespeichert. " +
                               "Audio-Wiedergabe nicht verfuegbar \u2014 Detektions-Daten sind weiterhin sichtbar.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Sonogramm + Aufnahme-Wiedergabe
        if (state.recordingFile != null) {
            // Play/Stop fuer gesamte Aufnahme (Chunk-Navigation entfernt in T46, kommt in T48)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { viewModel.exportRavenTable() },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("Raven (.txt)")
                }
                IconButton(onClick = {
                    if (state.playbackState == AudioPlayer.PlaybackState.PLAYING) {
                        viewModel.stopPlayback()
                    } else {
                        viewModel.playCurrentChunk()
                    }
                }) {
                    Icon(
                        imageVector = if (state.playbackState == AudioPlayer.PlaybackState.PLAYING)
                            Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = "Abspielen"
                    )
                }
            }

            // Sonogramm-Canvas
            SpectrogramCanvas(
                spectrogramState = state.spectrogramState,
                config = SpectrogramConfig.BIRDS,
                palette = SpectrogramPalette.MAGMA,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Detektionsliste
        Text(
            text = "Detektionen (${state.detections.size}):",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (state.detections.isEmpty()) {
            Text(
                text = "Keine Detektionen in dieser Session",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()

            // T39: Scroll-to-Top nach Jump
            LaunchedEffect(state.scrollToTop) {
                if (state.scrollToTop) {
                    listState.animateScrollToItem(0)
                    viewModel.consumeScrollToTop()
                }
            }

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(
                    items = state.detections,
                    key = { it.id }
                ) { detection ->
                    SpeciesCard(
                        detection = detection,
                        onConfirm = {
                            viewModel.verifyDetection(
                                detection.id,
                                VerificationStatus.CONFIRMED
                            )
                        },
                        onReject = {
                            viewModel.verifyDetection(
                                detection.id,
                                VerificationStatus.REJECTED
                            )
                        },
                        onCorrect = { correctedName ->
                            viewModel.verifyDetection(
                                detection.id,
                                VerificationStatus.CORRECTED,
                                correctedName
                            )
                        },
                        onCompare = { viewModel.openCompare(detection) },
                        onJumpToChunk = { viewModel.jumpToDetection(detection) },
                        onPlay = { viewModel.playDetection(detection) },
                        isPlayEnabled = state.recordingFile != null,
                        playTimeLabel = formatSecondsAsMinSec(detection.chunkStartSec)
                    )
                }
            }
        }
    }
}

// ── Ebene 3: Vergleichs-Ansicht (Detektion vs Referenz) ────────

@Composable
private fun CompareView(
    state: AnalysisUiState,
    viewModel: AnalysisViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Zurueck-Button + Titel
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        ) {
            IconButton(onClick = { viewModel.closeCompare() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurueck")
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Vergleich: ${state.compareDetection?.commonName ?: ""}",
                style = MaterialTheme.typography.titleMedium
            )
        }

        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))

        // ── DETEKTION ──
        Text(
            text = "DETEKTION",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))

        // Sonogramm Detektion
        SpectrogramCanvas(
            spectrogramState = state.detectionSpectrogramState,
            config = SpectrogramConfig.BIRDS,
            palette = SpectrogramPalette.MAGMA,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        )

        // Info + Play
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            val det = state.compareDetection
            if (det != null) {
                Text(
                    text = "${(det.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatSecondsAsMinSec(det.chunkStartSec),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = {
                if (state.playingSource == PlayingSource.DETECTION &&
                    state.playbackState == AudioPlayer.PlaybackState.PLAYING) {
                    viewModel.stopPlayback()
                } else {
                    viewModel.playDetection()
                }
            }) {
                Icon(
                    imageVector = if (state.playingSource == PlayingSource.DETECTION &&
                        state.playbackState == AudioPlayer.PlaybackState.PLAYING)
                        Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = "Detektion abspielen"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // ── REFERENZ ──
        Text(
            text = "REFERENZ",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (state.selectedReference != null) {
            // Sonogramm Referenz
            SpectrogramCanvas(
                spectrogramState = state.referenceSpectrogramState,
                config = SpectrogramConfig.BIRDS,
                palette = SpectrogramPalette.MAGMA,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )

            // Info + Play
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val ref = state.selectedReference
                Text(
                    text = "${(ref.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = ref.wavFileName,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    if (state.playingSource == PlayingSource.REFERENCE &&
                        state.playbackState == AudioPlayer.PlaybackState.PLAYING) {
                        viewModel.stopPlayback()
                    } else {
                        viewModel.playReference()
                    }
                }) {
                    Icon(
                        imageVector = if (state.playingSource == PlayingSource.REFERENCE &&
                            state.playbackState == AudioPlayer.PlaybackState.PLAYING)
                            Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = "Referenz abspielen"
                    )
                }
            }

            // Referenz-Auswahl (wenn mehrere vorhanden)
            if (state.referenceEntries.size > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Weitere Referenzen:",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    state.referenceEntries.forEach { ref ->
                        FilterChip(
                            selected = ref.id == state.selectedReference.id,
                            onClick = { viewModel.selectReference(ref) },
                            label = {
                                Text("${ref.wavFileName.take(7)} (${(ref.confidence * 100).toInt()}%)")
                            }
                        )
                    }
                }
            }
        } else {
            // Keine Referenzen vorhanden
            Text(
                text = "Keine Referenzen fuer diese Art gespeichert.\n" +
                    "Bestaetigen Sie Detektionen und speichern Sie diese als Referenz.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── Hilfsfunktionen ─────────────────────────────────────────────

/**
 * Wandelt Sekunden (Float) in "MM:SS" um.
 * Beispiel: 74.3f → "01:14"
 */
private fun formatSecondsAsMinSec(s: Float): String {
    val total = s.toInt()
    val mm = total / 60
    val ss = total % 60
    return String.format("%02d:%02d", mm, ss)
}
