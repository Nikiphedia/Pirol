package ch.etasystems.pirol.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
 * Zustand der Location-Permission.
 */
enum class LocationPermissionState {
    Granted,
    Denied,
    PermanentlyDenied
}

/**
 * Benoetigte Location-Permissions.
 */
fun requiredLocationPermissions(): Array<String> {
    return arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
}

/**
 * Prueft ob alle Location-Permissions erteilt sind.
 */
fun areLocationPermissionsGranted(context: Context): Boolean {
    return requiredLocationPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Composable das den Location-Permission-Flow handelt und bei Bedarf einen Dialog anzeigt.
 * Analog zu AudioPermissionHandler aufgebaut.
 *
 * @param triggerRequest Auf true setzen um den Request auszuloesen
 * @param onPermissionResult Callback mit dem Ergebnis
 */
@Composable
fun LocationPermissionHandler(
    triggerRequest: Boolean,
    onPermissionResult: (LocationPermissionState) -> Unit
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
            onPermissionResult(LocationPermissionState.Granted)
        } else {
            // Pruefen ob "Nicht mehr fragen" gewaehlt wurde
            val activity = context as? android.app.Activity
            val permanentlyDenied = activity != null && requiredLocationPermissions().any { perm ->
                !results.getOrDefault(perm, false) &&
                        !activity.shouldShowRequestPermissionRationale(perm)
            }
            if (permanentlyDenied && hasRequestedOnce) {
                showSettingsDialog = true
                onPermissionResult(LocationPermissionState.PermanentlyDenied)
            } else {
                showRationale = true
                onPermissionResult(LocationPermissionState.Denied)
            }
        }
        hasRequestedOnce = true
    }

    // Trigger-Mechanismus: Wenn triggerRequest auf true gesetzt wird
    LaunchedEffect(triggerRequest) {
        if (!triggerRequest) return@LaunchedEffect

        if (areLocationPermissionsGranted(context)) {
            onPermissionResult(LocationPermissionState.Granted)
        } else {
            permissionLauncher.launch(requiredLocationPermissions())
        }
    }

    // Erklaerungsdialog (nach erstem Deny)
    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("GPS-Zugriff benötigt") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "PIROL benötigt GPS-Zugriff um Beobachtungen zu verorten.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Ohne diese Berechtigung werden Detektionen ohne Koordinaten gespeichert.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    permissionLauncher.launch(requiredLocationPermissions())
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
                        "Die GPS-Berechtigung wurde dauerhaft verweigert.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Bitte aktiviere die Berechtigung in den App-Einstellungen unter " +
                                "\"Berechtigungen → Standort\".",
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
