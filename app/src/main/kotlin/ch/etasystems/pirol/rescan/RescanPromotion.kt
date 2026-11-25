package ch.etasystems.pirol.rescan

import ch.etasystems.pirol.data.repository.DetectionUpdateLog
import ch.etasystems.pirol.data.repository.SessionFiles
import ch.etasystems.pirol.data.repository.SessionJson
import ch.etasystems.pirol.data.repository.SessionMetadata
import kotlinx.serialization.encodeToString

/**
 * T273: Erster Scan einer importierten Session wird zum Original (reine Logik ueber [SessionFiles], JVM-testbar).
 * T306: Regel erweitert - jeder vollstaendig fertige Scan wird Original, wenn das Original leer ist (auch bei
 * aelteren Varianten und Live-Sessions ohne Detektionen); bestehende Sessions beim Oeffnen ([promoteNewestIfEmpty]).
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

    /** T306: Toleranz fuer "ganze Aufnahme gescannt" (Float-Sekunden) */
    private const val COMPLETE_TOLERANCE_SEC = 0.5f

    /**
     * T306: Vollstaendig fertiger Scan: DONE und ganze Aufnahme gescannt (scannedSec >= totalSec).
     * Alte Varianten ohne Sekundenangaben (vor T260, fehlender Status = DONE) gelten als vollstaendig.
     * ABORTED, RUNNING und PROMOTING nie.
     */
    fun isComplete(info: RescanInfo): Boolean {
        if (info.status != RescanStatus.DONE) return false
        val scanned = info.scannedSec ?: return true
        val total = info.totalSec ?: return true
        return scanned >= total - COMPLETE_TOLERANCE_SEC
    }

    /**
     * T306: Original leer = keine Detektionen und keine Verifikationen im Original. Updates ohne Zeile fallen beim
     * Zusammenfuehren weg ([DetectionUpdateLog.applyTo]), also ist leeres detections.jsonl = leerer Gesamtstand.
     */
    fun isOriginalEmpty(originalDetections: String?, originalVerifications: String?): Boolean =
        originalDetections.isNullOrBlank() && originalVerifications.isNullOrBlank()

    /**
     * Wird die Variante [info] zum Original? T306 (ersetzt T273-Regel "importiert + keine andere fertige Variante"):
     * Original leer und Variante vollstaendig fertig - unabhaengig von aelteren Varianten und davon, ob die
     * Session importiert ist. Aeltere Varianten bleiben Varianten (nach der Uebernahme ist das Original belegt).
     */
    fun shouldPromote(info: RescanInfo, originalDetections: String?, originalVerifications: String?): Boolean =
        isComplete(info) && isOriginalEmpty(originalDetections, originalVerifications)

    /** T306: Beim Oeffnen zu uebernehmende Variante: die neueste vollstaendig fertige ([list] aelteste zuerst). */
    fun newestComplete(list: List<RescanInfo>, runningId: String?): RescanInfo? =
        list.lastOrNull { it.id != runningId && isComplete(it) }

    /** T306: Variante [info] uebernehmen, falls [shouldPromote] (Aufrufer haelt den fileMutex). */
    fun promoteIfEmpty(files: SessionFiles, info: RescanInfo): Boolean =
        shouldPromote(info, files.readText(RescanFiles.DETECTIONS), files.readText(RescanFiles.VERIFICATIONS)) &&
            promote(files, info)

    /**
     * T306: Bestehende Session mit leerem Original (z.B. Scans vor T306): neueste vollstaendig fertige Variante
     * uebernehmen. Nichts tun, solange eine Uebernahme liegengeblieben ist ([PROMOTING], erst wiederherstellen)
     * oder eine Variante noch RUNNING ist (laufender Scan entscheidet selbst bei seinem Ende).
     * Aufrufer haelt den fileMutex. Idempotent: danach ist das Original belegt.
     * @return uebernommene Variante oder null
     */
    fun promoteNewestIfEmpty(files: SessionFiles, runningId: String?): RescanInfo? {
        val list = RescanStore.list(files)
        if (list.any { it.status == PROMOTING || it.status == RescanStatus.RUNNING }) return null
        if (!isOriginalEmpty(files.readText(RescanFiles.DETECTIONS), files.readText(RescanFiles.VERIFICATIONS))) return null
        val pick = newestComplete(list, runningId) ?: return null
        return pick.takeIf { promote(files, it) }
    }

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
