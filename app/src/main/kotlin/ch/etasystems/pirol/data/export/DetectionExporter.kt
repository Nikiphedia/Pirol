package ch.etasystems.pirol.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import ch.etasystems.pirol.audio.dsp.DynamicRangeMapper
import ch.etasystems.pirol.audio.dsp.MelSpectrogram
import ch.etasystems.pirol.audio.dsp.SpectrogramConfig
import ch.etasystems.pirol.audio.export.DetectionExcerpt
import ch.etasystems.pirol.audio.export.ExcerptEncoders
import ch.etasystems.pirol.audio.export.ExcerptWav
import ch.etasystems.pirol.audio.export.SegmentedRecording
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.SpectrogramSettingsRepository
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ui.components.SpectrogramPalette
import ch.etasystems.pirol.ui.components.effectiveDbRange
import ch.etasystems.pirol.ui.components.visibleBinRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

/** Quelle fuer einen Detektions-Export: Detektion + zugehoerige Aufnahme. */
data class DetectionShareRequest(
    val detection: DetectionResult,
    val recordingFile: File?,
    val sessionNote: String?,
    /** T130: alle Segmente der Session; null = aus [recordingFile] und Nachbar-Segmenten ableiten */
    val recording: SegmentedRecording? = null
)

/** Export-Formate (T116). */
enum class ShareFormat(val label: String, val extension: String, val mime: String) {
    WAV("WAV (unkomprimiert)", "wav", "audio/wav"),
    FLAC("FLAC (verlustfrei)", "flac", "audio/flac"),
    MP3("MP3", "mp3", "audio/mpeg"),
    M4A("M4A (AAC, klein)", "m4a", "audio/mp4"),
    PNG("Sonogramm (PNG)", "png", "image/png")
}

/**
 * T116: Erzeugt Audio-/Bild-Ausschnitte einer Detektion und teilt sie ueber das Android-Share-Sheet.
 * Dateien liegen in `cacheDir/share/` (FileProvider), aeltere werden bei jedem Export geloescht.
 */
