package ch.etasystems.pirol.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File

/**
 * T218: Dateien und Bezug des BirdNET+ Geomodel V3.0.5 (Apache 2.0, BirdNET-Team,
 * DOI 10.5281/zenodo.24362980). Liegt in filesDir/geomodel/ (getrennt von den Audiomodellen).
 * Die Release-Assets werden vom Projekt bereitgestellt (siehe tools/geomodel).
 */
object GeoModelFiles {
    const val MODEL_NAME = "geomodel_v305_fp16.onnx"
    const val INDEX_NAME = "geomodel_v305_index.tsv"
    const val MODEL_BYTES = 7_729_898L
    const val INDEX_BYTES = 396_423L
    const val MODEL_SHA256 = "c42785b4fc984adb0e60ab76c8b01a5631d950092c62c568da4b954966d6d9d5"
    const val INDEX_SHA256 = "5ea8ab9c1a3f326b2da7ac40a7996049e2e59902f2d548e69c70bea7045a0995"
    const val BASE_URL = "https://github.com/Nikiphedia/Pirol/releases/download/geomodel-v3.0.5/"
    const val VERSION = "BirdNET+ Geomodel V3.0.5 FP16"

    fun dir(context: Context) = File(context.filesDir, "geomodel")
    fun modelFile(context: Context) = File(dir(context), MODEL_NAME)
    fun indexFile(context: Context) = File(dir(context), INDEX_NAME)

    /** Beide Dateien vorhanden und in der erwarteten Groesse (Pruefsumme prueft der Download). */
    fun isInstalled(context: Context): Boolean =
        modelFile(context).length() == MODEL_BYTES && indexFile(context).length() == INDEX_BYTES
}

/**
 * T218: Rechnet das Geomodel. Eingabe `input` [n,3] = Breite, Laenge, Woche (1-48),
 * Ausgabe `probabilities` [n,14057] (Sigmoid enthalten). Die ORT-Session wird pro Aufruf
 * geoeffnet und sofort wieder geschlossen (Aufruf selten, 7,7 MB).
 */
class GeoSpeciesModel(private val modelFile: File, private val indexFile: File) {

    /** Namen pro Modellausgang (Zeile n der Indexdatei = Index n) */
    fun loadNames(): List<String> =
        indexFile.useLines { lines ->
            lines.filter { it.isNotBlank() }.map { it.substringAfter('\t').trim() }.toList()
        }

    /** Scores je Woche; Ergebnis[i] gehoert zu weeks[i]. */
    fun predict(lat: Double, lon: Double, weeks: IntArray): List<FloatArray> {
        val env = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { opts ->
            env.createSession(modelFile.absolutePath, opts).use { session ->
                val input = Array(weeks.size) { floatArrayOf(lat.toFloat(), lon.toFloat(), weeks[it].toFloat()) }
                OnnxTensor.createTensor(env, input).use { tensor ->
                    session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                        @Suppress("UNCHECKED_CAST") // Ausgabe ist float32 [n, klassen]
                        val out = result[0].value as Array<FloatArray>
                        return out.toList()
                    }
                }
            }
        }
    }
}
