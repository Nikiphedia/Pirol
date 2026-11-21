package ch.etasystems.pirol.ui.components

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R

/** T183: Entscheidung, was beim Start mit dem Changelog passiert. */
enum class WhatsNewAction { SHOW, MARK_SEEN, NOTHING }

/**
 * Reine Entscheidungsfunktion.
 * - Onboarding laeuft noch (Neuinstallation): still auf aktuell setzen.
 * - Update (gespeicherter Wert fehlt oder kleiner als aktuell): anzeigen.
 * - Sonst nichts.
 */
fun decideWhatsNew(lastSeenVersionCode: Int?, currentVersionCode: Int, onboardingCompleted: Boolean): WhatsNewAction {
    val outdated = lastSeenVersionCode == null || lastSeenVersionCode < currentVersionCode
    return when {
        !outdated -> WhatsNewAction.NOTHING
        !onboardingCompleted -> WhatsNewAction.MARK_SEEN
        else -> WhatsNewAction.SHOW
    }
}

@Suppress("DEPRECATION")
fun currentVersionCode(context: Context): Int = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else info.versionCode
} catch (e: Exception) { 0 }

fun currentVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
} catch (e: Exception) { "" }

/** Dialog "Neu in Version X" mit scrollbarer Stichpunktliste (Ressource whats_new_items). */
@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val items = stringArrayResource(R.array.whats_new_items)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.whats_new_title, currentVersionName(context))) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                items.forEach { item ->
                    Row(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text("• ", style = MaterialTheme.typography.bodyMedium)
                        Text(item, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.whats_new_ok)) }
        }
    )
}
