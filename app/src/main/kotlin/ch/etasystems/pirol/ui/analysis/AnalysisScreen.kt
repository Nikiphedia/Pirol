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
import androidx.compose.material.icons.filled.AddCircle
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.defaultMinSize
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.ml.VerificationStatus
import ch.etasystems.pirol.ui.components.AddManualDetectionDialog
import ch.etasystems.pirol.ui.components.SessionCard
import ch.etasystems.pirol.ui.components.SpeciesCard
import ch.etasystems.pirol.ui.components.SpectrogramCanvas
import ch.etasystems.pirol.data.SpectrogramSettingsRepository
import ch.etasystems.pirol.ui.components.SpectrogramDisplaySettings
import ch.etasystems.pirol.ui.components.SpectrogramSettingsPanel
import ch.etasystems.pirol.ui.components.onSpectrogramLongPress
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun AnalysisScreen(
    viewModel: AnalysisViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    // ON_RESUME: Sessions neu laden (fuer App-Foregrounding + Tab-Wechsel, T51).
    // T107: Sonogramm-Einstellungen kommen live aus dem SpectrogramSettingsRepository.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadSessions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

/** T116: Rahmen mit Snackbar-Host (Fehler beim Teilen/Speichern) um die Session-Detailansicht. */
@Composable
private fun SessionDetailView(
    state: AnalysisUiState,
    viewModel: AnalysisViewModel
) {
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(state.saveErrorMessage) {
        state.saveErrorMessage?.let { msg ->
            viewModel.consumeSaveErrorMessage()
            snackbarHostState.showSnackbar(msg)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        SessionDetailContent(state, viewModel)
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun SessionDetailContent(
    state: AnalysisUiState,
    viewModel: AnalysisViewModel
) {
    val session = state.selectedSession ?: return
    val context = LocalContext.current
    var showAddManualDialog by remember { mutableStateOf(false) }
    var showRavenPreviewDialog by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }  // T109

    // T74: Phenology
    val phenologyRepository: ch.etasystems.pirol.ml.PhenologyRepository = org.koin.compose.koinInject()
    val appPrefsForPhenology: ch.etasystems.pirol.data.AppPreferences = org.koin.compose.koinInject()
    val phenologyEnabled = appPrefsForPhenology.phenologyEnabled
    val currentMonth = java.time.LocalDate.now().monthValue

    // Raven-Export Feedback
    LaunchedEffect(state.ravenExportMessage) {
        state.ravenExportMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeRavenExportMessage()
        }
    }

    // T62: MediaPlayer-Fehler anzeigen (AC7)
    LaunchedEffect(state.playerError) {
        state.playerError?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.consumePlayerError()
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
            Column {
                Text(
                    text = session.metadata.startedAt.take(10), // Kurzform Datum
                    style = MaterialTheme.typography.titleMedium
                )
                // T107: Session-Kennung (= Ordnername) zur Zuordnung auf dem Geraet
                Text(
                    text = session.sessionDir.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // T109: Session-Bemerkung (editierbar)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = session.metadata.note?.takeIf { it.isNotBlank() } ?: "Keine Bemerkung",
                style = MaterialTheme.typography.bodyMedium,
                color = if (session.metadata.note.isNullOrBlank())
                    MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 12.dp)
            )
            IconButton(onClick = { showNoteDialog = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "Bemerkung bearbeiten")
            }
        }
        if (showNoteDialog) {
            var noteText by remember { mutableStateOf(session.metadata.note.orEmpty()) }
            AlertDialog(
                onDismissRequest = { showNoteDialog = false },
                title = { Text("Bemerkung zur Session") },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it.replace('\n', ' ').take(200) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.updateSessionNote(noteText)
                        showNoteDialog = false
                    }) { Text("Speichern") }
                },
                dismissButton = {
                    TextButton(onClick = { showNoteDialog = false }) { Text("Abbrechen") }
                }
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

            // T78: Raven-Datei-Link — klickbar wenn recording.selections.txt existiert
            val ravenFile = remember(session.sessionDir) {
                java.io.File(session.sessionDir, "recording.selections.txt")
            }
            if (ravenFile.exists()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showRavenPreviewDialog = true }) {
                        Text("Raven-Datei anzeigen")
                    }
                }
            }

            // Sonogramm (T107: gemeinsame Einstellungen, Langdruck oeffnet das Panel darunter)
            val specRepo: SpectrogramSettingsRepository = org.koin.compose.koinInject()
            val settings by specRepo.settings.collectAsState()
            var showPanel by remember { mutableStateOf(false) }
            val sessionRange = rememberStaticRange(state.spectrogramState, settings)
            // T116: Zoom auf den abgespielten Detektions-Ausschnitt
            val zoom = state.zoomRange
            val zoomRangeDb = rememberStaticRange(state.zoomSpectrogramState, settings)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .onSpectrogramLongPress { showPanel = true }
            ) {
                SpectrogramCanvas(
                    spectrogramState = if (zoom != null) state.zoomSpectrogramState else state.spectrogramState,
                    config = SpectrogramConfig.BIRDS,
                    palette = settings.palette,
                    autoContrast = false,
                    manualMinDb = settings.minDb,
                    manualMaxDb = settings.maxDb,
                    manualRangeOverride = if (zoom != null) zoomRangeDb else sessionRange,
                    gamma = settings.gamma,
                    ceilingDb = settings.ceilingDb,
                    fitAllFrames = true,  // T61: ganze Uebersicht/Ausschnitt auf Canvas-Breite skalieren
                    freqMinHz = settings.freqMinHz,
                    freqMaxHz = settings.freqMaxHz,
                    modifier = Modifier.fillMaxSize()
                )
                if (state.sonogramLoading) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sonogramm wird berechnet\u2026", color = Color.White.copy(alpha = 0.8f))
                    }
                } else if (state.sonogramError != null) {
                    Text(
                        text = state.sonogramError,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.align(Alignment.Center).padding(12.dp)
                    )
                }
                // T107: Abspiel-Zeiger (Position relativ zur ganzen Aufnahme)
                val posSec = state.playbackPositionSec
                // T116: im Zoom relativ zum Ausschnitt statt zur ganzen Aufnahme
                val viewStart = zoom?.startSec ?: 0f
                val durSec = zoom?.durationSec ?: state.recordingDurationSec
                if (posSec != null && durSec > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val x = ((posSec - viewStart) / durSec).coerceIn(0f, 1f) * size.width
                                drawLine(Color.Cyan, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
                            }
                    )
                }
                if (zoom != null) {
                    TextButton(
                        onClick = { viewModel.clearZoom() },
                        modifier = Modifier.align(Alignment.TopEnd).defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text("Gesamte Session", color = Color.White)
                    }
                }
            }
            if (showPanel) {
                Spacer(modifier = Modifier.height(4.dp))
                SpectrogramSettingsPanel(
                    repository = specRepo,
                    settings = settings,
                    config = SpectrogramConfig.BIRDS,
                    showWindow = false,
                    onClose = { showPanel = false }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Detektionsliste — Header mit "+ Art"-Button (T72)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Detektionen (${state.detections.size}):",
                style = MaterialTheme.typography.titleSmall
            )
            TextButton(onClick = { showAddManualDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.AddCircle,
                    contentDescription = "+ Art",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("+ Art")
            }
        }

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

            // T73: Lock-First sortieren, dann nach lastSeenAtMs desc (AC6)
            val sortedDetections = remember(state.detections) {
                state.detections.sortedWith(
                    compareByDescending<ch.etasystems.pirol.ml.DetectionResult> { it.lockSpecies }
                        .thenByDescending { it.lastSeenAtMs }
                )
            }

            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(
                    items = sortedDetections,
                    key = { it.id }
                ) { detection ->
                    val speciesKey = detection.scientificName.replace(' ', '_')
                    // T107: abgespielte Detektion hervorheben
                    val isPlaying = state.playingDetectionId == detection.id
                    Box(
                        modifier = if (isPlaying) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                        } else Modifier
                    ) {
                    SpeciesCard(
                        detection = detection,
                        phenologyEnabled = phenologyEnabled,
                        isInSeason = phenologyRepository.isInSeason(speciesKey, currentMonth),
                        rarityScore = phenologyRepository.rarity(speciesKey),
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
                        onPlay = { viewModel.playDetection(detection) },
                        isPlayEnabled = state.recordingFile != null,
                        playTimeLabel = formatSecondsAsMinSec(detection.chunkStartSec),
                        onEditDetection = { count, atlasCode, note, memoFile ->
                            viewModel.editDetection(detection.id, count, atlasCode, note, memoFile)
                        },
                        atlasCodes = viewModel.atlasCodes,
                        onSelectAlternative = { candidate ->
                            viewModel.selectAlternative(detection.id, candidate)
                        },
                        // T69: Voice-Memo
                        voiceMemoRecorder = viewModel.voiceMemoRecorder,
                        memoAudioPlayer = viewModel.memoAudioPlayer,
                        onGetMemoTargetFile = { viewModel.getMemoTargetFile(detection.id) },
                        onGetMemoTargetUri = { viewModel.getMemoTargetUri(detection.id) },
                        // T73: Lock-Species
                        onToggleLock = { viewModel.toggleLock(detection.id) },
                        // T116: Teilen (Formatwahl-Dialog)
                        onShare = if (!detection.manualEntry && state.recordingFile != null) { { viewModel.requestShare(detection) } } else null,
                    )
                    }
                }
            }
        }
    }

    // T116: Share-Dialog (Detektion teilen)
    state.shareRequest?.let { req ->
        ch.etasystems.pirol.ui.components.DetectionShareDialog(
            request = req,
            exporter = org.koin.compose.koinInject(),
            onDismiss = { viewModel.clearShareRequest() },
            onError = { viewModel.reportError(it) }
        )
    }

    // T78: Raven-Datei Vorschau-Dialog
    if (showRavenPreviewDialog) {
        val ravenPreviewFile = java.io.File(session.sessionDir, "recording.selections.txt")
        val lines = remember(session.sessionDir) {
            runCatching { ravenPreviewFile.readLines() }.getOrElse { listOf("Fehler: Datei nicht lesbar") }
        }
        AlertDialog(
            onDismissRequest = { showRavenPreviewDialog = false },
            title = { Text("recording.selections.txt") },
            text = {
                LazyColumn {
                    items(lines) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRavenPreviewDialog = false }) {
                    Text("Schliessen")
                }
            }
        )
    }

    // AddManualDetectionDialog (T72)
    if (showAddManualDialog) {
        AddManualDetectionDialog(
            onSearchSpecies = { query -> viewModel.searchSpecies(query) },
            atlasCodes = viewModel.atlasCodes,
            currentLat = null,
            currentLon = null,
            onAdd = { speciesKey, count, atlasCode, note, lat, lon ->
                viewModel.addManualDetection(speciesKey, count, atlasCode, note, lat, lon)
                showAddManualDialog = false
            },
            onDismiss = { showAddManualDialog = false }
        )
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

        // Sonogramm Detektion (T56: Einmal-Perzentil wenn Toggle AN; T56b: Gamma)
        val cmpRepo: SpectrogramSettingsRepository = org.koin.compose.koinInject()
        val settings by cmpRepo.settings.collectAsState()
        val detectionOverride = rememberStaticRange(state.detectionSpectrogramState, settings)
        SpectrogramCanvas(
            spectrogramState = state.detectionSpectrogramState,
            config = SpectrogramConfig.BIRDS,
            palette = settings.palette,
            autoContrast = false,
            manualMinDb = settings.minDb,
            manualMaxDb = settings.maxDb,
            manualRangeOverride = detectionOverride,
            gamma = settings.gamma,
            ceilingDb = settings.ceilingDb,
            freqMinHz = settings.freqMinHz,
            freqMaxHz = settings.freqMaxHz,
            fitAllFrames = true,  // T61: ganze Uebersicht/Ausschnitt auf Canvas-Breite skalieren
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
            // Sonogramm Referenz (T56: Einmal-Perzentil wenn Toggle AN; T56b: Gamma)
            val referenceOverride = rememberStaticRange(state.referenceSpectrogramState, settings)
            SpectrogramCanvas(
                spectrogramState = state.referenceSpectrogramState,
                config = SpectrogramConfig.BIRDS,
                palette = settings.palette,
                autoContrast = false,
                manualMinDb = settings.minDb,
                manualMaxDb = settings.maxDb,
                manualRangeOverride = referenceOverride,
                gamma = settings.gamma,
                ceilingDb = settings.ceilingDb,
                freqMinHz = settings.freqMinHz,
                freqMaxHz = settings.freqMaxHz,
                fitAllFrames = true,  // T61: ganze Uebersicht/Ausschnitt auf Canvas-Breite skalieren
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

/**
 * T107: Einmal-Perzentil-Bereich ueber alle Frames eines (statischen) Sonogramms, abhaengig von den
 * gemeinsamen Einstellungen. null bei AUS-geschaltetem Auto-Kontrast oder leerem State
 * (dann greifen die manuellen dB-Werte). Rechenaufwand: Quickselect ueber <= 2048 x 128 Werte.
 */
@Composable
private fun rememberStaticRange(
    state: ch.etasystems.pirol.ui.components.SpectrogramState,
    settings: SpectrogramDisplaySettings
): Pair<Float, Float>? {
    if (!settings.autoContrast) return null
    val version = state.frameVersion  // Recomposition bei neuem Inhalt
    return remember(state, version, settings.autoLowPct, settings.autoHighPct, settings.autoMinSpanDb) {
        val frames = state.getVisibleFrames(state.maxFrames)
        if (frames.isEmpty()) null
        else ch.etasystems.pirol.audio.dsp.DynamicRangeMapper.computeStatic(
            frames,
            lowPercentile = settings.autoLowPct,
            highPercentile = settings.autoHighPct,
            minSpanDb = settings.autoMinSpanDb
        )
    }
}
