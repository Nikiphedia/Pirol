package ch.etasystems.pirol.ui.help

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R

/**
 * T266: Ueberlagerungen des Hilfemodus (in MainActivity nach dem NavHost):
 * Erklaerungsblase, Anleitung im Vollbild, Zurueck beendet den Hilfemodus.
 */
@Composable
fun HelpOverlays(state: HelpModeState) {
    // Zurueck: zuerst Anleitung schliessen, sonst Hilfemodus beenden (nach dem NavHost registriert = Vorrang)
    BackHandler(enabled = state.active && state.manualChapter == null) {
        state.active = false
        state.bubble = null
    }

    state.bubble?.let { topic ->
        AlertDialog(
            onDismissRequest = { state.bubble = null },
            icon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
            text = { Text(stringResource(topic.textRes), style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = { state.bubble = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.help_bubble_ok))
                }
            },
            dismissButton = {
                if (topic.chapter != null) {
                    TextButton(
                        onClick = { state.openManual(topic.chapter) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.help_bubble_more)) }
                }
            }
        )
    }

    state.manualChapter?.let { chapter ->
        ManualScreen(chapter = chapter, onClose = { state.manualChapter = null })
    }
}

/** T266: Dezente Hinweisleiste oben, solange der Hilfemodus an ist (verschiebt den Inhalt, ueberdeckt nichts). */
@Composable
fun HelpModeBar(state: HelpModeState) {
    if (!state.active) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            Icons.AutoMirrored.Filled.HelpOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.help_mode_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { state.active = false; state.bubble = null }) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.help_mode_exit),
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}
