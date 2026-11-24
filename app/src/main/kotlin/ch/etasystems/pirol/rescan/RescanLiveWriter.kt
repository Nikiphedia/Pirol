package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.data.repository.DetectionUpdateLog
import ch.etasystems.pirol.data.repository.DetectionUpdateQueue
import ch.etasystems.pirol.data.repository.SessionFiles
import ch.etasystems.pirol.data.repository.SessionJson
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationEvent
import kotlinx.serialization.encodeToString

/**
 * T260: Schreibt eine Rescan-Variante waehrend des Scans, nach demselben Muster wie die Live-Session (T236/T251):
 * neue Detektionen werden an `detections.rescan-<id>.jsonl` angehaengt, Updates bereits geschriebener
 * Detektionen (Cluster-Merge, Zweitmodell) append-only an die Zusatzdatei der Variante
 * ([RescanFiles.updates]), CLUSTER_MERGED an `verifications.rescan-<id>.jsonl`. Gesammelt wird im Speicher,
 * geschrieben gebuendelt je [flushLocked] (Engine: alle 10 s wie live, und am Ende).
 *
 * Nutzer-Aenderungen waehrend des Scans: Ein Update des Rescans wird beim Flush auf den AKTUELLEN Stand
 * der Detektion aus der Datei angewendet ([RescanLiveMerge.applyRunnerUpdate]), nie als ganze Kopie des
 * Rescans geschrieben. Verifikationen (Events) liegen ohnehin in der Verifikationsdatei.
 *
 * Reihenfolge je Flush (jeder Schritt ist fuer sich ein Append; ein Kill dazwischen verliert nur den Rest):
 * 1. neue Detektionen (mit der neuesten vorgemerkten Fassung, [DetectionUpdateQueue.takeForAppend]-Regel)
 * 2. Updates geschriebener Detektionen
 * 3. Merge-Events (verweisen auf Anker, die nach 1. sicher in der Datei stehen)
 *
 * Nicht thread-sicher fuer gleichzeitige Aufrufe: der Runner meldet und flusht sequenziell; [flushLocked]
 * laeuft unter dem fileMutex des SessionManagers (gegen Nutzer-Aenderungen derselben Dateien).
 */
class RescanLiveWriter(val id: String) : RescanRunner.Sink {

    /** Neue Detektionen, noch nicht angehaengt (Reihenfolge des Entstehens) */
    private val pendingNew = ArrayList<DetectionResult>()
    /** Neueste Fassung je ID (fuer noch nicht angehaengte wie fuer geschriebene) */
    private val queue = DetectionUpdateQueue()
    /** IDs mit Zeile in der Detektionsdatei der Variante */
    private val appended = HashSet<String>()
    private val pendingMerges = ArrayList<VerificationEvent>()

    /** Angehaengte Detektionen (ohne manuelle Eintraege des Nutzers) */
    val detectionCount: Int get() = appended.size

    override fun onNew(detection: DetectionResult) { pendingNew += detection }
    override fun onUpdate(detection: DetectionResult) = queue.put(detection)
    override fun onMerge(event: VerificationEvent) { pendingMerges += event }

    fun hasPending(): Boolean = pendingNew.isNotEmpty() || !queue.isEmpty() || pendingMerges.isNotEmpty()

    /**
     * Gesammeltes schreiben (Aufrufer haelt den fileMutex). Wirft ein Append, bleibt der noch nicht
     * geschriebene Teil fuer den naechsten Versuch stehen.
     * @return Anzahl geschriebener Zeilen
     */
    fun flushLocked(files: SessionFiles): Int {
        var lines = 0
        val detName = RescanFiles.detections(id)
        // 1. Neue Detektionen; ein vorgemerktes Update ersetzt die alte Fassung direkt in der Zeile
        if (pendingNew.isNotEmpty()) {
            val batch = queue.snapshot()
            val out = pendingNew.map { batch[it.id] ?: it }
            files.append(detName, out.joinToString("") { SessionJson.compact.encodeToString(it) + "\n" })
            val ids = pendingNew.mapTo(HashSet()) { it.id }
            queue.markWritten(batch, ids)
            appended += ids
            pendingNew.clear()
            lines += out.size
        }
        // 2. Updates geschriebener Detektionen auf den aktuellen Stand (Nutzerfelder bleiben)
        val updates = queue.snapshot().filterKeys { it in appended }
        if (updates.isNotEmpty()) {
            val text = DetectionUpdateLog.mergedText(files, detName)
            val current = text?.let { DetectionUpdateLog.currentOf(it, updates.keys) }.orEmpty()
            // Ohne aktuelle Zeile (unlesbar/entfernt) nichts schreiben: keine Wiederbelebung
            val out = updates.values.mapNotNull { r -> current[r.id]?.let { RescanLiveMerge.applyRunnerUpdate(it, r) } }
            if (out.isNotEmpty()) {
                files.append(RescanFiles.updates(id), out.joinToString("") { SessionJson.compact.encodeToString(it) + "\n" })
                lines += out.size
            }
            queue.markWritten(updates, updates.keys)
        }
        // 3. Merge-Events
        if (pendingMerges.isNotEmpty()) {
            files.append(
                RescanFiles.verifications(id),
                pendingMerges.joinToString("") { SessionJson.compact.encodeToString(it) + "\n" }
            )
            lines += pendingMerges.size
            pendingMerges.clear()
        }
        return lines
    }
}
