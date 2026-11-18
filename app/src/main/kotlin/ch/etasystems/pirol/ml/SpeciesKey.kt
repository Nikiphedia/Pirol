package ch.etasystems.pirol.ml

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * T105: Gemeinsamer Artschluessel fuer den Vergleich von Modell-Labels mit Listen
 * (Regionssets, Watchlist).
 *
 * Verschiedene Modelle (BirdNET 2.4, Preview, 3.0 final) und Listen (AMSEL) verwenden fuer
 * dieselbe Art teils unterschiedliche wissenschaftliche Namen. Kanonisch ist der Name aus
 * BirdNET 3.0 final. `assets/species_aliases.json` bildet "alter Name -> kanonischer Name" ab.
 * Die Listen auf dem Datentraeger bleiben unveraendert (AMSEL-Kompatibilitaet); nur der
 * Vergleich laeuft ueber den Schluessel.
 *
 * Der Schluessel ist getrimmt, Leerzeichen -> Unterstrich, Kleinschreibung.
 */
object SpeciesKey {

    private const val TAG = "SpeciesKey"
    private const val ASSET_PATH = "species_aliases.json"

    /** normalisierter alter Name -> normalisierter kanonischer Name */
    @Volatile
    private var aliases: Map<String, String> = emptyMap()

    @Volatile
    private var loaded = false

    /** Normalisierung ohne Alias-Aufloesung. */
    fun normalize(name: String): String = name.trim().replace(' ', '_').lowercase()

    /** Kanonischer Schluessel einer Art (Alias aufgeloest). */
    fun of(name: String): String {
        val n = normalize(name)
        return aliases[n] ?: n
    }

    /** Anzahl geladener Aliase. */
    fun aliasCount(): Int = aliases.size

    /** Laedt die Alias-Tabelle einmalig aus den Assets (idempotent). */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
                setAliases(parse(text))
                Log.i(TAG, "Alias-Tabelle geladen: ${aliases.size} Eintraege")
            } catch (e: Exception) {
                Log.w(TAG, "species_aliases.json nicht ladbar: ${e.message}")
            }
            loaded = true
        }
    }

    /** Parst das JSON-Format {"version":1,"aliases":{"Alt":"Neu",...}}. */
    internal fun parse(jsonText: String): Map<String, String> {
        val obj = Json.parseToJsonElement(jsonText).jsonObject["aliases"]!!.jsonObject
        val result = HashMap<String, String>(obj.size)
        for ((k, v) in obj) result[normalize(k)] = normalize(v.jsonPrimitive.content)
        return result
    }

    /** Setzt die Tabelle direkt (Tests). Eintraege muessen nicht normalisiert sein. */
    internal fun setAliases(map: Map<String, String>) {
        aliases = map.entries.associate { normalize(it.key) to normalize(it.value) }
        loaded = true
    }
}
