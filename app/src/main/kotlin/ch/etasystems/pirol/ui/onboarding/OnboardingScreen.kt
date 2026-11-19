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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import ch.etasystems.pirol.ml.ModelProfile
import ch.etasystems.pirol.ui.components.ModelProfileDialog
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

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
    // T103a: Standard = BirdNET 3.0 final FP16 (E1), erster Katalog-Eintrag
    var selectedModel by remember { mutableStateOf(ModelProfile.CATALOG.first()) }
    var pendingImport by remember { mutableStateOf<ModelManager.ImportResult.NeedsProfile?>(null) }

    // T103a: Ergebnis eines Imports auswerten (Erfolg, Rueckfrage zum Profil, Fehler)
    fun handleImportResult(result: ModelManager.ImportResult) {
        when (result) {
            is ModelManager.ImportResult.Success -> {
                isModelInstalled = modelManager.isModelInstalled()
                // Classifier-Session zuruecksetzen damit das neue Modell geladen wird
                classifier.resetSession()
                Toast.makeText(context, AppStrings.get(R.string.onboarding_importiert, result.profile.name), Toast.LENGTH_SHORT).show()
            }
            is ModelManager.ImportResult.NeedsProfile -> pendingImport = result
            is ModelManager.ImportResult.Failed -> importError = result.message
        }
    }

    // T102: laufender Download des ModelManager (ueberlebt das Verlassen des Screens)
    val runningDownload by modelManager.downloadProgress.collectAsState()
    var sawDownload by remember { mutableStateOf(false) }
    var partialTick by remember { mutableIntStateOf(0) }  // T104: erzwingt Neuberechnung der Teildatei-Groesse
    LaunchedEffect(runningDownload) {
        if (runningDownload != null) {
            sawDownload = true
        } else if (sawDownload) {
            sawDownload = false
            isModelInstalled = modelManager.isModelInstalled() || classifier.isModelAvailable()
        }
    }

    // SAF File-Picker fuer ONNX-Modell
    val modelImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImporting = true
            importError = null
            scope.launch {
                val result = modelManager.importFromUri(uri) { progress ->
                    importProgress = progress
                }
                isImporting = false
                handleImportResult(result)
            }
        }
    }

    // T103a: Rueckfrage, wenn das Profil der importierten Datei nicht erkennbar ist
    pendingImport?.let { pending ->
        ModelProfileDialog(
            fileSizeMB = pending.sizeMB,
            onConfirm = { profile ->
                pendingImport = null
                isImporting = true
                scope.launch {
                    val result = modelManager.assignImportedModel(pending.pendingFileName, profile)
                    isImporting = false
                    handleImportResult(result)
                }
            },
            onDismiss = {
                modelManager.discardPendingImport(pending.pendingFileName)
                pendingImport = null
            }
        )
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
            text = stringResource(R.string.onboarding_schritt_von_3, currentStep + 1),
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
                    text = stringResource(R.string.onboarding_willkommen_bei_pirol),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.onboarding_waehle_deine_region_fuer_die),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(24.dp))

                val regionOptions = listOf(
                    "ch_breeding" to AppStrings.get(R.string.onboarding_schweizer_brutvoegel_180_arten),
                    // T106: ID muss in region_sets.json existieren ("dach" gab es nie → Filter war aus)
                    "central_europe" to AppStrings.get(R.string.onboarding_mitteleuropa_302_arten),
                    null to AppStrings.get(R.string.onboarding_alle_arten_11_560)
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
                    Text(stringResource(R.string.onboarding_weiter))
                }
            }

            // ---- SCHRITT 2: Modell herunterladen / importieren ----
            1 -> {
                Text(
                    text = stringResource(R.string.onboarding_birdnet_v3_0_modell),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.onboarding_fuer_die_artenerkennung_wird_das),
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
                            text = stringResource(R.string.onboarding_installiert_mb, modelManager.getInstalledModelSizeMB()),
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
                            text = stringResource(R.string.onboarding_nicht_installiert),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (!isModelInstalled) {
                    // --- Modell-Auswahl ---
                    Text(
                        text = stringResource(R.string.onboarding_modell_variante_waehlen),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(modifier = Modifier.selectableGroup()) {
                        ModelProfile.CATALOG.filter { it.downloadUrl != null }.forEach { model ->
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
                                        text = stringResource(R.string.onboarding_mb_klassen, model.expectedSizeMB, model.speciesCount, model.licenseNote),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isDownloading || runningDownload != null) {
                        // Fortschrittsbalken waehrend Download
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.onboarding_wird_heruntergeladen),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { runningDownload ?: downloadProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${((runningDownload ?: downloadProgress) * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            // T104: Abbrechen behaelt die Teildatei zum Fortsetzen
                            OutlinedButton(onClick = { modelManager.cancelDownload() }) {
                                Text(stringResource(R.string.common_cancel))
                            }
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
                        val partialBytes = remember(selectedModel, partialTick, runningDownload) {
                            modelManager.getPartialBytes(selectedModel)
                        }
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
                                        Toast.makeText(context, AppStrings.get(R.string.onboarding_modell_heruntergeladen), Toast.LENGTH_SHORT).show()
                                    } else {
                                        importError = if (modelManager.lastDownloadError == ModelManager.ERROR_CANCELLED) AppStrings.get(R.string.onboarding_download_abgebrochen_teil_bleibt_zum)
                                        else AppStrings.get(R.string.onboarding_download_fehlgeschlagen) + (modelManager.lastDownloadError ?: AppStrings.get(R.string.onboarding_internetverbindung_pruefen))  // T121/T104
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (partialBytes > 0L) AppStrings.get(R.string.onboarding_fortsetzen_von_mb, partialBytes / 1_000_000, selectedModel.expectedSizeMB)
                                else AppStrings.get(R.string.onboarding_herunterladen_mb, selectedModel.expectedSizeMB)
                            )
                        }
                        if (partialBytes > 0L) {
                            TextButton(onClick = {
                                modelManager.discardPartialDownload(selectedModel)
                                partialTick++
                            }) {
                                Text(stringResource(R.string.onboarding_verwerfen))
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // SAF-Import als Alternative
                        TextButton(
                            onClick = {
                                modelImportLauncher.launch(arrayOf("*/*"))
                            }
                        ) {
                            Text(stringResource(R.string.onboarding_modell_datei_manuell_auswaehlen))
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
                        Text(stringResource(R.string.common_back))
                    }
                    Row {
                        if (!isModelInstalled) {
                            TextButton(onClick = { currentStep = 2 }) {
                                Text(stringResource(R.string.onboarding_ueberspringen))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Button(
                            onClick = { currentStep = 2 },
                            enabled = isModelInstalled
                        ) {
                            Text(stringResource(R.string.onboarding_weiter))
                        }
                    }
                }
            }

            // ---- SCHRITT 3: Berechtigungen ----
            2 -> {
                Text(
                    text = stringResource(R.string.onboarding_berechtigungen),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.onboarding_pirol_benoetigt_folgende_berechtigun),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Mikrofon-Status
                PermissionRow(
                    label = stringResource(R.string.onboarding_mikrofon_artenerkennung),
                    granted = micGranted
                )
                Spacer(modifier = Modifier.height(8.dp))

                // GPS-Status
                PermissionRow(
                    label = stringResource(R.string.onboarding_gps_beobachtungen_verorten),
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
                        Text(stringResource(R.string.onboarding_berechtigungen_erteilen))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { currentStep = 1 }) {
                        Text(stringResource(R.string.common_back))
                    }
                    Button(
                        onClick = {
                            appPreferences.onboardingCompleted = true
                            onFinished()
                        }
                    ) {
                        Text(stringResource(R.string.ui_fertig))
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
