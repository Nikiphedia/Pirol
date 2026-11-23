package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.rescan.RescanFiles
import kotlinx.serialization.encodeToString

/**
 * T251 (N2): Updates bereits geschriebener Detektionen der aktiven Session (Zweitmodell, Cluster-Merge,
 * GPS-Fix, Nutzer-Aktionen) werden append-only in die Zusatzdatei [FILE] geschrieben, statt jedes Mal
 * die ganze detections.jsonl neu zu schreiben. Kosten pro Flush = nur die geaenderten Zeilen (flach).
 *
 * Zusammengefuehrt wird einmal: beim Session-Ende ([consolidate]); nach Kill oder Fehler beim naechsten
 * Oeffnen (Analyse-Start) bzw. vor dem naechsten Schreiben der Session. Lesen zeigt immer den
 * zusammengefuehrten Stand ([mergedText]), ohne zu schreiben. Danach hat detections.jsonl dasselbe
 * Format wie bisher (eine Zeile je Detektion, [SessionJson.compact]) — AMSEL-kompatibel.
 *
 * Reihenfolge Append/Update wie T236: Updates fuer noch nicht angehaengte Detektionen bleiben stehen
 * und gehen beim Anhaengen direkt in die Zeile ein ([takeForAppend]).
 * Thread-Sicherheit: [put]/[isEmpty]/[size] jederzeit; alles andere unter dem fileMutex des Aufrufers.
 */
class DetectionUpdateLog(private val queue: DetectionUpdateQueue = DetectionUpdateQueue()) {

    /** IDs mit Zeile in detections.jsonl (aktive Session) */
    private val appended = HashSet<String>()

    fun put(updated: DetectionResult) = queue.put(updated)
    fun isEmpty(): Boolean = queue.isEmpty()
    fun size(): Int = queue.size()

    /** Neue Session: Warteschlange und bekannte IDs verwerfen; liefert die Zahl verworfener Updates. */
    fun reset(): Int {
        appended.clear()
        return queue.clear()
    }

    /** Beim Anhaengen: neuere vorgemerkte Fassung verwenden; die IDs gelten danach als geschrieben. */
    fun takeForAppend(detections: List<DetectionResult>): List<DetectionResult> =
        queue.takeForAppend(detections).also { list -> list.forEach { appended += it.id } }

    /** Von aussen angehaengte Zeile (Analyse: manuelle Detektion in der aktiven Session). */
    fun noteAppendedLine(line: String) {
        line.lineSequence().filter { it.isNotBlank() }.forEach { l ->
            SessionFilesLogic.detectionIdOf(l)?.let { appended += it }
        }
    }

    /** Vorgemerkte Updates mit Zeile (fuer das Zusammenfuehren, wenn die Zusatzdatei nicht beschreibbar war). */
    fun pendingWithLine(): Map<String, DetectionResult> = queue.snapshot().filterKeys { it in appended }

    /**
     * Vorgemerkte Updates mit vorhandener Zeile in EINEM Block an die Zusatzdatei haengen ([append]).
     * Wirft [append], bleiben die Eintraege fuer den naechsten Versuch stehen.
     * @return Anzahl geschriebener Updates
     */
    fun flush(append: (String) -> Unit): Int {
        if (queue.isEmpty()) return 0
        val batch = pendingWithLine()
        if (batch.isEmpty()) return 0
        append(batch.values.joinToString("") { SessionJson.compact.encodeToString(it) + "\n" })
        queue.markWritten(batch, batch.keys)
        return batch.size
    }

    /** Rueckfall-Rewrite erfolgreich: geschriebene Eintraege entfernen (sofern seither kein neuerer kam). */
    fun markWritten(batch: Map<String, DetectionResult>) = queue.markWritten(batch, batch.keys)

    /** Aktueller Stand einer Detektion: Warteschlange, sonst Zusatzdatei, sonst Hauptdatei; null = unbekannt. */
    fun current(id: String, files: SessionFiles): DetectionResult? {
        queue.snapshot()[id]?.let { return it }
        val text = mergedText(files) ?: return null
        return findLine(text, id)?.let { decode(it) }
    }

    companion object {
        /** Zusatzdatei neben detections.jsonl (nur Original, nie Rescan-Varianten) */
        const val FILE = "detections.updates.jsonl"
        private const val DETECTIONS = RescanFiles.DETECTIONS

        /** Letzte Zeile je ID aus der Zusatzdatei (unlesbare Zeilen werden uebersprungen). */
        fun latestLines(updates: String?): Map<String, String> {
            if (updates.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            updates.lineSequence().filter { it.isNotBlank() }.forEach { l ->
                SessionFilesLogic.detectionIdOf(l)?.let { out[it] = l }
            }
            return out
        }

        /** Hauptinhalt mit den Updates (letzter Stand je ID); Updates ohne Zeile fallen weg. */
        fun applyTo(main: String, updates: Map<String, String>): SessionFilesLogic.ReplaceResult =
            SessionFilesLogic.replaceLinesById(main, updates)

        /** Zusammengefuehrter Inhalt (nur lesen); null = detections.jsonl fehlt. */
        fun mergedText(files: SessionFiles): String? {
            val main = files.readText(DETECTIONS) ?: return null
            val updates = latestLines(files.readText(FILE))
            return if (updates.isEmpty()) main else applyTo(main, updates).text
        }

        /**
         * Zusatzdatei (und [extra] = noch nicht geschriebene Updates) in detections.jsonl uebernehmen
         * (atomar: File temp + Move, SAF Schattenkopie), danach Zusatzdatei loeschen. Aufrufer haelt den
         * fileMutex, nie waehrend die Zusatzdatei offen ist. Idempotent: ein Kill zwischen Schreiben und
         * Loeschen fuehrt beim naechsten Mal zum gleichen Ergebnis.
         * @return true wenn detections.jsonl neu geschrieben wurde
         */
        fun consolidate(files: SessionFiles, extra: Map<String, DetectionResult> = emptyMap()): Boolean {
            val sideText = files.readText(FILE)
            if (sideText == null && extra.isEmpty()) return false
            val main = files.readText(DETECTIONS)
            var written = false
            if (main != null) {
                val updates = latestLines(sideText) + extra.mapValues { SessionJson.compact.encodeToString(it.value) }
                val result = applyTo(main, updates)
                if (result.matchedIds.isNotEmpty() && result.text != main) {
                    files.writeReplacing(DETECTIONS, result.text)
                    written = true
                }
            }
            if (sideText != null) files.delete(FILE)
            return written
        }

        private fun findLine(text: String, id: String): String? =
            text.lineSequence().lastOrNull { it.isNotBlank() && SessionFilesLogic.detectionIdOf(it) == id }

        private fun decode(line: String): DetectionResult? =
            try { SessionJson.lenient.decodeFromString<DetectionResult>(line) } catch (_: Exception) { null }
    }
}
