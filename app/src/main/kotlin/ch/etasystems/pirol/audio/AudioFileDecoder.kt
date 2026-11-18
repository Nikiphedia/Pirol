package ch.etasystems.pirol.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.data.repository.WavParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * T123: Dekodiert eine per SAF gewaehlte Audiodatei (WAV/FLAC/MP3/M4A/OGG) zu Mono-16-bit-PCM.
 * WAV wird selbst gelesen (MediaExtractor kennt WAV erst ab Android 10), alles andere
 * via MediaExtractor + MediaCodec.
 */
object AudioFileDecoder {

    private const val TAG = "AudioFileDecoder"

    /** Obergrenze der importierten Laenge (Speicherschutz) */
    const val MAX_IMPORT_SEC = 300

    /** Liefert Mono-PCM oder null, wenn das Format nicht dekodiert werden kann. */
    fun decode(context: Context, uri: Uri, displayName: String?): ExcerptWav.Pcm? {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val pcm = if (ext == "wav" || ext == "wave") {
            decodeWav(context, uri) ?: decodeWithCodec(context, uri)
        } else {
            decodeWithCodec(context, uri) ?: decodeWav(context, uri)
        }
        return pcm?.let(::capLength)
    }

    private fun capLength(pcm: ExcerptWav.Pcm): ExcerptWav.Pcm {
        val max = MAX_IMPORT_SEC * pcm.sampleRate
        return if (pcm.samples.size > max) ExcerptWav.Pcm(pcm.samples.copyOf(max), pcm.sampleRate) else pcm
    }

    private fun decodeWav(context: Context, uri: Uri): ExcerptWav.Pcm? = try {
        context.contentResolver.openInputStream(uri)?.use { WavParser.parse(it.readBytes()) }
    } catch (e: Exception) {
        Log.w(TAG, "WAV lesen fehlgeschlagen", e)
        null
    }

    private fun decodeWithCodec(context: Context, uri: Uri): ExcerptWav.Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val inFormat = extractor.getTrackFormat(trackIndex)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val maxBytes = MAX_IMPORT_SEC.toLong() * rate * channels * 2
            while (!outputDone && out.size() < maxBytes) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        val chunk = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(chunk, 0, info.size)
                        out.write(chunk)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            val bytes = out.toByteArray()
            val shorts = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            val mono = WavParser.downmixToMono(shorts, channels)
            return if (mono.isEmpty() || rate <= 0) null else ExcerptWav.Pcm(mono, rate)
        } catch (e: Exception) {
            Log.w(TAG, "Dekodieren fehlgeschlagen", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
}
