package ch.etasystems.pirol.ml

import android.util.Log
import ch.etasystems.pirol.audio.AudioChunk
import ch.etasystems.pirol.audio.dsp.AudioDspUtils
import ch.etasystems.pirol.audio.dsp.AudioResampler
import java.util.UUID

/**
 * Coroutine-basierter Worker fuer die Inference-Pipeline.
 *
 * Orchestriert den gesamten Ablauf:
 * AudioChunk → ChunkAccumulator (3s) → Intervall-Check → AudioResampler (→32kHz) → AudioClassifier → DetectionResult
 *
 * Wird pro Chunk vom LiveViewModel aufgerufen (suspend fuer cooperative Cancellation).
 * Der onDetections-Callback liefert Ergebnisse an den DetectionListState.
 *
 * Ab T12: Der Callback erhaelt zusaetzlich den AudioBlock (48kHz Float-Samples),
 * damit die Embedding-Pipeline darauf zugreifen kann.
 *
 * Ab T29: Intervall-Steuerung — Chunks werden uebersprungen wenn das konfigurierte
 * Mindest-Intervall (inferenceIntervalMs) nicht erreicht ist. Audio wird trotzdem
 * gespeichert (SessionManager), nur die CPU-intensive Inference wird uebersprungen.
 *
 * @param classifier Audio-Klassifizierer (via Koin injiziert)
 * @param regionalFilter Filter fuer regionale Artenliste (Plausibilitaetspruefung)
 * @param config Laufzeit-Konfiguration fuer Inference-Parameter (topK, Schwellwert, Regionalfilter, Intervall)
 * @param onDetections Callback fuer neue Detektionen + Audio-Block (wird auf dem Aufrufer-Scope ausgefuehrt)
 */
class InferenceWorker(
    private val classifier: AudioClassifier,
    private val regionalFilter: RegionalSpeciesFilter,
    var config: InferenceConfig = InferenceConfig.DEFAULT,
    private val onDetections: (List<DetectionResult>, AudioBlock?) -> Unit
) {
    companion object {
        private const val TAG = "InferenceWorker"
        private const val TARGET_RATE = 32000
        private const val ORIGINAL_RATE = 48000
    }

    private val accumulator = ChunkAccumulator()

    /** Zeitstempel der letzten ausgefuehrten Inference (ms seit Epoch) */
    private var lastInferenceMs: Long = 0L

    /**
     * Verarbeitet einen AudioChunk.
     *
     * Akkumuliert Chunks bis 3s voll sind, dann:
     * 0. Intervall-Check: Zu frueh seit letzter Inference? → Chunk verwerfen
     * 1. Resampling auf 32 kHz (fuer BirdNET)
     * 2. ONNX-Klassifizierung
     * 3. Optionaler Regionalfilter (Plausibilitaetspruefung)
     * 4. Mapping auf DetectionResult
     * 5. Konvertierung der Original-Samples zu Float (48kHz) fuer Embedding-Pipeline
     * 6. Callback mit Ergebnissen + AudioBlock
     */
    suspend fun processChunk(chunk: AudioChunk) {
        val block = try {
            accumulator.feed(chunk)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Samplerate-Wechsel erkannt, Accumulator wird zurueckgesetzt: ${e.message}")
            accumulator.reset()
            return
        }

        // Kein Block fertig — weiter sammeln
        if (block == null) return

        // T29: Intervall-Check — zu frueh fuer naechste Inference?
        val now = System.currentTimeMillis()
        if (now - lastInferenceMs < config.inferenceIntervalMs) {
            // Chunk verwerfen (Audio wird trotzdem vom SessionManager gespeichert)
            Log.d(TAG, "Inference uebersprungen (Intervall ${config.inferenceIntervalMs}ms nicht erreicht)")
            return
        }
        lastInferenceMs = now

        try {
            // 1. Resampling: ShortArray → FloatArray @32kHz
            val resampled = AudioResampler.resample(
                block.samples, block.sampleRate, TARGET_RATE
            )

            // 1b. DSP-Vorverarbeitung auf Inference-Kopie (T34)
            if (config.highpassEnabled) {
                AudioDspUtils.highpassFilter(resampled, TARGET_RATE, 200f)
            }
            AudioDspUtils.peakNormalize(resampled, 0.95f)
            AudioDspUtils.brickwallLimit(resampled, 1.0f)

            // 2. Klassifizierung via AudioClassifier (Parameter aus config)
            val classifierResults = classifier.classify(
                samples = resampled,
                sampleRate = TARGET_RATE,
                topK = config.topK,
                threshold = config.confidenceThreshold
            )

            if (classifierResults.isEmpty()) return

            // 3. Regionalfilter anwenden (falls konfiguriert)
            val filteredResults = if (config.regionFilter != null && config.showOnlyFiltered) {
                // Nur plausible Arten durchlassen
                classifierResults.filter { cr ->
                    val plausible = regionalFilter.isPlausible(cr.scientificName)
                    if (!plausible) {
                        Log.d(TAG, "Herausgefiltert (nicht regional plausibel): " +
                                "${cr.commonName} (${cr.scientificName})")
                    }
                    plausible
                }
            } else {
                // Alle behalten, aber ungewoehnliche Arten loggen
                if (config.regionFilter != null) {
                    classifierResults.forEach { cr ->
                        if (!regionalFilter.isPlausible(cr.scientificName)) {
                            Log.d(TAG, "Ungewoehnlich (nicht regional erwartet): " +
                                    "${cr.commonName} (${cr.scientificName}) " +
                                    "${(cr.confidence * 100).toInt()}%")
                        }
                    }
                }
                classifierResults
            }

            // 4. Mapping: ClassifierResult → DetectionResult
            if (filteredResults.isNotEmpty()) {
                val detections = filteredResults.map { cr ->
                    DetectionResult(
                        id = UUID.randomUUID().toString(),
                        scientificName = cr.scientificName.replace(' ', '_'),
                        commonName = cr.commonName,
                        confidence = cr.confidence,
                        timestampMs = now,
                        chunkStartSec = cr.timeStartSec,
                        chunkEndSec = cr.timeEndSec,
                        sampleRate = block.sampleRate
                    )
                }

                Log.d(TAG, "Detektionen: ${detections.size} Arten " +
                        "(Top: ${detections.first().commonName} " +
                        "${(detections.first().confidence * 100).toInt()}%)")

                // 5. Original-Samples als Float konvertieren (48kHz fuer Embedding)
                val floatSamples = FloatArray(block.samples.size) { i ->
                    block.samples[i].toFloat() / Short.MAX_VALUE.toFloat()
                }
                val audioBlock = AudioBlock(
                    samples = floatSamples,
                    sampleRate = block.sampleRate
                )

                // 6. Ergebnisse + AudioBlock an Consumer liefern
                onDetections(detections, audioBlock)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference fehlgeschlagen", e)
        }
    }

    /** Pipeline zuruecksetzen (bei Recording-Stop/Neustart) */
    fun reset() {
        accumulator.reset()
        lastInferenceMs = 0L
        Log.d(TAG, "Pipeline zurueckgesetzt")
    }
}
