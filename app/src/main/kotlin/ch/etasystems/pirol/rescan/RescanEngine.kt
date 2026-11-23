package ch.etasystems.pirol.rescan

import android.content.Context
import android.util.Log
import ch.etasystems.pirol.audio.RecordingPipeline
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.BirdNetV3Classifier
import ch.etasystems.pirol.ml.CHUNK_DURATION_MS
import ch.etasystems.pirol.ml.GeoFilterController
import ch.etasystems.pirol.ml.GeoLogic
import ch.etasystems.pirol.ml.ModelManager
import ch.etasystems.pirol.ml.PirolFolder
import ch.etasystems.pirol.ml.RegionInfo
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.SpeciesKey
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.util.parseInstantCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** T221: Auswahl im Rescan-Dialog. [modelFileName] = Datei in filesDir/models. */
data class RescanOptions(
    val modelFileName: String,
    val regionFilter: String?,
    val confidenceThreshold: Float,
    val overlapPercent: Int
)

/**
 * T221: Android-Anbindung des Rescans (Koin-Singleton): eigener Klassifizierer mit eigener ORT-Session
 * fuer das gewaehlte Modell (nie die Session der Live-Pipeline), eigener Listenfilter, Session-Dateien.
 * Nur ohne laufende Aufnahme; startet waehrenddessen eine Aufnahme, bricht der Rescan ab.
 */
