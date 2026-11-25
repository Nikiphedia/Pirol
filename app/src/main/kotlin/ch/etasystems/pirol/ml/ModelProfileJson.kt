package ch.etasystems.pirol.ml

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * T151a: Profil-JSON `pirol-model/1` (Vertrag in Arbeitspaket_T151.md) — Parser, Pflichtfeld-Pruefung,
 * Umwandlung in [ModelProfile] und Pruefung der ONNX-Ein-/Ausgaenge.
 *
 * Ohne Android- und ORT-Abhaengigkeit (unit-testbar): die ORT-Seite liefert nur Namen, Formen und
 * Ausgangsgroessen an [checkIo].
 */
object ModelProfileJson {

    const val SCHEMA = "pirol-model/1"
    const val FILE_SUFFIX = ".pirol.json"

    private val ID_REGEX = Regex("[a-z0-9_]+")
    private val TAXON_REGEX = Regex("[a-z0-9_]+")
    private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
    private val TRANSFORMS = setOf("none", "sigmoid", "softmax")
    private val ROLES = setOf("scores", "aux", "embedding")
    private val DELIMITERS = mapOf("," to ',', ";" to ';', "\t" to '\t')

    // Unbekannte Felder werden laut Vertrag ignoriert
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    // ---- DTOs (1:1 zum JSON) ----

    @Serializable
    data class Spec(
        val schema: String? = null,
        val id: String? = null,
        val name: String? = null,
        val family: String? = null,
        val runtime: String? = null,
        val modelFile: String? = null,
        val modelSha256: String? = null,
        val license: String? = null,
        val input: InputSpec? = null,
        val outputs: List<OutputSpec>? = null,
        val labels: Map<String, LabelSpec>? = null,
        // T293: Artgruppe und Anzeige (alle optional)
        val taxonGroup: String? = null,
        val display: DisplaySpec? = null
    )

    /** T293: Anzeigehinweise; Auswertung in T294/T295 */
    @Serializable
    data class DisplaySpec(
        val freqRangeHz: List<Double>? = null,
        val playbackTimeExpansion: Int? = null
    )

    @Serializable
    data class InputSpec(
        val name: String? = null,
        val sampleRate: Int? = null,
        val windowSec: Double? = null,
        val layout: String? = null,
        // T293: kleinere Quellen werden nicht hochgesampelt
        val minSourceRate: Int? = null
    )

    @Serializable
    data class OutputSpec(
        val name: String? = null,
        val role: String? = null,
        val transform: String? = null,
        val labels: String? = null,
        // T156: nur am scores-Ausgang gueltig, 0.0..1.0
        val minConfidence: Double? = null,
        // T293: nur am scores-Ausgang gueltig
        val ignoreLabels: List<String>? = null
    )

    @Serializable
    data class LabelSpec(
        val file: String? = null,
        val sha256: String? = null,
        val delimiter: String? = null,
        val header: Boolean? = null,
        // Name (String) oder 0-basierter Index (Zahl)
        val scientificColumn: JsonElement? = null,
        val commonColumn: JsonElement? = null
    )

    /** Fehlerart fuer die UI (je eine Meldung de/en), [detail] bleibt technisch/sprachneutral */
    enum class ErrorCode {
        /** JSON unlesbar oder Pflichtfeld fehlt/ungueltig */
        SCHEMA,
        /** runtime != "onnx" */
        RUNTIME,
        /** im Profil genannte Datei wurde nicht mit ausgewaehlt */
        MISSING_FILE,
        /** SHA-256 stimmt nicht */
        CHECKSUM,
        /** Label-Datei unlesbar */
        LABELS,
        /** ONNX-Session laesst sich nicht oeffnen */
        ORT_OPEN,
        /** Ein-/Ausgaenge passen nicht zum Profil */
        IO_MISMATCH,
        /** id kollidiert mit einem eingebauten Profil */
        ID_CONFLICT
    }

    class ProfileError(val code: ErrorCode, val detail: String) : Exception("$code: $detail")

