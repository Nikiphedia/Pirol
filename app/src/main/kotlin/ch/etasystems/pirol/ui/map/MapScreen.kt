package ch.etasystems.pirol.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.SecurePreferences
import ch.etasystems.pirol.data.StorageManager
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel = koinViewModel(),
    appPreferences: AppPreferences = koinInject(),
    securePreferences: SecurePreferences = koinInject(),
    storageManager: StorageManager = koinInject()
) {
    val state by viewModel.uiState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Aktive Tile-Source aus Preferences laden (T45)
    var selectedSource by remember {
        mutableStateOf(MapTileSource.fromId(appPreferences.mapTileSourceId))
    }

    // osmdroid Konfiguration initialisieren
    val context = LocalContext.current

    Configuration.getInstance()
        .load(context, context.getSharedPreferences("osmdroid", 0))

    // Tile-Cache-Pfad auf StorageManager-Einstellung setzen (T46, nach load())
    val storagePath = appPreferences.storagePath
    val tileCacheDir = if (storagePath != null) {
        File(storagePath, "tiles")
    } else {
        File(context.filesDir, "tiles")
    }
    if (!tileCacheDir.exists()) tileCacheDir.mkdirs()
    Configuration.getInstance().osmdroidTileCache = tileCacheDir

    // MapView-Referenz fuer Download
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    // Snackbar fuer Download-Abschluss
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.downloadFinishedMessage) {
        state.downloadFinishedMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissFinishedMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // --- Tile-Source FilterChips (T45) ---
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MapTileSource.entries.forEach { source ->
                    FilterChip(
                        selected = source == selectedSource,
                        onClick = {
                            selectedSource = source
                            appPreferences.mapTileSourceId = source.id
                        },
                        label = { Text(source.displayName) },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                }
            }

            // --- Karte ---
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        setMultiTouchControls(true)
                        controller.setZoom(14.0)
                        controller.setCenter(GeoPoint(state.centerLat, state.centerLon))
                        setTileSource(
                            PirolTileSourceFactory.create(
                                selectedSource,
                                securePreferences.swisstopoApiKey
                            )
                        )
                        mapViewRef = this
                    }
                },
                update = { mapView ->
                    mapViewRef = mapView

                    // TileSource wechseln falls noetig
                    val currentName = mapView.tileProvider.tileSource.name()
                    val targetSource = PirolTileSourceFactory.create(
                        selectedSource,
                        securePreferences.swisstopoApiKey
                    )
                    if (currentName != targetSource.name()) {
                        mapView.setTileSource(targetSource)
                    }

                    // Marker aktualisieren
                    mapView.overlays.clear()
                    for (marker in state.markers) {
                        val m = Marker(mapView)
                        m.position = GeoPoint(marker.latitude, marker.longitude)
                        m.title = "${marker.species} (${(marker.confidence * 100).toInt()}%)"
                        m.snippet = marker.sessionId.take(16)
                        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        mapView.overlays.add(m)
                    }
                    mapView.invalidate()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }

        // --- FAB: Offline speichern ---
        FloatingActionButton(
            onClick = { viewModel.showDownloadSheet() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Download, contentDescription = "Offline speichern")
        }

        // --- Snackbar ---
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // --- Download Bottom Sheet ---
        if (state.showDownloadSheet) {
            DownloadBottomSheet(
                mapView = mapViewRef,
                selectedSource = selectedSource,
                apiKey = securePreferences.swisstopoApiKey,
                downloadProgress = downloadProgress,
                estimatedTiles = state.estimatedTiles,
                onEstimate = { mv, bb, zMin, zMax -> viewModel.estimateTiles(mv, bb, zMin, zMax) },
                onStartDownload = { mapView, tileSource, bb, zMin, zMax ->
                    viewModel.startDownload(mapView, tileSource, bb, zMin, zMax, selectedSource.id)
                },
                onCancel = { viewModel.cancelDownload() },
                onDismiss = { viewModel.hideDownloadSheet() }
            )
        }
    }
}

