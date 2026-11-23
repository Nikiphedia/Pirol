package ch.etasystems.pirol.ui.live

import android.os.SystemClock
import android.widget.Chronometer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import ch.etasystems.pirol.ui.components.AddManualDetectionDialog
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.etasystems.pirol.audio.AudioPermissionHandler
import ch.etasystems.pirol.audio.PermissionState
import ch.etasystems.pirol.audio.areAudioPermissionsGranted
import ch.etasystems.pirol.location.LocationPermissionHandler
import ch.etasystems.pirol.location.LocationPermissionState
import ch.etasystems.pirol.location.areLocationPermissionsGranted
import ch.etasystems.pirol.data.sync.UploadStatus
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.ml.VerificationStatus
import ch.etasystems.pirol.ui.components.DetectionList
import ch.etasystems.pirol.ui.components.SimilarityPanel
import ch.etasystems.pirol.ui.components.LiveDisplayMode
import ch.etasystems.pirol.ui.components.SpectrogramCanvas
import ch.etasystems.pirol.ui.components.SpectrumView
import ch.etasystems.pirol.ui.components.SpectrumSettingsPanel
import ch.etasystems.pirol.ui.components.WaveformView
import ch.etasystems.pirol.ui.components.labelRes
import ch.etasystems.pirol.ui.components.SpectrogramOverlay
import ch.etasystems.pirol.ui.components.SpectrogramPalette
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import ch.etasystems.pirol.data.SpectrogramSettingsRepository
import ch.etasystems.pirol.ui.components.SpectrogramDisplaySettings
import ch.etasystems.pirol.ui.components.SpectrogramSettingsPanel
import ch.etasystems.pirol.ui.components.forDisplay
import ch.etasystems.pirol.ui.components.onSpectrogramLongPress
import kotlin.math.roundToInt
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

