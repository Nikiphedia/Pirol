package ch.etasystems.pirol.ml

/**
 * T287: Artnamen gespeicherter Detektionen beim LESEN neu aufloesen (Anzeige, Karte, Raven-Export).
 *
 * detections.jsonl traegt den beim Erfassen aufgeloesten `commonName`. Alte Sessions koennen dort den
 * englischen Modellnamen haben (Arten ohne Landesnamen im exakten species_master-Eintrag). Die Datei
 * wird nicht umgeschrieben (AMSEL-Format); stattdessen gilt beim Lesen der Name aus dem
 * wissenschaftlichen Namen in der aktuellen Artnamen-Sprache.
 *
 * Registrierung beim App-Start ([PirolApp]); ohne Resolver (Unit-Tests) bleibt der gespeicherte Name.
 */
object StoredSpeciesNames {

    @Volatile
    var resolver: SpeciesNameResolver? = null

    /** Name fuer eine gespeicherte Detektion; [stored] = commonName aus detections.jsonl */
    fun of(scientificName: String, stored: String): String =
        resolver?.storedName(scientificName, stored) ?: stored
}
