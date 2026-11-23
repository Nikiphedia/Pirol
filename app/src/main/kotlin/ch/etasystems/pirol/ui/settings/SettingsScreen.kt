package ch.etasystems.pirol.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.compose.material3.Checkbox
import ch.etasystems.pirol.audio.AlarmService
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.MigrationRefusal
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.data.repository.SessionMigrationLogic
import ch.etasystems.pirol.data.repository.SessionMigrationWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ch.etasystems.pirol.data.sync.UploadManager
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.ModelManager
import ch.etasystems.pirol.ml.ModelProfile
import ch.etasystems.pirol.ui.components.ModelProfileDialog
import ch.etasystems.pirol.ml.PhenologyRepository
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.RegionInfo
import ch.etasystems.pirol.ml.PirolFolder
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ml.WatchlistEntry
import ch.etasystems.pirol.ml.WatchlistManager
import ch.etasystems.pirol.ml.WatchlistPriority
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.data.SpectrogramSettingsRepository
import ch.etasystems.pirol.ui.components.SpectrogramSettingsPanel
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import ch.etasystems.pirol.ml.OnnxBenchmark
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.AppLanguage
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

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
    var showWhatsNew by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Alarm-Cooldown State (T58)
    var cooldownMinutes by remember { mutableIntStateOf(appPreferences.alarmCooldownMinutes) }
    // T124: Alarm-Ausgaenge
    var alarmVibration by remember { mutableStateOf(appPreferences.alarmVibrationEnabled) }
    var alarmNotification by remember { mutableStateOf(appPreferences.alarmNotificationEnabled) }
    var alarmBanner by remember { mutableStateOf(appPreferences.alarmBannerEnabled) }
    val alarmService: AlarmService = koinInject()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                AppStrings.get(R.string.settings_benachrichtigungen_sind_nicht_erlaub),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Detektions-Cluster State (T70)
    var clusterEnabled by remember { mutableStateOf(appPreferences.clusterEnabled) }
    var clusterRadiusM by remember { mutableIntStateOf(appPreferences.clusterRadiusM) }
    var clusterTimeWindowMin by remember { mutableIntStateOf(appPreferences.clusterTimeWindowMin) }

    // Aehnliche Arten State (T75)
    var similarEnabled by remember { mutableStateOf(appPreferences.similarEnabled) }
    var similarLines by remember { mutableFloatStateOf(appPreferences.similarLines) }
    var embeddingDbMaxEntries by remember { mutableIntStateOf(appPreferences.embeddingDbMaxEntries) }  // T97

    // Diagnose: Inference-Benchmark (T96)
    var benchmarkRunning by remember { mutableStateOf(false) }
    var benchmarkProgress by remember { mutableStateOf("") }
    var benchmarkReport by remember { mutableStateOf("") }
    // T146: Benchmark fuer beliebige ONNX-Datei
    var genericBenchRunning by remember { mutableStateOf(false) }
    var genericBenchProgress by remember { mutableStateOf("") }
    var genericBenchReport by remember { mutableStateOf("") }

    // Multi-Label State (T71)
    var selectedTopK by remember { mutableIntStateOf(appPreferences.topK) }
    var selectedConfidence by remember { mutableFloatStateOf(appPreferences.confidenceThreshold) }

    // Phenology State (T74)
    val phenologyRepository: PhenologyRepository = koinInject()
    var phenologyEnabled by remember { mutableStateOf(appPreferences.phenologyEnabled) }
    var phenologyEntryCount by remember { mutableIntStateOf(phenologyRepository.entryCount()) }

    // Art-Vorschlagsliste fuer Dialog: einmalig geladen, ausserhalb des if-Blocks (Compose-Regel)
    var allWatchlistSpecies by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        if (regionalFilter.getSpeciesList().isEmpty()) {
            regionalFilter.loadRegion(appPreferences.regionFilter ?: "ch_breeding")
        }
        speciesNameResolver.load()
        allWatchlistSpecies = regionalFilter.getSpeciesList().sorted().map { scientific ->
            Pair(scientific, speciesNameResolver.resolve(scientific))
        }
    }

    // T129: PIROL-Ordner-Freigabe, eigene Listen
    val pirolFolder: PirolFolder = koinInject()
    var folderName by remember { mutableStateOf<String?>(null) }
    var customRegions by remember { mutableStateOf<List<RegionInfo>>(emptyList()) }
    var customWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    suspend fun refreshFolder() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (pirolFolder.isAvailable()) watchlistManager.load()
            regionalFilter.refreshCustomLists()
        }
        folderName = pirolFolder.displayName()
        customRegions = regionalFilter.getAvailableRegions().filter { it.isCustom }
        customWarnings = regionalFilter.getCustomWarnings()
    }
    LaunchedEffect(Unit) { refreshFolder() }
    // T136: Auswahl der eigenen Listen auch bei ON_START (Rueckkehr aus Dateimanager) auffrischen
    val settingsLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(settingsLifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                scope.launch {
                    val changed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        regionalFilter.refreshCustomListsIfChanged()
                    }
                    if (changed) {
                        customRegions = regionalFilter.getAvailableRegions().filter { it.isCustom }
                        customWarnings = regionalFilter.getCustomWarnings()
                    }
                }
            }
        }
        settingsLifecycleOwner.lifecycle.addObserver(obs)
        onDispose { settingsLifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            pirolFolder.grant(uri)
            scope.launch { refreshFolder() }
        }
    }

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
                    Toast.makeText(context, AppStrings.get(R.string.settings_watchlist_importiert), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, AppStrings.get(R.string.settings_import_fehlgeschlagen, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // T190: Snackbar (Undo der Watchlist-Uebernahme) und Dialogzustand
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    var adoptList by remember { mutableStateOf<ch.etasystems.pirol.ml.CustomSpeciesList?>(null) }

    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())  // AP-5: scrollbar
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_einstellungen),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(24.dp))

        SettingsSection(
            sectionKey = "lang",
            title = stringResource(R.string.settings_section_language),
            appPreferences = appPreferences,
            defaultExpanded = true
        ) {
            Text(stringResource(R.string.settings_sprache_der_app), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            var appLangExpanded by remember { mutableStateOf(false) }
            var appLangTag by remember { mutableStateOf(AppLanguage.currentTag()) }
            val systemLangLabel = stringResource(R.string.settings_systemsprache)

            ExposedDropdownMenuBox(
                expanded = appLangExpanded,
                onExpandedChange = { appLangExpanded = it }
            ) {
                @Suppress("DEPRECATION")
                OutlinedTextField(
                    value = AppLanguage.OPTIONS.find { it.first == appLangTag }?.second ?: systemLangLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_sprache)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(appLangExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = appLangExpanded,
                    onDismissRequest = { appLangExpanded = false }
                ) {
                    (listOf(AppLanguage.SYSTEM_TAG to systemLangLabel) + AppLanguage.OPTIONS).forEach { (tag, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                appLangExpanded = false
                                if (tag != appLangTag) {
                                    appLangTag = tag
                                    // Artnamen folgen der App-Sprache, solange nicht explizit gesetzt
                                    if (!appPreferences.speciesLanguageExplicit) {
                                        speciesNameResolver.language = AppLanguage.speciesLanguageForTag(
                                            tag, AppLanguage.systemLanguage()
                                        )
                                    }
                                    // startet die Activity neu
                                    AppLanguage.apply(tag)
                                }
                            }
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsSection(
            sectionKey = "s_rec",
            title = stringResource(R.string.settings_section_recording),
            appPreferences = appPreferences,
            defaultExpanded = false
        ) {
            Text(stringResource(R.string.settings_audio), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_48_khz_16_bit_mono),
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
                    Text(stringResource(R.string.settings_preroll_puffer))
                    Text(
                        AppStrings.get(R.string.settings_zeichnet_vor_dem_aufnahmestart_auf),
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
                Text(stringResource(R.string.settings_laenge), style = MaterialTheme.typography.bodyMedium)
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
                text = stringResource(R.string.settings_aenderungen_wirken_nach_neustart_der),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            // --- Ueberlappung der Analysefenster (T110) ---
            Spacer(modifier = Modifier.height(12.dp))
            var selectedOverlap by remember { mutableIntStateOf(appPreferences.overlapPercent) }
            Text(stringResource(R.string.settings_ueberlappung), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.settings_mehr_ueberlappung_erkennt_kurze_rufe),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val overlapOptions = listOf(
                Triple(0, "0 %", AppStrings.get(R.string.settings_fenster_alle_3_s_standard)),
                Triple(50, "50 %", AppStrings.get(R.string.settings_fenster_alle_1_5_s)),
                Triple(67, "67 %", AppStrings.get(R.string.settings_fenster_jede_sekunde))
            )
            overlapOptions.forEach { (percent, label, description) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedOverlap = percent
                            appPreferences.overlapPercent = percent
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedOverlap == percent,
                        onClick = {
                            selectedOverlap = percent
                            appPreferences.overlapPercent = percent
                        }
                    )
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
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
            Text(stringResource(R.string.settings_energieprofil), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_laengere_intervalle_sparen_akku_erke),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            val profiles = listOf(
                Triple(AppStrings.get(R.string.settings_genau), 3000L, AppStrings.get(R.string.settings_jeder_3s_chunk_wird_analysiert)),
                Triple(AppStrings.get(R.string.settings_standard), 6000L, AppStrings.get(R.string.settings_erkennung_alle_6s_guter_kompromiss)),
                Triple(AppStrings.get(R.string.settings_sparsam), 15000L, AppStrings.get(R.string.settings_erkennung_alle_15s_fuer_lange)),
                Triple(AppStrings.get(R.string.settings_ultra_sparsam), 30000L, AppStrings.get(R.string.settings_erkennung_alle_30s_maximale_laufzeit))
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
            Text(stringResource(R.string.settings_speicherort), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))

            // Anzeige des aktuellen Pfads
            var currentBaseUri by remember { mutableStateOf(appPreferences.storageBaseUri) }
            val currentPathDisplay = if (currentBaseUri != null) {
                Uri.parse(currentBaseUri).lastPathSegment ?: currentBaseUri ?: "?"
            } else {
                "Android/data/ch.etasystems.pirol/files/PIROL/"
            }
            Text(
                text = stringResource(R.string.settings_aktuell, currentPathDisplay),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // SAF-Picker (T51) + Session-Migration (T141)
            var pendingTargetUri by remember { mutableStateOf<Uri?>(null) }
            var pendingManual by remember { mutableStateOf(false) }
            var showMigrationDialog by remember { mutableStateOf(false) }
            var migrationSessionCount by remember { mutableIntStateOf(0) }
            var migrationSizeBytes by remember { mutableLongStateOf(0L) }
            var deleteOriginal by remember { mutableStateOf(false) }
            var manualPick by remember { mutableStateOf(false) }

            fun refusalMessage(r: MigrationRefusal): String = when (r) {
                MigrationRefusal.RECORDING_ACTIVE -> AppStrings.get(R.string.mig_refuse_recording)
                MigrationRefusal.SAME_LOCATION -> AppStrings.get(R.string.mig_refuse_same)
                MigrationRefusal.NO_SESSIONS -> AppStrings.get(R.string.settings_keine_sessions_vorhanden)
            }

            // uri == null = Ziel App-Speicher (Standard)
            val prepareMigration: (Uri?, Boolean) -> Unit = { uri, manual ->
                scope.launch {
                    val same = if (uri == null) currentBaseUri == null else uri.toString() == currentBaseUri
                    val info = withContext(Dispatchers.IO) { sessionManager.getMigrationInfo() }
                    val refusal = SessionMigrationLogic.refusal(sessionManager.isActive, info.sessionCount, same)
                    when {
                        refusal == null -> {
                            migrationSessionCount = info.sessionCount
                            migrationSizeBytes = info.totalSizeBytes
                            pendingTargetUri = uri
                            pendingManual = manual
                            showMigrationDialog = true
                        }
                        !manual && uri != null && refusal == MigrationRefusal.NO_SESSIONS -> {
                            appPreferences.storageBaseUri = uri.toString()
                            currentBaseUri = uri.toString()
                            Toast.makeText(context, AppStrings.get(R.string.settings_speicherort_gespeichert), Toast.LENGTH_SHORT).show()
                        }
                        else -> Toast.makeText(context, refusalMessage(refusal), Toast.LENGTH_LONG).show()
                    }
                }
            }

            val safLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    prepareMigration(uri, manualPick)
                }
            }

            OutlinedButton(onClick = {
                if (sessionManager.isActive) {
                    Toast.makeText(context, AppStrings.get(R.string.mig_refuse_recording), Toast.LENGTH_LONG).show()
                } else {
                    manualPick = false
                    safLauncher.launch(null)
                }
            }) {
                Text(stringResource(R.string.settings_anderen_ordner_waehlen))
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
                    Text(stringResource(R.string.settings_tages_unterordner_yyyy_mm_dd), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.settings_sessions_nach_datum_gruppieren),
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
                text = stringResource(R.string.settings_beispiel, examplePath),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Auto-Delete kurzer leerer Sessions (T60)
            var autoDeleteEnabled by remember { mutableStateOf(appPreferences.autoDeleteShortEmptySessions) }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = autoDeleteEnabled,
                    onCheckedChange = { enabled ->
                        autoDeleteEnabled = enabled
                        appPreferences.autoDeleteShortEmptySessions = enabled
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(stringResource(R.string.settings_kurze_leere_sessions_automatisch_loe), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.settings_sessions_kuerzer_als_minimum_dauer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (autoDeleteEnabled) {
                var autoDeleteMinSec by remember { mutableIntStateOf(appPreferences.autoDeleteMinSeconds) }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_minimum_dauer_s_0_deaktiviert, autoDeleteMinSec),
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = autoDeleteMinSec.toFloat(),
                    onValueChange = { autoDeleteMinSec = it.toInt() },
                    onValueChangeFinished = { appPreferences.autoDeleteMinSeconds = autoDeleteMinSec },
                    valueRange = 0f..60f,
                    steps = 59,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Manuelle Migration (T141)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (sessionManager.isActive) {
                        Toast.makeText(context, AppStrings.get(R.string.mig_refuse_recording), Toast.LENGTH_LONG).show()
                    } else {
                        manualPick = true
                        safLauncher.launch(null)
                    }
                }) { Text(stringResource(R.string.mig_manual_button)) }
                if (currentBaseUri != null) {
                    OutlinedButton(onClick = { prepareMigration(null, true) }) {
                        Text(stringResource(R.string.mig_to_default_button))
                    }
                }
            }

            // Fortschritt und Ergebnis der Migration (WorkManager)
            val migWork by WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(SessionMigrationWorker.UNIQUE_NAME)
                .collectAsState(initial = emptyList())
            val migInfo = migWork.firstOrNull()
            var dismissedMigId by remember { mutableStateOf<java.util.UUID?>(null) }
            LaunchedEffect(migInfo?.state) { currentBaseUri = appPreferences.storageBaseUri }
            if (migInfo != null) {
                when (migInfo.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        val done = migInfo.progress.getInt(SessionMigrationWorker.KEY_DONE, 0)
                        val total = migInfo.progress.getInt(SessionMigrationWorker.KEY_TOTAL, 0)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.mig_progress, done, total), style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED -> if (dismissedMigId != migInfo.id) {
                        val out = migInfo.outputData
                        val copied = out.getInt(SessionMigrationWorker.KEY_COPIED, 0)
                        val skipped = out.getInt(SessionMigrationWorker.KEY_SKIPPED, 0)
                        val failed = out.getInt(SessionMigrationWorker.KEY_FAILED, 0)
                        val bytes = out.getLong(SessionMigrationWorker.KEY_BYTES, 0L)
                        val errs = out.getString(SessionMigrationWorker.KEY_ERRORS) ?: ""
                        val ok = migInfo.state == WorkInfo.State.SUCCEEDED
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (ok) stringResource(R.string.mig_result_ok, copied, SessionMigrationLogic.formatSize(bytes), skipped)
                                       else stringResource(R.string.mig_result_failed, copied, failed, errs),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { dismissedMigId = migInfo.id }) { Text(stringResource(R.string.mig_dismiss)) }
                        }
                    }
                    else -> {}
                }
            }

            // Bestaetigungsdialog fuer Migration
            if (showMigrationDialog) {
                AlertDialog(
                    onDismissRequest = { showMigrationDialog = false; pendingTargetUri = null },
                    title = { Text(stringResource(R.string.mig_dialog_title)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.mig_dialog_body, migrationSessionCount, SessionMigrationLogic.formatSize(migrationSizeBytes)))
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = deleteOriginal, onCheckedChange = { deleteOriginal = it })
                                Text(stringResource(R.string.mig_delete_original), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showMigrationDialog = false
                            if (sessionManager.isActive) {
                                Toast.makeText(context, AppStrings.get(R.string.mig_refuse_recording), Toast.LENGTH_LONG).show()
                            } else {
                                val uri = pendingTargetUri
                                SessionMigrationWorker.enqueue(
                                    context,
                                    targetUri = uri?.toString(),
                                    targetPath = if (uri == null) sessionManager.defaultBaseDir().absolutePath else null,
                                    deleteSource = deleteOriginal,
                                    switchStorage = true
                                )
                                Toast.makeText(context, AppStrings.get(R.string.mig_started), Toast.LENGTH_SHORT).show()
                            }
                            pendingTargetUri = null
                        }) { Text(stringResource(R.string.mig_start)) }
                    },
                    dismissButton = {
                        Row {
                            if (!pendingManual) {
                                TextButton(onClick = {
                                    // Ohne Migration wechseln — nur URI speichern
                                    showMigrationDialog = false
                                    pendingTargetUri?.let { uri ->
                                        appPreferences.storageBaseUri = uri.toString()
                                        currentBaseUri = uri.toString()
                                    }
                                    pendingTargetUri = null
                                }) { Text(stringResource(R.string.settings_nur_wechseln)) }
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            TextButton(onClick = { showMigrationDialog = false; pendingTargetUri = null }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            SettingsAdvancedBlock(sectionKey = "adv_rec", appPreferences = appPreferences) {
                // T57-B1: Max. Aufnahmelaenge pro WAV
                Spacer(modifier = Modifier.height(12.dp))

                var maxRecordingMinutes by remember { mutableIntStateOf(appPreferences.maxRecordingMinutes) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_max_aufnahmelaenge_pro_wav), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.settings_laengere_sessions_werden_automatisch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_min, maxRecordingMinutes), style = MaterialTheme.typography.bodyMedium)
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
                // T116: Max. Laenge des Detektions-Ausschnitts
                Spacer(modifier = Modifier.height(12.dp))
                var excerptMaxSec by remember { mutableIntStateOf(appPreferences.excerptMaxSec) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_max_laenge_detektions_ausschnitt), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.settings_wiedergabe_zoom_und_teilen_einer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("$excerptMaxSec s", style = MaterialTheme.typography.bodyMedium)
                }
                Slider(
                    value = excerptMaxSec.toFloat(),
                    onValueChange = { v ->
                        val s = (v / 5f).roundToInt() * 5
                        excerptMaxSec = s
                        appPreferences.excerptMaxSec = s
                    },
                    valueRange = 10f..120f,
                    steps = 21  // 10,15,...,120 = 23 Positionen
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                ResamplerSetting()
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
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
                    text = { Text(stringResource(R.string.model_delete_confirm, model.name)) },
                    confirmButton = {
                        TextButton(onClick = {
                            deleteCandidate = null
                            modelManager.deleteModel(model.fileName)
                            installedModels = modelManager.listInstalledModels()
                            Toast.makeText(context, AppStrings.get(R.string.model_deleted, model.name), Toast.LENGTH_SHORT).show()
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
                        // T151a: importierte, nicht aktive Modelle loeschbar
                        if (model.profile.source == ModelProfile.Source.IMPORTED && !model.isActive) {
                            IconButton(onClick = { deleteCandidate = model }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.model_delete))
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.settings_kein_modell_installiert),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Download-Sektion
            if (isDownloading || runningDownload != null) {
                Text(stringResource(R.string.onboarding_wird_heruntergeladen), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { runningDownload ?: downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${((runningDownload ?: downloadProgress) * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                // T104: Abbrechen behaelt die Teildatei zum Fortsetzen
                OutlinedButton(onClick = { modelManager.cancelDownload() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            } else if (isImporting) {
                Text(stringResource(R.string.settings_wird_importiert), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { importProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // Modell-Download Buttons
                Text(stringResource(R.string.settings_modell_herunterladen), style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(4.dp))
                ModelProfile.CATALOG.filter { it.downloadUrl != null }.forEach { model ->
                    val alreadyInstalled = installedModels.any { it.fileName == model.fileName }
                    val partialBytes = remember(model, partialTick, runningDownload) { modelManager.getPartialBytes(model) }
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
                                    Toast.makeText(context, AppStrings.get(R.string.settings_heruntergeladen, model.name), Toast.LENGTH_SHORT).show()
                                } else {
                                    modelError = if (modelManager.lastDownloadError == ModelManager.ERROR_CANCELLED) AppStrings.get(R.string.onboarding_download_abgebrochen_teil_bleibt_zum)
                                        else AppStrings.get(R.string.onboarding_download_fehlgeschlagen) + (modelManager.lastDownloadError ?: AppStrings.get(R.string.onboarding_internetverbindung_pruefen))  // T121/T104
                                }
                            }
                        },
                        enabled = !alreadyInstalled,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Text(
                            if (alreadyInstalled) "${model.name} ✓"
                            else if (partialBytes > 0L) AppStrings.get(R.string.settings_fortsetzen_von_mb, model.name, partialBytes / 1_000_000, model.expectedSizeMB)
                            else "${model.name} (${model.expectedSizeMB} MB)"
                        )
                    }
                    if (partialBytes > 0L && !alreadyInstalled) {
                        TextButton(onClick = {
                            modelManager.discardPartialDownload(model)
                            partialTick++
                        }) {
                            Text(stringResource(R.string.settings_angefangenen_download_verwerfen))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

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
                        appPreferences.topKThreshold = preset.topKThreshold
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

                if (entries.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_arten_2, watchlistManager.name, entries.size),
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
                                    contentDescription = stringResource(R.string.settings_entfernen),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.settings_keine_watchlist_geladen),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_auto_import_downloads_pirol_watchlis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (entries.isNotEmpty()) stringResource(R.string.settings_geladen) else stringResource(R.string.settings_keine_datei_gefunden),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entries.isNotEmpty())
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )

            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsSection(
            sectionKey = "s_species",
            title = stringResource(R.string.settings_section_species_alarm),
            appPreferences = appPreferences,
            defaultExpanded = false
        ) {
            // Region-Chips
            var selectedRegion by remember { mutableStateOf(appPreferences.regionFilter) }

            Text(stringResource(R.string.settings_regionaler_filter), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "ch_breeding" to "CH",
                    "ch_all" to "CH+",
                    "central_europe" to "EU",
                    "all" to AppStrings.get(R.string.common_all),
                    null to AppStrings.get(R.string.common_off)
                ).plus(customRegions.map { it.id to (it.nameDe + " (" + AppStrings.get(R.string.settings_eigene_liste) + ")") })
                .forEach { (regionId, label) ->
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

            customWarnings.forEach {
                Text(
                    AppStrings.get(R.string.settings_eigene_liste_warnung, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            // T190: Saison der Artenliste beachten (Default aus)
            var respectSeason by remember { mutableStateOf(appPreferences.respectListSeason) }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_list_season_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.settings_list_season_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = respectSeason,
                    onCheckedChange = { respectSeason = it; appPreferences.respectListSeason = it }
                )
            }
            // T190: gewaehlte eigene Liste in die Watchlist uebernehmen
            val selectedCustom = selectedRegion?.let { id -> customRegions.firstOrNull { it.id == id } }
                ?.let { regionalFilter.getCustomList(it.id) }
            if (selectedCustom != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { adoptList = selectedCustom },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(stringResource(R.string.settings_list_to_watchlist)) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.ui_watchlist), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // Alarm-Pause-Slider (T58)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_alarm_pause), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.settings_pause_zwischen_alarmen_derselben_art),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (cooldownMinutes == 0) stringResource(R.string.common_off) else stringResource(R.string.settings_min, cooldownMinutes),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Slider(
                value = cooldownMinutes.toFloat(),
                onValueChange = { cooldownMinutes = it.roundToInt() },
                onValueChangeFinished = { appPreferences.alarmCooldownMinutes = cooldownMinutes },
                valueRange = 0f..60f
            )
            Spacer(modifier = Modifier.height(8.dp))

            // --- Alarm-Ausgaenge (T124) ---
            Text(stringResource(R.string.settings_alarm_ausgabe), style = MaterialTheme.typography.bodyMedium)
            AlarmCheckRow(AppStrings.get(R.string.settings_vibration), alarmVibration) {
                alarmVibration = it; appPreferences.alarmVibrationEnabled = it
            }
            AlarmCheckRow(AppStrings.get(R.string.settings_benachrichtigung), alarmNotification) {
                alarmNotification = it; appPreferences.alarmNotificationEnabled = it
                if (it && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            AlarmCheckRow(AppStrings.get(R.string.settings_vollbild_hinweis_in_der_app), alarmBanner) {
                alarmBanner = it; appPreferences.alarmBannerEnabled = it
            }
            if (Build.VERSION.SDK_INT >= 34 && alarmService.needsFullScreenHint()) {
                // T135: Full-Screen-Berechtigung fehlt -> nur Heads-up auf dem Sperrbildschirm
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.settings_alarm_fullscreen_hinweis),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                    Uri.parse("package:" + context.packageName)
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text(stringResource(R.string.settings_alarm_fullscreen_oeffnen)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { alarmService.triggerTestAlarm() }) {
                    Text(stringResource(R.string.settings_alarm_testen))
                }
                OutlinedButton(onClick = { alarmService.triggerTestAlarm(5_000L) }) {
                    Text(stringResource(R.string.settings_alarm_testen_5s))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            // T129: PIROL-Ordner freigeben (Watchlist + eigene Listen)
            Text(
                text = if (folderName != null) stringResource(R.string.settings_pirol_ordner_status, folderName ?: "")
                       else stringResource(R.string.settings_pirol_ordner_keine),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.settings_pirol_ordner_hinweis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folderLauncher.launch(null) }) {
                    Text(stringResource(R.string.settings_pirol_ordner_freigeben))
                }
                if (folderName != null) {
                    OutlinedButton(onClick = {
                        val ok = pirolFolder.writeExampleList()
                        Toast.makeText(
                            context,
                            AppStrings.get(if (ok) R.string.settings_beispielliste_angelegt else R.string.settings_beispielliste_fehler),
                            Toast.LENGTH_SHORT
                        ).show()
                        scope.launch { refreshFolder() }
                    }) { Text(stringResource(R.string.settings_beispielliste_anlegen)) }
                    TextButton(onClick = {
                        pirolFolder.revoke()
                        scope.launch { refreshFolder() }
                    }) { Text(stringResource(R.string.settings_pirol_ordner_entziehen)) }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                // SAF-Import-Button (AP-2)
                OutlinedButton(onClick = {
                    importLauncher.launch(arrayOf("application/json", "*/*"))
                }) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.common_import))
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Reload-Button (AP-3)
                TextButton(onClick = {
                    scope.launch {
                        watchlistManager.load()
                        Toast.makeText(context, AppStrings.get(R.string.settings_watchlist_neu_geladen), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_neu_laden))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Art hinzufuegen Button (AP-4)
            OutlinedButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_art_hinzufuegen))
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            SettingsAdvancedBlock(sectionKey = "adv_species", appPreferences = appPreferences) {
                Text(stringResource(R.string.settings_saisonalitaet_seltenheit), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_saison_seltenheits_hervorhebung_akti), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (phenologyEntryCount == 0) stringResource(R.string.settings_datei_leer_kein_effekt)
                                   else stringResource(R.string.settings_arten_geladen, phenologyEntryCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = phenologyEnabled,
                        onCheckedChange = {
                            phenologyEnabled = it
                            appPreferences.phenologyEnabled = it
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = {
                    scope.launch {
                        phenologyRepository.reload()
                        phenologyEntryCount = phenologyRepository.entryCount()
                        Toast.makeText(context, AppStrings.get(R.string.settings_phenology_json_neu_geladen), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.settings_phenology_json_neu_laden))
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsSection(
            sectionKey = "s_display",
            title = stringResource(R.string.settings_section_display_main),
            appPreferences = appPreferences,
            defaultExpanded = false
        ) {
            Text(stringResource(R.string.settings_artennamen), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            var langExpanded by remember { mutableStateOf(false) }
            // "" = folgt der App-Sprache (nicht explizit gesetzt)
            var selectedLang by remember {
                mutableStateOf(if (appPreferences.speciesLanguageExplicit) appPreferences.speciesLanguage else "")
            }
            val followAppLabel = stringResource(R.string.settings_wie_app_sprache)

            ExposedDropdownMenuBox(
                expanded = langExpanded,
                onExpandedChange = { langExpanded = it }
            ) {
                @Suppress("DEPRECATION")
                OutlinedTextField(
                    value = SpeciesNameResolver.AVAILABLE_LANGUAGES
                        .find { it.first == selectedLang }?.second ?: followAppLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_sprache)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(langExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = langExpanded,
                    onDismissRequest = { langExpanded = false }
                ) {
                    (listOf("" to followAppLabel) + SpeciesNameResolver.AVAILABLE_LANGUAGES).forEach { (code, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                selectedLang = code
                                if (code.isEmpty()) {
                                    appPreferences.speciesLanguageExplicit = false
                                } else {
                                    appPreferences.speciesLanguage = code
                                    appPreferences.speciesLanguageExplicit = true
                                }
                                speciesNameResolver.language = appPreferences.effectiveSpeciesLanguage()
                                langExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            // T220: Artnamen wissenschaftlich anzeigen (Landesname als Untertitel)
            var sciNames by remember { mutableStateOf(appPreferences.scientificNamesDisplay) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_artnamen_wiss))
                    Text(
                        AppStrings.get(R.string.settings_artnamen_wiss_hinweis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = sciNames,
                    onCheckedChange = {
                        sciNames = it
                        appPreferences.scientificNamesDisplay = it
                        speciesNameResolver.scientificNames = it
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            var compactCard by remember { mutableStateOf(appPreferences.compactSpeciesCard) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_artkarte_kompakt))
                    Text(
                        AppStrings.get(R.string.settings_artkarte_kompakt_hinweis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = compactCard,
                    onCheckedChange = {
                        compactCard = it
                        appPreferences.compactSpeciesCard = it
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            val fullscreen by appPreferences.fullscreenFlow.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_fullscreen), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.settings_fullscreen_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = fullscreen, onCheckedChange = { appPreferences.fullscreen = it })
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // SpectrogramConfig Chips: Voegel / Fledermaus / Breitband
            var selectedSpecConfig by remember { mutableStateOf(appPreferences.spectrogramConfigName) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "BIRDS" to AppStrings.get(R.string.live_voegel),
                    "BATS" to AppStrings.get(R.string.live_fledermaus),
                    "WIDEBAND" to AppStrings.get(R.string.live_breitband)
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

            // T107: Darstellung (Palette, Kontrast, Gamma, Deckel, Frequenz, Zeitfenster) —
            // dieselben Regler und derselbe Zustand wie im Langdruck-Panel von Live- und Analyse-Tab.
            val specRepo: SpectrogramSettingsRepository = koinInject()
            val specSettings by specRepo.settings.collectAsState()
            val specConfig = when (selectedSpecConfig) {
                "BATS" -> SpectrogramConfig.BATS
                "WIDEBAND" -> SpectrogramConfig.WIDEBAND
                else -> SpectrogramConfig.BIRDS
            }
            Text(
                text = stringResource(R.string.settings_tipp_langdruck_auf_das_sonogramm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            SpectrogramSettingsPanel(
                repository = specRepo,
                settings = specSettings,
                config = specConfig,
                showWindow = true,
                onClose = null,
                maxHeight = Dp.Infinity
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsSection(
            sectionKey = "s_geo",
            title = stringResource(R.string.settings_section_map_gps),
            appPreferences = appPreferences,
            defaultExpanded = false
        ) {
            Text("GPS", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // T152: GPS-Modus
            var gpsMode by remember { mutableStateOf(appPreferences.gpsMode) }
            Text(stringResource(R.string.settings_gps_mode), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ch.etasystems.pirol.data.GPS_MODE_CONTINUOUS to R.string.settings_gps_mode_continuous,
                    ch.etasystems.pirol.data.GPS_MODE_ON_DETECTION to R.string.settings_gps_mode_on_detection
                ).forEach { (mode, labelRes) ->
                    FilterChip(
                        selected = gpsMode == mode,
                        onClick = {
                            gpsMode = mode
                            appPreferences.gpsMode = mode
                        },
                        label = { Text(stringResource(labelRes)) }
                    )
                }
            }
            val gpsOnDetection = gpsMode == ch.etasystems.pirol.data.GPS_MODE_ON_DETECTION
            if (gpsOnDetection) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_gps_mode_on_detection_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            // T191: Route aufzeichnen
            var trackRecordingEnabled by remember { mutableStateOf(appPreferences.trackRecordingEnabled) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_track_recording), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.settings_track_recording_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = trackRecordingEnabled,
                    onCheckedChange = {
                        trackRecordingEnabled = it
                        appPreferences.trackRecordingEnabled = it
                    }
                )
            }
            if (trackRecordingEnabled && gpsOnDetection) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_track_recording_on_detection_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            SettingsAdvancedBlock(sectionKey = "adv_geo", appPreferences = appPreferences) {
                // GPS-Intervall
                var selectedGpsInterval by remember { mutableIntStateOf(appPreferences.gpsIntervalSeconds) }
                Text(stringResource(R.string.settings_gps_intervall), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_kuerzere_intervalle_genauere_positio),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2 to "2s", 5 to "5s", 10 to "10s", 20 to "20s", 60 to "60s").forEach { (sec, label) ->
                        FilterChip(
                            selected = selectedGpsInterval == sec,
                            enabled = !gpsOnDetection,
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
                    text = stringResource(R.string.settings_wirkt_ab_naechster_aufnahme),
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
                        Text(stringResource(R.string.settings_max_gps_ungenauigkeit), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.settings_fixes_ungenauer_als_dieser_wert),
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
                        Text(stringResource(R.string.settings_gps_smoothing), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = stringResource(R.string.settings_median_ueber_5_fixes_reduziert),
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
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsSection(
            sectionKey = "s_export",
            title = stringResource(R.string.settings_section_export_upload),
            appPreferences = appPreferences,
            defaultExpanded = false
        ) {
            var ravenFieldsExpanded by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { ravenFieldsExpanded = !ravenFieldsExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_raven_export_felder),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (ravenFieldsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (ravenFieldsExpanded) stringResource(R.string.settings_einklappen) else stringResource(R.string.settings_aufklappen)
                )
            }

            AnimatedVisibility(visible = ravenFieldsExpanded) {
                Column {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_spalten_die_in_recording_selections),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    var ravenBeginTime     by remember { mutableStateOf(appPreferences.ravenFieldBeginTime) }
                    var ravenEndTime       by remember { mutableStateOf(appPreferences.ravenFieldEndTime) }
                    var ravenLowFreq       by remember { mutableStateOf(appPreferences.ravenFieldLowFreq) }
                    var ravenHighFreq      by remember { mutableStateOf(appPreferences.ravenFieldHighFreq) }
                    var ravenSpecies       by remember { mutableStateOf(appPreferences.ravenFieldSpecies) }
                    var ravenConfidence    by remember { mutableStateOf(appPreferences.ravenFieldConfidence) }
                    var ravenBeginFile     by remember { mutableStateOf(appPreferences.ravenFieldBeginFile) }
                    var ravenBeginDateTime by remember { mutableStateOf(appPreferences.ravenFieldBeginDateTime) }
                    var ravenLat           by remember { mutableStateOf(appPreferences.ravenFieldLat) }
                    var ravenLon           by remember { mutableStateOf(appPreferences.ravenFieldLon) }
                    var ravenSimultan      by remember { mutableStateOf(appPreferences.ravenFieldSimultan) }
                    var ravenMoeglich      by remember { mutableStateOf(appPreferences.ravenFieldMoeglich) }
                    var ravenGleich        by remember { mutableStateOf(appPreferences.ravenFieldGleich) }

                    listOf(
                        Triple("Begin Time (s)",   ravenBeginTime,     { v: Boolean -> ravenBeginTime = v;     appPreferences.ravenFieldBeginTime = v }),
                        Triple("End Time (s)",      ravenEndTime,       { v: Boolean -> ravenEndTime = v;       appPreferences.ravenFieldEndTime = v }),
                        Triple("Low Freq (Hz)",     ravenLowFreq,       { v: Boolean -> ravenLowFreq = v;       appPreferences.ravenFieldLowFreq = v }),
                        Triple("High Freq (Hz)",    ravenHighFreq,      { v: Boolean -> ravenHighFreq = v;      appPreferences.ravenFieldHighFreq = v }),
                        Triple("Species",           ravenSpecies,       { v: Boolean -> ravenSpecies = v;       appPreferences.ravenFieldSpecies = v }),
                        Triple("Confidence",        ravenConfidence,    { v: Boolean -> ravenConfidence = v;    appPreferences.ravenFieldConfidence = v }),
                        Triple("Begin File",        ravenBeginFile,     { v: Boolean -> ravenBeginFile = v;     appPreferences.ravenFieldBeginFile = v }),
                        Triple("Begin Date Time",   ravenBeginDateTime, { v: Boolean -> ravenBeginDateTime = v; appPreferences.ravenFieldBeginDateTime = v }),
                        Triple("Lat",               ravenLat,           { v: Boolean -> ravenLat = v;           appPreferences.ravenFieldLat = v }),
                        Triple("Lon",               ravenLon,           { v: Boolean -> ravenLon = v;           appPreferences.ravenFieldLon = v })
                    ).forEach { (label, checked, onToggle) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = checked,
                                onCheckedChange = onToggle
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_wirkt_beim_naechsten_export),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        AppStrings.get(R.string.settings_simultanbeobachtungen),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                    listOf(
                        Triple(AppStrings.get(R.string.settings_simultan), ravenSimultan, { v: Boolean -> ravenSimultan = v; appPreferences.ravenFieldSimultan = v }),
                        Triple(AppStrings.get(R.string.settings_moeglich),  ravenMoeglich, { v: Boolean -> ravenMoeglich = v; appPreferences.ravenFieldMoeglich = v }),
                        Triple(AppStrings.get(R.string.settings_gleich),    ravenGleich,   { v: Boolean -> ravenGleich   = v; appPreferences.ravenFieldGleich   = v })
                    ).forEach { (label, checked, onToggle) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = checked, onCheckedChange = onToggle)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.settings_export), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // WLAN-only Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.settings_nur_bei_wlan_exportieren), modifier = Modifier.weight(1f))
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
                Text(stringResource(R.string.settings_automatisch_nach_aufnahme), modifier = Modifier.weight(1f))
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
                text = stringResource(R.string.settings_automatischer_upload_findet_nur_mit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            UploadSettingsSection(appPreferences)
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsSection(
            sectionKey = "s_info",
            title = stringResource(R.string.settings_info_section),
            appPreferences = appPreferences,
            defaultExpanded = false
        ) {
            Text(
                text = stringResource(R.string.settings_info_version, ch.etasystems.pirol.ui.components.currentVersionName(context)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_info_sources),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { showWhatsNew = true }) {
                Text(stringResource(R.string.settings_whats_new_button))
            }
            // T190: Listen-Editor im Browser oeffnen
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    if (!ListEditorLauncher.open(context)) {
                        Toast.makeText(context, AppStrings.get(R.string.settings_open_list_editor_failed), Toast.LENGTH_LONG).show()
                    }
                }
            ) { Text(stringResource(R.string.settings_open_list_editor)) }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))
            SettingsAdvancedBlock(sectionKey = "adv_info", appPreferences = appPreferences) {
                Text(stringResource(R.string.settings_diagnose), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_misst_die_birdnet_inference_des),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !benchmarkRunning,
                    onClick = {
                        val modelFile = modelManager.getActiveModelFile()
                        if (modelFile == null) {
                            benchmarkReport = AppStrings.get(R.string.settings_kein_modell_installiert_2)
                        } else {
                            benchmarkRunning = true
                            benchmarkReport = ""
                            scope.launch {
                                benchmarkReport = try {
                                    val p = modelManager.getActiveProfile()  // T151a: Eingang/Fenster laut Profil
                                    OnnxBenchmark.run(modelFile, p.windowSamples, p.inputName, p.windowMs) { benchmarkProgress = it }
                                } catch (e: Exception) {
                                    AppStrings.get(R.string.settings_benchmark_fehlgeschlagen, e.message)
                                }
                                benchmarkRunning = false
                            }
                        }
                    }
                ) {
                    Text(if (benchmarkRunning) stringResource(R.string.settings_laeuft, benchmarkProgress) else stringResource(R.string.settings_inference_benchmark_starten))
                }
                if (benchmarkReport.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = benchmarkReport,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                // T146: Benchmark fuer eine beliebige ONNX-Datei
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_bench_datei_hinweis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val genericBenchContext = LocalContext.current
                val genericBenchLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        genericBenchRunning = true
                        genericBenchReport = ""
                        scope.launch {
                            val tmp = File(genericBenchContext.cacheDir, "t146_bench.onnx")
                            genericBenchReport = try {
                                genericBenchProgress = AppStrings.get(R.string.settings_bench_datei_kopiere)
                                val name = withContext(Dispatchers.IO) {
                                    genericBenchContext.contentResolver.openInputStream(uri)!!.use { input ->
                                        tmp.outputStream().use { input.copyTo(it) }
                                    }
                                    genericBenchContext.contentResolver.query(
                                        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "model.onnx"
                                }
                                OnnxBenchmark.runGeneric(tmp, name) { genericBenchProgress = it }
                            } catch (e: Throwable) {
                                AppStrings.get(R.string.settings_benchmark_fehlgeschlagen, e.message)
                            } finally {
                                tmp.delete()
                            }
                            genericBenchRunning = false
                        }
                    }
                }
                OutlinedButton(
                    enabled = !genericBenchRunning && !benchmarkRunning,
                    onClick = { genericBenchLauncher.launch(arrayOf("*/*")) }
                ) {
                    Text(if (genericBenchRunning) stringResource(R.string.settings_laeuft, genericBenchProgress) else stringResource(R.string.settings_bench_datei_waehlen))
                }
                if (genericBenchReport.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = genericBenchReport,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
    androidx.compose.material3.SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    }

    // T190: Watchlist-Uebernahme (Zusammenfuehren / Ersetzen)
    adoptList?.let { list ->
        fun adopt(merge: Boolean) {
            adoptList = null
            scope.launch {
                val previous = watchlistManager.adoptFromList(list, merge) { sci ->
                    if (speciesNameResolver.getAllNames(sci).isNotEmpty()) speciesNameResolver.resolve(sci)
                    else list.commonNames[sci] ?: ""
                }
                val result = snackbarHostState.showSnackbar(
                    message = AppStrings.get(R.string.settings_list_to_watchlist_done, watchlistManager.entries.size),
                    actionLabel = AppStrings.get(R.string.common_undo),
                    duration = androidx.compose.material3.SnackbarDuration.Long
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    watchlistManager.restore(previous)
                }
            }
        }
        AlertDialog(
            onDismissRequest = { adoptList = null },
            title = { Text(stringResource(R.string.settings_list_to_watchlist)) },
            text = { Text(stringResource(R.string.settings_list_to_watchlist_text, list.nameDe, list.species.size.toString())) },
            confirmButton = {
                Row {
                    TextButton(onClick = { adopt(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.settings_list_replace))
                    }
                    TextButton(onClick = { adopt(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.settings_list_merge))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { adoptList = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showWhatsNew) {
        ch.etasystems.pirol.ui.components.WhatsNewDialog(onDismiss = { showWhatsNew = false })
    }

    // --- Art-Hinzufuegen-Dialog (AP-4) ---
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                searchQuery = ""
            },
            title = { Text(stringResource(R.string.settings_art_zur_watchlist_hinzufuegen)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(stringResource(R.string.settings_art_suchen)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Vorschlaege: Trivialname (gewaehlte Sprache) ODER wissenschaftlicher Name
                    val suggestions = if (searchQuery.length >= 2) {
                        allWatchlistSpecies.filter { (scientific, common) ->
                            common.contains(searchQuery, ignoreCase = true) ||
                                scientific.contains(searchQuery, ignoreCase = true)
                        }.take(8)
                    } else emptyList()

                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(suggestions) { (scientific, common) ->
                            Text(
                                text = "$common (${scientific.replace('_', ' ')})",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            watchlistManager.addSpecies(
                                                WatchlistEntry(
                                                    scientificName = scientific,
                                                    commonName = common,
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
                }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** Checkbox-Zeile fuer die Alarm-Ausgaenge (T124), 48 dp Tap-Target. */
@Composable
private fun AlarmCheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}


/** T134: Einklappbare Einstellungs-Sektion (Kopfzeile 56 dp, Zustand pro Sektion in AppPreferences). */
@Composable
private fun SettingsSection(
    sectionKey: String,
    title: String,
    appPreferences: AppPreferences,
    defaultExpanded: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(appPreferences.isSettingsSectionExpanded(sectionKey, defaultExpanded)) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable {
                        expanded = !expanded
                        appPreferences.setSettingsSectionExpanded(sectionKey, expanded)
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    content()
                }
            }
        }
    }
}

/** T209: Einklappbarer Unterblock "Erweitert" am Ende einer Sektion (Zustand in AppPreferences, Standard zu). */
@Composable
private fun SettingsAdvancedBlock(
    sectionKey: String,
    appPreferences: AppPreferences,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(appPreferences.isSettingsSectionExpanded(sectionKey, false)) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable {
                    expanded = !expanded
                    appPreferences.setSettingsSectionExpanded(sectionKey, expanded)
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_advanced),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                content()
            }
        }
    }
}

/** T134: Resampler-Auswahl (48 -> 32 kHz). Liest/schreibt direkt "pirol_resampler_mode" (POLYPHASE/LINEAR). */
@Composable
private fun ResamplerSetting() {
    val appPrefsForResampler: ch.etasystems.pirol.data.AppPreferences = org.koin.compose.koinInject()
    var mode by remember { mutableStateOf(appPrefsForResampler.resamplerMode) }
    Text(stringResource(R.string.settings_resampler_title), style = MaterialTheme.typography.bodyMedium)
    listOf(
        "POLYPHASE" to stringResource(R.string.settings_resampler_polyphase),
        "LINEAR" to stringResource(R.string.settings_resampler_linear)
    ).forEach { (value, label) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable {
                    mode = value
                    appPrefsForResampler.resamplerMode = value  // T133-Setter: wirkt sofort (AudioResampler.defaultMode)
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = mode == value, onClick = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(label)
        }
    }
    // T208: Hinweis, ab wann der Wechsel greift
    Text(
        stringResource(R.string.settings_resampler_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
