package ch.etasystems.pirol.data.repository

import android.content.Context
import android.util.Log
import ch.etasystems.pirol.ml.DetectionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
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
     * Fuegt eine verifizierte Detektion als Referenz hinzu.
     *
     * 1. Kopiert WAV aus Session-Ordner in references/{species}/
     * 2. Erstellt ReferenceEntry
     * 3. Aktualisiert Index
     *
     * @param detection Verifizierte Detektion (CONFIRMED oder CORRECTED)
     * @param sessionDir Session-Ordner der die Audio-Chunks enthaelt
     * @param chunkIndex Index des 3s-Chunks (fuer Dateiname)
     * @return ReferenceEntry oder null bei Fehler
     */
    suspend fun addFromDetection(
        detection: DetectionResult,
        sessionDir: File,
        chunkIndex: Int
    ): ReferenceEntry? = withContext(Dispatchers.IO) {
        // 1. Quell-WAV finden (T46: ganze recording.wav; T48/T49 schneidet mit Zeitoffset)
        val sourceWav = File(sessionDir, "audio/recording.wav")
        if (!sourceWav.exists()) {
            Log.e(TAG, "WAV nicht gefunden: ${sourceWav.absolutePath}")
            return@withContext null
        }

        // 2. Zielordner erstellen
        val speciesName = detection.scientificName.replace(' ', '_')
        val refsRoot = File(context.filesDir, REFERENCES_DIR)
        val speciesDir = File(refsRoot, speciesName)
        speciesDir.mkdirs()

        // 3. Naechste ID + Dateiname
        val existingCount = getBySpecies(speciesName).size
        val refId = UUID.randomUUID().toString().take(8)
        val wavFileName = "ref_${String.format("%03d", existingCount + 1)}_${speciesName}.wav"

        // 4. WAV kopieren
        val targetWav = File(speciesDir, wavFileName)
        try {
            sourceWav.copyTo(targetWav, overwrite = false)
        } catch (e: Exception) {
            Log.e(TAG, "WAV kopieren fehlgeschlagen", e)
            return@withContext null
        }

        // 5. Entry erstellen
        val entry = ReferenceEntry(
            id = refId,
            scientificName = speciesName,
            commonName = detection.commonName,
            confidence = detection.confidence,
            wavFileName = wavFileName,
            sourceSessionId = sessionDir.name,
            sourceDetectionId = detection.id,
            recordedAtMs = detection.timestampMs,
            addedAtMs = System.currentTimeMillis(),
            latitude = detection.latitude,
            longitude = detection.longitude,
            verificationStatus = detection.verificationStatus.name
        )

        entries.add(entry)
        saveIndex()
        Log.i(TAG, "Referenz gespeichert: ${entry.commonName} → ${targetWav.name}")
        entry
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
            Log.i(TAG, "Index geladen: ${entries.size} Referenzen, " +
                    "${getSpecies().size} Arten")
        } catch (e: Exception) {
            Log.e(TAG, "Index laden fehlgeschlagen", e)
        }
    }

    /** Index speichern */
    private suspend fun saveIndex() = withContext(Dispatchers.IO) {
        val refsRoot = File(context.filesDir, REFERENCES_DIR)
        refsRoot.mkdirs()
        val indexFile = File(refsRoot, INDEX_FILE)

        val index = ReferenceIndex(
            version = 1,
            updatedAt = Instant.now().toString(),
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
