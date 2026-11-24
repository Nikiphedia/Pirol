package ch.etasystems.pirol.data.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Orchestriert Session-Uploads via WorkManager.
 * Stellt Upload-Status als StateFlow bereit.
 */
class UploadManager(private val context: Context) {

    companion object {
        private const val TAG = "UploadManager"
    }

    val status: StateFlow<UploadStatus> = UploadStatusHolder.status

    /** Ob Uploads nur bei WLAN erlaubt sind (Standard: true) */
    var wifiOnly: Boolean = true

    /** Ob nach Session-Ende automatisch hochgeladen wird */
    var autoUpload: Boolean = false

    /**
     * Session-Upload in WorkManager einreihen.
     *
     * @param sessionDir Pfad zum Session-Ordner
     * @param manual T263: vom Nutzer ausgeloest (Auto-Upload = false): nur dann fragt ein Modell ohne Mensch-Klasse nach
     * @param maskChoice T263: Entscheid aus dem Dialog ([ch.etasystems.pirol.data.export.HumanExportMask.Choice.encode])
     */
    fun enqueue(sessionDir: File, manual: Boolean = false, maskChoice: String? = null) {
        val sessionId = sessionDir.name
        UploadStatusHolder.set(UploadStatus.InProgress(sessionId, 0f))

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SessionUploadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    SessionUploadWorker.KEY_SESSION_DIR to sessionDir.absolutePath,
                    SessionUploadWorker.KEY_MANUAL to manual,
                    SessionUploadWorker.KEY_MASK_CHOICE to (maskChoice ?: "")
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("session_upload")
            .addTag(sessionId)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("upload_$sessionId", ExistingWorkPolicy.REPLACE, workRequest)
        Log.i(TAG, "Upload eingereiht: $sessionId (wifiOnly=$wifiOnly)")
    }
}
