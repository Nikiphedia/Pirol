package ch.etasystems.pirol.ui.analysis

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
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
import ch.etasystems.pirol.audio.PlaybackKeys
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.ml.VerificationStatus
import ch.etasystems.pirol.ui.components.AddManualDetectionDialog
import ch.etasystems.pirol.ui.components.SessionCard
import ch.etasystems.pirol.ui.components.SpeciesCard
import ch.etasystems.pirol.ui.components.FrequencyAxis
import ch.etasystems.pirol.ui.components.forDisplay
import ch.etasystems.pirol.ui.components.SpectrogramCanvas
import ch.etasystems.pirol.data.SpectrogramSettingsRepository
import ch.etasystems.pirol.ui.components.SpectrogramDisplaySettings
import ch.etasystems.pirol.ui.components.SpectrogramSettingsPanel
import ch.etasystems.pirol.ui.components.LiveDisplayMode
import ch.etasystems.pirol.ui.components.SpectrumSettingsPanel
import ch.etasystems.pirol.ui.components.WaveformSettingsPanel
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

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
    // T180: Mehrfachauswahl
    val sessionManager: ch.etasystems.pirol.data.repository.SessionManager = org.koin.compose.koinInject()
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val moveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.moveSelected(uri) }  // Berechtigung nur fuer diesen Vorgang, nicht persistiert
    androidx.activity.compose.BackHandler(enabled = state.selectionMode && !state.batchRunning) {
        viewModel.clearSelection()
    }
    LaunchedEffect(state.batchMessage) {
        state.batchMessage?.let { msg ->
            viewModel.consumeBatchMessage()
            snackbarHostState.showSnackbar(msg)
        }
    }
    val selectedCount = state.selectedIds.size
    // T255: Import-Fortschritt und Dialog vor dem Fremdaudio-Import
    val importActive by viewModel.importActive.collectAsState()
    if (state.audioImportOpen) {
        AudioImportDialog(
            probes = state.audioImportProbes,
            onDismiss = { viewModel.dismissAudioImport() },
            onStart = { viewModel.startAudioImport(it) }
        )
    }
    // T202: Artsuche (Trefferzahl je Session aus dem beim Laden aufgebauten Index)
    val nameResolver: ch.etasystems.pirol.ml.SpeciesNameResolver = org.koin.compose.koinInject()
    var searchOpen by remember { mutableStateOf(state.sessionSearch.isNotEmpty()) }
    val searchCounts: Map<String, Int>? = remember(state.sessionSearch, state.sessionSpecies) {
        if (state.sessionSearch.isBlank()) null
        else state.sessionSpecies.mapValues { (_, names) ->
            SpeciesSearch.countMatches(state.sessionSearch, names) { nameResolver.displayName(it) }
        }
    }

    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text(stringResource(R.string.analysis_batch_loeschen_titel, selectedCount)) },
            text = { Text(stringResource(R.string.analysis_batch_loeschen_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showBatchDeleteDialog = false
                    viewModel.deleteSelected()
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (state.batchRunning) {
        val pr = state.batchProgress
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(if (state.batchIsMove) R.string.analysis_batch_verschiebe else R.string.analysis_batch_loesche)) },
            text = {
                Column {
                    if (pr != null) {
                        Text(stringResource(R.string.analysis_batch_fortschritt, pr.sessionIndex + 1, pr.sessionCount, pr.currentName))
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { pr.fraction }, modifier = Modifier.fillMaxWidth())
                        if (state.batchIsMove) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.analysis_batch_dateien_mb, pr.filesDone, pr.filesTotal,
                                    pr.bytesDone / 1_048_576f, pr.bytesTotal / 1_048_576f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                if (state.batchIsMove) {
                    TextButton(onClick = { viewModel.cancelBatch() }) { Text(stringResource(R.string.common_cancel)) }
                }
            }
        )
    }

    // Bestaetigungsdialog
    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text(stringResource(R.string.analysis_session_loeschen)) },
            text = {
                Text(
                    stringResource(R.string.analysis_session_mit_detektionen_wirklich_loe, session.metadata.startedAt.take(10), session.detectionCount)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(session)
                    sessionToDelete = null
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        if (state.selectionMode) {
            // T180: Kopfleiste im Auswahlmodus
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.clearSelection() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
                }
                Text(
                    text = stringResource(R.string.analysis_batch_n_ausgewaehlt, selectedCount),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.selectAllSessions() }) {
                    Text(stringResource(R.string.analysis_batch_alle))
                }
                IconButton(
                    onClick = { moveLauncher.launch(null) },
                    enabled = selectedCount > 0,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.analysis_batch_verschieben))
                }
                IconButton(
                    onClick = { showBatchDeleteDialog = true },
                    enabled = selectedCount > 0,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = if (selectedCount > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
        } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.analysis_aufnahmen),
                style = MaterialTheme.typography.headlineSmall
            )
            // T202: Artsuche gleich neben der Ueberschrift (Feld klappt per Icon auf)
            IconButton(onClick = {
                searchOpen = !searchOpen
                if (!searchOpen) viewModel.setSessionSearch("")
            }) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.analysis_suche_art))
            }
            Spacer(modifier = Modifier.weight(1f))
            // T255: Import (ZIP, Ordner, Fremdaudio); waehrend eines Imports gesperrt
            ImportMenuButton(viewModel, enabled = importActive == null && !state.batchRunning)
            if (state.sessions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.analysis_ses, state.sessions.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        }
        importActive?.let { a -> ImportProgressRow(a, onCancel = { viewModel.cancelImport() }) }  // T255
        if (searchOpen) {
            OutlinedTextField(
                value = state.sessionSearch,
                onValueChange = { viewModel.setSessionSearch(it) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.analysis_suche_art)) },
                modifier = Modifier.fillMaxWidth()
            )
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
                        text = stringResource(R.string.analysis_noch_keine_aufnahmen_starte_eine),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            searchCounts != null && searchCounts.values.none { it > 0 } -> {
                Text(
                    text = stringResource(R.string.analysis_keine_treffer),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = if (searchCounts == null) state.sessions
                        else state.sessions.filter { (searchCounts[it.metadata.sessionId] ?: 0) > 0 },
                        key = { it.metadata.sessionId }
                    ) { summary ->
                        val active = sessionManager.isActiveSession(summary.sessionDir)
                        Column {
                        SessionCard(
                            summary = summary,
                            onClick = {
                                if (state.selectionMode) viewModel.toggleSelection(summary)
                                else viewModel.openSession(summary)
                            },
                            onDelete = { sessionToDelete = summary },
                            selectionMode = state.selectionMode,
                            selected = summary.metadata.sessionId in state.selectedIds,
                            selectable = !active,
                            onLongClick = { viewModel.startSelection(summary) }
                        )
                        searchCounts?.get(summary.metadata.sessionId)?.let { n ->
                            Text(
                                text = stringResource(R.string.analysis_treffer, n),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                            )
                        }
                        }
                    }
                }
            }
        }
    }
    androidx.compose.material3.SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
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
    // T237: Hinweis mit Undo (Long = ca. 10 s, Feedback-Regel >= 5 s)
    val undoScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(state.undoOffer) {
        state.undoOffer?.let { offer ->
            viewModel.consumeUndoOffer()
            snackbarHostState.currentSnackbarData?.dismiss()
            // eigener Scope: der Effekt endet mit consumeUndoOffer() (Schluessel wird null)
            undoScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = offer.message,
                    actionLabel = offer.actionLabel,
                    duration = androidx.compose.material3.SnackbarDuration.Long
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) offer.onUndo()
            }
        }
    }
    // T244: Undo-Snackbar fuer den Alarm-Knopf der Artkarte
    val alarmUndo: (String, String, () -> Unit) -> Unit = { message, label, onUndo ->
        undoScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = label,
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onUndo()
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        ch.etasystems.pirol.ui.components.LocalUndoSnackbar provides alarmUndo
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        SessionDetailContent(state, viewModel)
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
    }
}

