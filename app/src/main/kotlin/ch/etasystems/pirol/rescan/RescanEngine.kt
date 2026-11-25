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
import ch.etasystems.pirol.ml.ModelManager
import ch.etasystems.pirol.ml.PirolFolder
import ch.etasystems.pirol.ml.RegionInfo
import ch.etasystems.pirol.ml.InferenceConfig
import ch.etasystems.pirol.ml.ListSeason
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.SecondaryChecker
import ch.etasystems.pirol.ml.SpeciesKey
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.util.parseInstantCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.encodeToString
import ch.etasystems.pirol.data.repository.DetectionUpdateLog
import ch.etasystems.pirol.data.repository.SessionJson
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** T221: Auswahl im Rescan-Dialog. [modelFileName] = Datei in filesDir/models. */
data class RescanOptions(
    val modelFileName: String,
    val confidenceThreshold: Float,
    val overlapPercent: Int,
    /** T239/T246: Gruppenwahl (einziger Filter); Voreinstellung = Wahl der Session, sonst aktuelle Einstellungen */
    val groups: ch.etasystems.pirol.ml.SpeciesGroupSelection,
    /** T248: Zweitmodell (Datei in filesDir/models), null = keins. Voreinstellung: wie in den Live-Einstellungen */
    val secondaryModelFileName: String? = null
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
        /** T260: Flush-Intervall der laufenden Variante (wie live, T236) */
        private const val FLUSH_INTERVAL_MS = 10_000L
    }

    class NoRecordingException : Exception("Keine Aufnahme")
    class ModelUnavailableException : Exception("Modell nicht verfuegbar")
    class RecordingActiveException : Exception("Aufnahme laeuft")

    /** T293: Aufnahme-Abtastrate unter der Mindestrate des Modells (Ultraschall-Modell auf 48-kHz-Session) */
    class SourceRateTooLowException(val minRate: Int, val rate: Int) : Exception("Quelle $rate Hz < Mindestrate $minRate Hz")

    /** Aufnahme laeuft → Rescan nicht moeglich */
    val recording: StateFlow<Boolean> get() = pipeline.runningState

    private val busy = Mutex()

    // T250/M4: Der Rescan laeuft im Scope dieses Singletons, nicht im ViewModel. Verlaesst der Nutzer die
    // Analyse (ViewModel verworfen), laeuft er weiter; ein neues ViewModel haengt sich an [active] an.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Laufender Rescan (Session-Ordner + Fortschritt 0..1), null = keiner.
     * T260: [variantId] = Kennung der schon sichtbaren Variante (null bis sie angelegt ist),
     * [version] zaehlt die Flushes (UI laedt die Liste je Flush neu).
     */
    data class Active(
        val sessionDir: File,
        val progress: Float,
        val variantId: String? = null,
        val version: Int = 0,
        /** T273: Stelle in der Warteschlange (0-basiert) und deren Laenge (mehrere importierte Sessions) */
        val batchIndex: Int = 0,
        val batchSize: Int = 1
    )
    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()

    /** Ende eines Rescans: Erfolg, Abbruch oder Fehler (Ausnahme zur Meldungswahl) */
    sealed class Outcome {
        abstract val sessionDir: File
        /** T273: [promoted] = erster Scan einer importierten Session wurde zum Original (keine Variante mehr) */
        data class Done(override val sessionDir: File, val info: RescanInfo, val promoted: Boolean = false) : Outcome()
        /** T260: [variantId] = behaltenes Teilergebnis (ABORTED), null = Abbruch vor dem Anlegen */
        data class Cancelled(override val sessionDir: File, val variantId: String? = null) : Outcome()
        data class Failed(override val sessionDir: File, val error: Throwable, val variantId: String? = null) : Outcome()
    }
    private val _outcomes = MutableSharedFlow<Outcome>(extraBufferCapacity = 4)
    val outcomes: SharedFlow<Outcome> = _outcomes.asSharedFlow()

    /**
     * Rescan im Engine-Scope starten; ignoriert, wenn bereits einer laeuft.
     * T273 (B8): [then] = weitere Sessions mit denselben Einstellungen nacheinander (Import mehrerer Dateien).
     * Abbruch (Knopf) oder gestartete Aufnahme beendet die ganze Warteschlange; andere Fehler nur die Session.
     */
    fun start(sessionDir: File, options: RescanOptions, then: List<File> = emptyList()) {
        synchronized(this) {
            if (job?.isActive == true || _active.value != null) return
            val queue = (listOf(sessionDir) + then).distinctBy { it.path }
            _active.value = Active(sessionDir, 0f, batchSize = queue.size)
            job = scope.launch {
                try {
                    for ((index, dir) in queue.withIndex()) {
                        _active.value = Active(dir, 0f, batchIndex = index, batchSize = queue.size)
                        var lastPct = -1
                        val outcome = try {
                            val info = run(dir, options) { p ->
                                val pct = (p * 100).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    _active.update { it?.copy(progress = p) }
                                }
                            }
                            Outcome.Done(dir, info, promoted = isPromoted(dir, info))
                        } catch (e: CancellationException) {
                            Outcome.Cancelled(dir, _active.value?.variantId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Rescan fehlgeschlagen", e)
                            Outcome.Failed(dir, e, _active.value?.variantId)
                        }
                        val stop = outcome is Outcome.Cancelled || (outcome is Outcome.Failed &&
                            (outcome.error is RecordingActiveException || outcome.error is RescanAbortedException))
                        if (stop || index == queue.lastIndex) _active.value = null
                        _outcomes.tryEmit(outcome)
                        if (stop) break
                    }
                } finally {
                    _active.value = null
                }
            }
        }
    }

    /** T273: fertige Variante wurde zum Original (Metadaten der Variante sind weg) */
    private suspend fun isPromoted(sessionDir: File, info: RescanInfo): Boolean =
        // T303: Abbruch nach erfolgter Uebernahme darf nicht als "nicht uebernommen" gemeldet werden
        PromotionCheck.isPromoted(info.status, PromotedVariants.contains(sessionDir, info.id)) {
            sessionManager.withSessionFiles(sessionDir) { RescanFiles.meta(info.id) !in it.listNames() }
        }

    /** Laufenden Rescan abbrechen; T260: das Teilergebnis bleibt als Variante (ABORTED) */
    fun cancel() { job?.cancel() }

    /** Installierte Modelle (aktives zuerst) */
    fun models(): List<ModelManager.InstalledModel> = modelManager.listInstalledModels()

    /** Asset-Regionen und eigene Listen (T239: auch fuer die Gruppenwahl im Dialog) */
    suspend fun regions(): List<RegionInfo> = withContext(Dispatchers.IO) {
        val f = RegionalSpeciesFilter(context, pirolFolder, null, appPreferences)
        runCatching { f.refreshCustomLists() }
        f.getAvailableRegions()
    }

    /**
     * Voreinstellung: aktives Modell, Gruppenwahl und Schwelle der Session, Overlap aus den Einstellungen.
     * T246: Session ohne Gruppenwahl -> aktuelle Gruppenwahl der Einstellungen.
     */
    fun defaults(threshold: Float?, groups: Map<String, String>?): RescanOptions {
        val model = models().firstOrNull { it.isActive } ?: models().firstOrNull()
        val secondary = appPreferences.secondaryModelFileName
            ?.takeIf { f -> f != model?.fileName && models().any { it.fileName == f } }
        return RescanOptions(
            modelFileName = model?.fileName.orEmpty(),
            confidenceThreshold = threshold ?: appPreferences.confidenceThreshold,
            overlapPercent = appPreferences.overlapPercent,
            groups = groups?.let { ch.etasystems.pirol.ml.SpeciesGroupSelection.fromMap(it) } ?: appPreferences.speciesGroups,
            secondaryModelFileName = secondary
        )
    }

    /**
     * T260: Variante, die gerade in diesem Prozess geschrieben wird (Session-Pfad, Kennung). Wird gesetzt,
     * BEVOR die Metadaten mit RUNNING entstehen, und erst nach dem Abschluss geloescht; alles andere mit
     * RUNNING ist liegengeblieben (Prozess-Kill) und wird beim Oeffnen abgeschlossen.
     */
    @Volatile private var writing: Pair<String, String>? = null

    /** T260: Kennung der Variante, die gerade fuer diese Session geschrieben wird (null = keine) */
    fun runningVariant(sessionDir: File): String? =
        writing?.takeIf { it.first == sessionDir.absolutePath }?.second

    /**
     * Varianten einer Session (aelteste zuerst). T260: liegengebliebene RUNNING-Varianten (Prozess-Kill)
     * werden dabei als ABORTED abgeschlossen (Updates uebernehmen, Uebernahme, Raven), wie beim Oeffnen
     * einer Session mit liegengebliebener Zusatzdatei (T251).
     */
    suspend fun variants(sessionDir: File): List<RescanInfo> {
        var list = runCatching { sessionManager.withSessionFiles(sessionDir) { RescanStore.list(it) } }.getOrElse { emptyList() }
        // T273: abgebrochene Uebernahme ins Original zu Ende fuehren
        val promoting = RescanPromotion.pending(list)
        if (promoting.isNotEmpty()) {
            for (info in promoting) {
                runCatching {
                    val ok = sessionManager.withSessionFiles(sessionDir) {
                        // T303: unter derselben Sperre vermerken (Schreibzugriffe auf die Variante -> Original)
                        RescanPromotion.promote(it, info).also { done -> if (done) PromotedVariants.add(sessionDir, info.id) }
                    }
                    if (ok) sessionManager.exportRavenSelectionTable(sessionDir, appPreferences)
                }.onFailure { Log.w(TAG, "T273 Uebernahme der Variante ${info.id} nicht abgeschlossen", it) }
            }
            list = runCatching { sessionManager.withSessionFiles(sessionDir) { RescanStore.list(it) } }.getOrElse { emptyList() }
        }
        val stale = RescanStatus.stale(list, runningVariant(sessionDir))
        if (stale.isEmpty()) return list
        for (info in stale) {
            runCatching { finish(sessionDir, info, RescanStatus.ABORTED, scannedSec = null) }
                .onFailure { Log.w(TAG, "T260 Variante ${info.id} nicht abgeschlossen", it) }
        }
        val running = runningVariant(sessionDir)
        return runCatching { sessionManager.withSessionFiles(sessionDir) { RescanStore.list(it) } }.getOrElse { list }
            .map { it.copy(status = RescanStatus.effective(it, running)) }
    }

    suspend fun deleteVariant(sessionDir: File, id: String): Boolean {
        if (runningVariant(sessionDir) == id) return false  // T260: laufende Variante nicht loeschen
        return sessionManager.withSessionFiles(sessionDir) { RescanStore.delete(it, id) }
    }

    /**
     * T260: Variante abschliessen (Ende, Abbruch oder nach Kill). Idempotent; Reihenfolge:
     * 1. Zusatzdatei in die Detektionsdatei uebernehmen (atomar, danach Zusatzdatei weg)
     * 2. Verifikationen des Originals uebernehmen, nur fuer vom Nutzer nicht angefasste Detektionen
     * 3. Metadaten mit Endstatus (erst damit gilt die Variante als abgeschlossen)
     * 4. Raven-Datei der Variante
     * Ein Kill vor 3. laesst RUNNING stehen -> beim naechsten Oeffnen dasselbe noch einmal (1. und 2. ohne Doppel).
     */
    private suspend fun finish(sessionDir: File, info: RescanInfo, status: String, scannedSec: Float?): RescanInfo {
        val id = info.id
        sessionManager.withSessionFiles(sessionDir) {
            DetectionUpdateLog.consolidate(it, detectionsName = RescanFiles.detections(id))
        }
        val originals = sessionManager.loadDetectionsWithVerifications(sessionDir)
        val current = sessionManager.loadDetectionsWithVerifications(sessionDir, id)
        val transfer = RescanVerification.transferUntouched(current, originals, System.currentTimeMillis())
        if (transfer.isNotEmpty()) {
            sessionManager.appendVerificationLine(
                sessionDir, variant = id,
                line = transfer.joinToString("") { SessionJson.compact.encodeToString(it) + "\n" }
            )
        }
        val transferred = sessionManager.withSessionFiles(sessionDir) { f ->
            RescanVerification.countTransferred(f.readText(RescanFiles.verifications(id)))
        }
        val done = RescanStatus.finished(info, status, scannedSec, current.size, transferred)
        // T273: erster vollstaendiger Scan einer importierten Session ohne Original-Detektionen -> Original
        val promoted = sessionManager.withSessionFiles(sessionDir) { f ->
            RescanStore.writeMeta(f, done)
            (status == RescanStatus.DONE && promoteIfFirst(f, done))
                .also { if (it) PromotedVariants.add(sessionDir, id) }  // T303: unter derselben Sperre
        }
        if (promoted) Log.i(TAG, "T273: Scan $id als Original uebernommen (importierte Session)")
        runCatching { sessionManager.exportRavenSelectionTable(sessionDir, appPreferences, variant = if (promoted) null else id) }
            .onFailure { Log.w(TAG, "Raven-Export der Variante fehlgeschlagen", it) }
        return done
    }

    /** T273: Variante ins Original uebernehmen, wenn sie der erste Scan einer importierten Session ist (unter fileMutex). */
    private fun promoteIfFirst(files: ch.etasystems.pirol.data.repository.SessionFiles, info: RescanInfo): Boolean {
        val meta = files.readText("session.json")?.let {
            runCatching { SessionJson.lenient.decodeFromString<ch.etasystems.pirol.data.repository.SessionMetadata>(it) }.getOrNull()
        }
        val first = RescanPromotion.shouldPromote(
            meta, info.id,
            files.readText(RescanFiles.DETECTIONS), files.readText(RescanFiles.VERIFICATIONS),
            RescanStore.list(files)
        )
        return first && RescanPromotion.promote(files, info)
    }

    /**
     * Rescan ausfuehren und als neue Variante speichern (Original bleibt unveraendert; T273: Ausnahme erster
     * Scan einer importierten Session, siehe [RescanPromotion]).
     * @return Metadaten der neuen Variante
     */
    suspend fun run(sessionDir: File, options: RescanOptions, onProgress: (Float) -> Unit): RescanInfo {
        if (pipeline.isRunning || sessionManager.isActiveSession(sessionDir)) throw RecordingActiveException()
        if (!busy.tryLock()) throw IllegalStateException("Rescan laeuft bereits")
        var classifier: BirdNetV3Classifier? = null
        var secondaryClassifier: BirdNetV3Classifier? = null
        val secondaryFile = options.secondaryModelFileName?.takeIf { it != options.modelFileName }
        modelManager.markInUse(options.modelFileName)  // T239: waehrend des Rescans nicht loeschbar
        secondaryFile?.let { modelManager.markInUse(it) }
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
            val cls = BirdNetV3Classifier(context, modelManager, null, options.modelFileName, BirdNetV3Classifier.RESCAN_THREADS)  // T289
            classifier = cls
            if (!cls.isModelAvailable()) throw ModelUnavailableException()
            // T293: nicht pruefbar statt Unsinn (kein Hochsampeln); vor dem Anlegen der Variante
            if (!profile.supportsSourceRate(recording.sampleRate)) {
                throw SourceRateTooLowException(profile.minSourceRate ?: 0, recording.sampleRate)
            }
            // T187: Live-Session freigeben (RAM), sie laedt beim naechsten Bedarf neu
            liveClassifier.releaseIfIdle()

            // Liste: eigene Filter-Instanz (Live-Filter bleibt unberuehrt)
            SpeciesKey.load(context)
            speciesNameResolver.load()
            val filter = RegionalSpeciesFilter(context, pirolFolder, null, appPreferences)
            withContext(Dispatchers.IO) {
                runCatching { filter.refreshCustomLists() }
                // T239: Gruppenwahl der Session (im Dialog aenderbar), nicht die aktuellen Einstellungen
                filter.loadGroups(options.groups)
            }
            val filterInfo = if (filter.isAutoGeo()) {  // T232: Artgruppe auf Auto
                geoFilter.applyForRescan(filter, sessionPos?.first, sessionPos?.second, epoch0, options.groups)
            } else {
                ch.etasystems.pirol.data.repository.SpeciesFilterInfo(source = "list", groups = options.groups.toMap())
            }
            val region = RegionalSpeciesFilter.GROUPS_ID

            val config = appPreferences.loadInferenceConfig().copy(
                confidenceThreshold = options.confidenceThreshold,
                regionFilter = region,
                overlapPercent = options.overlapPercent,
                inferenceIntervalMs = CHUNK_DURATION_MS
            )
            // T248: Zweitmodell wie live (nur Anzeige, Felder secondary*); nicht ladbar -> Rescan ohne
            var secondary: SecondaryChecker? = null
            if (secondaryFile != null) {
                secondary = runCatching { openSecondary(secondaryFile, options.modelFileName, filter, config, epoch0) }
                    .onFailure { Log.w(TAG, "T248 Zweitmodell nicht geoeffnet", it) }.getOrNull()
                    ?.also { secondaryClassifier = it.first }?.second
            }
            val runner = RescanRunner(
                classifier = cls,
                regionalFilter = filter,
                config = config,
                resolveName = { speciesNameResolver.commonName(it) },
                clusterEnabled = appPreferences.clusterEnabled,
                clusterRadiusM = appPreferences.clusterRadiusM,
                clusterTimeWindowMin = appPreferences.clusterTimeWindowMin,
                secondary = secondary,
                secondaryOverlapPercent = appPreferences.secondaryOverlapPercent,
                threads = BirdNetV3Classifier.RESCAN_THREADS
            )
            val t0 = System.currentTimeMillis()
            // T260: Variante sofort anlegen (RUNNING), damit die Analyse sie waehrend des Scans zeigt
            val begun = sessionManager.withSessionFiles(sessionDir) { files ->
                val newId = RescanFiles.newId(t0, ZoneId.systemDefault(), RescanFiles.idsIn(files.listNames()))
                val info = RescanInfo(
                    id = newId,
                    createdAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    modelId = cls.modelId,
                    modelName = cls.modelName,
                    regionFilter = region,
                    confidenceThreshold = options.confidenceThreshold,
                    overlapPercent = options.overlapPercent,
                    speciesFilter = filterInfo,
                    status = RescanStatus.RUNNING,
                    scannedSec = 0f,
                    totalSec = recording.durationSec
                )
                writing = sessionDir.absolutePath to newId  // vor dem Schreiben: sonst gilt RUNNING als liegengeblieben
                RescanStore.begin(files, info)
                info
            }
            val id = begun.id
            _active.update { it?.copy(variantId = id) }
            val writer = RescanLiveWriter(id, cls.modelId)  // T261: + Modell fuer die Mensch-Merkliste
            var scanned = 0f
            var lastFlush = System.currentTimeMillis()

            // Flush wie live: gebuendelt, unter dem fileMutex (gegen Nutzer-Aenderungen derselben Variante)
            suspend fun flush() {
                val m = begun.copy(scannedSec = scanned, detectionCount = writer.detectionCount)
                sessionManager.withSessionFiles(sessionDir) { files ->
                    writer.flushLocked(files)
                    RescanStore.writeMeta(files, m)
                }
                _active.update { it?.copy(version = it.version + 1) }
            }

            val result = try {
                withContext(Dispatchers.Default) {
                    runner.run(
                        recording = recording,
                        epochAtZeroMs = epoch0,
                        wallClockAt = RescanPlan.wallClockAt(meta?.recordingSegments, recording.sampleRate, epoch0),  // T255
                        positionAt = { t -> RescanPlan.positionAt(t, track, sessionPos) },
                        onProgress = onProgress,
                        isAborted = { pipeline.isRunning },
                        sink = writer,
                        onWindow = { sec ->
                            scanned = sec
                            val now = System.currentTimeMillis()
                            if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                                lastFlush = now
                                // Fehler: Gesammeltes bleibt stehen, neuer Versuch beim naechsten Intervall
                                try { flush() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                                    Log.w(TAG, "T260 Flush der Variante $id fehlgeschlagen", e)
                                }
                            }
                        }
                    )
                }
            } catch (e: Throwable) {
                // T260: Abbruch (Knopf, Aufnahme, Fehler) behaelt das Teilergebnis
                withContext(NonCancellable) {
                    runCatching { flush() }.onFailure { Log.e(TAG, "T260 letzter Flush der Variante $id fehlgeschlagen", it) }
                    runCatching { finish(sessionDir, begun, RescanStatus.ABORTED, scanned) }
                        .onFailure { Log.e(TAG, "T260 Variante $id nicht abgeschlossen (wird beim Oeffnen nachgeholt)", it) }
                }
                Log.i(TAG, "T260_RESCAN_ABORTED id=$id scannedSec=$scanned reason=${e.javaClass.simpleName}")
                throw e
            }
            // T240: ab hier nicht mehr abbrechbar, sonst meldet der Abbruch "abgebrochen" trotz fertiger Variante
            val info = withContext(NonCancellable) {
                flush()
                finish(sessionDir, begun, RescanStatus.DONE, recording.durationSec)
            }
            val now = System.currentTimeMillis()
            Log.i(TAG, "T221_RESCAN id=$id model=${cls.modelId} region=$region thr=${options.confidenceThreshold} " +
                "overlap=${options.overlapPercent} hop=${config.hopMsFor(options.overlapPercent, cls.windowMs)} windows=${result.windows} detections=${info.detectionCount} " +
                "merges=${result.mergeEvents.size} transferred=${info.transferredVerifications} ms=${now - t0}")
            return info
        } finally {
            classifier?.close()  // ORT-Session des Rescans sofort freigeben
            secondaryClassifier?.close()
            secondaryFile?.let { modelManager.unmarkInUse(it) }
            modelManager.unmarkInUse(options.modelFileName)
            writing = null
            busy.unlock()
        }
    }

    /**
     * T248: Zweitmodell fuer den Rescan oeffnen (eigene ORT-Session). Schwelle wie live.
     * @return Klassifizierer (zum Schliessen) und Checker; null/Fehler -> Rescan laeuft ohne Zweitmodell
     */
    private suspend fun openSecondary(
        fileName: String,
        primaryFileName: String,
        filter: RegionalSpeciesFilter,
        config: InferenceConfig,
        epoch0: Long
    ): Pair<BirdNetV3Classifier, SecondaryChecker>? {
        val file = modelManager.getModelFile(fileName) ?: return null
        // T293: Zweitmodell mit hoeherer Mindestrate als das Hauptmodell kann nie rechnen -> ohne Zweitmodell
        val secProfile = ch.etasystems.pirol.ml.ModelProfile.resolve(file.name, file.length())
        val primFile = modelManager.getModelFile(primaryFileName)
        val primProfile = primFile?.let { ch.etasystems.pirol.ml.ModelProfile.resolve(it.name, it.length()) }
        if (!ch.etasystems.pirol.ml.ModelRoles.secondaryAllowed(primProfile, secProfile)) return null
        modelManager.ensureLabels(ch.etasystems.pirol.ml.ModelProfile.resolve(file.name, file.length()))
        val cls = BirdNetV3Classifier(context, modelManager, null, fileName, BirdNetV3Classifier.RESCAN_THREADS)  // T289
        if (!cls.isModelAvailable()) { cls.close(); return null }
        // Regionsfilter fuer die angezeigte Top-Art wie live (Saison nach Aufnahmedatum der Session)
        val monthDay = if (config.respectListSeason) ListSeason.monthDay(epoch0) else null
        val checker = SecondaryChecker(
            classifier = cls,
            threshold = appPreferences.secondaryConfidenceThreshold,
            isPlausible = { filter.isPlausible(it, config.respectListSeason, monthDay) }
        ) { _, _ -> }
        return cls to checker
    }
}
