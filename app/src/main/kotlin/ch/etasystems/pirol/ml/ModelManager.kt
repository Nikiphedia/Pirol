package ch.etasystems.pirol.ml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ch.etasystems.pirol.R
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ui.AppStrings
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Verwaltet mehrere BirdNET-ONNX-Modelle: Import, Download, Umschaltung.
 * Modelle liegen in filesDir/models/ mit eindeutigem Dateinamen pro Variante.
 */
class ModelManager(
    private val context: Context,
    private val appPreferences: AppPreferences
) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODEL_DIR = "models"

        /** T103a: Mindestgroesse einer Modelldatei (3.0 final pruned = 72 MB) */
        private const val MIN_MODEL_SIZE_BYTES = 50_000_000L
        private const val IMPORT_TEMP_PREFIX = "import_pending_"

        /** T104: Marker in [lastDownloadError], wenn der User den Download abgebrochen hat */
        const val ERROR_CANCELLED = "abgebrochen"
    }

    // T102: eigener Scope, damit ein Download das Verlassen des Screens ueberlebt
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadLock = Any()
    private var activeDownload: Deferred<Boolean>? = null
    private val _downloadProgress = MutableStateFlow<Float?>(null)

    /** Fortschritt 0.0-1.0 des laufenden Downloads, null = kein Download aktiv */
    val downloadProgress: StateFlow<Float?> = _downloadProgress

    private val _downloadingFile = MutableStateFlow<String?>(null)

    /** T227: Dateiname des Modells, dessen Download gerade laeuft (null = keiner); fuer das Lade-Symbol in der Modelltabelle */
    val downloadingFile: StateFlow<String?> = _downloadingFile

    /** T121: Grund des letzten fehlgeschlagenen Downloads fuer die UI (null = kein Fehler) */
    @Volatile
    var lastDownloadError: String? = null
        private set

    init {
        // T151a: importierte Profile (filesDir/models/*.pirol.json) vor dem ersten getActiveProfile() einlesen
        reloadImportedProfiles()
        // T103a: vor T103a importiertes 3.0-final-Modell hat keine Label-Datei → einmal beim Start nachladen
        downloadScope.launch {
            val profile = getActiveProfile()
            if (profile.labelUrl != null && getLabelFile(profile) == null) {
                Log.i(TAG, "Label-Datei fuer ${profile.id} fehlt — wird nachgeladen")
                ensureLabels(profile)
            }
        }
    }

    /** Installiertes Modell fuer UI-Anzeige (T103a: mit zugeordnetem Profil) */
    data class InstalledModel(
        val fileName: String,
        val name: String,
        val sizeMB: Int,
        val isActive: Boolean,
        val profile: ModelProfile
    )

    /** T103a: Ergebnis eines Datei-Imports */
    sealed class ImportResult {
        data class Success(val profile: ModelProfile) : ImportResult()
        /** Profil nicht erkennbar (unbekannte Groesse) — UI fragt nach, dann [assignImportedModel] */
        data class NeedsProfile(val pendingFileName: String, val sizeMB: Int) : ImportResult()
        data class Failed(val message: String) : ImportResult()
    }

    /** Ob mindestens ein Modell in filesDir verfuegbar ist */
    fun isModelInstalled(): Boolean {
        return getActiveModelFile()?.exists() == true ||
                listInstalledModels().isNotEmpty()
    }

    /** Groesse des aktiven Modells in MB, oder 0 */
    fun getInstalledModelSizeMB(): Int {
        val file = getActiveModelFile() ?: return 0
        if (!file.exists()) return 0
        return (file.length() / 1_000_000).toInt()
    }

    /** Pfad zum aktiven Modell (aus AppPreferences) */
    fun getActiveModelFile(): File? {
        val modelsDir = File(context.filesDir, MODEL_DIR)
        val activeFileName = appPreferences.activeModelFileName

        // 1. Aktives Modell pruefen
        val activeFile = File(modelsDir, activeFileName)
        if (activeFile.exists() && isUsableModelFile(activeFile)) {
            return activeFile
        }

        // 2. Irgendein installiertes Modell als Fallback
        val anyModel = modelsDir.listFiles()
            ?.firstOrNull { isUsableModelFile(it) }
        if (anyModel != null) {
            appPreferences.activeModelFileName = anyModel.name
            return anyModel
        }

        return null
    }

    /** T221: installierte Modelldatei per Dateiname (null = fehlt/unbrauchbar) */
    fun getModelFile(fileName: String): File? =
        File(File(context.filesDir, MODEL_DIR), fileName).takeIf { it.exists() && isUsableModelFile(it) }

    /** T103a: Profil des aktiven Modells (ohne Modelldatei: Preview-Profil fuer das Asset-Modell) */
    fun getActiveProfile(): ModelProfile {
        val file = getActiveModelFile() ?: return ModelProfile.LEGACY_DEFAULT
        return ModelProfile.resolve(file.name, file.length())
    }

    /**
     * T103a: Label-Datei eines Profils in filesDir/models/ (null = Profil nutzt Asset-Labels
     * oder Datei fehlt).
     */
    fun getLabelFile(profile: ModelProfile): File? {
        val name = profile.labelFileName ?: return null
        val file = File(File(context.filesDir, MODEL_DIR), name)
        return file.takeIf { it.exists() && it.length() > 0 }
    }

    /** Alle installierten Modelle auflisten */
    fun listInstalledModels(): List<InstalledModel> {
        val modelsDir = File(context.filesDir, MODEL_DIR)
        if (!modelsDir.exists()) return emptyList()

        val activeFileName = appPreferences.activeModelFileName

        return modelsDir.listFiles()
            ?.filter { isUsableModelFile(it) }
            ?.map { file ->
                val profile = ModelProfile.resolve(file.name, file.length())
                InstalledModel(
                    fileName = file.name,
                    name = profile.name,
                    sizeMB = (file.length() / 1_000_000).toInt(),
                    isActive = file.name == activeFileName,
                    profile = profile
                )
            }
            ?.sortedByDescending { it.isActive }
            ?: emptyList()
    }

    /** Aktives Modell umschalten (Preference setzen) */
    fun setActiveModel(fileName: String) {
        val file = File(File(context.filesDir, MODEL_DIR), fileName)
        if (!file.exists()) {
            Log.e(TAG, "setActiveModel: Datei existiert nicht: $fileName")
            return
        }
        appPreferences.activeModelFileName = fileName
        appPreferences.secondaryModelFileName = ModelRoles.secondaryAfterPrimaryChange(fileName, appPreferences.secondaryModelFileName)  // T223
        Log.i(TAG, "Aktives Modell gesetzt: $fileName")
    }

    /**
     * Importiert eine Modell- oder Label-Datei via SAF URI (T103a).
     *
     * - `.csv` mit Kopfzeile von 3.0 final: wird als Label-Datei fuer 3.0 final abgelegt.
     * - Modelldatei: Profil wird ueber die exakte Groesse erkannt und die Datei unter dem
     *   Katalog-Dateinamen abgelegt. Unbekannte Groesse → [ImportResult.NeedsProfile].
     * - Benoetigt das Profil eine Label-Datei, die fehlt, wird sie heruntergeladen.
     */
    suspend fun importFromUri(uri: Uri, onProgress: (Float) -> Unit = {}): ImportResult =
        withContext(Dispatchers.IO) {
            val targetDir = File(context.filesDir, MODEL_DIR)
            targetDir.mkdirs()

            val displayName = queryDisplayName(uri) ?: "imported_model.onnx"
            val totalSize = queryFileSize(uri)
            val isCsv = displayName.endsWith(".csv", ignoreCase = true)
            val tempFile = File(targetDir, "$IMPORT_TEMP_PREFIX${System.currentTimeMillis()}.pending")

            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext ImportResult.Failed(AppStrings.get(R.string.model_err_unreadable))

                inputStream.use { input ->
                    FileOutputStream(tempFile).use { fos ->
                        val buffer = ByteArray(65536) // 64 KB Buffer fuer bessere Performance
                        var bytesWritten = 0L
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                            bytesWritten += bytesRead
                            if (totalSize > 0) {
                                onProgress((bytesWritten.toFloat() / totalSize).coerceAtMost(1f))
                            }
                        }
                    }
                }

                if (isCsv) return@withContext importLabelFile(tempFile)

                // Groesse verifizieren
                if (tempFile.length() < MIN_MODEL_SIZE_BYTES) {
                    Log.e(TAG, "Importierte Datei zu klein: ${tempFile.length()} Bytes")
                    tempFile.delete()
                    return@withContext ImportResult.Failed(AppStrings.get(R.string.model_err_too_small))
                }

                val profile = ModelProfile.bySize(tempFile.length())
                if (profile == null) {
                    Log.i(TAG, "Import: Profil fuer $displayName (${tempFile.length()} Bytes) unbekannt — Rueckfrage")
                    return@withContext ImportResult.NeedsProfile(tempFile.name, (tempFile.length() / 1_000_000).toInt())
                }
                onProgress(1f)
                finishImport(tempFile, profile)
            } catch (e: Exception) {
                Log.e(TAG, "Modell-Import fehlgeschlagen", e)
                tempFile.delete()
                ImportResult.Failed(AppStrings.get(R.string.model_import_err_store, e.javaClass.simpleName))
            }
        }

    /** T103a: Vom User gewaehltes Profil einer importierten Datei zuweisen (nach [ImportResult.NeedsProfile]) */
    suspend fun assignImportedModel(pendingFileName: String, profile: ModelProfile): ImportResult =
        withContext(Dispatchers.IO) {
            val pending = File(File(context.filesDir, MODEL_DIR), pendingFileName)
            if (!pending.exists()) return@withContext ImportResult.Failed("Importierte Datei nicht mehr vorhanden")
            finishImport(pending, profile)
        }

    /** T103a: Abgebrochenen Import verwerfen */
    fun discardPendingImport(pendingFileName: String) {
        File(File(context.filesDir, MODEL_DIR), pendingFileName).delete()
    }

    private suspend fun finishImport(tempFile: File, profile: ModelProfile): ImportResult {
        val targetFile = File(tempFile.parentFile, profile.fileName)
        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.delete()
            return ImportResult.Failed(AppStrings.get(R.string.model_err_not_stored))
        }
        if (!ensureLabels(profile)) {
            // Modell bleibt liegen, wird aber erst aktiv, wenn die Labels da sind
            return ImportResult.Failed(AppStrings.get(R.string.model_err_labels_missing, profile.name))
        }
        appPreferences.activeModelFileName = profile.fileName
        Log.i(TAG, "Modell importiert: ${profile.fileName} als ${profile.id} (${targetFile.length() / 1_000_000} MB)")
        return ImportResult.Success(profile)
    }

    /** Importierte CSV als Label-Datei von 3.0 final ablegen (Kopfzeile pruefen) */
    private fun importLabelFile(tempFile: File): ImportResult {
        val header = tempFile.bufferedReader().use { it.readLine() }?.trimStart('\uFEFF') ?: ""
        val profile = ModelProfile.CATALOG.firstOrNull {
            it.labelFormat == ModelProfile.LabelFormat.FINAL_CSV && it.labelFileName != null
        }
        if (profile == null || !header.startsWith("ix,") || !header.contains("scientific_name")) {
            tempFile.delete()
            return ImportResult.Failed("CSV ist keine Label-Datei von BirdNET 3.0")
        }
        val target = File(tempFile.parentFile, profile.labelFileName!!)
        if (target.exists()) target.delete()
        tempFile.renameTo(target)
        Log.i(TAG, "Label-Datei importiert: ${target.name}")
        reloadTaxonGroups()  // T248
        // Liegt ein passendes Modell schon bereit, wird es aktiv
        listInstalledModels().firstOrNull { it.profile.labelFileName == profile.labelFileName }
            ?.let { appPreferences.activeModelFileName = it.fileName }
        return ImportResult.Success(profile)
    }

    /**
     * T103a: Stellt sicher, dass die Label-Datei eines Profils vorhanden ist (laedt sie bei Bedarf).
     * @return true wenn Labels verfuegbar (Asset oder Datei)
     */
    suspend fun ensureLabels(profile: ModelProfile): Boolean {
        if (profile.labelAssetPath != null) return true
        if (getLabelFile(profile) != null) return true
        val url = profile.labelUrl ?: return false
        val target = File(File(context.filesDir, MODEL_DIR), profile.labelFileName ?: return false)
        val ok = downloadFile(url, target, profile.labelChecksum, profile.labelSizeBytes, minBytes = 1_000L) {}
        if (ok) reloadTaxonGroups()  // T248: neue Label-CSV sofort fuer die Gruppenzuordnung
        return ok
    }

    /** T104: Groesse der angefangenen (unvollstaendigen) Download-Datei eines Modells, 0 = keine */
    fun getPartialBytes(model: ModelProfile): Long {
        val tmp = File(File(context.filesDir, MODEL_DIR), "${model.fileName}.tmp")
        return if (tmp.exists()) tmp.length() else 0L
    }

    /**
     * T104: Laufenden Download abbrechen. Die .tmp-Datei bleibt zum Fortsetzen erhalten,
     * ausser [discardPartial] ist gesetzt ("Verwerfen").
     */
    fun cancelDownload(discardPartial: Boolean = false, model: ModelProfile? = null) {
        val job = synchronized(downloadLock) { activeDownload }
        downloadScope.launch {
            job?.takeIf { it.isActive }?.cancelAndJoin()
            if (discardPartial && model != null) discardPartialDownload(model)
        }
    }

    /** T104/T136: Angefangene Download-Dateien (.tmp) nur des gewaehlten Modells verwerfen — nur wenn kein Download laeuft */
    fun discardPartialDownload(model: ModelProfile) {
        if (activeDownload?.isActive == true) return
        val dir = File(context.filesDir, MODEL_DIR)
        val names = dir.listFiles()?.map { it.name }.orEmpty()
        DownloadResume.tmpFilesFor(names, model.fileName, model.labelFileName)
            .forEach { File(dir, it).delete() }
    }

    /**
     * Modell von URL herunterladen nach filesDir/models/ (T103a: inkl. Label-Datei, Pruefsumme).
     * Nutzt Ktor HttpClient mit OkHttp-Engine (Follow-Redirects explizit aktiviert).
     *
     * @param model Profil mit Download-URL
     * @param onProgress Fortschritt 0.0-1.0
     * @return true bei Erfolg
     */
    suspend fun downloadModel(
        model: ModelProfile,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        // T102: Der Download laeuft im Scope des ModelManager (App-Lebensdauer), nicht im Scope des
        // aufrufenden Screens. Verlaesst der User den Screen, wird nur das Warten abgebrochen.
        // Laeuft bereits ein Download, haengt sich der Aufrufer an diesen an.
        val job = synchronized(downloadLock) {
            activeDownload?.takeIf { it.isActive } ?: downloadScope.async {
                _downloadProgress.value = 0f
                _downloadingFile.value = model.fileName
                try {
                    runDownload(model) { _downloadProgress.value = it }
                } finally {
                    _downloadProgress.value = null
                    _downloadingFile.value = null
                }
            }.also { activeDownload = it }
        }
        return coroutineScope {
            val progressJob = launch { downloadProgress.collect { p -> if (p != null) onProgress(p) } }
            try {
                job.await()
            } catch (e: CancellationException) {
                // T104: Job wurde per Abbrechen-Button beendet (Aufrufer selbst laeuft noch) → false statt Exception
                if (job.isCancelled && currentCoroutineContext().isActive) {
                    lastDownloadError = ERROR_CANCELLED
                    false
                } else {
                    throw e
                }
            } finally {
                progressJob.cancel()
            }
        }
    }

    /**
     * T218: Geomodel (ONNX + Indexdatei) von den PIROL-Release-Assets laden (fortsetzbar, SHA-256).
     * Fehlt das Release oder ist die Verbindung weg, steht der Grund in [lastDownloadError].
     */
    suspend fun downloadGeomodel(onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        lastDownloadError = null
        if (GeoModelFiles.isInstalled(context)) return@withContext true
        val dir = GeoModelFiles.dir(context)
        dir.mkdirs()
        if (dir.usableSpace < 3 * GeoModelFiles.MODEL_BYTES) {
            lastDownloadError = AppStrings.get(R.string.model_err_space, dir.usableSpace / 1_048_576)
            return@withContext false
        }
        val indexOk = GeoModelFiles.indexFile(context).length() == GeoModelFiles.INDEX_BYTES ||
            downloadFile(
                GeoModelFiles.BASE_URL + GeoModelFiles.INDEX_NAME, GeoModelFiles.indexFile(context),
                "sha256:" + GeoModelFiles.INDEX_SHA256, GeoModelFiles.INDEX_BYTES, GeoModelFiles.INDEX_BYTES
            ) { onProgress(it * 0.05f) }
        if (!indexOk) return@withContext false
        val ok = downloadFile(
            GeoModelFiles.BASE_URL + GeoModelFiles.MODEL_NAME, GeoModelFiles.modelFile(context),
            "sha256:" + GeoModelFiles.MODEL_SHA256, GeoModelFiles.MODEL_BYTES, GeoModelFiles.MODEL_BYTES
        ) { onProgress(0.05f + it * 0.95f) }
        if (ok) onProgress(1f)
        ok
    }

    /** T218: Geomodel-Dateien loeschen */
    fun deleteGeomodel() {
        GeoModelFiles.dir(context).deleteRecursively()
    }

    private suspend fun runDownload(
        model: ModelProfile,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        lastDownloadError = null
        val url = model.downloadUrl
        if (url == null) {
            lastDownloadError = AppStrings.get(R.string.model_err_no_download, model.name)
            return@withContext false
        }
        val targetDir = File(context.filesDir, MODEL_DIR)
        targetDir.mkdirs()

        // T121: Speicher vorab pruefen (sonst bricht der Download bei 0 % mit irrefuehrender Meldung ab)
        // T104: bereits heruntergeladener Teil (.tmp) zaehlt nicht mehr
        val neededBytes = DownloadResume.neededFreeBytes(
            model.sizeBytes, model.labelSizeBytes, getPartialBytes(model), 50L * 1_048_576L
        )
        if (targetDir.usableSpace < neededBytes) {
            lastDownloadError = AppStrings.get(R.string.model_err_space_needed, targetDir.usableSpace / 1_048_576, neededBytes / 1_048_576)
            Log.e(TAG, lastDownloadError!!)
            return@withContext false
        }

        // T103a: zuerst die (kleine) Label-Datei, damit das Modell nie ohne passende Labels aktiv wird
        if (!ensureLabels(model)) {
            if (lastDownloadError == null) lastDownloadError = AppStrings.get(R.string.model_err_label_file)
            return@withContext false
        }

        val ok = downloadFile(url, File(targetDir, model.fileName), model.checksum, model.sizeBytes,
            MIN_MODEL_SIZE_BYTES, onProgress)
        if (ok) {
            // Als aktives Modell setzen
            appPreferences.activeModelFileName = model.fileName
            Log.i(TAG, "Modell heruntergeladen: ${model.name}")
            onProgress(1.0f)
        }
        ok
    }

    /**
     * Laedt eine Datei per Streaming in [target] (ueber .tmp), prueft Mindestgroesse und Pruefsumme.
     * T104: Vorhandene .tmp wird per HTTP-Range fortgesetzt; bei voruebergehenden Netzwerkfehlern
     * bis zu [DownloadResume.MAX_ATTEMPTS] Versuche (jeweils fortsetzend). Abbruch und
     * Netzwerkfehler behalten die .tmp, Pruefsummenfehler loeschen sie.
     * Setzt [lastDownloadError] bei Fehlern.
     */
    private suspend fun downloadFile(
        url: String,
        target: File,
        checksum: String?,
        expectedBytes: Long?,
        minBytes: Long,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        val (algo, expectedHex) = parseChecksum(checksum)

        val client = HttpClient(OkHttp) {
            engine {
                config {
                    followRedirects(true)
                    followSslRedirects(true)
                }
            }
        }
        try {
            var attempt = 0
            var discardRetryUsed = false
            while (true) {
                attempt++
                val offset = DownloadResume.startOffset(if (tempFile.exists()) tempFile.length() else 0L, expectedBytes)
                if (offset == 0L && tempFile.exists()) tempFile.delete()
                var digest = algo?.let { MessageDigest.getInstance(it) }
                try {
                    if (DownloadResume.isComplete(offset, expectedBytes)) {
                        // Datei war schon vollstaendig (Abbruch vor Umbenennen) → nur noch pruefen
                        Log.i(TAG, "Teildatei bereits vollstaendig: ${tempFile.name}")
                        digest?.let { hashFile(tempFile, it) }
                    } else {
                        Log.i(TAG, "Starte Download: $url (ab Byte $offset, Versuch $attempt)")
                        // T102: prepareGet + execute streamt den Body. client.get() laedt die ganze Datei
                        // zuerst in den Speicher — kein Fortschritt, hoher Heap-Verbrauch.
                        val action = client.prepareGet(url) {
                            // T121: eindeutige Kennung; Zenodo lehnt Anfragen ohne User-Agent mit HTTP 403 ab
                            headers.append(HttpHeaders.UserAgent, "PIROL-Android (+https://github.com/Nikiphedia/Pirol)")
                            DownloadResume.rangeHeader(offset)?.let { header(HttpHeaders.Range, it) }
                        }.execute { response ->
                            val code = response.status.value
                            val decision = DownloadResume.decide(code, offset)
                            var writeFrom = offset
                            when (decision) {
                                DownloadResume.Action.REJECT -> {
                                    lastDownloadError = if (code == 429) AppStrings.get(R.string.model_err_http_429)
                                        else AppStrings.get(R.string.model_err_http, code)
                                    Log.e(TAG, "Download abgelehnt: HTTP $code fuer $url")
                                    return@execute decision
                                }
                                DownloadResume.Action.DISCARD_AND_RETRY -> return@execute decision
                                DownloadResume.Action.FROM_START -> {
                                    // Kein Range-Support (oder frischer Start) → von vorn
                                    if (offset > 0L) Log.i(TAG, "Server ignoriert Range — Neustart bei 0")
                                    writeFrom = 0L
                                    digest = algo?.let { MessageDigest.getInstance(it) }
                                }
                                DownloadResume.Action.APPEND -> digest?.let { hashFile(tempFile, it) }
                            }
                            val total = DownloadResume.totalBytes(expectedBytes, writeFrom, response.contentLength())
                            val channel = response.bodyAsChannel()
                            FileOutputStream(tempFile, writeFrom > 0L).use { fos ->
                                val buffer = ByteArray(65536) // 64 KB Buffer
                                var totalRead = writeFrom
                                while (!channel.isClosedForRead) {
                                    val read = channel.readAvailable(buffer)
                                    if (read <= 0) break
                                    fos.write(buffer, 0, read)
                                    digest?.update(buffer, 0, read)
                                    totalRead += read
                                    onProgress((totalRead.toFloat() / total).coerceAtMost(0.99f))
                                }
                            }
                            decision
                        }
                        when (action) {
                            DownloadResume.Action.REJECT -> {
                                val code = lastDownloadError?.let { Regex("HTTP (\\d{3})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                                if (code != null && code in 500..599) throw IOException(lastDownloadError) // voruebergehend
                                tempFile.delete()
                                return@withContext false
                            }
                            DownloadResume.Action.DISCARD_AND_RETRY -> {
                                tempFile.delete()
                                if (discardRetryUsed) {
                                    lastDownloadError = AppStrings.get(R.string.model_err_http, 416)
                                    return@withContext false
                                }
                                discardRetryUsed = true
                                attempt-- // zaehlt nicht als Fehlversuch
                                continue
                            }
                            else -> Unit
                        }
                    }

                    // Groesse verifizieren (zu kurz = Verbindung vorzeitig beendet → Wiederholung)
                    val size = tempFile.length()
                    if (size < minBytes || (expectedBytes != null && size < expectedBytes)) {
                        lastDownloadError = AppStrings.get(R.string.model_err_incomplete, size / 1_048_576)
                        Log.e(TAG, "Heruntergeladene Datei zu klein: $size Bytes")
                        throw IOException(lastDownloadError)
                    }

                    // T103a: Pruefsumme verifizieren (bei Abweichung: Datei verwerfen, nicht wiederholen)
                    if (digest != null && expectedHex != null) {
                        val actual = digest!!.digest().joinToString("") { "%02x".format(it) }
                        if (!actual.equals(expectedHex, ignoreCase = true)) {
                            lastDownloadError = AppStrings.get(R.string.model_err_corrupt)
                            Log.e(TAG, "Pruefsumme falsch fuer ${target.name}: $actual, erwartet $expectedHex")
                            tempFile.delete()
                            return@withContext false
                        }
                    }

                    // Bestehende Datei mit gleichem Namen ersetzen
                    if (target.exists()) target.delete()
                    tempFile.renameTo(target)
                    Log.i(TAG, "Download fertig: ${target.name} (${target.length() / 1_000_000} MB)")
                    return@withContext true
                } catch (e: CancellationException) {
                    // T104: Abbruch — .tmp bleibt zum Fortsetzen
                    Log.i(TAG, "Download abgebrochen: ${tempFile.name} bei ${tempFile.length()} Bytes")
                    throw e
                } catch (e: IOException) {
                    lastDownloadError = "${e.javaClass.simpleName}: ${e.message?.take(120) ?: "ohne Meldung"}"
                    Log.w(TAG, "Download-Versuch $attempt fehlgeschlagen: $url", e)
                    // .tmp bleibt fuer erneuten Versuch bzw. manuelles Fortsetzen
                    if (attempt >= DownloadResume.MAX_ATTEMPTS) return@withContext false
                    delay(DownloadResume.backoffMillis(attempt))
                }
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastDownloadError = "${e.javaClass.simpleName}: ${e.message?.take(120) ?: "ohne Meldung"}"
            Log.e(TAG, "Download fehlgeschlagen: $url", e)
            tempFile.delete()
            false
        } finally {
            client.close()
        }
    }

    /** Bestehende Datei in den Digest einlesen (fuer Pruefsumme nach Fortsetzen) */
    private fun hashFile(file: File, digest: MessageDigest) {
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
    }

    /** "sha256:<hex>" / "md5:<hex>" → (Algorithmus fuer MessageDigest, Hex) */
    private fun parseChecksum(checksum: String?): Pair<String?, String?> {
        val parts = checksum?.split(":", limit = 2) ?: return null to null
        if (parts.size != 2) return null to null
        val algo = when (parts[0].lowercase()) {
            "sha256" -> "SHA-256"
            "md5" -> "MD5"
            else -> return null to null
        }
        return algo to parts[1]
    }

    // T239: Modelldateien, die gerade von Zweitmodell (Aufnahme) oder Rescan benutzt werden; nicht loeschbar
    private val inUse = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun markInUse(fileName: String) { inUse.merge(fileName, 1, Int::plus) }

    fun unmarkInUse(fileName: String) { inUse.compute(fileName) { _, n -> if (n == null || n <= 1) null else n - 1 } }

    fun isInUse(fileName: String): Boolean = inUse.containsKey(fileName)

    /** Installiertes Modell loeschen (T151a: bei importierten Modellen inkl. Profil-JSON und Labels) */
    fun deleteModel(fileName: String): Boolean {
        val dir = File(context.filesDir, MODEL_DIR)
        val file = File(dir, fileName)
        if (!file.exists()) return false

        // Aktives Modell nicht loeschen
        if (fileName == appPreferences.activeModelFileName) {
            Log.w(TAG, "Kann aktives Modell nicht loeschen: $fileName")
            return false
        }
        // T239: Zweitmodell der laufenden Aufnahme bzw. Modell eines laufenden Rescans
        if (isInUse(fileName)) {
            Log.w(TAG, "Kann Modell in Benutzung nicht loeschen: $fileName")
            return false
        }

        val deleted = file.delete()
        Log.i(TAG, "Modell geloescht: $fileName = $deleted")
        appPreferences.secondaryModelFileName = ModelRoles.secondaryAfterDelete(fileName, appPreferences.secondaryModelFileName)
        // T223: Katalog-Modell: eigene Label-Datei mitloeschen (Embedding-DB bleibt: Nutzerdaten, eigene Entscheidung)
        ModelProfile.all.firstOrNull { it.fileName == fileName && it.source != ModelProfile.Source.IMPORTED }?.let { p ->
            val stillUsed = ModelProfile.all.filter { it.id != p.id }.flatMap { labelFilesOf(it) }.toSet()
            p.labelFileName?.takeIf { it !in stillUsed }?.let { File(dir, it).delete() }
        }
        ModelProfile.imported.firstOrNull { it.fileName == fileName }?.let { p ->
            val ownLabels = labelFilesOf(p)
            File(dir, p.id + ModelProfileJson.FILE_SUFFIX).delete()
            // Label-Dateien nur loeschen, wenn kein anderes Profil sie nutzt
            val stillUsed = ModelProfile.all.filter { it.id != p.id }.flatMap { labelFilesOf(it) }.toSet()
            ownLabels.filter { it !in stillUsed }.forEach { File(dir, it).delete() }
            reloadImportedProfiles()
        }
        return deleted
    }

    // --- T151a: Import ueber Profil-JSON pirol-model/1 ---

    /** Katalog-Modelle muessen gross sein (Schutz vor abgebrochenen Dateien), importierte nicht */
    private fun isUsableModelFile(file: File): Boolean =
        file.extension == "onnx" &&
            (file.length() >= MIN_MODEL_SIZE_BYTES || ModelProfile.imported.any { it.fileName == file.name })

    /** Label-Dateien eines Profils (importiert: alle aus dem JSON, inkl. aux) */
    private fun labelFilesOf(p: ModelProfile): List<String> {
        if (p.source != ModelProfile.Source.IMPORTED) return listOfNotNull(p.labelFileName)
        val jsonFile = File(File(context.filesDir, MODEL_DIR), p.id + ModelProfileJson.FILE_SUFFIX)
        val fromJson = runCatching { ModelProfileJson.parse(jsonFile.readText()).labels.values.mapNotNull { it.file } }
            .getOrDefault(emptyList())
        return (fromJson + listOfNotNull(p.labelFileName)).distinct()
    }

    /**
     * T151a: Liest alle `*.pirol.json` in filesDir/models/ ein und setzt [ModelProfile.imported].
     * Profile ohne Modell- oder Label-Datei werden uebersprungen (Log).
     */
    fun reloadImportedProfiles() {
        val dir = File(context.filesDir, MODEL_DIR)
        val files = dir.listFiles { f -> f.name.endsWith(ModelProfileJson.FILE_SUFFIX) }.orEmpty()
        val profiles = files.mapNotNull { f ->
            try {
                val p = ModelProfileJson.parse(f.readText()).profile
                val labelFile = File(dir, p.labelFileName ?: "")
                when {
                    !File(dir, p.fileName).exists() || !labelFile.exists() -> {
                        Log.w(TAG, "Profil ${f.name}: Modell- oder Label-Datei fehlt — uebersprungen")
                        null
                    }
                    ModelProfile.CATALOG.any { it.id == p.id || it.fileName == p.fileName } -> {
                        Log.w(TAG, "Profil ${f.name}: kollidiert mit eingebautem Profil — uebersprungen")
                        null
                    }
                    else -> p.copy(speciesCount = countLabelRows(labelFile, p.genericLabels?.header ?: true))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Profil ${f.name} unlesbar: ${e.message}")
                null
            }
        }
        ModelProfile.imported = profiles
        if (profiles.isNotEmpty()) Log.i(TAG, "Importierte Profile: ${profiles.joinToString { it.id }}")
    }

    private fun countLabelRows(file: File, header: Boolean): Int =
        file.bufferedReader().useLines { lines -> lines.count { it.isNotBlank() } } - if (header) 1 else 0

    private fun importError(code: ModelProfileJson.ErrorCode, detail: String): ImportResult.Failed {
        val res = when (code) {
            ModelProfileJson.ErrorCode.SCHEMA -> R.string.model_import_err_schema
            ModelProfileJson.ErrorCode.RUNTIME -> R.string.model_import_err_runtime
            ModelProfileJson.ErrorCode.MISSING_FILE -> R.string.model_import_err_missing_file
            ModelProfileJson.ErrorCode.CHECKSUM -> R.string.model_import_err_checksum
            ModelProfileJson.ErrorCode.LABELS -> R.string.model_import_err_labels
            ModelProfileJson.ErrorCode.ORT_OPEN -> R.string.model_import_err_ort
            ModelProfileJson.ErrorCode.IO_MISMATCH -> R.string.model_import_err_io
            ModelProfileJson.ErrorCode.ID_CONFLICT -> R.string.model_import_err_conflict
        }
        Log.w(TAG, "Import abgelehnt: $code $detail")
        return ImportResult.Failed(AppStrings.get(res, detail))
    }

    /**
     * T151a: Importiert mehrere per SAF gewaehlte Dateien. Ist eine `*.pirol.json` dabei, wird ein Modell
     * nach Profil importiert ([importProfileBundle]); eine einzelne andere Datei laeuft ueber den
     * bisherigen Import ([importFromUri]).
     */
    suspend fun importUris(uris: List<Uri>, onProgress: (Float) -> Unit = {}): ImportResult {
        if (uris.isEmpty()) return importError(ModelProfileJson.ErrorCode.MISSING_FILE, "-")
        val named = withContext(Dispatchers.IO) { uris.map { (queryDisplayName(it) ?: it.lastPathSegment ?: "") to it } }
        val jsons = named.filter { it.first.endsWith(ModelProfileJson.FILE_SUFFIX, ignoreCase = true) }
        return when {
            jsons.size == 1 -> importProfileBundle(jsons.single().second, named - jsons.single(), onProgress)
            jsons.size > 1 -> importError(ModelProfileJson.ErrorCode.SCHEMA, "mehrere *${ModelProfileJson.FILE_SUFFIX}")
            uris.size == 1 -> importFromUri(uris.single(), onProgress)
            else -> importError(ModelProfileJson.ErrorCode.MISSING_FILE, "*${ModelProfileJson.FILE_SUFFIX}")
        }
    }

    /**
     * T151a: Modell nach Profil-JSON importieren. Prueft Schema, SHA-256 von Modell und Labels,
     * Labels, ORT-Session sowie Ein-/Ausgaenge (Probelauf). Erst wenn alles stimmt, landen die Dateien
     * in filesDir/models/ und das Modell wird aktiv.
     *
     * Dateizuordnung ueber den Dateinamen laut JSON; fehlt er, wird eine eindeutige Datei mit gleicher
     * Endung genommen (z.B. die einzige gewaehlte .onnx unter anderem Namen).
     */
    private suspend fun importProfileBundle(
        jsonUri: Uri,
        others: List<Pair<String, Uri>>,
        onProgress: (Float) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
        val staging = File(dir, "$IMPORT_TEMP_PREFIX${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val jsonText = context.contentResolver.openInputStream(jsonUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@withContext importError(ModelProfileJson.ErrorCode.MISSING_FILE, ModelProfileJson.FILE_SUFFIX)
            val parsed = ModelProfileJson.parse(jsonText)
            val profile = parsed.profile
            ModelProfile.CATALOG.firstOrNull { it.id == profile.id || it.fileName == profile.fileName }?.let {
                return@withContext importError(ModelProfileJson.ErrorCode.ID_CONFLICT, it.id)
            }
            // Label-Dateien eingebauter Profile duerfen nur mit identischem Inhalt ueberschrieben werden
            for (l in parsed.labels.values) {
                val clash = ModelProfile.CATALOG.firstOrNull { it.labelFileName == l.file }
                if (clash != null && clash.labelChecksum?.substringAfter(':')?.lowercase() != l.sha256!!.lowercase()) {
                    return@withContext importError(ModelProfileJson.ErrorCode.ID_CONFLICT, l.file!!)
                }
            }

            // Benoetigte Dateien: Modell + alle Label-Dateien (Name → erwartetes SHA-256)
            val needed = linkedMapOf(parsed.modelFile to parsed.modelSha256)
            parsed.labels.values.forEach { needed[it.file!!] = it.sha256!!.lowercase() }
            val byName = others.toMap()
            fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
            val sources = LinkedHashMap<String, Uri>()
            for (name in needed.keys) {
                val direct = byName[name]
                val uri = direct ?: run {
                    val freeFiles = others.filter { ext(it.first) == ext(name) && it.first !in needed }
                    val openNames = needed.keys.count { ext(it) == ext(name) && it !in byName }
                    if (freeFiles.size == 1 && openNames == 1) freeFiles.single().second else null
                } ?: return@withContext importError(ModelProfileJson.ErrorCode.MISSING_FILE, name)
                sources[name] = uri
            }

            // Kopieren in den Staging-Ordner, dabei SHA-256 bilden
            val total = sources.values.sumOf { queryFileSize(it).coerceAtLeast(0L) }.coerceAtLeast(1L)
            var done = 0L
            for ((name, uri) in sources) {
                val digest = MessageDigest.getInstance("SHA-256")
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@withContext importError(ModelProfileJson.ErrorCode.MISSING_FILE, name)
                input.use { inp ->
                    FileOutputStream(File(staging, name)).use { out ->
                        val buf = ByteArray(65536)
                        while (true) {
                            val r = inp.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            digest.update(buf, 0, r)
                            done += r
                            onProgress((done.toFloat() / total).coerceAtMost(0.95f))
                        }
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (hex != needed.getValue(name)) {
                    return@withContext importError(ModelProfileJson.ErrorCode.CHECKSUM, name)
                }
            }

            // Labels lesen (Zeilenzahl fuer die Ausgangspruefung)
            val labelCounts = HashMap<String, Int>()
            for ((key, l) in parsed.labels) {
                labelCounts[key] = try {
                    ModelLabelParser.parseGeneric(File(staging, l.file!!).readText(), ModelProfileJson.genericLabels(l)).size
                } catch (e: Exception) {
                    return@withContext importError(ModelProfileJson.ErrorCode.LABELS, "$key: ${e.message}")
                }
            }

            // ORT: Session oeffnen, Eingangsform und Ausgangsgroessen (Probelauf) pruefen
            val modelPath = File(staging, parsed.modelFile).absolutePath
            val session = try {
                OrtEnvironment.getEnvironment().createSession(modelPath, OrtSession.SessionOptions())
            } catch (e: Exception) {
                return@withContext importError(ModelProfileJson.ErrorCode.ORT_OPEN, e.message?.take(200) ?: e.javaClass.simpleName)
            }
            session.use { s ->
                val inputShapes = s.inputNames.associateWith { OrtModelIo.inputShape(s, it) ?: longArrayOf() }
                val inName = parsed.input.name!!
                val outputSizes = if (inName in s.inputNames) {
                    try {
                        OrtModelIo.probeOutputSizes(s, inName, parsed.windowSamples)
                    } catch (e: Exception) {
                        return@withContext importError(ModelProfileJson.ErrorCode.IO_MISMATCH, "Probelauf: ${e.message?.take(200)}")
                    }
                } else emptyMap()
                ModelProfileJson.checkIo(parsed, inputShapes, outputSizes, labelCounts)
                if (profile.auxOutputNames.isNotEmpty()) {
                    // T151: aux-Ausgaenge werden geprueft, aber noch nicht ausgewertet
                    Log.i(TAG, "aux-Ausgaenge ${profile.auxOutputNames} geprueft, nicht ausgewertet")
                }
            }

            // Ablegen (Modell + Labels + JSON) und aktivieren
            for (name in needed.keys) {
                val target = File(dir, name)
                if (target.exists()) target.delete()
                if (!File(staging, name).renameTo(target)) {
                    return@withContext ImportResult.Failed(AppStrings.get(R.string.model_import_err_store, name))
                }
            }
            File(dir, profile.id + ModelProfileJson.FILE_SUFFIX).writeText(jsonText)
            reloadImportedProfiles()
            reloadTaxonGroups()  // T248: Label-CSV des Profils zaehlt sofort fuer die Gruppenzuordnung
            val installed = ModelProfile.byId(profile.id) ?: profile
            appPreferences.activeModelFileName = installed.fileName
            onProgress(1f)
            Log.i(TAG, "Modell importiert (Profil-JSON): ${installed.id}, ${installed.sampleRate} Hz, " +
                    "${installed.windowSec} s, ${installed.speciesCount} Klassen")
            ImportResult.Success(installed)
        } catch (e: ModelProfileJson.ProfileError) {
            importError(e.code, e.detail)
        } catch (e: Exception) {
            Log.e(TAG, "Profil-Import fehlgeschlagen", e)
            ImportResult.Failed(AppStrings.get(R.string.model_import_err_store, e.javaClass.simpleName))
        } finally {
            staging.deleteRecursively()
        }
    }

    // --- Private Hilfsfunktionen ---

    /** T248: Artgruppen aus den Label-CSVs neu einlesen (Fehler nie nach aussen) */
    private fun reloadTaxonGroups() {
        runCatching { TaxonGroups.reload(context) }.onFailure { Log.w(TAG, "Artgruppen nicht neu geladen", it) }
    }

    /** Dateiname aus ContentResolver lesen */
    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Dateigroesse aus ContentResolver lesen (statt inputStream.available()) */
    private fun queryFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
                } else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
