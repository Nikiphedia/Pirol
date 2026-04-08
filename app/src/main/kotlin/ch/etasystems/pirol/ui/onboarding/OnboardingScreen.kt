package ch.etasystems.pirol.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.ModelManager
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Onboarding-Screen bei Erststart.
 * Drei Schritte: Region waehlen → Modell herunterladen/importieren → Berechtigungen erteilen.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    appPreferences: AppPreferences = koinInject(),
    modelManager: ModelManager = koinInject(),
    classifier: AudioClassifier = koinInject()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentStep by remember { mutableIntStateOf(0) }

    // --- Schritt 1: Region ---
    var selectedRegion by remember { mutableStateOf("ch_breeding") }

    // --- Schritt 2: Modell ---
    var isModelInstalled by remember { mutableStateOf(modelManager.isModelInstalled() || classifier.isModelAvailable()) }
    var isImporting by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var importError by remember { mutableStateOf<String?>(null) }
    var selectedModel by remember { mutableStateOf(ModelManager.AVAILABLE_MODELS.first()) }

    // SAF File-Picker fuer ONNX-Modell
    val modelImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImporting = true
            importError = null
            scope.launch {
                val success = modelManager.importFromUri(uri) { progress ->
                    importProgress = progress
                }
                isImporting = false
                if (success) {
                    isModelInstalled = true
                    // Classifier-Session zuruecksetzen damit das neue Modell geladen wird
                    classifier.resetSession()
                    Toast.makeText(context, "Modell importiert", Toast.LENGTH_SHORT).show()
                } else {
                    importError = "Import fehlgeschlagen — Datei zu klein oder ungueltig"
                }
            }
        }
    }

    // --- Schritt 3: Berechtigungen ---
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    // Alle benoetigten Permissions auf einmal anfragen
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        micGranted = results[Manifest.permission.RECORD_AUDIO] == true
        locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Fortschrittsanzeige
        Text(
            text = "Schritt ${currentStep + 1} von 3",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (currentStep + 1) / 3f },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp))

        when (currentStep) {
            // ---- SCHRITT 1: Region waehlen ----
            0 -> {
                Text(
                    text = "Willkommen bei PIROL",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Waehle deine Region fuer die Artenerkennung:",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(24.dp))

                val regionOptions = listOf(
                    "ch_breeding" to "Schweizer Brutvoegel (180 Arten)",
                    "dach" to "Mitteleuropa (350+ Arten)",
                    null to "Alle Arten (11'560)"
                )

                Column(modifier = Modifier.selectableGroup()) {
                    regionOptions.forEach { (regionId, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedRegion == (regionId ?: ""),
                                    onClick = { selectedRegion = regionId ?: "" },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedRegion == (regionId ?: ""),
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        // Region speichern
                        appPreferences.regionFilter = selectedRegion.ifEmpty { null }
                        currentStep = 1
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Weiter")
                }
            }

            // ---- SCHRITT 2: Modell herunterladen / importieren ----
            1 -> {
                Text(
                    text = "BirdNET V3.0 Modell",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Fuer die Artenerkennung wird das BirdNET-Modell benoetigt.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Status-Anzeige
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isModelInstalled) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Installiert (${modelManager.getInstalledModelSizeMB()} MB)",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            Icons.Filled.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Nicht installiert",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (!isModelInstalled) {
                    // --- Modell-Auswahl ---
                    Text(
                        text = "Modell-Variante waehlen:",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(modifier = Modifier.selectableGroup()) {
                        ModelManager.AVAILABLE_MODELS.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selectedModel == model,
                                        onClick = { selectedModel = model },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedModel == model,
                                    onClick = null
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = model.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "${model.expectedSizeMB} MB — ${model.speciesCount} Arten",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isDownloading) {
                        // Fortschrittsbalken waehrend Download
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Wird heruntergeladen...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${(downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else if (isImporting) {
                        // Fortschrittsbalken waehrend SAF-Import
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { importProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${(importProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        // Download-Button
                        Button(
                            onClick = {
                                isDownloading = true
                                importError = null
                                scope.launch {
                                    val success = modelManager.downloadModel(selectedModel) { progress ->
                                        downloadProgress = progress
                                    }
                                    isDownloading = false
                                    if (success) {
                                        isModelInstalled = true
                                        classifier.resetSession()
                                        Toast.makeText(context, "Modell heruntergeladen", Toast.LENGTH_SHORT).show()
                                    } else {
                                        importError = "Download fehlgeschlagen — bitte Internetverbindung pruefen"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Herunterladen (${selectedModel.expectedSizeMB} MB)")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // SAF-Import als Alternative
                        TextButton(
                            onClick = {
                                modelImportLauncher.launch(arrayOf("*/*"))
                            }
                        ) {
                            Text("Modell-Datei manuell auswaehlen...")
                        }
                    }
                }

                // Fehlermeldung
                if (importError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = importError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { currentStep = 0 }) {
                        Text("Zurueck")
                    }
                    Row {
                        if (!isModelInstalled) {
                            TextButton(onClick = { currentStep = 2 }) {
                                Text("Ueberspringen")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Button(
                            onClick = { currentStep = 2 },
                            enabled = isModelInstalled
                        ) {
                            Text("Weiter")
                        }
                    }
                }
            }

            // ---- SCHRITT 3: Berechtigungen ----
            2 -> {
                Text(
                    text = "Berechtigungen",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "PIROL benoetigt folgende Berechtigungen:",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Mikrofon-Status
                PermissionRow(
                    label = "Mikrofon (Artenerkennung)",
                    granted = micGranted
                )
                Spacer(modifier = Modifier.height(8.dp))

                // GPS-Status
                PermissionRow(
                    label = "GPS (Beobachtungen verorten)",
                    granted = locationGranted
                )
                Spacer(modifier = Modifier.height(24.dp))

                if (!micGranted || !locationGranted) {
                    OutlinedButton(
                        onClick = {
                            val perms = mutableListOf<String>()
                            if (!micGranted) {
                                perms.add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            if (!locationGranted) {
                                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
                                perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                            permissionLauncher.launch(perms.toTypedArray())
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Berechtigungen erteilen")
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { currentStep = 1 }) {
                        Text("Zurueck")
                    }
                    Button(
                        onClick = {
                            appPreferences.onboardingCompleted = true
                            onFinished()
                        }
                    ) {
                        Text("Fertig")
                    }
                }
            }
        }
    }
}

/** Zeile fuer eine einzelne Berechtigung mit Status-Icon */
@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
