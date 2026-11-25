package ch.etasystems.pirol.data.sync

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager

/**
 * T304: Fragt ab, ob fuer eine Session ein Upload in WorkManager eingereiht ist (auch nach Prozess-Neustart
 * oder wenn der Status im Speicher ueberschrieben wurde). Blockierend, nur auf IO aufrufen.
 */
fun interface UploadQueueProbe {
    /** true = Upload der Session ist ENQUEUED, RUNNING oder BLOCKED */
    fun hasPendingUpload(sessionName: String): Boolean

    companion object {
        /** Nicht abgeschlossene Zustaende (SUCCEEDED/FAILED/CANCELLED zaehlen nicht) */
        fun isPending(states: Collection<WorkInfo.State>): Boolean =
            states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING || it == WorkInfo.State.BLOCKED }
    }
}

/** WorkManager-Umsetzung: Tag = Session-Ordnername (siehe [UploadManager.enqueue]) */
class WorkManagerUploadQueue(private val context: Context) : UploadQueueProbe {
    override fun hasPendingUpload(sessionName: String): Boolean = try {
        val infos = WorkManager.getInstance(context).getWorkInfosByTag(sessionName).get()
        UploadQueueProbe.isPending(infos.filter { "session_upload" in it.tags }.map { it.state })
    } catch (e: Exception) {
        false  // WorkManager nicht verfuegbar: nur der Status im Speicher zaehlt
    }
}
