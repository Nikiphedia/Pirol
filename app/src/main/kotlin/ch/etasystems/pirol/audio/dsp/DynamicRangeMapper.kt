package ch.etasystems.pirol.audio.dsp

import android.os.SystemClock

/**
 * Perzentil-basierter Auto-Kontrast fuer Sonogramm-Display (T56).
 *
 * Sammelt ueber ein Rolling-Window (~5 s) die eingehenden Mel-dB-Werte und
 * berechnet p2/p98 (T56b: aggressiver als p5/p95, mehr Palette fuer mittlere Signale),
 * die dann als Mapping-Range [minDb, maxDb] dienen.
 * Das ersetzt das fixe [-80, 0] Mapping — leise Vogelrufe werden sichtbar,
 * laute Szenen ueberblenden nicht.
 *
 * Perzentil-Neuberechnung nur alle [recomputeIntervalMs] ms (default 250),
 * dazwischen wird das letzte Ergebnis mit IIR geglaettet. Kein Compose-Draw
 * Overhead — Update laeuft im DSP-Flow (Dispatchers.Default).
 *
 * Zusaetzlich gibt es [computeStatic] fuer den Analyse-Tab: einmalige Berechnung
 * ueber eine komplette Frame-Liste, ohne Rolling-Window/IIR.
 *
 * Thread-safe: alle public Methoden synchronisiert.
 */
