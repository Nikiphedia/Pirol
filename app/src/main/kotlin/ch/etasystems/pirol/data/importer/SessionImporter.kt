package ch.etasystems.pirol.data.importer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.repository.ImportStaging
import ch.etasystems.pirol.data.repository.ImportedSource
import ch.etasystems.pirol.data.repository.SessionJson
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.data.repository.SessionMetadata
import ch.etasystems.pirol.data.repository.WavParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** T255: Vorab gelesene Angaben einer gewaehlten Audiodatei (Dialog vor dem Import). */
data class AudioProbe(
    val uri: String,
    val name: String,
    /** Endung bzw. MIME-Untertyp, z.B. "wav", "flac", "m4a" */
    val format: String,
    val sizeBytes: Long,
    val durationSec: Double?,
    val sampleRate: Int,
    val channels: Int,
    /** Erkannte Startzeit (null = muss eingegeben werden) */
    val time: TimeGuess?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** false = nicht lesbar/kein Audio (wird nicht importiert) */
    val readable: Boolean = true
)

/** T255: Auftrag fuer den Fremdaudio-Import (Zeiten nach Pruefung/Eingabe im Dialog). */
data class AudioImportRequest(
    val files: List<Pair<AudioProbe, TimeGuess>>,
    /** true = alle Dateien in eine Session (Segmente), sonst je Datei eine Session */
    val combine: Boolean,
    /** Ort fuer Dateien ohne eigene Koordinaten (Karte), null = keiner */
    val latitude: Double?,
    val longitude: Double?
)

/**
 * T255: Import in die Analyse (Koin-Singleton, eigener Scope wie [ch.etasystems.pirol.rescan.RescanEngine]:
 * laeuft weiter, wenn die Analyse verlassen wird). Quellen: PIROL-Session-ZIP, PIROL-Session-Ordner,
 * fremde Audiodateien. Immer nur ein Import gleichzeitig.
 *
 * Datenintegritaet: jede Session entsteht in einem unsichtbaren Arbeitsordner ([ImportStaging]) und wird erst
 * nach dem letzten geschriebenen Byte umbenannt. Abbruch/Fehler loeschen den Arbeitsordner; fertige Sessions
 * eines Mehrfachimports bleiben erhalten. Speicher: Audio wird blockweise (ca. 1 s) dekodiert, gemischt und
 * geschrieben, ZIPs streamend entpackt.
 */
