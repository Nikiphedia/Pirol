package ch.etasystems.pirol.ml

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Regionaler Artenfilter — laedt regionale Artenlisten aus assets/regions/region_sets.json.
 *
 * Format identisch mit AMSEL Desktop (region_sets.json v3).
 * Artennamen verwenden Unterstrich-Konvention: "Turdus_merula" (BirdNET-Standard).
 * T238: Zustand als Schnappschuss ([FilterState]); Laden ist IO und laeuft nicht auf dem Main-Thread.
 */
class RegionalSpeciesFilter(
    private val context: Context,
    private val pirolFolder: PirolFolder? = null,
    /** T190: erhaelt Anzeigenamen aus eigenen Listen (Rueckfall fuer Arten ohne species_master-Eintrag) */
    private val speciesNames: SpeciesNameResolver? = null,
    /** T218: Begleitliste und Verknuepfung fuer "auto_geo" */
    private val appPreferences: ch.etasystems.pirol.data.AppPreferences? = null,
    /** T238: Inhalt von region_sets.json (Tests); null = Asset */
    private val regionSetsText: (() -> String)? = null
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
            TaxonGroup.BIRDS -> birdsFallbackId(birdsFallback)
            TaxonGroup.ORTHOPTERA, TaxonGroup.AMPHIBIANS -> "ch_orthoptera_anura"
            TaxonGroup.MAMMALS, TaxonGroup.OTHER -> "all"
        }

        /** T239: Ersatzliste der Voegel: eine Heuschrecken-/Amphibienliste ist dafuer nie gueltig (Altlast der Migration) -> CH+ */
        internal fun birdsFallbackId(pref: String?): String =
            if (pref.isNullOrBlank() || pref == "ch_orthoptera_anura") "ch_all" else pref

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

    // T238: gesamter Filterzustand als unveraenderlicher Schnappschuss. Leser (Inferenz-Thread) lesen
    // genau eine Referenz, Schreiber tauschen sie unter [lock] (Laden, Geomodel-Maske). Vorher einzeln
    // @Volatile gesetzte Felder konnten waehrend eines Ladevorgangs einen gemischten Zustand zeigen.
    @Volatile
    private var state = FilterState()

    /** T238: serialisiert Laden und Maskenwechsel (reentrant, da refreshCustomLists neu laedt) */
    private val lock = Any()

    /** T238: region_sets.json nur einmal parsen (Metadaten + Arten je Set) */
    private val regionSets: RegionSets by lazy { parseRegionSets() }

    // T129: eigene Listen aus <Ordner>/lists/NAME.json
    @Volatile
    private var customLists: List<CustomSpeciesList> = emptyList()
    @Volatile
    private var customWarnings: List<String> = emptyList()

    @Volatile
    private var listsSignature: String? = null

    /** T238: aktueller Schnappschuss (Statistik, Tests) */
    internal fun snapshot(): FilterState = state

    /**
     * T238: Assets vorab laden (App-Start auf IO): Alias-Tabelle, Artgruppen-Zuordnung, region_sets.
     * Danach parst kein Aufruf auf dem Main-Thread mehr.
     */
    fun warmUp() {
        SpeciesKey.load(context)
        TaxonGroups.load(context)
        regionSets
    }

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
    fun refreshCustomLists(): Unit = synchronized(lock) {
        readCustomLists()
        // aktive eigene Liste neu laden, falls sie sich geaendert hat
        val s = state
        if (s.groupRules != null) { loadRegion(GROUPS_ID); return }  // T232
        val cur = if (s.autoGeo) appPreferences?.geoCompanionRegion else s.regionId
        if (cur != null && cur.startsWith(CustomSpeciesLists.ID_PREFIX)) loadRegion(if (s.autoGeo) GeoLogic.REGION_ID else cur)
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
     * Aktiviert das Set mit der gegebenen Region-ID (IO: liest Assets bzw. eigene Listen).
     * T238: unter [lock]; der neue Zustand wird als Ganzes getauscht.
     *
     * @param regionId z.B. "ch_breeding", "ch_all", "central_europe", "all"
     */
    fun loadRegion(regionId: String = "ch_breeding"): Unit = synchronized(lock) {
        val cur = state
        state = when (regionId) {
            // T232: Maske bleibt (haengt nur von Position/Woche ab), Regeln aus den Einstellungen
            GROUPS_ID -> buildGroups(cur).also {
                Log.i(TAG, "Artgruppen geladen: ${(appPreferences?.speciesGroups ?: SpeciesGroupSelection.DEFAULT).toMap()}")
            }
            // T218: "auto_geo" = Begleitliste laden (Ersatz/Verknuepfung), Geomodel-Maske kommt vom Controller
            GeoLogic.REGION_ID -> {
                val base = cur.copy(groupRules = null, autoGeo = true, geoCombine = GeoCombine.parse(appPreferences?.geoCombine))
                loadRegionInternal(base, appPreferences?.geoCompanionRegion ?: "ch_all").copy(regionId = GeoLogic.REGION_ID)
            }
            else -> loadRegionInternal(cur.copy(groupRules = null, autoGeo = false, geoMask = null), regionId)
        }
    }

    /**
     * T239: Gruppenregeln aus einer vorgegebenen Wahl (Rescan mit der Gruppenwahl der Session) statt aus den
     * Einstellungen laden (IO). Maske/Verknuepfung bleiben.
     */
    fun loadGroups(selection: SpeciesGroupSelection): Unit = synchronized(lock) {
        state = buildGroups(state, selection)
    }

    /**
     * T239: Kaltstart — wartet, bis ein laufendes Laden fertig ist; ist noch nie eine Region geladen worden,
     * laedt es die gewaehlte (IO). Danach ist der Filter nie "leer" (= alles plausibel).
     */
    fun ensureLoaded(): Unit = synchronized(lock) {
        if (state.regionId == null) loadRegion(appPreferences?.regionFilter ?: GROUPS_ID)
    }

    /** T239: ist schon eine Region geladen? (schneller Pfad ohne Sperre) */
    fun isLoaded(): Boolean = state.regionId != null

    /** T218: Ist der Geomodel-Filter aktiv (Region "auto_geo" oder eine Artgruppe auf Auto, T232)? */
    fun isAutoGeo(): Boolean = state.isAutoGeo()

    /**
     * T238: Arten der Gruppenwahl aus den Einstellungen, ohne den aktiven Filter zu aendern
     * (Vorschlagsliste der Einstellungen). IO.
     */
    fun previewGroupSpecies(): Set<String> = synchronized(lock) { buildGroups(state).species }

    /** T232: Regeln je Artgruppe aus [AppPreferences.speciesGroups] aufbauen (IO); Maske/Verknuepfung aus [cur]. */
    private fun buildGroups(cur: FilterState, selection: SpeciesGroupSelection? = null): FilterState {
        SpeciesKey.load(context)
        TaxonGroups.load(context)
        val sel = selection ?: appPreferences?.speciesGroups ?: SpeciesGroupSelection.DEFAULT
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
        return cur.copy(
            groupRules = rules,
            autoGeo = false,
            custom = null,
            species = union,
            canonical = emptySet(),
            regionId = GROUPS_ID
        )
    }

    /** T232: Arten einer Region bzw. eigenen Liste (ohne Zustand zu aendern); "all" = leer; null = nicht gefunden. */
    private fun resolveList(regionId: String): Pair<Set<String>, CustomSpeciesList?>? {
        if (regionId == "all") return emptySet<String>() to null
        if (regionId.startsWith(CustomSpeciesLists.ID_PREFIX)) {
            return customLists.firstOrNull { it.id == regionId }?.let { it.species to it }
        }
        val species = regionSets.species[regionId]
        if (species == null) Log.w(TAG, "Liste '$regionId' nicht ladbar: nicht in region_sets.json")
        return species?.let { it to null }
    }

    /** T218: Geomodel-Maske ersetzen (null = keine Position/Modell → Begleitliste gilt als Ersatz) */
    fun setGeoMask(mask: GeoMask?, combine: GeoCombine): Unit = synchronized(lock) {
        state = state.copy(geoMask = mask, geoCombine = combine)
    }

    /** Liste [regionId] in [base] uebernehmen; nicht gefunden = [base] (Listenfelder unveraendert, wie bisher). */
    private fun loadRegionInternal(base: FilterState, regionId: String): FilterState {
        SpeciesKey.load(context)
        try {
            // "all" = kein Filter (leere species-Liste)
            if (regionId == "all") {
                Log.i(TAG, "Region 'all' aktiviert — kein Artenfilter")
                return base.copy(species = emptySet(), canonical = emptySet(), regionId = "all", custom = null)
            }

            // T129: eigene Liste (T238: nur einlesen, nicht rekursiv neu laden)
            if (regionId.startsWith(CustomSpeciesLists.ID_PREFIX)) {
                if (customLists.isEmpty()) readCustomLists()
                val custom = customLists.firstOrNull { it.id == regionId }
                if (custom == null) {
                    Log.w(TAG, "Eigene Liste '$regionId' nicht gefunden")
                    return base
                }
                Log.i(TAG, "Eigene Liste '$regionId' geladen: ${custom.species.size} Arten")
                return base.copy(species = custom.species, canonical = custom.canonical(), custom = custom, regionId = regionId)
            }

            // Set mit passender ID (T238: aus dem Cache statt erneutem Parsen)
            val species = regionSets.species[regionId]
            if (species == null) {
                Log.w(TAG, "Region '$regionId' nicht gefunden in region_sets.json")
                return base
            }
            Log.i(TAG, "Region '$regionId' geladen: ${species.size} Arten")
            return base.copy(species = species, canonical = canonicalize(species), custom = null, regionId = regionId)
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Laden der Region '$regionId': ${e.message}")
            return base
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
     * T238: liest genau einen Schnappschuss.
     */
    fun isPlausible(scientificName: String, respectSeason: Boolean, monthDay: Pair<Int, Int>?): Boolean =
        state.isPlausible(scientificName, respectSeason, monthDay)

    /** Alle Arten der aktuell geladenen Region */
    fun getSpeciesList(): Set<String> = state.species

    /** Aktuell geladene Region-ID (null wenn keine geladen) */
    fun getCurrentRegionId(): String? = state.regionId

    /** Alle verfuegbaren Region-Sets */
    fun getAvailableRegions(): List<RegionInfo> = regionSets.infos + customLists.map {
        RegionInfo(it.id, it.nameDe, it.nameEn, it.species.size, isCustom = true)
    }

    /** T238: region_sets.json einmal lesen; Fehler = leer (Asset fehlt dauerhaft). */
    private fun parseRegionSets(): RegionSets {
        try {
            val jsonText = regionSetsText?.invoke()
                ?: context.assets.open(REGION_SETS_PATH).bufferedReader().use { it.readText() }
            // T238: kotlinx statt org.json (wie TaxonGroups.parseRegionSets, im JVM-Test lauffaehig)
            val setsArray = Json.parseToJsonElement(jsonText).jsonObject["sets"]!!.jsonArray
            val infos = mutableListOf<RegionInfo>()
            val species = HashMap<String, Set<String>>()

            for (el in setsArray) {
                val set = el.jsonObject
                val id = set["id"]!!.jsonPrimitive.content
                val nameDe = set["name_de"]?.jsonPrimitive?.content ?: id
                val nameEn = set["name_en"]?.jsonPrimitive?.content ?: id
                val arr = set["species"]!!.jsonArray
                val names = LinkedHashSet<String>(arr.size * 2)
                for (sp in arr) names.add(sp.jsonPrimitive.content)
                infos.add(RegionInfo(id = id, nameDe = nameDe, nameEn = nameEn, speciesCount = arr.size))
                // erstes Set mit dieser ID gilt (wie die fruehere lineare Suche)
                species.putIfAbsent(id, names)
            }

            Log.i(TAG, "Region-Sets geladen: ${infos.size} Sets " +
                    "(${infos.joinToString { "${it.id}:${it.speciesCount}" }})")
            return RegionSets(infos, species)
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim Parsen von region_sets.json: ${e.message}")
            return RegionSets(emptyList(), emptyMap())
        }
    }

    private class RegionSets(val infos: List<RegionInfo>, val species: Map<String, Set<String>>)
}

/**
 * T238: unveraenderlicher Filterzustand (ein Schnappschuss je Pruefung).
 * [groupRules] != null = Liste je Artgruppe (T232); sonst Einzelliste, ggf. mit Geomodel ([autoGeo], T218).
 */
internal data class FilterState(
    val regionId: String? = null,
    val species: Set<String> = emptySet(),
    /** T105: kanonische Schluessel der aktiven Region (Synonyme aufgeloest) fuer den Vergleich */
    val canonical: Set<String> = emptySet(),
    /** T190: aktive eigene Liste (fuer Saison), null bei Asset-Region */
    val custom: CustomSpeciesList? = null,
    val autoGeo: Boolean = false,
    val geoMask: GeoMask? = null,
    val geoCombine: GeoCombine = GeoCombine.GEO_ONLY,
    val groupRules: Map<TaxonGroup, GroupRule>? = null
) {
    fun isAutoGeo(): Boolean = autoGeo || groupRules?.values?.any { it is GroupRule.Auto } == true

    fun isPlausible(scientificName: String, respectSeason: Boolean, monthDay: Pair<Int, Int>?): Boolean {
        // T232: Liste je Artgruppe
        groupRules?.let { rules ->
            val key = SpeciesKey.of(scientificName)
            if (!RegionalSpeciesFilter.groupPlausible(key, rules, geoMask, TaxonGroups::of)) return false
            val c = (rules[TaxonGroups.of(key)] as? GroupRule.Listed)?.custom
            if (!respectSeason || c == null) return true
            return ListSeason.isInSeason(c.seasons[key], monthDay)
        }
        // Kein Filter aktiv oder "all" gewaehlt; sonst Vergleich ueber kanonischen Schluessel (T105)
        val listOk = RegionalSpeciesFilter.matches(canonical, scientificName)
        // T218: mit Geomodel-Maske entscheidet GeoLogic (ohne Maske gilt die Begleitliste als Ersatz)
        val base = if (autoGeo) GeoLogic.plausible(SpeciesKey.of(scientificName), geoMask, listOk, geoCombine) else listOk
        if (!base) return false
        val c = custom
        if (!respectSeason || c == null) return true
        return ListSeason.isInSeason(c.seasons[SpeciesKey.of(scientificName)], monthDay)
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