/**
 * Bottom Sheet fuer Offline-Tile-Download-Konfiguration und Fortschritt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadBottomSheet(
    mapView: MapView?,
    selectedSource: MapTileSource,
    apiKey: String,
    downloadProgress: DownloadProgress,
    estimatedTiles: Int,
    onEstimate: (MapView, BoundingBox, Int, Int) -> Unit,
    onStartDownload: (MapView, org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase, BoundingBox, Int, Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Aktuelle Zoom-Stufe der Karte als Default
    val currentZoom = mapView?.zoomLevelDouble?.toInt() ?: 10
    var zoomMin by remember { mutableFloatStateOf(currentZoom.toFloat()) }
    var zoomMax by remember { mutableFloatStateOf((currentZoom + 3).coerceAtMost(18).toFloat()) }

    // Bounding Box der aktuellen Kartenansicht
    val boundingBox = mapView?.boundingBox

    // Tile-Schaetzung bei Aenderung aktualisieren
    LaunchedEffect(boundingBox, zoomMin, zoomMax) {
        if (mapView != null && boundingBox != null) {
            onEstimate(mapView, boundingBox, zoomMin.roundToInt(), zoomMax.roundToInt())
        }
    }

    // Speicherplatz-Schaetzung (Richtwerte: OSM ~15KB, swisstopo ~25KB)
    val avgTileSizeKb = when (selectedSource) {
        MapTileSource.OSM -> 15
        MapTileSource.SWISSTOPO_MAP, MapTileSource.SWISSTOPO_AERIAL -> 25
    }
    val estimatedSizeMb = (estimatedTiles * avgTileSizeKb) / 1024.0

    ModalBottomSheet(
        onDismissRequest = {
            if (!downloadProgress.isDownloading) onDismiss()
        },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = "Offline speichern",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Quelle: ${selectedSource.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            // --- Zoom Min ---
            Text("Min. Zoom: ${zoomMin.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = zoomMin,
                onValueChange = { zoomMin = it.coerceAtMost(zoomMax) },
                valueRange = 1f..18f,
                steps = 16,
                enabled = !downloadProgress.isDownloading
            )

            // --- Zoom Max ---
            Text("Max. Zoom: ${zoomMax.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = zoomMax,
                onValueChange = { zoomMax = it.coerceAtLeast(zoomMin) },
                valueRange = 1f..18f,
                steps = 16,
                enabled = !downloadProgress.isDownloading
            )

            Spacer(Modifier.height(8.dp))

            // --- Schaetzung ---
            Text(
                text = "Geschaetzte Tiles: $estimatedTiles (~${"%.1f".format(estimatedSizeMb)} MB)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(16.dp))

            // --- Fortschritt (waehrend Download) ---
            if (downloadProgress.isDownloading) {
                val fraction = if (downloadProgress.totalTiles > 0) {
                    downloadProgress.downloadedTiles.toFloat() / downloadProgress.totalTiles
                } else 0f
                val percent = (fraction * 100).roundToInt()

                Text(
                    text = "Zoom ${downloadProgress.currentZoom}: ${downloadProgress.downloadedTiles}/${downloadProgress.totalTiles} Tiles ($percent%)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Abbrechen")
                }
            }

            // --- Fehler ---
            downloadProgress.error?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // --- Buttons (wenn nicht am Downloaden) ---
            if (!downloadProgress.isDownloading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Abbrechen")
                    }
                    Button(
                        onClick = {
                            mapView?.let { mv ->
                                boundingBox?.let { bb ->
                                    val tileSource = PirolTileSourceFactory.create(selectedSource, apiKey)
                                    onStartDownload(mv, tileSource, bb, zoomMin.roundToInt(), zoomMax.roundToInt())
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = mapView != null && boundingBox != null && estimatedTiles > 0
                    ) {
                        Text("Download starten")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
