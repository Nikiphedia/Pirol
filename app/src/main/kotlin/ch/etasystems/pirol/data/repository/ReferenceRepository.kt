package ch.etasystems.pirol.data.repository

import android.content.Context
import android.util.Log
import ch.etasystems.pirol.audio.export.DetectionExcerpt
import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Repository fuer lokale Referenz-Aufnahmen.
 *
 * Speichert verifizierte Detektionen (CONFIRMED/CORRECTED) als WAV-Dateien
 * in filesDir/references/{scientificName}/ und verwaltet einen JSON-Index.
 */
class ReferenceRepository(private val context: Context) {

    companion object {
        private const val TAG = "ReferenceRepo"
        private const val REFERENCES_DIR = "references"
        private const val INDEX_FILE = "index.json"
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val entries = mutableListOf<ReferenceEntry>()

    // T127: IDs der Detektionen, die schon als Referenz gespeichert sind (Markierung + Umschalten)
    private val _referencedDetectionIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val referencedDetectionIds: kotlinx.coroutines.flow.StateFlow<Set<String>> = _referencedDetectionIds

    private fun publishReferencedIds() {
        _referencedDetectionIds.value = synchronized(entries) {
            entries.filter { it.source == "DETECTION" }.map { it.sourceDetectionId }.toSet()
        }
    }

    /** T127: vorhandene Referenz einer Detektion (null = noch keine) */
    fun findByDetection(detectionId: String): ReferenceEntry? =
        synchronized(entries) { entries.firstOrNull { it.source == "DETECTION" && it.sourceDetectionId == detectionId } }

    /** Alle Referenzen (in-memory Cache) */
    val allEntries: List<ReferenceEntry> get() = entries.toList()

    /** Anzahl Referenzen */
    val size: Int get() = entries.size

    /** Alle vorhandenen Arten (sortiert) */
    fun getSpecies(): List<String> {
        return entries.map { it.scientificName }.distinct().sorted()
    }

    /** Referenzen fuer eine Art */
    fun getBySpecies(scientificName: String): List<ReferenceEntry> {
        return entries.filter { it.scientificName == scientificName }
    }

    /**
     * Fuegt eine verifizierte Detektion als Referenz hinzu (T123).
     *
     * Schneidet den Ausschnitt [range] (T116: 3 s davor, 5 s danach) aus der Session-Aufnahme
     * und schreibt ihn als eigene WAV nach references/{Art}/. Vorher wurde ein nicht mehr
     * existierender Pfad audio/recording.wav gesucht (seit T51 flach) und still abgebrochen.
     *
     * @param recording Aufnahme der Session (T130: alle WAV-Segmente, [range] auf der Session-Achse)
     * @return ReferenceEntry oder null bei Fehler
     */
    suspend fun addFromDetection(
        detection: DetectionResult,
        recording: SegmentedRecording,
        sessionId: String,
        range: DetectionExcerpt.Range
    ): ReferenceEntry? = withContext(Dispatchers.IO) {
        // T131: File- oder SAF-Segment (Laenge > Header = Aufnahme vorhanden)
        if (recording.parts.none { runCatching { it.source.length() > 0L }.getOrDefault(false) }) {
            Log.e(TAG, "WAV nicht gefunden: ${recording.firstSource?.name}")
            return@withContext null
        }
        val pcm = try {
            recording.readRange(range.startSec, range.endSec)
        } catch (e: Exception) {
            Log.e(TAG, "Ausschnitt lesen fehlgeschlagen", e)
            null
        } ?: return@withContext null

        // Bei CORRECTED zaehlt die korrigierte Art
        val corrected = detection.correctedSpecies
        val sciName = if (detection.verificationStatus == VerificationStatus.CORRECTED &&
            !corrected.isNullOrBlank()) corrected else detection.scientificName
        val common = if (sciName == detection.scientificName) detection.commonName else sciName.replace('_', ' ')
        store(
            pcm = pcm,
            speciesKey = ReferenceNaming.speciesKey(sciName),
            commonName = common,
            confidence = detection.confidence,
            sourceSessionId = sessionId,
            sourceDetectionId = detection.id,
            recordedAtMs = detection.timestampMs,
            lat = detection.latitude,
            lon = detection.longitude,
            status = detection.verificationStatus.name,
            source = "DETECTION",
            originalName = null
        )
    }

    /** Importiert eine externe Aufnahme (bereits zu Mono-PCM dekodiert) als Referenz (T123). */
    suspend fun addImported(
        pcm: ExcerptWav.Pcm,
        scientificName: String,
        commonName: String,
        originalName: String?
    ): ReferenceEntry? = withContext(Dispatchers.IO) {
        store(
            pcm = pcm,
            speciesKey = ReferenceNaming.speciesKey(scientificName),
            commonName = commonName,
            confidence = 1f,
            sourceSessionId = "import",
            sourceDetectionId = "",
            recordedAtMs = System.currentTimeMillis(),
            lat = null,
            lon = null,
            status = "IMPORTED",
            source = "IMPORT",
            originalName = originalName
        )
    }

    private suspend fun store(
        pcm: ExcerptWav.Pcm,
        speciesKey: String,
        commonName: String,
        confidence: Float,
        sourceSessionId: String,
        sourceDetectionId: String,
        recordedAtMs: Long,
        lat: Double?,
        lon: Double?,
        status: String,
        source: String,
        originalName: String?
    ): ReferenceEntry? {
        val speciesDir = File(File(context.filesDir, REFERENCES_DIR), speciesKey)
        speciesDir.mkdirs()
        val existing = speciesDir.list()?.toSet().orEmpty() +
            getBySpecies(speciesKey).map { it.wavFileName }
        val wavFileName = ReferenceNaming.nextWavFileName(speciesKey, existing)
        val targetWav = File(speciesDir, wavFileName)
        try {
            ExcerptWav.write(targetWav, pcm)
        } catch (e: Exception) {
            Log.e(TAG, "WAV schreiben fehlgeschlagen", e)
            targetWav.delete()
            return null
        }
        val entry = ReferenceEntry(
            id = UUID.randomUUID().toString().take(8),
            scientificName = speciesKey,
            commonName = commonName,
            confidence = confidence,
            wavFileName = wavFileName,
            sourceSessionId = sourceSessionId,
            sourceDetectionId = sourceDetectionId,
            recordedAtMs = recordedAtMs,
            addedAtMs = System.currentTimeMillis(),
            latitude = lat,
            longitude = lon,
            verificationStatus = status,
            source = source,
            originalName = originalName
        )
        synchronized(entries) { entries.add(entry) }
        saveIndex()
        Log.i(TAG, "Referenz gespeichert: ${entry.commonName} -> ${targetWav.name}")
        return entry
    }

    /** Einzelne Referenz loeschen (WAV + Index-Eintrag) */
    suspend fun deleteReference(entry: ReferenceEntry): Boolean = withContext(Dispatchers.IO) {
        val wavFile = getWavFile(entry)
        wavFile?.delete()
        entries.removeAll { it.id == entry.id }
        saveIndex()
        Log.i(TAG, "Referenz geloescht: ${entry.commonName} (${entry.wavFileName})")
        true
    }

    /** Index laden (beim App-Start) */
    suspend fun loadIndex() = withContext(Dispatchers.IO) {
        val indexFile = File(File(context.filesDir, REFERENCES_DIR), INDEX_FILE)
        if (!indexFile.exists()) {
            Log.d(TAG, "Kein Index vorhanden — leere Bibliothek")
            return@withContext
        }
        try {
            val index = json.decodeFromString<ReferenceIndex>(indexFile.readText())
            entries.clear()
            entries.addAll(index.entries)
            publishReferencedIds()  // T127
            Log.i(TAG, "Index geladen: ${entries.size} Referenzen, " +
                    "${getSpecies().size} Arten")
        } catch (e: Exception) {
            Log.e(TAG, "Index laden fehlgeschlagen", e)
        }
    }

    /** Index speichern */
    private suspend fun saveIndex() = withContext(Dispatchers.IO) {
        publishReferencedIds()  // T127
        val refsRoot = File(context.filesDir, REFERENCES_DIR)
        refsRoot.mkdirs()
        val indexFile = File(refsRoot, INDEX_FILE)

        val index = ReferenceIndex(
            version = 1,
            updatedAt = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            totalSpecies = getSpecies().size,
            totalRecordings = entries.size,
            entries = entries.toList()
        )

        try {
            indexFile.writeText(json.encodeToString(ReferenceIndex.serializer(), index))
            Log.d(TAG, "Index gespeichert: ${entries.size} Eintraege")
        } catch (e: Exception) {
            Log.e(TAG, "Index speichern fehlgeschlagen", e)
        }
    }

    /** WAV-Datei fuer eine Referenz holen */
    fun getWavFile(entry: ReferenceEntry): File? {
        val speciesDir = File(File(context.filesDir, REFERENCES_DIR), entry.scientificName)
        val wavFile = File(speciesDir, entry.wavFileName)
        return if (wavFile.exists()) wavFile else null
    }
}
