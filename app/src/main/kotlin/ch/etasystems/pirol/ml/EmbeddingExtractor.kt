package ch.etasystems.pirol.ml

import android.util.Log
import kotlin.math.sqrt

/**
 * Extrahiert Embedding-Vektoren aus Audio fuer die Aehnlichkeitssuche.
 *
 * Zwei Pfade:
 * a) ONNX-Pfad: BirdNET V3.0 Embeddings (1024-dim) — wenn Modell vorhanden
 * b) MFCC-Fallback: Enhanced MFCC Features (43-dim) — immer verfuegbar
 *
 * Alle zurueckgegebenen Embeddings sind L2-normalisiert.
 */
class EmbeddingExtractor(
    private val classifier: AudioClassifier,
    private val mfccExtractor: MfccExtractor
) {

    companion object {
        private const val TAG = "EmbeddingExtractor"
    }

    /**
     * Extrahiert einen L2-normalisierten Embedding-Vektor aus Audio-Samples.
     *
     * Versucht zuerst den ONNX-Pfad (BirdNET V3.0 Embedding, 1024-dim).
     * Bei Fehler oder fehlendem Modell: Fallback auf MFCC Enhanced (43-dim).
     *
     * @param samples Audio-Daten, normalisiert [-1, 1]
     * @param sampleRate Samplerate der Input-Daten
     * @return L2-normalisierter Embedding-Vektor (1024-dim oder 43-dim)
     */
    fun extract(samples: FloatArray, sampleRate: Int): FloatArray {
        // ONNX-Pfad versuchen
        val onnxEmbedding = try {
            classifier.extractEmbedding(samples, sampleRate)
        } catch (e: Exception) {
            Log.w(TAG, "ONNX Embedding-Extraktion fehlgeschlagen, nutze MFCC-Fallback", e)
            null
        }

        if (onnxEmbedding != null && onnxEmbedding.isNotEmpty()) {
            Log.d(TAG, "ONNX Embedding extrahiert: ${onnxEmbedding.size}-dim")
            l2Normalize(onnxEmbedding)
            return onnxEmbedding
        }

        // MFCC-Fallback
        Log.d(TAG, "Nutze MFCC-Fallback fuer Embedding")
        val mfccEmbedding = mfccExtractor.extractEnhanced(samples, sampleRate)
        // extractEnhanced liefert bereits L2-normalisiert, aber sicherheitshalber nochmal
        l2Normalize(mfccEmbedding)
        return mfccEmbedding
    }

    /**
     * Gibt die erwartete Embedding-Dimension zurueck.
     * Abhaengig davon ob ONNX verfuegbar ist oder MFCC genutzt wird.
     */
    fun expectedDimension(): Int {
        return if (classifier.isModelAvailable()) {
            1024 // BirdNET V3.0 Embedding-Dimension
        } else {
            43 // MFCC Enhanced
        }
    }

    /** L2-Normalisierung in-place */
    private fun l2Normalize(vector: FloatArray) {
        var sumSq = 0f
        for (v in vector) {
            sumSq += v * v
        }
        val norm = sqrt(sumSq)
        if (norm > 1e-10f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
    }
}
