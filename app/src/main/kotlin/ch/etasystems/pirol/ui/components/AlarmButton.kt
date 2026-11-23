package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.WatchlistManager
import ch.etasystems.pirol.ml.WatchlistMatcher
import ch.etasystems.pirol.ui.AppStrings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * T244: Snackbar mit Undo fuer den Alarm-Knopf. Wird von Live- und Analyse-Screen bereitgestellt:
 * (Meldung, Undo-Beschriftung, Undo-Aktion). Ohne Bereitsteller: keine Rueckmeldung.
 */
val LocalUndoSnackbar = compositionLocalOf<((String, String, () -> Unit) -> Unit)?> { null }

/**
 * T244: Alarm-Knopf der vollen Artkarte (Glocke, 48 dp). Tipp oeffnet ein Menue:
 * dauerhaft in die Alarmliste (Watchlist) oder 24 h (laeuft ab Tipp, danach automatisch entfernt).
 * Ist die Art schon drin: "Entfernen" (bei 24 h zusaetzlich "Dauerhaft machen").
 */
@Composable
fun AlarmButton(detection: DetectionResult) {
    val manager: WatchlistManager = koinInject()
    val entries by manager.entriesFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val undoSnackbar = LocalUndoSnackbar.current
    var menuOpen by remember { mutableStateOf(false) }

    val entry = WatchlistMatcher.findEntry(entries, detection.scientificName)
    val temporary = entry?.expiresAtMs != null
    val name = detection.commonName.ifEmpty { detection.scientificName.replace('_', ' ') }

    fun apply(expiresAtMs: Long?, messageRes: Int) {
        menuOpen = false
        scope.launch {
            val previous = manager.setAlarm(detection.scientificName, detection.commonName, expiresAtMs)
            undoSnackbar?.invoke(AppStrings.get(messageRes, name), AppStrings.get(R.string.common_undo)) {
                scope.launch { manager.restore(previous) }
            }
        }
    }

    androidx.compose.foundation.layout.Box {
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (entry != null) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (entry != null) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            val desc = stringResource(R.string.ui_alarm_button)
            if (temporary) {
                // Glocke mit Uhr: kleines "24h"-Abzeichen
                BadgedBox(badge = { Badge { Text("24h") } }) {
                    Icon(Icons.Filled.NotificationsActive, contentDescription = desc, modifier = Modifier.size(24.dp))
                }
            } else {
                Icon(
                    if (entry != null) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
                    contentDescription = desc,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (entry == null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_alarm_dauerhaft)) },
                    onClick = { apply(null, R.string.ui_alarm_gesetzt) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_alarm_24h)) },
                    onClick = {
                        apply(System.currentTimeMillis() + WatchlistMatcher.TEMP_ALARM_MS, R.string.ui_alarm_gesetzt_24h)
                    }
                )
            } else {
                if (temporary) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_alarm_dauerhaft_machen)) },
                        onClick = { apply(null, R.string.ui_alarm_gesetzt) }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ui_alarm_entfernen)) },
                    onClick = {
                        menuOpen = false
                        scope.launch {
                            val previous = manager.clearAlarm(detection.scientificName)
                            undoSnackbar?.invoke(
                                AppStrings.get(R.string.ui_alarm_entfernt, name),
                                AppStrings.get(R.string.common_undo)
                            ) { scope.launch { manager.restore(previous) } }
                        }
                    }
                )
            }
        }
    }
}
