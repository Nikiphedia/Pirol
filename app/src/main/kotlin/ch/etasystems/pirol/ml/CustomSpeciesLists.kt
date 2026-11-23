package ch.etasystems.pirol.ml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** T129: Eigene Artenliste (ein Set im region_sets.json-v3-Format). */
data class CustomSpeciesList(
    /** Region-ID mit Prefix "list:" (kollisionsfrei zu den Asset-Sets) */
    val id: String,
    val nameDe: String,
    val nameEn: String,
    val species: Set<String>,
    val fileName: String,
    /** T190 (v4): Tiergruppe der Liste, Default "aves" */
    val taxonGroup: String = "aves",
    /** T190 (v4): Saisonzeitraeume je Art (Schluessel = SpeciesKey); fehlend = ganzjaehrig */
    val seasons: Map<String, List<SeasonPeriod>> = emptyMap(),
    /** T190 (v4): Anzeigenamen aus species_info (Schluessel = Artname mit Unterstrich), nur Rueckfall */
    val commonNames: Map<String, String> = emptyMap(),
    /** T190 (v4): Kopfdaten, nur zur Anzeige */
    val author: String? = null,
    val description: String? = null
) {
    /** Kanonische Schluessel (Synonyme aufgeloest) fuer den Vergleich */
    fun canonical(): Set<String> = species.map { SpeciesKey.of(it) }.toSet()

    /** T190: hat die Liste Saisonangaben? */
    fun hasSeasons(): Boolean = seasons.isNotEmpty()
}

/** Ergebnis des Parsens einer Listendatei: gueltige Listen und/oder Fehlertext. */
data class CustomListParseResult(
    val lists: List<CustomSpeciesList>,
    val error: String?,
    /** T190: nicht fatale Hinweise (ungueltige Saison, Unterart auf Art abgebildet ...) */
    val warnings: List<String> = emptyList()
)

/** T129: Reine Parser-Logik fuer `lists/NAME.json` (ohne Android-Abhaengigkeit, testbar). */
object CustomSpeciesLists {

    const val ID_PREFIX = "list:"
    const val FOLDER_NAME = "lists"
    const val EXAMPLE_FILE_NAME = "beispiel_liste.json"

    /** Beispieldatei (Format v4, siehe tools/SPECIES_LIST_FORMAT.md): Saison-Beispiel und eine Heuschrecke. */
    val EXAMPLE_CONTENT: String = """{
  "version": 4,
  "generator": "PIROL",
  "description": "Beispielliste",
  "display_language": "de",
  "taxonomy": { "aves": { "reference": "IOC", "version": "15.1" } },
  "id": "beispiel",
  "name_de": "Beispielliste",
  "name_en": "Example list",
  "taxon_group": "aves",
  "species": [
    "Chorthippus_biguttulus",
    "Erithacus_rubecula",
    "Oriolus_oriolus",
    "Parus_major",
    "Turdus_merula"
  ],
  "species_info": {
    "Chorthippus_biguttulus": {
      "common_name": "Nachtigall-Grashüpfer",
      "taxon_group": "orthoptera",
      "season": [{ "from": "07", "to": "10" }]
    },
    "Oriolus_oriolus": {
      "common_name": "Pirol",
      "season": [{ "from": "04-20", "to": "08-31" }]
    }
  }
}
"""

    /** Artname normalisieren: trimmen, Leerzeichen -> Unterstrich, Unterart (Trinomen) auf die Art abbilden. */
    internal fun normalizeName(raw: String): String {
        val parts = raw.trim().replace(' ', '_').split('_').filter { it.isNotEmpty() }
        return if (parts.size > 2) parts.take(2).joinToString("_") else parts.joinToString("_")
    }

    private fun JsonElement?.str(): String? =
        this?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    /**
     * Akzeptiert ein einzelnes Set `{"id","name_de","name_en","species":[...]}`
     * oder eine Datei mit `{"sets":[...]}`. Ungueltige Sets einer Mehrfachdatei werden uebersprungen,
     * wenn mindestens eines gueltig ist; sonst `error`.
     */
    fun parse(fileName: String, text: String): CustomListParseResult {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return CustomListParseResult(emptyList(), "kein gueltiges JSON")
        }
        if (root !is JsonObject) return CustomListParseResult(emptyList(), "JSON-Objekt erwartet")

        val setElements: List<JsonObject> = if (root.containsKey("sets")) {
            val arr = root["sets"] as? JsonArray
                ?: return CustomListParseResult(emptyList(), "\"sets\" ist keine Liste")
            arr.mapNotNull { it as? JsonObject }
        } else listOf(root)

