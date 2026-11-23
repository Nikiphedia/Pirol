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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import ch.etasystems.pirol.ml.GeoModelFiles
import ch.etasystems.pirol.ml.ModelProfile
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ui.AppLanguage
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.saveable.rememberSaveable
import ch.etasystems.pirol.ui.components.ModelProfileDialog
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

private enum class SetupStep { LANGUAGE, LEVEL, PURPOSE, EXPERT, LIST, MODEL, PERMISSIONS, SUMMARY }

/**
 * T226: Einrichtungsassistent (Erststart und ueber Settings).
 * Sprache, Niveau, Einsatzzweck, (Experte), Artenliste, Modell/Downloads, Berechtigungen, Zusammenfassung.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    appPreferences: AppPreferences = koinInject(),
    modelManager: ModelManager = koinInject(),
    classifier: AudioClassifier = koinInject(),
    speciesNameResolver: SpeciesNameResolver = koinInject()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // T226: Zustand ueberlebt den Activity-Neustart beim Sprachwechsel
    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    var expert by rememberSaveable { mutableStateOf(appPreferences.expertMode) }
    var purposeNames by rememberSaveable { mutableStateOf(SetupPurpose.FIELD_BIRDS.name) }
    var listName by rememberSaveable { mutableStateOf(SetupList.AUTO.name) }
    var thresholdOv by rememberSaveable { mutableFloatStateOf(-1f) }
    var overlapOv by rememberSaveable { mutableIntStateOf(-1) }
    var secondaryOv by rememberSaveable { mutableIntStateOf(-1) }
    var secondaryModelOv by rememberSaveable { mutableStateOf("") }
    val purposes = purposeNames.split(',').mapNotNull { n -> SetupPurpose.entries.firstOrNull { it.name == n } }.toSet()
    val listChoice = SetupList.entries.firstOrNull { it.name == listName } ?: SetupList.AUTO
    val steps = remember(expert) {
        listOfNotNull(
            SetupStep.LANGUAGE, SetupStep.LEVEL, SetupStep.PURPOSE,
            SetupStep.EXPERT.takeIf { expert }, SetupStep.LIST, SetupStep.MODEL, SetupStep.PERMISSIONS, SetupStep.SUMMARY
        )
    }
    val step = steps[currentStep.coerceIn(0, steps.lastIndex)]
    fun next() { currentStep = (currentStep + 1).coerceAtMost(steps.lastIndex) }
    fun prev() { currentStep = (currentStep - 1).coerceAtLeast(0) }
    // T228: Zweitmodell-Kandidaten = installierte Modelle, die nicht das aktive sind
    val secondaryModels = remember(currentStep) {
        modelManager.listInstalledModels().filter { !it.isActive }.map { it.fileName to it.name }
    }
    val secondaryCandidates = secondaryModels.map { it.first }
    val baseChoices = SetupChoices(expert = expert, purposes = purposes, list = listChoice)
    val profileBase = SetupProfile.resolve(baseChoices, secondaryCandidates)
    val choices = baseChoices.copy(
        thresholdOverride = thresholdOv.takeIf { expert && it >= 0f },
        overlapOverride = overlapOv.takeIf { expert && it >= 0 },
        secondaryOverride = if (expert && secondaryOv >= 0) secondaryOv == 1 else null,
        secondaryModel = secondaryModelOv.takeIf { expert && it.isNotEmpty() }
    )
    val resolved = SetupProfile.resolve(choices, secondaryCandidates)
    var geoInstalled by remember { mutableStateOf(GeoModelFiles.isInstalled(context)) }
    var geoProgress by remember { mutableStateOf<Float?>(null) }
    var geoError by remember { mutableStateOf<String?>(null) }

    // --- Schritt 2: Modell ---
    var isModelInstalled by remember { mutableStateOf(modelManager.isModelInstalled() || classifier.isModelAvailable()) }
    var isImporting by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var importError by remember { mutableStateOf<String?>(null) }
    // T103a: Standard = BirdNET 3.0 final FP16 (E1), erster Katalog-Eintrag
    // T145: Mehrfachwahl, Download nacheinander
    var selectedIds by remember { mutableStateOf(setOf(ModelProfile.CATALOG.first().id)) }
    var queueIndex by remember { mutableIntStateOf(0) }
    var queueTotal by remember { mutableIntStateOf(0) }
    var queueErrors by remember { mutableStateOf<List<String>>(emptyList()) }
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

    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsGranted = results[Manifest.permission.POST_NOTIFICATIONS] == true || notificationsGranted
        }
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
            text = stringResource(R.string.setup_step, currentStep + 1, steps.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (currentStep + 1) / steps.size.toFloat() },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp))

        when (step) {
            SetupStep.LANGUAGE -> {
                SetupLanguagePage(AppLanguage.currentTag()) { tag ->
                    if (!appPreferences.speciesLanguageExplicit) {
                        speciesNameResolver.language = AppLanguage.speciesLanguageForTag(tag, AppLanguage.systemLanguage())
                    }
                    AppLanguage.apply(tag)  // startet die Activity neu, Schritt bleibt gespeichert
                }
                Spacer(modifier = Modifier.height(16.dp))
                SetupNavRow(null, stringResource(R.string.onboarding_weiter)) { next() }
            }
            SetupStep.LEVEL -> {
                SetupLevelPage(expert) { expert = it }
                Spacer(modifier = Modifier.height(16.dp))
                SetupNavRow({ prev() }, stringResource(R.string.onboarding_weiter)) { next() }
            }
            SetupStep.PURPOSE -> {
                SetupPurposePage(purposes) { p ->
                    val n = if (p in purposes) purposes - p else purposes + p
                    if (n.isNotEmpty()) purposeNames = n.sortedBy { it.ordinal }.joinToString(",") { it.name }
                }
                Spacer(modifier = Modifier.height(16.dp))
                SetupNavRow({ prev() }, stringResource(R.string.onboarding_weiter)) { next() }
            }
            SetupStep.EXPERT -> {
                SetupExpertPage(
                    threshold = choices.thresholdOverride ?: profileBase.threshold,
                    overlap = choices.overlapOverride ?: profileBase.overlapPercent,
                    secondary = choices.secondaryOverride ?: (profileBase.secondaryModelFileName != null),
                    secondaryModels = secondaryModels,
                    secondaryChosen = resolved.secondaryModelFileName ?: secondaryCandidates.firstOrNull(),
                    onThreshold = { thresholdOv = it }, onOverlap = { overlapOv = it },
                    onSecondary = { secondaryOv = if (it) 1 else 0 },
                    onSecondaryModel = { secondaryModelOv = it }
                )
                Spacer(modifier = Modifier.height(16.dp))
                SetupNavRow({ prev() }, stringResource(R.string.onboarding_weiter)) { next() }
            }
            SetupStep.LIST -> {
                SetupListPage(listChoice, SetupPurpose.ORTHOPTERA_AMPHIBIANS in purposes) { listName = it.name }
                Spacer(modifier = Modifier.height(16.dp))
                SetupNavRow({ prev() }, stringResource(R.string.onboarding_weiter)) { next() }
            }

            // ---- SCHRITT 2: Modell herunterladen / importieren ----
            SetupStep.MODEL -> {
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

                    Column {
                        ModelProfile.CATALOG.filter { it.downloadUrl != null }.forEach { model ->
                            val checked = model.id in selectedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = checked,
                                        enabled = !isDownloading && runningDownload == null,
                                        onValueChange = {
                                            selectedIds = if (checked) selectedIds - model.id else selectedIds + model.id
                                        },
                                        role = Role.Checkbox
                                    )
                                    .padding(vertical = 8.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = null
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
                                text = stringResource(R.string.onboarding_wird_heruntergeladen) +
                                    if (queueTotal > 1) " " + stringResource(R.string.onboarding_modell_x_von_n, queueIndex, queueTotal) else "",
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
                        val queue = ModelSelection.queue(selectedIds, ModelProfile.CATALOG)
                        val partialBytes = remember(selectedIds, partialTick, runningDownload) {
                            queue.sumOf { modelManager.getPartialBytes(it) }
                        }
                        val totalMB = queue.sumOf { it.expectedSizeMB }
                        Button(
                            enabled = queue.isNotEmpty(),
                            onClick = {
                                isDownloading = true
                                importError = null
                                queueErrors = emptyList()
                                queueTotal = queue.size
                                scope.launch {
                                    val succeeded = mutableListOf<ModelProfile>()
                                    val errors = mutableListOf<String>()
                                    for ((i, model) in queue.withIndex()) {
                                        queueIndex = i + 1
                                        downloadProgress = 0f
                                        val ok = modelManager.downloadModel(model) { progress ->
                                            downloadProgress = progress
                                        }
                                        if (ok) {
                                            succeeded += model
                                        } else if (modelManager.lastDownloadError == ModelManager.ERROR_CANCELLED) {
                                            // Abbrechen beendet die ganze Warteschlange, Teildatei bleibt (T104)
                                            errors += AppStrings.get(R.string.onboarding_download_abgebrochen_teil_bleibt_zum)
                                            break
                                        } else {
                                            errors += model.name + ": " + (modelManager.lastDownloadError ?: AppStrings.get(R.string.onboarding_internetverbindung_pruefen))  // T121/T104
                                        }
                                    }
                                    isDownloading = false
                                    queueTotal = 0
                                    queueErrors = errors
                                    // Aktives Modell: V3.0 final, sonst das erste erfolgreiche
                                    ModelSelection.pickActive(succeeded, ModelProfile.V3_FINAL_FP16.id)?.let {
                                        modelManager.setActiveModel(it.fileName)
                                    }
                                    isModelInstalled = modelManager.isModelInstalled() || classifier.isModelAvailable()
                                    partialTick++
                                    if (succeeded.isNotEmpty()) {
                                        classifier.resetSession()
                                        Toast.makeText(context, AppStrings.get(R.string.onboarding_modell_heruntergeladen), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (partialBytes > 0L) AppStrings.get(R.string.onboarding_fortsetzen_von_mb, partialBytes / 1_000_000, totalMB)
                                else AppStrings.get(R.string.onboarding_herunterladen_mb, totalMB)
                            )
                        }
                        if (partialBytes > 0L) {
                            TextButton(onClick = {
                                queue.forEach { modelManager.discardPartialDownload(it) }
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

                // T145: Fehler pro Modell
                queueErrors.forEach {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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

                // T226: Geomodel fuer "Automatisch (Standort und Woche)"
                if (listChoice == SetupList.AUTO) {
                    Spacer(modifier = Modifier.height(24.dp))
                    if (geoInstalled) {
                        Text(stringResource(R.string.settings_geo_installiert), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(stringResource(R.string.settings_geo_fehlt), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        val gp = geoProgress
                        if (gp != null) {
                            LinearProgressIndicator(progress = { gp }, modifier = Modifier.fillMaxWidth())
                        } else {
                            OutlinedButton(
                                onClick = {
                                    geoError = null
                                    geoProgress = 0f
                                    scope.launch {
                                        val ok = modelManager.downloadGeomodel { geoProgress = it }
                                        geoProgress = null
                                        geoInstalled = ok && GeoModelFiles.isInstalled(context)
                                        if (!ok) geoError = modelManager.lastDownloadError ?: "?"
                                    }
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(stringResource(R.string.settings_geo_laden)) }
                        }
                        geoError?.let {
                            Text(AppStrings.get(R.string.settings_geo_fehler, it), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { prev() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.common_back))
                    }
                    Row {
                        if (!isModelInstalled) {
                            TextButton(onClick = { next() }) {
                                Text(stringResource(R.string.onboarding_ueberspringen))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Button(
                            onClick = { next() },
                            enabled = isModelInstalled
                        ) {
                            Text(stringResource(R.string.onboarding_weiter))
                        }
                    }
                }
            }

            // ---- SCHRITT 3: Berechtigungen ----
            SetupStep.PERMISSIONS -> {
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PermissionRow(
                        label = stringResource(R.string.setup_perm_notifications),
                        granted = notificationsGranted
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))

                if (!micGranted || !locationGranted || !notificationsGranted) {
                    OutlinedButton(
                        onClick = {
                            val perms = mutableListOf<String>()
                            if (!micGranted) perms.add(Manifest.permission.RECORD_AUDIO)
                            if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                perms.add(Manifest.permission.POST_NOTIFICATIONS)
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
                    TextButton(onClick = { prev() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.common_back))
                    }
                    Button(onClick = { next() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.onboarding_weiter))
                    }
                }
            }

            SetupStep.SUMMARY -> {
                val active = if (modelManager.isModelInstalled()) modelManager.getActiveProfile().name else null
                SetupSummaryPage(choices, resolved, active)
                Spacer(modifier = Modifier.height(16.dp))
                SetupNavRow({ prev() }, stringResource(R.string.ui_fertig)) {
                    SetupProfile.apply(resolved, expert, appPreferences)
                    appPreferences.onboardingCompleted = true
                    onFinished()
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
