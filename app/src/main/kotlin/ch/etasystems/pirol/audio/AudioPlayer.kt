package ch.etasystems.pirol.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Einfacher Audio-Player fuer WAV-Dateien.
 * Wrapper um Android MediaPlayer mit StateFlow fuer UI-Anbindung.
 *
 * - play(file)              : ganze Datei via MediaPlayer
 * - playFromOffset(file, s, e): Zeitabschnitt via AudioTrack (T48)
 */
class AudioPlayer {

    enum class PlaybackState { IDLE, PLAYING, PAUSED }

    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** WAV-Datei vollstaendig abspielen */
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

    /**
     * Zeitabschnitt einer WAV-Datei abspielen (16-bit PCM Mono/Stereo).
     *
     * Liest die Samples direkt ab Byte-Offset aus der Datei und spielt sie
     * via AudioTrack im Static-Modus ab. Stoppt automatisch am Ende des
     * Abschnitts.
     *
     * @param startSec Startposition in Sekunden (relativ zum Datei-Anfang)
     * @param endSec   Endposition in Sekunden (null = bis Dateiende)
     */
    fun playFromOffset(file: File, startSec: Float, endSec: Float? = null) {
        stop()

        // WAV-Header lesen (44 Bytes, Standard RIFF/PCM)
        val header = ByteArray(44)
        try {
            file.inputStream().use { it.read(header) }
        } catch (_: Exception) { return }

        val hBuf = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val sampleRate   = hBuf.getInt(24)
        val numChannels  = hBuf.getShort(22).toInt().coerceIn(1, 2)
        val bytesPerFrame = numChannels * 2  // 16-bit PCM

        // Byte-Grenzen berechnen — auf Frame-Grenze alignen
        val fileDataBytes = (file.length() - 44L).coerceAtLeast(0L)
        val totalFrames   = fileDataBytes / bytesPerFrame
        val startFrame    = (startSec * sampleRate).toLong().coerceIn(0L, totalFrames)
        val endFrame      = if (endSec != null) {
            (endSec * sampleRate).toLong().coerceIn(startFrame, totalFrames)
        } else {
            totalFrames
        }

        val startByte = 44L + startFrame * bytesPerFrame
        val endByte   = 44L + endFrame   * bytesPerFrame
        val dataSize  = (endByte - startByte).coerceAtLeast(0L).toInt()
        if (dataSize <= 0) return

        // Samples aus Datei lesen
        val data = ByteArray(dataSize)
        try {
            file.inputStream().use { stream ->
                stream.skip(startByte)
                var read = 0
                while (read < dataSize) {
                    val n = stream.read(data, read, dataSize - read)
                    if (n < 0) break
                    read += n
                }
            }
        } catch (_: Exception) { return }

        val channelConfig = if (numChannels == 2) AudioFormat.CHANNEL_OUT_STEREO
                            else AudioFormat.CHANNEL_OUT_MONO

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(dataSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (_: Exception) { return }

        track.write(data, 0, dataSize)

        // Completion-Callback: Marker am letzten Frame
        val numFrames = dataSize / bytesPerFrame
        track.setNotificationMarkerPosition(numFrames)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) {
                t.release()
                if (audioTrack === t) {
                    audioTrack = null
                    _state.value = PlaybackState.IDLE
                }
            }
            override fun onPeriodicNotification(t: AudioTrack) {}
        })

        audioTrack = track
        track.play()
        _state.value = PlaybackState.PLAYING
    }

    /** Pause/Resume Toggle (nur fuer MediaPlayer-Wiedergabe) */
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

    /** Playback stoppen (MediaPlayer und AudioTrack) */
    fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
        try { audioTrack?.stop() } catch (_: Exception) {}
        audioTrack?.release()
        audioTrack = null
        _state.value = PlaybackState.IDLE
    }

    /** Aufraemen (in onCleared aufrufen) */
    fun release() {
        stop()
    }
}
