package ch.etasystems.pirol.ml

import android.content.Context
import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            "ca" to "Català",
            "nl" to "Nederlands",
            "pl" to "Polski",
            "ru" to "Русский",
            "ja" to "日本語",
            "zh" to "中文"
        )
    }

    // Lookup: scientificName (Unterstrich) → Map<langCode, commonName>
    // T95: wird im Hintergrund aufgebaut und dann als Ganzes zugewiesen (Leser sehen leer oder komplett)
    @Volatile private var namesMap: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var loaded = false
    private val loadMutex = Mutex()

    /** Aktuelle Sprache (Default: Deutsch) */
    var language: String = "de"

    /**
     * species_master.json laden und Index aufbauen.
     * Wird einmalig beim App-Start aufgerufen.
     *
     * T95: Idempotent und nebenlaeufig aufrufbar — weitere Aufrufer warten, bis der erste
     * Ladevorgang fertig ist. Ist bereits geladen, kehrt der Aufruf sofort zurueck.
     */
    suspend fun load() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) { loadFromAssets() }
        }
    }

    private fun loadFromAssets() {
        try {
            val startMs = SystemClock.elapsedRealtime()
            val jsonText = context.assets.open(SPECIES_PATH).bufferedReader().use { it.readText() }
            val jsonObj = JSONObject(jsonText)
            val taxa = jsonObj.getJSONArray("taxa")
            val map = HashMap<String, Map<String, String>>(taxa.length() * 2)
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
                map[sciName] = langMap
            }
            namesMap = map
            loaded = true
            Log.i(TAG, "T95_STATS load_ms=${SystemClock.elapsedRealtime() - startMs} species=${map.size} lang=$language")
        } catch (e: Exception) {
            Log.e(TAG, "species_master.json laden fehlgeschlagen", e)
            // T95: nicht bei jedem Aufruf erneut versuchen (10-MB-Parse) — Fallback sind wissenschaftliche Namen
            loaded = true
        }
    }

    /**
     * Uebersetzt scientificName in CommonName der gewaehlten Sprache.
     * Fallback: Englisch → scientificName falls nichts gefunden.
     */
    fun resolve(scientificName: String): String {
        val key = scientificName.replace(' ', '_')
        val names = namesMap[key]
        // T103a: Rueckfall auf die Sprachspalten des aktiven Modells (3.0 final), z.B. fuer neue Taxonomie
        val modelNames = modelNamesMap[key]
        return names?.get(language)
            ?: modelNames?.get(language)
            ?: names?.get("en")
            ?: modelNames?.get("en")
            ?: scientificName.replace('_', ' ')
    }

    // T103a: Namen aus der Label-Datei des aktiven Modells (Unterstrich-Schluessel → Sprache → Name)
    @Volatile private var modelNamesMap: Map<String, Map<String, String>> = emptyMap()

    /** T103a: Namen des aktiven Modells setzen (leer = kein Rueckfall) */
    fun setModelNames(names: Map<String, Map<String, String>>) {
        // Speicher sparen: nur Namen behalten, die species_master nicht schon liefert
        val master = namesMap
        modelNamesMap = if (master.isEmpty()) names else names.mapNotNull { (key, langs) ->
            val known = master[key]
            val missing = if (known == null) langs else langs.filterKeys { it !in known }
            if (missing.isEmpty()) null else key to missing
        }.toMap()
        Log.i(TAG, "T103_STATS model_names=${modelNamesMap.size} species")
    }

    /** Alle verfuegbaren Namen fuer eine Art */
    fun getAllNames(scientificName: String): Map<String, String> {
        val key = scientificName.replace(' ', '_')
        return namesMap[key] ?: emptyMap()
    }

    /**
     * Sucht Arten nach Freitext (DE-Name + wissenschaftlicher Name, case-insensitive).
     * Gibt max. 20 Treffer zurueck: List<Pair<scientificName-mit-Leerzeichen, commonName>>.
     * Mindestlaenge der Anfrage: 2 Zeichen (sonst leer).
     */
    fun searchSpecies(query: String): List<Pair<String, String>> {
        if (query.length < 2) return emptyList()
        val q = query.lowercase()
        return namesMap.entries
            .filter { (sciName, names) ->
                sciName.replace('_', ' ').lowercase().contains(q) ||
                names[language]?.lowercase()?.contains(q) == true ||
                names["en"]?.lowercase()?.contains(q) == true
            }
            .map { (sciName, names) ->
                val commonName = names[language] ?: names["en"] ?: sciName.replace('_', ' ')
                sciName.replace('_', ' ') to commonName
            }
            .sortedBy { it.second }
            .take(20)
    }
}
