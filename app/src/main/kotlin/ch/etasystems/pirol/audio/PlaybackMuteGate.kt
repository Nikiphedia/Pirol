package ch.etasystems.pirol.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * T168: Zentraler Zustand "Wiedergabe laeuft ueber den Lautsprecher".
 *
 * Alle [AudioPlayer]-Instanzen melden Start/Ende per [acquire]/[release] (Zaehler, damit mehrere
 * gleichzeitige Player funktionieren). Solange der Zaehler > 0 ist - plus [TAIL_MS] Nachlauf -
 * ersetzt der Chunk-Emitter des RecordingService die Mikrofon-Samples durch Stille, damit die
 * eigene Wiedergabe weder in recording.wav noch in die Inferenz gelangt.
 *
 * Mute-Granularitaet: ganze Chunks (der Emitter liefert ca. 100 ms Chunks, nur bei Rueckstau bis
 * 1 s). Entschieden wird beim Emittieren des Chunks; Zeitachse und Offsets bleiben lueckenlos.
 */
class PlaybackMuteGate(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }) {

    companion object {
        /** Nachlauf nach Wiedergabe-Ende (Raumhall / Latenz des Mikrofons) */
        const val TAIL_MS = 500L

        /** Prozessweite Instanz (AudioPlayer werden teils ohne DI erzeugt) */
        val shared = PlaybackMuteGate()
    }

    private val count = AtomicInteger(0)
    private val lastReleaseMs = AtomicLong(Long.MIN_VALUE / 2)
    private val _playbackActive = MutableStateFlow(false)

    /** Laeuft gerade mindestens eine stummschaltende Wiedergabe? (fuer den UI-Hinweis) */
    val playbackActive: StateFlow<Boolean> = _playbackActive.asStateFlow()

    /** Wiedergabe ueber Lautsprecher beginnt. */
    fun acquire() {
        count.incrementAndGet()
        _playbackActive.value = true
    }

    /** Wiedergabe beendet (auch Stopp/Fehler). Ueberzaehlige Aufrufe werden ignoriert. */
    fun release() {
        while (true) {
            val c = count.get()
            if (c <= 0) return
            if (count.compareAndSet(c, c - 1)) {
                lastReleaseMs.set(clockMs())
                if (c - 1 == 0) _playbackActive.value = false
                return
            }
        }
    }

    /** Muss das Mikrofon jetzt stumm geschaltet werden (Wiedergabe oder Nachlauf)? */
    fun isMuted(nowMs: Long = clockMs()): Boolean =
        count.get() > 0 || nowMs - lastReleaseMs.get() < TAIL_MS

    /** Ersetzt die Samples durch Stille, falls gemutet; liefert true wenn gemutet wurde. */
    fun muteIfNeeded(samples: ShortArray, nowMs: Long = clockMs()): Boolean {
        if (!isMuted(nowMs)) return false
        samples.fill(0)
        return true
    }
}

/** T168: Erkennung, ob die Ausgabe ueber Kopfhoerer statt Lautsprecher laeuft. */
object OutputRoute {
    private val HEADPHONE_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID
    )

    /** true nur wenn sicher ein Kopfhoerer-Typ angeschlossen ist; im Zweifel false (= muten). */
    fun headphonesConnected(context: Context): Boolean = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }
    } catch (_: Exception) {
        false
    }
}
