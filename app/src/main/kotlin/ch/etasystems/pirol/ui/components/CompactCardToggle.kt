package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.data.AppPreferences
import org.koin.compose.koinInject

/** T229: Schnellknopf Artkarte kompakt/ausfuehrlich; das Icon zeigt den aktuellen Zustand. */
@Composable
fun CompactCardToggleButton(modifier: Modifier = Modifier) {
    val prefs: AppPreferences = koinInject()
    val compact by prefs.compactCardSetting.flow.collectAsState()
    IconButton(onClick = { prefs.compactCardSetting.toggle() }, modifier = modifier.size(48.dp)) {
        Icon(
            imageVector = if (compact) Icons.Filled.ViewList else Icons.Filled.ViewAgenda,
            contentDescription = stringResource(
                if (compact) R.string.card_toggle_to_detailed else R.string.card_toggle_to_compact
            ),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