        val base = fileName.substringBeforeLast('.')
        val lists = mutableListOf<CustomSpeciesList>()
        val warnings = mutableListOf<String>()
        var firstError: String? = null
        val fileVersion = (root["version"].str()?.toIntOrNull()) ?: 3
        if (fileVersion > 4) warnings.add("neuere Formatversion $fileVersion")
        val headAuthor = root["author"].str()
        val headDescription = root["description"].str()
        for ((idx, obj) in setElements.withIndex()) {
            val speciesEl = obj["species"] as? JsonArray
            if (speciesEl == null) {
                if (firstError == null) firstError = "Feld \"species\" fehlt"
                continue
            }
            val rawNames = speciesEl.mapNotNull { it.str()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            // T190: Unterarten (Trinomen) auf die Art abbilden, einfachste Variante (Modelle kennen nur Arten)
            val species = rawNames.map { normalizeName(it) }.toSet()
            val subspecies = rawNames.count { it.trim().replace(' ', '_').split('_').size > 2 }
            if (subspecies > 0) warnings.add("$fileName: $subspecies Unterart(en) auf Art abgebildet")
            if (species.isEmpty()) {
                if (firstError == null) firstError = "Artenliste leer"
                continue
            }
            val rawId = obj["id"].str()?.takeIf { it.isNotBlank() }
                ?: if (setElements.size == 1) base else "$base-${idx + 1}"
            val nameDe = obj["name_de"].str()?.takeIf { it.isNotBlank() } ?: rawId
            val nameEn = obj["name_en"].str()?.takeIf { it.isNotBlank() } ?: nameDe
            val seasons = mutableMapOf<String, List<SeasonPeriod>>()
            val commonNames = mutableMapOf<String, String>()
            val info = obj["species_info"] as? JsonObject
            val speciesKeys = species.map { SpeciesKey.of(it) }.toSet()
            val seenInfo = mutableSetOf<String>()
            info?.forEach { (rawKey, el) ->
                val name = normalizeName(rawKey)
                val key = SpeciesKey.of(name)
                if (key !in speciesKeys) { warnings.add("$fileName: species_info ohne Eintrag in species: $rawKey"); return@forEach }
                if (!seenInfo.add(key)) { warnings.add("$fileName: doppelter species_info-Eintrag: $rawKey"); return@forEach }
                val entry = el as? JsonObject ?: return@forEach
                entry["common_name"].str()?.takeIf { it.isNotBlank() }?.let { commonNames[name] = it.trim() }
                val seasonEl = entry["season"] as? JsonArray ?: return@forEach
                val periods = seasonEl.mapNotNull { p ->
                    val po = p as? JsonObject
                    ListSeason.parsePeriod(po?.get("from").str(), po?.get("to").str())
                        .also { if (it == null) warnings.add("$fileName: ungueltige Saison bei $rawKey") }
                }
                // nur gueltige Zeitraeume zaehlen; keiner gueltig = ganzjaehrig (nie still ausfiltern)
                if (periods.isNotEmpty()) seasons[key] = periods
            }
            lists.add(
                CustomSpeciesList(
                    ID_PREFIX + rawId.trim(), nameDe, nameEn, species, fileName,
                    taxonGroup = obj["taxon_group"].str()?.takeIf { it.isNotBlank() } ?: "aves",
                    seasons = seasons, commonNames = commonNames,
                    author = headAuthor, description = headDescription
                )
            )
        }
        return if (lists.isEmpty()) CustomListParseResult(emptyList(), firstError ?: "keine Liste gefunden", warnings)
        else CustomListParseResult(lists, null, warnings)
    }

    /**
     * T136: Aenderungs-Signatur aus (Name, lastModified, Groesse) aller Listendateien.
     * Gleiche Signatur = nichts neu einlesen. Reihenfolge-unabhaengig.
     */
    fun signature(files: List<Triple<String, Long, Long>>): String =
        files.sortedBy { it.first }.joinToString("|") { "${it.first}:${it.second}:${it.third}" }

    /** Pruefung gegen die Liste ueber SpeciesKey (Aliase). */
    fun matches(list: CustomSpeciesList, scientificName: String): Boolean =
        RegionalSpeciesFilter.matches(list.canonical(), scientificName)

    /**
     * T190: Pruefung inkl. Saison. [respectSeason] aus: Saisonangaben werden ignoriert.
     * [monthDay] = lokales Kalenderdatum der Detektion (null = Saison nicht auswerten).
     */
    fun isPlausible(list: CustomSpeciesList, scientificName: String, respectSeason: Boolean, monthDay: Pair<Int, Int>?): Boolean {
        if (!matches(list, scientificName)) return false
        if (!respectSeason) return true
        return ListSeason.isInSeason(list.seasons[SpeciesKey.of(scientificName)], monthDay)
    }
}