class DynamicRangeMapper(
    /** Fenstergroesse in Sekunden fuer Rolling-Window. */
    private val windowSeconds: Float = 5f,
    /** Sample-Rate / Hop (48000/512 ≈ 93.75 fps) fuer Puffer-Dimensionierung. */
    private val framesPerSecond: Float = 93.75f,
    /** Neuberechnungs-Intervall in ms. */
    private val recomputeIntervalMs: Long = 250L,
    /** IIR-Glaettungsfaktor [0..1]. Hoeher = reagiert schneller, mehr Pop. */
    private val smoothingAlpha: Float = 0.15f,
    /** Fallback-Bereich solange noch zu wenig Daten (< minFramesForCompute). */
    private val fallbackMinDb: Float = -80f,
    private val fallbackMaxDb: Float = 0f
) {
    /** Max. Frames im Rolling-Window (Kapazitaet des Ringpuffers). */
    private val maxFrames: Int = (windowSeconds * framesPerSecond).toInt().coerceAtLeast(64)

    /** Minimale Frame-Anzahl bevor ueberhaupt Perzentile berechnet werden (~1 s). */
    private val minFramesForCompute: Int = (framesPerSecond * 1f).toInt().coerceAtLeast(32)

    /** Ringpuffer fuer Frame-Referenzen. Flache FloatArray-Referenzen, kein Deep-Copy. */
    private val frameBuffer = arrayOfNulls<FloatArray>(maxFrames)
    private var writeIdx = 0
    private var fillCount = 0

    // T56b: Aggressivere Perzentil-Grenzen — mehr Palette-Raum fuer mittlere Signale.
    // p2/p98 statt p5/p95: die extremsten 2% oben und unten werden ignoriert.
    private val lowPercentile  = 0.02f  // war: 0.05f
    private val highPercentile = 0.98f  // war: 0.95f

    /** Letzter geglaetteter Perzentil-Stand. */
    private var smoothedP5: Float = fallbackMinDb
    private var smoothedP95: Float = fallbackMaxDb
    private var hasValidRange: Boolean = false

    /** Zeitpunkt der letzten Neuberechnung (elapsedRealtime). */
    private var lastRecomputeMs: Long = 0L

    private val lock = Any()

    /** Wiederverwendbarer Arbeitspuffer fuer die Perzentil-Berechnung (vermeidet Allokation alle 250 ms). */
    private var workBuffer = FloatArray(0)

    /**
     * Neuen Frame in den Rolling-Window-Buffer einreihen.
     * Triggert periodische Neuberechnung (alle recomputeIntervalMs).
     */
    fun update(frame: FloatArray) {
        synchronized(lock) {
            frameBuffer[writeIdx] = frame
            writeIdx = (writeIdx + 1) % maxFrames
            if (fillCount < maxFrames) fillCount++

            val now = SystemClock.elapsedRealtime()
            if (now - lastRecomputeMs >= recomputeIntervalMs && fillCount >= minFramesForCompute) {
                lastRecomputeMs = now
                recomputePercentilesLocked()
            }
        }
    }

    /**
     * Aktuelle Range [minDb, maxDb] fuer das Mapping.
     * Vor erster valider Berechnung: Fallback [-80, 0].
     * maxDb > minDb ist garantiert (minimal +1 dB Abstand).
     */
    fun currentRange(): Pair<Float, Float> {
        synchronized(lock) {
            if (!hasValidRange) return fallbackMinDb to fallbackMaxDb
            val lo = smoothedP5
            val hi = if (smoothedP95 - lo < 1f) lo + 1f else smoothedP95
            return lo to hi
        }
    }

    /** Buffer + Glaettung zuruecksetzen (z.B. nach Session-Neustart). */
    fun reset() {
        synchronized(lock) {
            for (i in frameBuffer.indices) frameBuffer[i] = null
            writeIdx = 0
            fillCount = 0
            smoothedP5 = fallbackMinDb
            smoothedP95 = fallbackMaxDb
            hasValidRange = false
            lastRecomputeMs = 0L
        }
    }

    /**
     * Vollstaendige Neuberechnung der Perzentile + IIR-Update.
     * Muss unter [lock] aufgerufen werden.
     */
    private fun recomputePercentilesLocked() {
        // Alle gueltigen Werte in ein flaches Array packen.
        val frames = fillCount
        if (frames == 0) return
        val firstFrame = frameBuffer.firstNotNullOfOrNull { it } ?: return
        val binsPerFrame = firstFrame.size
        val total = frames * binsPerFrame
        if (workBuffer.size < total) workBuffer = FloatArray(total)
        val flat = workBuffer
        var idx = 0
        for (i in 0 until frames) {
            val f = frameBuffer[i] ?: continue
            val n = minOf(binsPerFrame, f.size)
            System.arraycopy(f, 0, flat, idx, n)
            idx += n
        }
        val usable = idx
        if (usable < minFramesForCompute) return

        // Perzentile per Quickselect (O(n)) statt Vollsortierung (O(n log n)).
        // Liefert exakt dieselben Werte wie sorted[index].
        val p5Raw  = selectKth(flat, usable, (usable * lowPercentile ).toInt().coerceAtMost(usable - 1))
        val p95Raw = selectKth(flat, usable, (usable * highPercentile).toInt().coerceAtMost(usable - 1))

        if (!hasValidRange) {
            smoothedP5 = p5Raw
            smoothedP95 = p95Raw
            hasValidRange = true
        } else {
            // IIR-Glaettung: neu = alpha * raw + (1-alpha) * alt
            smoothedP5 = smoothingAlpha * p5Raw + (1f - smoothingAlpha) * smoothedP5
            smoothedP95 = smoothingAlpha * p95Raw + (1f - smoothingAlpha) * smoothedP95
        }
    }

    companion object {
        // T56b: Gleiche Konstanten wie Rolling-Window-Mapper fuer konsistentes Verhalten.
        private const val STATIC_LOW_PERCENTILE  = 0.02f  // war: 0.05f
        private const val STATIC_HIGH_PERCENTILE = 0.98f  // war: 0.95f

        /**
         * Einmal-Berechnung ueber eine feste Frame-Liste (Analyse-Tab).
         * Kein Rolling-Window, kein IIR — bestimmt (p2, p98) per Quickselect ueber alle Werte.
         * Bei zu wenig Daten: Fallback [-80, 0].
         */
        fun computeStatic(
            frames: List<FloatArray>,
            fallbackMinDb: Float = -80f,
            fallbackMaxDb: Float = 0f
        ): Pair<Float, Float> {
            if (frames.isEmpty()) return fallbackMinDb to fallbackMaxDb
            val binsPerFrame = frames.first().size
            val total = frames.size * binsPerFrame
            if (total < 64) return fallbackMinDb to fallbackMaxDb
            val flat = FloatArray(total)
            var idx = 0
            for (f in frames) {
                val n = minOf(binsPerFrame, f.size)
                System.arraycopy(f, 0, flat, idx, n)
                idx += n
            }
            // Quickselect statt Sortierung (gleiche Werte, O(n)). flat ist bereits eine Kopie.
            val p2  = selectKth(flat, idx, (idx * STATIC_LOW_PERCENTILE ).toInt().coerceAtMost(idx - 1))
            val p98 = selectKth(flat, idx, (idx * STATIC_HIGH_PERCENTILE).toInt().coerceAtMost(idx - 1))
            val hi = if (p98 - p2 < 1f) p2 + 1f else p98
            return p2 to hi
        }

        /**
         * Quickselect (Hoare-Partition, Median-of-3): liefert den k-kleinsten Wert
         * der ersten [size] Elemente — identisch zu sortiert[k]. Ordnet [arr] dabei um.
         * NaN-Werte werden wie bei Arrays.sort ans Ende sortiert behandelt.
         */
        internal fun selectKth(arr: FloatArray, size: Int, k: Int): Float {
            var lo = 0
            var hi = size - 1
            while (hi > lo) {
                // Median-of-3 Pivot gegen degenerierte Faelle (z.B. viele gleiche Werte bei minDb)
                val mid = (lo + hi) ushr 1
                if (less(arr[mid], arr[lo])) swap(arr, mid, lo)
                if (less(arr[hi], arr[lo])) swap(arr, hi, lo)
                if (less(arr[hi], arr[mid])) swap(arr, hi, mid)
                val pivot = arr[mid]
                var i = lo
                var j = hi
                while (i <= j) {
                    while (less(arr[i], pivot)) i++
                    while (less(pivot, arr[j])) j--
                    if (i <= j) {
                        swap(arr, i, j)
                        i++
                        j--
                    }
                }
                when {
                    k <= j -> hi = j
                    k >= i -> lo = i
                    else -> return arr[k]
                }
            }
            return arr[k]
        }

        /** Totalordnung wie Float.compareTo / Arrays.sort (NaN am Ende, -0.0 < 0.0). */
        private fun less(a: Float, b: Float): Boolean = a.compareTo(b) < 0

        private fun swap(arr: FloatArray, i: Int, j: Int) {
            val t = arr[i]
            arr[i] = arr[j]
            arr[j] = t
        }
    }
}
