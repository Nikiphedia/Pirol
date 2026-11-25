package ch.etasystems.pirol.data.export

import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.RecordingSegment
import ch.etasystems.pirol.data.repository.SessionMemo
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ui.analysis.DetectionOccurrences
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Exportiert Detektionen einer Session als Raven Selection Table (.txt).
 *
 * Format: Tab-getrennt, eine Header-Zeile + eine Zeile pro Detektion.
 * Kompatibel mit Cornell Raven, Audacity (Label-Track), Sonic Visualiser.
 *
 * T57-B3: Neue Spalten Begin File (fuer Multi-File-Sessions) und Begin Date Time.
 * T78: Felder per enabledFields-Set selektierbar; Lat/Lon aus DetectionResult.
 * T259: eine Zeile pro Episode (zusammenhaengende Fenster eines Clusters, [RavenEpisodes]); Spalten
 * "Cluster" (Anker-Id) und "Windows" (Fensterzahl) hinter "Corrected Species", vor "Session-Bemerkung".
 * T263: Spalte "Memo" (voiceMemoFile) hinter "Windows", vor "Session-Bemerkung"; auf jeder Episoden-Zeile der Detektion.
 * T264: Session-Memos als eigene Zeilen (Species/Common Name "Memo", Confidence/Status/Cluster leer, Spalte Memo = Pfad).
 */
object RavenExporter {

    const val FIELD_BEGIN_FILE      = "pirol_raven_field_begin_file"
    const val FIELD_BEGIN_TIME      = "pirol_raven_field_begin_time"
    const val FIELD_END_TIME        = "pirol_raven_field_end_time"
    const val FIELD_LOW_FREQ        = "pirol_raven_field_low_freq"
    const val FIELD_HIGH_FREQ       = "pirol_raven_field_high_freq"
    const val FIELD_BEGIN_DATE_TIME = "pirol_raven_field_begin_date_time"
    const val FIELD_SPECIES         = "pirol_raven_field_species"
    const val FIELD_CONFIDENCE      = "pirol_raven_field_confidence"
    const val FIELD_LAT             = "pirol_raven_field_lat"
    const val FIELD_LON             = "pirol_raven_field_lon"
    const val FIELD_SIMULTAN        = "pirol_raven_field_simultan"
    const val FIELD_MOEGLICH        = "pirol_raven_field_moeglich"
    const val FIELD_GLEICH          = "pirol_raven_field_gleich"
    /** T224: Spalten des Zweitmodells (nur wenn mindestens eine Detektion ein Ergebnis hat) */
    const val FIELD_SECONDARY       = "pirol_raven_field_secondary"
    /** T263: Spalte "Memo" (relativer Pfad memos/<id>.wav der Detektion, auf jeder Episoden-Zeile) */
    const val FIELD_MEMO            = "pirol_raven_field_memo"

    /**
     * T249 (M2): Alle waehlbaren Felder in Spaltenreihenfolge. Einzige Liste fuer Auto-Export (Session-Stopp)
     * und manuellen Export; der Feld-Schluessel ist zugleich der Settings-Schluessel (Default an).
     */
    val ALL_FIELDS: List<String> = listOf(
        FIELD_BEGIN_FILE, FIELD_BEGIN_TIME, FIELD_END_TIME, FIELD_LOW_FREQ, FIELD_HIGH_FREQ,
        FIELD_BEGIN_DATE_TIME, FIELD_SPECIES, FIELD_CONFIDENCE, FIELD_LAT, FIELD_LON,
        FIELD_SIMULTAN, FIELD_MOEGLICH, FIELD_GLEICH, FIELD_SECONDARY, FIELD_MEMO
    )

    /** T249 (M2): Gewaehlte Felder; [isOn] fragt je Feld-Schluessel die Einstellung ab. */
    fun enabledFields(isOn: (String) -> Boolean): Set<String> = ALL_FIELDS.filterTo(LinkedHashSet(), isOn)

