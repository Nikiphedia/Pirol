package ch.etasystems.pirol.ui.settings.sections

import ch.etasystems.pirol.ui.settings.SpeciesGroupSettings
import ch.etasystems.pirol.ui.settings.GeoFilterSettings
import ch.etasystems.pirol.ui.settings.SettingsSection
import ch.etasystems.pirol.ui.settings.SettingsAdvancedBlock
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import ch.etasystems.pirol.ml.ModelManager
import ch.etasystems.pirol.ml.PhenologyRepository
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.TaxonGroup
import ch.etasystems.pirol.ml.RegionInfo
import ch.etasystems.pirol.ml.PirolFolder
import ch.etasystems.pirol.ml.WatchlistManager
import ch.etasystems.pirol.ml.WatchlistPriority
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

/** T241: Sektion Arten & Alarm. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
internal fun SpeciesAlarmSettingsSection(
    appPreferences: AppPreferences,
    expert: Boolean,
    watchlistManager: WatchlistManager,
    regionalFilter: RegionalSpeciesFilter,
    onAdoptList: (ch.etasystems.pirol.ml.CustomSpeciesList) -> Unit,
    onShowAddDialog: () -> Unit
) {
    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    val entries by watchlistManager.entriesFlow.collectAsState()

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

    // Phenology State (T74)
    val phenologyRepository: PhenologyRepository = koinInject()
    var phenologyEnabled by remember { mutableStateOf(appPreferences.phenologyEnabled) }
    var phenologyEntryCount by remember { mutableIntStateOf(phenologyRepository.entryCount()) }

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
    LaunchedEffect(Unit) { refreshFolder(); watchlistManager.refreshExpired() }  // T244
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

    SettingsSection(
        sectionKey = "s_species",
        title = stringResource(R.string.settings_section_species_alarm),
        appPreferences = appPreferences,
        defaultExpanded = false
    ) {
        // T232: Artenliste je Artgruppe (ersetzt die einzelne Regionswahl und die Geomodel-Verknuepfung)
        var groupSel by remember { mutableStateOf(appPreferences.speciesGroups) }
        SpeciesGroupSettings(groupSel, regionalFilter.getAvailableRegions()) { sel ->
            groupSel = sel
            appPreferences.speciesGroups = sel
            scope.launch(Dispatchers.IO) { regionalFilter.loadRegion(RegionalSpeciesFilter.GROUPS_ID) }
        }

        // T218: Geomodel-Einstellungen; T218b: Hinweis + Download immer, Rest nur wenn eine Gruppe auf Auto steht
        Spacer(modifier = Modifier.height(8.dp))
        GeoFilterSettings(
            appPreferences, koinInject<ModelManager>(), regionalFilter.getAvailableRegions(),
            active = groupSel.usesAuto()
        )

        customWarnings.forEach {
            Text(
                AppStrings.get(R.string.settings_eigene_liste_warnung, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (expert) {
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
        }
        // T190: gewaehlte eigene Liste in die Watchlist uebernehmen
        val selectedCustom = TaxonGroup.entries.map { groupSel.sourceOf(it) }
            .firstNotNullOfOrNull { id -> customRegions.firstOrNull { it.id == id } }
            ?.let { regionalFilter.getCustomList(it.id) }
        if (selectedCustom != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onAdoptList(selectedCustom) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(stringResource(R.string.settings_list_to_watchlist)) }
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.ui_watchlist), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        if (expert) {
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
        }

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
        if (expert) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { alarmService.triggerTestAlarm() }) {
                    Text(stringResource(R.string.settings_alarm_testen))
                }
                OutlinedButton(onClick = { alarmService.triggerTestAlarm(5_000L) }) {
                    Text(stringResource(R.string.settings_alarm_testen_5s))
                }
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
                    entry.expiresAtMs?.let { until ->
                        Text(
                            text = stringResource(
                                R.string.settings_alarm_bis,
                                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                                    .format(java.util.Date(until))
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
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
        Spacer(modifier = Modifier.height(12.dp))

        // Art hinzufuegen Button (AP-4)
        OutlinedButton(onClick = { onShowAddDialog() }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_art_hinzufuegen))
        }
        if (expert) {
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

