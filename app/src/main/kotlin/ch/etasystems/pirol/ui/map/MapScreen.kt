package ch.etasystems.pirol.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.koin.androidx.compose.koinViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay

// ── Hilfsfunktion: farbigen Kreis-Marker programmatisch erstellen ─────────────

private fun createCircleMarkerIcon(resources: android.content.res.Resources, color: Int): Drawable {
    val size = 48
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paint)
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, border)
    return BitmapDrawable(resources, bitmap)
}

// ── Long-Press Overlay: findet naechsten historischen Marker innerhalb 50px ──

private class MarkerLongPressOverlay(
    private val markers: List<MapViewModel.MapMarker>,
    private val onLongPress: (MapViewModel.MapMarker) -> Unit
) : Overlay() {
    override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection
        val historisch = markers.filter { !it.isLive }
        val nearest = historisch.minByOrNull { m ->
            val px = proj.toPixels(GeoPoint(m.latitude, m.longitude), null)
            val dx = (px.x - e.x).toDouble()
            val dy = (px.y - e.y).toDouble()
            dx * dx + dy * dy
        } ?: return false
        val nearPx = proj.toPixels(GeoPoint(nearest.latitude, nearest.longitude), null)
        val dist = Math.hypot((nearPx.x - e.x).toDouble(), (nearPx.y - e.y).toDouble())
        if (dist < 50.0) {
            onLongPress(nearest)
            return true
        }
        return false
    }
}

// ── Haupt-Composable ──────────────────────────────────────────────────────────

@Composable
fun MapScreen(viewModel: MapViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // osmdroid Konfiguration einmalig initialisieren
    Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))

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
                }
            },
            update = { mapView ->
                mapView.overlays.clear()

                // Long-Press Overlay zuerst registrieren (vor Markern)
                mapView.overlays.add(
                    MarkerLongPressOverlay(allMarkers) { marker ->
                        viewModel.selectMarkerForMove(marker)
                    }
                )

                // Icons einmalig erzeugen
                val redIcon = createCircleMarkerIcon(
                    mapView.resources,
                    android.graphics.Color.parseColor("#D32F2F")
                )
                val blueIcon = createCircleMarkerIcon(
                    mapView.resources,
                    android.graphics.Color.parseColor("#1976D2")
                )

                for (m in allMarkers) {
                    val marker = Marker(mapView)
                    marker.position = GeoPoint(m.latitude, m.longitude)
                    marker.title = "${m.species} (${(m.confidence * 100).toInt()}%)"
                    marker.snippet = if (m.isLive) "Live" else m.sessionId.take(16)
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    marker.icon = if (m.isLive) blueIcon else redIcon
                    mapView.overlays.add(marker)
                }

                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

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
