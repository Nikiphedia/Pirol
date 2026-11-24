package ch.etasystems.pirol.ui.help

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** T266: Anleitung aus assets/manual in der App-Sprache laden (Rueckfall Deutsch) */
fun loadManual(context: Context): ManualDoc? = try {
    val available = context.assets.list(ManualLanguage.DIR)?.toSet().orEmpty()
    val file = ManualLanguage.select(AppLanguage.currentTag(), AppLanguage.systemLanguage(), available)
    val text = context.assets.open("${ManualLanguage.DIR}/$file").bufferedReader(Charsets.UTF_8).use { it.readText() }
    ManualMarkdown.parse(text)
} catch (e: Exception) {
    android.util.Log.w("ManualScreen", "Anleitung nicht lesbar", e)
    null
}

/**
 * T266: Bedienungsanleitung offline im Vollbild. Inhaltsverzeichnis aus ##/###, Sprung zu Abschnitt.
 * @param chapter Kapitelnummer zum Hinspringen ("" = Anfang)
 */
@Composable
fun ManualScreen(chapter: String, onClose: () -> Unit) {
    val context = LocalContext.current
    // Laden: loaded = false waehrend des Lesens, danach doc oder null (fehlt)
    val loadState by produceState<Pair<Boolean, ManualDoc?>>(initialValue = false to null) {
        value = true to withContext(Dispatchers.IO) { loadManual(context) }
    }
    val doc = loadState.second
    var showToc by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    BackHandler {
        if (showToc) showToc = false else onClose()
    }

    LaunchedEffect(doc, chapter) {
        val d = doc ?: return@LaunchedEffect
        if (chapter.isNotEmpty()) d.chapterIndex(chapter)?.let { listState.scrollToItem(it) }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { if (showToc) showToc = false else onClose() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.help_manual_back))
                }
                Text(
                    stringResource(if (showToc) R.string.help_manual_toc else R.string.help_manual_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showToc = !showToc }) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.help_manual_toc))
                }
            }
            HorizontalDivider()
            val d = doc
            when {
                d == null -> Text(
                    stringResource(if (loadState.first) R.string.help_manual_missing else R.string.help_manual_loading),
                    modifier = Modifier.padding(16.dp)
                )
                showToc -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(d.toc) { _, entry ->
                        Text(
                            entry.title,
                            style = if (entry.level == 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    showToc = false
                                    scope.launch { listState.scrollToItem(entry.blockIndex) }
                                }
                                .padding(start = if (entry.level == 2) 16.dp else 32.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
                        )
                    }
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    itemsIndexed(d.blocks) { _, block -> MdBlockView(block) }
                }
            }
        }
    }
}

@Composable
private fun MdBlockView(block: MdBlock) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    when (block) {
        is MdBlock.Heading -> Text(
            inlineAnnotated(block.text, codeBg),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            modifier = Modifier.padding(top = if (block.level <= 2) 20.dp else 12.dp, bottom = 6.dp)
        )
        is MdBlock.Paragraph -> Text(
            inlineAnnotated(block.text, codeBg),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        is MdBlock.ListItem -> Row(Modifier.padding(start = (8 + block.indent * 16).dp, top = 2.dp, bottom = 2.dp)) {
            Text(block.marker + " ", style = MaterialTheme.typography.bodyMedium)
            Text(inlineAnnotated(block.text, codeBg), style = MaterialTheme.typography.bodyMedium)
        }
        is MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 12.dp))
        is MdBlock.Table -> Box(
            Modifier
                .padding(vertical = 6.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            Column(Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                TableRow(block.header, header = true, codeBg = codeBg)
                block.rows.forEach { TableRow(it, header = false, codeBg = codeBg) }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean, codeBg: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min).then(
            if (header) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier
        )
    ) {
        cells.forEachIndexed { col, cell ->
            Text(
                inlineAnnotated(cell, codeBg),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (header) FontWeight.Bold else null,
                modifier = Modifier
                    .width(if (col == 0) 140.dp else 240.dp)
                    .fillMaxHeight()
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    .padding(6.dp)
            )
        }
    }
}

private fun inlineAnnotated(text: String, codeBg: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        ManualMarkdown.inline(text).forEach { span ->
            withStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) codeBg else androidx.compose.ui.graphics.Color.Unspecified
                )
            ) { append(span.text) }
        }
    }
