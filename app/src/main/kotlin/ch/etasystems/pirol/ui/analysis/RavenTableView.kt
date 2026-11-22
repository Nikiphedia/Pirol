package ch.etasystems.pirol.ui.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.etasystems.pirol.R

/**
 * T194: Vollbild-Dialog mit der Raven Selection Table als Tabelle. Kopfzeile bleibt oben stehen,
 * Inhalt scrollt vertikal (LazyColumn) und die ganze Tabelle horizontal; Zahlenspalten rechtsbuendig.
 */
@Composable
fun RavenTableDialog(
    title: String,
    table: RavenTable,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                    }
                }
                HorizontalDivider()
                RavenTableContent(table, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RavenTableContent(table: RavenTable, modifier: Modifier = Modifier) {
    val widths = remember(table) { RavenTable.columnWidthsDp(table) }
    val hScroll = rememberScrollState()
    Box(modifier = modifier.horizontalScroll(hScroll)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Kopfzeile fix (scrollt nur horizontal mit)
            Row(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                table.header.forEachIndexed { c, name ->
                    Cell(name, widths[c], right = c in table.numericColumns, bold = true)
                }
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.weight(1f).testTag("raven_rows")) {
                itemsIndexed(table.rows) { i, row ->
                    Row(
                        modifier = if (i % 2 == 1) Modifier.background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                        ) else Modifier
                    ) {
                        table.header.indices.forEach { c ->
                            Cell(row.getOrElse(c) { "" }, widths[c], right = c in table.numericColumns, bold = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(text: String, widthDp: Int, right: Boolean, bold: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else null,
        textAlign = if (right) TextAlign.End else TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(widthDp.dp).padding(horizontal = 8.dp, vertical = 6.dp)
    )
}