    /** T249 (M2): Gewaehlte Felder aus den Einstellungen (Auto- und manueller Export). */
    fun enabledFields(p: AppPreferences): Set<String> = enabledFields { key ->
        when (key) {
            FIELD_BEGIN_FILE      -> p.ravenFieldBeginFile
            FIELD_BEGIN_TIME      -> p.ravenFieldBeginTime
            FIELD_END_TIME        -> p.ravenFieldEndTime
            FIELD_LOW_FREQ        -> p.ravenFieldLowFreq
            FIELD_HIGH_FREQ       -> p.ravenFieldHighFreq
            FIELD_BEGIN_DATE_TIME -> p.ravenFieldBeginDateTime
            FIELD_SPECIES         -> p.ravenFieldSpecies
            FIELD_CONFIDENCE      -> p.ravenFieldConfidence
            FIELD_LAT             -> p.ravenFieldLat
            FIELD_LON             -> p.ravenFieldLon
            FIELD_SIMULTAN        -> p.ravenFieldSimultan
            FIELD_MOEGLICH        -> p.ravenFieldMoeglich
            FIELD_GLEICH          -> p.ravenFieldGleich
            FIELD_SECONDARY       -> p.ravenFieldSecondary
            FIELD_MEMO            -> p.ravenFieldMemo
            else                  -> true
        }
    }

    private const val LOW_FREQ_HZ = 150
    private const val HIGH_FREQ_HZ = 12000
    private const val VIEW = "Spectrogram 1"
    private const val CHANNEL = 1

