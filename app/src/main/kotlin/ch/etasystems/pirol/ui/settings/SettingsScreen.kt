package ch.etasystems.pirol.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.SecurePreferences
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import ch.etasystems.pirol.data.StorageManager
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.data.sync.UploadManager
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.ModelManager
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ml.WatchlistEntry
import ch.etasystems.pirol.ml.WatchlistManager
import ch.etasystems.pirol.ml.WatchlistPriority
import ch.etasystems.pirol.ui.components.SpectrogramPalette
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun SettingsScreen(
    uploadManager: UploadManager = koinInject(),
    watchlistManager: WatchlistManager = koinInject(),
    regionalFilter: RegionalSpeciesFilter = koinInject(),
    appPreferences: AppPreferences = koinInject(),
    speciesNameResolver: SpeciesNameResolver = koinInject(),
    classifier: AudioClassifier = koinInject(),
    modelManager: ModelManager = koinInject(),
    storageManager: StorageManager = koinInject(),
    sessionManager: SessionManager = koinInject(),
    securePreferences: SecurePreferences = koinInject()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // API-Key State (T48)
    var xenoCantoKey by remember { mutableStateOf(securePreferences.xenoCantoApiKey) }
    var keyVisible by remember { mutableStateOf(false) }

    // swisstopo API-Key State (T45)
    var swisstopoKey by remember { mutableStateOf(securePreferences.swisstopoApiKey) }
    var swisstopoKeyVisible by remember { mutableStateOf(false) }

    // Lokaler State fuer Recomposition bei Toggle-Aenderung
    var wifiOnly by remember { mutableStateOf(uploadManager.wifiOnly) }
    var autoUpload by remember { mutableStateOf(uploadManager.autoUpload) }

    // Reaktive Watchlist-Daten (T21)
    val entries by watchlistManager.entriesFlow.collectAsState()

    // Watchlist-Editor State (T21)
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // T29: Power-Profile State
    var selectedInterval by remember { mutableLongStateOf(appPreferences.inferenceIntervalMs) }

    // SAF File-Picker (T21)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    watchlistManager.importFromUri(uri)
                    Toast.makeText(context, "Watchlist importiert", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Import fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())  // AP-5: scrollbar
            .padding(16.dp)
    ) {
        Text(
            text = "Einstellungen",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(24.dp))

        // --- Sonogramm (T30) ---
        Text("Sonogramm", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // SpectrogramConfig Chips: Voegel / Fledermaus / Breitband
        var selectedSpecConfig by remember { mutableStateOf(appPreferences.spectrogramConfigName) }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "BIRDS" to "Voegel",
                "BATS" to "Fledermaus",
                "WIDEBAND" to "Breitband"
            ).forEach { (configName, label) ->
                FilterChip(
                    selected = selectedSpecConfig == configName,
                    onClick = {
                        selectedSpecConfig = configName
                        appPreferences.spectrogramConfigName = configName
                    },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Farbpalette
        var selectedPalette by remember { mutableStateOf(appPreferences.paletteName) }

        Text("Farbpalette", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpectrogramPalette.entries.forEach { palette ->
                FilterChip(
                    selected = selectedPalette == palette.name,
                    onClick = {
                        selectedPalette = palette.name
                        appPreferences.paletteName = palette.name
                    },
                    label = { Text(palette.name) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // --- Erkennung (T30) ---
        Text("Erkennung", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // Confidence-Slider
        var selectedThreshold by remember { mutableFloatStateOf(appPreferences.confidenceThreshold) }

        Text("Konfidenz-Schwelle: ${(selectedThreshold * 100).roundToInt()}%")
        Slider(
            value = selectedThreshold,
            onValueChange = {
                selectedThreshold = it
                appPreferences.confidenceThreshold = it
            },
            valueRange = 0.1f..0.9f,
            steps = 7
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Region-Chips
        var selectedRegion by remember { mutableStateOf(appPreferences.regionFilter) }

        Text("Regionaler Filter", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "ch_breeding" to "CH",
                "ch_all" to "CH+",
                "central_europe" to "EU",
                "all" to "Alle",
                null to "Aus"
            ).forEach { (regionId, label) ->
                FilterChip(
                    selected = selectedRegion == regionId,
                    onClick = {
                        selectedRegion = regionId
                        appPreferences.regionFilter = regionId
                    },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Presets
        Text("Voreinstellungen", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                InferenceConfig.SENSITIVE to "Sensitiv",
                InferenceConfig.DEFAULT to "Standard",
                InferenceConfig.STRICT to "Strikt"
            ).forEach { (preset, label) ->
                OutlinedButton(onClick = {
                    selectedThreshold = preset.confidenceThreshold
                    appPreferences.confidenceThreshold = preset.confidenceThreshold
                    appPreferences.topK = preset.topK
                }) {
                    Text(label)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // T34: Hochpassfilter Toggle
        var highpassEnabled by remember { mutableStateOf(appPreferences.highpassEnabled) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Hochpassfilter (200 Hz)")
                Text(
                    "Filtert Wind und Trittschall",
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
            text = "Aenderungen wirken nach Neustart der Aufnahme",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // --- Audio / Preroll-Puffer (T35) ---
        Text("Audio", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "48 kHz, 16-bit Mono",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Preroll-Puffer Toggle + Laenge (T35)
        var prerollEnabled by remember { mutableStateOf(appPreferences.prerollEnabled) }
        var prerollDuration by remember { mutableIntStateOf(appPreferences.prerollDurationSec) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Preroll-Puffer")
                Text(
                    "Zeichnet vor dem Aufnahmestart auf",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = prerollEnabled,
                onCheckedChange = {
                    prerollEnabled = it
                    appPreferences.prerollEnabled = it
                }
            )
        }

        if (prerollEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Laenge:", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5 to "5s", 10 to "10s", 30 to "30s").forEach { (sec, label) ->
                    FilterChip(
                        selected = prerollDuration == sec,
                        onClick = {
                            prerollDuration = sec
                            appPreferences.prerollDurationSec = sec
                        },
                        label = { Text(label) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Aenderungen wirken nach Neustart der Aufnahme",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // --- Modell-Management (T37) ---
        Text("Modell", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // Installierte Modelle auflisten
        var installedModels by remember { mutableStateOf(modelManager.listInstalledModels()) }
        var isDownloading by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableFloatStateOf(0f) }
        var isImporting by remember { mutableStateOf(false) }
        var importProgress by remember { mutableFloatStateOf(0f) }
        var modelError by remember { mutableStateOf<String?>(null) }

        // SAF-Import fuer Modell (T37)
        val modelImportLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                isImporting = true
                modelError = null
                scope.launch {
                    val success = modelManager.importFromUri(uri) { progress ->
                        importProgress = progress
                    }
                    isImporting = false
                    if (success) {
                        classifier.resetSession()
                        installedModels = modelManager.listInstalledModels()
                        Toast.makeText(context, "Modell importiert", Toast.LENGTH_SHORT).show()
                    } else {
                        modelError = "Import fehlgeschlagen — Datei zu klein oder ungueltig"
                    }
                }
            }
        }

        if (installedModels.isNotEmpty()) {
            // Aktives Modell anzeigen
            Text(
                text = "${classifier.modelName} — ${classifier.labelCount} Arten",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Installierte Modelle als RadioButtons
            installedModels.forEach { model ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            modelManager.setActiveModel(model.fileName)
                            classifier.resetSession()
                            installedModels = modelManager.listInstalledModels()
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = model.isActive,
                        onClick = {
                            modelManager.setActiveModel(model.fileName)
                            classifier.resetSession()
                            installedModels = modelManager.listInstalledModels()
                        }
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(model.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${model.sizeMB} MB — ${model.fileName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            Text(
                text = "Kein Modell installiert",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Download-Sektion
        if (isDownloading) {
            Text("Wird heruntergeladen...", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { downloadProgress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${(downloadProgress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall
            )
        } else if (isImporting) {
            Text("Wird importiert...", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { importProgress },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            // Modell-Download Buttons
            Text("Modell herunterladen:", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            ModelManager.AVAILABLE_MODELS.forEach { model ->
                val alreadyInstalled = installedModels.any { it.fileName == model.fileName }
                OutlinedButton(
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
                                Toast.makeText(context, "${model.name} heruntergeladen", Toast.LENGTH_SHORT).show()
                            } else {
                                modelError = "Download fehlgeschlagen — Internetverbindung pruefen"
                            }
                        }
                    },
                    enabled = !alreadyInstalled,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Text(
                        if (alreadyInstalled) "${model.name} ✓"
                        else "${model.name} (${model.expectedSizeMB} MB)"
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // SAF-Import Button
            OutlinedButton(onClick = {
                modelImportLauncher.launch(arrayOf("*/*"))
            }) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Anderes Modell importieren...")
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

        // --- Energieprofil (T29) ---
        Text("Energieprofil", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Laengere Intervalle sparen Akku, erkennen aber seltener",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        val profiles = listOf(
            Triple("Genau", 3000L, "Jeder 3s-Chunk wird analysiert"),
            Triple("Standard", 6000L, "Erkennung alle 6s — guter Kompromiss"),
            Triple("Sparsam", 15000L, "Erkennung alle 15s — fuer lange Feldtage"),
            Triple("Ultra-Sparsam", 30000L, "Erkennung alle 30s — maximale Laufzeit")
        )

        profiles.forEach { (name, interval, description) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedInterval = interval
                        appPreferences.inferenceIntervalMs = interval
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedInterval == interval,
                    onClick = {
                        selectedInterval = interval
                        appPreferences.inferenceIntervalMs = interval
                    }
                )
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // --- Artennamen-Sprache (T26) ---
        Text("Artennamen", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        var langExpanded by remember { mutableStateOf(false) }
        var selectedLang by remember { mutableStateOf(appPreferences.speciesLanguage) }

        ExposedDropdownMenuBox(
            expanded = langExpanded,
            onExpandedChange = { langExpanded = it }
        ) {
            @Suppress("DEPRECATION")
            OutlinedTextField(
                value = SpeciesNameResolver.AVAILABLE_LANGUAGES
                    .find { it.first == selectedLang }?.second ?: "Deutsch",
                onValueChange = {},
                readOnly = true,
                label = { Text("Sprache") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(langExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = langExpanded,
                onDismissRequest = { langExpanded = false }
            ) {
                SpeciesNameResolver.AVAILABLE_LANGUAGES.forEach { (code, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            selectedLang = code
                            appPreferences.speciesLanguage = code
                            speciesNameResolver.language = code
                            langExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // --- Speicher (T38 + T41: Migration) ---
        Text("Speicher", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Sessions werden am gewaehlten Ort gespeichert",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        val storageLocations = remember { storageManager.getAvailableStorageLocations() }
        var selectedStoragePath by remember {
            mutableStateOf(appPreferences.storagePath ?: storageLocations.first().path.absolutePath)
        }

        // T41: Migrations-Dialog State
        var showMigrationDialog by remember { mutableStateOf(false) }
        var migrationTargetLocation by remember { mutableStateOf<ch.etasystems.pirol.data.StorageLocation?>(null) }
        var migrationSessionCount by remember { mutableIntStateOf(0) }
        var migrationSizeMB by remember { mutableLongStateOf(0L) }
        var isMigrating by remember { mutableStateOf(false) }
        var migrationProgress by remember { mutableFloatStateOf(0f) }

        storageLocations.forEach { location ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val currentPath = appPreferences.storagePath
                            ?: storageLocations.first().path.absolutePath
                        if (location.path.absolutePath != currentPath) {
                            // T41: Vor dem Wechsel Migration anbieten
                            val info = sessionManager.getMigrationInfo()
                            migrationSessionCount = info.sessionCount
                            migrationSizeMB = info.totalSizeBytes / 1_000_000
                            migrationTargetLocation = location
                            showMigrationDialog = true
                        }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedStoragePath == location.path.absolutePath,
                    onClick = {
                        val currentPath = appPreferences.storagePath
                            ?: storageLocations.first().path.absolutePath
                        if (location.path.absolutePath != currentPath) {
                            val info = sessionManager.getMigrationInfo()
                            migrationSessionCount = info.sessionCount
                            migrationSizeMB = info.totalSizeBytes / 1_000_000
                            migrationTargetLocation = location
                            showMigrationDialog = true
                        }
                    }
                )
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(location.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "${location.freeSpaceMB} MB frei",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (storageLocations.size == 1) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Keine SD-Karte erkannt",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // T41: Fortschrittsanzeige waehrend Migration
        if (isMigrating) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Sessions werden kopiert...", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { migrationProgress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${(migrationProgress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // T41: Bestaetigungsdialog
        if (showMigrationDialog && migrationTargetLocation != null) {
            val targetLoc = migrationTargetLocation!!

            AlertDialog(
                onDismissRequest = {
                    // Abbrechen — nichts tun
                    showMigrationDialog = false
                    migrationTargetLocation = null
                },
                title = { Text("Speicherort wechseln") },
                text = {
                    if (migrationSessionCount > 0) {
                        Text(
                            "$migrationSessionCount Sessions ($migrationSizeMB MB) " +
                            "zum neuen Speicherort kopieren?"
                        )
                    } else {
                        Text("Keine Sessions vorhanden. Speicherort wechseln?")
                    }
                },
                confirmButton = {
                    if (migrationSessionCount > 0) {
                        TextButton(onClick = {
                            showMigrationDialog = false
                            isMigrating = true
                            migrationProgress = 0f
                            scope.launch {
                                val result = sessionManager.migrateSessionsTo(
                                    targetBaseDir = targetLoc.path
                                ) { progress ->
                                    migrationProgress = progress
                                }
                                isMigrating = false
                                if (result.errors.isEmpty()) {
                                    // Erfolg: Speicherort umstellen
                                    selectedStoragePath = targetLoc.path.absolutePath
                                    appPreferences.storagePath =
                                        if (targetLoc.isInternal) null else targetLoc.path.absolutePath
                                    Toast.makeText(
                                        context,
                                        "${result.sessionsCopied} Sessions kopiert",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    // Fehler: alter Pfad bleibt aktiv
                                    Toast.makeText(
                                        context,
                                        "Migration fehlgeschlagen: ${result.errors.first()}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                migrationTargetLocation = null
                            }
                        }) { Text("Kopieren") }
                    } else {
                        // Keine Sessions — einfach wechseln
                        TextButton(onClick = {
                            showMigrationDialog = false
                            selectedStoragePath = targetLoc.path.absolutePath
                            appPreferences.storagePath =
                                if (targetLoc.isInternal) null else targetLoc.path.absolutePath
                            migrationTargetLocation = null
                        }) { Text("Wechseln") }
                    }
                },
                dismissButton = {
                    Row {
                        if (migrationSessionCount > 0) {
                            // Ohne Kopieren wechseln
                            TextButton(onClick = {
                                showMigrationDialog = false
                                selectedStoragePath = targetLoc.path.absolutePath
                                appPreferences.storagePath =
                                    if (targetLoc.isInternal) null else targetLoc.path.absolutePath
                                migrationTargetLocation = null
                            }) { Text("Ohne Kopieren") }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        TextButton(onClick = {
                            showMigrationDialog = false
                            migrationTargetLocation = null
                        }) { Text("Abbrechen") }
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // --- Export-Sektion ---
        Text("Export", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // WLAN-only Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Nur bei WLAN exportieren", modifier = Modifier.weight(1f))
            Switch(
                checked = wifiOnly,
                onCheckedChange = {
                    wifiOnly = it
                    uploadManager.wifiOnly = it
                    appPreferences.wifiOnly = it
                }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Auto-Upload Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Automatisch nach Aufnahme", modifier = Modifier.weight(1f))
            Switch(
                checked = autoUpload,
                onCheckedChange = {
                    autoUpload = it
                    uploadManager.autoUpload = it
                    appPreferences.autoUpload = it
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Ziel: Downloads/PIROL/",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // --- API-Keys (T48) ---
        Text("API-Keys", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = xenoCantoKey,
            onValueChange = { xenoCantoKey = it },
            label = { Text("Xeno-Canto API-Key") },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) Icons.Filled.VisibilityOff
                                      else Icons.Filled.Visibility,
                        contentDescription = if (keyVisible) "Verbergen" else "Anzeigen"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(
            onClick = {
                securePreferences.xenoCantoApiKey = xenoCantoKey
                Toast.makeText(context, "API-Key gespeichert", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text("Speichern")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- swisstopo API-Key (T45) ---
        OutlinedTextField(
            value = swisstopoKey,
            onValueChange = { swisstopoKey = it },
            label = { Text("swisstopo API-Key (optional)") },
            singleLine = true,
            visualTransformation = if (swisstopoKeyVisible) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { swisstopoKeyVisible = !swisstopoKeyVisible }) {
                    Icon(
                        imageVector = if (swisstopoKeyVisible) Icons.Filled.VisibilityOff
                                      else Icons.Filled.Visibility,
                        contentDescription = if (swisstopoKeyVisible) "Verbergen" else "Anzeigen"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(
            onClick = {
                securePreferences.swisstopoApiKey = swisstopoKey
                Toast.makeText(context, "swisstopo-Key gespeichert", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text("Speichern")
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // --- Watchlist-Sektion (T20 + T21) ---
        Text("Watchlist", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        if (entries.isNotEmpty()) {
            Text(
                text = "${watchlistManager.name} (${entries.size} Arten)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Artenliste mit Loeschen-Buttons (T21 AP-4)
            entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.commonName.ifEmpty { entry.scientificName.replace('_', ' ') },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = entry.priority.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (entry.priority) {
                            WatchlistPriority.HIGH -> MaterialTheme.colorScheme.error
                            WatchlistPriority.NORMAL -> MaterialTheme.colorScheme.primary
                            WatchlistPriority.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    IconButton(onClick = {
                        scope.launch {
                            watchlistManager.removeSpecies(entry.scientificName)
                        }
                    }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Entfernen",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        } else {
            Text(
                text = "Keine Watchlist geladen",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Auto-Import: Downloads/PIROL/watchlist.json",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (entries.isNotEmpty()) "\u2713 Geladen" else "\u2717 Keine Datei gefunden",
            style = MaterialTheme.typography.bodySmall,
            color = if (entries.isNotEmpty())
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(12.dp))

        // --- Watchlist-Aktionen (T21) ---
        Row(modifier = Modifier.fillMaxWidth()) {
            // SAF-Import-Button (AP-2)
            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf("application/json", "*/*"))
            }) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Importieren...")
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Reload-Button (AP-3)
            TextButton(onClick = {
                scope.launch {
                    watchlistManager.load()
                    Toast.makeText(context, "Watchlist neu geladen", Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Neu laden")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Art hinzufuegen Button (AP-4)
        OutlinedButton(onClick = { showAddDialog = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Art hinzufuegen")
        }
    }

    // --- Art-Hinzufuegen-Dialog (AP-4) ---
    if (showAddDialog) {
        val allSpecies = remember { regionalFilter.getSpeciesList().sorted() }

        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                searchQuery = ""
            },
            title = { Text("Art zur Watchlist hinzufuegen") },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Artname suchen...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Autocomplete-Vorschlaege (aus RegionalSpeciesFilter)
                    val suggestions = if (searchQuery.length >= 2) {
                        allSpecies.filter { species ->
                            species.contains(searchQuery, ignoreCase = true)
                        }.take(8)
                    } else emptyList()

                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(suggestions) { species ->
                            Text(
                                text = species.replace('_', ' '),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            watchlistManager.addSpecies(
                                                WatchlistEntry(
                                                    scientificName = species,
                                                    commonName = "",
                                                    priority = WatchlistPriority.NORMAL
                                                )
                                            )
                                        }
                                        showAddDialog = false
                                        searchQuery = ""
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    searchQuery = ""
                }) { Text("Abbrechen") }
            }
        )
    }
}
