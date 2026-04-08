package ch.etasystems.pirol.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.koin.androidx.compose.koinViewModel
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
fun MapScreen(viewModel: MapViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (state.markers.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Noch keine georeferenzierten Detektionen",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // osmdroid Konfiguration initialisieren
    val context = LocalContext.current
    Configuration.getInstance()
        .load(context, context.getSharedPreferences("osmdroid", 0))

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
        modifier = Modifier.fillMaxSize()
    )
}
