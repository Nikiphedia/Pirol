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
    private val modelManager: ModelManager
) : AudioClassifier {

    companion object {
        private const val TAG = "BirdNetV3"
        private const val LABELS_PATH = "models/birdnet_v3_labels.csv"
        private const val ASSETS_MODEL_PATH = "models/birdnet_v3.onnx"
        private const val TARGET_SAMPLE_RATE = 32000
        private const val CHUNK_DURATION_SEC = 3.0f
        private const val CHUNK_SAMPLES = (TARGET_SAMPLE_RATE * CHUNK_DURATION_SEC).toInt() // 96000
    }

    // Lazy-initialisiert beim ersten classify()-Aufruf
    private var ortSession: OrtSession? = null
    private var sessionFailed = false
    private var labels: List<Pair<String, String>> = emptyList() // (scientific, common)

    /** Anzahl geladener Labels */
    override val labelCount: Int get() = labels.size

    /** Modell-Name fuer UI */
    override val modelName: String = "BirdNET V3.0"

    /**
     * Prueft ob das ONNX-Modell verfuegbar ist.
     * Zuerst aktives Modell via ModelManager, dann assets/ (Fallback).
     */
    override fun isModelAvailable(): Boolean {
        // 1. Aktives Modell via ModelManager
        val activeFile = modelManager.getActiveModelFile()
        if (activeFile != null && activeFile.exists()) return true
        // 2. Assets Fallback
        return isModelAvailableInAssets()
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
    ): List<AudioClassifier.ClassifierResult> {
        if (samples.isEmpty()) return emptyList()

        val session = ensureSession()
        if (session == null) {
            Log.w(TAG, "ONNX Session nicht verfuegbar — Modell fehlt oder Ladefehler")
            return emptyList()
        }

        return try {
            // 1. Resample auf 32 kHz falls noetig
            val resampled = if (sampleRate != TARGET_SAMPLE_RATE) {
                AudioResampler.resample(samples, sampleRate, TARGET_SAMPLE_RATE)
            } else {
                samples
            }

            // 2. Auf exakt CHUNK_SAMPLES bringen (Zero-Padding oder Truncate)
            val chunk = prepareChunk(resampled)

            // 3. ONNX Inference
            val scores = runInference(session, chunk)

            // 4. Top-K filtern und sortieren
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
                            timeEndSec = CHUNK_DURATION_SEC
                        )
                    )
                }
            }

            results.sortedByDescending { it.confidence }.take(topK)
        } catch (e: Exception) {
            Log.e(TAG, "Klassifizierung fehlgeschlagen", e)
            emptyList()
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

        val session = ensureSession() ?: run {
            Log.w(TAG, "ONNX Session nicht verfuegbar fuer Embedding-Extraktion")
            return null
        }

        return try {
            // 1. Resample auf 32 kHz falls noetig
            val resampled = if (sampleRate != TARGET_SAMPLE_RATE) {
                AudioResampler.resample(samples, sampleRate, TARGET_SAMPLE_RATE)
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
        }
    }

    /** Gibt alle geladenen Labels zurueck */
    fun getLabels(): List<Pair<String, String>> {
        ensureSession() // Stellt sicher, dass Labels geladen sind
        return labels
    }

    /** ONNX Session und Ressourcen freigeben */
    fun close() {
        try {
            ortSession?.close()
            ortSession = null
            sessionFailed = false
            Log.i(TAG, "ONNX Session geschlossen")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim Schliessen der ONNX Session", e)
        }
    }

    /**
     * Zuruecksetzen wenn Modell nachtraeglich installiert wurde.
     * Ermoeglicht erneuten Session-Aufbau beim naechsten classify()-Aufruf.
     */
    override fun resetSession() {
        ortSession?.close()
        ortSession = null
        sessionFailed = false
        Log.i(TAG, "Session zurueckgesetzt — Modell wird beim naechsten Aufruf neu geladen")
    }

    // --- Private Hilfsfunktionen ---

    /**
     * ONNX Session lazy initialisieren + Labels laden.
     * Modell wird zuerst aus filesDir/models/ geladen (Pfad-basiert, kein readBytes),
     * dann aus assets/ als Fallback.
     */
    @Synchronized
    private fun ensureSession(): OrtSession? {
        if (ortSession != null) return ortSession
        if (sessionFailed) return null

        return try {
            // Labels immer laden (auch wenn Modell fehlt — fuer getLabels())
            loadLabels()

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

            // 1. Aktives Modell via ModelManager (direkter Pfad — memory-mapped)
            val activeFile = modelManager.getActiveModelFile()
            if (activeFile != null && activeFile.exists()) {
                Log.i(TAG, "Modell geladen via ModelManager: ${activeFile.absolutePath} " +
                        "(${activeFile.length() / 1_000_000} MB)")
                val session = env.createSession(activeFile.absolutePath, opts)
                ortSession = session
                logSessionInfo(session)
                return session
            }

            // 2. Assets Fallback (muss als ByteArray geladen werden)
            if (!isModelAvailableInAssets()) {
                Log.w(TAG, "Modell nicht gefunden — weder in filesDir noch in assets")
                Log.w(TAG, "Bitte Modell via Onboarding importieren oder in assets/ ablegen")
                sessionFailed = true
                return null
            }

            val modelBytes = context.assets.open(ASSETS_MODEL_PATH).use { it.readBytes() }
            val session = env.createSession(modelBytes, opts)
            ortSession = session
            logSessionInfo(session)
            session
        } catch (e: Exception) {
            Log.e(TAG, "ONNX Session konnte nicht erstellt werden", e)
            sessionFailed = true
            null
        }
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
        Log.i(TAG, "BirdNET V3.0 geladen")
        Log.i(TAG, "  Input: ${session.inputNames}")
        Log.i(TAG, "  Output: ${session.outputNames}")
        Log.i(TAG, "  Labels: ${labels.size} Arten")
    }

    /**
     * Labels aus assets/models/birdnet_v3_labels.csv laden.
     * Format: Semikolon-CSV mit Header (idx;id;sci_name;com_name;class;order)
     * BOM wird entfernt falls vorhanden.
     */
    private fun loadLabels() {
        if (labels.isNotEmpty()) return

        try {
            val lines = context.assets.open(LABELS_PATH).bufferedReader().use { reader ->
                reader.readLines()
            }

            // Erste Zeile = Header, ueberspringen. BOM entfernen falls vorhanden.
            labels = lines
                .drop(1) // Header ueberspringen
                .filter { it.isNotBlank() }
                .map { line ->
                    val cleaned = line.trimStart('\uFEFF').trim()
                    val parts = cleaned.split(";")
                    val sciName = parts.getOrElse(2) { "" }.trim()   // sci_name
                    val comName = parts.getOrElse(3) { "" }.trim()   // com_name
                    sciName to comName
                }
                .filter { it.first.isNotEmpty() }

            Log.i(TAG, "Labels geladen: ${labels.size} Arten (CSV)")
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
        return when {
            input.size == CHUNK_SAMPLES -> input
            input.size > CHUNK_SAMPLES -> input.copyOfRange(0, CHUNK_SAMPLES)
            else -> {
                // Zero-Padding
                val padded = FloatArray(CHUNK_SAMPLES)
                input.copyInto(padded)
                padded
            }
        }
    }

    /**
     * ONNX Inference auf einem vorbereiteten Chunk ausfuehren.
     * Input-Tensor: [1, 96000], Output: Predictions-Array
     */
    private fun runInference(session: OrtSession, chunk: FloatArray): FloatArray {
        val env = OrtEnvironment.getEnvironment()

        // Input-Tensor: [batch=1, samples=96000]
        val shape = longArrayOf(1, chunk.size.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), shape)

        return try {
            val inputName = session.inputNames.first()
            val result = session.run(mapOf(inputName to tensor))

            // V3.0 hat 2 Outputs: (embeddings, predictions)
            // Predictions ist der Tensor mit der passenden Label-Anzahl
            extractPredictions(result, session)
        } finally {
            tensor.close()
        }
    }

    /**
     * Extrahiert die Prediction-Scores aus dem ONNX-Output.
     * BirdNET V3.0: 2 Outputs (embeddings [1,1024], predictions [1,N])
     */
    private fun extractPredictions(result: OrtSession.Result, session: OrtSession): FloatArray {
        val outputNames = session.outputNames.toList()

        // Versuche den Output zu finden, der zur Label-Anzahl passt
        for (i in outputNames.indices) {
            val output = result[i].value
            val scores = flattenOutput(output)
            if (scores != null && (labels.isEmpty() || scores.size == labels.size)) {
                return scores
            }
        }

        // Fallback: letzten Output nehmen (predictions ist typischerweise Index 1)
        val predIndex = if (outputNames.size >= 2) 1 else 0
        val output = result[predIndex].value
        return flattenOutput(output) ?: FloatArray(0)
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
        val shape = longArrayOf(1, chunk.size.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), shape)

        return try {
            val inputName = session.inputNames.first()
            session.run(mapOf(inputName to tensor))
        } catch (e: Exception) {
            Log.e(TAG, "ONNX Inference fehlgeschlagen", e)
            null
        } finally {
            tensor.close()
        }
    }

    /**
     * Extrahiert den Embedding-Vektor aus dem ONNX-Result.
     * BirdNET V3.0: Output Index 0 ist typischerweise embeddings [1,1024].
     * Erkennung: Der Output, der NICHT zur Label-Anzahl passt, ist das Embedding.
     */
    private fun extractEmbeddingFromResult(result: OrtSession.Result, session: OrtSession): FloatArray? {
        val outputNames = session.outputNames.toList()

        // Strategie: Finde den Output der NICHT zur Label-Anzahl passt (= Embedding)
        for (i in outputNames.indices) {
            val output = result[i].value
            val flat = flattenOutput(output) ?: continue
            // Embedding ist typischerweise 1024-dim, Predictions passt zur Label-Anzahl
            if (labels.isNotEmpty() && flat.size != labels.size) {
                Log.d(TAG, "Embedding gefunden: Output[$i] '${outputNames[i]}', dim=${flat.size}")
                return flat
            }
        }

        // Fallback: ersten Output nehmen (typischerweise Index 0 = Embedding)
        if (outputNames.isNotEmpty()) {
            val output = result[0].value
            val flat = flattenOutput(output)
            if (flat != null) {
                Log.d(TAG, "Embedding Fallback: Output[0], dim=${flat.size}")
                return flat
            }
        }

        Log.w(TAG, "Kein Embedding-Output gefunden")
        return null
    }
}