@Composable
fun LiveScreen(
    widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    compactHeight: Boolean = false,  // T174: Telefon quer (Fensterhoehe < 480 dp)
    viewModel: LiveViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // --- Service-Binding via ViewModel (Application-Context, kein Leak) ---
    DisposableEffect(Unit) {
        viewModel.bindService(context)
        viewModel.setPermissionGranted(areAudioPermissionsGranted(context))
        onDispose {
            viewModel.unbindService(context)
        }
    }

    // --- Prefs (Top-N, Embedding-DB-Limit) bei ON_RESUME neu einlesen.
    // T107: Sonogramm-Einstellungen kommen live aus dem SpectrogramSettingsRepository.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadPrefs()
            }
            // T157: Alarm-Bestaetigung ggf. mit Undo-Snackbar anbieten
            if (event == Lifecycle.Event.ON_START) viewModel.setScreenVisible(true)
            if (event == Lifecycle.Event.ON_STOP) viewModel.setScreenVisible(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // T157: Observer wird erst nach ON_START registriert; Sichtbarkeit explizit nachziehen
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.setScreenVisible(true)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setScreenVisible(false)
        }
    }

    // --- Audio Permission ---
    var permissionTrigger by remember { mutableStateOf(false) }

    AudioPermissionHandler(
        triggerRequest = permissionTrigger,
        onPermissionResult = { state ->
            permissionTrigger = false
            val granted = state == PermissionState.Granted
            viewModel.setPermissionGranted(granted)
            if (granted) {
                viewModel.startRecording()
            }
        }
    )

    // --- Location Permission ---
    var locationPermissionTrigger by remember { mutableStateOf(false) }
    var locationPermissionGranted by remember {
        mutableStateOf(areLocationPermissionsGranted(context))
    }

    LocationPermissionHandler(
        triggerRequest = locationPermissionTrigger,
        onPermissionResult = { state ->
            locationPermissionTrigger = false
            locationPermissionGranted = state == LocationPermissionState.Granted
            viewModel.setLocationPermissionGranted(locationPermissionGranted)  // T52
        }
    )

    // Location-Permission beim ersten Composable-Aufbau anfragen
    LaunchedEffect(Unit) {
        val granted = areLocationPermissionsGranted(context)
        viewModel.setLocationPermissionGranted(granted)  // T52
        if (!granted) {
            locationPermissionTrigger = true
        }
    }

    // --- Snackbar-Infrastruktur (T52) ---
    val snackbarHostState = remember { SnackbarHostState() }
    val topSnackbarHostState = remember { SnackbarHostState() }  // T172: Hinweise oben
    LaunchedEffect(Unit) {
        val effectScope = this
        viewModel.snackbarEvents.collect { event ->
            if (event.atTop) {
                // Kurzer Hinweis oben, blockiert die Undo-Snackbars unten nicht
                topSnackbarHostState.currentSnackbarData?.dismiss()
                effectScope.launch {
                    topSnackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short
                    )
                }
                return@collect
            }
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = event.message,
                actionLabel = event.actionLabel,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) event.onAction?.invoke()
        }
    }

    // --- Adaptives Layout basierend auf WindowWidthSizeClass ---
    Box(modifier = Modifier.fillMaxSize()) {
        Column {
            // Fallback-Banner: SAF-URI nicht erreichbar (T51)
            if (uiState.storageUnavailableFallback) {
                StorageFallbackBanner()
            }

            when {
                // T174: Telefon quer (breit, aber niedrig): kompaktes Zweispalten-Layout
                compactHeight && widthSizeClass != WindowWidthSizeClass.Compact -> {
                    MediumLiveLayout(
                        uiState = uiState,
                        viewModel = viewModel,
                        onRequestPermission = { permissionTrigger = true },
                        onRequestLocationPermission = { locationPermissionTrigger = true },
                        compact = true
                    )
                }
                widthSizeClass == WindowWidthSizeClass.Expanded -> {
                    // Tablet: Dual-Pane nebeneinander
                    ExpandedLiveLayout(
                        uiState = uiState,
                        viewModel = viewModel,
                        onRequestPermission = { permissionTrigger = true },
                        onRequestLocationPermission = { locationPermissionTrigger = true }
                    )
                }
                widthSizeClass == WindowWidthSizeClass.Medium -> {
                    // Phone Landscape / kleines Tablet: gewichtetes Layout
                    MediumLiveLayout(
                        uiState = uiState,
                        viewModel = viewModel,
                        onRequestPermission = { permissionTrigger = true },
                        onRequestLocationPermission = { locationPermissionTrigger = true }
                    )
                }
                else -> {
                    // Phone Portrait: Standard-Layout
                    CompactLiveLayout(
                        uiState = uiState,
                        viewModel = viewModel,
                        onRequestPermission = { permissionTrigger = true },
                        onRequestLocationPermission = { locationPermissionTrigger = true }
                    )
                }
            }
        }
        // T116: Share-Dialog (Detektion teilen)
        val shareRequest by viewModel.shareRequest.collectAsState()
        shareRequest?.let { req ->
            ch.etasystems.pirol.ui.components.DetectionShareDialog(
                request = req,
                exporter = org.koin.compose.koinInject(),
                onDismiss = { viewModel.clearShareRequest() },
                onError = { viewModel.postSnackbar(it) }
            )
        }
        // Snackbar (T52): Verifikations-Feedback + Undo
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        // T172: Hinweis "lange druecken" oben, damit der Finger am Stopp-Knopf ihn nicht verdeckt
        SnackbarHost(
            hostState = topSnackbarHostState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * Fallback-Banner: Wird angezeigt wenn der konfigurierte SAF-Speicherort nicht erreichbar war
 * und stattdessen getExternalFilesDir() verwendet wurde (T51).
 */
@Composable
private fun StorageFallbackBanner() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(R.string.live_speicherort_nicht_erreichbar_aufnahm),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

// =============================================================================
// Compact Layout (Phone Portrait)
// =============================================================================

@Composable
private fun CompactLiveLayout(
    uiState: LiveUiState,
    viewModel: LiveViewModel,
    onRequestPermission: () -> Unit,
    onRequestLocationPermission: () -> Unit = {}
) {
    var showAddManualDialog by remember { mutableStateOf(false) }
    var showMemoDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // T170: Telefon ohne Titelzeile (Platz). Laufzeit steht in der Status-Zeile,
            // Palette ist per Langdruck auf das Sonogramm waehlbar.
            // Kompakte Status-Zeile (T30) + Export-Buttons
            ScanStatusRow(uiState = uiState, viewModel = viewModel)
            if (!uiState.isRecording) SessionNoteField(viewModel)

            LocationBar(uiState = uiState, onRequestLocationPermission = onRequestLocationPermission)

            Spacer(modifier = Modifier.height(4.dp))

            // Sonogramm (140dp)
            SpectrogramWithPanel(
                uiState = uiState,
                fixedHeight = 140.dp,
                modifier = Modifier.fillMaxWidth()
            )

            // Preroll-Indikator
            PrerollIndicator(uiState = uiState)

            // T168: dezenter Hinweis, solange das Mikrofon wegen Lautsprecher-Wiedergabe stumm ist
            val micMuted by viewModel.micMutedByPlayback.collectAsState()
            if (micMuted && uiState.isRecording) {
                Text(
                    text = stringResource(R.string.live_mic_stumm_wiedergabe),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Detektionsliste (ersetzt SpeciesPlaceholder)
            DetectionList(
                detectionState = uiState.detectionListState,
                primaryModelLabel = ch.etasystems.pirol.ml.SecondaryCheck.modelLabel(
                    org.koin.compose.koinInject<ch.etasystems.pirol.ml.AudioClassifier>().modelId
                ),  // T224
                isModelAvailable = uiState.isModelAvailable,
                onConfirm = { id -> viewModel.verifyDetection(id, VerificationStatus.CONFIRMED) },
                onMarkUncertain = { id -> viewModel.verifyDetection(id, VerificationStatus.UNCERTAIN) },
                onReject = { id -> viewModel.verifyDetection(id, VerificationStatus.REJECTED) },
                onCorrect = { id, species -> viewModel.verifyDetection(id, VerificationStatus.CORRECTED, species) },
                onSaveAsReference = { id -> viewModel.saveAsReference(id) },
                referencedIds = viewModel.referencedDetectionIds.collectAsState().value,  // T127
                onShare = if (uiState.isRecording || uiState.lastSessionId != null) { id -> viewModel.requestShare(id) } else null,  // T116/T128: auch nach Stopp (letzte Session)
                onSelectAlternative = { id, candidate -> viewModel.selectAlternative(id, candidate) },
                onEditDetection = { id, count, atlasCode, note, memoFile -> viewModel.editDetection(id, count, atlasCode, note, memoFile) },
                atlasCodes = viewModel.atlasCodes,
                watchlistSpecies = uiState.watchlistSpecies,
                showTopNCandidates = uiState.showTopNCandidates,
                // T69: Voice-Memo
                voiceMemoRecorder = viewModel.voiceMemoRecorder,
                memoAudioPlayer = viewModel.memoAudioPlayer,
                onGetMemoTargetFile = { id -> viewModel.getMemoTargetFile(id) },
                onGetMemoTargetUri = { id -> viewModel.getMemoTargetUri(id) },
                // T73: Lock-Species
                onToggleLock = { id -> viewModel.toggleLock(id) },
                // T164: Ausschnitt anhoeren
                onPlay = { id -> viewModel.toggleDetectionPlayback(id) },
                playingDetectionId = viewModel.playingDetectionId.collectAsState().value,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            // Aehnlichkeitssuche (T12b)
            if (uiState.similarMatches.isNotEmpty() || uiState.isEmbeddingAvailable) {
                Spacer(modifier = Modifier.height(2.dp))
                SimilarityPanel(
                    matches = uiState.similarMatches,
                    isEmbeddingAvailable = uiState.isEmbeddingAvailable,
                    similarEnabled = uiState.similarEnabled,
                    similarLines = uiState.similarLines,
                    recentSpecies = uiState.recentSpecies,
                    collapsible = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // FAB-Bereich: Memo + +Art (nur bei Recording) + Preroll-Toggle (F5) + Haupt-FAB
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Memo-Button + +Art-Button (F6): nur sichtbar wenn Recording aktiv
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Memo-Button
                AnimatedVisibility(visible = uiState.isRecording) {
                    IconButton(
                        onClick = { showMemoDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = stringResource(R.string.ui_memo_aufnehmen),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                // +Art-Button
                AnimatedVisibility(visible = uiState.isRecording) {
                    IconButton(
                        onClick = { showAddManualDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddCircle,
                            contentDescription = stringResource(R.string.analysis_art),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Preroll-Toggle (F5) + Haupt-FAB
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Preroll-Toggle (F5): nur sichtbar wenn Preroll in Settings aktiviert und keine Aufnahme
                if (uiState.isPrerollEnabled && !uiState.isRecording) {
                    SmallFloatingActionButton(
                        onClick = {
                            if (uiState.isPrerollRunning) viewModel.userStopPreroll()
                            else viewModel.userStartPreroll()
                        },
                        containerColor = if (uiState.isPrerollRunning)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            imageVector = if (uiState.isPrerollRunning)
                                Icons.Filled.Stop
                            else
                                Icons.Filled.Mic,
                            contentDescription = if (uiState.isPrerollRunning) stringResource(R.string.live_preroll_stoppen) else stringResource(R.string.live_preroll_starten),
                            tint = if (uiState.isPrerollRunning)
                                Color.White
                            else
                                MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                RecordingFab(uiState = uiState, viewModel = viewModel, onRequestPermission = onRequestPermission)
            }
        }
    }

    // AddManualDetectionDialog (T72)
    if (showAddManualDialog) {
        AddManualDetectionDialog(
            onSearchSpecies = { query -> viewModel.searchSpecies(query) },
            atlasCodes = viewModel.atlasCodes,
            currentLat = uiState.currentLatitude,
            currentLon = uiState.currentLongitude,
            onAdd = { speciesKey, count, atlasCode, note, lat, lon ->
                viewModel.addManualDetection(speciesKey, count, atlasCode, note, lat, lon)
                showAddManualDialog = false
            },
            onDismiss = { showAddManualDialog = false }
        )
    }
    if (showMemoDialog) {
        AlertDialog(
            onDismissRequest = { showMemoDialog = false },
            title = { Text(stringResource(R.string.ui_sprach_memo)) },
            text = { Text(stringResource(R.string.live_sprach_memo_funktion_wird_in)) },
            confirmButton = {
                TextButton(onClick = { showMemoDialog = false }) { Text("OK") }
            }
        )
    }
}

// =============================================================================
// Medium Layout (Phone Landscape / kleines Tablet)
// =============================================================================

@Composable
private fun MediumLiveLayout(
    uiState: LiveUiState,
    viewModel: LiveViewModel,
    onRequestPermission: () -> Unit,
    onRequestLocationPermission: () -> Unit = {},
    compact: Boolean = false  // T174: niedrige Fensterhoehe (Telefon quer)
) {
    var showAddManualDialog by remember { mutableStateOf(false) }
    var showMemoDialog by remember { mutableStateOf(false) }

    val similarity: @Composable () -> Unit = {
            // Aehnlichkeitssuche (T12b)
            if (uiState.similarMatches.isNotEmpty() || uiState.isEmbeddingAvailable) {
                Spacer(modifier = Modifier.height(if (compact) 2.dp else 8.dp))
                SimilarityPanel(
                    matches = uiState.similarMatches,
                    isEmbeddingAvailable = uiState.isEmbeddingAvailable,
                    similarEnabled = uiState.similarEnabled,
                    similarLines = uiState.similarLines,
                    recentSpecies = uiState.recentSpecies,
                    collapsible = compact,
                    modifier = Modifier.fillMaxWidth()
                )
            }
    }
    val fabRow: @Composable () -> Unit = {
            // FAB-Bereich: Memo + +Art (nur bei Recording) + Preroll-Toggle (F5) + Haupt-FAB
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (compact) 0.dp else 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Memo-Button + +Art-Button (F6): nur sichtbar wenn Recording aktiv
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Memo-Button
                    AnimatedVisibility(visible = uiState.isRecording) {
                        IconButton(
                            onClick = { showMemoDialog = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = stringResource(R.string.ui_memo_aufnehmen),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    // +Art-Button
                    AnimatedVisibility(visible = uiState.isRecording) {
                        IconButton(
                            onClick = { showAddManualDialog = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddCircle,
                                contentDescription = stringResource(R.string.analysis_art),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Preroll-Toggle (F5) + Haupt-FAB
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Preroll-Toggle (F5): nur sichtbar wenn Preroll in Settings aktiviert und keine Aufnahme
                    if (uiState.isPrerollEnabled && !uiState.isRecording) {
                        SmallFloatingActionButton(
                            onClick = {
                                if (uiState.isPrerollRunning) viewModel.userStopPreroll()
                                else viewModel.userStartPreroll()
                            },
                            containerColor = if (uiState.isPrerollRunning)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(
                                imageVector = if (uiState.isPrerollRunning)
                                    Icons.Filled.Stop
                                else
                                    Icons.Filled.Mic,
                                contentDescription = if (uiState.isPrerollRunning) stringResource(R.string.live_preroll_stoppen) else stringResource(R.string.live_preroll_starten),
                                tint = if (uiState.isPrerollRunning)
                                    Color.White
                                else
                                    MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    RecordingFab(uiState = uiState, viewModel = viewModel, onRequestPermission = onRequestPermission)
                }
            }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = if (compact) 8.dp else 16.dp, vertical = if (compact) 4.dp else 16.dp)
    ) {
        // Links: Sonogramm (60%)
        Column(
            modifier = Modifier
                .weight(if (compact) 0.55f else 0.6f)
                .fillMaxHeight()
                .padding(end = 8.dp)
        ) {
            // T174: Telefon quer ohne Titelzeile (Laufzeit in Status-Zeile, Palette per Langdruck)
            if (!compact) {
                LiveHeader(uiState = uiState, viewModel = viewModel)
                Spacer(modifier = Modifier.height(4.dp))
            }

            ScanStatusRow(uiState = uiState, viewModel = viewModel)
            if (!uiState.isRecording) SessionNoteField(viewModel)

            LocationBar(uiState = uiState, onRequestLocationPermission = onRequestLocationPermission)

            Spacer(modifier = Modifier.height(4.dp))

            SpectrogramWithPanel(
                uiState = uiState,
                fixedHeight = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            PrerollIndicator(uiState = uiState)

            // T174: kompakt: Aehnliche Arten (eingeklappt) und Knopfzeile unter dem Sonogramm,
            // damit die rechte Spalte komplett den Artkarten gehoert
            if (compact) {
                similarity()
                fabRow()
            }
        }

        // Rechts: Detektionsliste + Controls (40%)
        Column(
            modifier = Modifier
                .weight(if (compact) 0.45f else 0.4f)
                .fillMaxHeight()
                .padding(start = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            DetectionList(
                detectionState = uiState.detectionListState,
                primaryModelLabel = ch.etasystems.pirol.ml.SecondaryCheck.modelLabel(
                    org.koin.compose.koinInject<ch.etasystems.pirol.ml.AudioClassifier>().modelId
                ),  // T224
                isModelAvailable = uiState.isModelAvailable,
                onConfirm = { id -> viewModel.verifyDetection(id, VerificationStatus.CONFIRMED) },
                onMarkUncertain = { id -> viewModel.verifyDetection(id, VerificationStatus.UNCERTAIN) },
                onReject = { id -> viewModel.verifyDetection(id, VerificationStatus.REJECTED) },
                onCorrect = { id, species -> viewModel.verifyDetection(id, VerificationStatus.CORRECTED, species) },
                onSaveAsReference = { id -> viewModel.saveAsReference(id) },
                referencedIds = viewModel.referencedDetectionIds.collectAsState().value,  // T127
                onShare = if (uiState.isRecording || uiState.lastSessionId != null) { id -> viewModel.requestShare(id) } else null,  // T116/T128: auch nach Stopp (letzte Session)
                onSelectAlternative = { id, candidate -> viewModel.selectAlternative(id, candidate) },
                onEditDetection = { id, count, atlasCode, note, memoFile -> viewModel.editDetection(id, count, atlasCode, note, memoFile) },
                atlasCodes = viewModel.atlasCodes,
                watchlistSpecies = uiState.watchlistSpecies,
                showTopNCandidates = uiState.showTopNCandidates,
                // T69: Voice-Memo
                voiceMemoRecorder = viewModel.voiceMemoRecorder,
                memoAudioPlayer = viewModel.memoAudioPlayer,
                onGetMemoTargetFile = { id -> viewModel.getMemoTargetFile(id) },
                onGetMemoTargetUri = { id -> viewModel.getMemoTargetUri(id) },
                // T73: Lock-Species
                onToggleLock = { id -> viewModel.toggleLock(id) },
                // T164: Ausschnitt anhoeren
                onPlay = { id -> viewModel.toggleDetectionPlayback(id) },
                playingDetectionId = viewModel.playingDetectionId.collectAsState().value,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            if (!compact) {
                similarity()
                fabRow()
            }
        }
    }

    // AddManualDetectionDialog (T72)
    if (showAddManualDialog) {
        AddManualDetectionDialog(
            onSearchSpecies = { query -> viewModel.searchSpecies(query) },
            atlasCodes = viewModel.atlasCodes,
            currentLat = uiState.currentLatitude,
            currentLon = uiState.currentLongitude,
            onAdd = { speciesKey, count, atlasCode, note, lat, lon ->
                viewModel.addManualDetection(speciesKey, count, atlasCode, note, lat, lon)
                showAddManualDialog = false
            },
            onDismiss = { showAddManualDialog = false }
        )
    }
    if (showMemoDialog) {
        AlertDialog(
            onDismissRequest = { showMemoDialog = false },
            title = { Text(stringResource(R.string.ui_sprach_memo)) },
            text = { Text(stringResource(R.string.live_sprach_memo_funktion_wird_in)) },
            confirmButton = {
                TextButton(onClick = { showMemoDialog = false }) { Text("OK") }
            }
        )
    }
}

// =============================================================================
// Expanded Layout (Tablet Dual-Pane)
// =============================================================================

@Composable
private fun ExpandedLiveLayout(
    uiState: LiveUiState,
    viewModel: LiveViewModel,
    onRequestPermission: () -> Unit,
    onRequestLocationPermission: () -> Unit = {}
) {
    var showAddManualDialog by remember { mutableStateOf(false) }
    var showMemoDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Links: Sonogramm, volle Hoehe (60%)
        Column(
            modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight()
                .padding(end = 12.dp)
        ) {
            LiveHeader(uiState = uiState, viewModel = viewModel)

            Spacer(modifier = Modifier.height(4.dp))

            ScanStatusRow(uiState = uiState, viewModel = viewModel)
            if (!uiState.isRecording) SessionNoteField(viewModel)

            LocationBar(uiState = uiState, onRequestLocationPermission = onRequestLocationPermission)

            Spacer(modifier = Modifier.height(4.dp))

            SpectrogramWithPanel(
                uiState = uiState,
                fixedHeight = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            PrerollIndicator(uiState = uiState)
        }

        // Rechts: Detektionsliste + Aufnahme-Controls (40%)
        Column(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxHeight()
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            DetectionList(
                detectionState = uiState.detectionListState,
                primaryModelLabel = ch.etasystems.pirol.ml.SecondaryCheck.modelLabel(
                    org.koin.compose.koinInject<ch.etasystems.pirol.ml.AudioClassifier>().modelId
                ),  // T224
                isModelAvailable = uiState.isModelAvailable,
                onConfirm = { id -> viewModel.verifyDetection(id, VerificationStatus.CONFIRMED) },
                onMarkUncertain = { id -> viewModel.verifyDetection(id, VerificationStatus.UNCERTAIN) },
                onReject = { id -> viewModel.verifyDetection(id, VerificationStatus.REJECTED) },
                onCorrect = { id, species -> viewModel.verifyDetection(id, VerificationStatus.CORRECTED, species) },
                onSaveAsReference = { id -> viewModel.saveAsReference(id) },
                referencedIds = viewModel.referencedDetectionIds.collectAsState().value,  // T127
                onShare = if (uiState.isRecording || uiState.lastSessionId != null) { id -> viewModel.requestShare(id) } else null,  // T116/T128: auch nach Stopp (letzte Session)
                onSelectAlternative = { id, candidate -> viewModel.selectAlternative(id, candidate) },
                onEditDetection = { id, count, atlasCode, note, memoFile -> viewModel.editDetection(id, count, atlasCode, note, memoFile) },
                atlasCodes = viewModel.atlasCodes,
                watchlistSpecies = uiState.watchlistSpecies,
                showTopNCandidates = uiState.showTopNCandidates,
                // T69: Voice-Memo
                voiceMemoRecorder = viewModel.voiceMemoRecorder,
                memoAudioPlayer = viewModel.memoAudioPlayer,
                onGetMemoTargetFile = { id -> viewModel.getMemoTargetFile(id) },
                onGetMemoTargetUri = { id -> viewModel.getMemoTargetUri(id) },
                // T73: Lock-Species
                onToggleLock = { id -> viewModel.toggleLock(id) },
                // T164: Ausschnitt anhoeren
                onPlay = { id -> viewModel.toggleDetectionPlayback(id) },
                playingDetectionId = viewModel.playingDetectionId.collectAsState().value,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            // Aehnlichkeitssuche (T12b)
            if (uiState.similarMatches.isNotEmpty() || uiState.isEmbeddingAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                SimilarityPanel(
                    matches = uiState.similarMatches,
                    isEmbeddingAvailable = uiState.isEmbeddingAvailable,
                    similarEnabled = uiState.similarEnabled,
                    similarLines = uiState.similarLines,
                    recentSpecies = uiState.recentSpecies,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // FAB-Bereich: Memo + +Art (nur bei Recording) + Preroll-Toggle (F5) + Haupt-FAB (unten rechts im rechten Pane)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Memo-Button + +Art-Button (F6): nur sichtbar wenn Recording aktiv
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Memo-Button
                    AnimatedVisibility(visible = uiState.isRecording) {
                        IconButton(
                            onClick = { showMemoDialog = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = stringResource(R.string.ui_memo_aufnehmen),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    // +Art-Button
                    AnimatedVisibility(visible = uiState.isRecording) {
                        IconButton(
                            onClick = { showAddManualDialog = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddCircle,
                                contentDescription = stringResource(R.string.analysis_art),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Preroll-Toggle (F5) + Haupt-FAB
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Preroll-Toggle (F5): nur sichtbar wenn Preroll in Settings aktiviert und keine Aufnahme
                    if (uiState.isPrerollEnabled && !uiState.isRecording) {
                        SmallFloatingActionButton(
                            onClick = {
                                if (uiState.isPrerollRunning) viewModel.userStopPreroll()
                                else viewModel.userStartPreroll()
                            },
                            containerColor = if (uiState.isPrerollRunning)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(
                                imageVector = if (uiState.isPrerollRunning)
                                    Icons.Filled.Stop
                                else
                                    Icons.Filled.Mic,
                                contentDescription = if (uiState.isPrerollRunning) stringResource(R.string.live_preroll_stoppen) else stringResource(R.string.live_preroll_starten),
                                tint = if (uiState.isPrerollRunning)
                                    Color.White
                                else
                                    MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    RecordingFab(uiState = uiState, viewModel = viewModel, onRequestPermission = onRequestPermission)
                }
            }
        }
    }

    // AddManualDetectionDialog (T72)
    if (showAddManualDialog) {
        AddManualDetectionDialog(
            onSearchSpecies = { query -> viewModel.searchSpecies(query) },
            atlasCodes = viewModel.atlasCodes,
            currentLat = uiState.currentLatitude,
            currentLon = uiState.currentLongitude,
            onAdd = { speciesKey, count, atlasCode, note, lat, lon ->
                viewModel.addManualDetection(speciesKey, count, atlasCode, note, lat, lon)
                showAddManualDialog = false
            },
            onDismiss = { showAddManualDialog = false }
        )
    }
    if (showMemoDialog) {
        AlertDialog(
            onDismissRequest = { showMemoDialog = false },
            title = { Text(stringResource(R.string.ui_sprach_memo)) },
            text = { Text(stringResource(R.string.live_sprach_memo_funktion_wird_in)) },
            confirmButton = {
                TextButton(onClick = { showMemoDialog = false }) { Text("OK") }
            }
        )
    }
}

// =============================================================================
// Shared Composables
// =============================================================================

/**
 * Header-Zeile mit Titel, Palette-Button und Aufnahme-Timer.
 */
@Composable
private fun LiveHeader(
    uiState: LiveUiState,
    viewModel: LiveViewModel
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.live_live_aufnahme),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Palette-Umschaltung
            val specRepo: SpectrogramSettingsRepository = koinInject()
            val palette = specRepo.settings.collectAsState().value.palette
            IconButton(onClick = {
                val next = when (palette) {
                    SpectrogramPalette.MAGMA -> SpectrogramPalette.VIRIDIS
                    SpectrogramPalette.VIRIDIS -> SpectrogramPalette.GRAYSCALE
                    SpectrogramPalette.GRAYSCALE -> SpectrogramPalette.PAPER
                    SpectrogramPalette.PAPER -> SpectrogramPalette.MAGMA
                }
                specRepo.update { it.copy(palette = next) }
            }) {
                Icon(
                    Icons.Filled.Palette,
                    contentDescription = stringResource(R.string.live_farbpalette_wechseln, stringResource(palette.labelRes())),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Aufnahme-Timer (Chronometer)
            if (uiState.isRecording) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFD32F2F))
                )
                Spacer(modifier = Modifier.width(6.dp))
                val chronometerBase = uiState.recordingStartElapsedRealtime
                AndroidView(
                    factory = { ctx ->
                        Chronometer(ctx).apply {
                            base = chronometerBase
                            start()
                        }
                    },
                    update = { chronometer ->
                        chronometer.base = chronometerBase
                    }
                )
            }
        }
    }
}

/**
 * T109: Optionale Bemerkung zur naechsten Session (vorbelegt mit der zuletzt verwendeten).
 * Nur vor Aufnahmestart sichtbar; Leeren per Clear-Icon.
 */
@Composable
private fun SessionNoteField(viewModel: LiveViewModel) {
    val note by viewModel.sessionNote.collectAsState()
    val locationProvider: ch.etasystems.pirol.location.LocationProvider = koinInject()
    // T197: Gemeinde-Knopf neben dem Feld
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    androidx.compose.material3.OutlinedTextField(
        value = note,
        onValueChange = { viewModel.setSessionNote(it) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        placeholder = { Text(stringResource(R.string.live_bemerkung_zur_session_optional), style = MaterialTheme.typography.bodySmall) },
        trailingIcon = if (note.isNotEmpty()) {
            {
                IconButton(onClick = { viewModel.setSessionNote("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.live_bemerkung_leeren))
                }
            }
        } else null,
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
    ch.etasystems.pirol.ui.components.MunicipalityInsertButton(
        position = { locationProvider.location.value?.let { it.latitude to it.longitude } },
        note = note,
        maxLength = 200,
        onNoteChange = { viewModel.setSessionNote(it) },
        iconOnly = true,
        ensureFix = { locationProvider.ensureFix() },
    )
    }
}

/**
 * Kompakte Status-Zeile (T30): Aktive Config als Text + Export-Buttons.
 * Ersetzt die alten ConfigChipRow + InferenceConfigRow.
 */
@Composable
private fun ScanStatusRow(
    uiState: LiveUiState,
    viewModel: LiveViewModel
) {
    val configLabel = when (uiState.spectrogramConfig) {
        SpectrogramConfig.BIRDS -> AppStrings.get(R.string.live_voegel)
        SpectrogramConfig.BATS -> AppStrings.get(R.string.live_fledermaus)
        SpectrogramConfig.WIDEBAND -> AppStrings.get(R.string.live_breitband)
        else -> AppStrings.get(R.string.live_voegel)
    }
    val regionLabel = when (uiState.inferenceConfig.regionFilter) {
        "ch_breeding" -> "CH"
        "ch_all" -> "CH+"
        "central_europe" -> "EU"
        "all" -> AppStrings.get(R.string.common_all)
        "auto_geo" -> AppStrings.get(R.string.live_region_auto)  // T218
        null -> AppStrings.get(R.string.common_off)
        else -> uiState.inferenceConfig.regionFilter ?: "?"
    }

    // T52: Session-Laufzeit als mm:ss, 1-Hz-Ticker
    var elapsedSecs by remember { mutableStateOf(0L) }
    LaunchedEffect(uiState.isRecording, uiState.recordingStartElapsedRealtime) {
        if (!uiState.isRecording) { elapsedSecs = 0L; return@LaunchedEffect }
        while (true) {
            elapsedSecs = (SystemClock.elapsedRealtime() - uiState.recordingStartElapsedRealtime) / 1000L
            kotlinx.coroutines.delay(1000L)
        }
    }
    val modelLabel = koinInject<ch.etasystems.pirol.ml.AudioClassifier>().modelName  // T122
    val sessionTimeLabel = if (uiState.isRecording) {
        " · %02d:%02d".format(elapsedSecs / 60L, elapsedSecs % 60L)
    } else ""

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            // T122: aktives Modell anzeigen (Feldtest: unklar, welches Modell laeuft)
            text = "${modelLabel} · $configLabel · ${(uiState.inferenceConfig.confidenceThreshold * 100).roundToInt()}% · $regionLabel$sessionTimeLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )

        // Upload-Button (T17): nur sichtbar nach abgeschlossener Session
        if (uiState.lastSessionId != null && !uiState.isRecording) {
            IconButton(onClick = { viewModel.uploadLastSession() }) {
                if (uiState.uploadStatus is UploadStatus.InProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Upload,
                        contentDescription = stringResource(R.string.live_session_exportieren),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * T107: Sonogramm + (nach Langdruck) Einstellungs-Panel DARUNTER. Das Sonogramm bleibt sichtbar,
 * Aenderungen wirken live. Auto-Kontrast-Parameter werden hier an den Mapper weitergereicht.
 */
@Composable
private fun SpectrogramWithPanel(
    uiState: LiveUiState,
    fixedHeight: androidx.compose.ui.unit.Dp?,
    modifier: Modifier = Modifier
) {
    val repo: SpectrogramSettingsRepository = koinInject()
    val settings by repo.settings.collectAsState()
    val spectrumSettings by repo.spectrum.collectAsState()  // T198
    val waveformSettings by repo.waveform.collectAsState()  // T200
    var showPanel by remember { mutableStateOf(false) }
    // T200: Frequenzgrenzen bis Nyquist der tatsaechlichen Abtastrate (Regler, Achse, Bin-Zuschnitt)
    val liveRate = if (uiState.actualSampleRate > 0) uiState.actualSampleRate else 48000
    val liveConfig = uiState.spectrogramConfig.resolved(liveRate)

    // Perzentil-Paar/Mindestspanne in den Rolling-Mapper uebernehmen (sofort wirksam)
    LaunchedEffect(settings.autoLowPct, settings.autoHighPct, settings.autoMinSpanDb) {
        uiState.spectrogramState.dynamicRangeMapper?.configure(
            settings.autoLowPct, settings.autoHighPct, settings.autoMinSpanDb
        )
    }

    Column(modifier = modifier) {
        SpectrogramBox(
            uiState = uiState,
            settings = settings,
            spectrumSettings = spectrumSettings,
            waveformSettings = waveformSettings,
            onLongPress = { showPanel = true },
            modifier = if (fixedHeight != null) {
                Modifier.fillMaxWidth().height(fixedHeight)
            } else {
                Modifier.fillMaxWidth().weight(1f)
            }
        )
        if (showPanel && settings.displayMode == LiveDisplayMode.SPECTRUM) {
            // T198: eigenes Panel fuer das FFT-Spektrum
            Spacer(modifier = Modifier.height(4.dp))
            SpectrumSettingsPanel(
                repository = repo,
                displaySettings = settings,
                settings = spectrumSettings,
                config = liveConfig,
                sampleRate = uiState.spectrumState.sampleRate,
                onResetPeak = { uiState.spectrumState.resetPeak() },
                onClose = { showPanel = false }
            )
        } else if (showPanel && settings.displayMode == LiveDisplayMode.WAVEFORM) {
            // T200: eigenes Panel fuer das Oszillogramm
            Spacer(modifier = Modifier.height(4.dp))
            ch.etasystems.pirol.ui.components.WaveformSettingsPanel(
                repository = repo,
                displaySettings = settings,
                settings = waveformSettings,
                config = liveConfig,
                onClose = { showPanel = false }
            )
        } else if (showPanel) {
            Spacer(modifier = Modifier.height(4.dp))
            SpectrogramSettingsPanel(
                repository = repo,
                settings = settings,
                config = liveConfig,
                showWindow = true,
                showDisplayMode = true,
                onClose = { showPanel = false }
            )
        }
    }
}

/**
 * Sonogramm-Box mit Overlay oder Platzhalter-Text.
 */
@Composable
private fun SpectrogramBox(
    uiState: LiveUiState,
    settings: SpectrogramDisplaySettings,
    spectrumSettings: ch.etasystems.pirol.ui.components.SpectrumSettings,
    waveformSettings: ch.etasystems.pirol.ui.components.WaveformSettings,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    // T107: Frames im Zeitfenster (Frame-Rate = Samplerate / Hop), max. Puffergroesse
    val sampleRate = if (uiState.actualSampleRate > 0) uiState.actualSampleRate else 48000
    val windowFrames = (settings.windowSec * sampleRate / uiState.spectrogramConfig.hopSize).toInt()
        .coerceIn(64, uiState.spectrogramState.maxFrames)
    // T200: Mel-Config mit aufgeloester Obergrenze (Nyquist der tatsaechlichen Rate)
    val config = uiState.spectrogramConfig.resolved(sampleRate)
    // T200: gemeinsamer Frequenzbereich wirkt als Bandpass auf das Oszillogramm
    val (bandLo, bandHi) = ch.etasystems.pirol.ui.components.sharedFreqRange(settings, config, sampleRate)
    LaunchedEffect(bandLo, bandHi) { uiState.waveformState.setBand(bandLo, bandHi) }
    // T193: Oszillogramm-Puffer nur fuellen, solange dieser Modus gewaehlt ist
    LaunchedEffect(settings.displayMode) {
        uiState.waveformState.setActive(settings.displayMode == LiveDisplayMode.WAVEFORM)
        uiState.spectrumState.setActive(settings.displayMode == LiveDisplayMode.SPECTRUM)  // T198
    }
    // T198: FFT-Groesse/Mittelung uebernehmen; Peak-Hold startet beim Einschalten frisch
    LaunchedEffect(spectrumSettings.fftSize, spectrumSettings.averaging) {
        uiState.spectrumState.configure(spectrumSettings.fftSize, spectrumSettings.averaging.tauSec)
    }
    LaunchedEffect(spectrumSettings.traceSec) {  // T201
        uiState.spectrumState.setTraceSeconds(spectrumSettings.traceSec)
    }
    LaunchedEffect(spectrumSettings.peakHold) {
        if (spectrumSettings.peakHold) uiState.spectrumState.resetPeak()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (settings.palette.isLight) Color.White else Color.Black)
            .onSpectrogramLongPress(onLongPress)
    ) {
        // T176: beobachtbarer State statt availableFrames (nicht beobachtbar → Platzhalter blieb stehen)
        val modeHasData = when (settings.displayMode) {
            LiveDisplayMode.WAVEFORM -> uiState.waveformState.hasData
            LiveDisplayMode.SPECTRUM -> uiState.spectrumState.hasData
            else -> uiState.spectrogramState.hasFrames
        }
        if (modeHasData && settings.displayMode == LiveDisplayMode.SPECTRUM) {
            SpectrumView(
                spectrumState = uiState.spectrumState,
                settings = spectrumSettings,
                displaySettings = settings,
                config = config,
                onLongPress = onLongPress,
                modifier = Modifier.fillMaxSize()
            )
        } else if (modeHasData && settings.displayMode == LiveDisplayMode.WAVEFORM) {
            WaveformView(
                waveformState = uiState.waveformState,
                windowSec = settings.windowSec,
                sampleRate = sampleRate,
                settings = settings,
                waveformSettings = waveformSettings,
                sonoAutoRange = { uiState.spectrogramState.dynamicRangeMapper?.currentRange() },
                onLongPress = onLongPress,
                modifier = Modifier.fillMaxSize()
            )
        } else if (modeHasData) {
            SpectrogramOverlay(
                config = config.forDisplay(settings.freqMinHz, settings.freqMaxHz),
                spectrogramState = uiState.spectrogramState,
                sampleRate = sampleRate,
                windowFrames = windowFrames,
                lightBackground = settings.palette.isLight
            ) {
                SpectrogramCanvas(
                    spectrogramState = uiState.spectrogramState,
                    config = config,
                    palette = settings.palette,
                    autoContrast = settings.autoContrast,
                    manualMinDb = settings.minDb,
                    manualMaxDb = settings.maxDb,
                    gamma = settings.gamma,
                    ceilingDb = settings.ceilingDb,
                    freqMinHz = settings.freqMinHz,
                    freqMaxHz = settings.freqMaxHz,
                    windowFrames = windowFrames,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        uiState.isRecording -> stringResource(R.string.live_sonogramm_wird_geladen)
                        uiState.isServiceBound -> stringResource(R.string.live_bereit_aufnahme_starten)
                        else -> stringResource(R.string.live_sonogramm_aufnahme_starten)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
        // T229: Schnellknopf Artkarte kompakt/ausfuehrlich
        ch.etasystems.pirol.ui.components.CompactCardToggleButton(Modifier.align(Alignment.BottomEnd).padding(bottom = 20.dp))
    }
}

/**
 * Preroll-Indikator: zeigt Buffer-Status an.
 * - Service gebunden, nicht recording: "Buffer aktiv — letzte 30s verfuegbar"
 * - Recording: zeigt Preroll-Hinweis
 */
@Composable
private fun PrerollIndicator(uiState: LiveUiState) {
    when {
        uiState.isPrerollRunning && !uiState.isRecording -> {
            Text(
                text = stringResource(R.string.live_buffer_aktiv_letzte_30s_verfuegbar),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
        uiState.isRecording -> {
            Text(
                text = stringResource(R.string.live_live_aufnahme_preroll_buffer_gesiche),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFD32F2F).copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
    }
}

/**
 * Recording-FAB mit polished States:
 * IDLE: Mic, primary, enabled
 * CONNECTING: Mic, ausgegraut, disabled
 * READY: Mic, primary, enabled
 * RECORDING: Stop, rot, pulsierender Schatten
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingFab(
    uiState: LiveUiState,
    viewModel: LiveViewModel,
    onRequestPermission: () -> Unit
) {
    val fabState = uiState.fabState

    val fabColor by animateColorAsState(
        targetValue = when (fabState) {
            // T57-B4: PREROLL = gruen (Buffer laeuft), RECORDING = rot (scharf)
            RecordingFabState.RECORDING        -> Color(0xFFC62828)  // MaterialRed 800
            RecordingFabState.PREROLL_BUFFERING -> Color(0xFF2E7D32) // MaterialGreen 800
            RecordingFabState.CONNECTING       -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.primary
        },
        label = "fabColor"
    )

    val fabIcon = when (fabState) {
        RecordingFabState.RECORDING -> Icons.Filled.Stop
        else -> Icons.Filled.Mic
    }

    val fabDescription = when (fabState) {
        RecordingFabState.RECORDING        -> AppStrings.get(R.string.ui_aufnahme_stoppen)
        RecordingFabState.PREROLL_BUFFERING -> AppStrings.get(R.string.live_preroll)
        RecordingFabState.CONNECTING       -> AppStrings.get(R.string.live_verbinde)
        else -> AppStrings.get(R.string.live_aufnahme_starten)
    }

    // T52: PREROLL_BUFFERING und CONNECTING deaktivieren (kein Tap moeglich)
    val enabled = fabState != RecordingFabState.CONNECTING &&
                  fabState != RecordingFabState.PREROLL_BUFFERING

    // T163: Statische Elevation und langsam umschaltender Ring. Frueher liefen zwei Infinite-Transitions
    // mit voller Bildrate (Recomposition + Schatten-Neuzeichnen pro Frame, ~100 % CPU-Kern).
    val pulseElevation = 6.dp

    // Aufnahme-Hinweis (T57-B4: rot): Zustand wechselt nur ca. 1x pro Sekunde und wird erst in der
    // Draw-Phase gelesen (drawBehind), damit die Komposition nicht invalidiert wird.
    var ringOn by remember { mutableStateOf(true) }
    val isRecordingFab = fabState == RecordingFabState.RECORDING
    LaunchedEffect(isRecordingFab) {
        ringOn = true
        while (isRecordingFab) {
            kotlinx.coroutines.delay(1000L)
            ringOn = !ringOn
        }
    }
    val ringModifier = if (isRecordingFab) {
        Modifier.drawBehind {
            val strokePx = 3.dp.toPx()
            drawCircle(
                color = Color(0xFFC62828).copy(alpha = if (ringOn) 0.6f else 0.15f),
                radius = (size.minDimension - strokePx) / 2f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokePx)
            )
        }
    } else {
        Modifier
    }

    Box(modifier = ringModifier.padding(4.dp)) {
        if (fabState == RecordingFabState.RECORDING) {
            // F7: Stop nur per Long-Press (verhindert versehentliches Stoppen)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .shadow(pulseElevation, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(fabColor)
                    .combinedClickable(
                        onClick = {
                            if (viewModel.onFabTap()) viewModel.showLongPressHint()
                        },
                        onLongClick = { viewModel.stopRecording() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.live_aufnahme_stoppen_lang_druecken),
                    tint = Color.White
                )
            }
        } else {
            FloatingActionButton(
                onClick = {
                    // T52: 500ms Debounce-Gate im ViewModel
                    if (viewModel.onFabTap()) {
                        when (fabState) {
                            RecordingFabState.IDLE, RecordingFabState.READY -> {
                                if (uiState.permissionGranted) {
                                    viewModel.startRecording()
                                } else {
                                    onRequestPermission()
                                }
                            }
                            else -> { /* CONNECTING oder PREROLL_BUFFERING: disabled */ }
                        }
                    }
                },
                containerColor = fabColor,
                modifier = Modifier.shadow(pulseElevation, CircleShape, clip = false),
                shape = CircleShape
            ) {
                // T52: PREROLL_BUFFERING zeigt Mic + indeterminate Progress-Ring
                if (fabState == RecordingFabState.PREROLL_BUFFERING) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            fabIcon,
                            contentDescription = fabDescription,
                            tint = Color.White
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.size(44.dp),
                            color = Color.White.copy(alpha = 0.7f),
                            strokeWidth = 3.dp
                        )
                    }
                } else {
                    Icon(
                        fabIcon,
                        contentDescription = fabDescription,
                        tint = if (fabState == RecordingFabState.CONNECTING) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        } else {
                            // T169: Hintergrund ist colorScheme.primary (dynamische Farben koennen fast weiss sein)
                            MaterialTheme.colorScheme.onPrimary
                        }
                    )
                }
            }
        }
    }
}

/**
 * GPS-Koordinaten-Anzeige mit Fix-Alter und Permission-Tap (T52).
 * Tap oeffnet Location-Permission-Dialog falls Permission nicht erteilt.
 */
@Composable
private fun LocationBar(
    uiState: LiveUiState,
    onRequestLocationPermission: () -> Unit = {}
) {
    // T52: Fix-Alter live berechnen (1-Hz-Ticker)
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000L)
        }
    }
    val fixAgeSeconds = uiState.lastLocationFixMs?.let { (nowMs - it) / 1000L }
    val fixAgeColor = if (fixAgeSeconds == null || fixAgeSeconds > 30L)
        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

    if (uiState.isLocationAvailable && uiState.currentLatitude != null && uiState.currentLongitude != null) {
        Row(
            modifier = Modifier
                .padding(start = 4.dp, top = 2.dp)
                .clickable { if (!uiState.locationPermissionGranted) onRequestLocationPermission() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = String.format(
                    "\uD83D\uDCCD %.4f\u00B0 N, %.4f\u00B0 E \u00B1%.0fm",
                    uiState.currentLatitude, uiState.currentLongitude,
                    uiState.locationAccuracyM ?: 0f
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (fixAgeSeconds != null) {
                Text(
                    text = stringResource(R.string.live_fix_vor_s, fixAgeSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = fixAgeColor
                )
            }
        }
    } else {
        Text(
            text = stringResource(R.string.live_gps_nicht_verfuegbar),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .padding(start = 4.dp, top = 2.dp)
                .clickable { onRequestLocationPermission() }
        )
    }
}

