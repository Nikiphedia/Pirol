package ch.etasystems.pirol.ml

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** T232: Artgruppen, fuer die je eine Artenliste waehlbar ist. Reihenfolge = Anzeige. */
enum class TaxonGroup(val code: String) {
    BIRDS("birds"),
    ORTHOPTERA("orthoptera"),
    AMPHIBIANS("amphibians"),
    MAMMALS("mammals"),
    /** T295: Fledermaeuse (Chiroptera), getrennt von den uebrigen Saeugetieren; ab Ultraschall-Modellen (T293) */
    BATS("bats"),
    /** uebrige Insekten, Reptilien, Fische, Pflanzen und Geraeuschklassen (Wind, Hund, Motor ...) */
    OTHER("other");

    companion object {
        fun ofCode(code: String?): TaxonGroup? = entries.firstOrNull { it.code == code }
    }
}

/**
 * T232: Zuordnung Art -> Artgruppe. Quellen:
 * 1. Label-CSV der Modelle (Spalten `class`/`order`; Asset-CSV und CSVs in filesDir/models),
 * 2. `region_sets.json` (species_info.taxon_group ist kuratiert und gewinnt; Sets ohne Angabe = Voegel),
 * 3. `species_master.json` (`taxon_group`) fuer Arten ohne CSV-Eintrag (z.B. Fledermaeuse aus "manual");
 *    geliefert vom [SpeciesNameResolver], der die Datei (~10 MB) ohnehin im Hintergrund parst,
 * 4. eigene Listen (Tiergruppe der Liste) fuer sonst unbekannte Arten.
 * species_master ist nicht erste Quelle: bei Insekten fehlt die Ordnung (Heuschrecke vs. Zikade nicht
 * unterscheidbar) und einzelne Arten sind falsch zugeordnet. Unbekannte Arten: Gattung, sonst [TaxonGroup.OTHER].
 */
object TaxonGroups {

    private const val TAG = "TaxonGroups"

    @Volatile
    private var index: Map<String, TaxonGroup> = emptyMap()
    @Volatile
    private var genusIndex: Map<String, TaxonGroup> = emptyMap()
    @Volatile
    private var loaded = false
    /** Ergaenzungen aus species_master und eigenen Listen (nur Arten ohne Eintrag im [index]) */
    @Volatile
    private var extra: Map<String, TaxonGroup> = emptyMap()

    /** Klasse/Ordnung (Label-CSV) -> Gruppe. Reine Logik. */
    fun classify(cls: String?, order: String?): TaxonGroup {
        val c = cls?.trim()?.lowercase().orEmpty()
        val o = order?.trim()?.lowercase().orEmpty()
        return when {
            o == "orthoptera" -> TaxonGroup.ORTHOPTERA
            o == "anura" || c == "amphibia" -> TaxonGroup.AMPHIBIANS
            o == "chiroptera" -> TaxonGroup.BATS
            c == "mammalia" -> TaxonGroup.MAMMALS
            c == "aves" -> TaxonGroup.BIRDS
            else -> TaxonGroup.OTHER
        }
    }

    /**
     * `taxon_group` aus species_master / region_sets -> Gruppe. null = nicht eindeutig
     * ("insecta" ohne Ordnung, leer).
     */
    fun fromTaxonGroup(tg: String?): TaxonGroup? = when (tg?.trim()?.lowercase()) {
        null, "", "insecta" -> null
        "aves" -> TaxonGroup.BIRDS
        "orthoptera" -> TaxonGroup.ORTHOPTERA
        "amphibia", "anura" -> TaxonGroup.AMPHIBIANS
        "chiroptera", "bats" -> TaxonGroup.BATS
        "mammalia" -> TaxonGroup.MAMMALS
        else -> TaxonGroup.OTHER
    }

