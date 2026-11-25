package ch.etasystems.pirol.ui.analysis

import ch.etasystems.pirol.data.repository.RecordingSegment
import ch.etasystems.pirol.util.parseInstantCompat

/**
 * T203: Zeitfenster eines manuell hinzugefuegten Eintrags in der Analyse.
 * Reine Funktion (unit-testbar): Abspielposition p +/- [HALF_WINDOW_SEC].
 */
data class ManualEntryTime(
    val chunkStartSec: Float,
    val chunkEndSec: Float,
    val timestampMs: Long,
    val hasPosition: Boolean
) {
    companion object {
        const val HALF_WINDOW_SEC = 10f

        /**
         * @param positionSec Abspielposition, session-relativ (null = unbekannt)
         * @param durationSec Dauer der Aufnahme (<= 0 = keine Aufnahme)
         * @param sessionStartMs Session-Start in epoch ms (null = unbekannt)
         * @param nowMs Rueckfall-Zeitstempel ohne Position
         * @param segments T274: Segmente; tragen sie eine Wanduhr ([RecordingSegment.startedAt], importierte
         *   Sessions mit Luecken zwischen den Dateien), gilt Segment-Wanduhr + Offset im Segment und das
         *   Fenster wird auf das Segment begrenzt (nie ueber eine Luecke hinweg)
         */
        fun compute(
            positionSec: Float?, durationSec: Float, sessionStartMs: Long?, nowMs: Long,
            segments: List<RecordingSegment>? = null
        ): ManualEntryTime {
            if (positionSec == null || durationSec <= 0f || sessionStartMs == null) {
                return ManualEntryTime(0f, 0f, nowMs, false)
            }
            val p = positionSec.coerceIn(0f, durationSec)
            val seg = anchoredSegment(segments, p)
            if (seg != null) {
                val (s, wallMs) = seg
                val segEnd = minOf(s.startSec + s.durationSec, durationSec)
                return ManualEntryTime(
                    chunkStartSec = (p - HALF_WINDOW_SEC).coerceAtLeast(s.startSec),
                    chunkEndSec = (p + HALF_WINDOW_SEC).coerceAtMost(segEnd),
                    timestampMs = wallMs + ((p - s.startSec) * 1000f).toLong(),
                    hasPosition = true
                )
            }
            return ManualEntryTime(
                chunkStartSec = (p - HALF_WINDOW_SEC).coerceAtLeast(0f),
                chunkEndSec = (p + HALF_WINDOW_SEC).coerceAtMost(durationSec),
                timestampMs = sessionStartMs + (p * 1000f).toLong(),
                hasPosition = true
            )
        }
        /**
         * Segment mit Wanduhr, das die Position [p] enthaelt (Segmente sortiert nach Beginn; die letzte
         * Grenze gehoert noch zum Segment). null, wenn nicht alle Segmente eine Wanduhr tragen.
         */
        internal fun anchoredSegment(segments: List<RecordingSegment>?, p: Float): Pair<RecordingSegment, Long>? {
            if (segments.isNullOrEmpty()) return null
            val walls = segments.map { s ->
                s to (s.startedAt?.let { parseInstantCompat(it) }?.toEpochMilli() ?: return null)
            }.sortedBy { it.first.startSec }
            return walls.lastOrNull { it.first.startSec <= p } ?: walls.first()
        }
    }
}
