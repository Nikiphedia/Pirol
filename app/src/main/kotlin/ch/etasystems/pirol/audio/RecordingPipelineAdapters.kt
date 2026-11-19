package ch.etasystems.pirol.audio

import android.content.Context
import android.util.Log
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.GpsStats
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.data.sync.UploadManager
import ch.etasystems.pirol.data.sync.UploadTargetFactory
import ch.etasystems.pirol.location.LocationData
import ch.etasystems.pirol.location.LocationProvider
import ch.etasystems.pirol.ml.AudioBlock
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.EmbeddingDatabase
import ch.etasystems.pirol.ml.EmbeddingExtractor
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.ModelProfile
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.WatchlistManager
import java.io.File

/** T94: [PipelineSession] ueber den bestehenden SessionManager (keine Aenderung am SessionManager). */
class SessionManagerPipelineSession(private val sm: SessionManager) : PipelineSession {
    override val isActive: Boolean get() = sm.isActive
    override val lastStartUsedFallback: Boolean get() = sm.lastStartUsedFallback
    override val recordedSampleCount: Long get() = sm.recordedSampleCount
    override fun currentRecordingOffsetSec(): Float = sm.getCurrentRecordingOffsetSec()
    override suspend fun appendAudio(samples: ShortArray) { sm.appendAudioSamples(samples) }
    override suspend fun appendDetections(detections: List<DetectionResult>) { sm.appendDetections(detections) }
    override suspend fun replaceDetection(updated: DetectionResult) { sm.replaceDetection(updated) }
    override suspend fun appendVerification(event: VerificationEvent) { sm.appendVerification(event) }
    override suspend fun startSession(
        sampleRate: Int, latitude: Double?, longitude: Double?, regionFilter: String?,
        confidenceThreshold: Float, note: String?, modelId: String?
    ): String = sm.startSession(sampleRate, latitude, longitude, regionFilter, confidenceThreshold, note, modelId)
    override suspend fun endSession(gpsStats: GpsStats?) { sm.endSession(gpsStats) }
}

/**
 * T94: Embedding-DB-Datei (aus dem LiveViewModel verschoben).
 * T61 Versionswechsel, T97 Obergrenze + Speichern bei Session-Ende, T103a eine DB pro Modell.
 */
class EmbeddingDbStore(
    private val context: Context,
    private val embeddingDatabase: EmbeddingDatabase,
    private val classifier: AudioClassifier,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "EmbeddingDbStore"
        /** T61: Embeddings aus dem Klassifikations-Durchlauf; aeltere DBs werden einmalig geleert */
        private const val EMBEDDING_DB_VERSION = 2
    }

    @Volatile private var loaded = false
    @Volatile private var fileName: String? = null

    private fun activeFileName(): String =
        (ModelProfile.byId(classifier.modelId) ?: ModelProfile.LEGACY_DEFAULT).embeddingDbFileName

    private fun dbFile(): File {
        val name = fileName ?: activeFileName().also { fileName = it }
        return File(context.filesDir, "embeddings/$name")
    }

    /** Einmalig laden (idempotent). */
    @Synchronized
    fun loadOnce() {
        if (loaded) return
        loaded = true
        val file = dbFile()
        if (appPreferences.embeddingDbVersion < EMBEDDING_DB_VERSION) {
            if (file.exists()) {
                val backup = File(file.parentFile, file.name + ".v1.bak")
                val moved = file.renameTo(backup)
                if (!moved) file.delete()
                Log.i(TAG, "Embedding-DB auf neue Basis umgestellt — alte DB gesichert: $moved")
            }
            embeddingDatabase.clear()
            appPreferences.embeddingDbVersion = EMBEDDING_DB_VERSION
        }
        embeddingDatabase.maxEntries = appPreferences.embeddingDbMaxEntries  // T97
        if (file.exists()) {
            try {
                embeddingDatabase.load(file)
                Log.i(TAG, "Embedding-DB geladen: ${embeddingDatabase.size} Eintraege")
            } catch (e: Exception) {
                Log.e(TAG, "Embedding-DB laden fehlgeschlagen", e)
            }
        }
    }

    /** T103a: Nach Modellwechsel alte DB sichern, DB des aktiven Modells laden. */
    @Synchronized
    fun switchIfModelChanged() {
        loadOnce()
        val current = fileName ?: return
        val wanted = activeFileName()
        if (wanted == current) return
        try {
            val oldFile = File(context.filesDir, "embeddings/$current")
            oldFile.parentFile?.mkdirs()
            embeddingDatabase.save(oldFile)
        } catch (e: Exception) {
            Log.e(TAG, "Embedding-DB ($current) speichern fehlgeschlagen", e)
        }
        embeddingDatabase.clear()
        fileName = wanted
        val newFile = File(context.filesDir, "embeddings/$wanted")
        if (newFile.exists()) {
            try {
                embeddingDatabase.load(newFile)
            } catch (e: Exception) {
                Log.e(TAG, "Embedding-DB ($wanted) laden fehlgeschlagen", e)
            }
        }
        Log.i(TAG, "Embedding-DB gewechselt: $current -> $wanted (${embeddingDatabase.size} Eintraege)")
    }

    /** T97: einmal pro Session speichern (Aufrufer: IO-Kontext). */
    @Synchronized
    fun save() {
        if (!loaded) return  // nie geladen → nichts zu sichern (wuerde sonst eine vorhandene Datei ueberschreiben)
        val file = dbFile()
        file.parentFile?.mkdirs()
        try {
            embeddingDatabase.save(file)
            Log.d(TAG, "Embedding-DB gespeichert: ${embeddingDatabase.size} Eintraege")
        } catch (e: Exception) {
            Log.e(TAG, "Embedding-DB speichern fehlgeschlagen", e)
        }
    }
}