    /**
     * T295: Fledermausgattungen (Kleinschreibung). Haben Vorrang vor Tabellen, die Fledermaeuse nur als
     * "Mammalia" fuehren (species_master). Nur Gattungen, die ausschliesslich Fledermaeuse enthalten.
     */
    internal val BAT_GENERA: Set<String> = setOf(
        "myotis", "pipistrellus", "nyctalus", "eptesicus", "plecotus", "barbastella", "rhinolophus",
        "vespertilio", "hypsugo", "miniopterus", "tadarida", "nyctinomus", "otonycteris",
        "pteropus", "rousettus", "taphozous", "murina", "kerivoula", "lasiurus", "antrozous", "corynorhinus"
    )

    /** Gruppe einer Art (Name oder Schluessel). Ohne Eintrag: Gattung (nur eindeutige), sonst OTHER. */
    fun of(name: String): TaxonGroup {
        val key = SpeciesKey.of(name)
        if (key.substringBefore('_', "") in BAT_GENERA) return TaxonGroup.BATS
        index[key]?.let { return it }
        extra[key]?.let { return it }
        val genus = key.substringBefore('_', "")
        if (genus.isNotEmpty()) genusIndex[genus]?.let { return it }
        return TaxonGroup.OTHER
    }

    /** Tabelle direkt setzen (Tests); Schluessel werden normalisiert. */
    internal fun setIndex(map: Map<String, TaxonGroup>) {
        val idx = map.mapKeys { SpeciesKey.of(it.key) }
        index = idx
        extra = emptyMap()
        genusIndex = buildGenusIndex(idx)
        loaded = true
    }

    /** Gattung -> Gruppe, nur wenn alle Arten der Gattung in derselben Gruppe sind. */
    internal fun buildGenusIndex(idx: Map<String, TaxonGroup>): Map<String, TaxonGroup> {
        val out = HashMap<String, TaxonGroup?>()
        for ((k, g) in idx) {
            val genus = k.substringBefore('_', "")
            if (genus.isEmpty()) continue
            if (!out.containsKey(genus)) out[genus] = g else if (out[genus] != g) out[genus] = null
        }
        @Suppress("UNCHECKED_CAST")
        return out.filterValues { it != null } as Map<String, TaxonGroup>
    }

    /**
     * Label-CSV parsen (Trenner `;` oder `,`, Kopfzeile mit sci_name/scientific_name, class, order).
     * Ohne class-Spalte leeres Ergebnis.
     */
    internal fun parseLabelCsv(text: String): Map<String, TaxonGroup> {
        val lines = text.removePrefix("\uFEFF").lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyMap()
        val sep = if (lines[0].count { it == ';' } >= lines[0].count { it == ',' }) ';' else ','
        val head = lines[0].split(sep).map { it.trim().lowercase() }
        val iName = head.indexOfFirst { it == "sci_name" || it == "scientific_name" || it == "scientific name" }
        val iClass = head.indexOf("class")
        val iOrder = head.indexOf("order")
        if (iName < 0 || iClass < 0) return emptyMap()
        val out = HashMap<String, TaxonGroup>(lines.size * 2)
        for (line in lines.drop(1)) {
            val f = line.split(sep)
            val name = f.getOrNull(iName)?.trim().orEmpty()
            if (name.isEmpty()) continue
            out[SpeciesKey.of(name)] = classify(f.getOrNull(iClass), if (iOrder >= 0) f.getOrNull(iOrder) else null)
        }
        return out
    }

    /** region_sets.json: Arten der Sets; species_info.taxon_group vor Set-taxon_group, Sets ohne Angabe = Voegel. */
    internal fun parseRegionSets(text: String): Map<String, TaxonGroup> {
        val out = HashMap<String, TaxonGroup>()
        val sets = Json.parseToJsonElement(text).jsonObject["sets"]?.jsonArray ?: return out
        for (s in sets) {
            val o = s.jsonObject
            val setGroup = fromTaxonGroup(o["taxon_group"]?.jsonPrimitive?.content ?: "aves")
            val info = o["species_info"] as? JsonObject
            for (sp in o["species"]?.jsonArray.orEmpty()) {
                val name = sp.jsonPrimitive.content
                val g = fromTaxonGroup((info?.get(name) as? JsonObject)?.get("taxon_group")?.jsonPrimitive?.content)
                    ?: setGroup ?: continue
                out[SpeciesKey.of(name)] = g
            }
        }
        return out
    }

