package ch.etasystems.pirol.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import ch.etasystems.pirol.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Aufnahme-Phase fuer FAB-3-State (T52).
 * IDLE: keine Aufnahme aktiv.
 * PREROLL_FILLING: Aufnahme gestartet, Ring-Buffer fuellt sich noch (Dauer = prerollDurationSec).
 * RUNNING: Preroll voll — Aufnahme laeuft stabil.
 */
enum class RecordingPhase { IDLE, PREROLL_FILLING, RUNNING }

/**
 * Foreground Service fuer Hintergrund-Aufnahme via Oboe.
 *
 * Lifecycle: Service lebt solange die Aufnahme laeuft.
 * Bei stopRecording() wird stopSelf() aufgerufen.
 *
 * Audio-Chunks werden alle ~100ms aus dem Ring-Buffer gelesen
 * und in einen SharedFlow emittiert (fuer Sonogramm + ML in spaeteren Tasks).
 */
class RecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "pirol_recording"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "ch.etasystems.pirol.STOP_RECORDING"
        private const val CHUNK_INTERVAL_MS = 100L
    }

    // --- Binder ---
    inner class RecordingBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = RecordingBinder()

    // --- Audio Engine ---
    private val engine = OboeAudioEngine()

    // --- Coroutines ---
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var chunkJob: Job? = null
    private var prerollPhaseJob: Job? = null  // T52: PREROLL_FILLING → RUNNING Timer

    // --- State ---
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // T52: FAB-3-State — exponierter RecordingPhase-Flow
    private val _recordingPhase = MutableStateFlow(RecordingPhase.IDLE)
    val recordingPhase: StateFlow<RecordingPhase> = _recordingPhase.asStateFlow()

    // Preroll: Engine laeuft fuer Ring-Buffer, auch wenn nicht "aufgenommen" wird (T35)
    private var prerollActive = false
    private var configuredPrerollSec: Int = 0  // Gespeichert von initPreroll() fuer Phase-Timer

    private val _audioChunkFlow = MutableSharedFlow<AudioChunk>(
        replay = 0,
        extraBufferCapacity = 64 // Genug Puffer fuer langsame Consumer
    )
    val audioChunkFlow: SharedFlow<AudioChunk> = _audioChunkFlow.asSharedFlow()

    /** Tatsaechliche Sample-Rate (erst nach startRecording gueltig). */
    val actualSampleRate: Int
        get() = engine.actualSampleRate

    // Zeitpunkt des Aufnahme-Starts (elapsedRealtime) fuer Timer-Anzeige
    private var recordingStartTimeMs: Long = 0L

    /** Aufnahme-Startzeit als elapsedRealtime (fuer Chronometer). */
    val recordingStartElapsedRealtime: Long
        get() = recordingStartTimeMs

    // --- Service Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        prerollPhaseJob?.cancel()
        prerollPhaseJob = null
        _recordingPhase.value = RecordingPhase.IDLE
        stopChunkEmitter()
        if (engine.isRecording) {
            engine.stop()
        }
        _isRecording.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    // --- Oeffentliches API (via Binder) ---

    /**
     * Preroll-Buffer starten: Engine laeuft leise im Hintergrund und fuellt den Ring-Buffer (T35).
     * Muss VOR startRecording() aufgerufen werden, damit Preroll-Samples verfuegbar sind.
     * @param sampleRate Ziel-Samplerate
     * @param prerollDurationSec Preroll-Dauer in Sekunden (0 = deaktiviert)
     */
    fun initPreroll(sampleRate: Int = OboeAudioEngine.SAMPLE_RATE_BIRDS, prerollDurationSec: Int = 0) {
        if (engine.isRecording) return
        configuredPrerollSec = prerollDurationSec.coerceAtLeast(0)  // T52: merken fuer Phase-Timer
        if (prerollDurationSec <= 0) {
            prerollActive = false
            return
        }
        engine.setPrerollDuration(prerollDurationSec)
        val started = engine.start(sampleRate)
        prerollActive = started
    }

    /**
     * Preroll-Samples aus dem Ring-Buffer lesen (T35).
     * @param durationMs Gewuenschte Dauer in Millisekunden
     * @return ShortArray mit den gepufferten Samples
     */
    fun getPrerollSamples(durationMs: Int): ShortArray {
        return engine.getLatestChunk(durationMs)
    }

    /**
     * Aufnahme starten.
     * Falls Preroll aktiv: Engine laeuft bereits, nur Foreground + Chunk-Emitter starten.
     * Falls kein Preroll: Engine normal starten.
     */
    fun startRecording(sampleRate: Int = OboeAudioEngine.SAMPLE_RATE_BIRDS) {
        if (_isRecording.value) return

        if (!engine.isRecording) {
            val started = engine.start(sampleRate)
            if (!started) return
        }

        recordingStartTimeMs = SystemClock.elapsedRealtime()
        _isRecording.value = true

        // T52: RecordingPhase — PREROLL_FILLING fuer configuredPrerollSec, dann RUNNING
        _recordingPhase.value = RecordingPhase.PREROLL_FILLING
        prerollPhaseJob?.cancel()
        val prerollMs = configuredPrerollSec.toLong() * 1000L
        prerollPhaseJob = serviceScope.launch {
            if (prerollMs > 0L) delay(prerollMs)
            _recordingPhase.value = RecordingPhase.RUNNING
        }

        // Foreground starten
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Chunk-Emitter starten
        startChunkEmitter()
    }

    /** Aufnahme stoppen. Engine bleibt fuer Preroll aktiv falls prerollActive. */
    fun stopRecording() {
        if (!_isRecording.value) return

        // T52: Phase-Timer abbrechen + IDLE setzen
        prerollPhaseJob?.cancel()
        prerollPhaseJob = null
        _recordingPhase.value = RecordingPhase.IDLE

        stopChunkEmitter()
        if (!prerollActive) {
            engine.stop()
        }
        _isRecording.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Aufnahme",
            NotificationManager.IMPORTANCE_LOW // Kein Ton, minimale Stoerung
        ).apply {
            description = "Zeigt an, dass PIROL Audio aufnimmt"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Tap oeffnet MainActivity
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stopp-Action
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PIROL")
            .setContentText("Aufnahme läuft")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapPending)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stopp",
                stopPending
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // --- Chunk-Emitter ---

    private fun startChunkEmitter() {
        chunkJob = serviceScope.launch {
            while (isActive && _isRecording.value) {
                val samples = engine.getLatestChunk(CHUNK_INTERVAL_MS.toInt())
                if (samples.isNotEmpty()) {
                    val chunk = AudioChunk(
                        samples = samples.copyOf(), // Kopie, nicht der interne Buffer
                        sampleRate = engine.actualSampleRate,
                        timestampMs = SystemClock.elapsedRealtime()
                    )
                    _audioChunkFlow.emit(chunk)
                }
                delay(CHUNK_INTERVAL_MS)
            }
        }
    }

    private fun stopChunkEmitter() {
        chunkJob?.cancel()
        chunkJob = null
    }
}