    /** Validiertes Profil: Spec mit garantiert gesetzten Pflichtfeldern */
    class Parsed(val spec: Spec, val profile: ModelProfile) {
        val modelFile: String get() = spec.modelFile!!
        val modelSha256: String get() = spec.modelSha256!!.lowercase()
        val input: InputSpec get() = spec.input!!
        val outputs: List<OutputSpec> get() = spec.outputs!!
        val labels: Map<String, LabelSpec> get() = spec.labels.orEmpty()
        val scoresOutput: OutputSpec get() = outputs.single { it.role == "scores" }
        /** Labels, die von Ausgaengen referenziert werden (Schluessel → Spec) */
        val usedLabels: Map<String, LabelSpec>
            get() = outputs.mapNotNull { it.labels }.distinct().associateWith { labels.getValue(it) }
        val windowSamples: Int get() = profile.windowSamples
    }

    /**
     * JSON lesen und gegen den Vertrag pruefen.
     * @throws ProfileError bei Formfehlern
     */
    fun parse(text: String): Parsed {
        val spec = try {
            json.decodeFromString(Spec.serializer(), text.trimStart('\uFEFF'))
        } catch (e: Exception) {
            throw ProfileError(ErrorCode.SCHEMA, "JSON: ${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName}")
        }
        validate(spec)
        return Parsed(spec, toProfile(spec))
    }

    private fun fail(detail: String): Nothing = throw ProfileError(ErrorCode.SCHEMA, detail)

    private fun validate(s: Spec) {
        if (s.schema != SCHEMA) fail("schema muss \"$SCHEMA\" sein (ist ${s.schema})")
        val id = s.id ?: fail("id fehlt")
        if (!ID_REGEX.matches(id)) fail("id \"$id\" erlaubt nur [a-z0-9_]")
        if (s.name.isNullOrBlank()) fail("name fehlt")
        if (s.runtime.isNullOrBlank()) fail("runtime fehlt")
        if (s.runtime != "onnx") throw ProfileError(ErrorCode.RUNTIME, "runtime \"${s.runtime}\" (nur \"onnx\")")
        val modelFile = s.modelFile ?: fail("modelFile fehlt")
        if (!isPlainFileName(modelFile) || !modelFile.endsWith(".onnx")) fail("modelFile \"$modelFile\" ungueltig")
        if (s.modelSha256 == null || !SHA256_REGEX.matches(s.modelSha256)) fail("modelSha256 fehlt oder ist kein SHA-256")
        if (s.license.isNullOrBlank()) fail("license fehlt")

        val input = s.input ?: fail("input fehlt")
        if (input.name.isNullOrBlank()) fail("input.name fehlt")
        val sr = input.sampleRate ?: fail("input.sampleRate fehlt")
        if (sr !in 1_000..384_000) fail("input.sampleRate $sr ausserhalb 1000..384000")
        val win = input.windowSec ?: fail("input.windowSec fehlt")
        if (!(win > 0.0 && win <= 60.0)) fail("input.windowSec $win ausserhalb 0..60")
        if (input.layout != "waveform") fail("input.layout \"${input.layout}\" (nur \"waveform\")")

        val outputs = s.outputs ?: fail("outputs fehlt")
        val labels = s.labels.orEmpty()
        for ((i, o) in outputs.withIndex()) {
            if (o.name.isNullOrBlank()) fail("outputs[$i].name fehlt")
            if (o.role !in ROLES) fail("outputs[$i].role \"${o.role}\" unbekannt")
            if (o.role != "embedding") {
                if ((o.transform ?: "none") !in TRANSFORMS) fail("outputs[$i].transform \"${o.transform}\" unbekannt")
                val key = o.labels ?: fail("outputs[$i].labels fehlt")
                if (key !in labels) fail("outputs[$i].labels \"$key\" nicht in labels")
            }
        }
        input.minSourceRate?.let { if (it !in 1_000..2_000_000) fail("input.minSourceRate $it ausserhalb 1000..2000000") }
        s.taxonGroup?.let { if (!TAXON_REGEX.matches(it)) fail("taxonGroup \"$it\" erlaubt nur [a-z0-9_]") }
        s.display?.freqRangeHz?.let {
            if (it.size != 2 || !(it[0] >= 0.0 && it[0] < it[1] && it[1] <= 1_000_000.0)) {
                fail("display.freqRangeHz muss [tief, hoch] mit 0 <= tief < hoch <= 1000000 sein")
            }
        }
        s.display?.playbackTimeExpansion?.let { if (it !in 1..100) fail("display.playbackTimeExpansion $it ausserhalb 1..100") }
        outputs.forEachIndexed { i, o ->
            if (o.ignoreLabels != null) {
                if (o.role != "scores") fail("outputs[$i].ignoreLabels nur am scores-Ausgang")
                if (o.ignoreLabels.any { it.isBlank() }) fail("outputs[$i].ignoreLabels enthaelt leeren Namen")
            }
        }
        outputs.firstOrNull { it.role == "scores" }?.minConfidence?.let {
            if (!(it >= 0.0 && it <= 1.0)) fail("minConfidence $it ausserhalb 0..1")
        }
        if (outputs.map { it.name }.toSet().size != outputs.size) fail("Ausgangsnamen doppelt")
        val scores = outputs.count { it.role == "scores" }
        if (scores != 1) fail("genau ein Ausgang mit role \"scores\" noetig (sind $scores)")
        if (outputs.count { it.role == "embedding" } > 1) fail("hoechstens ein Embedding-Ausgang")

        for ((key, l) in labels) {
            val file = l.file ?: fail("labels.$key.file fehlt")
            if (!isPlainFileName(file)) fail("labels.$key.file \"$file\" ungueltig")
            if (l.sha256 == null || !SHA256_REGEX.matches(l.sha256)) fail("labels.$key.sha256 fehlt oder ist kein SHA-256")
            if ((l.delimiter ?: ",") !in DELIMITERS) fail("labels.$key.delimiter \"${l.delimiter}\" (nur , ; \\t)")
            val sci = column(l.scientificColumn, "labels.$key.scientificColumn")
                ?: fail("labels.$key.scientificColumn fehlt")
            val com = column(l.commonColumn, "labels.$key.commonColumn")
            val header = l.header ?: true
            if (!header && (sci.name != null || com?.name != null)) fail("labels.$key: Spaltenname ohne Kopfzeile")
        }
    }