    /**
     * T248: Zuordnung neu einlesen, nachdem eine Label-CSV heruntergeladen oder importiert wurde
     * (sonst zaehlt sie erst nach einem Neustart). Der alte Stand gilt bis zum Austausch.
     */
    fun reload(context: Context) {
        synchronized(this) { loaded = false }
        load(context)
    }

    /** Laedt die Zuordnung einmalig (IO, idempotent). */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            SpeciesKey.load(context)
            val idx = HashMap<String, TaxonGroup>(24_000)
            fun addMissing(m: Map<String, TaxonGroup>) = m.forEach { (k, v) -> idx.putIfAbsent(k, v) }
            try {
                // 1. Label-CSVs (Asset + heruntergeladene/importierte Modelle)
                addMissing(parseLabelCsv(context.assets.open("models/birdnet_v3_labels.csv").bufferedReader().use { it.readText() }))
                File(context.filesDir, "models").listFiles { f -> f.name.endsWith(".csv", ignoreCase = true) }
                    ?.forEach { f -> runCatching { addMissing(parseLabelCsv(f.readText())) } }
                // 2. region_sets
                runCatching {
                    // species_info der Listen ist kuratiert -> ueberschreibt
                    parseRegionSets(context.assets.open("regions/region_sets.json").bufferedReader().use { it.readText() })
                        .forEach { (k, v) -> if (v != TaxonGroup.BIRDS || k !in idx) idx[k] = v }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Artgruppen nicht vollstaendig ladbar: ${e.message}")
            }
            index = idx
            genusIndex = buildGenusIndex(idx)
            loaded = true
            Log.i(TAG, "Artgruppen geladen: ${idx.size} Arten, " +
                TaxonGroup.entries.joinToString { g -> "${g.code}=${idx.values.count { it == g }}" })
        }
    }

    /** species_master: Name -> taxon_group (nur eindeutige Gruppen, nur Arten ohne Eintrag). */
    fun addMasterGroups(groups: Map<String, String>) {
        addExtra(groups.mapNotNull { (n, tg) -> fromTaxonGroup(tg)?.let { SpeciesKey.of(n) to it } }.toMap())
    }

    /** Eigene Listen: Arten ohne Zuordnung bekommen die Tiergruppe der Liste (T190 v4). */
    fun addFromCustomLists(lists: List<CustomSpeciesList>) {
        val m = HashMap<String, TaxonGroup>()
        for (l in lists) {
            val g = fromTaxonGroup(l.taxonGroup) ?: continue
            for (k in l.canonical()) m.putIfAbsent(k, g)
        }
        addExtra(m)
    }

    @Synchronized
    private fun addExtra(m: Map<String, TaxonGroup>) {
        if (m.isEmpty()) return
        val e = HashMap(extra)
        m.forEach { (k, g) -> e.putIfAbsent(k, g) }
        extra = e
    }
}

/** T232: Quelle einer Artgruppe. Gespeichert als String: "off", "all", "auto_geo" oder eine Region-ID. */
object GroupSource {
    const val OFF = "off"
    const val ALL = "all"
    /** Geomodel (Kennung aus T218 beibehalten, steht so in Einstellungen und session.json) */
    const val AUTO = "auto_geo"
    fun isList(source: String) = source != OFF && source != ALL && source != AUTO
}

/**
 * T232: Wahl der Quelle je Artgruppe. Fehlende Gruppen gelten als [GroupSource.OFF].
 */
data class SpeciesGroupSelection(val sources: Map<TaxonGroup, String>) {

