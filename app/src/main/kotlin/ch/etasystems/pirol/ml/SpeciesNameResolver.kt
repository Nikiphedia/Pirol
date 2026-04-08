package ch.etasystems.pirol.ml

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Uebersetzt wissenschaftliche Artnamen in CommonNames der gewaehlten Sprache.
 * Nutzt species_master.json (11'560 Arten, 23 Sprachen) aus AMSEL.
 *
 * Fallback-Kette: gewaehlte Sprache → Englisch → scientificName (ohne Unterstrich).
 */
class SpeciesNameResolver(private val context: Context) {

    companion object {
        private const val TAG = "SpeciesNames"
        private const val SPECIES_PATH = "species/species_master.json"

        /** Verfuegbare Sprachen (Subset der 23, die wichtigsten) */
        val AVAILABLE_LANGUAGES = listOf(
            "de" to "Deutsch",
            "en" to "English",
            "fr" to "Français",
            "it" to "Italiano",
            "es" to "Español",
            "pt" to "Português",
            "nl" to "Nederlands",
            "pl" to "Polski",
            "ru" to "Русский",
            "ja" to "日本語",
            "zh" to "中文"
        )
    }

    // Lookup: scientificName (Unterstrich) → Map<langCode, commonName>
    private val namesMap = mutableMapOf<String, Map<String, String>>()
    private var loaded = false

    /** Aktuelle Sprache (Default: Deutsch) */
    var language: String = "de"

    /**
     * species_master.json laden und Index aufbauen.
     * Wird einmalig beim App-Start aufgerufen.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        try {
            val jsonText = context.assets.open(SPECIES_PATH).bufferedReader().readText()
            val jsonObj = JSONObject(jsonText)
            val taxa = jsonObj.getJSONArray("taxa")
            for (i in 0 until taxa.length()) {
                val taxon = taxa.getJSONObject(i)
                val sciName = taxon.getString("scientific_name")
                val commonNames = taxon.optJSONObject("common_names") ?: continue
                val langMap = mutableMapOf<String, String>()
                for (key in commonNames.keys()) {
                    val name = commonNames.getString(key)
                    if (name.isNotEmpty()) {
                        langMap[key] = name
                    }
                }
                namesMap[sciName] = langMap
            }
            loaded = true
            Log.i(TAG, "Species-Namen geladen: ${namesMap.size} Arten, Sprache: $language")
        } catch (e: Exception) {
            Log.e(TAG, "species_master.json laden fehlgeschlagen", e)
        }
    }

    /**
     * Uebersetzt scientificName in CommonName der gewaehlten Sprache.
     * Fallback: Englisch → scientificName falls nichts gefunden.
     */
    fun resolve(scientificName: String): String {
        val key = scientificName.replace(' ', '_')
        val names = namesMap[key] ?: return scientificName.replace('_', ' ')
        return names[language]
            ?: names["en"]
            ?: scientificName.replace('_', ' ')
    }

    /** Alle verfuegbaren Namen fuer eine Art */
    fun getAllNames(scientificName: String): Map<String, String> {
        val key = scientificName.replace(' ', '_')
        return namesMap[key] ?: emptyMap()
    }
}
