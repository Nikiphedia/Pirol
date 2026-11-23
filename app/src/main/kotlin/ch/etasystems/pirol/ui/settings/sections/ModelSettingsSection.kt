package ch.etasystems.pirol.ui.settings.sections

import ch.etasystems.pirol.ui.settings.SettingsSection
import ch.etasystems.pirol.ui.settings.SettingsAdvancedBlock
import androidx.compose.material3.Checkbox
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import ch.etasystems.pirol.ml.ModelTableRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.ModelManager
import ch.etasystems.pirol.ml.ModelProfile
import ch.etasystems.pirol.ml.ModelRoles
import ch.etasystems.pirol.ui.components.ModelProfileDialog
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

/** T241: Sektion Modell & Erkennung. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
internal fun ModelSettingsSection(
    appPreferences: AppPreferences,
    expert: Boolean,
    classifier: AudioClassifier,
    modelManager: ModelManager,
    recordingPipeline: ch.etasystems.pirol.audio.RecordingPipeline
) {
    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    // Detektions-Cluster State (T70)
    var clusterEnabled by remember { mutableStateOf(appPreferences.clusterEnabled) }
    var clusterRadiusM by remember { mutableIntStateOf(appPreferences.clusterRadiusM) }
    var clusterTimeWindowMin by remember { mutableIntStateOf(appPreferences.clusterTimeWindowMin) }

    // Aehnliche Arten State (T75)
    var similarEnabled by remember { mutableStateOf(appPreferences.similarEnabled) }
    var similarLines by remember { mutableFloatStateOf(appPreferences.similarLines) }
    var embeddingDbMaxEntries by remember { mutableIntStateOf(appPreferences.embeddingDbMaxEntries) }  // T97

    // Multi-Label State (T71)
    var selectedTopK by remember { mutableIntStateOf(appPreferences.topK) }
    var selectedAltFloor by remember { mutableFloatStateOf(appPreferences.alternativesFloor) }
    var selectedConfidence by remember { mutableFloatStateOf(appPreferences.confidenceThreshold) }

    SettingsSection(
        sectionKey = "s_model",
        title = stringResource(R.string.settings_section_model_detection),
        appPreferences = appPreferences,
        defaultExpanded = false
    ) {
        Text(stringResource(R.string.settings_modell), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // Installierte Modelle auflisten
        var installedModels by remember { mutableStateOf(modelManager.listInstalledModels()) }
        var isDownloading by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableFloatStateOf(0f) }
        // T102: laufender Download des ModelManager (ueberlebt das Verlassen des Screens)
        val runningDownload by modelManager.downloadProgress.collectAsState()
        var sawDownload by remember { mutableStateOf(false) }
        var partialTick by remember { mutableIntStateOf(0) }  // T104: erzwingt Neuberechnung der Teildatei-Groesse
        LaunchedEffect(runningDownload) {
            if (runningDownload != null) {
                sawDownload = true
            } else if (sawDownload) {
                sawDownload = false
                installedModels = modelManager.listInstalledModels()
            }
        }
        var isImporting by remember { mutableStateOf(false) }
        var importProgress by remember { mutableFloatStateOf(0f) }
        var modelError by remember { mutableStateOf<String?>(null) }
        var pendingImport by remember { mutableStateOf<ModelManager.ImportResult.NeedsProfile?>(null) }
        var deleteCandidate by remember { mutableStateOf<ModelManager.InstalledModel?>(null) }

        // T151a: Loeschen eines importierten Modells bestaetigen (Dateien lassen sich nicht wiederherstellen)
        deleteCandidate?.let { model ->
            AlertDialog(
                onDismissRequest = { deleteCandidate = null },
                text = { Text(stringResource(R.string.model_delete_confirm, model.name, model.sizeMB)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleteCandidate = null
                        val deleted = modelManager.deleteModel(model.fileName)
                        installedModels = modelManager.listInstalledModels()
                        // T239: bei gesperrtem Modell (Aufnahme/Rescan) keine falsche Erfolgsmeldung
                        if (deleted) Toast.makeText(context, AppStrings.get(R.string.model_deleted, model.name), Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.common_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteCandidate = null }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }

        // T103a: Ergebnis eines Imports auswerten (Erfolg, Rueckfrage zum Profil, Fehler)
        fun handleImportResult(result: ModelManager.ImportResult) {
            when (result) {
                is ModelManager.ImportResult.Success -> {
                    classifier.resetSession()
                    installedModels = modelManager.listInstalledModels()
                    Toast.makeText(context, AppStrings.get(R.string.onboarding_importiert, result.profile.name), Toast.LENGTH_SHORT).show()
                }
                is ModelManager.ImportResult.NeedsProfile -> pendingImport = result
                is ModelManager.ImportResult.Failed -> modelError = result.message
            }
        }

        // SAF-Import fuer Modell (T37); T151a: Mehrfachauswahl fuer .onnx + .pirol.json + Label-CSV
        val modelImportLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) {
                isImporting = true
                modelError = null
                scope.launch {
                    val result = modelManager.importUris(uris) { progress ->
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

        if (installedModels.isNotEmpty()) {
            // Aktives Modell anzeigen
            Text(
                text = // T145: Name und Artenzahl direkt aus dem aktiven Profil (classifier.labelCount hinkt nach Wechsel hinterher)
                stringResource(
                    R.string.settings_arten,
                    installedModels.firstOrNull { it.isActive }?.profile?.name ?: classifier.modelName,
                    ch.etasystems.pirol.ui.onboarding.ModelSelection.formatSpeciesCount(
                        installedModels.firstOrNull { it.isActive }?.profile?.speciesCount ?: classifier.labelCount
                    )
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            Text(
                text = stringResource(R.string.settings_kein_modell_installiert),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        run {
            // T223: Modelltabelle mit Primaer (Radio), Sekundaer (Checkbox) und Loeschen;
            // T227: nicht geladene Katalogmodelle als Zeile mit Lade-Symbol, Sekundaer nur im Expertenmodus
            val downloadingFile by modelManager.downloadingFile.collectAsState()
            val recording by recordingPipeline.runningState.collectAsState()
            var secondaryFile by remember { mutableStateOf(appPreferences.secondaryModelFileName) }
            var secondaryConf by remember { mutableFloatStateOf(appPreferences.secondaryConfidenceThreshold) }
            var secondaryOverlap by remember { mutableIntStateOf(appPreferences.secondaryOverlapPercent) }
            val primaryFile = installedModels.firstOrNull { it.isActive }?.fileName ?: ""
            val anyDownload = isDownloading || runningDownload != null
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.weight(1f))
                Text(stringResource(R.string.model_col_primary), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (expert) {
                    Text(stringResource(R.string.model_col_secondary), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Spacer(modifier = Modifier.width(48.dp))
            }
            installedModels.forEach { model ->
                val block = ModelRoles.deleteBlock(model.fileName, primaryFile, recording)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.onboarding_mb_klassen, model.sizeMB, model.profile.speciesCount, model.fileName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // T151a: importierte Modelle mit Abtastrate, Fenster und Lizenz
                        if (model.profile.source == ModelProfile.Source.IMPORTED) {
                            Text(
                                text = stringResource(
                                    R.string.model_imported_info,
                                    model.profile.sampleRate,
                                    model.profile.windowSec,
                                    model.profile.licenseNote
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // T156: Mindest-Konfidenz des Modells (Profil-JSON)
                            model.profile.minConfidence?.let { min ->
                                Text(
                                    text = stringResource(R.string.model_min_confidence, (min * 100).roundToInt()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                        RadioButton(
                            selected = model.isActive,
                            onClick = {
                                modelManager.setActiveModel(model.fileName)
                                classifier.resetSession()
                                secondaryFile = appPreferences.secondaryModelFileName
                                installedModels = modelManager.listInstalledModels()
                            }
                        )
                    }
                    if (expert) {
                        Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                            Checkbox(
                                checked = secondaryFile == model.fileName,
                                enabled = ModelTableRow.secondaryEnabled(true, model.isActive),
                                onCheckedChange = {
                                    secondaryFile = ModelRoles.toggleSecondary(primaryFile, secondaryFile, model.fileName)
                                    appPreferences.secondaryModelFileName = secondaryFile
                                }
                            )
                        }
                    }
                    // T227: installiert -> Papierkorb (nie gleichzeitig mit Lade-Symbol)
                    val trailing = ModelTableRow.trailing(true, false, false, block)
                    IconButton(enabled = trailing == ModelTableRow.Trailing.DELETE, onClick = { deleteCandidate = model }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.model_delete))
                    }
                }
                when (block) {
                    ModelRoles.DeleteBlock.PRIMARY -> Text(
                        stringResource(R.string.model_delete_blocked_primary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ModelRoles.DeleteBlock.RECORDING -> Text(
                        stringResource(R.string.model_delete_blocked_recording),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ModelRoles.DeleteBlock.NONE -> {}
                }
            }

            // T227: nicht geladene Katalogmodelle mit Lade-Symbol (ersetzt die frueheren Download-Knoepfe)
            ModelTableRow.missingCatalog(ModelProfile.CATALOG, installedModels.map { it.fileName }.toSet()).forEach { model ->
                val downloadingThis = downloadingFile == model.fileName
                val partialBytes = remember(model, partialTick, runningDownload) { modelManager.getPartialBytes(model) }
                val trailing = ModelTableRow.trailing(false, downloadingThis, anyDownload && !downloadingThis, ModelRoles.DeleteBlock.NONE)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (partialBytes > 0L) AppStrings.get(R.string.settings_fortsetzen_von_mb, model.name, partialBytes / 1_000_000, model.expectedSizeMB)
                                   else stringResource(R.string.onboarding_mb_klassen, model.expectedSizeMB, model.speciesCount, model.fileName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                        RadioButton(selected = false, enabled = ModelTableRow.primaryEnabled(false), onClick = {})
                    }
                    if (expert) {
                        Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                            Checkbox(checked = false, enabled = ModelTableRow.secondaryEnabled(false, false), onCheckedChange = {})
                        }
                    }
                    if (trailing == ModelTableRow.Trailing.PROGRESS) {
                        // Abbrechen behaelt die Teildatei zum Fortsetzen (T104)
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(progress = { runningDownload ?: downloadProgress }, modifier = Modifier.size(32.dp))
                            IconButton(onClick = { modelManager.cancelDownload() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel), modifier = Modifier.size(16.dp))
                            }
                        }
                    } else {
                        IconButton(
                            enabled = trailing == ModelTableRow.Trailing.DOWNLOAD,
                            onClick = {
                                isDownloading = true
                                modelError = null
                                scope.launch {
                                    val success = modelManager.downloadModel(model) { progress ->
                                        downloadProgress = progress
                                    }
                                    isDownloading = false
                                    if (success) {
                                        classifier.resetSession()
                                        installedModels = modelManager.listInstalledModels()
                                        Toast.makeText(context, AppStrings.get(R.string.settings_heruntergeladen, model.name), Toast.LENGTH_SHORT).show()
                                    } else {
                                        modelError = if (modelManager.lastDownloadError == ModelManager.ERROR_CANCELLED) AppStrings.get(R.string.onboarding_download_abgebrochen_teil_bleibt_zum)
                                            else AppStrings.get(R.string.onboarding_download_fehlgeschlagen) + (modelManager.lastDownloadError ?: AppStrings.get(R.string.onboarding_internetverbindung_pruefen))  // T121/T104
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.settings_modell_herunterladen))
                        }
                    }
                }
                if (downloadingThis) {
                    LinearProgressIndicator(progress = { runningDownload ?: downloadProgress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "${((runningDownload ?: downloadProgress) * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (partialBytes > 0L && trailing == ModelTableRow.Trailing.DOWNLOAD) {
                    TextButton(onClick = {
                        modelManager.discardPartialDownload(model)
                        partialTick++
                    }) {
                        Text(stringResource(R.string.settings_angefangenen_download_verwerfen))
                    }
                }
            }

            // Einstellungen je Rolle: Sekundaer nur bei gewaehltem Sekundaermodell (Expertenmodus)
            val secondaryModel = installedModels.firstOrNull { it.fileName == secondaryFile }
            if (expert && secondaryModel != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.model_secondary_settings, secondaryModel.name),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    stringResource(R.string.model_secondary_active_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(stringResource(R.string.model_secondary_threshold, (secondaryConf * 100).roundToInt()))
                Slider(
                    value = secondaryConf,
                    onValueChange = {
                        secondaryConf = it
                        appPreferences.secondaryConfidenceThreshold = it
                    },
                    valueRange = 0.1f..0.9f,
                    steps = 15
                )
                Text(stringResource(R.string.settings_ueberlappung), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(0, 50, 67).forEach { percent ->
                        RadioButton(
                            selected = secondaryOverlap == percent,
                            onClick = {
                                secondaryOverlap = percent
                                appPreferences.secondaryOverlapPercent = percent
                            }
                        )
                        Text("$percent %", modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Import eigener Modelle (Expertenmodus); Download-Fortschritt steht in der Tabellenzeile
        if (isImporting) {
            Text(stringResource(R.string.settings_wird_importiert), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { importProgress },
                modifier = Modifier.fillMaxWidth()
            )
        } else if (expert) {
            // SAF-Import Button
            OutlinedButton(onClick = {
                modelImportLauncher.launch(arrayOf("*/*"))
            }) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_anderes_modell_importieren))
            }
        }

        // Fehlermeldung
        if (modelError != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = modelError!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.settings_erkennung), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // T211: eine Konfidenz-Schwelle fuer alle Arten (auch die Top-Art)
        Text(stringResource(R.string.settings_erkennungs_schwelle_topk, (selectedConfidence * 100).roundToInt()))
        Slider(
            value = selectedConfidence,
            onValueChange = {
                selectedConfidence = it
                appPreferences.confidenceThreshold = it
            },
            valueRange = 0.1f..0.9f,
            steps = 15  // Schritt 0.05 = 16 Positionen → 15 Schritte zwischen den Endpunkten
        )
        Text(
            text = stringResource(R.string.settings_erkennungs_schwelle_hinweis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (expert) {
            Spacer(modifier = Modifier.height(8.dp))

            // TopK-Slider (Anzahl Arten pro Chunk)
            Text(stringResource(R.string.settings_max_arten_pro_chunk_topk, selectedTopK))
            Slider(
                value = selectedTopK.toFloat(),
                onValueChange = {
                    selectedTopK = it.roundToInt()
                    appPreferences.topK = selectedTopK
                },
                valueRange = 1f..5f,
                steps = 3  // 1,2,3,4,5 = 4 Schritte zwischen Endpunkten
            )
            Text(
                text = if (selectedTopK == 1) stringResource(R.string.settings_topk_1_nur_top_art)
                       else stringResource(R.string.settings_topk_bis_zu_arten_gleichzeitig, selectedTopK, selectedTopK),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            // T225: Untergrenze fuer Alternativen
            Text(stringResource(R.string.settings_alternativen_ab, (selectedAltFloor * 100).roundToInt()))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.05f, 0.10f, 0.20f, 0.30f).forEach { v ->
                    FilterChip(
                        selected = kotlin.math.abs(selectedAltFloor - v) < 0.001f,
                        onClick = {
                            selectedAltFloor = v
                            appPreferences.alternativesFloor = v
                        },
                        label = { Text("${(v * 100).roundToInt()} %") }
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_alternativen_ab_hinweis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            // Presets
            Text(stringResource(R.string.settings_voreinstellungen), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    InferenceConfig.SENSITIVE to AppStrings.get(R.string.settings_sensitiv),
                    InferenceConfig.DEFAULT to AppStrings.get(R.string.settings_standard),
                    InferenceConfig.STRICT to AppStrings.get(R.string.settings_strikt)
                ).forEach { (preset, label) ->
                    OutlinedButton(onClick = {
                        selectedConfidence = preset.confidenceThreshold
                        selectedAltFloor = preset.alternativesFloor
                        appPreferences.alternativesFloor = preset.alternativesFloor
                        appPreferences.confidenceThreshold = preset.confidenceThreshold
                        selectedTopK = preset.topK
                        appPreferences.topK = preset.topK
                    }) {
                        Text(label)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            // T52: Artvorschlaege (Top-N Kandidaten) Toggle
            var showTopNCandidates by remember { mutableStateOf(appPreferences.showTopNCandidates) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_artvorschlaege_anzeigen))
                    Text(
                        AppStrings.get(R.string.settings_alternativen_unter_jeder_detektion_a),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = showTopNCandidates,
                    onCheckedChange = {
                        showTopNCandidates = it
                        appPreferences.showTopNCandidates = it
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            SettingsAdvancedBlock(sectionKey = "adv_model", appPreferences = appPreferences) {
                // T34: Hochpassfilter Toggle
                var highpassEnabled by remember { mutableStateOf(appPreferences.highpassEnabled) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_hochpassfilter_200_hz))
                        Text(
                            AppStrings.get(R.string.settings_filtert_wind_und_trittschall),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = highpassEnabled,
                        onCheckedChange = {
                            highpassEnabled = it
                            appPreferences.highpassEnabled = it
                        }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_aenderungen_wirken_nach_neustart_der),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.settings_detektions_cluster), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                // Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            AppStrings.get(R.string.settings_detektionen_raeumlich_zeitlich_zusam),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.settings_ohne_gps_fix_wird_nur),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = clusterEnabled,
                        onCheckedChange = { clusterEnabled = it; appPreferences.clusterEnabled = it }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Radius-Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_cluster_radius_m), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.settings_0_kein_radius_check_nur),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (clusterRadiusM == 0) stringResource(R.string.common_off) else "$clusterRadiusM m",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Slider(
                    value = clusterRadiusM.toFloat(),
                    onValueChange = { clusterRadiusM = it.roundToInt() },
                    onValueChangeFinished = { appPreferences.clusterRadiusM = clusterRadiusM },
                    valueRange = 0f..50f,
                    enabled = clusterEnabled
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Zeitfenster-Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_cluster_zeitfenster_min), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.settings_0_kein_zeit_check),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (clusterTimeWindowMin == 0) stringResource(R.string.common_off) else stringResource(R.string.settings_min, clusterTimeWindowMin),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Slider(
                    value = clusterTimeWindowMin.toFloat(),
                    onValueChange = { clusterTimeWindowMin = it.roundToInt() },
                    onValueChangeFinished = { appPreferences.clusterTimeWindowMin = clusterTimeWindowMin },
                    valueRange = 0f..30f,
                    enabled = clusterEnabled
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.settings_section_similar_refs), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_aehnliche_arten_anzeigen), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.settings_embedding_aehnlichkeitssuche_aktivie),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = similarEnabled,
                        onCheckedChange = { similarEnabled = it; appPreferences.similarEnabled = it }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))

                val linesLabel = when {
                    similarLines == 0f -> AppStrings.get(R.string.settings_0_aus)
                    similarLines == 0.5f -> AppStrings.get(R.string.settings_0_5_laufschrift)
                    else -> similarLines.toInt().toString()
                }
                Text(
                    text = stringResource(R.string.settings_anzahl_zeilen, linesLabel),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = similarLines,
                    onValueChange = { similarLines = it; appPreferences.similarLines = it },
                    valueRange = 0f..10f,
                    steps = 19,
                    enabled = similarEnabled
                )
                Text(
                    text = stringResource(R.string.settings_0_aus_0_5_laufschrift),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                // T97: Obergrenze der Embedding-DB (1'000–20'000 in 1'000er-Schritten, ~4 KB pro Eintrag)
                Text(
                    text = stringResource(R.string.settings_gespeicherte_vergleichsaufnahmen_max, embeddingDbMaxEntries, embeddingDbMaxEntries * 4 / 1000),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = embeddingDbMaxEntries.toFloat(),
                    onValueChange = {
                        val v = (it / 1000f).roundToInt() * 1000
                        embeddingDbMaxEntries = v
                        appPreferences.embeddingDbMaxEntries = v
                    },
                    valueRange = 1000f..20000f,
                    steps = 18
                )
                Text(
                    text = stringResource(R.string.settings_aeltere_eintraege_fallen_heraus_soba),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )


            }
        }
    }
}