    /** Nur ein Dateiname, kein Pfad (Ablage in filesDir/models/) */
    private fun isPlainFileName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\') && name != "." && name != ".." &&
            !name.startsWith(".")

    private fun column(e: JsonElement?, field: String): ModelProfile.Column? {
        if (e == null) return null
        val p = e as? JsonPrimitive ?: fail("$field muss Name oder Index sein")
        if (p.isString) return ModelProfile.Column(name = p.content.ifBlank { fail("$field leer") })
        val idx = p.intOrNull ?: fail("$field muss Name oder Index sein")
        if (idx < 0) fail("$field negativ")
        return ModelProfile.Column(index = idx)
    }

    internal fun genericLabels(l: LabelSpec): ModelProfile.GenericLabels =
        ModelProfile.GenericLabels(
            delimiter = DELIMITERS.getValue(l.delimiter ?: ","),
            header = l.header ?: true,
            scientificColumn = column(l.scientificColumn, "scientificColumn")!!,
            commonColumn = column(l.commonColumn, "commonColumn")
        )

    /** Fensterlaenge in Samples: round(sampleRate * windowSec) */
    fun windowSamples(sampleRate: Int, windowSec: Double): Int = kotlin.math.round(sampleRate * windowSec).toInt()

    /** Validierte Spec → [ModelProfile] (source = IMPORTED). speciesCount wird beim Import gesetzt. */
    private fun toProfile(s: Spec): ModelProfile {
        val input = s.input!!
        val outputs = s.outputs!!
        val scores = outputs.single { it.role == "scores" }
        val embedding = outputs.firstOrNull { it.role == "embedding" }
        val labelSpec = s.labels!!.getValue(scores.labels!!)
        val transform = when (scores.transform ?: "none") {
            "sigmoid" -> ModelProfile.ScoreTransform.SIGMOID
            "softmax" -> ModelProfile.ScoreTransform.SOFTMAX
            else -> ModelProfile.ScoreTransform.NONE
        }
        return ModelProfile(
            id = s.id!!,
            name = s.name!!,
            family = s.family ?: s.id,
            downloadUrl = null,
            checksum = "sha256:" + s.modelSha256!!.lowercase(),
            fileName = s.modelFile!!,
            sizeBytes = null,  // keine Groessen-Erkennung fuer importierte Modelle
            sampleRate = input.sampleRate!!,
            windowSamples = windowSamples(input.sampleRate, input.windowSec!!),
            labelFormat = ModelProfile.LabelFormat.GENERIC_CSV,
            labelFileName = labelSpec.file,
            labelChecksum = "sha256:" + labelSpec.sha256!!.lowercase(),
            scoreOutputName = scores.name,
            scoreOutputIndex = 0,
            embeddingOutputName = embedding?.name,
            embeddingOutputIndex = -1,  // kein Index-Rueckfall: ohne Namen kein Embedding
            scoreTransform = transform,
            speciesCount = 0,
            licenseNote = s.license!!,
            embeddingDbFileName = "embeddings_${s.id}.bsed",
            runtime = s.runtime!!,
            inputName = input.name,
            genericLabels = genericLabels(labelSpec),
            auxOutputNames = outputs.filter { it.role == "aux" }.map { it.name!! },
            source = ModelProfile.Source.IMPORTED,
            minConfidence = scores.minConfidence?.toFloat(),
            minSourceRate = input.minSourceRate,
            taxonGroup = s.taxonGroup,
            freqRangeHz = s.display?.freqRangeHz?.let { it[0].toInt() to it[1].toInt() },
            playbackTimeExpansion = s.display?.playbackTimeExpansion,
            ignoreLabels = scores.ignoreLabels.orEmpty().mapTo(HashSet()) { ModelProfile.normalizeLabel(it) }
        )
    }

