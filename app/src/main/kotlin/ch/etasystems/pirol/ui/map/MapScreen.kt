package ch.etasystems.pirol.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ch.etasystems.pirol.data.repository.AtlasCodeRepository
import ch.etasystems.pirol.data.repository.SpeciesColorRepository
import ch.etasystems.pirol.ui.components.EditDetectionDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
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
    size: Int = 64
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

    if (locked) {
        val lockFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.BLACK
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size - 8f, 8f, 6f, lockFill)
        val lockBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawCircle(size - 8f, 8f, 6f, lockBorder)
    }

    return BitmapDrawable(resources, bitmap)
}

private fun tileSourceForZoom(zoom: Int): org.osmdroid.tileprovider.tilesource.ITileSource {
    return if (zoom >= 13) SwisstopoFarbeTileSource
    else org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK
}

// ── Haupt-Composable ──────────────────────────────────────────────────────────

@Composable
fun MapScreen(viewModel: MapViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val colorRepo: SpeciesColorRepository = koinInject()
    val atlasCodeRepo: AtlasCodeRepository = koinInject()

    // Synchron — muss VOR AndroidView.factory laufen
    remember(context) {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", 0))
            userAgentValue = context.packageName
            osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid_tiles")
            tileDownloadThreads = 4.toShort()
            tileDownloadMaxQueueSize = 40.toShort()
        }
    }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val locationState by viewModel.currentLocation.collectAsState()

    // Marker-Referenz fuer GestureDetector (aktuell, thread-safe via MutableState)
    val longPressMarkersRef = remember { mutableStateOf<List<MapViewModel.MapMarker>>(emptyList()) }

    Box(modifier = Modifier.fillMaxSize()) {

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
                    controller.setCenter(GeoPoint(state.centerLat, state.centerLon))
                    setTileSource(tileSourceForZoom(14))
                    addMapListener(object : org.osmdroid.events.MapListener {
                        override fun onZoom(event: org.osmdroid.events.ZoomEvent): Boolean {
                            setTileSource(tileSourceForZoom(event.zoomLevel.toInt()))
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
                                val markers = longPressMarkersRef.value
                                val proj = mapView.projection
                                val historisch = markers.filter { !it.isLive }
                                val nearest = historisch.minByOrNull { m ->
                                    val px = proj.toPixels(GeoPoint(m.latitude, m.longitude), null)
                                    val dx = (px.x - e.x).toDouble()
                                    val dy = (px.y - e.y).toDouble()
                                    dx * dx + dy * dy
                                } ?: return
                                val nearPx = proj.toPixels(
                                    GeoPoint(nearest.latitude, nearest.longitude), null
                                )
                                val dist = Math.hypot(
                                    (nearPx.x - e.x).toDouble(),
                                    (nearPx.y - e.y).toDouble()
                                )
                                if (dist < 120.0) {   // 120px ≈ 40dp — grosszügiger Tap-Target
                                    viewModel.onMarkerLongPress(nearest)
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
                longPressMarkersRef.value = state.markers

                // Einfaches clear — GestureDetector läuft unabhängig von Overlays
                mapView.overlays.clear()

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

                val clustersToRender = when {
                    state.filterMode == FilterMode.LIVE_ONLY -> emptyList()
                    state.focusMode -> state.clusters.filter { cluster ->
                        cluster.items.any { it.species == state.focusSpecies }
                    }
                    else -> state.clusters
                }
                for (cluster in clustersToRender) {
                    val marker = Marker(mapView)
                    marker.position = GeoPoint(cluster.centerLat, cluster.centerLon)
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    if (cluster.items.size == 1) {
                        val m = cluster.items.first()
                        val isAnchor = m.detectionId == state.linkAnchor?.detectionId
                        val color = colorRepo.getColor(m.species)
                        val iconSize = if (isAnchor) 80 else 64
                        marker.icon = createCircleMarkerIcon(mapView.resources, color, speciesLabel(m.species), locked = state.lockedSpecies.contains(m.species), size = iconSize)
                        marker.title = "${m.species} (${(m.confidence * 100).toInt()}%)"
                        marker.snippet = m.sessionId.take(16)
                        if (state.focusMode) {
                            marker.setOnMarkerClickListener { _, _ ->
                                viewModel.selectLinkTarget(m)
                                true
                            }
                        }
                    } else {
                        val label = "+${cluster.items.size}"
                        marker.icon = createCircleMarkerIcon(mapView.resources, clusterColor, label)
                        marker.title = "${cluster.items.size} Detektionen"
                        marker.snippet = null
                        marker.setOnMarkerClickListener { _, _ ->
                            viewModel.selectCluster(cluster)
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }

                val liveToRender = if (state.filterMode == FilterMode.LIVE_ONLY || state.filterMode == FilterMode.ALL)
                    state.liveMarkers else emptyList()
                for (m in liveToRender) {
                    val marker = Marker(mapView)
                    marker.position = GeoPoint(m.latitude, m.longitude)
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    marker.icon = createCircleMarkerIcon(mapView.resources, blueColor, speciesLabel(m.species))
                    marker.title = "${m.species} (Live, ${(m.confidence * 100).toInt()}%)"
                    mapView.overlays.add(marker)
                }

                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Cluster-Dialog
        val cluster = state.selectedCluster
        if (cluster != null) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissCluster() },
                title = { Text("${cluster.items.size} Detektionen — Tippen zum Bearbeiten") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(cluster.items) { m ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectMarkerForEdit(m) }
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
                                    Text(m.species, style = MaterialTheme.typography.bodyMedium)
                                    val meta = buildString {
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

        // Filter-Chips oben
        Column(modifier = Modifier.align(Alignment.TopStart)) {
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

        // Position-Verschieben-Dialog
        val target = state.positionMoveTarget
        if (target != null) {
            var latText by remember(target.detectionId) {
                mutableStateOf(target.latitude.toString())
            }
            var lonText by remember(target.detectionId) {
                mutableStateOf(target.longitude.toString())
            }
            AlertDialog(
                onDismissRequest = { viewModel.dismissPositionMove() },
                title = { Text("Position verschieben") },
                text = {
                    Column {
                        Text("Art: ${target.species}")
                        Text(
                            text = "Aktuelle Position: ${target.latitude}, ${target.longitude}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = latText,
                            onValueChange = { latText = it },
                            label = { Text("Breitengrad") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = lonText,
                            onValueChange = { lonText = it },
                            label = { Text("Laengengrad") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val lat = latText.toDoubleOrNull()
                            val lon = lonText.toDoubleOrNull()
                            if (lat != null && lon != null) {
                                viewModel.confirmPositionMove(lat, lon)
                            }
                        }
                    ) {
                        Text("Verschieben")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissPositionMove() }) {
                        Text("Abbrechen")
                    }
                }
            )
        }
    }
}
