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
class RegionalSpeciesFilter(
    private val context: Context,
    private val pirolFolder: PirolFolder? = null,
    /** T190: erhaelt Anzeigenamen aus eigenen Listen (Rueckfall fuer Arten ohne species_master-Eintrag) */
    private val speciesNames: SpeciesNameResolver? = null,
    /** T218: Begleitliste und Verknuepfung fuer "auto_geo" */
    private val appPreferences: ch.etasystems.pirol.data.AppPreferences? = null
) {

    companion object {
        private const val TAG = "RegionalSpeciesFilter"
        private const val REGION_SETS_PATH = "regions/region_sets.json"

        /** T105: Region-Arten als kanonische Schluessel (reine Logik, testbar). */
        internal fun canonicalize(species: Set<String>): Set<String> = species.map { SpeciesKey.of(it) }.toSet()

        /** T105: Pruefung gegen kanonisierte Region (leer = kein Filter). */
        internal fun matches(canonicalRegion: Set<String>, scientificName: String): Boolean =
            canonicalRegion.isEmpty() || SpeciesKey.of(scientificName) in canonicalRegion

        /** T232: Region-ID fuer "Liste je Artgruppe" (Auswahl in AppPreferences.speciesGroups) */
        const val GROUPS_ID = "groups"

        /** T232: Ersatzliste einer Auto-Gruppe ohne Position/Geomodel (Voegel: Einstellung, leer = alle) */
        internal fun autoFallbackId(g: TaxonGroup, birdsFallback: String): String = when (g) {
            TaxonGroup.BIRDS -> birdsFallback
            TaxonGroup.ORTHOPTERA, TaxonGroup.AMPHIBIANS -> "ch_orthoptera_anura"
            TaxonGroup.MAMMALS, TaxonGroup.OTHER -> "all"
        }

        /**
         * T232: Plausibel, wenn die Gruppe der Art nicht "Aus" ist und deren Quelle die Art erlaubt.
         * Reine Logik (Tests).
         */
        internal fun groupPlausible(
            key: String,
            rules: Map<TaxonGroup, GroupRule>,
            mask: GeoMask?,
            groupOf: (String) -> TaxonGroup
        ): Boolean = (rules[groupOf(key)] ?: GroupRule.Off).allows(key, mask)
    }

    // T232: Regeln je Artgruppe; null = Einzelliste wie bisher (z.B. Rescan alter Sessions)
    @Volatile
    private var groupRules: Map<TaxonGroup, GroupRule>? = null

    // T218: Geomodel-Filter (Region "auto_geo"); die Maske setzt der GeoFilterController
    @Volatile
    private var autoGeo = false
    @Volatile
    private var geoMask: GeoMask? = null
    @Volatile
    private var geoCombine = GeoCombine.GEO_ONLY

    private var currentRegionId: String? = null
    private var speciesSet: Set<String> = emptySet()

    // T105: kanonische Schluessel der aktiven Region (Synonyme aufgeloest) fuer den Vergleich
    private var canonicalSet: Set<String> = emptySet()
    private var allSets: List<RegionInfo> = emptyList()

    // T190: aktive eigene Liste (fuer Saison), null bei Asset-Region
    @Volatile
    private var activeCustom: CustomSpeciesList? = null

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
        readCustomLists()
        // aktive eigene Liste neu laden, falls sie sich geaendert hat
        if (groupRules != null) { loadRegion(GROUPS_ID); return }  // T232
        val cur = if (autoGeo) appPreferences?.geoCompanionRegion else currentRegionId
        if (cur != null && cur.startsWith(CustomSpeciesLists.ID_PREFIX)) loadRegion(if (autoGeo) GeoLogic.REGION_ID else cur)
    }

    /** Eigene Listen einlesen, ohne den aktiven Filter neu zu laden (IO) */
    private fun readCustomLists() {
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
                warnings.addAll(r.warnings)
                if (r.error != null) warnings.add("$name: ${r.error}")
            }
        } catch (e: Exception) {
            warnings.add("lists: ${e.message}")
        }
        customLists = lists
        customWarnings = warnings
        // T190: Namen aus allen Listen als Rueckfall an den Resolver
        speciesNames?.setListNames(lists.fold(mutableMapOf<String, String>()) { acc, l ->
            l.commonNames.forEach { (k, v) -> acc.putIfAbsent(k, v) }; acc
        })
        TaxonGroups.load(context)
        TaxonGroups.addFromCustomLists(lists)  // T232: Arten eigener Listen einer Gruppe zuordnen
    }

    fun getCustomWarnings(): List<String> = customWarnings

    /** T190: eigene Liste per Region-ID (z.B. "list:beispiel") */
    fun getCustomList(regionId: String): CustomSpeciesList? = customLists.firstOrNull { it.id == regionId }

    /**
     * Laedt die Region-Sets aus der JSON-Datei (einmalig beim ersten Aufruf).
     * Aktiviert das Set mit der gegebenen Region-ID.
     *
     * @param regionId z.B. "ch_breeding", "ch_all", "central_europe", "all"
     */
    fun loadRegion(regionId: String = "ch_breeding") {
        // T218: "auto_geo" = Begleitliste laden (Ersatz/Verknuepfung), Geomodel-Maske kommt vom Controller
        val auto = regionId == GeoLogic.REGION_ID
        if (regionId == GROUPS_ID) {
            // T232: Maske bleibt (haengt nur von Position/Woche ab), Regeln aus den Einstellungen
            loadGroups()
            return
        }
        groupRules = null
        autoGeo = auto
        if (!auto) geoMask = null
        if (auto) {
            geoCombine = GeoCombine.parse(appPreferences?.geoCombine)
            loadRegionInternal(appPreferences?.geoCompanionRegion ?: "ch_all")
            currentRegionId = GeoLogic.REGION_ID
        } else {
            loadRegionInternal(regionId)
        }
    }

    /** T218: Ist der Geomodel-Filter aktiv (Region "auto_geo" oder eine Artgruppe auf Auto, T232)? */
    fun isAutoGeo(): Boolean = autoGeo || groupRules?.values?.any { it is GroupRule.Auto } == true

    /** T232: Regeln je Artgruppe aus [AppPreferences.speciesGroups] aufbauen (IO). */
    private fun loadGroups() {
        SpeciesKey.load(context)
        TaxonGroups.load(context)
        if (allSets.isEmpty()) parseRegionSets()
        val sel = appPreferences?.speciesGroups ?: SpeciesGroupSelection.DEFAULT
        val needsCustom = TaxonGroup.entries.any { sel.sourceOf(it).startsWith(CustomSpeciesLists.ID_PREFIX) }
        if (needsCustom && customLists.isEmpty()) readCustomLists()
        val birdsFallback = appPreferences?.geoCompanionRegion ?: "ch_all"
        val union = mutableSetOf<String>()
        val rules = TaxonGroup.entries.associateWith { g ->
            when (val src = sel.sourceOf(g)) {
                GroupSource.OFF -> GroupRule.Off
                GroupSource.ALL -> GroupRule.All
                GroupSource.AUTO -> {
                    val fb = resolveList(autoFallbackId(g, birdsFallback))
                    fb?.let { union.addAll(it.first) }
                    GroupRule.Auto(fb?.let { canonicalize(it.first) } ?: emptySet())
                }
                else -> {
                    val l = resolveList(src)
                    l?.let { union.addAll(it.first) }
                    GroupRule.Listed(l?.let { canonicalize(it.first) } ?: emptySet(), l?.second)
                }
            }
        }
        groupRules = rules
        autoGeo = false
        activeCustom = null
        speciesSet = union
        canonicalSet = emptySet()
        currentRegionId = GROUPS_ID
        Log.i(TAG, "Artgruppen geladen: ${sel.toMap()}")
    }

    /** T232: Arten einer Region bzw. eigenen Liste (ohne Zustand zu aendern); "all" = leer; null = nicht gefunden. */
    private fun resolveList(regionId: String): Pair<Set<String>, CustomSpeciesList?>? {
        if (regionId == "all") return emptySet<String>() to null
        if (regionId.startsWith(CustomSpeciesLists.ID_PREFIX)) {
            return customLists.firstOrNull { it.id == regionId }?.let { it.species to it }
        }
        return try {
            val root = JSONObject(context.assets.open(REGION_SETS_PATH).bufferedReader().use { it.readText() })
            val sets = root.getJSONArray("sets")
            (0 until sets.length()).map { sets.getJSONObject(it) }.firstOrNull { it.getString("id") == regionId }
                ?.let { set ->
                    val arr = set.getJSONArray("species")
                    (0 until arr.length()).map { arr.getString(it) }.toSet() to null
                }
        } catch (e: Exception) {
            Log.w(TAG, "Liste '$regionId' nicht ladbar: ${e.message}")
            null
        }
    }

    /** T218: Geomodel-Maske ersetzen (null = keine Position/Modell → Begleitliste gilt als Ersatz) */
    fun setGeoMask(mask: GeoMask?, combine: GeoCombine) {
        geoMask = mask
        geoCombine = combine
    }

    private fun loadRegionInternal(regionId: String) {
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
                activeCustom = null
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
                    activeCustom = custom
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
                    activeCustom = null
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
    fun isPlausible(scientificName: String): Boolean = isPlausible(scientificName, false, null)

    /**
     * T190: Pruefung mit optionaler Saison der aktiven eigenen Liste.
     * [respectSeason] aus = Saisonangaben ignoriert; [monthDay] = lokales Datum der Detektion.
     */
    fun isPlausible(scientificName: String, respectSeason: Boolean, monthDay: Pair<Int, Int>?): Boolean {
        // T232: Liste je Artgruppe
        groupRules?.let { rules ->
            val key = SpeciesKey.of(scientificName)
            if (!groupPlausible(key, rules, geoMask, TaxonGroups::of)) return false
            val custom = (rules[TaxonGroups.of(key)] as? GroupRule.Listed)?.custom
            if (!respectSeason || custom == null) return true
            return ListSeason.isInSeason(custom.seasons[key], monthDay)
        }
        // Kein Filter aktiv oder "all" gewaehlt; sonst Vergleich ueber kanonischen Schluessel (T105)
        val listOk = matches(canonicalSet, scientificName)
        // T218: mit Geomodel-Maske entscheidet GeoLogic (ohne Maske gilt die Begleitliste als Ersatz)
        val base = if (autoGeo) GeoLogic.plausible(SpeciesKey.of(scientificName), geoMask, listOk, geoCombine) else listOk
        if (!base) return false
        val custom = activeCustom
        if (!respectSeason || custom == null) return true
        return ListSeason.isInSeason(custom.seasons[SpeciesKey.of(scientificName)], monthDay)
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