    /**
     * Fehlende Gruppe = Aus; Ausnahme T295: Fledermaeuse (gab es in aelteren Staenden nicht) = Alle.
     * Auto gibt es fuer Fledermaeuse nicht (das Geomodel kennt keine): gespeichertes Auto gilt als Alle.
     */
    fun sourceOf(g: TaxonGroup): String {
        val v = sources[g] ?: return if (g == TaxonGroup.BATS) GroupSource.ALL else GroupSource.OFF
        return if (g == TaxonGroup.BATS && v == GroupSource.AUTO) GroupSource.ALL else v
    }

    fun with(g: TaxonGroup, source: String) = SpeciesGroupSelection(sources + (g to source))

    /** Wird das Geomodel gebraucht? */
    fun usesAuto(): Boolean = TaxonGroup.entries.any { sourceOf(it) == GroupSource.AUTO }

    /** Fuer session.json (Schluessel = Gruppen-Code) */
    fun toMap(): Map<String, String> = TaxonGroup.entries.associate { it.code to sourceOf(it) }

    fun encode(): String = toMap().entries.joinToString("\n") { "${it.key}=${it.value}" }

    companion object {
        /** Standard bei Neuinstallation (der Einrichtungsassistent ueberschreibt) */
        val DEFAULT = SpeciesGroupSelection(mapOf(TaxonGroup.BIRDS to GroupSource.AUTO))

        /** T239: aus der Gruppen-Map einer session.json (Code -> Quelle); unbekannte Codes werden ignoriert */
        fun fromMap(m: Map<String, String>): SpeciesGroupSelection = SpeciesGroupSelection(
            m.mapNotNull { (c, v) -> TaxonGroup.ofCode(c)?.let { it to v } }.toMap()
        )

        fun decode(text: String?): SpeciesGroupSelection? {
            if (text.isNullOrBlank()) return null
            val m = text.lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) return@mapNotNull null
                val g = TaxonGroup.ofCode(line.substring(0, i).trim()) ?: return@mapNotNull null
                val v = line.substring(i + 1).trim()
                if (v.isEmpty()) null else g to v
            }.toMap()
            return SpeciesGroupSelection(m)
        }

        /**
         * Kurzanzeige fuer die Live-Statuszeile, z.B. "V:Auto H:CH A:CH S:–".
         * [letters] = Gruppenkuerzel, [sourceLabel] = Kurzname einer Quelle. "Andere" nur, wenn nicht Aus.
         */
        fun shortLabel(
            sel: SpeciesGroupSelection,
            letters: Map<TaxonGroup, String>,
            sourceLabel: (String) -> String
        ): String = TaxonGroup.entries
            .filter { it != TaxonGroup.OTHER || sel.sourceOf(it) != GroupSource.OFF }
            .joinToString(" ") { g -> "${letters[g] ?: g.code}:${sourceLabel(sel.sourceOf(g))}" }
    }
}

/**
 * T232: Regel einer Artgruppe (reine Logik). [allows] prueft den kanonischen Schluessel.
 */
sealed class GroupRule {
    abstract fun allows(key: String, mask: GeoMask?): Boolean

    object Off : GroupRule() {
        override fun allows(key: String, mask: GeoMask?) = false
    }

    object All : GroupRule() {
        override fun allows(key: String, mask: GeoMask?) = true
    }

    /**
     * Geomodel; T239: gilt nur fuer Arten, die das Geomodel kennt. Ohne Maske (keine Position/kein Modell)
     * und fuer dem Geomodel unbekannte Arten gilt die Ersatzliste der Gruppe (leer = alle).
     */
    class Auto(val fallback: Set<String>) : GroupRule() {
        override fun allows(key: String, mask: GeoMask?) =
            if (mask != null && key in mask.known) key in mask.allowed
            else fallback.isEmpty() || key in fallback
    }

    /** Liste (leer bzw. nicht gefunden = kein Filter, wie bisher) */
    class Listed(val canonical: Set<String>, val custom: CustomSpeciesList? = null) : GroupRule() {
        override fun allows(key: String, mask: GeoMask?) = canonical.isEmpty() || key in canonical
    }
}
