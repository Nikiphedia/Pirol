package ch.etasystems.pirol.ui.analysis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.audio.PlaybackKeys
import ch.etasystems.pirol.ui.help.helpTopic

/**
 * T264: Block "Memos" zuoberst in der Analyse (aufklappbar): Session- und Art-Memos chronologisch.
 * Zeile: Uhrzeit von-bis, Art (Art-Memo) bzw. "Session-Memo", Play-Knopf. Tippen auf die Zeile springt
 * (Session-Memo: Stelle in der Aufnahme, Art-Memo: Artkarte).
 *
 * @param displayName Anzeigename einer Art (SpeciesNameResolver.displayName)
 */
@Composable
fun MemoBlock(
    items: List<MemoItem>,
    playingKey: String?,
    displayName: (String) -> String,
    onPlay: (MemoItem) -> Unit,
    onOpen: (MemoItem) -> Unit,
    onShare: (MemoItem) -> Unit,
    onSaveAs: (MemoItem) -> Unit,
    onDelete: (MemoItem) -> Unit,
    deleteLocked: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .helpTopic(R.string.help_analysis_memo_block, "6")
                .clickable { expanded = !expanded }
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.analysis_memos_titel, items.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null
            )
        }
        if (expanded) {
            // Begrenzte Hoehe: die Artenliste darunter bleibt sichtbar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                for (item in items) MemoRow(item, playingKey, displayName, onPlay, onOpen, onShare, onSaveAs, onDelete, deleteLocked)
            }
        }
    }
}

@Composable
private fun MemoRow(
    item: MemoItem,
    playingKey: String?,
    displayName: (String) -> String,
    onPlay: (MemoItem) -> Unit,
    onOpen: (MemoItem) -> Unit,
    onShare: (MemoItem) -> Unit,
    onSaveAs: (MemoItem) -> Unit,
    onDelete: (MemoItem) -> Unit,
    deleteLocked: Boolean
) {
    var menuOpen by remember { mutableStateOf(false) }
    val isPlaying = playingKey == PlaybackKeys.memo(item.key)
    val time = MemoList.timeLabel(item)
    val timeText = if (item.approximate && time.isNotEmpty()) stringResource(R.string.analysis_memo_ungefaehr, time) else time
    val label = item.scientificName?.let(displayName) ?: stringResource(R.string.analysis_memo_session)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .helpTopic(R.string.help_analysis_memo_row, "6")
            .clickable { onOpen(item) }
            .padding(start = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (timeText.isNotEmpty()) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = { onPlay(item) }, modifier = Modifier.helpTopic(R.string.help_analysis_memo_play, "6").size(48.dp)) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.analysis_memo_abspielen)
            )
        }
        // T272: Teilen, Speichern unter, Loeschen (nur Session-Memos; Art-Memos loescht die Artkarte)
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.helpTopic(R.string.help_analysis_memo_menue, "6").size(48.dp)
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.analysis_memo_menue))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.analysis_memo_teilen)) },
                    onClick = { menuOpen = false; onShare(item) },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.analysis_memo_speichern_unter)) },
                    onClick = { menuOpen = false; onSaveAs(item) },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                )
                if (item.isSessionMemo) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(if (deleteLocked) R.string.analysis_memo_loeschen_gesperrt else R.string.common_delete))
                        },
                        enabled = !deleteLocked,
                        onClick = { menuOpen = false; onDelete(item) },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                    )
                }
            }
        }
    }
}
