package ch.etasystems.pirol.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * T96: Geraete-Benchmark fuer die BirdNET-Inference.
 *
 * Misst das aktive Modell mit verschiedenen Thread-Zahlen, jeweils mit dem Standard-CPU-Provider
 * und mit XNNPACK. Jede Variante bekommt eine eigene ONNX-Session — die produktive Session des
 * Klassifizierers wird nicht angefasst. Reines Diagnose-Werkzeug: es schaltet nichts um.
 *
 * FP16 gegen FP32 vergleicht man, indem man im Modell-Management das andere Modell aktiviert
 * und den Benchmark erneut startet.
 */
object OnnxBenchmark {

    private const val TAG = "OnnxBenchmark"
    private const val INPUT_SAMPLES = 96000   // 3 s @ 32 kHz, wie im Live-Betrieb (Default; T103a: aus dem Profil)
    private const val WARMUP_RUNS = 2
    private const val MEASURED_RUNS = 10
    private val THREAD_COUNTS = intArrayOf(1, 2, 4)

    /**
     * Fuehrt den Benchmark aus und liefert einen Text-Bericht (auch im Logcat unter T96_BENCH).
     *
     * @param modelFile aktives Modell (Pfad in filesDir/models)
     * @param onProgress Zwischenstand fuer die UI, z.B. "3/6: 2 Threads, XNNPACK"
     */
    suspend fun run(
        modelFile: File,
        inputSamples: Int = INPUT_SAMPLES,  // T103a: Fensterlaenge des Modellprofils
        onProgress: (String) -> Unit
    ): String = withContext(Dispatchers.Default) {
        val report = StringBuilder()
        fun line(s: String) {
            report.append(s).append('\n')
            Log.i(TAG, "T96_BENCH $s")
        }

        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        line("Geraet: ${Build.MANUFACTURER} ${Build.MODEL}, SoC $soc, " +
                "${Runtime.getRuntime().availableProcessors()} Kerne, Android ${Build.VERSION.RELEASE}")
        line("Modell: ${modelFile.name} (${modelFile.length() / 1_000_000} MB)")
        line("Messung: $MEASURED_RUNS Laeufe nach $WARMUP_RUNS Aufwaermlaeufen, 3 s Audio pro Lauf")
        line("Variante              Median   Max    Laden")

        // Fester Zufalls-Input: reproduzierbar und ohne Sonderfall "Stille"
        val rnd = Random(42)
        val input = FloatArray(inputSamples) { rnd.nextFloat() * 0.2f - 0.1f }

        val env = OrtEnvironment.getEnvironment()
        val total = THREAD_COUNTS.size * 2
        var step = 0
        for (xnnpack in booleanArrayOf(false, true)) {
            for (threads in THREAD_COUNTS) {
                coroutineContext.ensureActive()
                step++
                val name = "${threads} Thread${if (threads > 1) "s" else " "}, ${if (xnnpack) "XNNPACK" else "CPU    "}"
                onProgress("$step/$total: $name")
                try {
                    val result = measure(env, modelFile, threads, xnnpack, input)
                    line("%-20s %5d ms %5d ms %5d ms".format(name, result.medianMs, result.maxMs, result.loadMs))
                } catch (e: Exception) {
                    // z.B. XNNPACK in diesem Build nicht enthalten oder Modell-Operator nicht unterstuetzt
                    line("%-20s nicht verfuegbar: %s".format(name, e.message?.take(80) ?: e.javaClass.simpleName))
                }
            }
        }
        line("Lesehilfe: Median unter 3000 ms heisst, die Inference haelt mit der Aufnahme mit.")
        report.toString()
    }

    // ---- T146: Benchmark fuer eine beliebige ONNX-Datei (Dummy-Rauschen, nur CPU) ----

