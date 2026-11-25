package ch.etasystems.pirol.ui.settings.sections

import ch.etasystems.pirol.ui.help.helpTopic
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import ch.etasystems.pirol.R
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.ml.SecondaryStats
import ch.etasystems.pirol.ml.SecondaryStats.Secondary
import ch.etasystems.pirol.ml.SecondaryStats.Verification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.util.Locale

/**
 * T279: Zweitmodell-Statistik ueber alle Sessions (nur Expertenmodus). Liest nur (zusammengefuehrter Stand
 * mit Verifikationen), im Hintergrund, Schliessen bricht ab. Rescan-Varianten sind ausgeschlossen: sie
 * bewerten dieselbe Aufnahme erneut und wuerden jede Detektion doppelt zaehlen.
 */
@Composable
internal fun SecondaryStatsDialog(onDismiss: () -> Unit, sessionManager: SessionManager = koinInject()) {
    val context = LocalContext.current
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<SecondaryStats.Result?>(null) }

    LaunchedEffect(Unit) {
        val dirs = withContext(Dispatchers.IO) { sessionManager.listSessions() }
        total = dirs.size
        val acc = SecondaryStats.Accumulator()
        for (dir in dirs) {
            acc.add(sessionManager.loadDetectionsWithVerifications(dir))
            ensureActive()
            done++
        }
        result = acc.result()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.secstats_titel)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val r = result
                if (r == null) {
                    Text(stringResource(R.string.secstats_laeuft, done, total))
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    StatsBody(r)
                }
            }
        },
        confirmButton = {
            val r = result
            if (r != null) {
                TextButton(onClick = { shareCsv(context, r) }, modifier = Modifier.helpTopic(R.string.help_secstats_csv, "13")) { Text(stringResource(R.string.secstats_csv)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.helpTopic(R.string.help_secstats_close, "13")) {
                Text(stringResource(if (result == null) R.string.secstats_abbrechen else R.string.secstats_schliessen))
            }
        }
    )
}

@Composable
private fun StatsBody(r: SecondaryStats.Result) {
    Text(stringResource(R.string.secstats_hinweis), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    StatsBlock(stringResource(R.string.secstats_gesamt), r.total)
    r.perModel.toSortedMap().forEach { (id, t) ->
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        StatsBlock(id, t)
    }
}

@Composable
private fun StatsBlock(title: String, t: SecondaryStats.Table) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Cell("", 1.6f, header = true)
        listOf(
            stringResource(R.string.secstats_col_checked), stringResource(R.string.secstats_col_nc),
            stringResource(R.string.secstats_col_dis), stringResource(R.string.secstats_col_none),
            stringResource(R.string.secstats_col_sum)
        ).forEach { Cell(it, 1f, header = true) }
    }
    Verification.entries.forEach { v ->
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Cell(stringResource(rowLabel(v)), 1.6f, left = true)
            Secondary.entries.forEach { Cell(t.get(v, it).toString(), 1f) }
            Cell(t.rowSum(v).toString(), 1f)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Cell(stringResource(R.string.secstats_col_sum), 1.6f, left = true, header = true)
        Secondary.entries.forEach { Cell(t.colSum(it).toString(), 1f, header = true) }
        Cell(t.total.toString(), 1f, header = true)
    }
    Spacer(Modifier.height(8.dp))
    Goal(R.string.secstats_ziel_bestaetigt, t.withResult(Verification.CONFIRMED), SecondaryStats.GOAL_CONFIRMED)
    Goal(R.string.secstats_ziel_abgelehnt, t.withResult(Verification.REJECTED), SecondaryStats.GOAL_REJECTED)
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(
            R.string.secstats_widerspruch,
            pct(t.disagreeRate(Verification.CONFIRMED)), pct(t.disagreeRate(Verification.REJECTED))
        ),
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        stringResource(
            R.string.secstats_widerspruch_pruefbar,
            pct(t.disagreeRateCheckable(Verification.CONFIRMED)), pct(t.disagreeRateCheckable(Verification.REJECTED))
        ),
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun Goal(labelRes: Int, count: Int, goal: Int) {
    Text(stringResource(labelRes, count, goal), style = MaterialTheme.typography.bodySmall)
    LinearProgressIndicator(
        progress = { SecondaryStats.progress(count, goal) },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String, weight: Float, header: Boolean = false, left: Boolean = false
) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
        fontFamily = if (header || left) null else FontFamily.Monospace,
        textAlign = if (left) TextAlign.Start else TextAlign.End,
        maxLines = 2
    )
}

private fun rowLabel(v: Verification): Int = when (v) {
    Verification.CONFIRMED -> R.string.secstats_row_confirmed
    Verification.REJECTED -> R.string.secstats_row_rejected
    Verification.UNCERTAIN -> R.string.secstats_row_uncertain
    Verification.OPEN -> R.string.secstats_row_open
    Verification.OTHER -> R.string.secstats_row_other
}

private fun pct(rate: Double?): String =
    if (rate == null) "-" else String.format(Locale.US, "%.0f %%", rate * 100)

private fun shareCsv(context: android.content.Context, r: SecondaryStats.Result) {
    try {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "zweitmodell_statistik.csv")
        file.writeText(SecondaryStats.toCsv(r))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        android.util.Log.w("SecondaryStats", "CSV teilen fehlgeschlagen", e)
    }
}
