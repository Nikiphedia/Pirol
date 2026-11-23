package ch.etasystems.pirol.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.SessionMetadata
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import ch.etasystems.pirol.data.export.ExportNames
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
        /** Maximale Anzahl Versuche inkl. erstem (danach endgueltig fehlgeschlagen) */
        const val MAX_ATTEMPTS = 5
        /** T234: prozessweites Schloss fuer "Zielname waehlen + merken" (parallele Worker) */
        private val nameLock = Any()
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
        val zipFile = File(applicationContext.cacheDir, "upload_${ExportNames.sanitizePart(metadata.sessionId, 80).ifEmpty { "session" }}.zip")
        try {
            createZip(sessionDir, zipFile)
        } catch (e: Exception) {
            Log.e(TAG, "ZIP erstellen fehlgeschlagen", e)
            zipFile.delete()
            return Result.failure()
        }

        // T125: konfiguriertes Ziel (NAS/Ordner); ohne Ziel (manueller Export) nur lokale Kopie
        val prefs = AppPreferences(applicationContext)
        var error: String? = null
        var retryable = true
        var remoteDone = false
        val remoteConfigured = UploadTargetFactory.isConfigured(prefs)
        if (remoteConfigured) {
            try {
                val target = UploadTargetFactory.create(
                    applicationContext, prefs, UploadCredentialStore(applicationContext)
                )!!
                remoteDone = target.upload(zipFile, metadata, chooseName(target, metadata)) { p ->
                    UploadStatusHolder.set(UploadStatus.InProgress(metadata.sessionId, p))
                }
                if (!remoteDone) error = "Upload fehlgeschlagen"
            } catch (e: UploadConfigException) {
                error = UploadConfig.describeError(e)
                retryable = false
            } catch (e: Exception) {
                // Kein Passwort in Logs: nur Klasse und bereinigte Meldung
                Log.e(TAG, "Upload fehlgeschlagen: ${e.javaClass.simpleName}")
                error = UploadConfig.describeError(e)
            }
        }

        // Lokale ZIP-Kopie nach Downloads/PIROL: ohne Ziel immer; mit Ziel nur wenn nicht abgewaehlt (best effort)
        var localOk = true
        if (!remoteConfigured || (remoteDone && !prefs.deleteLocalZipAfterUpload)) {
            localOk = try {
                val local = LocalExportTarget(applicationContext)
                local.upload(zipFile, metadata, chooseName(local, metadata))
            } catch (e: Exception) {
                false
            }
            if (!remoteConfigured && !localOk) error = "Lokaler Export fehlgeschlagen"
        }

        // Temporaere ZIP aufraeumen (Session-Ordner bleibt immer erhalten)
        zipFile.delete()

        val success = error == null
        if (success) {
            Log.i(TAG, "Session exportiert: ${metadata.sessionId}")
            record(prefs, "Erfolgreich: ${metadata.sessionId}")
            UploadStatusHolder.set(UploadStatus.Success(metadata.sessionId))
            return Result.success()
        }
        Log.e(TAG, "Export fehlgeschlagen: ${metadata.sessionId}")
        val giveUp = !retryable || runAttemptCount >= MAX_ATTEMPTS - 1
        if (giveUp) {
            record(prefs, "Fehlgeschlagen: $error")
            UploadStatusHolder.set(UploadStatus.Failed(metadata.sessionId, error ?: "Unbekannter Fehler"))
            return Result.failure()
        }
        // Zwischenstand: Retry mit exponentiellem Backoff (WorkManager)
        record(prefs, "Fehler (Versuch ${runAttemptCount + 1}/$MAX_ATTEMPTS), neuer Versuch: $error")
        return Result.retry()
    }

    /**
     * T233: Zielname pro Session und Ziel einmal festlegen (kollisionsfrei gegen vorhandene Dateien)
     * und merken; Wiederholungen derselben Session ersetzen die eigene Datei.
     */
    private fun chooseName(target: UploadTarget, metadata: SessionMetadata): String {
        val store = applicationContext.getSharedPreferences("pirol_export_names", Context.MODE_PRIVATE)
        val key = "${metadata.sessionId}|${target.name}"
        synchronized(nameLock) {
            // Namen anderer Sessions auf demselben Ziel gelten als belegt (auch wenn die Datei noch fehlt)
            val suffix = "|${target.name}"
            val reserved = store.all.entries
                .filter { it.key != key && it.key.endsWith(suffix) }
                .mapNotNull { it.value as? String }
                .toSet()
            val name = ExportNames.chooseZipName(
                store.getString(key, null),
                ExportNames.sessionName(metadata.startedAt, metadata.note, metadata.sessionId),
                reserved,
                target::exists
            )
            store.edit().putString(key, name).commit()
            return name
        }
    }

    private fun record(prefs: AppPreferences, text: String) {
        prefs.lastUploadTimeMs = System.currentTimeMillis()
        prefs.lastUploadResult = text
    }
}

// Packt einen Session-Ordner als ZIP.
// Enthaelt: session.json, detections.jsonl, verifications.jsonl (falls vorhanden), audio/*.wav
private fun createZip(sessionDir: File, zipFile: File) {
    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
        sessionDir.walkTopDown()
            .filter { it.isFile && !ch.etasystems.pirol.data.repository.ShadowReplace.isShadowFile(it.name) }  // T240
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
