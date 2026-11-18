package ch.etasystems.pirol.audio.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * T116: Audio-Encoder auf Basis von MediaCodec (keine nativen Bibliotheken).
 *
 * - FLAC: `audio/flac`-Encoder; MediaCodec liefert rohe Frames, die Datei wird mit
 *   "fLaC" + STREAMINFO (aus Codec-Config oder selbst erzeugt) zusammengesetzt.
 * - M4A (AAC-LC): MediaCodec + MediaMuxer.
 * - MP3: nur falls ein `audio/mpeg`-Encoder existiert (auf fast allen Geraeten nicht der Fall);
 *   dann werden die rohen MP3-Frames direkt geschrieben.
 */
object ExcerptEncoders {

    private const val MIME_FLAC = "audio/flac"
    private const val MIME_AAC = "audio/mp4a-latm"
    private const val MIME_MP3 = "audio/mpeg"
    private const val TIMEOUT_US = 10_000L
    private const val CHUNK_BYTES = 8192

    fun hasEncoder(mime: String): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    } catch (_: Exception) { false }

    val flacAvailable: Boolean get() = hasEncoder(MIME_FLAC)
    val aacAvailable: Boolean get() = hasEncoder(MIME_AAC)
    val mp3Available: Boolean get() = hasEncoder(MIME_MP3)

    /** Encodiert nach FLAC. [onProgress] 0..1. Wirft Exception bei Fehler. */
    fun encodeFlac(pcm: ExcerptWav.Pcm, target: File, onProgress: (Float) -> Unit) {
        val format = MediaFormat.createAudioFormat(MIME_FLAC, pcm.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
        }
        val frames = java.io.ByteArrayOutputStream()
        var config: ByteArray? = null
        runCodecSimple(MIME_FLAC, format, pcm, onProgress) { data, flags ->
            if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                config = data
            } else {
                if (frames.size() == 0 && !FlacStreamInfo.isFrameStart(data)) {
                    throw IllegalStateException("FLAC-Encoder lieferte unerwartetes Format")
                }
                frames.write(data)
            }
        }
        if (frames.size() == 0) throw IllegalStateException("FLAC-Encoder lieferte keine Daten")
        val total = pcm.samples.size.toLong()
        val info = config?.let { FlacStreamInfo.extractFromConfig(it) }?.let { FlacStreamInfo.withTotalSamples(it, total) }
            ?: FlacStreamInfo.build(4096, 4096, pcm.sampleRate, 1, 16, total)
        target.outputStream().buffered().use { out ->
            out.write(FlacStreamInfo.fileHeader(info))
            frames.writeTo(out)
        }
    }

    /** Encodiert nach MP3 (nur wenn [mp3Available]). */
    fun encodeMp3(pcm: ExcerptWav.Pcm, target: File, onProgress: (Float) -> Unit) {
        val format = MediaFormat.createAudioFormat(MIME_MP3, pcm.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
        }
        target.outputStream().buffered().use { out ->
            runCodecSimple(MIME_MP3, format, pcm, onProgress) { data, flags ->
                if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) out.write(data)
            }
        }
    }

    /** Encodiert nach M4A (AAC-LC, 64 kbit/s mono). */
    fun encodeM4a(pcm: ExcerptWav.Pcm, target: File, onProgress: (Float) -> Unit) {
        val format = MediaFormat.createAudioFormat(MIME_AAC, pcm.sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        }
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        try {
            runCodec(MIME_AAC, format, pcm, onProgress, onFormat = { outFormat ->
                track = muxer.addTrack(outFormat)
                muxer.start()
                started = true
            }, onBuffer = { data, flags, ptsUs ->
                if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && started) {
                    val info = MediaCodec.BufferInfo().apply { set(0, data.size, ptsUs, 0) }
                    muxer.writeSampleData(track, ByteBuffer.wrap(data), info)
                }
            })
        } finally {
            if (started) try { muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    private fun runCodec(
        mime: String,
        format: MediaFormat,
        pcm: ExcerptWav.Pcm,
        onProgress: (Float) -> Unit,
        onFormat: (MediaFormat) -> Unit = {},
        onBuffer: (ByteArray, Int, Long) -> Unit
    ) {
        val codec = MediaCodec.createEncoderByType(mime)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val bytes = ByteBuffer.allocate(pcm.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(pcm.samples)
            val pcmBytes = bytes.array()
            var inPos = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val n = minOf(CHUNK_BYTES, pcmBytes.size - inPos, buf.capacity())
                        val ptsUs = inPos / 2L * 1_000_000L / pcm.sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.clear()
                            buf.put(pcmBytes, inPos, n)
                            codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
                            inPos += n
                            onProgress(inPos.toFloat() / pcmBytes.size)
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                    out >= 0 -> {
                        val buf = codec.getOutputBuffer(out)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val data = ByteArray(info.size)
                            buf.get(data)
                            onBuffer(data, info.flags, info.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
    }

    private fun runCodecSimple(
        mime: String,
        format: MediaFormat,
        pcm: ExcerptWav.Pcm,
        onProgress: (Float) -> Unit,
        onBuffer: (ByteArray, Int) -> Unit
    ) = runCodec(mime, format, pcm, onProgress, onFormat = {}, onBuffer = { d, f, _ -> onBuffer(d, f) })
}
