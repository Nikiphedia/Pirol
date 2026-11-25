package ch.etasystems.pirol.ui.settings.sections

import ch.etasystems.pirol.ui.settings.ListEditorLauncher
import ch.etasystems.pirol.ui.settings.SettingsSection
import ch.etasystems.pirol.ui.settings.SettingsAdvancedBlock
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ml.ModelManager
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import ch.etasystems.pirol.ml.OnnxBenchmark
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings
import ch.etasystems.pirol.ui.help.helpTopic

/** T277: Spendenseite (freiwillig, oeffnet nur den Browser). */
private const val DONATE_URL = "https://www.paypal.com/ncp/payment/ZFYW7YQSVE9XW"

/** T241: Sektion Info. */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
internal fun InfoSettingsSection(
    appPreferences: AppPreferences,
    expert: Boolean,
    modelManager: ModelManager,
    onShowWhatsNew: () -> Unit
) {
    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    var versionTaps by remember { mutableIntStateOf(0) }

    // Diagnose: Inference-Benchmark (T96)
    var benchmarkRunning by remember { mutableStateOf(false) }
    var benchmarkProgress by remember { mutableStateOf("") }
    var benchmarkReport by remember { mutableStateOf("") }
    // T146: Benchmark fuer beliebige ONNX-Datei
    var genericBenchRunning by remember { mutableStateOf(false) }
    var genericBenchProgress by remember { mutableStateOf("") }
    var genericBenchReport by remember { mutableStateOf("") }

    SettingsSection(
        sectionKey = "s_info",
        title = stringResource(R.string.settings_info_section),
        appPreferences = appPreferences,
        defaultExpanded = false
    ) {
        Text(
            text = stringResource(R.string.settings_info_version, ch.etasystems.pirol.ui.components.currentVersionName(context)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // T297: 7x Tippen schaltet die versteckten Fledermaus-Funktionen um (keine Anzeige, kein Hilfetext)
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                versionTaps++
                if (versionTaps >= 7) {
                    versionTaps = 0
                    appPreferences.experimentalBats = !appPreferences.experimentalBats
                    Toast.makeText(context, AppStrings.get(
                        if (appPreferences.experimentalBats) R.string.experimental_bats_on else R.string.experimental_bats_off
                    ), Toast.LENGTH_SHORT).show()
                    // T304: Ultraschall-Modell als Primaer-/Sekundaermodell zuruecksetzen
                    if (!appPreferences.experimentalBats && modelManager.resetUltrasonicModels()) {
                        Toast.makeText(context, AppStrings.get(R.string.experimental_bats_models_reset), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
        Text(
            text = stringResource(R.string.settings_info_developer),  // T302
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_info_sources),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.settings_info_geomodel),  // T218
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.settings_info_lists),  // T228: Quellenangabe CH-Heuschreckenliste
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { onShowWhatsNew() }) {
            Text(stringResource(R.string.settings_whats_new_button))
        }
        // T277: Spendenknopf, kein SDK, kein Tracking
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            modifier = Modifier.heightIn(min = 48.dp).helpTopic(R.string.help_settings_donate, "17"),
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Toast.makeText(context, AppStrings.get(R.string.settings_donate_failed), Toast.LENGTH_LONG).show()
                }
            }
        ) { Text(stringResource(R.string.settings_donate_button)) }
        Text(
            text = stringResource(R.string.settings_donate_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
        if (expert) {
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
    }
}
