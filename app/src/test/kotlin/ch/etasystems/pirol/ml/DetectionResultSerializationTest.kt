package ch.etasystems.pirol.ml

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Serialisierungs-Tests fuer DetectionResult und VerificationEvent.
 * Prueft Backward-Kompatibilitaet (V0.0.6-JSONL ohne neue Felder) und
 * Forward-Kompatibilitaet (V0.0.7-JSONL mit allen neuen Feldern).
 */
class DetectionResultSerializationTest {

    // Lenient-Parser wie in SessionManager (ignoreUnknownKeys = true)
    private val jsonLenient = Json { ignoreUnknownKeys = true }

    // Strikter Parser fuer Round-trip-Tests
    private val jsonStrict = Json { encodeDefaults = true }

    // ── DetectionResult ──────────────────────────────────────────────────────

    /**
     * AC2: Alte JSONL-Zeile (V0.0.6, ohne Cluster-C-Felder) parst ohne Exception.
     * count → 1, atlasCode/note/voiceMemoFile → null, manualEntry/lockSpecies → false.
     */
    @Test
    fun `old v006 jsonl without cluster c fields deserializes with correct defaults`() {
        val oldLine = """{"id":"abc","scientificName":"Turdus merula","commonName":"Amsel",""" +
            """"confidence":0.9,"timestampMs":1000,"chunkStartSec":0.0,"chunkEndSec":3.0,""" +
            """"sampleRate":48000}"""

        val result = jsonLenient.decodeFromString<DetectionResult>(oldLine)

        assertEquals(1, result.count)
        assertNull(result.atlasCode)
        assertNull(result.note)
        assertNull(result.voiceMemoFile)
        assertEquals(false, result.manualEntry)
        assertEquals(false, result.lockSpecies)
        // Bestandsfelder unveraendert
        assertEquals("Turdus merula", result.scientificName)
        assertEquals(0.9f, result.confidence, 0.001f)
    }

    /**
     * AC3: Neue JSONL-Zeile (V0.0.7, alle Cluster-C-Felder) parst korrekt.
     */
    @Test
    fun `new v007 jsonl with all cluster c fields deserializes correctly`() {
        val newLine = """{"id":"xyz","scientificName":"Parus major","commonName":"Kohlmeise",""" +
            """"confidence":0.85,"timestampMs":2000,"chunkStartSec":3.0,"chunkEndSec":6.0,""" +
            """"sampleRate":48000,"count":3,"atlasCode":"B4","note":"Gesang","voiceMemoFile":"memos/det_xyz.wav",""" +
            """"manualEntry":false,"lockSpecies":true}"""

        val result = jsonLenient.decodeFromString<DetectionResult>(newLine)

        assertEquals(3, result.count)
        assertEquals("B4", result.atlasCode)
        assertEquals("Gesang", result.note)
        assertEquals("memos/det_xyz.wav", result.voiceMemoFile)
        assertEquals(false, result.manualEntry)
        assertEquals(true, result.lockSpecies)
        assertEquals("Parus major", result.scientificName)
    }

    /**
     * AC4: Round-trip Serialize → Deserialize ist identitaetserhaltend.
     */
    @Test
    fun `round trip serialize deserialize is identity`() {
        val original = DetectionResult(
            id = "round-trip-id",
            scientificName = "Sylvia atricapilla",
            commonName = "Moenchsgrasmuecke",
            confidence = 0.75f,
            timestampMs = 9999L,
            chunkStartSec = 6.0f,
            chunkEndSec = 9.0f,
            sampleRate = 48000,
            count = 2,
            atlasCode = "A2",
            note = "Testnotiz",
            voiceMemoFile = "memos/det_round.wav",
            manualEntry = true,
            lockSpecies = false
        )

        val json = jsonStrict.encodeToString(DetectionResult.serializer(), original)
        val restored = jsonStrict.decodeFromString<DetectionResult>(json)

        assertEquals(original, restored)
    }

    // ── VerificationEvent ────────────────────────────────────────────────────

    /**
     * AC5: Altes VerificationEvent ohne actionType (V0.0.6) → actionType == null.
     */
    @Test
    fun `old verification event without actionType defaults to null`() {
        val oldLine = """{"detectionId":"abc","status":"CONFIRMED","verifiedAtMs":12345}"""

        val event = jsonLenient.decodeFromString<VerificationEvent>(oldLine)

        assertNull(event.actionType)
        assertEquals("abc", event.detectionId)
        assertEquals(VerificationStatus.CONFIRMED, event.status)
    }

    /**
     * AC5: Neues VerificationEvent mit actionType parst korrekt.
     */
    @Test
    fun `new verification event with actionType deserializes correctly`() {
        val newLine = """{"detectionId":"xyz","status":"CONFIRMED","verifiedAtMs":99999,""" +
            """"actionType":"COUNT_CHANGED"}"""

        val event = jsonLenient.decodeFromString<VerificationEvent>(newLine)

        assertEquals(VerificationEvent.ACTION_COUNT_CHANGED, event.actionType)
        assertEquals(VerificationStatus.CONFIRMED, event.status)
    }

    /**
     * Prueft dass alle 15 actionType-Konstanten korrekt definiert und unterschiedlich sind.
     * (AP-Tippfehler: "12 insgesamt" aber explizite Liste: 6 alt + 5 C + 4 B/D = 15)
     */
    @Test
    fun `all 15 actionType constants are defined and unique`() {
        val constants = listOf(
            VerificationEvent.ACTION_CONFIRMED,
            VerificationEvent.ACTION_REJECTED,
            VerificationEvent.ACTION_UNCERTAIN,
            VerificationEvent.ACTION_CORRECTED,
            VerificationEvent.ACTION_REPLACED,
            VerificationEvent.ACTION_UNDO,
            VerificationEvent.ACTION_COUNT_CHANGED,
            VerificationEvent.ACTION_ATLAS_SET,
            VerificationEvent.ACTION_NOTE_ADDED,
            VerificationEvent.ACTION_MEMO_RECORDED,
            VerificationEvent.ACTION_MEMO_DELETED,
            VerificationEvent.ACTION_MANUAL_ADD,
            VerificationEvent.ACTION_SPECIES_LOCKED,
            VerificationEvent.ACTION_ALTERNATIVE_CONFIRMED,
            VerificationEvent.ACTION_POSITION_MOVED
        )
        // Alle 15 Konstanten muessen eindeutig sein
        assertEquals("Nicht alle actionType-Konstanten sind eindeutig", 15, constants.size)
        assertEquals("Nicht alle actionType-Konstanten sind eindeutig", constants.size, constants.toSet().size)
    }
}
