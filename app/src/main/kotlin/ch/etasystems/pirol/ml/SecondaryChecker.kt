package ch.etasystems.pirol.ml

import android.util.Log
import ch.etasystems.pirol.audio.dsp.AudioResampler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicInteger

/**
 * T224: Zweitmodell der Live-Pipeline (nur Anzeige). Eigene Klassifizierer-Instanz mit eigener
 * ORT-Session; laeuft in eigener Coroutine NACH der Hauptinferenz und verdraengt sie nie.
 *
 * Warteschlange begrenzt ([capacity]); bei Stau wird das aelteste wartende Fenster verworfen
 * (dessen Detektionen bleiben ungeprueft, Felder null).
 *
 * @param onResult Ergebnis eines Fensters: Zuordnung Ziel-Detektion → Status (leer = nichts geliefert)
 */
class SecondaryChecker(
    private val classifier: AudioClassifier,
    private val threshold: Float,
    private val isPlausible: (String) -> Boolean = { true },
    capacity: Int = DEFAULT_CAPACITY,
    private val onResult: suspend (SecondaryWindow, List<Pair<SecondaryWindow.Target, SecondaryCheck.Outcome>>) -> Unit
) {
    companion object {
        private const val TAG = "SecondaryChecker"
        const val DEFAULT_CAPACITY = 2
    }

    /** Ein Fenster des Hauptmodells mit Detektion(en), das das Zweitmodell pruefen soll */
    class SecondaryWindow(
        val pcm: ShortArray,
        val sampleRate: Int,
        /** Fensterstart auf der Session-Achse (= chunkStartSec der Detektionen) */
        val windowStartSec: Float,
        val highpass: Boolean,
        val targets: List<Target>,
        /** Persistenz-Jobs dieses Fensters (Append/Merge); Aktualisierung der Zeile erst danach */
        val persisted: List<kotlinx.coroutines.Job> = emptyList()
    ) {
        /** Detektion in der Live-Liste (id) und ihre Art (Hauptmodell) */
        class Target(val id: String, val scientificName: String)
    }

    private val queue = Channel<SecondaryWindow>(capacity = capacity)
    private val droppedCount = AtomicInteger(0)
    private val checkedCount = AtomicInteger(0)
    @Volatile private var labelKeys: Set<String> = emptySet()

    /** Fensterlaenge des Zweitmodells (ms) */
    val windowMs: Int get() = classifier.windowMs
    val modelId: String? get() = classifier.modelId

    /** Verworfene Fenster (Warteschlange voll) seit Start */
    val dropped: Int get() = droppedCount.get()
    val checked: Int get() = checkedCount.get()

    /** Fenster einreihen; bei Stau aeltestes verwerfen. @return false, wenn ein Fenster verworfen wurde */
    fun submit(window: SecondaryWindow): Boolean {
        if (queue.trySend(window).isSuccess) return true
        if (queue.tryReceive().isSuccess) droppedCount.incrementAndGet()
        if (!queue.trySend(window).isSuccess) droppedCount.incrementAndGet()
        return false
    }

    /** Wartende Fenster verwerfen (Session-Ende) */
    fun clear() {
        while (queue.tryReceive().isSuccess) { /* verwerfen */ }
    }

    /** Schleife bis zur Cancellation der aufrufenden Coroutine */
    suspend fun runLoop() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val window = queue.receive()
            val results = process(window)
            onResult(window, results)
        }
    }

    /** Ein Fenster rechnen (blockierend). Leer bei Fehler/NaN. */
    internal fun process(window: SecondaryWindow): List<Pair<SecondaryWindow.Target, SecondaryCheck.Outcome>> {
        val t0 = System.nanoTime()
        val out = try {
            val targetRate = classifier.inputSampleRate
            val fitted = SecondaryCheck.fitWindow(pcm = window.pcm,
                targetSamples = SecondaryCheck.windowSamplesAt(classifier.windowMs, window.sampleRate))
            val samples = InferenceWorker.prepareInput(fitted, window.sampleRate, targetRate,
                AudioResampler.defaultMode, window.highpass)
            // Alle Scores (Schwelle 0): die Detektionsart braucht ihren Score auch unter jeder Schwelle
            val results = classifier.classify(samples, targetRate, topK = Int.MAX_VALUE, threshold = 0f)
            if (labelKeys.isEmpty()) {
                labelKeys = classifier.labelScientificNames.mapTo(HashSet()) { SpeciesKey.of(it) }
            }
            window.targets.mapNotNull { t ->
                SecondaryCheck.evaluate(t.scientificName, results, labelKeys, threshold, isPlausible)?.let { t to it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Zweitmodell fehlgeschlagen", e)
            emptyList()
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        checkedCount.incrementAndGet()
        Log.i(TAG, "T224_SEC ms=$ms model=${classifier.modelId} targets=${window.targets.size} " +
            "status=${out.joinToString(",") { it.second.status }} dropped=${droppedCount.get()}")
        return out
    }
}
