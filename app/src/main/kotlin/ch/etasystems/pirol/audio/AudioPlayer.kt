package ch.etasystems.pirol.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import ch.etasystems.pirol.audio.export.SegmentedRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
class AudioPlayer(
    private val context: Context,
    private val muteGate: PlaybackMuteGate = PlaybackMuteGate.shared
) : PlaybackEngine {

    enum class PlaybackState { IDLE, PLAYING }

    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    // T168: haelt dieser Player gerade das Mikrofon-Mute-Gate? (genau ein acquire pro Wiedergabe)
    private var holdsMuteGate = false

    /** T168: Zustand setzen und Mute-Gate nachfuehren (PLAYING ueber Lautsprecher = stumm). */
    private fun setState(newState: PlaybackState) {
        _state.value = newState
        synchronized(this) {
            if (newState == PlaybackState.PLAYING && !holdsMuteGate) {
                if (!OutputRoute.headphonesConnected(context)) {
                    muteGate.acquire()
                    holdsMuteGate = true
                }
            } else if (newState != PlaybackState.PLAYING && holdsMuteGate) {
                muteGate.release()
                holdsMuteGate = false
            }
        }
    }

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

    /** WAV-Datei abspielen (File-Pfad), ab [startSec] (T210, Default Anfang). */
    fun play(file: File, startSec: Float = 0f) {
        stop()
        // T294: Ultraschall-WAV (> 96 kHz) gestreamt mit Zeitdehnung bzw. heruntergerechnet
        if (playHighRateIfNeeded({ file.inputStream() }, file.length(), startSec, null, null)) return
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnErrorListener { _, what, extra ->
                setState(PlaybackState.IDLE)
                _playerError.tryEmit("Wiedergabe-Fehler (Code $what/$extra)")
                true
            }
            prepare()
            if (startSec > 0f) seekTo((startSec * 1000f).toInt())  // T210
            start()
            setOnCompletionListener { setState(PlaybackState.IDLE) }
        }
        setState(PlaybackState.PLAYING)
    }

    /**
     * WAV-Datei vollstaendig abspielen (SAF-URI).
     * T62: MediaPlayer.setDataSource(context, uri) unterstuetzt content://-URIs nativ.
     */
    fun play(uri: Uri, startSec: Float = 0f) {
        stop()
        // T294: Ultraschall-WAV (> 96 kHz) gestreamt
        if (playHighRateIfNeeded({ context.contentResolver.openInputStream(uri) }, uriLength(uri), startSec, null, null)) return
        mediaPlayer = MediaPlayer().apply {
            setDataSource(context, uri)
            setOnErrorListener { _, what, extra ->
                setState(PlaybackState.IDLE)
                _playerError.tryEmit("Wiedergabe-Fehler (Code $what/$extra)")
                true
            }
            prepare()
            if (startSec > 0f) seekTo((startSec * 1000f).toInt())  // T210
            start()
            setOnCompletionListener { setState(PlaybackState.IDLE) }
        }
        setState(PlaybackState.PLAYING)
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
    fun play(recording: SegmentedRecording, startSec: Float = 0f) {
        // T294: Ultraschall-Session ueber die Session-Achse streamen (alle Segmente, kein MediaPlayer)
        if (HighRatePlayback.applies(recording.sampleRate)) {
            stop()
            playHighRateIfNeeded({ recording.openWavStream() }, recording.virtualWavLength, startSec, null, null)
            return
        }
        if (recording.parts.size <= 1) {
            // T131: File- oder SAF-Segment
            val src = recording.firstSource ?: return
            val f = src.file
            val u = src.uriString
            if (f != null) play(f, startSec) else if (u != null) play(Uri.parse(u), startSec)
            return
        }
        stop()
        // T210: Start mitten in der Session -> passendes Segment + Offset darin
        val loc = if (startSec > 0f) recording.locate(startSec) else null
        playPart(recording, loc?.partIndex ?: 0, loc?.offsetSec ?: 0f)
    }

    private fun playPart(recording: SegmentedRecording, index: Int, seekSec: Float = 0f) {
        val part = recording.parts.getOrNull(index)
        if (part == null) {
            mediaPlayer?.release()
            mediaPlayer = null
            mediaPlayerOffsetSec = 0f
            setState(PlaybackState.IDLE)
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
                setState(PlaybackState.IDLE)
                _playerError.tryEmit("Wiedergabe-Fehler (Code $what/$extra)")
                true
            }
            mp.prepare()
            if (seekSec > 0f) mp.seekTo((seekSec * 1000f).toInt())
            mp.setOnCompletionListener { done ->
                if (mediaPlayer === done) playPart(recording, index + 1)
            }
            mp.start()
        } catch (e: Exception) {
            mp.release()
            mediaPlayer = null
            setState(PlaybackState.IDLE)
            _playerError.tryEmit("Wiedergabe-Fehler (${part.source.name})")
            return
        }
        setState(PlaybackState.PLAYING)
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
        val fileLength = uriLength(uri)
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
        // T294: ueber 96 kHz gestreamt (Zeitdehnung/heruntergerechnet), darunter unveraendert
        if (HighRatePlayback.applies(sampleRate)) {
            playHighRateStream(openStream, fileLength, sampleRate, numChannels, startSec, endSec, normalizeDbfs)
            return
        }

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
                    setState(PlaybackState.IDLE)
                }
            }
            override fun onPeriodicNotification(t: AudioTrack) {}
        })

        audioTrack = track
        trackOffsetSec = newOffsetSec
        trackSampleRate = sampleRate
        track.play()
        setState(PlaybackState.PLAYING)
    }

    // ── T294: Hochraten-Wiedergabe (> 96 kHz) ─────────────────────────────

    /** T294: Zeitdehnungsfaktor (1 = normal heruntergerechnet); in der DI aus den Prefs gesetzt */
    var timeExpansion: () -> Int = { HighRatePlayback.DEFAULT_EXPANSION }

    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null
    @Volatile private var streamGeneration = 0

    private fun uriLength(uri: Uri): Long = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    } catch (_: Exception) { 0L }

    /**
     * T294: Ist die Quelle eine WAV ueber 96 kHz, wird sie gestreamt gespielt (true). Sonst false, der Aufrufer
     * spielt wie bisher (MediaPlayer). Nur RIFF/WAVE-Dateien werden geprueft (Memos/Referenzen bleiben gleich).
     */
    private fun playHighRateIfNeeded(
        openStream: () -> InputStream?,
        fileLength: Long,
        startSec: Float,
        endSec: Float?,
        normalizeDbfs: Float?
    ): Boolean {
        val header = ByteArray(44)
        val n = try { openStream()?.use { readFully(it, header) } ?: 0 } catch (_: Exception) { 0 }
        if (n < 44) return false
        val isWav = header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'A'.code.toByte()
        if (!isWav) return false
        val hBuf = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val rate = hBuf.getInt(24)
        if (!HighRatePlayback.applies(rate)) return false
        val channels = hBuf.getShort(22).toInt().coerceIn(1, 2)
        playHighRateStream(openStream, fileLength, rate, channels, startSec, endSec, normalizeDbfs)
        return true
    }

    private fun readFully(s: InputStream, dst: ByteArray, len: Int = dst.size): Int {
        var read = 0
        while (read < len) {
            val n = s.read(dst, read, len - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    private fun skipFully(s: InputStream, bytes: Long) {
        var left = bytes
        val scratch = ByteArray(8192)
        while (left > 0) {
            val n = s.skip(left)
            if (n > 0) { left -= n; continue }
            // skip() darf 0 liefern (z.B. SAF): lesen statt springen
            val r = s.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r
        }
    }

    /**
     * T294: Streamt [startSec, endSec) einer 16-bit-WAV ueber 96 kHz in einen AudioTrack (MODE_STREAM):
     * Zeitdehnung (Samples unveraendert, Rate / Faktor) oder heruntergerechnet auf 48 kHz. Speicher bleibt
     * unabhaengig von der Laenge (Bloecke). [currentPositionSec] liefert die Session-Position.
     */
    private fun playHighRateStream(
        openStream: () -> InputStream?,
        fileLength: Long,
        sampleRate: Int,
        numChannels: Int,
        startSec: Float,
        endSec: Float?,
        normalizeDbfs: Float?
    ) {
        val plan = HighRatePlayback.plan(sampleRate, HighRatePlayback.sanitize(timeExpansion()))
        val bytesPerFrame = numChannels * 2
        val totalFrames = ((fileLength - 44L).coerceAtLeast(0L)) / bytesPerFrame
        val startFrame = (startSec.toDouble() * sampleRate).toLong().coerceIn(0L, totalFrames)
        val endFrame = if (endSec != null) (endSec.toDouble() * sampleRate).toLong().coerceIn(startFrame, totalFrames) else totalFrames
        if (endFrame <= startFrame) return

        val minBuf = AudioTrack.getMinBufferSize(plan.outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
                        .setSampleRate(plan.outRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, plan.outRate / 4 * 2))  // ca. 250 ms
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            _playerError.tryEmit("Wiedergabe-Fehler (${plan.outRate} Hz)")
            return
        }
        audioTrack = track
        trackOffsetSec = (startFrame.toDouble() / sampleRate).toFloat()
        trackSampleRate = plan.positionRate
        val gen = ++streamGeneration
        android.util.Log.i("AudioPlayer", "T294_HIGHRATE rate=$sampleRate expansion=${plan.expansion} out=${plan.outRate} frames=${endFrame - startFrame}")
        try { track.play() } catch (_: Exception) { track.release(); audioTrack = null; return }
        setState(PlaybackState.PLAYING)

        streamJob = streamScope.launch {
            var written = 0L
            try {
                // Normalisierung nur fuer begrenzte Ausschnitte (Spitze vorab gesucht, Datei bleibt unveraendert)
                val gain = if (normalizeDbfs != null && endSec != null &&
                    endFrame - startFrame <= MAX_NORMALIZE_SEC.toLong() * sampleRate) {
                    PlaybackGain.gainFor(peakOf(openStream, startFrame, endFrame, bytesPerFrame), normalizeDbfs, 30f)
                } else 1f
                val resampler = plan.resampleTo?.let { StreamingResampler(sampleRate, it) }
                openStream()?.use { s ->
                    skipFully(s, 44L + startFrame * bytesPerFrame)
                    val bytes = ByteArray(STREAM_BLOCK_FRAMES * bytesPerFrame)
                    val mono = ShortArray(STREAM_BLOCK_FRAMES)
                    var left = endFrame - startFrame
                    while (left > 0 && isActive) {
                        val want = minOf(left, STREAM_BLOCK_FRAMES.toLong()).toInt()
                        val got = readFully(s, bytes, want * bytesPerFrame) / bytesPerFrame
                        if (got <= 0) break
                        toMono(bytes, got, numChannels, mono)
                        val out = resampler?.process(mono, got) ?: mono.copyOf(got)
                        written += writeAll(track, out, gain, gen)
                        left -= got
                    }
                }
                resampler?.let { written += writeAll(track, it.flush(), gain, gen) }
                // Ende abwarten: alle geschriebenen Frames gespielt
                while (isActive && gen == streamGeneration &&
                    (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < written) {
                    delay(30)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AudioPlayer", "T294 Hochraten-Wiedergabe abgebrochen", e)
            }
            if (gen == streamGeneration && audioTrack === track) {
                try { track.stop() } catch (_: Exception) {}
                track.release()
                audioTrack = null
                setState(PlaybackState.IDLE)
            }
        }
    }

    /** Schreibt [data] (mit [gain]) nicht-blockierend, wartet bei vollem Puffer; Abbruch bei Stopp. */
    private suspend fun writeAll(track: AudioTrack, data: ShortArray, gain: Float, gen: Int): Long {
        if (gain != 1f) {
            for (i in data.indices) {
                data[i] = (data[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        var off = 0
        while (off < data.size) {
            if (gen != streamGeneration) throw kotlinx.coroutines.CancellationException("gestoppt")
            val n = track.write(data, off, data.size - off, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) throw IllegalStateException("AudioTrack.write=$n")
            if (n == 0) delay(10) else off += n
        }
        return data.size.toLong()
    }

    /** 16-bit LE nach Mono (Stereo gemittelt) */
    private fun toMono(bytes: ByteArray, frames: Int, channels: Int, out: ShortArray) {
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            out[i] = if (channels == 2) ((bb.short.toInt() + bb.short.toInt()) / 2).toShort() else bb.short
        }
    }

    /** Spitze |x| ueber [startFrame, endFrame) (alle Kanaele) */
    private fun peakOf(openStream: () -> InputStream?, startFrame: Long, endFrame: Long, bytesPerFrame: Int): Int {
        var peak = 0
        openStream()?.use { s ->
            skipFully(s, 44L + startFrame * bytesPerFrame)
            val bytes = ByteArray(STREAM_BLOCK_FRAMES * bytesPerFrame)
            var left = (endFrame - startFrame) * bytesPerFrame
            while (left > 0) {
                val got = readFully(s, bytes, minOf(left, bytes.size.toLong()).toInt())
                if (got <= 0) break
                var i = 0
                while (i + 1 < got) {
                    val v = kotlin.math.abs(((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort().toInt())
                    if (v > peak) peak = v
                    i += 2
                }
                left -= got
            }
        }
        return peak
    }

    private companion object {
        /** Frames je gelesenem Block (128 k = 256 KB Mono) */
        const val STREAM_BLOCK_FRAMES = 131_072
        /** Normalisierung (Spitze vorab suchen) nur bis zu dieser Ausschnittlaenge */
        const val MAX_NORMALIZE_SEC = 120
    }

    /** Playback stoppen (MediaPlayer und AudioTrack). */
    override fun stop() {
        streamGeneration++  // T294: laufende Hochraten-Wiedergabe beendet sich selbst
        streamJob?.cancel()
        streamJob = null
        mediaPlayer?.release()
        mediaPlayer = null
        mediaPlayerOffsetSec = 0f
        try { audioTrack?.stop() } catch (_: Exception) {}
        audioTrack?.release()
        audioTrack = null
        setState(PlaybackState.IDLE)
    }

    /** Aufraemen (in onCleared aufrufen). */
    fun release() {
        stop()
    }
}