    /**
     * Schreibt die Selection-Table.
     *
     * @param outputFile       Zieldatei (z.B. recording.selections.txt)
     * @param detections       Detektionen mit angewandten Verifikationen
     * @param sessionStartedAt ISO-8601-Timestamp mit Offset (aus session.json)
     * @param recordingSegments WAV-Segmente bei rotierter Session (null = Single-File)
     * @param enabledFields    Set der aktiven Feld-Keys; null = alle Felder aktiv
     * @param linkMap          Simultan/Moeglich/Gleich-Links pro DetectionId (T85)
     * @param sessionNote      T109: Session-Bemerkung; wenn nicht leer, als letzte Spalte "Session-Bemerkung" (gleicher Wert je Zeile)
     * @param merges           T259: absorbierte Fenster je Anker-Id (CLUSTER_MERGED); leer = eine Zeile je Detektion
     * @param sessionMemos     T264: Session-Memos; je eines mit Lage eine Zeile Species "Memo", chronologisch einsortiert
     * @param freqBandHz       T294: Low/High Freq je Zeile; null = Vogel-Standard 150..12000 Hz (siehe [freqBandFor])
     * @return Anzahl geschriebener Zeilen
     */
    fun export(
        outputFile: File,
        detections: List<DetectionResult>,
        sessionStartedAt: String,
        recordingSegments: List<RecordingSegment>? = null,
        enabledFields: Set<String>? = null,
        linkMap: Map<String, SimultaneousLinkSummary> = emptyMap(),
        sessionNote: String? = null,
        merges: Map<String, List<DetectionOccurrences.MergeInfo>> = emptyMap(),
        sessionMemos: List<SessionMemo> = emptyList(),
        freqBandHz: Pair<Int, Int>? = null
    ): Int {
        val lowFreq = (freqBandHz?.first ?: LOW_FREQ_HZ).toString()
        val highFreq = (freqBandHz?.second ?: HIGH_FREQ_HZ).toString()
        val sessionStart = OffsetDateTime.parse(sessionStartedAt)
        fun on(key: String) = enabledFields == null || key in enabledFields
        val noteCell = escapeTsv(sessionNote.orEmpty())
        // T224: ohne Zweitmodell-Ergebnis bleibt die Datei unveraendert
        val secondary = on(FIELD_SECONDARY) && detections.any { it.secondaryModelId != null }

        return outputFile.bufferedWriter(Charsets.UTF_8).use { w ->
            // Kopfzeile: Selection/View/Channel immer; optionale Felder nach Selektion
            val header = mutableListOf("Selection", "View", "Channel")
            if (on(FIELD_BEGIN_FILE))      header += "Begin File"
            if (on(FIELD_BEGIN_TIME))      header += "Begin Time (s)"
            if (on(FIELD_END_TIME))        header += "End Time (s)"
            if (on(FIELD_LOW_FREQ))        header += "Low Freq (Hz)"
            if (on(FIELD_HIGH_FREQ))       header += "High Freq (Hz)"
            if (on(FIELD_BEGIN_DATE_TIME)) header += "Begin Date Time"
            if (on(FIELD_SPECIES))         { header += "Species"; header += "Common Name" }
            if (on(FIELD_CONFIDENCE))      header += "Confidence"
            if (on(FIELD_LAT))             header += "Lat"
            if (on(FIELD_LON))             header += "Lon"
            if (on(FIELD_SIMULTAN))        header += "Simultan"
            if (on(FIELD_MOEGLICH))        header += "Moeglich"
            if (on(FIELD_GLEICH))          header += "Gleich"
            if (secondary) header += listOf("Second Model", "Second Status", "Second Score", "Second Top Species", "Second Top Score", "Second Rank")
            header += "Status"
            header += "Corrected Species"
            header += "Cluster"
            header += "Windows"
            if (on(FIELD_MEMO)) header += "Memo"
            if (noteCell.isNotEmpty()) header += "Session-Bemerkung"
            w.appendLine(header.joinToString("\t"))

            // T264: Session-Memos (mit Lage auf der Session-Achse) chronologisch zwischen die Detektions-Zeilen
            val detLines = detections.flatMap { d -> RavenEpisodes.rows(d, merges[d.id].orEmpty()).map { ep -> d to ep } }
            val lines = interleaveMemos(detLines, { it.second.startSec }, sessionMemos)

            var index = 0
            for (line in lines) {
                val det = line as? Pair<*, *>
                val d = det?.first as? DetectionResult
                val ep = det?.second as? RavenEpisodes.Row
                val memo = line as? SessionMemo
                // T259: Episode (Rueckfall T186: bestes Fenster des Clusters); Segment = das des Beginns
                val winStart = ep?.startSec ?: memo?.startSec ?: continue
                val winEnd = ep?.endSec ?: memo?.endSec ?: continue
                val segment = recordingSegments?.firstOrNull {
                    winStart >= it.startSec && winStart < it.startSec + it.durationSec
                }
                val beginFile  = segment?.fileName ?: "recording.wav"
                val localStart = if (segment != null) winStart - segment.startSec else winStart
                val localEnd   = if (segment != null) winEnd   - segment.startSec else winEnd
                // T255: importierte Segmente mit eigener Wanduhr (Luecken zwischen Dateien), sonst Session-Start + Offset
                val segmentStart = segment?.startedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                val detectionTime = segmentStart?.plusSeconds(localStart.toLong())
                    ?: sessionStart.plusSeconds(winStart.toLong())
                val beginDateTime = detectionTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

                index++
                val row = mutableListOf(index.toString(), VIEW, CHANNEL.toString())
                if (on(FIELD_BEGIN_FILE))      row += sanitize(beginFile)
                if (on(FIELD_BEGIN_TIME))      row += String.format(Locale.US, "%.3f", localStart)
                if (on(FIELD_END_TIME))        row += String.format(Locale.US, "%.3f", localEnd)
                if (on(FIELD_LOW_FREQ))        row += lowFreq
                if (on(FIELD_HIGH_FREQ))       row += highFreq
                if (on(FIELD_BEGIN_DATE_TIME)) row += beginDateTime
                if (d == null || ep == null) {
                    // T264: Session-Memo: sprachneutral "Memo", ohne Score, Status und Cluster
                    if (on(FIELD_SPECIES))     { row += MEMO_SPECIES; row += MEMO_SPECIES }
                    if (on(FIELD_CONFIDENCE))  row += ""
                    // T300: Position des Memos (GPS-Fix beim Druecken, T272), leer ohne Fix
                    if (on(FIELD_LAT))         row += memo?.lat?.let { String.format(Locale.US, "%.6f", it) } ?: ""
                    if (on(FIELD_LON))         row += memo?.lon?.let { String.format(Locale.US, "%.6f", it) } ?: ""
                    if (on(FIELD_SIMULTAN))    row += ""
                    if (on(FIELD_MOEGLICH))    row += ""
                    if (on(FIELD_GLEICH))      row += ""
                    if (secondary) repeat(6) { row += "" }
                    row += ""  // Status
                    row += ""  // Corrected Species
                    row += ""  // Cluster
                    row += ""  // Windows
                    if (on(FIELD_MEMO)) row += sanitize(memo?.file.orEmpty())
                    if (noteCell.isNotEmpty()) row += noteCell
                    w.appendLine(row.joinToString("\t"))
                    continue
                }
                if (on(FIELD_SPECIES))         { row += sanitize(d.scientificName); row += sanitize(ch.etasystems.pirol.ml.StoredSpeciesNames.of(d.scientificName, d.commonName)) }  // T287
                if (on(FIELD_CONFIDENCE))      row += ep.confidence?.let { String.format(Locale.US, "%.3f", it) } ?: ""
                if (on(FIELD_LAT))             row += d.latitude?.let  { String.format(Locale.US, "%.6f", it) } ?: ""
                if (on(FIELD_LON))             row += d.longitude?.let { String.format(Locale.US, "%.6f", it) } ?: ""
                if (on(FIELD_SIMULTAN))        row += linkMap[d.id]?.simultaneous?.joinToString(",") ?: ""
                if (on(FIELD_MOEGLICH))        row += linkMap[d.id]?.probable?.joinToString(",") ?: ""
                if (on(FIELD_GLEICH))          row += linkMap[d.id]?.sameIndividual?.joinToString(",") ?: ""
                if (secondary) {
                    row += sanitize(d.secondaryModelId.orEmpty())
                    row += d.secondaryStatus.orEmpty()
                    row += d.secondaryConfidence?.let { String.format(Locale.US, "%.3f", it) } ?: ""
                    row += sanitize(d.secondaryTopSpecies.orEmpty())
                    row += d.secondaryTopConfidence?.let { String.format(Locale.US, "%.3f", it) } ?: ""
                    row += d.secondaryRank?.toString().orEmpty()
                }
                row += d.verificationStatus.name
                row += sanitize(d.correctedSpecies ?: "")
                row += sanitize(d.id)
                row += ep.windows.toString()
                if (on(FIELD_MEMO)) row += sanitize(d.voiceMemoFile.orEmpty())
                if (noteCell.isNotEmpty()) row += noteCell
                w.appendLine(row.joinToString("\t"))
            }
            index
        }
    }

