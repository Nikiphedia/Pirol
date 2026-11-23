package ch.etasystems.pirol.data.repository

import kotlinx.serialization.json.Json

/**
 * T251 (N7): Einzige Stelle fuer die JSON-Konfigurationen der Session-Dateien.
 * - [compact]: JSONL-Zeilen (detections.jsonl, verifications.jsonl, Zusatzdatei); Format fuer AMSEL unveraendert
 * - [lenient]: Lesen (unbekannte Felder ignorieren)
 * - [pretty]: session.json
 */
object SessionJson {
    val compact: Json = Json { prettyPrint = false }
    val lenient: Json = Json { ignoreUnknownKeys = true }
    val pretty: Json = Json { prettyPrint = true }
}
