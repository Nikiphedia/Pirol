package ch.etasystems.pirol.data.repository

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * T264: Ein Session-Memo (gesprochene Notiz waehrend der Aufnahme, ohne Bezug zu einer Detektion).
 * Eine Zeile in `memos.jsonl` im Session-Ordner (append-only).
 *
 * @param file        relativer Pfad der Memo-WAV, z.B. `memos/session_20261124-143000.wav`
 * @param startedAt   Beginn (ISO 8601 mit Offset, Lokalzeit)
 * @param endedAt     Ende (ISO 8601 mit Offset, Lokalzeit)
 * @param startSec    Beginn auf der Session-Achse (s); null = unbekannt (keine Aufnahme lief)
 * @param endSec      Ende auf der Session-Achse (s); null = unbekannt
 * @param durationSec Laenge der Memo-Aufnahme (s)
 * @param lat         T272: Position beim Druecken (WGS84); null = kein Fix
 * @param lon         T272: Laengengrad beim Druecken
 * @param accuracyM   T272: Genauigkeit des Fixes (m); null = unbekannt
 */
@Serializable
data class SessionMemo(
    val file: String,
    val startedAt: String,
    val endedAt: String,
    val startSec: Float? = null,
    val endSec: Float? = null,
    val durationSec: Float,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null
) {
    /** T272: true = Position vorhanden (Karte) */
    val hasPosition: Boolean get() = lat != null && lon != null

    /** Wanduhr des Beginns (Epoch ms); null bei unlesbarem Zeitstempel */
    val startEpochMs: Long? get() = SessionMemos.parseEpochMs(startedAt)

    /** Wanduhr des Endes (Epoch ms); null bei unlesbarem Zeitstempel */
    val endEpochMs: Long? get() = SessionMemos.parseEpochMs(endedAt)
}

/** T264: Dateiname, Grenzen und (De-)Serialisierung der Session-Memos. Reine Logik (unit-testbar). */
object SessionMemos {

    /** Metadaten-Datei im Session-Ordner (wird exportiert) */
    const val FILE = "memos.jsonl"

    /** Unterordner der Memo-WAVs (wie Art-Memos) */
    const val DIR = "memos"

    /** Kuerzere Aufnahmen gelten als versehentlich und werden verworfen */
    const val MIN_DURATION_MS = 500L

    /**
     * Obergrenze einer Session-Memo-Aufnahme. 3 min reichen fuer eine ausfuehrliche Feldnotiz; laenger gehaltene
     * Knoepfe sind meist versehentlich (Tasche). Die Memo-WAV bleibt klein (16 kHz Mono: ca. 5.8 MB), und der
     * Bereich, der im Export ungedaempft bleibt, bleibt begrenzt.
     */
    const val MAX_DURATION_SEC = 180

    private val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /**
     * Dateiname `session_<yyyyMMdd-HHmmss>.wav` (Lokalzeit, kein `:`); bei Kollision `-2`, `-3` ...
     * @param exists true = Name im Ordner memos/ schon vergeben
     */
    fun fileName(epochMs: Long, zone: ZoneId, exists: (String) -> Boolean): String {
        val base = "session_" + NAME_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))
        var name = "$base.wav"
        var n = 2
        while (exists(name)) {
            name = "$base-$n.wav"
            n++
        }
        return name
    }

    /** true = Aufnahme zu kurz (versehentlich), verwerfen */
    fun isTooShort(startMs: Long, endMs: Long): Boolean = endMs - startMs < MIN_DURATION_MS

    /**
     * Baut den Eintrag einer fertigen Aufnahme.
     * @param startSec Session-Sekunde des Beginns; null = unbekannt (dann auch kein Ende)
     */
    fun create(
        fileName: String,
        startMs: Long,
        endMs: Long,
        startSec: Float?,
        zone: ZoneId,
        lat: Double? = null,
        lon: Double? = null,
        accuracyM: Float? = null
    ): SessionMemo {
        val dur = (endMs - startMs).coerceAtLeast(0L) / 1000f
        return SessionMemo(
            file = "$DIR/$fileName",
            startedAt = isoLocal(startMs, zone),
            endedAt = isoLocal(endMs, zone),
            startSec = startSec,
            endSec = startSec?.let { it + dur },
            durationSec = dur,
            // Position nur als Paar (halbe Koordinate waere unbrauchbar)
            lat = if (lat != null && lon != null) lat else null,
            lon = if (lat != null && lon != null) lon else null,
            accuracyM = if (lat != null && lon != null) accuracyM else null
        )
    }

    /** Eine JSONL-Zeile (mit Zeilenumbruch) */
    fun encode(memo: SessionMemo): String = SessionJson.compact.encodeToString(SessionMemo.serializer(), memo) + "\n"

    /**
     * T272: Text von memos.jsonl ohne den Eintrag [file]. Loeschen schreibt die Datei atomar neu
     * (statt Loesch-Marker): Export, Raven, MemoExclusion und ZIP lesen so ohne Sonderfall dieselbe Wahrheit.
     * Unlesbare Zeilen bleiben unveraendert stehen.
     */
    fun withoutFile(text: String?, file: String): String {
        if (text.isNullOrBlank()) return ""
        val kept = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.filter { line ->
            val m = runCatching { SessionJson.lenient.decodeFromString(SessionMemo.serializer(), line) }.getOrNull()
            m == null || m.file != file
        }.toList()
        return if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n")
    }

    /** Alle lesbaren Eintraege aus memos.jsonl; kaputte Zeilen werden uebersprungen */
    fun parse(text: String?): List<SessionMemo> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            runCatching { SessionJson.lenient.decodeFromString(SessionMemo.serializer(), line) }.getOrNull()
        }.toList()
    }

    fun isoLocal(epochMs: Long, zone: ZoneId): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun parseEpochMs(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
}

/** T299: Ereignis zu Session-Memos (sessionId = Ordnername der Session). */
sealed class SessionMemoEvent {
    abstract val sessionId: String
    data class Added(override val sessionId: String, val memo: SessionMemo) : SessionMemoEvent()
    data class Removed(override val sessionId: String, val file: String) : SessionMemoEvent()
}
