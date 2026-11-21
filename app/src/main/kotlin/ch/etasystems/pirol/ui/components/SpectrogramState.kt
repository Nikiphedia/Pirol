package ch.etasystems.pirol.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.etasystems.pirol.audio.dsp.DynamicRangeMapper

/**
 * Thread-safe Ring-Buffer fuer Mel-Spektrogramm-Frames.
 *
 * Wird vom ViewModel (Coroutine-Scope) befuellt und vom Canvas (Compose-Recomposition) gelesen.
 * Zugriffe sind ueber @Synchronized abgesichert.
 *
 * T56: Optionaler [dynamicRangeMapper] wird bei jedem appendFrames()-Aufruf
 * gefuettert, damit Auto-Kontrast-Perzentile live mitlaufen.
 *
 * @param maxFrames Maximale Anzahl gespeicherter Frames (~33 s bei 93 fps, Live: 3072)
 */
class SpectrogramState(
    val maxFrames: Int = 2048
) {
    /** Optionaler Perzentil-Mapper fuer Auto-Kontrast (T56). */
    var dynamicRangeMapper: DynamicRangeMapper? = null
    // Ring-Buffer: Array von FloatArray-Referenzen
    private val buffer = arrayOfNulls<FloatArray>(maxFrames)
    private var writeIndex = 0
    private var count = 0

    // Compose-Trigger: Aendert sich bei jedem appendFrames()-Aufruf → Recomposition
    var frameVersion by mutableIntStateOf(0)
        private set

    /**
     * T176: true, solange Frames vorhanden sind. Beobachtbar (Compose-State), damit die Anzeige
     * zwischen Platzhalter und Sonogramm wechselt, ohne auf eine fremde Recomposition zu warten.
     * Wird nur bei echtem Wechsel geschrieben (kein Recomposition-Takt pro Chunk).
     */
    var hasFrames by mutableStateOf(false)
        private set

    /** Wird bei jedem clear() erhoeht — erkennt verspaetete Hintergrund-Ergebnisse (T61) */
    private var generation = 0

    /** T160: Anzahl per [appendFrames] angehaengter Frames (monoton, nie zurueckgesetzt). */
    private var appendedTotal = 0L

    /** T160: erhoeht sich bei clear()/prependFrames(), d.h. wenn der Inhalt nicht nur rechts waechst. */
    private var structureVersion = 0

    /**
     * T160: Frames fuer das inkrementelle Rendern. Hat sich die Struktur geaendert, wird [forceFull]
     * gesetzt oder sind seit [sinceAppended] mindestens [width] Frames dazugekommen, kommen die
     * letzten [width] Frames (vollstaendiger Neuaufbau). Sonst nur die neuen Frames seit [sinceAppended].
     */
    @Synchronized
    fun snapshotForRender(
        sinceAppended: Long,
        sinceStructure: Int,
        width: Int,
        forceFull: Boolean
    ): RenderSnapshot {
        val fresh = appendedTotal - sinceAppended
        // T176: auch neu aufbauen, wenn mehr neue Frames gemeldet sind als im Puffer liegen
        // (sonst wuerde der Ring weniger Spalten schreiben als er weiterschiebt).
        val available = minOf(count, maxFrames)
        val full = forceFull || sinceStructure != structureVersion || fresh < 0 || fresh >= width ||
            fresh > available
        val frames = if (full) getVisibleFrames(width) else getVisibleFrames(fresh.toInt())
        return RenderSnapshot(appendedTotal, structureVersion, full, frames)
    }

    /** Aktuelle Generation (fuer [prependFrames]) */
    val currentGeneration: Int
        @Synchronized get() = generation

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
        if (newFrames.isEmpty()) return
        appendLocked(newFrames)
        syncHasFrames()
        frameVersion++
    }

    /**
     * T176: Inhalt atomar durch [frames] ersetzen (= clear() + appendFrames() ohne leeren
     * Zwischenzustand). Fuer den Backfill nach Ueberlauf: der Renderer sieht entweder den alten
     * oder den neuen Inhalt, nie einen leeren Puffer, und die Anzeige faellt nicht auf den
     * Platzhalter zurueck.
     */
    @Synchronized
    fun replaceAll(frames: List<FloatArray>) {
        clearLocked()
        if (frames.isNotEmpty()) appendLocked(frames)
        syncHasFrames()
        frameVersion++
    }

    private fun appendLocked(newFrames: List<FloatArray>) {
        for (frame in newFrames) {
            buffer[writeIndex] = frame
            writeIndex = (writeIndex + 1) % maxFrames
            count++
        }
        appendedTotal += newFrames.size
        // T56: DynamicRangeMapper fuettern (falls gesetzt). Mapper ist selbst thread-safe.
        dynamicRangeMapper?.let { mapper ->
            for (frame in newFrames) mapper.update(frame)
        }
    }

    /**
     * Fuegt Frames VOR den bereits vorhandenen ein (zeitlich aelter), z.B. Preroll,
     * das im Hintergrund berechnet wird, waehrend Live-Frames schon eintreffen (T61).
     * Bei Ueberlauf bleiben die neuesten [maxFrames] Frames erhalten.
     *
     * @param expectedGeneration Generation beim Start der Berechnung; wurde der Buffer
     *   inzwischen geleert (clear()), werden die Frames verworfen.
     */
    @Synchronized
    fun prependFrames(olderFrames: List<FloatArray>, expectedGeneration: Int? = null) {
        if (olderFrames.isEmpty()) return
        if (expectedGeneration != null && expectedGeneration != generation) return
        val existing = getVisibleFrames(maxFrames)
        val combined = olderFrames + existing
        val keep = combined.subList(maxOf(0, combined.size - maxFrames), combined.size)
        buffer.fill(null)
        for (i in keep.indices) buffer[i] = keep[i]
        writeIndex = keep.size % maxFrames
        count = keep.size
        structureVersion++
        // T56: Mapper kennt die Reihenfolge nicht (Perzentile) → einfach nachfuettern
        dynamicRangeMapper?.let { mapper ->
            for (frame in olderFrames) mapper.update(frame)
        }
        syncHasFrames()
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
        clearLocked()
        syncHasFrames()
        frameVersion++
    }

    /** T176: [hasFrames] nur am Ende einer Aenderung und nur bei Wechsel schreiben (kein Zwischenzustand sichtbar). */
    private fun syncHasFrames() {
        val v = count > 0
        if (hasFrames != v) hasFrames = v
    }

    private fun clearLocked() {
        buffer.fill(null)
        writeIndex = 0
        count = 0
        generation++
        structureVersion++
        // T56: Mapper mit Buffer zuruecksetzen — sonst rollt Auto-Kontrast aus alter Session.
        dynamicRangeMapper?.reset()
    }
}

/** T160: Ergebnis von [SpectrogramState.snapshotForRender]. */
class RenderSnapshot(
    val appendedTotal: Long,
    val structureVersion: Int,
    /** true = [frames] sind die letzten Fensterbreite-Frames (Neuaufbau), false = nur die neuen Frames. */
    val full: Boolean,
    val frames: List<FloatArray>
)
