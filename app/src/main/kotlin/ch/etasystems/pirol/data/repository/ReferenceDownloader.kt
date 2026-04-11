package ch.etasystems.pirol.data.repository

import android.util.Log
import ch.etasystems.pirol.data.api.XenoCantoClient
import ch.etasystems.pirol.data.api.XenoCantoRecording
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Laedt Xeno-Canto-Aufnahmen herunter und speichert sie in der Referenzbibliothek.
 * Nutzt den XenoCantoClient fuer API-Zugriff und das ReferenceRepository fuer lokale Speicherung.
 */
class ReferenceDownloader(
    private val xenoCantoClient: XenoCantoClient,
    private val referenceRepository: ReferenceRepository
) {
    companion object {
        private const val TAG = "RefDownloader"
    }

    private val httpClient = HttpClient(OkHttp)

    /**
     * Laedt eine Xeno-Canto-Aufnahme herunter und speichert sie als lokale Referenz.
     *
     * @param recording XenoCantoRecording mit Audio-URL und Metadaten
     * @return Result mit ReferenceEntry bei Erfolg, Exception bei Fehler
     */
    suspend fun downloadRecording(recording: XenoCantoRecording): Result<ReferenceEntry> =
        withContext(Dispatchers.IO) {
            try {
                val url = recording.audioUrl
                if (url.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Keine Audio-URL vorhanden"))
                }

                Log.i(TAG, "Download: XC${recording.id} (${recording.scientificName})")

                // MP3-Daten herunterladen
                val audioData = httpClient.get(url).readRawBytes()

                if (audioData.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("Leere Audio-Datei"))
                }

                Log.i(TAG, "Download OK: ${audioData.size} Bytes")

                // Koordinaten parsen (Xeno-Canto liefert Strings)
                val lat = recording.lat?.toDoubleOrNull()
                val lon = recording.lon?.toDoubleOrNull()

                // Common Name: englischer Name oder wissenschaftlicher Name
                val commonName = recording.en.ifBlank {
                    recording.scientificName.replace('_', ' ')
                }

                // In Referenzbibliothek speichern
                val entry = referenceRepository.addFromXenoCanto(
                    scientificName = recording.scientificName,
                    commonName = commonName,
                    audioData = audioData,
                    xenoCantoId = "XC${recording.id}",
                    recordist = recording.rec,
                    lat = lat,
                    lon = lon
                )

                if (entry != null) {
                    Result.success(entry)
                } else {
                    Result.failure(IllegalStateException("Speichern fehlgeschlagen"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download fehlgeschlagen: XC${recording.id}", e)
                Result.failure(e)
            }
        }

    /** Aufraeumen */
    fun close() {
        httpClient.close()
    }
}
