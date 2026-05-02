package ch.etasystems.pirol.ml

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PhenologyEntry(
    val key: String,
    val monthsActive: List<Int>,
    val rarityScore: Float
)

@Serializable
private data class PhenologyFile(
    val version: Int,
    val region: String,
    val species: List<PhenologyEntry>
)

class PhenologyRepository(private val context: Context) {

    companion object {
        private const val TAG = "PhenologyRepository"
        private const val ASSET_PATH = "species_phenology.json"
        private val jsonParser = Json { ignoreUnknownKeys = true }

        internal fun parsePhenologyJson(jsonString: String): Map<String, PhenologyEntry> {
            return try {
                val file = jsonParser.decodeFromString<PhenologyFile>(jsonString)
                file.species.associateBy { it.key }
            } catch (e: Exception) {
                Log.w(TAG, "Fehler beim Parsen von Phenology-JSON: ${e.message}")
                emptyMap()
            }
        }
    }

    private var cache: Map<String, PhenologyEntry>? = null

    fun loadPhenology(): Map<String, PhenologyEntry> {
        cache?.let { return it }
        val jsonString = try {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Oeffnen von $ASSET_PATH: ${e.message}")
            cache = emptyMap()
            return emptyMap()
        }
        val parsed = parsePhenologyJson(jsonString)
        cache = parsed
        Log.i(TAG, "Phenology geladen: ${parsed.size} Eintraege")
        return parsed
    }

    fun isInSeason(speciesKey: String, month: Int): Boolean {
        val map = loadPhenology()
        val entry = map[speciesKey] ?: return true
        return month in entry.monthsActive
    }

    fun rarity(speciesKey: String): Float {
        val map = loadPhenology()
        return map[speciesKey]?.rarityScore ?: 0.0f
    }

    fun reload() {
        cache = null
        loadPhenology()
    }

    fun entryCount(): Int = loadPhenology().size
}