    /**
     * Ein-/Ausgaenge des geladenen Modells gegen das Profil pruefen.
     *
     * @param inputShapes Eingangsname → Form laut Modell (-1 = dynamisch)
     * @param outputSizes Ausgangsname → Anzahl Werte (aus einem Probelauf mit [Parsed.windowSamples] Nullen)
     * @param labelCounts Label-Schluessel → Anzahl Zeilen
     * @throws ProfileError (IO_MISMATCH) beim ersten Widerspruch
     */
    fun checkIo(
        parsed: Parsed,
        inputShapes: Map<String, LongArray>,
        outputSizes: Map<String, Int>,
        labelCounts: Map<String, Int>
    ) {
        fun mismatch(d: String): Nothing = throw ProfileError(ErrorCode.IO_MISMATCH, d)
        val inName = parsed.input.name!!
        val shape = inputShapes[inName] ?: mismatch("Eingang \"$inName\" fehlt (Modell: ${inputShapes.keys})")
        val n = parsed.windowSamples.toLong()
        val ok = when (shape.size) {
            1 -> shape[0] == n || shape[0] < 0
            2 -> (shape[0] == 1L || shape[0] < 0) && (shape[1] == n || shape[1] < 0)
            else -> false
        }
        if (!ok) mismatch("Eingang \"$inName\" hat Form ${shape.toList()}, erwartet [1, $n] oder [$n]")

        for (o in parsed.outputs) {
            val name = o.name!!
            val size = outputSizes[name] ?: mismatch("Ausgang \"$name\" fehlt (Modell: ${outputSizes.keys})")
            val key = o.labels
            if (key != null) {
                val count = labelCounts[key] ?: mismatch("Labels \"$key\" nicht geladen")
                if (count != size) mismatch("Ausgang \"$name\" hat $size Werte, Labels \"$key\" $count Zeilen")
            } else if (size <= 0) {
                mismatch("Ausgang \"$name\" ist leer")
            }
        }
    }
}
