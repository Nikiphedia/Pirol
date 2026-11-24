package ch.etasystems.pirol.data.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.SessionMetadata
import kotlinx.serialization.json.Json
import ch.etasystems.pirol.data.repository.DetectionUpdateLog
import ch.etasystems.pirol.data.repository.SessionManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
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
) : CoroutineWorker(context, params), KoinComponent {

    private val sessionManager: SessionManager by inject()

    companion object {
        const val KEY_SESSION_DIR = "session_dir"
        private const val TAG = "SessionUploadWorker"
        /** Maximale Anzahl Versuche inkl. erstem (danach endgueltig fehlgeschlagen) */
        const val MAX_ATTEMPTS = 5
        /** T234: prozessweites Schloss fuer "Zielname waehlen + merken" (parallele Worker) */
        private val nameLock = Any()
        private const val CHOOSE_ATTEMPTS = 5

        /**
         * T250/N5: gemerkte Namen der Session entfernen (nach Erfolg/Aufgabe; die Datei im Ziel haelt den Namen belegt).
         * T257: auch nach "Angaben bearbeiten" (neuer Startzeit-/Bemerkungs-Name beim naechsten Upload).
         */
        fun forgetExportNames(context: Context, sessionId: String) {
            val store = context.getSharedPreferences("pirol_export_names", Context.MODE_PRIVATE)
            synchronized(nameLock) {
                val edit = store.edit()
                store.all.keys.filter { it.startsWith("$sessionId|") }.forEach { edit.remove(it) }
                edit.commit()
            }
        }
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
            // T253: zusammengefuehrte Detektionen (Zusatzdatei uebernommen bzw. bei aktiver Session eingerechnet)
            val detections = try {
                sessionManager.detectionsForExport(sessionDir)
            } catch (e: Exception) {
                Log.w(TAG, "Zusammengefuehrte Detektionen nicht lesbar, Datei wie sie ist", e)
                null
            }
            createZip(sessionDir, zipFile, detections)
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
            forgetNames(metadata.sessionId)  // T250/N5
            record(prefs, "Erfolgreich: ${metadata.sessionId}")
            UploadStatusHolder.set(UploadStatus.Success(metadata.sessionId))
            return Result.success()
        }
        Log.e(TAG, "Export fehlgeschlagen: ${metadata.sessionId}")
        val giveUp = !retryable || runAttemptCount >= MAX_ATTEMPTS - 1
        if (giveUp) {
            forgetNames(metadata.sessionId)  // T250/N5
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
        val suffix = "|${target.name}"
        val base = ExportNames.sessionName(metadata.startedAt, metadata.note, metadata.sessionId)
        // T250/N5: exists() (bei SMB ein Netzzugriff) laeuft ausserhalb des Schlosses; gesperrt wird nur
        // "reservierte Namen lesen" und "waehlen + merken". Hat ein anderer Worker den Namen inzwischen
        // reserviert, wird neu gewaehlt.
        repeat(CHOOSE_ATTEMPTS) {
            val (stored, reserved) = synchronized(nameLock) {
                // Namen anderer Sessions auf demselben Ziel gelten als belegt (auch wenn die Datei noch fehlt)
                store.getString(key, null) to store.all.entries
                    .filter { it.key != key && it.key.endsWith(suffix) }
                    .mapNotNull { it.value as? String }
                    .toSet()
            }
            val name = ExportNames.chooseZipName(stored, base, reserved, target::exists)
            synchronized(nameLock) {
                val takenNow = store.all.entries.any { it.key != key && it.key.endsWith(suffix) && it.value == name }
                if (!takenNow) {
                    store.edit().putString(key, name).commit()
                    return name
                }
            }
        }
        // Dauerkonflikt (praktisch ausgeschlossen): Name mit Session-Kennung, immer eindeutig
        val fallback = base + "_" + ExportNames.sanitizePart(metadata.sessionId, 40) + ".zip"
        synchronized(nameLock) { store.edit().putString(key, fallback).commit() }
        return fallback
    }

    private fun forgetNames(sessionId: String) = forgetExportNames(applicationContext, sessionId)

    private fun record(prefs: AppPreferences, text: String) {
        prefs.lastUploadTimeMs = System.currentTimeMillis()
        prefs.lastUploadResult = text
    }
}

// Packt einen Session-Ordner als ZIP.
// Enthaelt: session.json, detections.jsonl, verifications.jsonl (falls vorhanden), audio/*.wav
// T253: detections.jsonl kommt aus [detectionsText] (zusammengefuehrter Stand), falls gegeben; die Zusatzdatei
// der Detektions-Updates kommt nie ins ZIP.
internal fun createZip(sessionDir: File, zipFile: File, detectionsText: String? = null) {
    ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
        sessionDir.walkTopDown()
            .filter { it.isFile && !ch.etasystems.pirol.data.repository.ShadowReplace.isShadowFile(it.name) }  // T240
            .filter { it.name != DetectionUpdateLog.FILE && it.name != ch.etasystems.pirol.data.repository.SessionInfoEdit.JOURNAL_FILE }  // T257: Auftragsdatei nicht ins ZIP
            .forEach { file ->
                val entryName = file.relativeTo(sessionDir).path.replace('\\', '/')
                zos.putNextEntry(ZipEntry(entryName))
                if (detectionsText != null && entryName == "detections.jsonl") {
                    zos.write(detectionsText.toByteArray(Charsets.UTF_8))
                } else {
                    FileInputStream(file).use { fis ->
                        fis.copyTo(zos, bufferSize = 8192)
                    }
                }
                zos.closeEntry()
            }
    }
}
