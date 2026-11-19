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
class RegionalSpeciesFilter(private val context: Context, private val pirolFolder: PirolFolder? = null) {

    companion object {
        private const val TAG = "RegionalSpeciesFilter"
        private const val REGION_SETS_PATH = "regions/region_sets.json"

        /** T105: Region-Arten als kanonische Schluessel (reine Logik, testbar). */
        internal fun canonicalize(species: Set<String>): Set<String> = species.map { SpeciesKey.of(it) }.toSet()

        /** T105: Pruefung gegen kanonisierte Region (leer = kein Filter). */
        internal fun matches(canonicalRegion: Set<String>, scientificName: String): Boolean =
            canonicalRegion.isEmpty() || SpeciesKey.of(scientificName) in canonicalRegion
    }

    private var currentRegionId: String? = null
    private var speciesSet: Set<String> = emptySet()

    // T105: kanonische Schluessel der aktiven Region (Synonyme aufgeloest) fuer den Vergleich
    private var canonicalSet: Set<String> = emptySet()
    private var allSets: List<RegionInfo> = emptyList()

    // T129: eigene Listen aus <Ordner>/lists/NAME.json
    @Volatile
    private var customLists: List<CustomSpeciesList> = emptyList()
    @Volatile
    private var customWarnings: List<String> = emptyList()

    @Volatile
    private var listsSignature: String? = null

    /**
     * T136: Listen nur neu einlesen, wenn sich Dateien (Name/lastModified/Groesse) geaendert haben (IO).
     * @return true, wenn neu eingelesen wurde
     */
    fun refreshCustomListsIfChanged(): Boolean {
        val sig = try {
            CustomSpeciesLists.signature(pirolFolder?.listFileStats().orEmpty())
        } catch (e: Exception) { null }
        if (sig != null && sig == listsSignature) return false
        refreshCustomLists()
        return true
    }

    /** T129: Listen aus dem freigegebenen Ordner neu einlesen (IO). Warnungen siehe [getCustomWarnings]. */
    fun refreshCustomLists() {
        listsSignature = try {
            CustomSpeciesLists.signature(pirolFolder?.listFileStats().orEmpty())
        } catch (e: Exception) { null }
        val lists = mutableListOf<CustomSpeciesList>()
        val warnings = mutableListOf<String>()
        try {
            SpeciesKey.load(context)
            for ((name, text) in pirolFolder?.readListFiles().orEmpty()) {
                if (text == null) { warnings.add("$name: nicht lesbar"); continue }
                val r = CustomSpeciesLists.parse(name, text)
                lists.addAll(r.lists)
                if (r.error != null) warnings.add("$name: ${r.error}")
            }
        } catch (e: Exception) {
            warnings.add("lists: ${e.message}")
        }
        customLists = lists
        customWarnings = warnings
        // aktive eigene Liste neu laden, falls sie sich geaendert hat
        val cur = currentRegionId
        if (cur != null && cur.startsWith(CustomSpeciesLists.ID_PREFIX)) loadRegion(cur)
    }

    fun getCustomWarnings(): List<String> = customWarnings

    /**
     * Laedt die Region-Sets aus der JSON-Datei (einmalig beim ersten Aufruf).
     * Aktiviert das Set mit der gegebenen Region-ID.
     *
     * @param regionId z.B. "ch_breeding", "ch_all", "central_europe", "all"
     */
    fun loadRegion(regionId: String = "ch_breeding") {
        SpeciesKey.load(context)
        try {
            // Sets nur einmal parsen
            if (allSets.isEmpty()) {
                parseRegionSets()
            }

            // "all" = kein Filter (leere species-Liste)
            if (regionId == "all") {
                speciesSet = emptySet()
                canonicalSet = emptySet()
                currentRegionId = "all"
                Log.i(TAG, "Region 'all' aktiviert — kein Artenfilter")
                return
            }

            // T129: eigene Liste
            if (regionId.startsWith(CustomSpeciesLists.ID_PREFIX)) {
                if (customLists.isEmpty()) refreshCustomLists()
                val custom = customLists.firstOrNull { it.id == regionId }
                if (custom != null) {
                    speciesSet = custom.species
                    canonicalSet = custom.canonical()
                    currentRegionId = regionId
                    Log.i(TAG, "Eigene Liste '$regionId' geladen: ${custom.species.size} Arten")
                } else {
                    Log.w(TAG, "Eigene Liste '$regionId' nicht gefunden")
                }
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
                    canonicalSet = canonicalize(species)
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
        // Kein Filter aktiv oder "all" gewaehlt; sonst Vergleich ueber kanonischen Schluessel (T105)
        return matches(canonicalSet, scientificName)
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
        return allSets + customLists.map {
            RegionInfo(it.id, it.nameDe, it.nameEn, it.species.size, isCustom = true)
        }
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
    val speciesCount: Int,
    /** T129: eigene Liste aus dem PIROL-Ordner */
    val isCustom: Boolean = false
)
