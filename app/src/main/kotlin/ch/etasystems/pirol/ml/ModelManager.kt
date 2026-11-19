package ch.etasystems.pirol.ml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import ch.etasystems.pirol.data.AppPreferences
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

    /** T121: Grund des letzten fehlgeschlagenen Downloads fuer die UI (null = kein Fehler) */
    @Volatile
    var lastDownloadError: String? = null
        private set

    init {
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
        if (activeFile.exists() && activeFile.length() >= MIN_MODEL_SIZE_BYTES) {
            return activeFile
        }

        // 2. Irgendein installiertes Modell als Fallback
        val anyModel = modelsDir.listFiles()
            ?.filter { it.extension == "onnx" && it.length() >= MIN_MODEL_SIZE_BYTES }
            ?.firstOrNull()
        if (anyModel != null) {
            appPreferences.activeModelFileName = anyModel.name
            return anyModel
        }

        return null
    }

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
            ?.filter { it.extension == "onnx" && it.length() >= MIN_MODEL_SIZE_BYTES }
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
                    ?: return@withContext ImportResult.Failed("Datei nicht lesbar")

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
                    return@withContext ImportResult.Failed("Datei zu klein oder ungueltig")
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
                ImportResult.Failed("Import fehlgeschlagen: ${e.javaClass.simpleName}")
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
            return ImportResult.Failed("Datei konnte nicht abgelegt werden")
        }
        if (!ensureLabels(profile)) {
            // Modell bleibt liegen, wird aber erst aktiv, wenn die Labels da sind
            return ImportResult.Failed(
                "Labels fuer ${profile.name} fehlen — Internet pruefen oder Label-CSV ebenfalls importieren"
            )
        }
        appPreferences.activeModelFileName = profile.fileName
        Log.i(TAG, "Modell importiert: ${profile.fileName} als ${profile.id} (${targetFile.length() / 1_000_000} MB)")
        return ImportResult.Success(profile)
    }

    /** Importierte CSV als Label-Datei von 3.0 final ablegen (Kopfzeile pruefen) */
    private fun importLabelFile(tempFile: File): ImportResult {
        val header = tempFile.bufferedReader().use { it.readLine() }?.trimStart('﻿') ?: ""
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
        return downloadFile(url, target, profile.labelChecksum, profile.labelSizeBytes, minBytes = 1_000L) {}
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
                try {
                    runDownload(model) { _downloadProgress.value = it }
                } finally {
                    _downloadProgress.value = null
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

    private suspend fun runDownload(
        model: ModelProfile,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        lastDownloadError = null
        val url = model.downloadUrl
        if (url == null) {
            lastDownloadError = "Kein Download fuer ${model.name} hinterlegt"
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
            lastDownloadError = "Zu wenig Speicher: ${targetDir.usableSpace / 1_048_576} MB frei, " +
                "${neededBytes / 1_048_576} MB noetig"
            Log.e(TAG, lastDownloadError!!)
            return@withContext false
        }

        // T103a: zuerst die (kleine) Label-Datei, damit das Modell nie ohne passende Labels aktiv wird
        if (!ensureLabels(model)) {
            if (lastDownloadError == null) lastDownloadError = "Label-Datei nicht verfuegbar"
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
                                    lastDownloadError = "Server antwortet HTTP $code" +
                                        if (code == 429) " (zu viele Anfragen, spaeter erneut versuchen)" else ""
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
                                val code = lastDownloadError?.removePrefix("Server antwortet HTTP ")?.take(3)?.toIntOrNull()
                                if (code != null && code in 500..599) throw IOException(lastDownloadError) // voruebergehend
                                tempFile.delete()
                                return@withContext false
                            }
                            DownloadResume.Action.DISCARD_AND_RETRY -> {
                                tempFile.delete()
                                if (discardRetryUsed) {
                                    lastDownloadError = "Server antwortet HTTP 416"
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
                        lastDownloadError = "Download unvollstaendig (${size / 1_048_576} MB)"
                        Log.e(TAG, "Heruntergeladene Datei zu klein: $size Bytes")
                        throw IOException(lastDownloadError)
                    }

                    // T103a: Pruefsumme verifizieren (bei Abweichung: Datei verwerfen, nicht wiederholen)
                    if (digest != null && expectedHex != null) {
                        val actual = digest!!.digest().joinToString("") { "%02x".format(it) }
                        if (!actual.equals(expectedHex, ignoreCase = true)) {
                            lastDownloadError = "Datei beschaedigt (Pruefsumme stimmt nicht)"
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

    /** Installiertes Modell loeschen */
    fun deleteModel(fileName: String): Boolean {
        val file = File(File(context.filesDir, MODEL_DIR), fileName)
        if (!file.exists()) return false

        // Aktives Modell nicht loeschen
        if (fileName == appPreferences.activeModelFileName) {
            Log.w(TAG, "Kann aktives Modell nicht loeschen: $fileName")
            return false
        }

        val deleted = file.delete()
        Log.i(TAG, "Modell geloescht: $fileName = $deleted")
        return deleted
    }

    // --- Private Hilfsfunktionen ---

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