/** T94: Produktive [PipelineHooks] ueber die bestehenden Koin-Singletons. */
class AndroidPipelineHooks(
    private val appPreferences: AppPreferences,
    private val locationProvider: LocationProvider,
    private val speciesNameResolver: SpeciesNameResolver,
    private val watchlistManager: WatchlistManager,
    private val alarmService: AlarmService,
    private val embeddingExtractor: EmbeddingExtractor,
    private val embeddingDatabase: EmbeddingDatabase,
    private val embeddingDbStore: EmbeddingDbStore,
    private val uploadManager: UploadManager,
    private val sessionManager: SessionManager
) : PipelineHooks {
    override fun loadInferenceConfig(): InferenceConfig = appPreferences.loadInferenceConfig()
    override fun overlapPercent(): Int = appPreferences.overlapPercent
    override fun sessionNote(): String = appPreferences.lastSessionNote
    override fun currentLocation(): LocationData? = locationProvider.location.value
    override suspend fun requestFreshFix(timeoutMs: Long): LocationData? = locationProvider.requestFreshFix(timeoutMs)
    override fun maxFixAccuracyM(): Float = appPreferences.gpsMaxAccuracyMeters
    override fun startLocation() = locationProvider.start()
    override fun stopLocation(): GpsStats {
        val stats = locationProvider.buildGpsStats(appPreferences.gpsIntervalSeconds * 1000L)
        locationProvider.stop()
        return stats
    }
    override suspend fun awaitSpeciesNames() = speciesNameResolver.load()
    override fun resolveName(scientificName: String): String = speciesNameResolver.resolve(scientificName)
    override fun clusterEnabled(): Boolean = appPreferences.clusterEnabled
    override fun clusterRadiusM(): Int = appPreferences.clusterRadiusM
    override fun clusterTimeWindowMin(): Int = appPreferences.clusterTimeWindowMin

    override fun checkWatchlist(detection: DetectionResult) {
        val priority = watchlistManager.getPriority(detection.scientificName) ?: return
        Log.i("RecordingPipeline", "WATCHLIST-MATCH: ${detection.commonName} ($priority)")
        alarmService.triggerAlarm(detection, priority)
    }

    override suspend fun addEmbedding(block: AudioBlock, top: DetectionResult): FloatArray {
        val embedding = embeddingExtractor.extract(block.samples, block.sampleRate, precomputed = block.embedding)
        embeddingDatabase.add(recordingId = top.id, species = top.scientificName, embedding = embedding)
        return embedding
    }

    override fun beforeSessionStart() = embeddingDbStore.switchIfModelChanged()

    override suspend fun afterSessionEnd() {
        embeddingDbStore.save()  // T97
        alarmService.clearCooldowns()  // AC6: Cooldown-Map pro Session leeren
        // T17/T125: Auto-Upload nur mit konfiguriertem Ziel
        if (uploadManager.autoUpload && UploadTargetFactory.isConfigured(appPreferences)) {
            sessionManager.listSessions().firstOrNull()?.let { uploadManager.enqueue(it) }
        }
    }
}
