package ch.etasystems.pirol.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import ch.etasystems.pirol.audio.export.SegmentedRecording
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream

/**
 * Einfacher Audio-Player fuer WAV-Dateien.
 * Wrapper um Android MediaPlayer mit StateFlow fuer UI-Anbindung.
 *
 * - play(file) / play(uri)                 : ganze Datei via MediaPlayer
 * - playFromOffset(file/uri, s, e)          : Zeitabschnitt via AudioTrack (T48)
 *
 * T62: Context-Injektion fuer SAF-URI-Support (ContentResolver).
 *      play(Uri) nutzt MediaPlayer.setDataSource(context, uri) → content://-URIs nativ.
 *      playFromOffset(Uri) liest Samples via ContentResolver.openInputStream().
 * T62: playerError SharedFlow fuer Snackbar-Feedback bei MediaPlayer-Fehlern (AC7).
 */
class AudioPlayer(private val context: Context) {

    enum class PlaybackState { IDLE, PLAYING, PAUSED }

    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** T62: Fehler-Kanal — MediaPlayer-Fehler werden hier emittiert (AC7). */
    private val _playerError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playerError: SharedFlow<String> = _playerError.asSharedFlow()

    // T107: Position fuer den Abspiel-Zeiger. Bei AudioTrack-Wiedergabe (playFromOffset) ist die
    // Kopfposition relativ zum Clip-Start, daher Offset + Samplerate merken.
    private var trackOffsetSec = 0f
    private var trackSampleRate = 0
    // T130: Start des gerade gespielten Segments auf der Session-Achse (MediaPlayer-Wiedergabe)
    private var mediaPlayerOffsetSec = 0f

    /**
     * T107: Aktuelle Abspielposition in Sekunden relativ zum Datei-Anfang, null wenn nichts spielt.
     * Guenstig genug fuer Polling (~10 Hz).
     */
    fun currentPositionSec(): Float? {
        if (_state.value == PlaybackState.IDLE) return null
        try {
            mediaPlayer?.let { return mediaPlayerOffsetSec + it.currentPosition / 1000f }
            val t = audioTrack ?: return null
            if (trackSampleRate <= 0) return null
            return trackOffsetSec + t.playbackHeadPosition.toFloat() / trackSampleRate
        } catch (_: Exception) {
            return null  // Player wurde gerade freigegeben
        }
    }

