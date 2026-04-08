package ch.etasystems.pirol.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Thread-safe Ring-Buffer fuer Mel-Spektrogramm-Frames.
 *
 * Wird vom ViewModel (Coroutine-Scope) befuellt und vom Canvas (Compose-Recomposition) gelesen.
 * Zugriffe sind ueber @Synchronized abgesichert.
 *
 * @param maxFrames Maximale Anzahl gespeicherter Frames (~22s bei 93 fps)
 */
class SpectrogramState(
    val maxFrames: Int = 2048
) {
    // Ring-Buffer: Array von FloatArray-Referenzen
    private val buffer = arrayOfNulls<FloatArray>(maxFrames)
    private var writeIndex = 0
    private var count = 0

    // Compose-Trigger: Aendert sich bei jedem appendFrames()-Aufruf → Recomposition
    var frameVersion by mutableIntStateOf(0)
        private set

    /** Gesamtzahl bisher empfangener Frames (inkl. ueberschriebener) */
    val totalFrames: Int
        @Synchronized get() = count

    /** Anzahl aktuell im Buffer vorhandener Frames */
    val availableFrames: Int
        @Synchronized get() = minOf(count, maxFrames)

    /**
     * Fuegt neue Frames hinzu. Ueberschreibt aelteste Eintraege bei Ueberlauf.
     * Thread-safe — kann von Coroutine-Scope aufgerufen werden.
     */
    @Synchronized
    fun appendFrames(newFrames: List<FloatArray>) {
        for (frame in newFrames) {
            buffer[writeIndex] = frame
            writeIndex = (writeIndex + 1) % maxFrames
            count++
        }
        // Recomposition-Trigger (muss ausserhalb von synchronized in Compose laufen,
        // aber mutableIntStateOf ist thread-safe fuer primitive Schreibzugriffe)
        frameVersion++
    }

    /**
     * Gibt die letzten [requestedCount] Frames zurueck, zeitlich geordnet (aeltester zuerst).
     * Falls weniger vorhanden als angefragt, werden nur die vorhandenen zurueckgegeben.
     * Thread-safe — wird von Canvas-Composable aufgerufen.
     */
    @Synchronized
    fun getVisibleFrames(requestedCount: Int): List<FloatArray> {
        val available = minOf(count, maxFrames)
        val numToReturn = minOf(requestedCount, available)
        if (numToReturn == 0) return emptyList()

        val result = ArrayList<FloatArray>(numToReturn)
        // Start-Index im Ring: aeltester der angeforderten Frames
        val startOffset = available - numToReturn
        val ringStart = if (count <= maxFrames) {
            startOffset
        } else {
            (writeIndex + startOffset) % maxFrames
        }

        for (i in 0 until numToReturn) {
            val idx = (ringStart + i) % maxFrames
            buffer[idx]?.let { result.add(it) }
        }
        return result
    }

    /**
     * Leert den Buffer (z.B. bei Aufnahme-Neustart).
     */
    @Synchronized
    fun clear() {
        buffer.fill(null)
        writeIndex = 0
        count = 0
        frameVersion++
    }
}
