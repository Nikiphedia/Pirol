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
    val fileName: String
) {
    /** Kanonische Schluessel (Synonyme aufgeloest) fuer den Vergleich */
    fun canonical(): Set<String> = species.map { SpeciesKey.of(it) }.toSet()
}

/** Ergebnis des Parsens einer Listendatei: gueltige Listen und/oder Fehlertext. */
data class CustomListParseResult(
    val lists: List<CustomSpeciesList>,
    val error: String?
)

/** T129: Reine Parser-Logik fuer `lists/NAME.json` (ohne Android-Abhaengigkeit, testbar). */
object CustomSpeciesLists {

    const val ID_PREFIX = "list:"
    const val FOLDER_NAME = "lists"
    const val EXAMPLE_FILE_NAME = "beispiel_liste.json"

    /** Beispieldatei (region_sets-v3-Format, ein Set). */
    val EXAMPLE_CONTENT: String = """{
  "id": "beispiel",
  "name_de": "Beispielliste",
  "name_en": "Example list",
  "species": [
    "Turdus_merula",
    "Parus_major",
    "Erithacus_rubecula"
  ]
}
"""

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
        var firstError: String? = null
        for ((idx, obj) in setElements.withIndex()) {
            val speciesEl = obj["species"] as? JsonArray
            if (speciesEl == null) {
                if (firstError == null) firstError = "Feld \"species\" fehlt"
                continue
            }
            val species = speciesEl.mapNotNull { it.str()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .map { it.replace(' ', '_') }.toSet()
            if (species.isEmpty()) {
                if (firstError == null) firstError = "Artenliste leer"
                continue
            }
            val rawId = obj["id"].str()?.takeIf { it.isNotBlank() }
                ?: if (setElements.size == 1) base else "$base-${idx + 1}"
            val nameDe = obj["name_de"].str()?.takeIf { it.isNotBlank() } ?: rawId
            val nameEn = obj["name_en"].str()?.takeIf { it.isNotBlank() } ?: nameDe
            lists.add(CustomSpeciesList(ID_PREFIX + rawId.trim(), nameDe, nameEn, species, fileName))
        }
        return if (lists.isEmpty()) CustomListParseResult(emptyList(), firstError ?: "keine Liste gefunden")
        else CustomListParseResult(lists, null)
    }

    /** Pruefung gegen die Liste ueber SpeciesKey (Aliase). */
    fun matches(list: CustomSpeciesList, scientificName: String): Boolean =
        RegionalSpeciesFilter.matches(list.canonical(), scientificName)
}
