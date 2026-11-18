package ch.etasystems.pirol.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import org.osmdroid.views.overlay.CopyrightOverlay
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ch.etasystems.pirol.data.repository.AtlasCodeRepository
import ch.etasystems.pirol.data.repository.SpeciesColorRepository
import ch.etasystems.pirol.ui.components.EditDetectionDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.BoundingBox
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import ch.etasystems.pirol.data.AppPreferences
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import ch.etasystems.pirol.ml.SpeciesNameResolver
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import android.view.GestureDetector
import androidx.core.view.GestureDetectorCompat

// ── Hilfsfunktionen: Kreis-Marker mit Kuerzel ────────────────────────────────

private fun speciesLabel(species: String): String {
    val words = species.trim().split(" ", "-")
    return when {
        words.size >= 2 -> words.take(3).joinToString("") { it.take(1).uppercase() }
        species.length >= 3 -> species.take(3).replaceFirstChar { it.uppercase() }
        else -> species.uppercase()
    }
}

private fun createCircleMarkerIcon(
    resources: android.content.res.Resources,
    color: Int,
    label: String = "",
    locked: Boolean = false,
    size: Int = 64,
    atlasCode: String? = null,   // T111: kleines Etikett unten
    count: Int = 1,              // T111: Badge oben rechts ab 2
    ringColor: Int? = null       // T111: farbiger Ring (Cluster mit Live-Detektionen)
): Drawable {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paintFill)

    val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paintBorder)

    if (ringColor != null) {
        val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = ringColor
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 6f, paintRing)
    }

    if (label.isNotEmpty()) {
        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textSize = when {
                label.length == 1 -> 26f
                label.length <= 3 -> 18f
                else -> 14f
            }
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val bounds = android.graphics.Rect()
        paintText.getTextBounds(label, 0, label.length, bounds)
        val yOffset = bounds.height() / 2f
        canvas.drawText(label, size / 2f, size / 2f + yOffset, paintText)
    }

    if (!atlasCode.isNullOrBlank()) {
        val paintTag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textSize = size * 0.22f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val tw = paintTag.measureText(atlasCode)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.parseColor("#CC263238") }
        val h = size * 0.26f
        val rect = android.graphics.RectF(size / 2f - tw / 2f - 4f, size - h - 1f, size / 2f + tw / 2f + 4f, size - 1f)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, bg)
        canvas.drawText(atlasCode, size / 2f, size - 1f - h * 0.25f, paintTag)
    }

    if (count > 1) {
        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE }
        val r = size * 0.18f
        canvas.drawCircle(size - r - 1f, r + 1f, r, badge)
        val paintCount = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.BLACK
            textSize = r * (if (count > 9) 1.1f else 1.4f)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(count.coerceAtMost(99).toString(), size - r - 1f, r + 1f + r * 0.45f, paintCount)
    }

    if (locked) {
        val lockFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.BLACK
            style = Paint.Style.FILL
        }
        canvas.drawCircle(8f, 8f, 6f, lockFill)
        val lockBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawCircle(8f, 8f, 6f, lockBorder)
    }

    return BitmapDrawable(resources, bitmap)
}

/** Belegter Kartenspeicher (osmdroid-SQLite-Cache) in Bytes */
private fun tileCacheBytes(): Long {
    val writer = SqlTileWriter()
    return try { writer.size } finally { writer.onDetach() }
}

private fun purgeTileCache() {
    val writer = SqlTileWriter()
    try { writer.purgeCache() } finally { writer.onDetach() }
}

/** Dialoge rund um Kartenquelle und Offline-Download (T108) */
private sealed interface OfflineDialog {
    data class Confirm(val box: BoundingBox, val zoomMin: Int, val zoomMax: Int, val tiles: Int) : OfflineDialog
    data class Info(val text: String) : OfflineDialog
    data object ConfirmDelete : OfflineDialog
}

// ── Haupt-Composable ──────────────────────────────────────────────────────────

