package ch.etasystems.pirol.ui.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.RangeSlider
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
    sessionManager: SessionManager = koinInject()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

        Spacer(modifier = Modifier.height(12.dp))

        // --- Sonogramm-Dynamik (T56): Auto-Kontrast + manueller dB-Range ---
        var autoContrast by remember { mutableStateOf(appPreferences.spectrogramAutoContrast) }
        var manualMinDb by remember { mutableFloatStateOf(appPreferences.spectrogramMinDb) }
        var manualMaxDb by remember { mutableFloatStateOf(appPreferences.spectrogramMaxDb) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-Kontrast", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Passt die Helligkeit laufend an (5./95. Perzentil, 5 s Fenster)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = autoContrast,
                onCheckedChange = {
                    autoContrast = it
                    appPreferences.spectrogramAutoContrast = it
                }
            )
        }

        if (!autoContrast) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "dB-Bereich: ${manualMinDb.roundToInt()} dB bis ${manualMaxDb.roundToInt()} dB",
                style = MaterialTheme.typography.bodyMedium
            )
            RangeSlider(
                value = manualMinDb..manualMaxDb,
                onValueChange = { range ->
                    // Mindestens 1 dB Abstand, sonst teilt der Canvas durch 0
                    val lo = range.start
                    val hi = if (range.endInclusive - lo < 1f) lo + 1f else range.endInclusive
                    manualMinDb = lo
                    manualMaxDb = hi
                    appPreferences.spectrogramMinDb = lo
                    appPreferences.spectrogramMaxDb = hi
                },
                valueRange = -100f..10f,
                steps = 21 // 5 dB Raster
            )
        }

        // T56b: Gamma-Kompression — immer aktiv, unabhaengig vom Auto-Kontrast-Toggle
        Spacer(modifier = Modifier.height(12.dp))

        var gamma by remember { mutableFloatStateOf(appPreferences.spectrogramGamma) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Kontrast-Kompression (Gamma)", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Hebt leise Anteile an. 1.0 = aus, 0.5 = Standard fuer leise Voegel, 0.3 = stark.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "\u03B3 = ${String.format("%.2f", gamma)}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Slider(
            value = gamma,
            onValueChange = { v ->
                gamma = v
                appPreferences.spectrogramGamma = v
            },
            valueRange = 0.3f..1.0f,
            steps = 14
        )

        // T56b: Lautstärke-Deckel (Ceiling) — immer aktiv
        Spacer(modifier = Modifier.height(12.dp))

        var ceilingDb by remember { mutableFloatStateOf(appPreferences.spectrogramCeilingDb) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Lautstärke-Deckel (Ceiling)", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Schneidet laute Anteile ab. 0 dB = aus, -10 dB = laute Impulse kappen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (ceilingDb >= 0f) "aus" else "${ceilingDb.roundToInt()} dB",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Slider(
            value = ceilingDb,
            onValueChange = { v ->
                ceilingDb = v
                appPreferences.spectrogramCeilingDb = v
            },
            valueRange = -50f..0f,
            steps = 9  // 5 dB Raster: -50, -45, -40, -35, -30, -25, -20, -15, -10, -5, 0
        )

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

        Spacer(modifier = Modifier.height(12.dp))

        // T52: Artvorschlaege (Top-N Kandidaten) Toggle
        var showTopNCandidates by remember { mutableStateOf(appPreferences.showTopNCandidates) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Artvorschlaege anzeigen")
                Text(
                    "Alternativen unter jeder Detektion aufklappbar",
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

        // T57-B1: Max. Aufnahmelaenge pro WAV
        Spacer(modifier = Modifier.height(12.dp))

        var maxRecordingMinutes by remember { mutableIntStateOf(appPreferences.maxRecordingMinutes) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Max. Aufnahmelaenge pro WAV", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Laengere Sessions werden automatisch auf mehrere WAV-Dateien aufgeteilt. BirdNET laeuft dabei durchgehend weiter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("$maxRecordingMinutes min", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = maxRecordingMinutes.toFloat(),
            onValueChange = { v ->
                val mins = v.roundToInt()
                maxRecordingMinutes = mins
                appPreferences.maxRecordingMinutes = mins
            },
            valueRange = 10f..80f,
            steps = 13  // 10,15,20,...,80 = 15 Positionen → 13 Zwischenschritte
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

        // --- GPS (T53) ---
        Text("GPS", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        // GPS-Intervall
        var selectedGpsInterval by remember { mutableIntStateOf(appPreferences.gpsIntervalSeconds) }
        Text("GPS-Intervall", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Kuerzere Intervalle = genauere Position, hoeherer Akkuverbrauch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2 to "2s", 5 to "5s", 10 to "10s", 20 to "20s", 60 to "60s").forEach { (sec, label) ->
                FilterChip(
                    selected = selectedGpsInterval == sec,
                    onClick = {
                        selectedGpsInterval = sec
                        appPreferences.gpsIntervalSeconds = sec
                    },
                    label = { Text(label) }
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Wirkt ab naechster Aufnahme.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Accuracy-Filter
        var gpsMaxAccuracy by remember { mutableFloatStateOf(appPreferences.gpsMaxAccuracyMeters) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Max. GPS-Ungenauigkeit", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Fixes ungenauer als dieser Wert werden verworfen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${gpsMaxAccuracy.roundToInt()} m",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Slider(
            value = gpsMaxAccuracy,
            onValueChange = { v ->
                gpsMaxAccuracy = v
                appPreferences.gpsMaxAccuracyMeters = v
            },
            valueRange = 10f..200f,
            steps = 18  // 10 m Raster
        )

        Spacer(modifier = Modifier.height(12.dp))

        // GPS-Smoothing Toggle
        var gpsSmoothingEnabled by remember { mutableStateOf(appPreferences.gpsSmoothingEnabled) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("GPS-Smoothing", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Median ueber 5 Fixes — reduziert Ausreisser-Spruenge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = gpsSmoothingEnabled,
                onCheckedChange = {
                    gpsSmoothingEnabled = it
                    appPreferences.gpsSmoothingEnabled = it
                }
            )
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

        // --- Speicherort (T51: SAF + Tages-Unterordner) ---
        Text("Speicherort", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))

        // Anzeige des aktuellen Pfads
        var currentBaseUri by remember { mutableStateOf(appPreferences.storageBaseUri) }
        val currentPathDisplay = if (currentBaseUri != null) {
            Uri.parse(currentBaseUri).lastPathSegment ?: currentBaseUri ?: "?"
        } else {
            "Android/data/ch.etasystems.pirol/files/PIROL/"
        }
        Text(
            text = "Aktuell: $currentPathDisplay",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // SAF-Picker (T51)
        var migrationTargetUri by remember { mutableStateOf<Uri?>(null) }
        var showMigrationDialog by remember { mutableStateOf(false) }
        var migrationSessionCount by remember { mutableIntStateOf(0) }
        var migrationSizeMB by remember { mutableLongStateOf(0L) }
        var isMigrating by remember { mutableStateOf(false) }
        var migrationProgress by remember { mutableFloatStateOf(0f) }

        val safLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val info = sessionManager.getMigrationInfo()
                migrationSessionCount = info.sessionCount
                migrationSizeMB = info.totalSizeBytes / 1_000_000
                if (info.sessionCount > 0) {
                    migrationTargetUri = uri
                    showMigrationDialog = true
                } else {
                    appPreferences.storageBaseUri = uri.toString()
                    currentBaseUri = uri.toString()
                    Toast.makeText(context, "Speicherort gespeichert", Toast.LENGTH_SHORT).show()
                }
            }
        }

        OutlinedButton(onClick = { safLauncher.launch(null) }) {
            Text("Anderen Ordner waehlen")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tages-Unterordner Toggle
        var dailySubfolder by remember { mutableStateOf(appPreferences.storageDailySubfolder) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = dailySubfolder,
                onCheckedChange = { enabled ->
                    dailySubfolder = enabled
                    appPreferences.storageDailySubfolder = enabled
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text("Tages-Unterordner (YYYY-MM-DD)", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Sessions nach Datum gruppieren",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Preview-Zeile
        Spacer(modifier = Modifier.height(4.dp))
        val todayStr = java.time.LocalDate.now().toString()
        val examplePath = if (dailySubfolder) {
            "$currentPathDisplay/$todayStr/2026-04-20T08-30-00_a1b2c3/"
        } else {
            "$currentPathDisplay/2026-04-20T08-30-00_a1b2c3/"
        }
        Text(
            text = "Beispiel: $examplePath",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Migration-Button
        OutlinedButton(onClick = {
            val info = sessionManager.getMigrationInfo()
            migrationSessionCount = info.sessionCount
            migrationSizeMB = info.totalSizeBytes / 1_000_000
            if (info.sessionCount > 0) showMigrationDialog = true
            else Toast.makeText(context, "Keine Sessions vorhanden", Toast.LENGTH_SHORT).show()
        }) {
            Text("Bestehende Sessions verschieben")
        }

        // Fortschrittsanzeige waehrend Migration
        if (isMigrating) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Sessions werden kopiert...", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(progress = { migrationProgress }, modifier = Modifier.fillMaxWidth())
            Text(
                text = "${(migrationProgress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Bestaetigungsdialog fuer Migration
        if (showMigrationDialog) {
            AlertDialog(
                onDismissRequest = {
                    showMigrationDialog = false
                    migrationTargetUri = null
                },
                title = { Text("Sessions verschieben") },
                text = {
                    Text("$migrationSessionCount Sessions ($migrationSizeMB MB) an neuen Speicherort kopieren?")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showMigrationDialog = false
                        isMigrating = true
                        migrationProgress = 0f
                        val targetUri = migrationTargetUri
                        scope.launch {
                            // Migration zu File-Pfad (aus SAF-URI) oder bestehender Struktur
                            val targetFile = if (targetUri != null) {
                                // SAF-URI in File-Pfad umwandeln falls moeglich, sonst internal
                                try {
                                    val docId = android.provider.DocumentsContract.getTreeDocumentId(targetUri)
                                    val parts = docId.split(":")
                                    if (parts.size >= 2 && parts[0].equals("primary", ignoreCase = true)) {
                                        java.io.File(android.os.Environment.getExternalStorageDirectory(), parts[1])
                                    } else null
                                } catch (_: Exception) { null }
                            } else null

                            if (targetFile != null) {
                                val result = sessionManager.migrateSessionsTo(targetFile) { p ->
                                    migrationProgress = p
                                }
                                isMigrating = false
                                if (result.errors.isEmpty()) {
                                    appPreferences.storageBaseUri = targetUri.toString()
                                    currentBaseUri = targetUri.toString()
                                    Toast.makeText(context, "${result.sessionsCopied} Sessions kopiert", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Fehler: ${result.errors.first()}", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                isMigrating = false
                                Toast.makeText(context, "Zielordner nicht auflösbar", Toast.LENGTH_LONG).show()
                            }
                            migrationTargetUri = null
                        }
                    }) { Text("Kopieren") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            // Ohne Migration wechseln — nur URI speichern
                            showMigrationDialog = false
                            migrationTargetUri?.let { uri ->
                                appPreferences.storageBaseUri = uri.toString()
                                currentBaseUri = uri.toString()
                            }
                            migrationTargetUri = null
                        }) { Text("Nur wechseln") }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(onClick = {
                            showMigrationDialog = false
                            migrationTargetUri = null
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
                            WatchlistPriority.high -> MaterialTheme.colorScheme.error
                            WatchlistPriority.normal -> MaterialTheme.colorScheme.primary
                            WatchlistPriority.low -> MaterialTheme.colorScheme.onSurfaceVariant
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
                                                    priority = WatchlistPriority.normal
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
