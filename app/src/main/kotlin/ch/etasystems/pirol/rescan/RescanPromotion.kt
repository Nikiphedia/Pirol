package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.data.repository.DetectionUpdateLog
import ch.etasystems.pirol.data.repository.SessionFiles
import ch.etasystems.pirol.data.repository.SessionJson
import ch.etasystems.pirol.data.repository.SessionMetadata
import kotlinx.serialization.encodeToString

/**
 * T273: Erster Scan einer importierten Session wird zum Original (reine Logik ueber [SessionFiles], JVM-testbar).
 *
 * Vorher (T255/T260): Der Scan nach dem Audio-Import landete nur als Variante `detections.rescan-<id>.jsonl`;
 * `detections.jsonl` blieb leer, `recording.selections.txt` fehlte. Jetzt: Hat die importierte Session keine
 * Original-Detektionen und -Verifikationen und noch keine fertige Variante, wird die fertige (DONE) Variante
 * nach dem Scan ins Original uebernommen. Spaetere Rescans bleiben Varianten. Abgebrochene Scans bleiben
 * Varianten (Teilergebnis); der naechste vollstaendige Scan wird dann das Original.
 *
 * Ablauf ([promote], Aufrufer haelt den fileMutex), absturzsicher:
 * 1. Metadaten der Variante auf [PROMOTING] (Marke fuer die Wiederherstellung)
 * 2. Detektionen und Verifikationen ins Original kopieren (je atomar; nur in leere oder schon gleiche Dateien)
 * 3. session.json: Modell, Filter, Schwelle, Detektionszahl
 * 4. Dateien der Variante loeschen, Metadaten zuletzt
 * Nach einem Abbruch steht die Variante mit [PROMOTING] da und wird beim naechsten Oeffnen fertig uebernommen
 * (Schritte 2-4 wiederholbar: kopiert wird nur, solange die Quelle noch da ist). Raven schreibt der Aufrufer.
 */
object RescanPromotion {

    /** Status einer Variante, die gerade ins Original uebernommen wird */
    const val PROMOTING = "PROMOTING"

    /**
     * Wird die Variante [id] zum Original? Importierte Session ([SessionMetadata.importedFrom]), Original ohne
     * Detektionen und Verifikationen, keine andere fertige Variante.
     */
    fun shouldPromote(
        meta: SessionMetadata?,
        id: String,
        originalDetections: String?,
        originalVerifications: String?,
        variants: List<RescanInfo>
    ): Boolean =
        meta?.importedFrom != null &&
            originalDetections.isNullOrBlank() &&
            originalVerifications.isNullOrBlank() &&
            variants.none { it.id != id && it.status == RescanStatus.DONE }

    /** session.json nach der Uebernahme: Einstellungen des Scans wie bei einer Live-Session. */
    fun metadataAfter(meta: SessionMetadata, info: RescanInfo, detectionCount: Int): SessionMetadata = meta.copy(
        modelId = info.modelId ?: meta.modelId,
        regionFilter = info.regionFilter ?: meta.regionFilter,
        confidenceThreshold = info.confidenceThreshold,
        speciesFilter = info.speciesFilter ?: meta.speciesFilter,
        totalDetections = detectionCount
    )

    /** Kopieren erlaubt? Quelle fehlt (schon uebernommen) = ja; Ziel leer oder schon gleich = ja; sonst nein. */
    private fun canCopy(files: SessionFiles, from: String, to: String): Boolean {
        val src = files.readText(from) ?: return true
        val dst = files.readText(to)
        return dst.isNullOrBlank() || dst == src
    }

    private fun copy(files: SessionFiles, from: String, to: String) {
        val src = files.readText(from) ?: return
        if (files.readText(to) != src) files.writeReplacing(to, src)
    }

    /**
     * Variante [info] ins Original uebernehmen (siehe Klassenkommentar).
     * @return true = Variante ist jetzt das Original; false = nicht moeglich (Original inzwischen belegt, oder
     *   session.json fehlt), Variante bleibt unveraendert als DONE stehen
     */
    fun promote(files: SessionFiles, info: RescanInfo, sessionJsonName: String = "session.json"): Boolean {
        val id = info.id
        val meta = files.readText(sessionJsonName)
            ?.let { runCatching { SessionJson.lenient.decodeFromString<SessionMetadata>(it) }.getOrNull() }
            ?: return false
        val detName = RescanFiles.detections(id)
        val verName = RescanFiles.verifications(id)
        if (!canCopy(files, detName, RescanFiles.DETECTIONS) || !canCopy(files, verName, RescanFiles.VERIFICATIONS)) {
            if (info.status == PROMOTING) RescanStore.writeMeta(files, info.copy(status = RescanStatus.DONE))
            return false
        }
        if (info.status != PROMOTING) RescanStore.writeMeta(files, info.copy(status = PROMOTING))
        // Liegengebliebene Updates der Variante zuerst in ihre Detektionsdatei (nur nach Abbruch vorhanden)
        if (files.exists(RescanFiles.updates(id))) DetectionUpdateLog.consolidate(files, detectionsName = detName)
        copy(files, detName, RescanFiles.DETECTIONS)
        copy(files, verName, RescanFiles.VERIFICATIONS)
        val count = files.readText(RescanFiles.DETECTIONS)?.lineSequence()?.count { it.isNotBlank() } ?: info.detectionCount
        files.writeReplacing(sessionJsonName, SessionJson.pretty.encodeToString(metadataAfter(meta, info, count)))
        // Metadaten zuletzt: bis dahin bleibt die Marke PROMOTING fuer die Wiederherstellung
        RescanFiles.filesOf(id).filter { it != RescanFiles.meta(id) }.forEach { files.delete(it) }
        files.delete(RescanFiles.meta(id))
        return true
    }

    /** Liegengebliebene Uebernahmen (Status [PROMOTING]) */
    fun pending(list: List<RescanInfo>): List<RescanInfo> = list.filter { it.status == PROMOTING }
}