class SessionImporter(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val appPreferences: AppPreferences
) {
    companion object {
        private const val TAG = "SessionImporter"
        private const val CODEC_TIMEOUT_US = 10_000L
        /** Abbruch, wenn der Decoder nach Eingabe-Ende so oft nichts mehr liefert (ca. 10 s) */
        private const val MAX_IDLE_POLLS = 1_000
    }

    enum class Kind { ZIP, FOLDER, AUDIO }

    /** Laufender Import: Fortschritt 0..1 (null = unbestimmt), Session i von n */
    data class Active(val kind: Kind, val fraction: Float?, val sessionIndex: Int, val sessionCount: Int)

    enum class Failure { NOT_A_SESSION, NO_AUDIO, READ, WRITE }

    sealed class Outcome {
        /** [sessionIds] = fertige Sessions (Reihenfolge wie importiert) */
        abstract val sessionIds: List<String>
        data class Done(val kind: Kind, override val sessionIds: List<String>, val renamed: Boolean) : Outcome()
        data class Cancelled(val kind: Kind, override val sessionIds: List<String>) : Outcome()
        data class Failed(val kind: Kind, override val sessionIds: List<String>, val failure: Failure, val detail: String?) : Outcome()
    }

    private class ImportFailure(val failure: Failure, val detail: String? = null) : Exception(detail)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()
    private val _outcomes = MutableSharedFlow<Outcome>(extraBufferCapacity = 4)
    val outcomes: SharedFlow<Outcome> = _outcomes.asSharedFlow()

    fun cancel() { job?.cancel() }

    // ── Start ────────────────────────────────────────────────────────

    /** PIROL-Session aus ZIP ([isFolder] = false) oder Ordner (Tree-URI) importieren. */
    fun startPackage(uri: Uri, isFolder: Boolean) {
        val kind = if (isFolder) Kind.FOLDER else Kind.ZIP
        launch(kind, listOf(uri)) { created ->
            _active.value = Active(kind, null, 0, 1)
            val (id, renamed) = importPackage(uri, isFolder)
            created += id
            Outcome.Done(kind, created.toList(), renamed)
        }
    }

    /** Fremdaudio importieren (je Datei eine Session oder alle zusammen). */
    fun startAudio(request: AudioImportRequest) {
        launch(Kind.AUDIO, request.files.map { Uri.parse(it.first.uri) }) { created ->
            val groups = ImportPlanner.groups(request.files, request.combine) { it.second.time }
            val totalSec = request.files.sumOf { it.first.durationSec ?: 0.0 }.takeIf { it > 0 }
            var doneSec = 0.0
            groups.forEachIndexed { i, group ->
                _active.value = Active(Kind.AUDIO, totalSec?.let { (doneSec / it).toFloat() }, i, groups.size)
                created += importAudioSession(group, request, i, groups.size) { sec ->
                    totalSec?.let { _active.value = Active(Kind.AUDIO, ((doneSec + sec) / it).toFloat().coerceIn(0f, 1f), i, groups.size) }
                }
                doneSec += group.sumOf { it.first.durationSec ?: 0.0 }
            }
            Outcome.Done(Kind.AUDIO, created.toList(), false)
        }
    }

    /**
     * Lesezugriff fuer die Dauer des Imports sichern: die Freigabe aus der Dateiwahl gilt sonst nur, solange die
     * Activity lebt (der Import laeuft aber im Hintergrund weiter). Wird am Ende wieder abgegeben.
     */
    private fun holdPermissions(uris: List<Uri>): List<Uri> {
        // Bereits dauerhaft freigegebene URIs (z.B. Speicherort, T51) nie anfassen, sonst ginge die Freigabe verloren
        val persisted = runCatching { context.contentResolver.persistedUriPermissions.map { it.uri }.toSet() }.getOrDefault(emptySet())
        return uris.filter { it !in persisted }.filter { u -> runCatching {
            context.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess }
    }

    private fun releasePermissions(uris: List<Uri>) = uris.forEach { u ->
        runCatching { context.contentResolver.releasePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun launch(kind: Kind, uris: List<Uri>, block: suspend (MutableList<String>) -> Outcome) {
        synchronized(this) {
            if (job?.isActive == true || _active.value != null) return
            _active.value = Active(kind, null, 0, 1)
            val held = holdPermissions(uris)
            job = scope.launch {
                val created = mutableListOf<String>()
                val outcome = try {
                    sessionManager.removeImportStaging()  // Reste frueherer Abbrueche (nur ein Import gleichzeitig)
                    block(created)
                } catch (e: CancellationException) {
                    Outcome.Cancelled(kind, created.toList())
                } catch (e: ImportFailure) {
                    Log.w(TAG, "Import fehlgeschlagen: ${e.failure} ${e.detail}")
                    Outcome.Failed(kind, created.toList(), e.failure, e.detail)
                } catch (e: NotASessionException) {
                    Outcome.Failed(kind, created.toList(), Failure.NOT_A_SESSION, e.message)
                } catch (e: Exception) {
                    Log.e(TAG, "Import fehlgeschlagen", e)
                    Outcome.Failed(kind, created.toList(), Failure.WRITE, e.message)
                } finally {
                    releasePermissions(held)
                    _active.value = null
                }
                _outcomes.tryEmit(outcome)
            }
        }
    }

    // ── PIROL-Session (ZIP / Ordner) ─────────────────────────────────

    /** @return (Session-ID, true = ID wegen Kollision neu vergeben) */
    private suspend fun importPackage(uri: Uri, isFolder: Boolean): Pair<String, Boolean> {
        val ctx = currentCoroutineContext()
        val staging = sessionManager.createImportStaging()
        try {
            if (isFolder) copyFolder(uri, staging) { ctx.ensureActive() }
            else {
                val total = querySize(uri)
                var read = 0L
                val input = context.contentResolver.openInputStream(uri) ?: throw ImportFailure(Failure.READ, "ZIP")
                input.use {
                    SessionPackageLogic.extractZip(BufferedInputStream(it, 256 * 1024), staging::openOutput,
                        checkCancel = { ctx.ensureActive() },
                        onBytes = { n ->
                            read += n
                            // unkomprimierte Bytes; WAV ist kaum komprimiert, daher brauchbare Schaetzung
                            if (total > 0) _active.value = Active(Kind.ZIP, (read.toFloat() / total).coerceIn(0f, 1f), 0, 1)
                        })
                }
            }
            ctx.ensureActive()
            val original = SessionPackageLogic.readMetadata(staging.files)
            // Kollision: gleicher Zeitteil, neue Kennung (bis frei)
            val clean = SessionPackageLogic.sanitizeId(original.sessionId)
            var finalId = clean
            while (sessionManager.isSessionIdTaken(finalId)) {
                finalId = SessionPackageLogic.resolveSessionId(clean, { it == clean }, { UUID.randomUUID().toString().take(6) })
            }
            val meta = SessionPackageLogic.finalizeStaged(staging.files, finalId)
            ctx.ensureActive()
            staging.commit(finalId, sessionManager.importDayDir(SessionPackageLogic.dayDirOf(meta)))
            return finalId to (finalId != original.sessionId)
        } catch (e: Throwable) {
            runCatching { staging.discard() }
            throw e
        }
    }

    /** Ordner einer Session (Tree-URI) kopieren: Dateien + eine Unterebene (memos/) */
    private fun copyFolder(treeUri: Uri, staging: ImportStaging, checkCancel: () -> Unit) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw ImportFailure(Failure.READ, "Ordner")
        if (root.findFile(SessionPackageLogic.SESSION_JSON) == null) throw NotASessionException()
        val files = mutableListOf<Pair<String, DocumentFile>>()
        for (c in root.listFiles()) {
            val n = c.name ?: continue
            if (c.isDirectory) c.listFiles().forEach { s -> s.name?.let { sn -> if (s.isFile) files += "$n/$sn" to s } }
            else if (c.isFile) files += n to c
        }
        val total = files.sumOf { it.second.length() }.coerceAtLeast(1L)
        var done = 0L
        val buf = ByteArray(256 * 1024)
        for ((rel, f) in files) {
            val path = SessionPackageLogic.safePath(rel) ?: continue
            checkCancel()
            val input = context.contentResolver.openInputStream(f.uri) ?: throw ImportFailure(Failure.READ, rel)
            input.use { inp ->
                staging.openOutput(path).use { out ->
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        _active.value = Active(Kind.FOLDER, (done.toFloat() / total).coerceIn(0f, 1f), 0, 1)
                        checkCancel()
                    }
                }
            }
        }
    }

    // ── Fremdaudio ───────────────────────────────────────────────────

    /** Eine Session aus [group] (sortiert); @return Session-ID */
    private suspend fun importAudioSession(
        group: List<Pair<AudioProbe, TimeGuess>>,
        request: AudioImportRequest,
        index: Int,
        count: Int,
        onSec: (Double) -> Unit
    ): String {
        val ctx = currentCoroutineContext()
        val start = group.first().second.time.withZoneSameInstant(zone)
        val maxFrames = { rate: Int -> rate.toLong() * 60L * appPreferences.maxRecordingMinutes.coerceIn(10, 80) }
        val staging = sessionManager.createImportStaging()
        var segmenter: ImportSegmenter? = null
        try {
            var targetRate = 0
            var doneSec = 0.0
            val sources = mutableListOf<ImportedSource>()
            for ((probe, guess) in group) {
                ctx.ensureActive()
                var started = false
                var resampler: StreamResampler? = null
                var fileSec = 0.0
                var decodedRate = probe.sampleRate
                decode(probe, checkCancel = { ctx.ensureActive() }) { mono, rate ->
                    decodedRate = rate
                    val seg = segmenter ?: run {
                        targetRate = ImportPlanner.targetRate(rate)
                        ImportSegmenter(targetRate, maxFrames(targetRate)) { name ->
                            val w = staging.openWav(name, targetRate).also { it.open() }
                            object : ImportSegmenter.FrameSink {
                                override fun append(samples: ShortArray) = w.append(samples)
                                override fun close() = w.close()
                            }
                        }.also { segmenter = it }
                    }
                    if (!started) { seg.startSource(guess.time.withZoneSameInstant(zone)); started = true }
                    // Ratenwechsel im Strom (z.B. HE-AAC): Rest des alten Resamplers zuerst schreiben
                    val old = resampler
                    if (old != null && old.fromRate != rate) { seg.write(old.finishShorts()); resampler = null }
                    val out = if (rate == targetRate) mono
                        else (resampler ?: StreamResampler(rate, targetRate).also { resampler = it }).push(mono)
                    seg.write(out)
                    fileSec += mono.size.toDouble() / rate
                    onSec(doneSec + fileSec)
                }
                resampler?.let { r -> segmenter?.write(r.finishShorts()) }
                if (fileSec <= 0.0) throw ImportFailure(Failure.NO_AUDIO, probe.name)
                doneSec += probe.durationSec ?: fileSec
                sources += ImportedSource(
                    fileName = probe.name,
                    format = probe.format,
                    sampleRate = decodedRate,
                    channels = probe.channels,
                    durationSec = fileSec.toFloat(),
                    startedAt = guess.time.withZoneSameInstant(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    timeSource = guess.source.name.lowercase()
                )
            }
            val seg = segmenter ?: throw ImportFailure(Failure.NO_AUDIO, group.first().first.name)
            val segments = seg.finish()
            ctx.ensureActive()

            var sessionId: String
            do { sessionId = ImportPlanner.sessionId(start, UUID.randomUUID().toString().take(6)) }
            while (sessionManager.isSessionIdTaken(sessionId))
            val located = group.firstOrNull { it.first.latitude != null && it.first.longitude != null }?.first
            val meta = ImportPlanner.metadata(
                sessionId = sessionId,
                start = start,
                sampleRate = targetRate,
                totalFrames = seg.totalFrames,
                segments = segments,
                latitude = located?.latitude ?: request.latitude,
                longitude = located?.longitude ?: request.longitude,
                confidenceThreshold = appPreferences.confidenceThreshold,
                sources = sources
            )
            // Reihenfolge wie live: Audio, leere Detektionsliste, session.json zuletzt
            staging.openOutput("detections.jsonl").use { }
            staging.openOutput(SessionPackageLogic.SESSION_JSON).use {
                it.write(SessionJson.pretty.encodeToString(SessionMetadata.serializer(), meta)
                    .toByteArray(Charsets.UTF_8))
            }
            ctx.ensureActive()
            staging.commit(sessionId, sessionManager.importDayDir(ImportPlanner.dayDir(start)))
            Log.i(TAG, "Audio importiert: $sessionId (${group.size} Datei(en), ${segments.size} Segment(e), ${targetRate} Hz), ${index + 1}/$count")
            return sessionId
        } catch (e: Throwable) {
            segmenter?.abort()  // offenen WAV-Writer schliessen, bevor der Arbeitsordner geloescht wird
            runCatching { staging.discard() }
            throw e
        }
    }

    /** Datei blockweise dekodieren: WAV selbst (MediaExtractor kennt WAV erst ab Android 10), sonst MediaCodec. */
    private fun decode(probe: AudioProbe, checkCancel: () -> Unit, onBlock: (ShortArray, Int) -> Unit) {
        val uri = Uri.parse(probe.uri)
        val asWav = context.contentResolver.openInputStream(uri)?.use { raw ->
            val input = BufferedInputStream(raw, 256 * 1024)
            val info = WavInfoReader.read(input, probe.sizeBytes, readAfterData = false)
            if (!WavPcmReader.canRead(info)) return@use false
            val reader = WavPcmReader(info!!, input)
            val block = info.sampleRate.coerceIn(8_000, 192_000)  // ca. 1 s
            while (true) {
                checkCancel()
                val mono = reader.read(block) ?: break
                onBlock(mono, info.sampleRate)
            }
            true
        } ?: throw ImportFailure(Failure.READ, probe.name)
        if (!asWav) decodeWithCodec(uri, probe.name, checkCancel, onBlock)
    }

    private fun decodeWithCodec(uri: Uri, name: String, checkCancel: () -> Unit, onBlock: (ShortArray, Int) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try { extractor.setDataSource(context, uri, null) } catch (e: Exception) { throw ImportFailure(Failure.READ, name) }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw ImportFailure(Failure.NO_AUDIO, name)
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: throw ImportFailure(Failure.NO_AUDIO, name)
            codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { throw ImportFailure(Failure.READ, "$name ($mime)") }
            codec.configure(inFormat, null, null, 0)
            codec.start()
            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                checkCancel()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i) ?: continue
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
                val o = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                when {
                    o >= 0 -> {
                        idle = 0
                        val buf = codec.getOutputBuffer(o)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val mono = toMono(buf.slice().order(ByteOrder.nativeOrder()), channels, isFloat)
                            if (mono.isNotEmpty() && rate > 0) onBlock(mono, rate)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    inputDone && ++idle > MAX_IDLE_POLLS -> {
                        Log.w(TAG, "Decoder liefert kein Ende: $name")
                        break
                    }
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun toMono(buf: ByteBuffer, channels: Int, isFloat: Boolean): ShortArray {
        val ch = channels.coerceAtLeast(1)
        return if (isFloat) {
            val fb = buf.asFloatBuffer()
            val frames = fb.remaining() / ch
            ShortArray(frames) { i ->
                var s = 0f
                for (c in 0 until ch) s += fb.get(i * ch + c)
                ((s / ch).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
        } else {
            val sb = buf.asShortBuffer()
            val all = ShortArray(sb.remaining() - sb.remaining() % ch)
            sb.get(all)
            WavParser.downmixToMono(all, ch)
        }
    }

    // ── Vorpruefung (Dialog) ─────────────────────────────────────────

    /** Angaben je Datei: Format, Dauer, Rate, Kanaele, erkannte Zeit und Ort. Laeuft auf IO. */
    suspend fun probe(uris: List<Uri>): List<AudioProbe> = withContext(Dispatchers.IO) { uris.map { probeOne(it) } }

    private fun probeOne(uri: Uri): AudioProbe {
        val (name, size, modified) = queryDoc(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val z = zone
        val candidates = mutableListOf<TimeGuess>()
        var lat: Double? = null
        var lon: Double? = null
        var duration: Double? = null
        var rate = 0
        var channels = 0
        var format = ext.ifEmpty { "audio" }

        val wav = runCatching {
            context.contentResolver.openInputStream(uri)?.use { WavInfoReader.read(BufferedInputStream(it, 64 * 1024), size) }
        }.getOrNull()
        if (wav != null) {
            format = "wav"
            rate = wav.sampleRate
            channels = wav.channels
            duration = wav.frames?.let { it.toDouble() / wav.sampleRate.coerceAtLeast(1) }
            wav.guano?.let { g ->
                val gi = ImportTimeParser.parseGuano(g, z)
                gi.time?.let { candidates += TimeGuess(it, TimeSource.GUANO) }
                if (gi.latitude != null && gi.longitude != null) { lat = gi.latitude; lon = gi.longitude }
            }
            wav.comment?.let { c -> ImportTimeParser.fromAudioMothComment(c, z)?.let { candidates += TimeGuess(it, TimeSource.AUDIOMOTH) } }
            ImportTimeParser.fromBext(wav.bextDate, wav.bextTime, z)?.let { candidates += TimeGuess(it, TimeSource.BEXT) }
            ImportTimeParser.fromInfoDate(wav.creationDate, z)?.let { candidates += TimeGuess(it, TimeSource.INFO) }
        }
        var readable = wav?.supported == true
        if (!readable) {
            // Komprimierte Formate (und WAV-Varianten ohne eigenen Leser): MediaExtractor + Metadaten
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                val track = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                if (track != null) {
                    val f = ex.getTrackFormat(track)
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_DURATION)) duration = f.getLong(MediaFormat.KEY_DURATION) / 1e6
                    if (ext.isEmpty()) format = f.getString(MediaFormat.KEY_MIME)?.substringAfter('/') ?: format
                    readable = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Vorpruefung: $name nicht lesbar", e)
            } finally { ex.release() }
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                ImportTimeParser.fromMediaDate(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE), z)
                    ?.let { candidates += TimeGuess(it, TimeSource.METADATA) }
                ImportTimeParser.parseIso6709(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                    ?.let { (a, b) -> if (lat == null) { lat = a; lon = b } }
            } catch (_: Exception) {
            } finally { runCatching { mmr.release() } }
        }
        ImportTimeParser.fromFileName(name, z)?.let { candidates += TimeGuess(it, TimeSource.FILENAME) }
        ImportTimeParser.fromModified(modified, duration, z)?.let { candidates += TimeGuess(it, TimeSource.MODIFIED) }
        return AudioProbe(
            uri = uri.toString(), name = name, format = format, sizeBytes = size, durationSec = duration,
            sampleRate = rate, channels = channels, time = ImportTimeParser.best(candidates),
            latitude = lat, longitude = lon, readable = readable
        )
    }

    private data class DocInfo(val name: String, val size: Long, val modified: Long?)

    private fun queryDoc(uri: Uri): DocInfo {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
        var size = -1L
        var modified: Long? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        runCatching {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) modified = c.getLong(0).takeIf { it > 0 }
            }
        }
        return DocInfo(name, size, modified)
    }

    private fun querySize(uri: Uri): Long = queryDoc(uri).size
}
