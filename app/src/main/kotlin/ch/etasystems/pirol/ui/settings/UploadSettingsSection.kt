package ch.etasystems.pirol.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.sync.SafFolderTarget
import ch.etasystems.pirol.data.sync.SmbSettings
import ch.etasystems.pirol.data.sync.SmbTarget
import ch.etasystems.pirol.data.sync.UploadConfig
import ch.etasystems.pirol.data.sync.UploadCredentialStore
import ch.etasystems.pirol.data.sync.UploadTargetType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** T125: Upload-Ziel (Aus / Ordner / NAS-SMB) inkl. Verbindungstest und letztem Ergebnis. */
@Composable
fun UploadSettingsSection(appPreferences: AppPreferences) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val creds = remember { UploadCredentialStore(context) }

    var type by remember { mutableStateOf(UploadTargetType.fromName(appPreferences.uploadTargetType)) }
    var folderUri by remember { mutableStateOf(appPreferences.uploadFolderUri) }
    var host by remember { mutableStateOf(appPreferences.smbHost) }
    var share by remember { mutableStateOf(appPreferences.smbShare) }
    var path by remember { mutableStateOf(appPreferences.smbPath) }
    var user by remember { mutableStateOf(appPreferences.smbUser) }
    var password by remember { mutableStateOf("") }  // wird nie vorbelegt
    var hasPassword by remember { mutableStateOf(creds.hasSmbPassword()) }
    var deleteZip by remember { mutableStateOf(appPreferences.deleteLocalZipAfterUpload) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                folderUri = uri.toString()
                appPreferences.uploadFolderUri = folderUri
                testResult = null
            } catch (e: SecurityException) {
                testResult = "Ordner konnte nicht dauerhaft freigegeben werden"
            }
        }
    }

    fun setType(t: UploadTargetType) {
        type = t
        appPreferences.uploadTargetType = t.name
        testResult = null
    }

    Text("Upload-Ziel", style = MaterialTheme.typography.titleSmall)
    listOf(
        UploadTargetType.OFF to "Aus (nur lokale Kopie bei manuellem Export)",
        UploadTargetType.FOLDER to "Ordner waehlen (Geraet, Drive, OneDrive ...)",
        UploadTargetType.SMB to "NAS (SMB / Windows-Freigabe)"
    ).forEach { (t, label) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            RadioButton(selected = type == t, onClick = { setType(t) })
            Text(label)
        }
    }

    if (type == UploadTargetType.FOLDER) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (folderUri.isBlank()) "Kein Ordner gewaehlt"
            else "Ordner: " + (Uri.parse(folderUri).lastPathSegment ?: folderUri),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { folderLauncher.launch(null) }) { Text("Ordner waehlen") }
            Button(
                enabled = folderUri.isNotBlank() && !testing,
                onClick = {
                    testResult = SafFolderTarget(context, Uri.parse(folderUri)).check() ?: "Ordner ist beschreibbar"
                }
            ) { Text("Verbindung testen") }
        }
    }

    if (type == UploadTargetType.SMB) {
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = host, onValueChange = { host = it; appPreferences.smbHost = it },
            label = { Text("Host (Name oder IP)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = share, onValueChange = { share = it; appPreferences.smbShare = it },
            label = { Text("Freigabe (Share)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = path, onValueChange = { path = it; appPreferences.smbPath = it },
            label = { Text("Pfad in der Freigabe") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it; appPreferences.smbUser = it },
            label = { Text("Benutzer (ggf. DOMAENE\\Benutzer)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = {
                Text(if (hasPassword) "Passwort (gespeichert, zum Aendern neu eingeben)" else "Passwort")
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Button(
            enabled = !testing,
            onClick = {
                if (password.isNotEmpty()) {
                    if (creds.setSmbPassword(password)) {
                        hasPassword = true
                        password = ""
                    } else {
                        testResult = "Passwort konnte nicht sicher gespeichert werden"
                        return@Button
                    }
                }
                val settings = SmbSettings(host, share, path, user)
                val stored = creds.getSmbPassword()
                val errors = UploadConfig.validateSmb(settings, stored)
                if (errors.isNotEmpty()) {
                    testResult = errors.joinToString("; ")
                    return@Button
                }
                testing = true
                testResult = "Teste ..."
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            SmbTarget(settings, stored!!).testConnection()
                            "Verbindung erfolgreich"
                        } catch (e: Exception) {
                            "Fehler: " + UploadConfig.describeError(e)
                        }
                    }
                    testResult = result
                    testing = false
                }
            }
        ) { Text("Verbindung testen") }
    }

    testResult?.let {
        Spacer(Modifier.height(4.dp))
        Text(it, style = MaterialTheme.typography.bodySmall)
    }

    if (type != UploadTargetType.OFF) {
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Lokale ZIP-Kopie (Downloads/PIROL) nach Upload nicht behalten", modifier = Modifier.weight(1f))
            Switch(
                checked = deleteZip,
                onCheckedChange = { deleteZip = it; appPreferences.deleteLocalZipAfterUpload = it }
            )
        }
        Text(
            "Der Session-Ordner selbst wird nie geloescht.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(8.dp))
    val lastTime = appPreferences.lastUploadTimeMs
    Text(
        text = if (lastTime == 0L) "Letzter Upload: noch keiner"
        else "Letzter Upload (" + DateFormat.getDateTimeInstance().format(Date(lastTime)) + "): " +
            appPreferences.lastUploadResult,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
