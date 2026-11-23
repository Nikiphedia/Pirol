package ch.etasystems.pirol.ml

import android.util.Log
import ch.etasystems.pirol.audio.dsp.AudioResampler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    /** T231: Rohaudio der letzten Sekunden fuer laengere Fenster (Perch 5 s); null = wie T224 (Stille) */
    private val ring: AudioRingBuffer? = null,
    /** T231: zusaetzliche Wartezeit auf Audio nach dem Fenster (ms) */
    private val waitMarginMs: Long = DEFAULT_WAIT_MARGIN_MS,
    private val onResult: suspend (SecondaryWindow, List<Pair<SecondaryWindow.Target, SecondaryCheck.Outcome>>) -> Unit
) {
    companion object {
        private const val TAG = "SecondaryChecker"
        const val DEFAULT_CAPACITY = 2
        const val DEFAULT_WAIT_MARGIN_MS = 3000L
    }

    /** T231: Audio fuer das Zweitmodell in dessen Fensterlaenge (Aufnahmerate) */
    internal class ContextAudio(
        val pcm: ShortArray,
        /** CENTERED (inkl. am Anfang verschoben), RIGHT (Rueckfall rechtsbuendig), SILENCE (mit Stille aufgefuellt) */
        val mode: String,
        val waitMs: Long
    )

    /** Ein Fenster des Hauptmodells mit Detektion(en), das das Zweitmodell pruefen soll */
    class SecondaryWindow(
        val pcm: ShortArray,
        val sampleRate: Int,
        /** Fensterstart auf der Session-Achse (= chunkStartSec der Detektionen) */
        val windowStartSec: Float,
        val highpass: Boolean,
        val targets: List<Target>
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
            val context = contextFor(window)
            val results = process(window, context)
            onResult(window, results)
        }
    }

    /**
     * T231: Fuer ein laengeres Zweitmodell-Fenster (Perch 5 s) das echte Umgebungsaudio aus dem Ringpuffer
     * holen: zentriert um das Hauptfenster; fehlt das Audio danach noch, warten (hoechstens
     * Ueberhang + [waitMarginMs]), sonst rechtsbuendig. Stille nur, wenn insgesamt zu wenig Audio da ist.
     * null = gleich lang/kuerzer oder kein Puffer → Verhalten wie T224 ([SecondaryCheck.fitWindow]).
     */
    internal suspend fun contextFor(window: SecondaryWindow): ContextAudio? {
        val ring = ring ?: return null
        if (ring.sampleRate != window.sampleRate) return null
        val target = SecondaryCheck.windowSamplesAt(classifier.windowMs, window.sampleRate)
        if (target <= window.pcm.size) return null
        val t0 = System.nanoTime()
        val start = SecondaryCheck.startIndexOf(window.windowStartSec, window.sampleRate)
        val range = SecondaryCheck.contextRange(start, window.pcm.size, target, ring.originIndex)
        val overhangMs = (target - window.pcm.size).toLong() * 1000 / window.sampleRate
        withTimeoutOrNull(overhangMs + waitMarginMs) { ring.endIndex.first { it >= range.to } }
        val waitMs = (System.nanoTime() - t0) / 1_000_000
        ring.read(range.from, range.to)?.let { return ContextAudio(it, "CENTERED", waitMs) }
        // Rueckfall: rechtsbuendig bis zum vorhandenen Ende (enthaelt das Hauptfenster weiterhin)
        val fallback = SecondaryCheck.rightAlignedRange(ring.endIndex.value, target, ring.oldestIndex())
        val part = ring.read(fallback.from, fallback.to)
        return if (part != null && part.size >= window.pcm.size) {
            ContextAudio(SecondaryCheck.fitWindow(part, target), if (part.size < target) "SILENCE" else "RIGHT", waitMs)
        } else {
            ContextAudio(SecondaryCheck.fitWindow(window.pcm, target), "SILENCE", waitMs)
        }
    }

    /** Ein Fenster rechnen (blockierend). Leer bei Fehler/NaN. [context] = T231-Umgebungsaudio (null = T224) */
    internal fun process(
        window: SecondaryWindow,
        context: ContextAudio? = null
    ): List<Pair<SecondaryWindow.Target, SecondaryCheck.Outcome>> {
        val t0 = System.nanoTime()
        val out = try {
            val targetRate = classifier.inputSampleRate
            val fitted = context?.pcm ?: SecondaryCheck.fitWindow(pcm = window.pcm,
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
        if (classifier.windowMs > CHUNK_DURATION_MS) {
            // T231: Messlog Perch als Zweitmodell (ctx = Herkunft des Umgebungsaudios; SILENCE = Hinweis: aufgefuellt)
            Log.i(TAG, "T231_PERCH role=secondary ms=$ms main_window=${window.pcm.size * 1000L / window.sampleRate} " +
                "window=${classifier.windowMs} ctx=${context?.mode ?: "SILENCE"} wait_ms=${context?.waitMs ?: 0}")
        }
        return out
    }
}
