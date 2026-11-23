package ch.etasystems.pirol.audio.dsp

import android.os.SystemClock

/**
 * Perzentil-basierter Auto-Kontrast fuer Sonogramm-Display (T56).
 *
 * Sammelt ueber ein Rolling-Window (~5 s) die eingehenden Mel-dB-Werte und
 * berechnet p2/p98 (Default; T107: konfigurierbar) (T56b: aggressiver als p5/p95, mehr Palette fuer mittlere Signale),
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
    private val fallbackMinDb: Float = SONOGRAM_DEFAULT_MIN_DBFS,  // T199: dBFS
    private val fallbackMaxDb: Float = SONOGRAM_DEFAULT_MAX_DBFS,
    /** T176: Zeitquelle (ms), in Unit-Tests ersetzbar. */
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() }
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
    // T107: zur Laufzeit ueber configure() aenderbar (Presets "Leise Rufe" etc.).
    @Volatile private var lowPercentile  = DEFAULT_LOW_PERCENTILE
    @Volatile private var highPercentile = DEFAULT_HIGH_PERCENTILE
    /** T107: Mindestspanne [dB] zwischen minDb und maxDb — verhindert, dass Rauschen auf die volle Palette gestreckt wird. */
    @Volatile private var minSpanDb = DEFAULT_MIN_SPAN_DB

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

            val now = clockMs()
            if (now - lastRecomputeMs >= recomputeIntervalMs && fillCount >= minFramesForCompute) {
                lastRecomputeMs = now
                recomputePercentilesLocked()
            }
        }
    }

    /**
     * T107: Perzentil-Paar und Mindestspanne aendern. Erzwingt eine sofortige Neuberechnung
     * (naechster update()-Aufruf), die IIR-Glaettung blendet dann weich um.
     */
    fun configure(lowPercentile: Float, highPercentile: Float, minSpanDb: Float) {
        synchronized(lock) {
            this.lowPercentile = lowPercentile.coerceIn(0f, 0.9f)
            this.highPercentile = highPercentile.coerceIn(this.lowPercentile + 0.05f, 1f)
            this.minSpanDb = minSpanDb.coerceAtLeast(1f)
            lastRecomputeMs = 0L
        }
    }

    /**
     * Aktuelle Range [minDb, maxDb] fuer das Mapping.
     * Vor erster valider Berechnung: Fallback [SONOGRAM_DEFAULT_MIN_DBFS, SONOGRAM_DEFAULT_MAX_DBFS].
     * maxDb > minDb ist garantiert (minimal +1 dB Abstand).
     */
    fun currentRange(): Pair<Float, Float> {
        synchronized(lock) {
            // T176: nie einen nicht-endlichen Bereich ausgeben (NaN/Inf faerbt das ganze Sonogramm schwarz)
            if (!hasValidRange || !smoothedP5.isFinite() || !smoothedP95.isFinite()) {
                return fallbackMinDb to fallbackMaxDb
            }
            val lo = smoothedP5
            val hi = if (smoothedP95 - lo < minSpanDb) lo + minSpanDb else smoothedP95
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
            // T176: nur endliche Werte. NaN/-Inf wuerden als Perzentil in die IIR-Glaettung
            // gelangen und sie dauerhaft vergiften (NaN bleibt NaN, -Inf bleibt -Inf).
            for (b in 0 until n) {
                val v = f[b]
                if (v.isFinite()) flat[idx++] = v
            }
        }
        val usable = idx
        if (usable < minFramesForCompute) return

        // Perzentile per Quickselect (O(n)) statt Vollsortierung (O(n log n)).
        // Liefert exakt dieselben Werte wie sorted[index].
        val p5Raw  = selectKth(flat, usable, (usable * lowPercentile ).toInt().coerceAtMost(usable - 1))
        val p95Raw = selectKth(flat, usable, (usable * highPercentile).toInt().coerceAtMost(usable - 1))

        if (!hasValidRange || !smoothedP5.isFinite() || !smoothedP95.isFinite()) {
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
        const val DEFAULT_LOW_PERCENTILE  = 0.02f
        const val DEFAULT_HIGH_PERCENTILE = 0.98f
        /** T107: Standard-Mindestspanne im Auto-Kontrast (vorher nur 1 dB). */
        const val DEFAULT_MIN_SPAN_DB = 20f

        /**
         * Einmal-Berechnung ueber eine feste Frame-Liste (Analyse-Tab).
         * Kein Rolling-Window, kein IIR — bestimmt (p2, p98) per Quickselect ueber alle Werte.
         * Bei zu wenig Daten: Fallback [SONOGRAM_DEFAULT_MIN_DBFS, SONOGRAM_DEFAULT_MAX_DBFS].
         */
        fun computeStatic(
            frames: List<FloatArray>,
            fallbackMinDb: Float = SONOGRAM_DEFAULT_MIN_DBFS,
            fallbackMaxDb: Float = SONOGRAM_DEFAULT_MAX_DBFS,
            lowPercentile: Float = DEFAULT_LOW_PERCENTILE,
            highPercentile: Float = DEFAULT_HIGH_PERCENTILE,
            minSpanDb: Float = DEFAULT_MIN_SPAN_DB
        ): Pair<Float, Float> {
            if (frames.isEmpty()) return fallbackMinDb to fallbackMaxDb
            val binsPerFrame = frames.first().size
            val total = frames.size * binsPerFrame
            if (total < 64) return fallbackMinDb to fallbackMaxDb
            val flat = FloatArray(total)
            var idx = 0
            for (f in frames) {
                val n = minOf(binsPerFrame, f.size)
                for (b in 0 until n) {  // T176: nur endliche Werte
                    val v = f[b]
                    if (v.isFinite()) flat[idx++] = v
                }
            }
            if (idx < 64) return fallbackMinDb to fallbackMaxDb
            // Quickselect statt Sortierung (gleiche Werte, O(n)). flat ist bereits eine Kopie.
            val p2  = selectKth(flat, idx, (idx * lowPercentile).toInt().coerceAtMost(idx - 1))
            val p98 = selectKth(flat, idx, (idx * highPercentile).toInt().coerceAtMost(idx - 1))
            val hi = if (p98 - p2 < minSpanDb) p2 + minSpanDb else p98
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