@OptIn(FlowPreview::class, ExperimentalFoundationApi::class)
@Composable
fun MapScreen(viewModel: MapViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val colorRepo: SpeciesColorRepository = koinInject()
    val atlasCodeRepo: AtlasCodeRepository = koinInject()
    val nameResolver: SpeciesNameResolver = koinInject()

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val startCenter = remember { mutableStateOf<GeoPoint?>(null) }  // T112: Kartenmitte beim Start

    // T108: Kartenquelle + Offline-Download
    val prefs: AppPreferences = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceMode by remember { mutableStateOf(MapSourceMode.fromName(prefs.mapSourceMode)) }
    val sourceModeRef = rememberUpdatedState(sourceMode)
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var offlineDialog by remember { mutableStateOf<OfflineDialog?>(null) }
    var downloadTask by remember { mutableStateOf<CacheManager.CacheManagerTask?>(null) }
    var downloadDone by remember { mutableIntStateOf(0) }
    var downloadTotal by remember { mutableIntStateOf(0) }
    var cacheBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(sourceMenuOpen, downloadTask, offlineDialog) {
        cacheBytes = withContext(Dispatchers.IO) { runCatching { tileCacheBytes() }.getOrDefault(0L) }
    }
    val locationState by viewModel.currentLocation.collectAsState()

    // T112: Beim Oeffnen auf den Standort zoomen (swisstopo-Standard). Kein erneuter Sprung,
    // sobald der Nutzer die Karte bewegt hat (Kartenmitte != Startmitte) oder nach 4 s ohne Fix.
    LaunchedEffect(Unit) {
        viewModel.requestLastKnownLocation()
        val loc = withTimeoutOrNull(4000L) {
            snapshotFlow { locationState }.filterNotNull().first()
        } ?: return@LaunchedEffect
        val mv = mapViewRef.value ?: return@LaunchedEffect
        val start = startCenter.value ?: return@LaunchedEffect
        val untouched = abs(mv.mapCenter.latitude - start.latitude) < 1e-4 &&
            abs(mv.mapCenter.longitude - start.longitude) < 1e-4 &&
            mv.zoomLevelDouble == 14.0
        if (untouched && state.positionMoveTarget == null) {
            mv.controller.setZoom(15.0)
            mv.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
        }
    }

    // Marker-Referenz fuer GestureDetector (aktuell, thread-safe via MutableState)
    val positionMoveRef = remember { mutableStateOf<MapViewModel.MapMarker?>(null) }
    val longPressClustersRef = remember { mutableStateOf<List<MapViewModel.MarkerCluster>>(emptyList()) }

    // T111: Zoom -> Clustering. Roh-Zoom aus dem MapListener, entprellt (150 ms) fuer die Gruppierung.
    val rawZoom = remember { mutableDoubleStateOf(14.0) }
    var clusterZoom by remember { mutableDoubleStateOf(14.0) }
    LaunchedEffect(Unit) {
        snapshotFlow { rawZoom.doubleValue }.debounce(150L).collect { clusterZoom = it }
    }
    val thresholdPx = 48f * LocalDensity.current.density  // ~1 Icon-Durchmesser (48 dp)
    val clusters = remember(state.markers, state.liveMarkers, state.filterMode, state.focusMode, state.focusSpecies, clusterZoom, thresholdPx) {
        val input = when {
            state.filterMode == FilterMode.LIVE_ONLY -> state.liveMarkers
            // Fokus-Modus: nur historische Marker der Fokus-Art (Live hat keine Session fuer Verknuepfungen)
            state.focusMode -> state.markers.filter { it.species == state.focusSpecies }
            else -> state.markers + state.liveMarkers
        }
        buildClusters(input, clusterZoom, thresholdPx.toDouble())
    }

    Box(modifier = Modifier.fillMaxSize()) {

        val inMoveMode = state.positionMoveTarget != null

        // Karte (ganzer Bildschirm)
        val allMarkers = when (state.filterMode) {
            FilterMode.LIVE_ONLY -> state.liveMarkers
            else -> state.markers + state.liveMarkers
        }

        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setMultiTouchControls(true)
                    controller.setZoom(14.0)
                    rawZoom.doubleValue = 14.0
                    controller.setCenter(GeoPoint(state.centerLat, state.centerLon))
                    startCenter.value = GeoPoint(state.centerLat, state.centerLon)
                    setTileSource(tileSourceFor(sourceModeRef.value, 14))
                    addMapListener(object : org.osmdroid.events.MapListener {
                        override fun onZoom(event: org.osmdroid.events.ZoomEvent): Boolean {
                            // T106: nur bei Wechsel der Quelle setzen (setTileSource leert den Kachel-Cache)
                            rawZoom.doubleValue = event.zoomLevel
                            val source = tileSourceFor(sourceModeRef.value, event.zoomLevel.toInt())
                            if (tileProvider.tileSource != source) setTileSource(source)
                            return false
                        }
                        override fun onScroll(event: org.osmdroid.events.ScrollEvent) = false
                    })
                }.also { mapView ->
                    mapViewRef.value = mapView

                    // GestureDetector direkt auf dem MapView — empfängt Events vor Compose
                    val gestureDetector = GestureDetectorCompat(
                        ctx,
                        object : GestureDetector.SimpleOnGestureListener() {
                            override fun onLongPress(e: MotionEvent) {
                                val clustersNow = longPressClustersRef.value
                                val proj = mapView.projection
                                val nearest = clustersNow.minByOrNull { c ->
                                    val px = proj.toPixels(GeoPoint(c.centerLat, c.centerLon), null)
                                    val dx = (px.x - e.x).toDouble()
                                    val dy = (px.y - e.y).toDouble()
                                    dx * dx + dy * dy
                                } ?: return
                                val nearPx = proj.toPixels(GeoPoint(nearest.centerLat, nearest.centerLon), null)
                                val dist = Math.hypot(
                                    (nearPx.x - e.x).toDouble(),
                                    (nearPx.y - e.y).toDouble()
                                )
                                if (dist < 120.0) {   // 120px ~ 40dp - grosszuegiger Tap-Target
                                    if (nearest.items.size > 1) {
                                        viewModel.selectCluster(nearest)
                                    } else {
                                        val m = nearest.items.first()
                                        if (m.isLive) viewModel.selectMarkerForEdit(m)
                                        else viewModel.onMarkerLongPress(m)
                                    }
                                }
                            }
                        }
                    )

                    @Suppress("ClickableViewAccessibility")
                    mapView.setOnTouchListener { _, event ->
                        gestureDetector.onTouchEvent(event)
                        false  // false = MapView erhält Event weiterhin (Panning/Zooming)
                    }
                }
            },
            update = { mapView ->
                // Marker-Referenz aktuell halten (für GestureDetector)
                longPressClustersRef.value = if (state.positionMoveTarget == null) clusters else emptyList()

                // animateTo nur beim Eintritt in Move-Modus (prevMoveTarget war null)
                val moveTarget = state.positionMoveTarget
                val prevMoveTarget = positionMoveRef.value
                positionMoveRef.value = moveTarget
                if (prevMoveTarget == null && moveTarget != null) {
                    mapView.controller.animateTo(GeoPoint(moveTarget.latitude, moveTarget.longitude))
                }

                // T108: Quelle wechseln (nur bei Aenderung, setTileSource leert den Kachel-Cache)
                val wanted = tileSourceFor(sourceMode, mapView.zoomLevelDouble.toInt())
                if (mapView.tileProvider.tileSource != wanted) mapView.setTileSource(wanted)

                // Einfaches clear — GestureDetector läuft unabhängig von Overlays
                mapView.overlays.clear()
                // T106: Quellenangabe der aktiven Kachelquelle (swisstopo / OpenStreetMap)
                mapView.overlays.add(CopyrightOverlay(mapView.context))

                if (moveTarget != null) {
                    // Move-Modus: nur Ziel-Marker anzeigen (orange, gross)
                    val moveMarker = Marker(mapView)
                    moveMarker.position = GeoPoint(moveTarget.latitude, moveTarget.longitude)
                    moveMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    moveMarker.icon = createCircleMarkerIcon(
                        mapView.resources,
                        android.graphics.Color.parseColor("#FF8F00"),
                        speciesLabel(moveTarget.species),
                        size = 80
                    )
                    moveMarker.title = moveTarget.species
                    mapView.overlays.add(moveMarker)
                } else {
                    val blueColor = android.graphics.Color.parseColor("#1976D2")
                    val clusterColor = android.graphics.Color.parseColor("#607D8B")

                    // Polylinien vor Markern zeichnen
                    for (link in state.simultaneousLinks) {
                        val polyline = org.osmdroid.views.overlay.Polyline(mapView)
                        polyline.addPoint(GeoPoint(link.lat1, link.lon1))
                        polyline.addPoint(GeoPoint(link.lat2, link.lon2))
                        val lineColor = when (link.type) {
                            MapViewModel.LinkType.SIMULTANEOUS    -> android.graphics.Color.parseColor("#4CAF50")
                            MapViewModel.LinkType.PROBABLE        -> android.graphics.Color.parseColor("#F44336")
                            MapViewModel.LinkType.SAME_INDIVIDUAL -> android.graphics.Color.parseColor("#2196F3")
                        }
                        polyline.outlinePaint.apply {
                            color = lineColor
                            strokeWidth = 6f
                            isAntiAlias = true
                        }
                        mapView.overlays.add(polyline)
                    }

                    for (cluster in clusters) {
                        val marker = Marker(mapView)
                        marker.position = GeoPoint(cluster.centerLat, cluster.centerLon)
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        if (cluster.items.size == 1) {
                            val m = cluster.items.first()
                            if (m.isLive) {
                                marker.icon = createCircleMarkerIcon(
                                    mapView.resources, blueColor, speciesLabel(m.species),
                                    atlasCode = m.atlasCode, count = m.count
                                )
                                marker.title = "${m.species} (Live, ${(m.confidence * 100).toInt()}%)"
                            } else {
                                val isAnchor = m.detectionId == state.linkAnchor?.detectionId
                                val color = colorRepo.getColor(m.species)
                                val iconSize = if (isAnchor) 80 else 64
                                marker.icon = createCircleMarkerIcon(
                                    mapView.resources, color, speciesLabel(m.species),
                                    locked = state.lockedSpecies.contains(m.species), size = iconSize,
                                    atlasCode = m.atlasCode, count = m.count
                                )
                                marker.title = "${m.species} (${(m.confidence * 100).toInt()}%)"
                                marker.snippet = m.sessionId.take(16)
                                if (state.focusMode) {
                                    marker.setOnMarkerClickListener { _, _ ->
                                        viewModel.selectLinkTarget(m)
                                        true
                                    }
                                }
                            }
                        } else {
                            val hasLive = cluster.items.any { it.isLive }
                            marker.icon = createCircleMarkerIcon(
                                mapView.resources, clusterColor, "+${cluster.items.size}",
                                ringColor = if (hasLive) blueColor else null
                            )
                            marker.title = "${cluster.items.size} Detektionen"
                            marker.snippet = null
                            marker.setOnMarkerClickListener { _, _ ->
                                viewModel.selectCluster(cluster)
                                true
                            }
                        }
                        mapView.overlays.add(marker)
                    }
                }

                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!inMoveMode) {
            // Cluster-Dialog
            val cluster = state.selectedCluster
            if (cluster != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissCluster() },
                    title = { Text("${cluster.items.size} Detektionen — Tippen oder lange druecken zum Bearbeiten") },
                    text = {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(cluster.items) { m ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = { viewModel.selectMarkerForEdit(m) },
                                            onLongClick = { viewModel.selectMarkerForEdit(m) }
                                        )
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .background(
                                                color = Color(colorRepo.getColor(m.species)),
                                                shape = CircleShape
                                            )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        nameResolver.language = prefs.speciesLanguage
                                        val shownName = if (m.scientificName.isNotBlank())
                                            nameResolver.resolve(m.scientificName) else m.species
                                        Text(shownName, style = MaterialTheme.typography.bodyMedium)
                                        if (m.scientificName.isNotBlank()) {
                                            Text(
                                                m.scientificName.replace('_', ' '),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        val meta = buildString {
                                            if (m.isLive) append("Live  •  ")
                                            append("${(m.confidence * 100).toInt()}%")
                                            if (m.atlasCode != null) append("  •  ${m.atlasCode}")
                                            if (m.count > 1) append("  •  ${m.count}×")
                                            append("  •  ${m.verificationStatus.name}")
                                        }
                                        Text(
                                            meta,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.dismissCluster() }) { Text("Schliessen") }
                    }
                )
            }

            // Edit-Detection-Dialog
            val editTarget = state.editTarget
            val editDetection = state.editDetection
            if (editTarget != null && editDetection != null) {
                EditDetectionDialog(
                    detection = editDetection,
                    atlasCodes = atlasCodeRepo.loadCodes(),
                    onDismiss = { viewModel.dismissEdit() },
                    onSave = { count, atlasCode, note, _ ->
                        viewModel.saveDetectionEdit(count, atlasCode, note)
                    }
                )
            }

            // Long-Press-Auswahl-Dialog
            val longPressTarget = state.longPressTarget
            if (longPressTarget != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissLongPressMenu() },
                    title = { Text(longPressTarget.species) },
                    text = { Text("Was moechten Sie tun?") },
                    confirmButton = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    viewModel.dismissLongPressMenu()
                                    viewModel.selectMarkerForEdit(longPressTarget)
                                }
                            ) { Text("Bearbeiten") }
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    viewModel.dismissLongPressMenu()
                                    viewModel.selectMarkerForMove(longPressTarget)
                                }
                            ) { Text("Position verschieben") }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    viewModel.dismissLongPressMenu()
                                    viewModel.enterFocusMode(longPressTarget)
                                }
                            ) { Text("Simultanbeobachtung") }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissLongPressMenu() }) { Text("Abbrechen") }
                    }
                )
            }

            // Fokus-beenden-Button
            if (state.focusMode) {
                Button(
                    onClick = { viewModel.exitFocusMode() },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Fokus beenden (${state.focusSpecies})")
                }
            }

            // Verbindungstyp-Dialog
            val linkTarget = state.linkTarget
            if (linkTarget != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissLinkTarget() },
                    title = { Text("Verbindungstyp") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                onClick = { viewModel.confirmLink(MapViewModel.LinkType.SIMULTANEOUS) }
                            ) { Text("//  Simultanbeobachtung") }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                                onClick = { viewModel.confirmLink(MapViewModel.LinkType.PROBABLE) }
                            ) { Text("?  Vermutete Simultanbeobachtung") }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                                onClick = { viewModel.confirmLink(MapViewModel.LinkType.SAME_INDIVIDUAL) }
                            ) { Text("!  Gleiches Tier") }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissLinkTarget() }) { Text("Abbrechen") }
                    }
                )
            }

            // Home-FAB
            FloatingActionButton(
                onClick = {
                    val loc = locationState
                    if (loc != null) {
                        mapViewRef.value?.controller?.animateTo(
                            GeoPoint(loc.latitude, loc.longitude),
                            15.0,
                            500L
                        )
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "Mein Standort")
            }

            // T108: Kartenquelle + Offline-Karten (oben rechts)
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                IconButton(onClick = { sourceMenuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Layers, contentDescription = "Kartenquelle")
                }
                DropdownMenu(expanded = sourceMenuOpen, onDismissRequest = { sourceMenuOpen = false }) {
                    for (mode in MapSourceMode.entries) {
                        DropdownMenuItem(
                            text = { Text(if (mode == sourceMode) "\u2713 ${mode.label}" else mode.label) },
                            onClick = {
                                sourceMode = mode
                                prefs.mapSourceMode = mode.name
                                sourceMenuOpen = false
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Ausschnitt herunterladen") },
                        enabled = downloadTask == null,
                        onClick = {
                            sourceMenuOpen = false
                            val mv = mapViewRef.value ?: return@DropdownMenuItem
                            val source = mv.tileProvider.tileSource as? SwisstopoWmtsTileSource
                            if (source == null) {
                                offlineDialog = OfflineDialog.Info(
                                    "Offline-Download gibt es nur fuer swisstopo-Karten. " +
                                        "Waehle eine swisstopo-Quelle (bei Automatisch: ab Zoom $AUTO_SWISSTOPO_MIN_ZOOM)."
                                )
                                return@DropdownMenuItem
                            }
                            val zooms = OfflineTilePlan.zoomRange(mv.zoomLevelDouble.toInt(), source)
                            val box = mv.boundingBox
                            val tiles = try {
                                CacheManager(mv).possibleTilesInArea(box, zooms.first, zooms.last)
                            } catch (e: Exception) { 0 }
                            offlineDialog = if (OfflineTilePlan.isAllowed(tiles)) {
                                OfflineDialog.Confirm(box, zooms.first, zooms.last, tiles)
                            } else {
                                OfflineDialog.Info(
                                    "Dieser Ausschnitt braucht $tiles Kacheln (Zoom ${zooms.first}-${zooms.last}). " +
                                        "Erlaubt sind maximal ${OfflineTilePlan.MAX_TILES}. Bitte weiter hineinzoomen."
                                )
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Offline-Karten: ${cacheBytes / 1_000_000} MB \u2014 loeschen") },
                        onClick = {
                            sourceMenuOpen = false
                            offlineDialog = OfflineDialog.ConfirmDelete
                        }
                    )
                }
            }

            // T108: Download-Fortschritt mit Abbrechen
            if (downloadTask != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 88.dp, bottom = 16.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), MaterialTheme.shapes.medium)
                        .padding(12.dp)
                ) {
                    Text("Karte laden: $downloadDone / $downloadTotal Kacheln", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { if (downloadTotal > 0) downloadDone.toFloat() / downloadTotal else 0f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                    TextButton(onClick = {
                        downloadTask?.cancel(true)
                        downloadTask = null
                        offlineDialog = OfflineDialog.Info("Download abgebrochen. Bereits geladene Kacheln bleiben gespeichert.")
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abbrechen") }
                }
            }

            when (val d = offlineDialog) {
                is OfflineDialog.Confirm -> AlertDialog(
                    onDismissRequest = { offlineDialog = null },
                    title = { Text("Ausschnitt herunterladen?") },
                    text = {
                        Text("${d.tiles} Kacheln (Zoom ${d.zoomMin}-${d.zoomMax}), ca. ${OfflineTilePlan.estimatedMegabytes(d.tiles)} MB.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            offlineDialog = null
                            val mv = mapViewRef.value ?: return@TextButton
                            downloadDone = 0
                            downloadTotal = d.tiles
                            downloadTask = try {
                                CacheManager(mv).downloadAreaAsyncNoUI(
                                    context, d.box, d.zoomMin, d.zoomMax,
                                    object : CacheManager.CacheManagerCallback {
                                        override fun onTaskComplete() {
                                            downloadTask = null
                                            offlineDialog = OfflineDialog.Info("Download abgeschlossen (${d.tiles} Kacheln).")
                                        }
                                        override fun onTaskFailed(errors: Int) {
                                            downloadTask = null
                                            offlineDialog = OfflineDialog.Info(
                                                "Download beendet, $errors Kacheln fehlgeschlagen (Netz?). Erneut starten laedt nur Fehlendes."
                                            )
                                        }
                                        override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                                            downloadDone = progress
                                        }
                                        override fun downloadStarted() {}
                                        override fun setPossibleTilesInArea(total: Int) { downloadTotal = total }
                                    }
                                )
                            } catch (e: Exception) {
                                offlineDialog = OfflineDialog.Info("Download nicht moeglich: ${e.message}")
                                null
                            }
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Herunterladen") }
                    },
                    dismissButton = { TextButton(onClick = { offlineDialog = null }) { Text("Abbrechen") } }
                )
                is OfflineDialog.Info -> AlertDialog(
                    onDismissRequest = { offlineDialog = null },
                    text = { Text(d.text) },
                    confirmButton = { TextButton(onClick = { offlineDialog = null }) { Text("OK") } }
                )
                OfflineDialog.ConfirmDelete -> AlertDialog(
                    onDismissRequest = { offlineDialog = null },
                    title = { Text("Offline-Karten loeschen?") },
                    text = { Text("Alle gespeicherten Kacheln (${cacheBytes / 1_000_000} MB) werden entfernt. Das kann nicht rueckgaengig gemacht werden.") },
                    confirmButton = {
                        TextButton(onClick = {
                            offlineDialog = null
                            scope.launch {
                                withContext(Dispatchers.IO) { runCatching { purgeTileCache() } }
                                mapViewRef.value?.let { it.tileProvider.clearTileCache(); it.invalidate() }
                                cacheBytes = withContext(Dispatchers.IO) { runCatching { tileCacheBytes() }.getOrDefault(0L) }
                            }
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Loeschen") }
                    },
                    dismissButton = { TextButton(onClick = { offlineDialog = null }) { Text("Abbrechen") } }
                )
                null -> {}
            }

            // Filter-Chips oben
            Column(modifier = Modifier.align(Alignment.TopStart).padding(end = 56.dp)) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = state.filterMode == FilterMode.ALL,
                        onClick = { viewModel.setFilter(FilterMode.ALL) },
                        label = { Text("Alle") }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    FilterChip(
                        selected = state.filterMode == FilterMode.LIVE_ONLY,
                        onClick = { viewModel.setFilter(FilterMode.LIVE_ONLY) },
                        label = { Text("Nur Live") }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    FilterChip(
                        selected = state.filterMode == FilterMode.RECENT_3,
                        onClick = { viewModel.setFilter(FilterMode.RECENT_3) },
                        label = { Text("Letzte 3") }
                    )
                }
            }

            // Ladeindikator
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            // Leerstate (kein Loading, keine Marker)
            if (!state.isLoading && allMarkers.isEmpty()) {
                Text(
                    text = "Noch keine georeferenzierten Detektionen",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Move-Modus UI
        if (inMoveMode) {
            // Fadenkreuz in der Mitte
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Zielposition",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
            )

            // Abbrechen — oben links
            IconButton(
                onClick = { viewModel.dismissPositionMove() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Abbrechen",
                    tint = MaterialTheme.colorScheme.onSurface)
            }

            // Speichern — oben rechts
            IconButton(
                onClick = {
                    mapViewRef.value?.let { mv ->
                        viewModel.confirmPositionMove(
                            mv.mapCenter.latitude,
                            mv.mapCenter.longitude
                        )
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(Icons.Filled.Check, contentDescription = "Speichern",
                    tint = MaterialTheme.colorScheme.primary)
            }

            // Art-Name als Kontext
            Text(
                text = state.positionMoveTarget!!.species,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.small
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** T111: Marker bei gegebenem Zoom zu Clustern gruppieren (Pixel-Schwelle, Web-Mercator). */
private fun buildClusters(
    markers: List<MapViewModel.MapMarker>,
    zoom: Double,
    thresholdPx: Double
): List<MapViewModel.MarkerCluster> {
    if (markers.isEmpty()) return emptyList()
    val lats = DoubleArray(markers.size) { markers[it].latitude }
    val lons = DoubleArray(markers.size) { markers[it].longitude }
    val groups = MarkerClustering.groupByZoom(lats, lons, zoom, thresholdPx, org.osmdroid.util.TileSystem.getTileSize())
    return groups.map { g ->
        val items = g.map { markers[it] }
        MapViewModel.MarkerCluster(items.sumOf { it.latitude } / items.size, items.sumOf { it.longitude } / items.size, items)
    }
}
