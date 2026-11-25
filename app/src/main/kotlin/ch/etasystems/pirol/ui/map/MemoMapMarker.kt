package ch.etasystems.pirol.ui.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import ch.etasystems.pirol.data.repository.SessionMemo
import ch.etasystems.pirol.ui.analysis.MemoItem

/**
 * T272: Session-Memo mit Position auf der Karte.
 *
 * @param sessionId Session (zum Finden der Memo-Datei)
 * @param file      Memo-Pfad relativ zum Session-Ordner (`memos/session_...wav`)
 * @param startMs   Wanduhr des Beginns (Anzeige)
 */
data class MemoMapMarker(
    val sessionId: String,
    val file: String,
    val latitude: Double,
    val longitude: Double,
    val startMs: Long?,
    val accuracyM: Float?
) {
    /** Schluessel wie im Memo-Block der Analyse (gemeinsame Wiedergabe-Kennung) */
    val key: String get() = MemoItem.sessionKey(file)
}

/** T272: Marker aus den Memos einer Session; Memos ohne Position fehlen (reine Logik, unit-testbar). */
object MemoMapMarkers {
    fun of(sessionId: String, memos: List<SessionMemo>): List<MemoMapMarker> = memos.mapNotNull { m ->
        val lat = m.lat ?: return@mapNotNull null
        val lon = m.lon ?: return@mapNotNull null
        MemoMapMarker(sessionId, m.file, lat, lon, m.startEpochMs, m.accuracyM)
    }

    /** T299: neue Marker anhaengen; Duplikate (gleiche Session und Datei) werden uebersprungen. */
    fun append(existing: List<MemoMapMarker>, added: List<MemoMapMarker>): List<MemoMapMarker> {
        val seen = existing.mapTo(HashSet()) { it.sessionId to it.file }
        return existing + added.filter { seen.add(it.sessionId to it.file) }
    }

    /** T299: Marker einer Session-Memo-Datei entfernen. */
    fun remove(existing: List<MemoMapMarker>, sessionId: String, file: String): List<MemoMapMarker> =
        existing.filterNot { it.sessionId == sessionId && it.file == file }

    /** T299: Filter "Nur Live" zeigt nur Memos der laufenden Session (liveSessionId null = keine). */
    fun forFilter(mode: FilterMode, liveSessionId: String?, markers: List<MemoMapMarker>): List<MemoMapMarker> =
        if (mode == FilterMode.LIVE_ONLY) markers.filter { it.sessionId == liveSessionId } else markers
}

/** Mikrofon-Symbol (dunkler Kreis, weisses Mikrofon); einmal gezeichnet und gecacht (Main-Thread). */
private var cachedIcon: Drawable? = null

fun memoMarkerIcon(resources: Resources, size: Int = 64): Drawable {
    cachedIcon?.let { return it }
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val s = size.toFloat()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF37474F.toInt() }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = s * 0.06f }
    c.drawCircle(s / 2, s / 2, s / 2 - s * 0.04f, fill)
    c.drawCircle(s / 2, s / 2, s / 2 - s * 0.04f, ring)
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    // Kapsel
    c.drawRoundRect(RectF(s * 0.40f, s * 0.20f, s * 0.60f, s * 0.54f), s * 0.10f, s * 0.10f, white)
    // Buegel und Fuss
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = s * 0.05f; strokeCap = Paint.Cap.ROUND
    }
    c.drawArc(RectF(s * 0.32f, s * 0.32f, s * 0.68f, s * 0.64f), 0f, 180f, false, line)
    c.drawLine(s * 0.5f, s * 0.64f, s * 0.5f, s * 0.78f, line)
    c.drawLine(s * 0.40f, s * 0.78f, s * 0.60f, s * 0.78f, line)
    return BitmapDrawable(resources, bmp).also { cachedIcon = it }
}
