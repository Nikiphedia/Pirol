package ch.etasystems.pirol.ui.settings.sections

import ch.etasystems.pirol.ui.settings.SettingsSection
import ch.etasystems.pirol.ui.settings.SettingsAdvancedBlock
import androidx.compose.material3.Checkbox
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.MigrationRefusal
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.data.repository.SessionMigrationLogic
import ch.etasystems.pirol.data.repository.SessionMigrationWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ch.etasystems.pirol.ml.InferenceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

/** T241: Sektion Aufnahme. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
internal fun RecordingSettingsSection(
    appPreferences: AppPreferences,
    expert: Boolean,
    sessionManager: SessionManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // T29: Power-Profile State
    var selectedInterval by remember { mutableLongStateOf(appPreferences.inferenceIntervalMs) }

    SettingsSection(
        sectionKey = "s_rec",
        title = stringResource(R.string.settings_section_recording),
        appPreferences = appPreferences,
        defaultExpanded = false
    ) {
        if (expert) {
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
            // --- T231: groesster Hop fuer lange Modellfenster (Perch 5 s) ---
            Spacer(modifier = Modifier.height(12.dp))
            var selectedMaxHop by remember { mutableIntStateOf(appPreferences.maxHopMs) }
            Text(stringResource(R.string.settings_max_hop, selectedMaxHop / 1000), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InferenceConfig.MAX_HOP_OPTIONS_MS.forEach { v ->
                    FilterChip(
                        selected = selectedMaxHop == v,
                        onClick = {
                            selectedMaxHop = v
                            appPreferences.maxHopMs = v
                        },
                        label = { Text("${v / 1000} s") }
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_max_hop_hinweis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        }
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
        if (expert) {
            SettingsAdvancedBlock(sectionKey = "adv_rec", appPreferences = appPreferences) {
                MaxRecordingSetting(appPreferences)
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
        } else MaxRecordingSetting(appPreferences)
    }
}

/** T227: Max. Aufnahmelaenge pro WAV (T57-B1); im Einfach-Modus direkt, im Expertenmodus im Erweitert-Block. */
@Composable
private fun MaxRecordingSetting(appPreferences: AppPreferences) {
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