class DetectionExporter(
    private val context: Context,
    private val settingsRepository: SpectrogramSettingsRepository,
    private val prefs: AppPreferences,
    private val nameResolver: SpeciesNameResolver
) {

    /** Formate, die auf diesem Geraet angeboten werden (MP3/FLAC nur mit vorhandenem Encoder). */
    fun availableFormats(): List<ShareFormat> = buildList {
        add(ShareFormat.WAV)
        if (ExcerptEncoders.flacAvailable) add(ShareFormat.FLAC)
        if (ExcerptEncoders.mp3Available) add(ShareFormat.MP3)
        if (ExcerptEncoders.aacAvailable) add(ShareFormat.M4A)
        add(ShareFormat.PNG)
    }

    /**
     * Erzeugt die Exportdatei. Laeuft auf Dispatchers.IO/Default; wirft bei Fehler.
     * @param onProgress 0..1 (nur bei Audio-Encodern feingranular)
     */
    suspend fun export(
        request: DetectionShareRequest,
        format: ShareFormat,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val det = request.detection
        // T130: Session-Zeitachse ueber alle WAV-Segmente (Ausschnitt darf eine Segmentgrenze queren)
        val recording = request.recording
            ?: SegmentedRecording.forRecordingFile(
                request.recordingFile ?: throw IllegalStateException("Aufnahme nicht verfuegbar")
            )
        val range = recording.excerptRange(det, prefs.excerptMaxSec.toFloat())
        val pcm = recording.readRange(range.startSec, range.endSec)
            ?: throw IllegalStateException("Ausschnitt nicht lesbar (Aufnahme leer oder ausserhalb)")

        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // Alte Dateien aufraeumen
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, DetectionExcerpt.baseFileName(det.scientificName, det.timestampMs) + "." + format.extension)

        when (format) {
            ShareFormat.WAV -> ExcerptWav.write(target, pcm)
            ShareFormat.FLAC -> ExcerptEncoders.encodeFlac(pcm, target, onProgress)
            ShareFormat.MP3 -> ExcerptEncoders.encodeMp3(pcm, target, onProgress)
            ShareFormat.M4A -> ExcerptEncoders.encodeM4a(pcm, target, onProgress)
            ShareFormat.PNG -> withContext(Dispatchers.Default) {
                renderPng(target, pcm, range, request, onProgress)
            }
        }
        target
    }

    /** Oeffnet das Share-Sheet fuer [file]. */
    fun share(file: File, format: ShareFormat) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Detektion teilen").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    // ── Sonogramm-PNG ───────────────────────────────────────────────

    private fun renderPng(
        target: File,
        pcm: ExcerptWav.Pcm,
        range: DetectionExcerpt.Range,
        request: DetectionShareRequest,
        onProgress: (Float) -> Unit
    ) {
        val settings = settingsRepository.settings.value
        val config = SpectrogramConfig.BIRDS
        val frames = MelSpectrogram(sampleRate = pcm.sampleRate).process(pcm.samples)
        if (frames.isEmpty()) throw IllegalStateException("Ausschnitt zu kurz fuer ein Sonogramm")
        onProgress(0.6f)

        // dB-Bereich wie im Analyse-Tab (Auto-Kontrast ueber den Ausschnitt oder fester Bereich)
        val (lo, hi) = if (settings.autoContrast) {
            DynamicRangeMapper.computeStatic(
                frames,
                lowPercentile = settings.autoLowPct,
                highPercentile = settings.autoHighPct,
                minSpanDb = settings.autoMinSpanDb
            )
        } else settings.minDb to settings.maxDb
        val (minDb, maxDb) = effectiveDbRange(lo, hi, settings.ceilingDb)

        val binRange = visibleBinRange(
            config.numMelBins, config.fMin,
            if (config.fMax <= 0f) 24000f else config.fMax,
            settings.freqMinHz, settings.freqMaxHz
        )
        val h = binRange.last - binRange.first + 1
        val w = frames.size
        val colorLut = SpectrogramPalette.lutFor(settings.palette)
        val lut = IntArray(256) { i ->
            val g = if (settings.gamma == 1f) i else ((i / 255f).pow(settings.gamma) * 255f).toInt().coerceIn(0, 255)
            colorLut[g]
        }
        val invRange = 1f / (maxDb - minDb).coerceAtLeast(1f)
        val pixels = IntArray(w * h)
        for (x in 0 until w) {
            val frame = frames[x]
            for (bin in 0 until h) {
                val src = binRange.first + bin
                if (src >= frame.size) break
                val n = ((frame[src] - minDb) * invRange).coerceIn(0f, 1f)
                pixels[(h - 1 - bin) * w + x] = lut[(n * 255f).toInt().coerceIn(0, 255)]
            }
        }
        val raw = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)

        val outW = 1600
        val specH = 480
        val lines = captionLines(request, range)
        val textSize = 34f
        val lineH = textSize * 1.35f
        val captionH = (lines.size * lineH + 40f).toInt()
        val result = Bitmap.createBitmap(outW, captionH + specH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.rgb(24, 24, 24))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
        }
        var y = 20f + textSize
        lines.forEachIndexed { i, line ->
            paint.typeface = if (i == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.color = if (i == lines.lastIndex) Color.LTGRAY else Color.WHITE
            canvas.drawText(line, 24f, y, paint)
            y += lineH
        }
        canvas.drawBitmap(
            raw,
            android.graphics.Rect(0, 0, w, h),
            android.graphics.Rect(0, captionH, outW, captionH + specH),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        // Markierung: Beginn der Detektion
        val dur = range.durationSec.coerceAtLeast(0.1f)
        val xDet = ((range.detectionStartSec - range.startSec) / dur * outW).coerceIn(0f, outW.toFloat())
        val mark = Paint().apply { color = Color.CYAN; strokeWidth = 3f }
        canvas.drawLine(xDet, captionH.toFloat(), xDet, (captionH + specH).toFloat(), mark)
        // Zeitachse (Sekunden im Ausschnitt)
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; this.textSize = 24f }
        canvas.drawText("0 s", 8f, (captionH + specH - 8).toFloat(), tick)
        val endLabel = "%.0f s".format(Locale.ROOT, dur)
        canvas.drawText(endLabel, outW - tick.measureText(endLabel) - 8f, (captionH + specH - 8).toFloat(), tick)

        target.outputStream().buffered().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
        raw.recycle()
        result.recycle()
        onProgress(1f)
    }

    private fun captionLines(request: DetectionShareRequest, range: DetectionExcerpt.Range): List<String> {
        val det = request.detection
        val sci = det.scientificName.replace('_', ' ')
        val common = nameResolver.resolve(det.scientificName)
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        val lines = mutableListOf<String>()
        lines += common
        lines += sci
        lines += fmt.format(Date(det.timestampMs))
        val lat = det.latitude
        val lon = det.longitude
        if (lat != null && lon != null) lines += "%.5f, %.5f".format(Locale.ROOT, lat, lon)
        lines += "Konfidenz ${(det.confidence * 100).toInt()}%  |  Ausschnitt %.0f s".format(Locale.ROOT, range.durationSec)
        request.sessionNote?.takeIf { it.isNotBlank() }?.let { lines += it.trim().lineSequence().first().take(100) }
        lines += "PIROL"
        return lines
    }
}
