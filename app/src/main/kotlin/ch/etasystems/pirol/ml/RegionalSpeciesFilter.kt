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
    /** T232/T246: Gruppenwahl und Ersatzliste der Voegel */
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

        /**
         * T232: Kennung des Gruppenfilters in InferenceConfig.regionFilter und session.json `regionFilter`
         * (T246: einziger Filterweg; null = kein Filter).
         */
        const val GROUPS_ID = "groups"

        /** T232: Ersatzliste einer Auto-Gruppe ohne Position/Geomodel (Voegel: Einstellung, leer = alle) */
        internal fun autoFallbackId(g: TaxonGroup, birdsFallback: String?): String = when (g) {
            TaxonGroup.BIRDS -> birdsFallbackId(birdsFallback)
            TaxonGroup.ORTHOPTERA, TaxonGroup.AMPHIBIANS -> "ch_orthoptera_anura"
            TaxonGroup.MAMMALS, TaxonGroup.OTHER -> "all"
        }

        /** T246: Ersatzlisten der Gruppen auf Auto (Gruppen-Code -> Liste) fuer session.json */
        fun autoFallbacks(sel: SpeciesGroupSelection, birdsFallback: String?): Map<String, String> =
            TaxonGroup.entries.filter { sel.sourceOf(it) == GroupSource.AUTO }
                .associate { it.code to autoFallbackId(it, birdsFallback) }

        /** T239: Ersatzliste der Voegel: eine Heuschrecken-/Amphibienliste ist dafuer nie gueltig -> CH+ */
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
        // T246: geladene Gruppenwahl neu aufbauen (eigene Listen koennen sich geaendert haben)
        if (state.groupRules != null) loadGroups()
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
     * T246: Gruppenregeln laden (IO), aus [selection] (Rescan mit der Gruppenwahl der Session) oder,
     * wenn null, aus den Einstellungen. Die Geomodel-Maske bleibt (haengt nur von Position/Woche ab).
     * T238: unter [lock]; der neue Zustand wird als Ganzes getauscht.
     */
    fun loadGroups(selection: SpeciesGroupSelection? = null): Unit = synchronized(lock) {
        val sel = selection ?: currentSelection()
        state = buildGroups(state, sel)
        Log.i(TAG, "Artgruppen geladen: ${sel.toMap()}")
    }

    /**
     * T239: Kaltstart — wartet, bis ein laufendes Laden fertig ist; sind noch nie Gruppen geladen worden,
     * laedt es die Gruppenwahl der Einstellungen (IO). Danach ist der Filter nie "leer" (= alles plausibel).
     */
    fun ensureLoaded(): Unit = synchronized(lock) {
        if (state.groupRules == null) loadGroups()
    }

    /** T239: sind die Gruppen schon geladen? (schneller Pfad ohne Sperre) */
    fun isLoaded(): Boolean = state.groupRules != null

    /** T218/T232: Steht eine Artgruppe auf Auto (Geomodel)? */
    fun isAutoGeo(): Boolean = state.isAutoGeo()

    /**
     * T238: Arten der Gruppenwahl aus den Einstellungen, ohne den aktiven Filter zu aendern
     * (Vorschlagsliste der Einstellungen). IO.
     */
    fun previewGroupSpecies(): Set<String> = synchronized(lock) { buildGroups(state, currentSelection()).species }

    private fun currentSelection(): SpeciesGroupSelection = appPreferences?.speciesGroups ?: SpeciesGroupSelection.DEFAULT

    /** T232: Regeln je Artgruppe aus [sel] aufbauen (IO); Maske aus [cur]. */
    private fun buildGroups(cur: FilterState, sel: SpeciesGroupSelection): FilterState {
        SpeciesKey.load(context)
        TaxonGroups.load(context)
        val needsCustom = TaxonGroup.entries.any { sel.sourceOf(it).startsWith(CustomSpeciesLists.ID_PREFIX) }
        if (needsCustom && customLists.isEmpty()) readCustomLists()
        val birdsFallback = appPreferences?.groupFallbackBirds
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
        return cur.copy(groupRules = rules, species = union)
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

    /** T218: Geomodel-Maske ersetzen (null = keine Position/Modell → Ersatzliste je Auto-Gruppe) */
    fun setGeoMask(mask: GeoMask?): Unit = synchronized(lock) {
        state = state.copy(geoMask = mask)
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

    /** Alle Arten der Listen der geladenen Gruppenwahl (Auto: Ersatzliste) */
    fun getSpeciesList(): Set<String> = state.species

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
 * T246: nur noch Liste je Artgruppe (T232); [groupRules] == null = noch nicht geladen (kein Filter).
 */
internal data class FilterState(
    /** Arten aller Listen der Gruppenwahl (Anzeige, Vorschlagsliste) */
    val species: Set<String> = emptySet(),
    val geoMask: GeoMask? = null,
    val groupRules: Map<TaxonGroup, GroupRule>? = null
) {
    fun isAutoGeo(): Boolean = groupRules?.values?.any { it is GroupRule.Auto } == true

    fun isPlausible(scientificName: String, respectSeason: Boolean, monthDay: Pair<Int, Int>?): Boolean {
        val rules = groupRules ?: return true
        val key = SpeciesKey.of(scientificName)
        if (!RegionalSpeciesFilter.groupPlausible(key, rules, geoMask, TaxonGroups::of)) return false
        val c = (rules[TaxonGroups.of(key)] as? GroupRule.Listed)?.custom
        if (!respectSeason || c == null) return true
        return ListSeason.isInSeason(c.seasons[key], monthDay)
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
