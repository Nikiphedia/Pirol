package ch.etasystems.pirol.ui.live

import android.widget.Chronometer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
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
import ch.etasystems.pirol.ui.components.SpectrogramCanvas
import ch.etasystems.pirol.ui.components.SpectrogramOverlay
import ch.etasystems.pirol.ui.components.SpectrogramPalette
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

@Composable
fun LiveScreen(
    widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
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

    // --- T56: Sonogramm-Dynamik-Prefs bei ON_RESUME neu einlesen.
    // Greift wenn der Nutzer von Settings zurueck zum Live-Tab wechselt — ohne App-Neustart.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadSpectrogramPrefs()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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
        }
    )

    // Location-Permission beim ersten Composable-Aufbau anfragen
    LaunchedEffect(Unit) {
        if (!areLocationPermissionsGranted(context)) {
            locationPermissionTrigger = true
        }
    }

    // --- Snackbar-Infrastruktur (T52) ---
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { event ->
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

            when (widthSizeClass) {
                WindowWidthSizeClass.Expanded -> {
                    // Tablet: Dual-Pane nebeneinander
                    ExpandedLiveLayout(
                        uiState = uiState,
                        viewModel = viewModel,
                        onRequestPermission = { permissionTrigger = true }
                    )
                }
                WindowWidthSizeClass.Medium -> {
                    // Phone Landscape / kleines Tablet: gewichtetes Layout
                    MediumLiveLayout(
                        uiState = uiState,
                        viewModel = viewModel,
                        onRequestPermission = { permissionTrigger = true }
                    )
                }
                else -> {
                    // Phone Portrait: Standard-Layout
                    CompactLiveLayout(
                        uiState = uiState,
                        viewModel = viewModel,
                        onRequestPermission = { permissionTrigger = true }
                    )
                }
            }
        }
        // Snackbar (T52): Verifikations-Feedback + Undo
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
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
                text = "Speicherort nicht erreichbar — Aufnahme im Fallback-Ordner gespeichert.",
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
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Header
            LiveHeader(uiState = uiState, viewModel = viewModel)

            Spacer(modifier = Modifier.height(4.dp))

            // Kompakte Status-Zeile (T30) + Export-Buttons
            ScanStatusRow(uiState = uiState, viewModel = viewModel)

            LocationBar(uiState = uiState)

            Spacer(modifier = Modifier.height(8.dp))

            // Sonogramm (200dp)
            SpectrogramBox(
                uiState = uiState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            )

            // Preroll-Indikator
            PrerollIndicator(uiState = uiState)

            Spacer(modifier = Modifier.height(12.dp))

            // Detektionsliste (ersetzt SpeciesPlaceholder)
            DetectionList(
                detectionState = uiState.detectionListState,
                isModelAvailable = uiState.isModelAvailable,
                onConfirm = { id -> viewModel.verifyDetection(id, VerificationStatus.CONFIRMED) },
                onMarkUncertain = { id -> viewModel.verifyDetection(id, VerificationStatus.UNCERTAIN) },
                onReject = { id -> viewModel.verifyDetection(id, VerificationStatus.REJECTED) },
                onCorrect = { id, species -> viewModel.verifyDetection(id, VerificationStatus.CORRECTED, species) },
                onSaveAsReference = { id -> viewModel.saveAsReference(id) },
                onSelectAlternative = { id, candidate -> viewModel.selectAlternative(id, candidate) },
                watchlistSpecies = uiState.watchlistSpecies,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            // Aehnlichkeitssuche (T12b)
            if (uiState.similarMatches.isNotEmpty() || uiState.isEmbeddingAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                SimilarityPanel(
                    matches = uiState.similarMatches,
                    isEmbeddingAvailable = uiState.isEmbeddingAvailable,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // FAB zentriert
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            RecordingFab(uiState = uiState, viewModel = viewModel, onRequestPermission = onRequestPermission)
        }
    }
}

// =============================================================================
// Medium Layout (Phone Landscape / kleines Tablet)
// =============================================================================

