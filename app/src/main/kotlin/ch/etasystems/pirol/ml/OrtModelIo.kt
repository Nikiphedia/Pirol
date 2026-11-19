package ch.etasystems.pirol.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

/**
 * T151a: ORT-Hilfen fuer Modelle mit Waveform-Eingang (Klassifikator, Benchmark, Import-Pruefung).
 */
object OrtModelIo {

    /** Eingangsname laut Profil, sonst erster Eingang */
    fun inputName(session: OrtSession, preferred: String?): String =
        preferred?.takeIf { it in session.inputNames } ?: session.inputNames.first()

    /** Form eines Eingangs laut Modell (-1 = dynamisch), null = unbekannt */
    fun inputShape(session: OrtSession, name: String): LongArray? =
        (session.inputInfo[name]?.info as? TensorInfo)?.shape

    /** Tensorform fuer n Samples: Rang 1 → [n], sonst [1, n] */
    fun tensorShape(session: OrtSession, name: String, n: Int): LongArray =
        if (inputShape(session, name)?.size == 1) longArrayOf(n.toLong()) else longArrayOf(1, n.toLong())

    /**
     * Probelauf mit n Nullen; liefert pro Ausgang die Anzahl Werte (Produkt der Form).
     * Wird beim Import genutzt, weil Ausgangsformen oft dynamisch deklariert sind.
     */
    fun probeOutputSizes(session: OrtSession, inputName: String, n: Int): Map<String, Int> {
        val env = OrtEnvironment.getEnvironment()
        val shape = tensorShape(session, inputName, n)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n)), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                result.associate { (name, value) ->
                    val info = (value as? OnnxTensor)?.info
                    name to (info?.shape?.fold(1L) { a, b -> a * b }?.toInt() ?: -1)
                }
            }
        }
    }
}