    /** WAV-Datei vollstaendig abspielen (File-Pfad). */
    fun play(file: File) {
        stop()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnErrorListener { _, what, extra ->
                _state.value = PlaybackState.IDLE
                _playerError.tryEmit("Wiedergabe-Fehler (Code $what/$extra)")
                true
            }
            prepare()
            start()
            setOnCompletionListener { _state.value = PlaybackState.IDLE }
        }
        _state.value = PlaybackState.PLAYING
    }

    /**
     * WAV-Datei vollstaendig abspielen (SAF-URI).
     * T62: MediaPlayer.setDataSource(context, uri) unterstuetzt content://-URIs nativ.
     */
    fun play(uri: Uri) {
        stop()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(context, uri)
            setOnErrorListener { _, what, extra ->
                _state.value = PlaybackState.IDLE
                _playerError.tryEmit("Wiedergabe-Fehler (Code $what/$extra)")
                true
            }
            prepare()
            start()
            setOnCompletionListener { _state.value = PlaybackState.IDLE }
        }
        _state.value = PlaybackState.PLAYING
    }

    /**
     * Zeitabschnitt einer WAV-Datei abspielen (16-bit PCM Mono/Stereo) — File-Variante.
     *
     * Liest die Samples direkt ab Byte-Offset aus der Datei und spielt sie
     * via AudioTrack im Static-Modus ab. Stoppt automatisch am Ende des
     * Abschnitts.
     *
     * @param startSec Startposition in Sekunden (relativ zum Datei-Anfang)
     * @param endSec   Endposition in Sekunden (null = bis Dateiende)
     */
    fun playFromOffset(file: File, startSec: Float, endSec: Float? = null, normalizeDbfs: Float? = null) {
        stop()
        playFromOffsetInternal(
            openStream = { file.inputStream() },
            fileLength  = file.length(),
            startSec    = startSec,
            endSec      = endSec,
            normalizeDbfs = normalizeDbfs
        )
    }

    /**
     * T130: Zeitabschnitt einer Session-Aufnahme (alle WAV-Segmente, Session-Zeitachse) abspielen.
     * Ueberspannt der Abschnitt eine Segmentgrenze, werden beide Teile lueckenlos gespielt.
     */
    fun playFromOffset(recording: SegmentedRecording, startSec: Float, endSec: Float? = null, normalizeDbfs: Float? = null) {
        stop()
        playFromOffsetInternal(
            openStream = { recording.openWavStream() },
            fileLength  = recording.virtualWavLength,
            startSec    = startSec,
            endSec      = endSec,
            normalizeDbfs = normalizeDbfs
        )
    }

    /**
     * T130: Ganze Session-Aufnahme abspielen — Segmente nacheinander via MediaPlayer.
     * [currentPositionSec] liefert die Position auf der Session-Zeitachse.
     */
    fun play(recording: SegmentedRecording) {
        if (recording.parts.size <= 1) {
            // T131: File- oder SAF-Segment
            val src = recording.firstSource ?: return
            val f = src.file
            val u = src.uriString
            if (f != null) play(f) else if (u != null) play(Uri.parse(u))
            return
        }
        stop()
        playPart(recording, 0)
    }

    private fun playPart(recording: SegmentedRecording, index: Int) {
        val part = recording.parts.getOrNull(index)
        if (part == null) {
            mediaPlayer?.release()
            mediaPlayer = null
            mediaPlayerOffsetSec = 0f
            _state.value = PlaybackState.IDLE
            return
        }
        mediaPlayer?.release()
        mediaPlayerOffsetSec = if (recording.sampleRate > 0) part.startFrame.toFloat() / recording.sampleRate else 0f
        val mp = MediaPlayer()
        mediaPlayer = mp
        try {
            val partFile = part.file
            if (partFile != null) mp.setDataSource(partFile.absolutePath)
            else mp.setDataSource(context, Uri.parse(part.source.uriString ?: error("Segment ohne Quelle")))
            mp.setOnErrorListener { _, what, extra ->
                _state.value = PlaybackState.IDLE
                _playerError.tryEmit("Wiedergabe-Fehler (Code $what/$extra)")
                true
            }
            mp.prepare()
            mp.setOnCompletionListener { done ->
                if (mediaPlayer === done) playPart(recording, index + 1)
            }
            mp.start()
        } catch (e: Exception) {
            mp.release()
            mediaPlayer = null
            _state.value = PlaybackState.IDLE
            _playerError.tryEmit("Wiedergabe-Fehler (${part.source.name})")
            return
        }
        _state.value = PlaybackState.PLAYING
    }

    /**
     * Zeitabschnitt einer WAV-Datei abspielen (16-bit PCM Mono/Stereo) — SAF-URI-Variante.
     * T62: Oeffnet ContentResolver-Streams via Uri.
     *
     * @param startSec Startposition in Sekunden (relativ zum Datei-Anfang)
     * @param endSec   Endposition in Sekunden (null = bis Dateiende)
     */
    fun playFromOffset(uri: Uri, startSec: Float, endSec: Float? = null, normalizeDbfs: Float? = null) {
        stop()
        val fileLength = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (_: Exception) { 0L }
        playFromOffsetInternal(
            openStream = { context.contentResolver.openInputStream(uri) },
            fileLength  = fileLength,
            startSec    = startSec,
            endSec      = endSec,
            normalizeDbfs = normalizeDbfs
        )
    }

    /**
     * Gemeinsame Implementierung fuer File- und Uri-Variante von playFromOffset.
     * [openStream] wird zweimal aufgerufen: einmal fuer den Header, einmal fuer die Daten.
     * Beide Streams werden nach Verwendung geschlossen.
     */
    private fun playFromOffsetInternal(
        openStream: () -> InputStream?,
        fileLength: Long,
        startSec:   Float,
        endSec:     Float?,
        normalizeDbfs: Float? = null
    ) {
        // WAV-Header lesen (44 Bytes, Standard RIFF/PCM)
        val header = ByteArray(44)
        try {
            openStream()?.use { it.read(header) } ?: return
        } catch (_: Exception) { return }

        val hBuf          = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val sampleRate    = hBuf.getInt(24)
        val numChannels   = hBuf.getShort(22).toInt().coerceIn(1, 2)
        val bytesPerFrame = numChannels * 2  // 16-bit PCM

        // Byte-Grenzen berechnen — auf Frame-Grenze alignen
        val fileDataBytes = (fileLength - 44L).coerceAtLeast(0L)
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
        val newOffsetSec = if (sampleRate > 0) startFrame.toFloat() / sampleRate else 0f

        // Samples aus Stream lesen
        val data = ByteArray(dataSize)
        try {
            openStream()?.use { stream ->
                stream.skip(startByte)
                var read = 0
                while (read < dataSize) {
                    val n = stream.read(data, read, dataSize - read)
                    if (n < 0) break
                    read += n
                }
            } ?: return
        } catch (_: Exception) { return }
        // T122: Abspielen leiser Aufnahmen auf Zielpegel anheben (nur Wiedergabe, Datei unveraendert)
        if (normalizeDbfs != null) PlaybackGain.normalizeInPlace(data, normalizeDbfs)

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
        trackOffsetSec = newOffsetSec
        trackSampleRate = sampleRate
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

    /** Playback stoppen (MediaPlayer und AudioTrack). */
    fun stop() {
        mediaPlayer?.release()
        mediaPlayer = null
        mediaPlayerOffsetSec = 0f
        try { audioTrack?.stop() } catch (_: Exception) {}
        audioTrack?.release()
        audioTrack = null
        _state.value = PlaybackState.IDLE
    }

    /** Aufraemen (in onCleared aufrufen). */
    fun release() {
        stop()
    }
}