    /**
     * Misst eine beliebige ONNX-Datei mit 1/2/4 Threads (CPU-Provider). Die Eingabeform wird aus dem
     * Modell gelesen; dynamische oder unbekannte Dimensionen werden auf 1 gesetzt. Es wird nur der
     * erste Float-Eingang bedient. Ausgabe auch im Logcat unter T146_BENCH.
     *
     * @param modelFile Kopie der Datei (z.B. in cacheDir); der Aufrufer loescht sie danach
     * @param displayName Anzeigename der Originaldatei
     */
    suspend fun runGeneric(
        modelFile: File,
        displayName: String,
        onProgress: (String) -> Unit
    ): String = withContext(Dispatchers.Default) {
        val report = StringBuilder()
        fun line(s: String) {
            report.append(s).append('\n')
            Log.i(TAG, "T146_BENCH $s")
        }
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        line("Geraet: ${Build.MANUFACTURER} ${Build.MODEL}, SoC $soc, " +
                "${Runtime.getRuntime().availableProcessors()} Kerne, Android ${Build.VERSION.RELEASE}")
        line("Modell: $displayName (${modelFile.length() / 1_000_000} MB)")
        line("Messung: $MEASURED_RUNS Laeufe nach $WARMUP_RUNS Aufwaermlaeufen, Zufallsrauschen, CPU")

        val env = OrtEnvironment.getEnvironment()
        for (threads in THREAD_COUNTS) {
            coroutineContext.ensureActive()
            val name = "$threads Thread${if (threads > 1) "s" else ""}"
            onProgress(name)
            try {
                OrtSession.SessionOptions().use { opts ->
                    opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    opts.setIntraOpNumThreads(threads)
                    val loadStart = SystemClock.elapsedRealtime()
                    env.createSession(modelFile.absolutePath, opts).use { session ->
                        val loadMs = SystemClock.elapsedRealtime() - loadStart
                        val inName = session.inputNames.first()
                        val info = session.inputInfo[inName]!!.info as TensorInfo
                        if (info.type != OnnxJavaType.FLOAT) {
                            line("$name: Eingang $inName hat Typ ${info.type}, nur FLOAT unterstuetzt")
                            return@use
                        }
                        val shape = LongArray(info.shape.size) { if (info.shape[it] > 0) info.shape[it] else 1L }
                        if (threads == THREAD_COUNTS.first()) {
                            line("Eingang: $inName ${info.shape.toList()} -> ${shape.toList()}; " +
                                    "Ausgaenge: ${session.outputNames.joinToString()}")
                        }
                        val count = shape.fold(1L) { a, b -> a * b }.toInt()
                        val rnd = Random(42)
                        val data = FloatArray(count) { rnd.nextFloat() * 0.2f - 0.1f }
                        val times = LongArray(MEASURED_RUNS)
                        var nanSeen = false
                        for (i in 0 until WARMUP_RUNS + MEASURED_RUNS) {
                            coroutineContext.ensureActive()
                            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
                                val start = SystemClock.elapsedRealtime()
                                session.run(mapOf(inName to tensor)).use { out ->
                                    val ms = SystemClock.elapsedRealtime() - start
                                    if (i >= WARMUP_RUNS) times[i - WARMUP_RUNS] = ms
                                    if (i == 0) {
                                        val fb = (out.first().value as? OnnxTensor)?.floatBuffer
                                        if (fb != null) {
                                            val a = FloatArray(fb.remaining())
                                            fb.get(a)
                                            nanSeen = a.any { it.isNaN() }
                                        }
                                    }
                                }
                            }
                        }
                        times.sort()
                        line("%-9s Median %5d ms  Max %5d ms  Laden %5d ms  Peak-RAM %d MB%s".format(
                            name, times[times.size / 2], times.last(), loadMs, peakRamMb(),
                            if (nanSeen) "  (NaN im 1. Ausgang!)" else ""))
                    }
                }
            } catch (e: Throwable) {
                // OOM kommt als Error, nicht als Exception
                line("$name: fehlgeschlagen: ${e.message?.take(100) ?: e.javaClass.simpleName}")
            }
        }
        report.toString()
    }

    /** Spitzen-RSS des Prozesses in MB (VmHWM aus /proc/self/status); -1 wenn nicht lesbar. */
    private fun peakRamMb(): Long = try {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }
            ?.filter { it.isDigit() }?.toLong()?.div(1024) ?: -1
    } catch (e: Exception) {
        -1
    }

    private class Measurement(val medianMs: Long, val maxMs: Long, val loadMs: Long)

    private fun measure(
        env: OrtEnvironment,
        modelFile: File,
        threads: Int,
        xnnpack: Boolean,
        input: FloatArray
    ): Measurement {
        OrtSession.SessionOptions().use { opts ->
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            opts.setIntraOpNumThreads(threads)
            if (xnnpack) {
                // XNNPACK bringt einen eigenen Thread-Pool mit
                opts.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            }
            val loadStart = SystemClock.elapsedRealtime()
            env.createSession(modelFile.absolutePath, opts).use { session ->
                val loadMs = SystemClock.elapsedRealtime() - loadStart
                val inputName = session.inputNames.first()
                val shape = longArrayOf(1, input.size.toLong())
                val times = LongArray(MEASURED_RUNS)
                for (i in 0 until WARMUP_RUNS + MEASURED_RUNS) {
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                        val start = SystemClock.elapsedRealtime()
                        session.run(mapOf(inputName to tensor)).use { }
                        val ms = SystemClock.elapsedRealtime() - start
                        if (i >= WARMUP_RUNS) times[i - WARMUP_RUNS] = ms
                    }
                }
                times.sort()
                return Measurement(medianMs = times[times.size / 2], maxMs = times.last(), loadMs = loadMs)
            }
        }
    }
}
