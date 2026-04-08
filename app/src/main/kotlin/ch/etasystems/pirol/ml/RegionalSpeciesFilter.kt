package ch.etasystems.pirol.ml

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Regionaler Artenfilter — laedt regionale Artenlisten aus assets/regions/region_sets.json.
 *
 * Format identisch mit AMSEL Desktop (region_sets.json v3).
 * Artennamen verwenden Unterstrich-Konvention: "Turdus_merula" (BirdNET-Standard).
 */
class RegionalSpeciesFilter(private val context: Context) {

    companion object {
        private const val TAG = "RegionalSpeciesFilter"
        private const val REGION_SETS_PATH = "regions/region_sets.json"
    }

    private var currentRegionId: String? = null
    private var speciesSet: Set<String> = emptySet()
    private var allSets: List<RegionInfo> = emptyList()

    /**
     * Laedt die Region-Sets aus der JSON-Datei (einmalig beim ersten Aufruf).
     * Aktiviert das Set mit der gegebenen Region-ID.
     *
     * @param regionId z.B. "ch_breeding", "ch_all", "central_europe", "all"
     */
    fun loadRegion(regionId: String = "ch_breeding") {
        try {
            // Sets nur einmal parsen
            if (allSets.isEmpty()) {
                parseRegionSets()
            }

            // "all" = kein Filter (leere species-Liste)
            if (regionId == "all") {
                speciesSet = emptySet()
                currentRegionId = "all"
                Log.i(TAG, "Region 'all' aktiviert — kein Artenfilter")
                return
            }

            // Set mit passender ID suchen
            val jsonText = context.assets.open(REGION_SETS_PATH).bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)
            val setsArray = root.getJSONArray("sets")

            for (i in 0 until setsArray.length()) {
                val set = setsArray.getJSONObject(i)
                if (set.getString("id") == regionId) {
                    val speciesArray = set.getJSONArray("species")
                    val species = mutableSetOf<String>()
                    for (j in 0 until speciesArray.length()) {
                        species.add(speciesArray.getString(j))
                    }
                    speciesSet = species
                    currentRegionId = regionId
                    Log.i(TAG, "Region '$regionId' geladen: ${species.size} Arten")
                    return
                }
            }

            Log.w(TAG, "Region '$regionId' nicht gefunden in region_sets.json")
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Laden der Region '$regionId': ${e.message}")
        }
    }

    /**
     * Prueft ob eine Art in der aktuell geladenen Region plausibel ist.
     *
     * Akzeptiert beide Formate:
     * - "Turdus_merula" (BirdNET/AMSEL-Standard)
     * - "Turdus merula" (Leerzeichen)
     *
     * @return true wenn die Art in der Region vorkommt, oder wenn kein Filter aktiv ist
     */
    fun isPlausible(scientificName: String): Boolean {
        // Kein Filter aktiv oder "all" gewaehlt
        if (speciesSet.isEmpty()) return true

        // Normalisierung: Leerzeichen → Unterstrich (BirdNET-Konvention)
        val normalized = scientificName.replace(' ', '_')
        return normalized in speciesSet
    }

    /** Alle Arten der aktuell geladenen Region */
    fun getSpeciesList(): Set<String> = speciesSet

    /** Aktuell geladene Region-ID (null wenn keine geladen) */
    fun getCurrentRegionId(): String? = currentRegionId

    /** Alle verfuegbaren Region-Sets */
    fun getAvailableRegions(): List<RegionInfo> {
        if (allSets.isEmpty()) {
            parseRegionSets()
        }
        return allSets
    }

    /**
     * Parst die region_sets.json und baut die Metadaten-Liste auf.
     */
    private fun parseRegionSets() {
        try {
            val jsonText = context.assets.open(REGION_SETS_PATH).bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)
            val setsArray = root.getJSONArray("sets")
            val result = mutableListOf<RegionInfo>()

            for (i in 0 until setsArray.length()) {
                val set = setsArray.getJSONObject(i)
                val id = set.getString("id")
                val nameDe = set.optString("name_de", id)
                val nameEn = set.optString("name_en", id)
                val speciesCount = set.getJSONArray("species").length()
                result.add(RegionInfo(id = id, nameDe = nameDe, nameEn = nameEn, speciesCount = speciesCount))
            }

            allSets = result
            Log.i(TAG, "Region-Sets geladen: ${result.size} Sets " +
                    "(${result.joinToString { "${it.id}:${it.speciesCount}" }})")
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Parsen von region_sets.json: ${e.message}")
        }
    }
}

/**
 * Metadaten eines Region-Sets.
 */
data class RegionInfo(
    val id: String,
    val nameDe: String,
    val nameEn: String,
    val speciesCount: Int
)