    /**
     * T294: Frequenzband der Raven-Zeilen fuer eine Session-Rate. Bis 96 kHz null (unveraendert 150..12000 Hz),
     * bei Ultraschall-Sessions der Ultraschall-Anzeigebereich 10 kHz bis min(150 kHz, Nyquist).
     */
    fun freqBandFor(sampleRate: Int?): Pair<Int, Int>? {
        if (sampleRate == null || !ch.etasystems.pirol.audio.dsp.SpectrogramConfig.isUltrasoundRate(sampleRate)) return null
        val lo = ch.etasystems.pirol.audio.dsp.SpectrogramConfig.ULTRASOUND_DISPLAY_MIN_HZ.toInt()
        val hi = minOf(ch.etasystems.pirol.audio.dsp.SpectrogramConfig.ULTRASOUND_DISPLAY_MAX_HZ.toInt(), sampleRate / 2)
        return lo to hi
    }

    /** T264: Species und Common Name einer Session-Memo-Zeile (sprachneutral) */
    const val MEMO_SPECIES = "Memo"

    /**
     * T264: Session-Memos mit Lage (startSec/endSec) in die Zeilenfolge einsortieren: jedes Memo vor die erste
     * Zeile, die spaeter beginnt; die Reihenfolge der uebrigen Zeilen bleibt unveraendert. Memos ohne Lage entfallen.
     */
    internal fun <T : Any> interleaveMemos(rows: List<T>, startOf: (T) -> Float, memos: List<SessionMemo>): List<Any> {
        val placed = memos.filter { it.startSec != null && it.endSec != null }.sortedBy { it.startSec }
        if (placed.isEmpty()) return rows
        val out = ArrayList<Any>(rows.size + placed.size)
        var mi = 0
        for (r in rows) {
            while (mi < placed.size && placed[mi].startSec!! < startOf(r)) out += placed[mi++]
            out += r
        }
        while (mi < placed.size) out += placed[mi++]
        return out
    }

    /** Tabs/Newlines aus Strings entfernen, damit das TSV-Format nicht bricht. */
    private fun sanitize(s: String): String = escapeTsv(s)

    /** T109: Tabs/Zeilenumbrueche durch Leerzeichen ersetzen (TSV-sicher). */
    internal fun escapeTsv(s: String): String =
        s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    /** T85: Verlinkte DetectionIds pro Kategorie fuer eine Detektion. */
    data class SimultaneousLinkSummary(
        val simultaneous: List<String> = emptyList(),
        val probable: List<String> = emptyList(),
        val sameIndividual: List<String> = emptyList()
    )
}
