package ch.etasystems.pirol.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import ch.etasystems.pirol.data.repository.StreamingWavWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Sprach-Memo Recorder: nimmt WAV 16-bit PCM Mono 16 kHz auf.
 *
 * Nutzt AudioRecord direkt (separate Pipeline, blockiert RecordingService nicht).
 * Schreibt via StreamingWavWriter (File- und SAF-Modus).
 *
 * Thread-safe: startRecording / stopRecording sind suspend funs auf Dispatchers.IO.
 * Nicht thread-safe gegen parallele Starts — isRecording pruefen.
 *
 * Lifetime: Feld im ViewModel. release() in onCleared() aufrufen.
 */
class VoiceMemoRecorder {

    companion object {
        private const val TAG = "VoiceMemoRecorder"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_DURATION_SEC = 60
        private const val MAX_SAMPLES = SAMPLE_RATE * MAX_DURATION_SEC
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    /** Emittiert Unit wenn der 60s-Cap greift — Screen soll Snackbar anzeigen. */
    private val _capReached = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val capReached: SharedFlow<Unit> = _capReached.asSharedFlow()

    val maxDurationSec: Int = MAX_DURATION_SEC

    private var audioRecord: AudioRecord? = null
    private var writer: StreamingWavWriter? = null
    private var recordingJob: Job? = null

    // ── Aufnahme starten (File-Modus) ────────────────────────────────

    /**
     * Startet die Aufnahme in eine WAV-Datei (File-Modus).
     * Laeuft auf Dispatchers.IO. Gibt sofort zurueck (Recording im Hintergrund).
     *
     * @param targetFile Zieldatei — wird erzeugt / ueberschrieben
     * @return Result.success oder Result.failure bei AudioRecord-Fehler
     */
    suspend fun startRecording(targetFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        if (_isRecording.value) return@withContext Result.failure(
            IllegalStateException("Aufnahme laeuft bereits")
        )
        val w = StreamingWavWriter.forFile(targetFile, SAMPLE_RATE)
        startInternal(w)
    }

    /**
     * Startet die Aufnahme in einen SAF-URI (SAF-Modus).
     * Laeuft auf Dispatchers.IO.
     *
     * @param context Context fuer ContentResolver
     * @param targetUri DocumentFile-URI (muss beschreibbar sein)
     * @return Result.success oder Result.failure bei Fehler
     */
    suspend fun startRecordingSaf(context: Context, targetUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (_isRecording.value) return@withContext Result.failure(
                IllegalStateException("Aufnahme laeuft bereits")
            )
            val w = try {
                StreamingWavWriter.forSaf(context, targetUri, SAMPLE_RATE)
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }
            startInternal(w)
        }

    // ── Aufnahme stoppen ─────────────────────────────────────────────

    /**
     * Stoppt die laufende Aufnahme und finalisiert den WAV-Header.
     * Sicher aufzurufen auch wenn keine Aufnahme laeuft (no-op).
     */
    suspend fun stopRecording(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!_isRecording.value) return@withContext Result.success(Unit)
        stopInternal()
        Result.success(Unit)
    }

    // ── Cleanup ───────────────────────────────────────────────────────

    /** Ressourcen freigeben (in ViewModel.onCleared aufrufen). */
    fun release() {
        stopInternal()
    }

    // ── Interne Implementierung ───────────────────────────────────────

    private fun startInternal(w: StreamingWavWriter): Result<Unit> {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) {
            return Result.failure(IllegalStateException("AudioRecord nicht verfuegbar (minBuf=$minBuf)"))
        }
        // Puffer: 160ms bei 16kHz = 2560 Samples — gute Balance Latenz vs Overhead
        val bufSamples = maxOf(minBuf / 2, 2560)
        val ar = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSamples * 2  // Bytes
            )
        } catch (e: SecurityException) {
            return Result.failure(e)
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return Result.failure(IllegalStateException("AudioRecord konnte nicht initialisiert werden"))
        }
        try {
            w.open()
        } catch (e: Exception) {
            ar.release()
            return Result.failure(e)
        }
        ar.startRecording()
        audioRecord = ar
        writer = w
        _isRecording.value = true

        // Recording-Loop in Coroutine auf Dispatchers.IO — non-blocking fuer Caller
        // Da wir withContext(IO) bereits sind, starten wir den Loop direkt in einem separaten Thread
        val thread = Thread({
            val buf = ShortArray(bufSamples)
            var totalSamples = 0L
            try {
                while (_isRecording.value) {
                    val read = ar.read(buf, 0, buf.size)
                    if (read <= 0) break
                    w.append(buf.copyOf(read))
                    totalSamples += read
                    // Amplitude fuer Wellenform-Anzeige
                    var peak = 0
                    for (i in 0 until read) {
                        val v = abs(buf[i].toInt())
                        if (v > peak) peak = v
                    }
                    _amplitude.value = peak.toFloat() / Short.MAX_VALUE.toFloat()
                    // 60s-Cap
                    if (totalSamples >= MAX_SAMPLES) {
                        Log.i(TAG, "60s-Cap erreicht — stoppe Aufnahme")
                        _capReached.tryEmit(Unit)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording-Loop Fehler", e)
            } finally {
                stopInternal()
            }
        }, "VoiceMemoRecorder")
        thread.isDaemon = true
        thread.start()

        return Result.success(Unit)
    }

    @Synchronized
    private fun stopInternal() {
        val ar = audioRecord ?: return
        _isRecording.value = false
        _amplitude.value = 0f
        try { ar.stop() } catch (_: Exception) {}
        ar.release()
        audioRecord = null
        try { writer?.close() } catch (_: Exception) {}
        writer = null
    }
}
