package ch.etasystems.pirol.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** T173: Minimale Sicht auf den Player, damit die Controller-Logik ohne Android testbar ist. */
interface PlaybackEngine {
    val state: StateFlow<AudioPlayer.PlaybackState>
    fun stop()
}

/**
 * T173: Zentrale Wiedergabe. Genau ein [AudioPlayer] (Koin-Singleton) fuer die ganze App, damit
 * immer nur eine Wiedergabe laeuft und jede Abspielstelle dasselbe Start/Stopp-Verhalten hat.
 *
 * Jede Wiedergabe hat einen Schluessel (z.B. "det:<id>", "ep:<id>@<start>", "memo:<id>"):
 * - [toggle]: laeuft genau dieser Schluessel, wird gestoppt; sonst startet er und verdraengt den laufenden.
 * - [playingKey]: Schluessel der laufenden Wiedergabe, null bei Ende/Stopp.
 *
 * Die eigentliche Wiedergabe startet der Aufrufer im `start`-Lambda am [AudioPlayer]; dessen
 * play*-Methoden stoppen eine laufende Wiedergabe selbst.
 */
class PlaybackController(
    private val engine: PlaybackEngine,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val _playingKey = MutableStateFlow<String?>(null)
    val playingKey: StateFlow<String?> = _playingKey.asStateFlow()

    init {
        // Ende (oder Fehler) der Wiedergabe: Schluessel zuruecksetzen
        scope.launch {
            engine.state.collect { st ->
                if (st == AudioPlayer.PlaybackState.IDLE) _playingKey.value = null
            }
        }
    }

    /** Laeuft dieser Schluessel gerade (oder ist pausiert)? */
    fun isPlaying(key: String): Boolean = _playingKey.value == key

    /** Laeuft [key], wird gestoppt (true zurueck = gestoppt), sonst wird [start] ausgefuehrt. */
    fun toggle(key: String, start: () -> Unit): Boolean {
        if (isPlaying(key)) {
            stop()
            return true
        }
        play(key, start)
        return false
    }

    /** [start] ausfuehren (verdraengt eine laufende Wiedergabe) und [key] als laufend merken. */
    fun play(key: String, start: () -> Unit) {
        engine.stop()
        start()
        // Startete nichts (Datei fehlt o.ae.), bleibt der Player IDLE -> kein Schluessel
        _playingKey.value = if (engine.state.value != AudioPlayer.PlaybackState.IDLE) key else null
    }

    /** Wiedergabe beenden. */
    fun stop() {
        engine.stop()
        _playingKey.value = null
    }
}

/** T173: Schluessel-Konventionen, damit Live, Analyse und Karte dieselbe Detektion als "laufend" erkennen. */
object PlaybackKeys {
    const val DETECTION_PREFIX = "det:"
    fun detection(id: String) = DETECTION_PREFIX + id
    fun episode(id: String, startSec: Float) = "ep:$id@$startSec"
    fun memo(id: String) = "memo:$id"
    fun reference(id: String) = "ref:$id"
    const val SESSION = "session"
    const val COMPARE_DETECTION = "cmp:det"
    const val COMPARE_REFERENCE = "cmp:ref"
    /** Id einer Detektion aus ihrem Schluessel, sonst null */
    fun detectionIdOf(key: String?): String? = key?.takeIf { it.startsWith(DETECTION_PREFIX) }?.removePrefix(DETECTION_PREFIX)
}
