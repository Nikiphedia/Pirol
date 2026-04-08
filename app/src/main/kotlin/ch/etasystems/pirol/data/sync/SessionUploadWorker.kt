package ch.etasystems.pirol.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.etasystems.pirol.data.repository.SessionMetadata
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// WorkManager Worker fuer Session-Upload.
// 1. Liest Session-Ordner
// 2. Packt als ZIP (session.json + detections.jsonl + verifications.jsonl + audio/)
// 3. Ruft UploadTarget.upload() auf
// 4. Raeumt temporaere ZIP auf
class SessionUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_SESSION_DIR = "session_dir"
        private const val TAG = "SessionUploadWorker"
    }

    override suspend fun doWork(): Result {
        val sessionPath = inputData.getString(KEY_SESSION_DIR) ?: return Result.failure()
        val sessionDir = File(sessionPath)
        if (!sessionDir.exists() || !sessionDir.isDirectory) return Result.failure()

        // Session-Metadaten lesen
        val metadataFile = File(sessionDir, "session.json")
        if (!metadataFile.exists()) return Result.failure()
        val metadata = try {
            Json.decodeFromString<SessionMetadata>(metadataFile.readText())
        } catch (e: Exception) {
            Log.e(TAG, "session.json parsen fehlgeschlagen", e)
            return Result.failure()
        }

        // ZIP erstellen (temporaer)
        val zipFile = File(applicationContext.cacheDir, "${metadata.sessionId}.zip")
        try {
            createZip(sessionDir, zipFile)
        } catch (e: Exception) {
            Log.e(TAG, "ZIP erstellen fehlgeschlagen", e)
            zipFile.delete()
            return Result.failure()
        }

        // Upload via LocalExportTarget
        val target = LocalExportTarget(applicationContext)
        val success = try {
            target.upload(zipFile, metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Upload fehlgeschlagen", e)
            false
        }

        // Temporaere ZIP aufraeumen
        zipFile.delete()

        return if (success) {
            Log.i(TAG, "Session exportiert: ${metadata.sessionId}")
            Result.success()
        } else {
            Log.e(TAG, "Export fehlgeschlagen: ${metadata.sessionId}")
            Result.retry()
        }
    }
}

// Packt einen Session-Ordner als ZIP.
// Enthaelt: session.json, detections.jsonl, verifications.jsonl (falls vorhanden), audio/*.wav
private fun createZip(sessionDir: File, zipFile: File) {
    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
        sessionDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val entryName = file.relativeTo(sessionDir).path.replace('\\', '/')
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos, bufferSize = 8192)
                }
                zos.closeEntry()
            }
    }
}
