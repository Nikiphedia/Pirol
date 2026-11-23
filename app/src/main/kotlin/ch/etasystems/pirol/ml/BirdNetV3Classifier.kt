package ch.etasystems.pirol.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import ch.etasystems.pirol.audio.dsp.AudioResampler
import java.io.File
import java.nio.FloatBuffer

/**
 * BirdNET V3.0 Classifier via ONNX Runtime Mobile.
 *
 * Portiert von AMSEL Desktop (OnnxBirdNetV3), angepasst fuer Android:
 * - Modell aus filesDir/models/ (priorisiert) oder assets/ (Fallback)
 * - Labels aus assets/models/birdnet_v3_labels.csv (Semikolon-CSV, 11'560 Arten)
 * - ONNX Runtime Mobile statt Desktop-Version
 * - Kein paralleles Chunking (einzelne 3s-Chunks vom ViewModel)
 *
 * Input:  FloatArray(96000) = 3 Sekunden @ 32 kHz, normalisiert [-1, 1]
 * Output: FloatArray(numLabels) = Sigmoid-Scores pro Art
 */
class BirdNetV3Classifier(
    private val context: Context,
    private val modelManager: ModelManager,
    // T103a: erhaelt die Sprachspalten der 3.0-final-Labels als Rueckfall fuer Artnamen
    private val speciesNameResolver: SpeciesNameResolver? = null,
    /** T221: festes Modell (Dateiname in filesDir/models, Rescan) statt des aktiven; null = aktives Modell */
    private val fixedModelFile: String? = null
) : AudioClassifier {

    companion object {
        private const val TAG = "BirdNetV3"
        private const val ASSETS_MODEL_PATH = "models/birdnet_v3.onnx"
        private const val INFERENCE_THREADS = 2
        // T103a: Abtastrate, Fensterlaenge und Label-Datei kommen aus dem aktiven ModelProfile

        /**
         * Filtert und sortiert rohe ONNX-Scores zu ClassifierResults.
         *
         * Extrahiert als interne Funktion fuer Unit-Tests ohne ONNX-Session (T71).
         * Wird von classify() nach der Inference aufgerufen.
         *
         * @param scores FloatArray mit Sigmoid-Scores pro Label-Index
         * @param labels Label-Liste (scientificName to commonName)
         * @param topK Maximale Anzahl Ergebnisse
         * @param threshold Mindest-Score fuer Aufnahme in Ergebnisliste
         * @return Sortierte Liste (hoechste Konfidenz zuerst), max. topK Eintraege
         */
        internal fun filterAndRank(
            scores: FloatArray,
            labels: List<Pair<String, String>>,
            topK: Int,
            threshold: Float,
            windowSec: Float = 3.0f
        ): List<AudioClassifier.ClassifierResult> {
            val results = mutableListOf<AudioClassifier.ClassifierResult>()
            for (i in scores.indices) {
                if (scores[i] >= threshold && i < labels.size) {
                    val (sciName, comName) = labels[i]
                    results.add(
                        AudioClassifier.ClassifierResult(
                            scientificName = sciName,
                            commonName = comName,
                            confidence = scores[i],
                            timeStartSec = 0f,
                            timeEndSec = windowSec
                        )
                    )
                }
            }
            return results.sortedByDescending { it.confidence }.take(topK)
        }

        /**
         * T103a: Waehlt den Ausgang laut Profil — zuerst ueber den Namen, sonst ueber den Index.
         * Keine Groessen-Heuristik (die hat beim 12K-Modell das Embedding als Scores gelesen).
         */
        internal fun selectOutputIndex(
            outputNames: List<String>, name: String?, fallbackIndex: Int, aliases: List<String> = emptyList()
        ): Int {
            for (n in listOfNotNull(name) + aliases) {
                val byName = outputNames.indexOf(n)
                if (byName >= 0) return byName
            }
            return fallbackIndex.coerceIn(0, (outputNames.size - 1).coerceAtLeast(0))
        }

        /** T103a: Score-Umrechnung laut Profil (in-place) */
        internal fun applyTransform(scores: FloatArray, transform: ModelProfile.ScoreTransform): FloatArray {
            when (transform) {
                ModelProfile.ScoreTransform.NONE -> {}
                ModelProfile.ScoreTransform.SIGMOID ->
                    for (i in scores.indices) scores[i] = (1.0 / (1.0 + kotlin.math.exp(-scores[i].toDouble()))).toFloat()
                ModelProfile.ScoreTransform.SOFTMAX -> {
                    val max = scores.maxOrNull() ?: return scores
                    var sum = 0.0
                    for (i in scores.indices) {
                        val e = kotlin.math.exp((scores[i] - max).toDouble())
                        scores[i] = e.toFloat()
                        sum += e
                    }
                    if (sum > 0) for (i in scores.indices) scores[i] = (scores[i] / sum).toFloat()
                }
            }
            return scores
        }
    }

    // Lazy-initialisiert beim ersten classify()-Aufruf
    // T187/T238: Session + laufende Inferenzen; schliesst nie waehrend run(), ersetzt nie ohne zu schliessen
    private val sessions = SessionGuard<OrtSession> { it.close() }
    private var sessionFailed = false
    private var labels: List<Pair<String, String>> = emptyList() // (scientific, common)
    @Volatile private var labelNameSet: Set<String> = emptySet()
    @Volatile private var labelNameList: List<String> = emptyList()  // T250/N3: einmal je Labelladen

    // T103a: Profil, mit dem Session und Labels geladen wurden (wechselt nur ueber resetSession)
    @Volatile private var profile: ModelProfile = currentProfile()
    private var labelsProfileId: String? = null
    @Volatile private var labelsMissing = false

    /** Anzahl geladener Labels */
    override val labelCount: Int get() = labels.size

    /** Modell-Name fuer UI */
    override val modelName: String get() = profile.name

    override val modelId: String get() = profile.id

    override val minConfidence: Float? get() = profile.minConfidence

    override val inputSampleRate: Int get() = profile.sampleRate

    // T151a: Fensterlaenge und Embedding laut Profil
    override val windowMs: Int get() = profile.windowMs

    override val hasEmbedding: Boolean get() = profile.hasEmbedding

    override val labelScientificNames: Set<String> get() = labelNameSet

    /**
     * Prueft ob das ONNX-Modell verfuegbar ist.
     * Zuerst aktives Modell via ModelManager, dann assets/ (Fallback).
     */
    override fun isModelAvailable(): Boolean {
        // 1. Aktives Modell via ModelManager
        val activeFile = currentModelFile()
        if (activeFile != null && activeFile.exists()) return true
        // 2. Assets Fallback (T221: nicht bei fest gewaehltem Modell)
        return fixedModelFile == null && isModelAvailableInAssets()
    }

    /**
     * Klassifiziert einen Audio-Chunk.
     *
     * @param samples Audio-Daten, normalisiert [-1, 1]. Wird bei Bedarf auf 32 kHz resampelt.
     * @param sampleRate Samplerate der Input-Daten
     * @param topK Maximale Anzahl Ergebnisse
     * @param threshold Mindest-Confidence (0.0 - 1.0)
     * @return Sortierte Liste der erkannten Arten (hoechste Confidence zuerst)
     */
    override fun classify(
        samples: FloatArray,
        sampleRate: Int,
        topK: Int,
        threshold: Float
    ): List<AudioClassifier.ClassifierResult> =
        classifyWithEmbedding(samples, sampleRate, topK, threshold).results

    /** T250/N3: alle Scores direkt aus dem Ausgang, ohne Ergebnisobjekte und Sortierung */
    override fun scoreAll(samples: FloatArray, sampleRate: Int): AudioClassifier.ScoreTable? {
        if (samples.isEmpty()) return null
        val session = acquireSession() ?: return null
        return try {
            val p = profile
            val resampled = if (sampleRate != p.sampleRate) {
                AudioResampler.resample(samples, sampleRate, p.sampleRate, AudioResampler.defaultMode)
            } else samples
            val result = runInferenceRaw(session, prepareChunk(resampled)) ?: return null
            val scores = result.use { res -> extractPredictions(res, session) }
            val names = labelNameList
            if (names.size != scores.size) null else AudioClassifier.ScoreTable(scores, names)
        } catch (e: Exception) {
            Log.e(TAG, "Score-Abfrage fehlgeschlagen", e)
            null
        } finally {
            endRun()
        }
    }

    /**
     * Klassifiziert einen Audio-Chunk und liefert das Embedding aus DEMSELBEN Modell-Durchlauf (T61).
     * BirdNET V3.0 berechnet Embedding [1,1024] und Predictions [1,N] ohnehin gemeinsam —
     * ein separater extractEmbedding()-Aufruf wuerde das Modell ein zweites Mal ausfuehren.
     *
     * @return Treffer (sortiert, Top-K) + rohes Embedding (nicht L2-normalisiert, null bei Fehler)
     */
    override fun classifyWithEmbedding(
        samples: FloatArray,
        sampleRate: Int,
        topK: Int,
        threshold: Float
    ): AudioClassifier.ClassificationOutput {
        val empty = AudioClassifier.ClassificationOutput(emptyList(), null)
        if (samples.isEmpty()) return empty

        val session = acquireSession()
        if (session == null) {
            Log.w(TAG, "ONNX Session nicht verfuegbar — Modell fehlt oder Ladefehler")
            return empty
        }

        return try {
            // 1. Resample auf 32 kHz falls noetig
            val p = profile
            val resampled = if (sampleRate != p.sampleRate) {
                AudioResampler.resample(samples, sampleRate, p.sampleRate, AudioResampler.defaultMode)
            } else {
                samples
            }

            // 2. Auf exakt CHUNK_SAMPLES bringen (Zero-Padding oder Truncate)
            val chunk = prepareChunk(resampled)

            // 3. ONNX Inference — Result wird geschlossen (sonst nativer Speicherleck pro Inference)
            val result = runInferenceRaw(session, chunk) ?: return empty
            val (scores, embedding) = result.use { res ->
                // T103a: Ausgaenge laut Profil (Preview: embeddings/predictions, 3.0 final: predictions/embeddings_out)
                extractPredictions(res, session) to extractEmbeddingFromResult(res, session)
            }

            // 4. Top-K filtern und sortieren (via extrahierte Companion-Funktion, T71)
            AudioClassifier.ClassificationOutput(
                filterAndRank(scores, labels, topK, threshold, p.windowSec),
                embedding
            )
        } catch (e: Exception) {
            Log.e(TAG, "Klassifizierung fehlgeschlagen", e)
            empty
        } finally {
            endRun()
        }
    }

    /**
     * Extrahiert den Embedding-Vektor aus einem Audio-Chunk.
     * BirdNET V3.0 hat 2 Outputs: embeddings [1,1024] und predictions [1,N].
     * Diese Methode gibt den Embedding-Output (Index 0) als flaches FloatArray zurueck.
     *
     * @param samples Audio-Daten, normalisiert [-1, 1]. Wird bei Bedarf auf 32 kHz resampelt.
     * @param sampleRate Samplerate der Input-Daten
     * @return FloatArray mit Embedding-Vektor (typischerweise 1024-dim), oder null bei Fehler
     */
    override fun extractEmbedding(samples: FloatArray, sampleRate: Int): FloatArray? {
        if (samples.isEmpty()) return null

        val session = acquireSession() ?: run {
            Log.w(TAG, "ONNX Session nicht verfuegbar fuer Embedding-Extraktion")
            return null
        }

        return try {
            // 1. Resample auf 32 kHz falls noetig
            val p = profile
            val resampled = if (sampleRate != p.sampleRate) {
                AudioResampler.resample(samples, sampleRate, p.sampleRate, AudioResampler.defaultMode)
            } else {
                samples
            }

            // 2. Auf exakt CHUNK_SAMPLES bringen
            val chunk = prepareChunk(resampled)

            // 3. ONNX Inference — rohes Result fuer Embedding-Zugriff
            val result = runInferenceRaw(session, chunk) ?: return null

            result.use { res ->
                extractEmbeddingFromResult(res, session)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embedding-Extraktion fehlgeschlagen", e)
            null
        } finally {
            endRun()
        }
    }

    /** Gibt alle geladenen Labels zurueck */
    fun getLabels(): List<Pair<String, String>> {
        ensureSession() // Stellt sicher, dass Labels geladen sind
        return labels
    }

    /**
     * T187: Session freigeben (RAM), aber nur wenn gerade keine Inferenz laeuft.
     * Die Session wird beim naechsten classify() lazy neu geladen. Labels bleiben erhalten.
     * @return true, wenn eine Session geschlossen wurde
     */
    @Synchronized
    override fun releaseIfIdle(): Boolean {
        if (!sessions.releaseIfIdle()) return false
        sessionFailed = false
        Log.i(TAG, "ONNX Session geschlossen (frei)")
        return true
    }

    /** T187: Session holen und Lauf zaehlen (atomar zu releaseIfIdle/resetSession) */
    @Synchronized
    private fun acquireSession(): OrtSession? {
        if (ensureSession() == null) return null
        return sessions.begin()
    }

    /** T238: Lauf beenden; eine waehrenddessen abgegebene Session wird jetzt geschlossen */
    private fun endRun() = sessions.end()

    /** ONNX Session und Ressourcen freigeben (T238: laeuft gerade eine Inferenz, nach deren Ende) */
    @Synchronized
    fun close() {
        val had = sessions.detach()
        sessionFailed = false
        if (had) Log.i(TAG, "ONNX Session geschlossen" + if (sessions.pendingClose > 0) " (nach laufender Inferenz)" else "")
    }

    /**
     * Zuruecksetzen wenn Modell nachtraeglich installiert wurde.
     * Ermoeglicht erneuten Session-Aufbau beim naechsten classify()-Aufruf.
     */
    @Synchronized
    override fun resetSession() {
        // T238: synchronisiert mit ensureSession (sonst konnte eine gerade entstehende Session verwaisen)
        // und nie waehrend einer laufenden Inferenz geschlossen
        sessions.detach()
        sessionFailed = false
        // T103a: neues Profil sofort uebernehmen (modelId/Embedding-DB), Labels beim naechsten Aufruf
        profile = currentProfile()
        Log.i(TAG, "Session zurueckgesetzt — Modell ${profile.id} wird beim naechsten Aufruf neu geladen")
    }

    // --- Private Hilfsfunktionen ---

    /**
     * ONNX Session lazy initialisieren + Labels laden.
     * Modell wird zuerst aus filesDir/models/ geladen (Pfad-basiert, kein readBytes),
     * dann aus assets/ als Fallback.
     */
    @Synchronized
    private fun ensureSession(): OrtSession? {
        sessions.session?.let { return it }
        // T103a: fehlten nur die Labels und sind sie inzwischen da (Nachladen beim Start), neu versuchen
        if (sessionFailed && labelsMissing && modelManager.getLabelFile(profile) != null) {
            sessionFailed = false
            labelsMissing = false
        }
        if (sessionFailed) return null

        return try {
            // T103a: Profil des aktiven Modells uebernehmen (nach resetSession ggf. ein anderes)
            profile = currentProfile()
            // Labels immer laden (auch wenn Modell fehlt — fuer getLabels())
            loadLabels()
            if (labels.isEmpty()) {
                Log.e(TAG, "Keine Labels fuer ${profile.id} — Modell wird nicht geladen")
                labelsMissing = true
                sessionFailed = true
                return null
            }

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // T114: feste Thread-Zahl. Benchmark SM-X236B (T96): 1 Thread 589 ms, 2 Threads 284 ms,
            // 4 Threads 242 ms pro 3-s-Fenster — 2 Threads halbieren die Kernlast bei kaum mehr Zeit (Akku im Feld).
            opts.setIntraOpNumThreads(INFERENCE_THREADS)

            // 1. Aktives Modell via ModelManager (direkter Pfad — memory-mapped)
            val activeFile = currentModelFile()
            if (activeFile != null && activeFile.exists()) {
                Log.i(TAG, "Modell geladen via ModelManager: ${activeFile.absolutePath} " +
                        "(${activeFile.length() / 1_000_000} MB)")
                // T239: SessionOptions nach dem Aufbau schliessen (nativer Rest)
                val session = try { env.createSession(activeFile.absolutePath, opts) } finally { opts.close() }
                sessions.install(session)
                logSessionInfo(session)
                return session
            }

            // 2. Assets Fallback (muss als ByteArray geladen werden)
            if (fixedModelFile != null || !isModelAvailableInAssets()) {
                Log.w(TAG, "Modell nicht gefunden — weder in filesDir noch in assets")
                Log.w(TAG, "Bitte Modell via Onboarding importieren oder in assets/ ablegen")
                opts.close()
                sessionFailed = true
                return null
            }

            val modelBytes = context.assets.open(ASSETS_MODEL_PATH).use { it.readBytes() }
            val session = try { env.createSession(modelBytes, opts) } finally { opts.close() }
            sessions.install(session)
            logSessionInfo(session)
            session
        } catch (e: Exception) {
            Log.e(TAG, "ONNX Session konnte nicht erstellt werden", e)
            sessionFailed = true
            null
        }
    }

    /** T221: Modelldatei — fest gewaehlt (Rescan) oder aktiv laut Einstellungen */
    private fun currentModelFile(): File? =
        if (fixedModelFile == null) modelManager.getActiveModelFile() else modelManager.getModelFile(fixedModelFile)

    /** T221: Profil der [currentModelFile] (ohne Datei: Preview-Profil fuer das Asset-Modell) */
    private fun currentProfile(): ModelProfile {
        if (fixedModelFile == null) return modelManager.getActiveProfile()
        val file = currentModelFile() ?: return ModelProfile.LEGACY_DEFAULT
        return ModelProfile.resolve(file.name, file.length())
    }

    /** Hilfsfunktion: Pruefen ob Modell in assets vorhanden ist */
    private fun isModelAvailableInAssets(): Boolean {
        return try {
            context.assets.open(ASSETS_MODEL_PATH).use { true }
        } catch (_: Exception) {
            false
        }
    }

    /** Session-Info loggen */
    private fun logSessionInfo(session: OrtSession) {
        Log.i(TAG, "${profile.name} geladen (Profil ${profile.id}, ${profile.sampleRate} Hz, ${profile.windowSamples} Samples)")
        Log.i(TAG, "  Input: ${session.inputNames}")
        Log.i(TAG, "  Output: ${session.outputNames}")
        Log.i(TAG, "  Labels: ${labels.size} Arten")
    }

    /**
     * T103a: Labels des aktiven Profils laden — Preview aus assets (Semikolon-CSV),
     * 3.0 final aus filesDir/models/ (Komma-CSV mit Sprachspalten).
     */
    private fun loadLabels() {
        if (labels.isNotEmpty() && labelsProfileId == profile.id) return
        labels = emptyList()
        labelNameSet = emptySet()

        try {
            val p = profile
            val assetPath = p.labelAssetPath
            val text = if (assetPath != null) {
                context.assets.open(assetPath).bufferedReader().use { it.readText() }
            } else {
                val file = modelManager.getLabelFile(p)
                if (file == null) {
                    Log.e(TAG, "Label-Datei fuer ${p.id} fehlt (${p.labelFileName})")
                    return
                }
                file.readText()
            }
            val languages = SpeciesNameResolver.AVAILABLE_LANGUAGES.mapTo(HashSet()) { it.first }
            val parsed = ModelLabelParser.parse(text, p.labelFormat, languages, p.genericLabels)
            labels = parsed.map { it.scientificName to it.commonName }
            labelNameList = parsed.map { it.scientificName }
            labelNameSet = parsed.mapTo(HashSet(parsed.size * 2)) { it.scientificName.replace(' ', '_') }
            labelsProfileId = p.id
            if (labels.size != p.speciesCount) {
                Log.w(TAG, "Label-Anzahl ${labels.size} weicht vom Profil ab (${p.speciesCount})")
            }
            // Sprachspalten als Rueckfall fuer SpeciesNameResolver (Arten ohne Eintrag in species_master)
            // T151a: generische Labels haben nur einen Trivialnamen ohne Sprache → als "en"-Rueckfall,
            // damit species_master (App-Sprache) Vorrang hat und sonst commonColumn statt des wiss. Namens kommt
            val modelNames = if (p.labelFormat == ModelProfile.LabelFormat.GENERIC_CSV) {
                parsed.filter { it.commonName != it.scientificName }
                    .associate { it.scientificName.replace(' ', '_') to mapOf("en" to it.commonName) }
            } else {
                parsed.filter { it.namesByLanguage.isNotEmpty() }
                    .associate { it.scientificName.replace(' ', '_') to it.namesByLanguage }
            }
            speciesNameResolver?.setModelNames(modelNames)

            Log.i(TAG, "Labels geladen: ${labels.size} Arten (${p.labelFormat})")
        } catch (e: Exception) {
            Log.e(TAG, "Labels konnten nicht geladen werden", e)
            labels = emptyList()
        }
    }

    /**
     * Audio-Chunk auf exakt CHUNK_SAMPLES (96000) bringen.
     * - Kuerzer: Zero-Padding am Ende
     * - Laenger: Truncate auf 3 Sekunden
     */
    private fun prepareChunk(input: FloatArray): FloatArray {
        val n = profile.windowSamples
        return when {
            input.size == n -> input
            input.size > n -> input.copyOfRange(0, n)
            else -> {
                // Zero-Padding
                val padded = FloatArray(n)
                input.copyInto(padded)
                padded
            }
        }
    }

    /**
     * Extrahiert die Prediction-Scores aus dem ONNX-Output (T103a: Ausgang laut Profil).
     * Passt die Laenge nicht zu den Labels, wird nichts gemeldet (sonst falsche Arten).
     */
    @Volatile private var loggedOutputFallback = false  // T122

    private fun extractPredictions(result: OrtSession.Result, session: OrtSession): FloatArray {
        val p = profile
        val idx = selectOutputIndex(session.outputNames.toList(), p.scoreOutputName, p.scoreOutputIndex)
        var scores = flattenOutput(result[idx].value) ?: return FloatArray(0)
        // T151a: Rueckfall nur fuer Katalog-Profile; importierte sind beim Import geprueft
        if (scores.size != labels.size && p.source == ModelProfile.Source.IMPORTED) {
            Log.e(TAG, "Score-Ausgang hat ${scores.size} Werte, ${labels.size} Labels (Profil ${p.id})")
            return FloatArray(0)
        }
        if (scores.size != labels.size) {
            // T122: Rueckfall auf den Ausgang mit passender Laenge (Preview 3.1 hat das Layout von 3.0 final,
            // nicht von Preview 3 — feste Indizes im Profil waren falsch → keine Erkennung mehr)
            val match = (0 until session.outputNames.size).firstOrNull { i ->
                flattenOutput(result[i].value)?.size == labels.size
            }
            if (match == null) {
                Log.e(TAG, "Kein Ausgang mit ${labels.size} Werten — Profil ${p.id} passt nicht zum Modell")
                return FloatArray(0)
            }
            if (!loggedOutputFallback) {
                loggedOutputFallback = true
                Log.w(TAG, "T122 Score-Ausgang per Laenge gewaehlt: [$match] statt [$idx] (Profil ${p.id})")
            }
            scores = flattenOutput(result[match].value) ?: return FloatArray(0)
        }
        return applyTransform(scores, p.scoreTransform)
    }

    /**
     * Konvertiert ONNX-Output in ein flaches FloatArray.
     * Unterstuetzt [batch, classes] und flache Arrays.
     */
    private fun flattenOutput(output: Any?): FloatArray? {
        return when (output) {
            is Array<*> -> {
                @Suppress("UNCHECKED_CAST")
                (output as? Array<FloatArray>)?.firstOrNull()
            }
            is FloatArray -> output
            else -> {
                Log.w(TAG, "Unbekanntes Output-Format: ${output?.javaClass}")
                null
            }
        }
    }

    /**
     * ONNX Inference ausfuehren und das rohe OrtSession.Result zurueckgeben.
     * Wird von extractEmbedding() genutzt, um auf alle Outputs zugreifen zu koennen.
     * Der Aufrufer ist fuer das Schliessen des Results verantwortlich (use {}).
     */
    private fun runInferenceRaw(session: OrtSession, chunk: FloatArray): OrtSession.Result? {
        val env = OrtEnvironment.getEnvironment()
        // T151a: Eingangsname laut Profil, Form [1, N] oder [N] laut Modell
        val inputName = OrtModelIo.inputName(session, profile.inputName)
        val shape = OrtModelIo.tensorShape(session, inputName, chunk.size)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), shape)

        return try {
            session.run(mapOf(inputName to tensor))
        } catch (e: Exception) {
            Log.e(TAG, "ONNX Inference fehlgeschlagen", e)
            null
        } finally {
            tensor.close()
        }
    }

    /**
     * Extrahiert den Embedding-Vektor aus dem ONNX-Result (T103a: Ausgang laut Profil).
     * Preview: Index 0 (1024-dim), 3.0 final: `embeddings_out` bzw. `embeddings` (1280-dim).
     * Vektoren mit NaN (3.0 final liefert bei reiner Stille NaN) werden verworfen.
     */
    private fun extractEmbeddingFromResult(result: OrtSession.Result, session: OrtSession): FloatArray? {
        val p = profile
        val outputNames = session.outputNames.toList()
        if (outputNames.isEmpty() || !p.hasEmbedding) return null  // T151a: Modell ohne Embedding
        // T151a: ohne Index-Rueckfall (importierte Profile) muss der Name passen
        if (p.embeddingOutputIndex < 0 &&
            (listOfNotNull(p.embeddingOutputName) + p.embeddingOutputAliases).none { it in outputNames }) return null
        var idx = selectOutputIndex(outputNames, p.embeddingOutputName, p.embeddingOutputIndex, p.embeddingOutputAliases)
        // T122: liegt auf dem Index der Score-Vektor, den anderen Ausgang nehmen (nur Katalog)
        if (p.source == ModelProfile.Source.CATALOG && outputNames.size == 2 &&
            flattenOutput(result[idx].value)?.size == labels.size) idx = 1 - idx
        val flat = flattenOutput(result[idx].value) ?: run {
            Log.w(TAG, "Kein Embedding-Output gefunden")
            return null
        }
        if (flat.any { it.isNaN() }) {
            Log.w(TAG, "Embedding enthaelt NaN — verworfen")
            return null
        }
        return flat
    }
}
