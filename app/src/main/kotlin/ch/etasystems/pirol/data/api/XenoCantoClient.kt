package ch.etasystems.pirol.data.api

import android.util.Log
import ch.etasystems.pirol.data.SecurePreferences
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Xeno-Canto API v3 Client.
 *
 * Nutzt Ktor OkHttp-Engine (bereits im Projekt).
 * API-Key wird aus SecurePreferences gelesen (EncryptedSharedPreferences).
 *
 * API-Dokumentation: https://xeno-canto.org/explore/api
 */
class XenoCantoClient(
    private val securePreferences: SecurePreferences
) {
    companion object {
        private const val TAG = "XenoCantoClient"
        private const val BASE_URL = "https://xeno-canto.org/api/3/recordings"
        private const val DEFAULT_PER_PAGE = 100
        private const val MAX_PER_PAGE = 500
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    /** Prueft ob ein API-Key konfiguriert ist */
    val hasApiKey: Boolean get() = securePreferences.hasXenoCantoApiKey

    /**
     * Sucht Aufnahmen nach wissenschaftlichem Namen.
     *
     * @param scientificName z.B. "Turdus merula" oder "Turdus_merula"
     * @param quality Mindestqualitaet (null = alle)
     * @param country Laenderfilter (null = alle)
     * @param page Seitennummer (1-basiert)
     * @param perPage Ergebnisse pro Seite (50-500)
     * @return Liste von Aufnahmen
     * @throws IllegalStateException wenn kein API-Key konfiguriert
     */
    suspend fun searchBySpecies(
        scientificName: String,
        quality: String? = null,
        country: String? = null,
        page: Int = 1,
        perPage: Int = DEFAULT_PER_PAGE
    ): List<XenoCantoRecording> {
        check(securePreferences.hasXenoCantoApiKey) {
            "Xeno-Canto API-Key nicht konfiguriert. Bitte in Settings eingeben."
        }

        // Wissenschaftlichen Namen in v3-Tags umwandeln
        val parts = scientificName.replace('_', ' ').trim().split(" ", limit = 2)
        val query = buildString {
            if (parts.isNotEmpty()) append("gen:${parts[0]}")
            if (parts.size > 1) append(" sp:${parts[1]}")
            quality?.let { append(" q:$it") }
            country?.let { append(" cnt:\"$it\"") }
        }

        return search(query, page, perPage)
    }

    /**
     * Generische Suche mit v3-Query-Syntax.
     *
     * @param query v3-konforme Query (z.B. "gen:Turdus sp:merula q:A")
     * @param page Seitennummer
     * @param perPage Ergebnisse pro Seite
     * @return Liste von Aufnahmen
     */
    suspend fun search(
        query: String,
        page: Int = 1,
        perPage: Int = DEFAULT_PER_PAGE
    ): List<XenoCantoRecording> {
        check(securePreferences.hasXenoCantoApiKey) {
            "Xeno-Canto API-Key nicht konfiguriert."
        }

        val apiKey = securePreferences.xenoCantoApiKey
        Log.i(TAG, "Suche: query='$query', page=$page (key=${apiKey.take(4)}...)")

        return try {
            val response: XenoCantoResponse = client.get(BASE_URL) {
                parameter("query", query)
                parameter("key", apiKey)
                parameter("page", page)
                parameter("per_page", perPage.coerceIn(50, MAX_PER_PAGE))
            }.body()

            Log.i(TAG, "Ergebnis: ${response.numRecordings} Aufnahmen, " +
                    "${response.numSpecies} Arten, Seite ${response.page}/${response.numPages}")

            response.recordings
        } catch (e: Exception) {
            Log.e(TAG, "API-Fehler: ${e.message}")
            emptyList()
        }
    }

    /** Aufraeumen bei App-Ende */
    fun close() {
        client.close()
    }
}