@Composable
private fun MediumLiveLayout(
    uiState: LiveUiState,
    viewModel: LiveViewModel,
    onRequestPermission: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // Links: Sonogramm (60%)
        Column(
            modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight()
                .padding(end = 8.dp)
        ) {
            LiveHeader(uiState = uiState, viewModel = viewModel)

            Spacer(modifier = Modifier.height(4.dp))

            ScanStatusRow(uiState = uiState, viewModel = viewModel)

            LocationBar(uiState = uiState)

            Spacer(modifier = Modifier.height(4.dp))

            SpectrogramBox(
                uiState = uiState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            PrerollIndicator(uiState = uiState)
        }

        // Rechts: Detektionsliste + Controls (40%)
        Column(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxHeight()
                .padding(start = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            DetectionList(
                detectionState = uiState.detectionListState,
                isModelAvailable = uiState.isModelAvailable,
                onConfirm = { id -> viewModel.verifyDetection(id, VerificationStatus.CONFIRMED) },
                onMarkUncertain = { id -> viewModel.verifyDetection(id, VerificationStatus.UNCERTAIN) },
                onReject = { id -> viewModel.verifyDetection(id, VerificationStatus.REJECTED) },
                onCorrect = { id, species -> viewModel.verifyDetection(id, VerificationStatus.CORRECTED, species) },
                onSaveAsReference = { id -> viewModel.saveAsReference(id) },
                onSelectAlternative = { id, candidate -> viewModel.selectAlternative(id, candidate) },
                watchlistSpecies = uiState.watchlistSpecies,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            // Aehnlichkeitssuche (T12b)
            if (uiState.similarMatches.isNotEmpty() || uiState.isEmbeddingAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                SimilarityPanel(
                    matches = uiState.similarMatches,
                    isEmbeddingAvailable = uiState.isEmbeddingAvailable,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                RecordingFab(uiState = uiState, viewModel = viewModel, onRequestPermission = onRequestPermission)
            }
        }
    }
}

// =============================================================================
// Expanded Layout (Tablet Dual-Pane)
// =============================================================================

@Composable
private fun ExpandedLiveLayout(
    uiState: LiveUiState,
    viewModel: LiveViewModel,
    onRequestPermission: () -> Unit
) {
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

            LocationBar(uiState = uiState)

            Spacer(modifier = Modifier.height(4.dp))

            SpectrogramBox(
                uiState = uiState,
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
                isModelAvailable = uiState.isModelAvailable,
                onConfirm = { id -> viewModel.verifyDetection(id, VerificationStatus.CONFIRMED) },
                onMarkUncertain = { id -> viewModel.verifyDetection(id, VerificationStatus.UNCERTAIN) },
                onReject = { id -> viewModel.verifyDetection(id, VerificationStatus.REJECTED) },
                onCorrect = { id, species -> viewModel.verifyDetection(id, VerificationStatus.CORRECTED, species) },
                onSaveAsReference = { id -> viewModel.saveAsReference(id) },
                onSelectAlternative = { id, candidate -> viewModel.selectAlternative(id, candidate) },
                watchlistSpecies = uiState.watchlistSpecies,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )

            // Aehnlichkeitssuche (T12b)
            if (uiState.similarMatches.isNotEmpty() || uiState.isEmbeddingAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                SimilarityPanel(
                    matches = uiState.similarMatches,
                    isEmbeddingAvailable = uiState.isEmbeddingAvailable,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // FAB unten rechts im rechten Pane
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                RecordingFab(uiState = uiState, viewModel = viewModel, onRequestPermission = onRequestPermission)
            }
        }
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
            text = "Live-Aufnahme",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Palette-Umschaltung
            IconButton(onClick = {
                val next = when (uiState.palette) {
                    SpectrogramPalette.MAGMA -> SpectrogramPalette.VIRIDIS
                    SpectrogramPalette.VIRIDIS -> SpectrogramPalette.GRAYSCALE
                    SpectrogramPalette.GRAYSCALE -> SpectrogramPalette.MAGMA
                }
                viewModel.setPalette(next)
            }) {
                Icon(
                    Icons.Filled.Palette,
                    contentDescription = "Farbpalette wechseln (${uiState.palette.name})",
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
 * Kompakte Status-Zeile (T30): Aktive Config als Text + Export-Buttons.
 * Ersetzt die alten ConfigChipRow + InferenceConfigRow.
 */
@Composable
private fun ScanStatusRow(
    uiState: LiveUiState,
    viewModel: LiveViewModel
) {
    val configLabel = when (uiState.spectrogramConfig) {
        SpectrogramConfig.BIRDS -> "Voegel"
        SpectrogramConfig.BATS -> "Fledermaus"
        SpectrogramConfig.WIDEBAND -> "Breitband"
        else -> "Voegel"
    }
    val regionLabel = when (uiState.inferenceConfig.regionFilter) {
        "ch_breeding" -> "CH"
        "ch_all" -> "CH+"
        "central_europe" -> "EU"
        "all" -> "Alle"
        null -> "Aus"
        else -> uiState.inferenceConfig.regionFilter ?: "?"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$configLabel · ${(uiState.inferenceConfig.confidenceThreshold * 100).roundToInt()}% · $regionLabel",
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
                        contentDescription = "Session exportieren",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * Sonogramm-Box mit Overlay oder Platzhalter-Text.
 */
@Composable
private fun SpectrogramBox(
    uiState: LiveUiState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
    ) {
        if (uiState.spectrogramState.availableFrames > 0) {
            SpectrogramOverlay(
                config = uiState.spectrogramConfig,
                spectrogramState = uiState.spectrogramState,
                sampleRate = if (uiState.actualSampleRate > 0)
                    uiState.actualSampleRate else 48000
            ) {
                SpectrogramCanvas(
                    spectrogramState = uiState.spectrogramState,
                    config = uiState.spectrogramConfig,
                    palette = uiState.palette,
                    autoContrast = uiState.spectrogramAutoContrast,
                    manualMinDb = uiState.spectrogramMinDb,
                    manualMaxDb = uiState.spectrogramMaxDb,
                    gamma = uiState.spectrogramGamma,
                    ceilingDb = uiState.spectrogramCeilingDb,
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
                        uiState.isRecording -> "Sonogramm wird geladen\u2026"
                        uiState.isServiceBound -> "Bereit \u2014 Aufnahme starten"
                        else -> "Sonogramm \u2014 Aufnahme starten"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
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
        uiState.isServiceBound && !uiState.isRecording -> {
            Text(
                text = "\u25CF Buffer aktiv \u2014 letzte 30s verfuegbar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
        uiState.isRecording -> {
            Text(
                text = "\u25B6 Live-Aufnahme \u2014 Preroll-Buffer gesichert",
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
@Composable
private fun RecordingFab(
    uiState: LiveUiState,
    viewModel: LiveViewModel,
    onRequestPermission: () -> Unit
) {
    val fabState = uiState.fabState

    val fabColor by animateColorAsState(
        targetValue = when (fabState) {
            // T52: RECORDING = gruen (war rot), PREROLL_BUFFERING = blau
            RecordingFabState.RECORDING        -> Color(0xFF2E7D32)
            RecordingFabState.PREROLL_BUFFERING -> Color(0xFF1565C0)
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
        RecordingFabState.RECORDING        -> "Aufnahme stoppen"
        RecordingFabState.PREROLL_BUFFERING -> "Preroll\u2026"
        RecordingFabState.CONNECTING       -> "Verbinde\u2026"
        else -> "Aufnahme starten"
    }

    // T52: PREROLL_BUFFERING und CONNECTING deaktivieren (kein Tap moeglich)
    val enabled = fabState != RecordingFabState.CONNECTING &&
                  fabState != RecordingFabState.PREROLL_BUFFERING

    // Pulsierender Schatten bei Aufnahme
    val pulseElevation = if (fabState == RecordingFabState.RECORDING) {
        val infiniteTransition = rememberInfiniteTransition(label = "fabPulse")
        val pulse by infiniteTransition.animateFloat(
            initialValue = 6f,
            targetValue = 16f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseElevation"
        )
        pulse.dp
    } else {
        6.dp
    }

    // Pulsierender Ring bei aktiver Aufnahme (T52: gruen statt rot)
    val ringModifier = if (fabState == RecordingFabState.RECORDING) {
        val infiniteTransition = rememberInfiniteTransition(label = "fabRing")
        val ringAlpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 0.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Restart
            ),
            label = "ringAlpha"
        )
        Modifier.border(
            width = 3.dp,
            color = Color(0xFF2E7D32).copy(alpha = ringAlpha),
            shape = CircleShape
        )
    } else {
        Modifier
    }

    Box(modifier = ringModifier.padding(4.dp)) {
        FloatingActionButton(
            onClick = {
                // T52: 500ms Debounce-Gate im ViewModel
                if (viewModel.onFabTap()) {
                    when (fabState) {
                        RecordingFabState.RECORDING -> viewModel.stopRecording()
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
                        Color.White
                    }
                )
            }
        }
    }
}

/**
 * GPS-Koordinaten-Anzeige. Zeigt aktuelle Position oder "GPS nicht verfuegbar".
 */
@Composable
private fun LocationBar(uiState: LiveUiState) {
    if (uiState.isLocationAvailable && uiState.currentLatitude != null && uiState.currentLongitude != null) {
        Text(
            text = String.format(
                "\uD83D\uDCCD %.4f\u00B0 N, %.4f\u00B0 E (\u00B1%.0fm)",
                uiState.currentLatitude, uiState.currentLongitude,
                uiState.locationAccuracyM ?: 0f
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    } else {
        Text(
            text = "GPS nicht verfuegbar",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    }
}

