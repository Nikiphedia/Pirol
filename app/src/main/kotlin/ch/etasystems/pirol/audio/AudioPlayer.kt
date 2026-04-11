package ch.etasystems.pirol.audio

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Einfacher Audio-Player fuer WAV-Dateien.
 * Wrapper um Android MediaPlayer mit StateFlow fuer UI-Anbindung.
 */
class AudioPlayer {

    enum class PlaybackState { IDLE, PLAYING, PAUSED }

    private var mediaPlayer: MediaPlayer? = null
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Audio-Datei abspielen (WAV oder MP3) */
    fun play(file: File) {
        stop()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
            setOnCompletionListener {
                _state.value = PlaybackState.IDLE
            }
        }
        _state.value = PlaybackState.PLAYING
    }

    /** Audio von URL streamen (fuer Xeno-Canto Preview) */
    fun playFromUrl(url: String) {
        stop()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(url)
            setOnPreparedListener { mp ->
                mp.start()
                _state.value = PlaybackState.PLAYING
            }
            setOnCompletionListener {
                _state.value = PlaybackState.IDLE
            }
            setOnErrorListener { _, _, _ ->
                _state.value = PlaybackState.IDLE
                true
            }
            prepareAsync()
        }
    }

    /** Pause/Resume Toggle */
    fun togglePause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            _state.value = PlaybackState.PAUSED
        } else if (_state.value == PlaybackState.PAUSED) {
            mp.start()
            _state.value = PlaybackState.PLAYING
        }
    }

    /** Playback stoppen */
    fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
        _state.value = PlaybackState.IDLE
    }

}
