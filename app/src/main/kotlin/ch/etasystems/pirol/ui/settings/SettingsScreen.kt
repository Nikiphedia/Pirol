package ch.etasystems.pirol.ui.settings

import ch.etasystems.pirol.ui.help.helpTopic

import ch.etasystems.pirol.ui.settings.sections.LanguageSettingsSection
import ch.etasystems.pirol.ui.settings.sections.RecordingSettingsSection
import ch.etasystems.pirol.ui.settings.sections.ModelSettingsSection
import ch.etasystems.pirol.ui.settings.sections.SpeciesAlarmSettingsSection
import ch.etasystems.pirol.ui.settings.sections.DisplaySettingsSection
import ch.etasystems.pirol.ui.settings.sections.MapGpsSettingsSection
import ch.etasystems.pirol.ui.settings.sections.ExportSettingsSection
import ch.etasystems.pirol.ui.settings.sections.InfoSettingsSection
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.data.sync.UploadManager
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.ModelManager
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ml.WatchlistEntry
import ch.etasystems.pirol.ml.WatchlistManager
import ch.etasystems.pirol.ml.WatchlistPriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import ch.etasystems.pirol.R
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
    sessionManager: SessionManager = koinInject(),
    recordingPipeline: ch.etasystems.pirol.audio.RecordingPipeline = koinInject(),
    onStartSetup: () -> Unit = {}  // T226: Einrichtungsassistent erneut starten
) {
    val scope = rememberCoroutineScope()
    var expert by remember { mutableStateOf(appPreferences.expertMode) }  // T227

    // Watchlist-Dialoge (T21, T190); die Sektion "Arten & Alarm" setzt sie ueber Callbacks
    var showAddDialog by remember { mutableStateOf(false) }
    var showWhatsNew by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // Art-Vorschlagsliste fuer Dialog: einmalig geladen, ausserhalb des if-Blocks (Compose-Regel)
    var allWatchlistSpecies by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        // T238: Live-Filter nur lesen; ist er (noch) leer, die Arten der Gruppenwahl ohne Umladen ermitteln (IO)
        val species = withContext(Dispatchers.IO) {
            regionalFilter.getSpeciesList().ifEmpty { regionalFilter.previewGroupSpecies() }
        }
        speciesNameResolver.load()
        allWatchlistSpecies = species.sorted().map { scientific ->
            Pair(scientific, speciesNameResolver.displayName(scientific))
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

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onStartSetup,
            modifier = Modifier.helpTopic(R.string.help_settings_setup, "1").fillMaxWidth().heightIn(min = 48.dp)
        ) { Text(stringResource(R.string.setup_start)) }
        Spacer(modifier = Modifier.height(12.dp))
        // T227: Einfach/Experte; Experte zeigt zusaetzlich alle Feineinstellungen
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_expert_mode), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.settings_expert_mode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = expert,
                onCheckedChange = { expert = it; appPreferences.expertMode = it },
                modifier = Modifier.helpTopic(R.string.help_settings_expert, "17")
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        LanguageSettingsSection(appPreferences, speciesNameResolver)
        Spacer(modifier = Modifier.height(12.dp))
        RecordingSettingsSection(appPreferences, expert, sessionManager)
        Spacer(modifier = Modifier.height(12.dp))
        ModelSettingsSection(appPreferences, expert, classifier, modelManager, recordingPipeline)
        Spacer(modifier = Modifier.height(12.dp))
        SpeciesAlarmSettingsSection(
            appPreferences, expert, watchlistManager, regionalFilter,
            onAdoptList = { adoptList = it },
            onShowAddDialog = { showAddDialog = true }
        )
        Spacer(modifier = Modifier.height(12.dp))
        DisplaySettingsSection(appPreferences, expert, speciesNameResolver)
        Spacer(modifier = Modifier.height(12.dp))
        MapGpsSettingsSection(appPreferences, expert)
        Spacer(modifier = Modifier.height(12.dp))
        ExportSettingsSection(appPreferences, expert, uploadManager)
        Spacer(modifier = Modifier.height(12.dp))
        InfoSettingsSection(appPreferences, expert, modelManager, classifier, onShowWhatsNew = { showWhatsNew = true })
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
                    if (speciesNameResolver.getAllNames(sci).isNotEmpty()) speciesNameResolver.commonName(sci)
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
