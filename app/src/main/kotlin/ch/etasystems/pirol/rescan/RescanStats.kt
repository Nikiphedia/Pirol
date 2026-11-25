package ch.etasystems.pirol.rescan

import java.util.Locale

/**
 * T289: Zeitanteile eines Rescans fuer das Messlog `T282_RESCAN_STATS` (reine Rechnung, ohne Android).
 * Alle Zeiten in Nanosekunden. Primaer und Zweitmodell laufen parallel, ihre Summen koennen deshalb
 * zusammen die Gesamtdauer uebersteigen; [secondaryWaitNs] ist die Zeit, in der der Hauptlauf auf das
 * Zweitmodell warten musste (der eigentliche Engpass, falls gross).
 */
class RescanStats {
    var windows = 0
    var readNs = 0L
    /** Resampling + DSP (Teil von [primaryNs]) */
    var prepNs = 0L
    /** Primaermodell inkl. Resampling, Nachbearbeitung des Workers */
    var primaryNs = 0L
    /** Rechenzeit des Zweitmodells (Summe, laeuft parallel) */
    var secondaryNs = 0L
    var secondaryWaitNs = 0L
    var secondaryWindows = 0
    /** Cluster, Sink-Ereignisse und Flush (onWindow) */
    var writeNs = 0L

    /** Eine Zeile fuer das Log; [totalNs] = Wanduhrdauer, [audioSec] = Dauer der Aufnahme */
    fun format(totalNs: Long, audioSec: Float, threads: Int, secondaryParallel: Boolean): String {
        val totalMs = totalNs / 1_000_000
        fun ms(ns: Long) = ns / 1_000_000
        fun pct(ns: Long) = if (totalNs > 0) (ns * 100.0 / totalNs) else 0.0
        val rtf = if (audioSec > 0f) totalNs / 1e9 / audioSec else 0.0
        val l = Locale.ROOT
        return String.format(
            l,
            "T282_RESCAN_STATS windows=%d total_ms=%d audio_s=%.1f rtf=%.3f threads=%d sec_parallel=%b " +
                "read_ms=%d (%.1f%%) prep_ms=%d (%.1f%%) primary_ms=%d (%.1f%%) " +
                "secondary_ms=%d (%.1f%%) secondary_windows=%d secondary_wait_ms=%d (%.1f%%) write_ms=%d (%.1f%%)",
            windows, totalMs, audioSec, rtf, threads, secondaryParallel,
            ms(readNs), pct(readNs), ms(prepNs), pct(prepNs), ms(primaryNs), pct(primaryNs),
            ms(secondaryNs), pct(secondaryNs), secondaryWindows, ms(secondaryWaitNs), pct(secondaryWaitNs),
            ms(writeNs), pct(writeNs)
        )
    }
}