class RescanEngine(
    private val context: Context,
    private val modelManager: ModelManager,
    private val appPreferences: AppPreferences,
    private val sessionManager: SessionManager,
    private val speciesNameResolver: SpeciesNameResolver,
    private val pirolFolder: PirolFolder,
    private val geoFilter: GeoFilterController,
    private val liveClassifier: AudioClassifier,
    private val pipeline: RecordingPipeline
) {
    companion object {
        private const val TAG = "RescanEngine"
    }

    class NoRecordingException : Exception("Keine Aufnahme")
    class ModelUnavailableException : Exception("Modell nicht verfuegbar")
    class RecordingActiveException : Exception("Aufnahme laeuft")

    /** Aufnahme laeuft → Rescan nicht moeglich */
    val recording: StateFlow<Boolean> get() = pipeline.runningState

    private val busy = Mutex()

    /** Installierte Modelle (aktives zuerst) */
    fun models(): List<ModelManager.InstalledModel> = modelManager.listInstalledModels()

    /** Eigene Listen (Asset-Regionen zeigt der Dialog fest, wie in den Einstellungen) */
    suspend fun customRegions(): List<RegionInfo> = withContext(Dispatchers.IO) {
        val f = RegionalSpeciesFilter(context, pirolFolder, null, appPreferences)
        runCatching { f.refreshCustomLists() }
        f.getAvailableRegions().filter { it.isCustom }
    }

    /** Voreinstellung: aktives Modell und Liste der Session, Schwelle der Session, Overlap aus den Einstellungen */
    fun defaults(regionFilter: String?, threshold: Float?): RescanOptions {
        val model = models().firstOrNull { it.isActive } ?: models().firstOrNull()
        return RescanOptions(
            modelFileName = model?.fileName.orEmpty(),
            regionFilter = regionFilter,
            confidenceThreshold = threshold ?: appPreferences.confidenceThreshold,
            overlapPercent = appPreferences.overlapPercent
        )
    }

    /** Varianten einer Session (aelteste zuerst) */
    suspend fun variants(sessionDir: File): List<RescanInfo> =
        runCatching { sessionManager.withSessionFiles(sessionDir) { RescanStore.list(it) } }.getOrElse { emptyList() }

    suspend fun deleteVariant(sessionDir: File, id: String): Boolean =
        sessionManager.withSessionFiles(sessionDir) { RescanStore.delete(it, id) }

    /**
     * Rescan ausfuehren und als neue Variante speichern (Original bleibt unveraendert).
     * @return Metadaten der neuen Variante
     */
    suspend fun run(sessionDir: File, options: RescanOptions, onProgress: (Float) -> Unit): RescanInfo {
        if (pipeline.isRunning || sessionManager.isActiveSession(sessionDir)) throw RecordingActiveException()
        if (!busy.tryLock()) throw IllegalStateException("Rescan laeuft bereits")
        var classifier: BirdNetV3Classifier? = null
        try {
            val meta = sessionManager.loadMetadata(sessionDir)
            val recording = sessionManager.loadRecording(sessionDir) ?: throw NoRecordingException()
            val originals = sessionManager.loadDetectionsWithVerifications(sessionDir)
            val startedAtMs = meta?.startedAt?.let { parseInstantCompat(it)?.toEpochMilli() }
            val epoch0 = RescanPlan.epochAtZero(originals, startedAtMs) ?: System.currentTimeMillis()

            // Position: Route (track.jsonl), sonst Session-Position
            val track = sessionManager.loadTrack(sessionDir).mapNotNull { p ->
                parseInstantCompat(p.time)?.toEpochMilli()?.let { Triple(it, p.lat, p.lon) }
            }
            val sessionPos = meta?.latitude?.let { lat -> meta.longitude?.let { lon -> lat to lon } }
                ?: track.firstOrNull()?.let { it.second to it.third }

            // Modell: eigene Instanz (eigene ORT-Session), Labels ggf. nachladen
            val modelFile = modelManager.getModelFile(options.modelFileName) ?: throw ModelUnavailableException()
            val profile = ch.etasystems.pirol.ml.ModelProfile.resolve(modelFile.name, modelFile.length())
            modelManager.ensureLabels(profile)
            val cls = BirdNetV3Classifier(context, modelManager, null, options.modelFileName)
            classifier = cls
            if (!cls.isModelAvailable()) throw ModelUnavailableException()
            // T187: Live-Session freigeben (RAM), sie laedt beim naechsten Bedarf neu
            liveClassifier.releaseIfIdle()

            // Liste: eigene Filter-Instanz (Live-Filter bleibt unberuehrt)
            SpeciesKey.load(context)
            speciesNameResolver.load()
            val filter = RegionalSpeciesFilter(context, pirolFolder, null, appPreferences)
            var filterInfo: ch.etasystems.pirol.data.repository.SpeciesFilterInfo? = null
            val region = options.regionFilter
            withContext(Dispatchers.IO) {
                runCatching { filter.refreshCustomLists() }
                if (region != null) filter.loadRegion(region)
            }
            if (region == GeoLogic.REGION_ID) {
                filterInfo = geoFilter.applyForRescan(filter, sessionPos?.first, sessionPos?.second, epoch0)
            }

            val config = appPreferences.loadInferenceConfig().copy(
                confidenceThreshold = options.confidenceThreshold,
                regionFilter = region,
                overlapPercent = options.overlapPercent,
                inferenceIntervalMs = CHUNK_DURATION_MS
            )
            val runner = RescanRunner(
                classifier = cls,
                regionalFilter = filter,
                config = config,
                resolveName = { speciesNameResolver.resolve(it) },
                clusterEnabled = appPreferences.clusterEnabled,
                clusterRadiusM = appPreferences.clusterRadiusM,
                clusterTimeWindowMin = appPreferences.clusterTimeWindowMin
            )
            val t0 = System.currentTimeMillis()
            val result = withContext(Dispatchers.Default) {
                runner.run(
                    recording = recording,
                    epochAtZeroMs = epoch0,
                    positionAt = { t -> RescanPlan.positionAt(t, track, sessionPos) },
                    onProgress = onProgress,
                    isAborted = { pipeline.isRunning }
                )
            }
            val now = System.currentTimeMillis()
            val transfer = RescanVerification.transfer(result.detections, originals, now)
            val existing = sessionManager.withSessionFiles(sessionDir) { RescanFiles.idsIn(it.listNames()) }
            val id = RescanFiles.newId(now, ZoneId.systemDefault(), existing)
            val info = RescanInfo(
                id = id,
                createdAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                modelId = cls.modelId,
                modelName = cls.modelName,
                regionFilter = region,
                confidenceThreshold = options.confidenceThreshold,
                overlapPercent = options.overlapPercent,
                speciesFilter = filterInfo,
                detectionCount = result.detections.size,
                transferredVerifications = transfer.size
            )
            sessionManager.withSessionFiles(sessionDir) {
                RescanStore.write(it, info, result.detections, result.mergeEvents, transfer)
            }
            // Raven-Datei der Variante (wie beim Session-Stopp fuer das Original)
            runCatching { sessionManager.exportRavenSelectionTable(sessionDir, appPreferences, variant = id) }
                .onFailure { Log.w(TAG, "Raven-Export der Variante fehlgeschlagen", it) }
            Log.i(TAG, "T221_RESCAN id=$id model=${cls.modelId} region=$region thr=${options.confidenceThreshold} " +
                "overlap=${options.overlapPercent} hop=${config.hopMsFor(options.overlapPercent, cls.windowMs)} windows=${result.windows} detections=${result.detections.size} " +
                "merges=${result.mergeEvents.size} transferred=${transfer.size} ms=${now - t0}")
            return info
        } finally {
            classifier?.close()  // ORT-Session des Rescans sofort freigeben
            busy.unlock()
        }
    }
}
