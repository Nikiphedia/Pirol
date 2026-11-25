package ch.etasystems.pirol.ui.components

import ch.etasystems.pirol.ui.help.helpTopic
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.etasystems.pirol.R
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ui.map.MapSourceMode
import ch.etasystems.pirol.ui.map.tileSourceFor
import org.koin.compose.koinInject
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay

/**
 * T203: Vollbild-Kartenwahl. Die Karte wird unter ein Fadenkreuz in der Mitte geschoben;
 * "Uebernehmen" liefert die Kartenmitte. Kachelquelle wie die Karte (Einstellung, Automatik).
 */
@Composable
fun MapPointPicker(
    startLat: Double,
    startLon: Double,
    startZoom: Double,
    onPick: (lat: Double, lon: Double) -> Unit,
    onDismiss: () -> Unit
) {
    val prefs = koinInject<AppPreferences>()
    val mode = remember { MapSourceMode.fromName(prefs.mapSourceMode) }
    val mapRef = remember { arrayOfNulls<MapView>(1) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().helpTopic(R.string.help_pointpicker_map, "8"),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setMultiTouchControls(true)
                            controller.setZoom(startZoom)
                            controller.setCenter(GeoPoint(startLat, startLon))
                            setTileSource(tileSourceFor(mode, startZoom.toInt(), startLat, startLon))
                            overlays.add(CopyrightOverlay(ctx))
                            addMapListener(object : MapListener {
                                private fun refresh(zoom: Double): Boolean {
                                    val c = mapCenter
                                    val source = tileSourceFor(mode, zoom.toInt(), c.latitude, c.longitude)
                                    if (tileProvider.tileSource != source) setTileSource(source)
                                    return false
                                }
                                override fun onZoom(event: ZoomEvent) = refresh(event.zoomLevel)
                                override fun onScroll(event: ScrollEvent) = refresh(zoomLevelDouble)
                            })
                            mapRef[0] = this
                        }
                    },
                    onRelease = { it.onDetach(); mapRef[0] = null }
                )
                // Fadenkreuz (Mitte)
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).size(36.dp)
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).helpTopic(R.string.help_dialog_cancel, "8").heightIn(min = 56.dp)
                    ) { Text(stringResource(R.string.common_cancel)) }
                    Button(
                        onClick = { mapRef[0]?.mapCenter?.let { onPick(it.latitude, it.longitude) } },
                        modifier = Modifier.weight(1f).helpTopic(R.string.help_pointpicker_ok, "8").heightIn(min = 56.dp)
                    ) { Text(stringResource(R.string.ui_karte_uebernehmen)) }
                }
            }
        }
    }
}
