package ch.etasystems.pirol.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Zustand der Audio-Permission.
 */
enum class PermissionState {
    Granted,
    Denied,
    PermanentlyDenied
}

/**
 * Ermittelt die benoetigten Permissions fuer Audio-Aufnahme.
 * Ab Android 13 (API 33): RECORD_AUDIO + POST_NOTIFICATIONS
 * Darunter: nur RECORD_AUDIO
 */
fun requiredAudioPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }
}

/**
 * Prueft ob alle Audio-Permissions erteilt sind.
 */
fun areAudioPermissionsGranted(context: Context): Boolean {
    return requiredAudioPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Composable das den Permission-Flow handelt und bei Bedarf einen Dialog anzeigt.
 *
 * @param onPermissionResult Callback mit dem Ergebnis (Granted, Denied, PermanentlyDenied)
 * @param triggerRequest Auf true setzen um den Request auszuloesen
 */
@Composable
fun AudioPermissionHandler(
    triggerRequest: Boolean,
    onPermissionResult: (PermissionState) -> Unit
) {
    val context = LocalContext.current
    var showRationale by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var hasRequestedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            onPermissionResult(PermissionState.Granted)
        } else {
            // Pruefen ob "Nicht mehr fragen" gewaehlt wurde
            val activity = context as? android.app.Activity
            val permanentlyDenied = activity != null && requiredAudioPermissions().any { perm ->
                !results.getOrDefault(perm, false) &&
                        !activity.shouldShowRequestPermissionRationale(perm)
            }
            if (permanentlyDenied && hasRequestedOnce) {
                showSettingsDialog = true
                onPermissionResult(PermissionState.PermanentlyDenied)
            } else {
                showRationale = true
                onPermissionResult(PermissionState.Denied)
            }
        }
        hasRequestedOnce = true
    }

    // Trigger-Mechanismus: Wenn triggerRequest auf true gesetzt wird
    LaunchedEffect(triggerRequest) {
        if (!triggerRequest) return@LaunchedEffect

        if (areAudioPermissionsGranted(context)) {
            onPermissionResult(PermissionState.Granted)
        } else {
            permissionLauncher.launch(requiredAudioPermissions())
        }
    }

    // Erklaerungsdialog (nach erstem Deny)
    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("Mikrofon-Zugriff benötigt") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "PIROL benötigt Mikrofon-Zugriff für die akustische Artenerkennung.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Ohne diese Berechtigung kann keine Live-Aufnahme gestartet werden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    permissionLauncher.launch(requiredAudioPermissions())
                }) {
                    Text("Erneut versuchen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    // Settings-Dialog (nach permanentem Deny)
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Berechtigung dauerhaft verweigert") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Die Mikrofon-Berechtigung wurde dauerhaft verweigert.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Bitte aktiviere die Berechtigung in den App-Einstellungen unter " +
                                "\"Berechtigungen → Mikrofon\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSettingsDialog = false
                    // App-Settings oeffnen
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Einstellungen öffnen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }
}
