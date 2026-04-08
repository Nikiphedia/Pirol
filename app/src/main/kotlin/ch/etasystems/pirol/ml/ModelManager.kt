package ch.etasystems.pirol.ml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import ch.etasystems.pirol.data.AppPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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
        private const val MIN_MODEL_SIZE_BYTES = 100_000_000L // 100 MB Minimum
        private const val LEGACY_MODEL_FILE = "birdnet_v3.onnx"

        /** Verfuegbare Modelle zum Download von Zenodo */
        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                id = "v3_fp16",
                name = "BirdNET V3.0 FP16 (empfohlen)",
                url = "https://zenodo.org/records/18247420/files/BirdNET%2B_V3.0-preview3_Global_11K_FP16.onnx",
                fileName = "birdnet_v3_fp16.onnx",
                expectedSizeMB = 259,
                speciesCount = 11560
            ),
            ModelInfo(
                id = "v3_fp32",
                name = "BirdNET V3.0 FP32 (volle Praezision)",
                url = "https://zenodo.org/records/18247420/files/BirdNET%2B_V3.0-preview3_Global_11K_FP32.onnx",
                fileName = "birdnet_v3_fp32.onnx",
                expectedSizeMB = 516,
                speciesCount = 11560
            )
        )
    }

    /** Modell-Metadaten fuer Download-Auswahl */
    data class ModelInfo(
        val id: String,
        val name: String,
        val url: String,
        val fileName: String,
        val expectedSizeMB: Int,
        val speciesCount: Int
    )

    /** Installiertes Modell fuer UI-Anzeige */
    data class InstalledModel(
        val fileName: String,
        val name: String,
        val sizeMB: Int,
        val isActive: Boolean
    )

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

        // 2. Legacy-Dateiname als Fallback (Backward-Compat)
        if (activeFileName != LEGACY_MODEL_FILE) {
            val legacyFile = File(modelsDir, LEGACY_MODEL_FILE)
            if (legacyFile.exists() && legacyFile.length() >= MIN_MODEL_SIZE_BYTES) {
                return legacyFile
            }
        }

        // 3. Irgendein installiertes Modell als Fallback
        val anyModel = modelsDir.listFiles()
            ?.filter { it.extension == "onnx" && it.length() >= MIN_MODEL_SIZE_BYTES }
            ?.firstOrNull()
        if (anyModel != null) {
            appPreferences.activeModelFileName = anyModel.name
            return anyModel
        }

        return null
    }

    /** Alle installierten Modelle auflisten */
    fun listInstalledModels(): List<InstalledModel> {
        val modelsDir = File(context.filesDir, MODEL_DIR)
        if (!modelsDir.exists()) return emptyList()

        val activeFileName = appPreferences.activeModelFileName

        return modelsDir.listFiles()
            ?.filter { it.extension == "onnx" && it.length() >= MIN_MODEL_SIZE_BYTES }
            ?.map { file ->
                val knownModel = AVAILABLE_MODELS.find { it.fileName == file.name }
                InstalledModel(
                    fileName = file.name,
                    name = knownModel?.name ?: file.nameWithoutExtension,
                    sizeMB = (file.length() / 1_000_000).toInt(),
                    isActive = file.name == activeFileName
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
     * Importiert Modell-Datei via SAF URI.
     * Liest Dateigrösse ueber ContentResolver (nicht available()).
     *
     * @param uri SAF URI zur ONNX-Datei
     * @param onProgress Fortschritt 0.0-1.0
     * @return true bei Erfolg
     */
    suspend fun importFromUri(uri: Uri, onProgress: (Float) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            val targetDir = File(context.filesDir, MODEL_DIR)
            targetDir.mkdirs()

            // Dateiname und Groesse aus ContentResolver lesen
            val displayName = queryDisplayName(uri) ?: "imported_model.onnx"
            val totalSize = queryFileSize(uri)

            // Ziel-Dateiname: Original-Name behalten wenn .onnx, sonst generieren
            val targetFileName = if (displayName.endsWith(".onnx")) {
                displayName
            } else {
                "imported_${System.currentTimeMillis()}.onnx"
            }
            val targetFile = File(targetDir, targetFileName)

            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext false

                FileOutputStream(targetFile).use { fos ->
                    val buffer = ByteArray(65536) // 64 KB Buffer fuer bessere Performance
                    var bytesWritten = 0L
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                        bytesWritten += bytesRead
                        if (totalSize > 0) {
                            onProgress((bytesWritten.toFloat() / totalSize).coerceAtMost(1f))
                        }
                    }
                }
                inputStream.close()

                // Groesse verifizieren
                if (targetFile.length() < MIN_MODEL_SIZE_BYTES) {
                    Log.e(TAG, "Importierte Datei zu klein: ${targetFile.length()} Bytes")
                    targetFile.delete()
                    return@withContext false
                }

                // Als aktives Modell setzen
                appPreferences.activeModelFileName = targetFileName
                Log.i(TAG, "Modell importiert: $targetFileName (${targetFile.length() / 1_000_000} MB)")
                onProgress(1f)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Modell-Import fehlgeschlagen", e)
                targetFile.delete()
                false
            }
        }

    /**
     * Modell von URL herunterladen nach filesDir/models/.
     * Nutzt Ktor HttpClient mit OkHttp-Engine (Follow-Redirects explizit aktiviert).
     *
     * @param model ModelInfo mit Download-URL
     * @param onProgress Fortschritt 0.0-1.0
     * @return true bei Erfolg
     */
    suspend fun downloadModel(
        model: ModelInfo,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, MODEL_DIR)
        targetDir.mkdirs()
        val targetFile = File(targetDir, model.fileName)
        val tempFile = File(targetDir, "${model.fileName}.tmp")

        val client = HttpClient(OkHttp) {
            engine {
                config {
                    followRedirects(true)
                    followSslRedirects(true)
                }
            }
        }
        try {
            Log.i(TAG, "Starte Download: ${model.name} von ${model.url}")
            val response = client.get(model.url)
            val contentLength = response.contentLength() ?: (model.expectedSizeMB * 1_000_000L)
            val channel = response.bodyAsChannel()

            FileOutputStream(tempFile).use { fos ->
                val buffer = ByteArray(65536) // 64 KB Buffer
                var totalRead = 0L
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read <= 0) break
                    fos.write(buffer, 0, read)
                    totalRead += read
                    onProgress((totalRead.toFloat() / contentLength).coerceAtMost(0.99f))
                }
            }

            // Groesse verifizieren
            if (tempFile.length() < MIN_MODEL_SIZE_BYTES) {
                Log.e(TAG, "Heruntergeladene Datei zu klein: ${tempFile.length()} Bytes")
                tempFile.delete()
                return@withContext false
            }

            // Bestehende Datei mit gleichem Namen ersetzen
            if (targetFile.exists()) {
                targetFile.delete()
            }
            tempFile.renameTo(targetFile)

            // Als aktives Modell setzen
            appPreferences.activeModelFileName = model.fileName
            Log.i(TAG, "Modell heruntergeladen: ${model.name} (${targetFile.length() / 1_000_000} MB)")
            onProgress(1.0f)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download fehlgeschlagen: ${model.name}", e)
            tempFile.delete()
            false
        } finally {
            client.close()
        }
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
