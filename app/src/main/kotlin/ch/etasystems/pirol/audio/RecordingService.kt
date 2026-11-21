package ch.etasystems.pirol.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import android.util.Log
import kotlinx.coroutines.launch
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.AppStrings
import org.koin.android.ext.android.inject

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

    // T94: Session-Pipeline (Persistenz + Analyse + Detektionen) haengt am Dienst, nicht am ViewModel
    private val pipeline: RecordingPipeline by inject()

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

    // T118: Preroll wird in startRecording() erfasst; der Lese-Cursor steht danach direkt hinter dem Preroll
    @Volatile private var pendingPreroll: PrerollCapture? = null
    @Volatile private var lastPreroll: PrerollCapture? = null
    @Volatile private var cursorPrepared = false

    private val _audioChunkFlow = MutableSharedFlow<AudioChunk>(
        replay = 0,
        extraBufferCapacity = 64 // Genug Puffer fuer langsame Consumer
    )
    val audioChunkFlow: SharedFlow<AudioChunk> = _audioChunkFlow.asSharedFlow()

    // AC5: Fehlersignal wenn Audio-Engine nicht starten kann (T63)
    private val _audioStartError = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioStartError: SharedFlow<String> = _audioStartError.asSharedFlow()

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
        Log.i("RecordingService", "T122 onDestroy: engineRunning=${engine.isRecording} prerollActive=$prerollActive")
        prerollPhaseJob?.cancel()
        prerollPhaseJob = null
        _recordingPhase.value = RecordingPhase.IDLE
        stopChunkEmitter()
        // T94: laufende Aufnahme sauber abschliessen (Audio-Rest + endSession), no-op wenn nichts laeuft
        pipeline.onRecordingStopped()
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
        Log.i("RecordingService", "T122 initPreroll: started=$started sec=$prerollDurationSec")
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
     * T118: Preroll aus dem Ring-Buffer lesen und den Lese-Cursor direkt dahinter stehen lassen.
     * Der Chunk-Emitter liest danach ab genau diesem Cursor weiter → Preroll und Live-Audio
     * schliessen lueckenlos und ohne Doppel-Samples aneinander an.
     */
    private fun capturePreroll(prerollMs: Int): PrerollCapture? {
        val rate = engine.actualSampleRate
        if (rate <= 0) return null
        val wanted = (rate.toLong() * prerollMs / 1000).toInt()
        // Marge 100 ms: aelteste Samples koennten waehrend des Kopierens ueberschrieben werden
        val back = engine.rewindReadCursor(wanted, rate / 10)
        cursorPrepared = true
        if (back <= 0) {
            Log.w("RecordingService", "T122 Preroll leer: rewind=$back angefordert=$wanted")
            return null
        }
        val out = ShortArray(back + rate) // Reserve fuer waehrend des Lesens nachgeschriebene Samples
        var n = 0
        while (n < out.size) {
            val part = engine.readNewSamples(out.size - n)
            if (part.isEmpty()) break
            part.copyInto(out, destinationOffset = n)
            n += part.size
        }
        val captureEpochMs = System.currentTimeMillis()
        // T182: Wiedergabe waehrend des Preroll-Fensters (inkl. Nachlauf) durch Stille ersetzen
        val result = out.copyOf(n)
        val muted = PlaybackMuteGate.shared.muteHistory(result, rate)
        if (muted > 0) Log.i("RecordingService", "T182 Preroll: $muted Samples wegen Wiedergabe stumm")
        Log.i("RecordingService", "T118 Preroll erfasst: $n Samples (angefordert $wanted, dropped=${engine.getDroppedSamples()})")
        if (n == 0) return null
        return PrerollCapture(result, rate, captureEpochMs)
    }

    /** T118: Erfassten Preroll genau einmal abholen (fuer Persistenz + Analyse). */
    fun takePrerollCapture(): PrerollCapture? {
        val p = pendingPreroll
        pendingPreroll = null
        return p
    }

    /** T118: Preroll der letzten Aufnahme (nur lesen, z.B. fuer die Sonogramm-Anzeige). */
    val lastPrerollCapture: PrerollCapture?
        get() = lastPreroll

    /**
     * Preroll-Engine stoppen. Nur moeglich wenn keine Aufnahme laeuft (F5).
     */
    fun stopPreroll() {
        if (prerollActive && !_isRecording.value) {
            engine.stop()
            prerollActive = false
        }
    }

    /**
     * Aufnahme starten.
     * Falls Preroll aktiv: Engine laeuft bereits, nur Foreground + Chunk-Emitter starten.
     * Falls kein Preroll: Engine normal starten.
     */
    fun startRecording(sampleRate: Int = OboeAudioEngine.SAMPLE_RATE_BIRDS, prerollMs: Int = 0) {
        if (_isRecording.value) return

        // T118: Preroll nur, wenn die Engine bereits lief (Ring-Buffer enthaelt echtes Audio vor dem Start).
        // Wird VOR _isRecording = true erfasst; T94: die RecordingPipeline holt ihn per takePrerollCapture() ab.
        Log.i("RecordingService", "T122 startRecording: engineRunning=${engine.isRecording} prerollMs=$prerollMs prerollActive=$prerollActive")
        cursorPrepared = false
        pendingPreroll = null
        lastPreroll = null
        if (engine.isRecording && prerollMs > 0) {
            capturePreroll(prerollMs)?.let {
                pendingPreroll = it
                lastPreroll = it
            }
        }

        if (!engine.isRecording) {
            val started = engine.start(sampleRate)
            if (!started) {
                // AC5: Fehler signalisieren + Service sauber beenden (verhindert 5s-ANR, T63)
                serviceScope.launch { _audioStartError.emit(AppStrings.get(R.string.svc_audio_engine_konnte_nicht_starten)) }
                stopSelf()
                return
            }
        }

        recordingStartTimeMs = SystemClock.elapsedRealtime()
        Log.d("RecordingService", "SMOKETEST_START ts=$recordingStartTimeMs")
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
            // T143: Typ location nur mit Standort-Permission (API 34+ wirft sonst SecurityException)
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (hasLocationPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // T94: Pipeline meldet sich synchron am Chunk-Flow an, BEVOR der Emitter liefert (kein Chunk-Verlust)
        pipeline.onRecordingStarted(audioChunkFlow, engine.actualSampleRate.takeIf { it > 0 } ?: sampleRate, takePrerollCapture())

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
        // T94: Audio-Rest schreiben + Session beenden (unabhaengig davon, ob ein UI gebunden ist)
        pipeline.onRecordingStopped()
        if (!prerollActive) {
            engine.stop()
        }
        _isRecording.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // --- Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            AppStrings.get(R.string.svc_aufnahme),
            NotificationManager.IMPORTANCE_LOW // Kein Ton, minimale Stoerung
        ).apply {
            description = AppStrings.get(R.string.svc_zeigt_an_dass_pirol_audio)
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
            .setContentText(AppStrings.get(R.string.svc_aufnahme_laeuft))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapPending)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                AppStrings.get(R.string.reference_stopp),
                stopPending
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // --- Chunk-Emitter ---

    private fun startChunkEmitter() {
        chunkJob = serviceScope.launch {
            var firstChunk = true
            // T92: Statistik fuer Feldtest (emittiert vs. Wanduhr-Soll)
            val emitterStartMs = SystemClock.elapsedRealtime()
            var totalEmitted = 0L
            var lastDropped = 0L
            val rate = engine.actualSampleRate
            val maxRead = if (rate > 0) rate else OboeAudioEngine.SAMPLE_RATE_BIRDS // 1 s
            // T92: Lese-Cursor auf "jetzt" -- ab hier wird jedes Sample genau einmal geliefert.
            // T118: Nach Preroll-Erfassung steht der Cursor bereits direkt hinter dem Preroll.
            if (!cursorPrepared) engine.resetReadCursor()
            cursorPrepared = false
            try {
                while (isActive && _isRecording.value) {
                    var more: Boolean
                    do {
                        val samples = engine.readNewSamples(maxRead)
                        if (samples.isNotEmpty()) {
                            if (firstChunk) { Log.d("RecordingService", "SMOKETEST_FIRST_CHUNK latency_ms=${SystemClock.elapsedRealtime() - recordingStartTimeMs}"); firstChunk = false }
                            val chunk = AudioChunk(
                                samples = samples, // JNI liefert bereits ein frisches Array
                                sampleRate = engine.actualSampleRate,
                                timestampMs = SystemClock.elapsedRealtime()
                            )
                            // T168: Wiedergabe ueber Lautsprecher -> Stille statt Mikrofon (Zeitachse bleibt lueckenlos)
                            PlaybackMuteGate.shared.muteIfNeeded(samples)
                            totalEmitted += samples.size
                            _audioChunkFlow.emit(chunk)
                        }
                        // Genau maxRead zurueck: vermutlich Rueckstand, sofort weiterlesen
                        more = samples.size == maxRead
                    } while (more && isActive)
                    val dropped = engine.getDroppedSamples()
                    if (dropped > lastDropped) {
                        Log.w("RecordingService", "T92: Ring-Buffer-Ueberlauf, ${dropped - lastDropped} Samples verloren (gesamt $dropped)")
                        lastDropped = dropped
                    }
                    delay(CHUNK_INTERVAL_MS)
                }
            } finally {
                val wallMs = SystemClock.elapsedRealtime() - emitterStartMs
                val expected = wallMs * engine.actualSampleRate / 1000L
                Log.i("RecordingService", "T92_STATS emitted=$totalEmitted dropped=${engine.getDroppedSamples()} wall_ms=$wallMs expected=$expected")
            }
        }
    }

    private fun stopChunkEmitter() {
        chunkJob?.cancel()
        chunkJob = null
    }
}

/**
 * T118: Beim Aufnahmestart erfasster Preroll.
 * @param samples PCM vor dem Aufnahmestart (endet exakt dort, wo der Live-Emitter beginnt)
 * @param captureEpochMs Wanduhr (Epoch ms) beim Erfassen = Ende des Prerolls
 */
class PrerollCapture(
    val samples: ShortArray,
    val sampleRate: Int,
    val captureEpochMs: Long
)