@Composable
private fun SessionDetailContent(
    state: AnalysisUiState,
    viewModel: AnalysisViewModel
) {
    val referencedIds by viewModel.referencedDetectionIds.collectAsState()  // T127
    val session = state.selectedSession ?: return
    val context = LocalContext.current
    var showAddManualDialog by remember { mutableStateOf(false) }
    // T203: Abspielposition beim Oeffnen von "+ Art" (session-relativ), null = keine
    var manualPositionSec by remember { mutableStateOf<Float?>(null) }
    var showNoteDialog by remember { mutableStateOf(false) }  // T109
    var showInfoDialog by remember { mutableStateOf(false) }  // T257
    // T258: Speichern unter: Auswahl ZIP/Ordner, danach System-Dialog (Berechtigung nur fuer diesen Vorgang)
    var showSaveAsChoice by remember { mutableStateOf(false) }
    val saveZipLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) viewModel.saveSessionAsZip(uri) }
    val saveFolderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.saveSessionToFolder(uri) }
    if (showSaveAsChoice) {
        AlertDialog(
            onDismissRequest = { showSaveAsChoice = false },
            title = { Text(stringResource(R.string.analysis_saveas_titel)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(R.string.analysis_saveas_frage))
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    ch.etasystems.pirol.ui.components.HumanMaskNotice(viewModel.masksHumans)  // T261
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showSaveAsChoice = false; saveZipLauncher.launch(viewModel.suggestedZipName()) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.analysis_saveas_zip)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSaveAsChoice = false; saveFolderLauncher.launch(null) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.analysis_saveas_ordner)) }
            }
        )
    }
    // T261: Mensch-Scan vor dem Speichern (Fortschritt, abbrechbar)
    state.humanScanProgress?.let { p ->
        ch.etasystems.pirol.ui.components.HumanScanProgressDialog(p) { viewModel.cancelSaveAs() }
    }
    // T263: Modell ohne Mensch-Klasse: Entscheid vor dem Export
    state.pendingMaskChoice?.let {
        ch.etasystems.pirol.ui.components.HumanMaskChoiceDialog(
            birdNetModelFile = remember(it) { viewModel.birdNetScanModelFile() },
            onChoice = { c -> viewModel.resolveMaskChoice(c) }
        )
    }
    var showRescanDialog by remember { mutableStateOf(false) }  // T221
    if (showRescanDialog) RescanDialog(viewModel) { showRescanDialog = false }
    // T255: nach einem Fremdaudio-Import den Rescan-Dialog fuer die neue Session oeffnen
    LaunchedEffect(state.rescanRequest, state.hasRecording, session.metadata.sessionId) {
        if (state.rescanRequest && state.hasRecording) {
            viewModel.consumeRescanRequest()
            if (state.rescanProgress == null) showRescanDialog = true
        }
    }
    // T202: Artsuche im Session-Detail (filtert die Detektionsliste)
    val detailResolver: ch.etasystems.pirol.ml.SpeciesNameResolver = org.koin.compose.koinInject()
    var detailSearchOpen by remember { mutableStateOf(false) }
    var detailSearch by remember { mutableStateOf("") }

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                // T247: Gemeinde (offline) neben dem Datum
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val lookup = remember { ch.etasystems.pirol.location.MunicipalityLookup(ctx) }
                val lat = session.metadata.latitude
                val lon = session.metadata.longitude
                var municipality by remember(lat, lon) { mutableStateOf<String?>(null) }
                LaunchedEffect(lat, lon) {
                    municipality = if (lat != null && lon != null) lookup.lookup(lat, lon) else null
                }
                // T254: Datum · Gemeinde · Bemerkung (gekuerzt) in einer Zeile, antippbar zum Bearbeiten
                val noteText0 = session.metadata.note?.takeIf { it.isNotBlank() }
                Text(
                    text = session.metadata.startedAt.take(10) + (municipality?.let { " · $it" } ?: "") +
                        " · " + (noteText0 ?: stringResource(R.string.analysis_keine_bemerkung)),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = if (noteText0 == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clickable(onClickLabel = stringResource(R.string.analysis_bemerkung_bearbeiten)) { showNoteDialog = true }
                        .heightIn(min = 48.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                )
                // T107: Session-Kennung (= Ordnername) zur Zuordnung auf dem Geraet
                Text(
                    text = session.sessionDir.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // T221: angezeigte Rescan-Variante (Original ohne Zusatz)
                state.variants.firstOrNull { it.id == state.activeVariant }?.let { v ->
                    Text(
                        text = variantLabel(v),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
            // T202: Kopfzeile: Suche, Raven; Upload im Ueberlaufmenue (je 48 dp). T210: Wiedergabe in eigener Zeile darunter
            val uploadStatus by viewModel.uploadStatus.collectAsState()
            // T254: Kompakt/Gross-Umschalter wie in Live
            ch.etasystems.pirol.ui.components.CompactCardToggleButton()
            // T254: schmale Fenster (Telefon hoch): Suche und Rescan ins Ueberlaufmenue, damit der Titel Platz hat
            val narrowHeader = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
            if (!narrowHeader) {
                IconButton(onClick = {
                    detailSearchOpen = !detailSearchOpen
                    if (!detailSearchOpen) detailSearch = ""
                }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.analysis_suche_art))
                }
                // T221: Rescan neben der Lupe; waehrend einer Aufnahme ausgegraut mit Hinweis
                if (state.hasRecording) {
                    val recordingActive by viewModel.recordingActive.collectAsState()
                    val rescanRunning = state.rescanProgress != null
                    IconButton(onClick = {
                        when {
                            recordingActive -> viewModel.reportError(ch.etasystems.pirol.ui.AppStrings.get(R.string.analysis_rescan_aufnahme_laeuft))
                            !rescanRunning -> showRescanDialog = true
                        }
                    }) {
                        Icon(
                            Icons.Filled.Autorenew,
                            contentDescription = stringResource(R.string.analysis_rescan),
                            tint = if (recordingActive || rescanRunning)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else androidx.compose.material3.LocalContentColor.current
                        )
                    }
                }
            }
            IconButton(onClick = { viewModel.openRavenTable() }) {
                Icon(Icons.Filled.TableChart, contentDescription = stringResource(R.string.analysis_raven_oeffnen))
            }
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }) {
                    if (uploadStatus is ch.etasystems.pirol.data.sync.UploadStatus.InProgress) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.analysis_mehr))
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (narrowHeader) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.analysis_menu_suche)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                detailSearchOpen = !detailSearchOpen
                                if (!detailSearchOpen) detailSearch = ""
                            }
                        )
                        if (state.hasRecording) {
                            val recActive by viewModel.recordingActive.collectAsState()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.analysis_rescan)) },
                                leadingIcon = { Icon(Icons.Filled.Autorenew, contentDescription = null) },
                                enabled = state.rescanProgress == null,
                                onClick = {
                                    menuOpen = false
                                    if (recActive) viewModel.reportError(ch.etasystems.pirol.ui.AppStrings.get(R.string.analysis_rescan_aufnahme_laeuft))
                                    else showRescanDialog = true
                                }
                            )
                        }
                    }
                    // T257: nur importierte Sessions: Startzeit, Ort, Bemerkung bearbeiten
                    if (session.metadata.importedFrom != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.analysis_angaben_bearbeiten)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            enabled = state.rescanProgress == null,
                            onClick = { menuOpen = false; showInfoDialog = true },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                    // T258: Speichern unter (ZIP oder Ordner); die laufende Aufnahme-Session ist ausgegraut
                    val saveAsRecActive by viewModel.recordingActive.collectAsState()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.analysis_saveas_menue)) },
                        leadingIcon = { Icon(Icons.Filled.SaveAlt, contentDescription = null) },
                        enabled = !saveAsRecActive || viewModel.canSaveAs(),
                        onClick = {
                            menuOpen = false
                            if (viewModel.canSaveAs()) showSaveAsChoice = true
                            else viewModel.reportError(ch.etasystems.pirol.ui.AppStrings.get(R.string.analysis_saveas_aufnahme_laeuft))
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.analysis_session_hochladen)) },
                        leadingIcon = { Icon(Icons.Filled.Upload, contentDescription = null) },
                        enabled = !(uploadStatus is ch.etasystems.pirol.data.sync.UploadStatus.InProgress),
                        onClick = { menuOpen = false; viewModel.uploadSession() }
                    )
                }
            }
        }
        // T210: Wiedergabe-Zeile: vorherige Detektion, Play/Stopp, naechste Detektion, Position (je 48 dp)
        if (state.hasRecording) {
            val navList = remember(state.detections, detailSearch) {
                state.detections.filter {
                    SpeciesSearch.matches(detailSearch, detailResolver.displayName(it.scientificName), it.scientificName)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.playAdjacent(navList, forward = false) }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.analysis_vorherige_detektion))
                }
                IconButton(onClick = { viewModel.playCurrentChunk() }) {  // T173: Start/Stopp-Umschalter
                    Icon(
                        imageVector = if (state.playingKey == PlaybackKeys.SESSION)
                            Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.common_play)
                    )
                }
                IconButton(onClick = { viewModel.playAdjacent(navList, forward = true) }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.analysis_naechste_detektion))
                }
                Text(
                    text = formatSecondsAsMinSec(state.playbackPositionSec ?: state.pendingStartSec ?: 0f) + " / " +
                        formatSecondsAsMinSec(state.recordingDurationSec),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (detailSearchOpen) {
            OutlinedTextField(
                value = detailSearch,
                onValueChange = { detailSearch = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.analysis_suche_art)) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // T221: Varianten Original | Rescan(s) und Fortschritt
        RescanVariantBar(state, viewModel)

        // T257: Angaben einer importierten Session
        if (showInfoDialog) {
            SessionInfoEditDialog(
                metadata = session.metadata,
                onDismiss = { showInfoDialog = false },
                onSave = { start, newLat, newLon, newNote ->
                    viewModel.updateImportedSessionInfo(start, newLat, newLon, newNote)
                    showInfoDialog = false
                }
            )
        }

        // T109: Session-Bemerkung (editierbar, T254: Bearbeiten ueber die Kopfzeile)
        if (showNoteDialog) {
            var noteText by remember { mutableStateOf(session.metadata.note.orEmpty()) }
            AlertDialog(
                onDismissRequest = { showNoteDialog = false },
                title = { Text(stringResource(R.string.analysis_bemerkung_zur_session)) },
                text = {
                    // T197: Session-Position, sonst aktuelles GPS
                    val locationProvider: ch.etasystems.pirol.location.LocationProvider = org.koin.compose.koinInject()
                    Column {
                        androidx.compose.material3.OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it.replace('\n', ' ').take(200) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        ch.etasystems.pirol.ui.components.MunicipalityInsertButton(
                            position = {
                                val lat = session.metadata.latitude ?: locationProvider.location.value?.latitude
                                val lon = session.metadata.longitude ?: locationProvider.location.value?.longitude
                                if (lat != null && lon != null) lat to lon else null
                            },
                            note = noteText,
                            maxLength = 200,
                            onNoteChange = { noteText = it },
                            ensureFix = { locationProvider.ensureFix() },
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.updateSessionNote(noteText)
                        showNoteDialog = false
                    }) { Text(stringResource(R.string.common_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showNoteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }

        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        // Banner: alte Session ohne recording.wav (chunk_*.wav Format vor T46)
        if (!state.hasRecording) {
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
                        text = stringResource(R.string.analysis_diese_session_wurde_im_alten),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Sonogramm + Aufnahme-Wiedergabe
        if (state.hasRecording) {
            // Sonogramm (T107: gemeinsame Einstellungen, Langdruck oeffnet das Panel darunter)
            val specRepo: SpectrogramSettingsRepository = org.koin.compose.koinInject()
            val settings by specRepo.settings.collectAsState()
            var showPanel by remember { mutableStateOf(false) }
            val sessionRange = rememberStaticRange(state.spectrogramState, settings)
            // T116: Zoom auf den abgespielten Detektions-Ausschnitt
            val zoom = state.zoomRange
            val zoomRangeDb = rememberStaticRange(state.zoomSpectrogramState, settings)
            // T200: Achse und Bin-Zuschnitt aus derselben Config wie die Mel-Berechnung (bis Nyquist)
            val mainState = if (zoom != null) state.zoomSpectrogramState else state.spectrogramState
            val mainConfig = SpectrogramConfig.BIRDS.resolved(mainState.sampleRate)
            // T205: Anzeigemodus und Einstellungen gemeinsam mit Live
            val mode = settings.displayMode
            val spectrumSettings by specRepo.spectrum.collectAsState()
            val waveformSettings by specRepo.waveform.collectAsState()
            // Spektrum/Oszillogramm brauchen einen Ausschnitt: ohne Zoom das erste Fenster laden
            LaunchedEffect(mode, zoom == null, state.recordingDurationSec) {
                if (mode != LiveDisplayMode.SONOGRAM && zoom == null && state.recordingDurationSec > 0f) {
                    viewModel.showWindow(0f, minOf(settings.windowSec, state.recordingDurationSec))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (settings.palette.isLight) Color.White else Color.Black)
                    // T210: Tippen setzt die Wiedergabe (nur Sonogramm; das Oszillogramm meldet selbst), Langdruck = Panel
                    .pointerInput(mode, zoom, state.recordingDurationSec) {
                        detectTapGestures(
                            onLongPress = { showPanel = true },
                            onTap = { pos ->
                                if (mode != LiveDisplayMode.SONOGRAM) return@detectTapGestures
                                val viewStart = zoom?.startSec ?: 0f
                                val viewDur = zoom?.durationSec ?: state.recordingDurationSec
                                PlaybackNav.tapToSec(pos.x, 0f, size.width.toFloat(), viewStart, viewDur, state.recordingDurationSec)
                                    ?.let { viewModel.seekTo(it) }
                            }
                        )
                    }
            ) {
                if (mode == LiveDisplayMode.SONOGRAM) {
                    SpectrogramCanvas(
                        spectrogramState = mainState,
                        config = mainConfig,
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
                    // T144: Frequenzachse links (1/2/4/8/12 kHz)
                    FrequencyAxis(
                        config = mainConfig.forDisplay(settings.freqMinHz, settings.freqMaxHz),
                        lightBackground = settings.palette.isLight,
                        modifier = Modifier.fillMaxHeight().align(Alignment.CenterStart)
                    )
                } else {
                    // T205: Spektrum / Oszillogramm des sichtbaren Ausschnitts (gemeinsame Einstellungen mit Live)
                    AnalysisExcerptView(
                        mode = mode,
                        pcm = state.zoomPcm,
                        excerptStartSec = zoom?.startSec ?: 0f,
                        posSec = state.playbackPositionSec,
                        playheadSec = state.playbackPositionSec ?: state.pendingStartSec,
                        settings = settings,
                        spectrumSettings = spectrumSettings,
                        waveformSettings = waveformSettings,
                        sonoAutoRange = zoomRangeDb,
                        onLongPress = { showPanel = true },
                        onSeekFraction = { frac ->  // T210
                            val viewStart = zoom?.startSec ?: 0f
                            val viewDur = zoom?.durationSec ?: state.recordingDurationSec
                            PlaybackNav.tapToSec(frac, 0f, 1f, viewStart, viewDur, state.recordingDurationSec)
                                ?.let { viewModel.seekTo(it) }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (state.sonogramLoading) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.analysis_sonogramm_wird_berechnet), color = Color.White.copy(alpha = 0.8f))
                    }
                } else if (state.sonogramError != null) {
                    Text(
                        text = state.sonogramError,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.align(Alignment.Center).padding(12.dp)
                    )
                }
                // T107: Abspiel-Zeiger (Position relativ zur ganzen Aufnahme)
                val posSec = state.playbackPositionSec ?: state.pendingStartSec  // T210: gesetzte Startposition zeigen
                // T116: im Zoom relativ zum Ausschnitt statt zur ganzen Aufnahme
                val viewStart = zoom?.startSec ?: 0f
                val durSec = zoom?.durationSec ?: state.recordingDurationSec
                if (mode == LiveDisplayMode.SONOGRAM && posSec != null && durSec > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val x = ((posSec - viewStart) / durSec).coerceIn(0f, 1f) * size.width
                                drawLine(Color.Cyan, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
                            }
                    )
                }
                if (zoom != null && mode == LiveDisplayMode.SONOGRAM) {
                    TextButton(
                        onClick = { viewModel.clearZoom() },
                        modifier = Modifier.align(Alignment.TopEnd).defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(stringResource(R.string.analysis_gesamte_session), color = Color.White)
                    }
                }
            }
            // T194: Uebersichtsleiste ueber die ganze Session (Segmente: Session-Gesamtdauer)
            Spacer(modifier = Modifier.height(4.dp))
            val markers = remember(state.detections) {
                state.detections.filter { !it.manualEntry }.map { it.bestWindowStartSec to it.bestWindowEndSec }
            }
            OverviewBar(
                totalSec = state.recordingDurationSec,
                viewStartSec = zoom?.startSec,
                viewEndSec = zoom?.endSec,
                markers = markers,
                contentDescription = stringResource(R.string.analysis_uebersichtsleiste),
                onWindowChange = { start, width -> viewModel.showWindow(start, width) }
            )
            if (showPanel) {
                Spacer(modifier = Modifier.height(4.dp))
                val panelConfig = SpectrogramConfig.BIRDS.resolved(mainState.sampleRate)
                when (mode) {
                    LiveDisplayMode.SPECTRUM -> SpectrumSettingsPanel(
                        repository = specRepo,
                        displaySettings = settings,
                        settings = spectrumSettings,
                        config = panelConfig,
                        sampleRate = mainState.sampleRate,
                        onResetPeak = {},
                        onClose = { showPanel = false }
                    )
                    LiveDisplayMode.WAVEFORM -> WaveformSettingsPanel(
                        repository = specRepo,
                        displaySettings = settings,
                        settings = waveformSettings,
                        config = panelConfig,
                        onClose = { showPanel = false }
                    )
                    else -> SpectrogramSettingsPanel(
                        repository = specRepo,
                        settings = settings,
                        config = panelConfig,
                        showWindow = false,
                        showDisplayMode = true,
                        onClose = { showPanel = false }
                    )
                }
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
                text = if (detailSearch.isBlank()) {
                    stringResource(R.string.analysis_detektionen, state.detections.size)
                } else {
                    // T202: bei aktiver Suche gefilterte / gesamte Zahl
                    val shown = state.detections.count {
                        SpeciesSearch.matches(detailSearch, detailResolver.displayName(it.scientificName), it.scientificName)
                    }
                    stringResource(R.string.analysis_detektionen_gefiltert, shown, state.detections.size)
                },
                style = MaterialTheme.typography.titleSmall
            )
            TextButton(onClick = {
                manualPositionSec = state.playbackPositionSec ?: state.lastPlaybackPositionSec
                showAddManualDialog = true
            }) {
                Icon(
                    imageVector = Icons.Filled.AddCircle,
                    contentDescription = stringResource(R.string.analysis_art),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.analysis_art))
            }
        }

        if (state.detections.isEmpty()) {
            Text(
                text = stringResource(R.string.analysis_keine_detektionen_in_dieser_session),
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
            val sortedDetections = remember(state.detections, detailSearch) {
                state.detections.filter {
                    SpeciesSearch.matches(detailSearch, detailResolver.displayName(it.scientificName), it.scientificName)
                }.sortedWith(
                    compareByDescending<ch.etasystems.pirol.ml.DetectionResult> { it.lockSpecies }
                        .thenByDescending { it.lastSeenAtMs }
                )
            }

            // T167: aufgeklappte Arten (Einzelvorkommen sichtbar)
            var expandedIds by remember { mutableStateOf(emptySet<String>()) }

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
                    // T186: laeuft eine Episode, markiert nur deren Zeile (OccurrenceSection), nicht die ganze Artkarte
                    val isCardPlaying = isPlaying && state.playingOccurrenceStartSec == null
                    Column {
                    Box(
                        modifier = if (isCardPlaying) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                        } else Modifier
                    ) {
                    SpeciesCard(
                        detection = detection,
                        // T224: Modell der angezeigten Variante (Rescan) bzw. der Session
                        primaryModelLabel = ch.etasystems.pirol.ml.SecondaryCheck.modelLabel(
                            state.variants.firstOrNull { it.id == state.activeVariant }?.modelId
                                ?: state.selectedSession?.metadata?.modelId
                        ),
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
                        isPlaying = state.playingKey == PlaybackKeys.detection(detection.id),  // T173
                        isPlayEnabled = state.hasRecording,
                        playTimeLabel = formatSecondsAsMinSec(detection.bestWindowStartSec),  // T186
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
                        // T123: Als Referenz speichern (Button erscheint bei CONFIRMED/CORRECTED)
                        onSaveAsReference = if (!detection.manualEntry && state.hasRecording) { { viewModel.saveAsReference(detection) } } else null,
                        isReference = detection.id in referencedIds,  // T127
                        onShare = if (!detection.manualEntry && state.hasRecording) { { viewModel.requestShare(detection) } } else null,
                    )
                    }
                    // T167: Einzelvorkommen (Anker + absorbierte Cluster-Fenster)
                    val occurrences = remember(detection, state.mergeTimestamps) {
                        DetectionOccurrences.of(detection, state.mergeTimestamps[detection.id].orEmpty())
                    }
                    OccurrenceSection(
                        detection = detection,
                        occurrences = occurrences,
                        expanded = detection.id in expandedIds,
                        onToggle = {
                            expandedIds = if (detection.id in expandedIds) expandedIds - detection.id
                            else expandedIds + detection.id
                        },
                        playingStartSec = if (isPlaying) state.playingOccurrenceStartSec else null,
                        playEnabled = state.hasRecording && !detection.manualEntry,
                        onPlay = { viewModel.playEpisode(detection, it) },
                        onShare = { viewModel.requestShareEpisode(detection, it) }
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

    // T194: Raven-Tabelle (ersetzt die Textvorschau aus T78)
    state.ravenTable?.let { table ->
        RavenTableDialog(
            title = "recording.selections.txt",
            table = table,
            onDismiss = { viewModel.closeRavenTable() }
        )
    }

    // AddManualDetectionDialog (T72)
    if (showAddManualDialog) {
        val manualTime = viewModel.manualEntryTime(manualPositionSec)
        AddManualDetectionDialog(
            onSearchSpecies = { query -> viewModel.searchSpecies(query) },
            atlasCodes = viewModel.atlasCodes,
            currentLat = state.selectedSession?.metadata?.latitude,
            currentLon = state.selectedSession?.metadata?.longitude,
            onAdd = { speciesKey, count, atlasCode, note, lat, lon ->
                viewModel.addManualDetection(speciesKey, count, atlasCode, note, lat, lon, manualPositionSec)
                showAddManualDialog = false
            },
            onDismiss = { showAddManualDialog = false },
            allowMapPick = true,
            timeHint = if (manualTime.hasPosition) {
                formatSecondsAsMinSec(manualPositionSec ?: 0f) + " ±" + ManualEntryTime.HALF_WINDOW_SEC.toInt() + " s"
            } else null
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.analysis_vergleich, state.compareDetection?.commonName ?: ""),
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(if (settings.palette.isLight) Color.White else Color.Black)
        ) {
        SpectrogramCanvas(
            spectrogramState = state.detectionSpectrogramState,
            config = SpectrogramConfig.BIRDS.resolved(state.detectionSpectrogramState.sampleRate),
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
            modifier = Modifier.fillMaxSize()
        )
        FrequencyAxis(
            config = SpectrogramConfig.BIRDS.resolved(state.detectionSpectrogramState.sampleRate)
                .forDisplay(settings.freqMinHz, settings.freqMaxHz),
            lightBackground = settings.palette.isLight,
            modifier = Modifier.fillMaxHeight().align(Alignment.CenterStart)
        )
        }

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
                    text = formatSecondsAsMinSec(det.bestWindowStartSec),  // T186
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = {
                viewModel.playDetection()  // T173: Start/Stopp-Umschalter
            }) {
                Icon(
                    imageVector = if (state.playingKey == PlaybackKeys.COMPARE_DETECTION)
                        Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.analysis_detektion_abspielen)
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(if (settings.palette.isLight) Color.White else Color.Black)
            ) {
            SpectrogramCanvas(
                spectrogramState = state.referenceSpectrogramState,
                config = SpectrogramConfig.BIRDS.resolved(state.referenceSpectrogramState.sampleRate),
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
                modifier = Modifier.fillMaxSize()
            )
            FrequencyAxis(
                config = SpectrogramConfig.BIRDS.resolved(state.referenceSpectrogramState.sampleRate)
                    .forDisplay(settings.freqMinHz, settings.freqMaxHz),
                lightBackground = settings.palette.isLight,
                modifier = Modifier.fillMaxHeight().align(Alignment.CenterStart)
            )
            }

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
                    viewModel.playReference()  // T173: Start/Stopp-Umschalter
                }) {
                    Icon(
                        imageVector = if (state.playingKey == PlaybackKeys.COMPARE_REFERENCE)
                            Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.analysis_referenz_abspielen)
                    )
                }
            }

            // Referenz-Auswahl (wenn mehrere vorhanden)
            if (state.referenceEntries.size > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.analysis_weitere_referenzen),
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
                text = stringResource(R.string.analysis_keine_referenzen_fuer_diese_art),
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
