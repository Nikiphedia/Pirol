package ch.etasystems.pirol.ml

/**
 * T104: Reine Logik fuer fortsetzbare Downloads (ohne Android/Netzwerk, damit unit-testbar).
 */
object DownloadResume {

    /** Was nach der Server-Antwort mit der vorhandenen .tmp-Datei geschieht */
    enum class Action {
        /** 206: an bestehende Datei anhaengen */
        APPEND,
        /** 200: Server ignoriert Range, bei Null neu beginnen */
        FROM_START,
        /** 416: Range ungueltig (Teildatei passt nicht) — verwerfen und neu versuchen */
        DISCARD_AND_RETRY,
        /** anderer Status: Fehler */
        REJECT
    }

    /** Anzahl Versuche bei voruebergehenden Netzwerkfehlern */
    const val MAX_ATTEMPTS = 3

    /** Wartezeit vor Versuch [attempt] (1-basiert, Versuch 2 = 2 s, Versuch 3 = 4 s) */
    fun backoffMillis(attempt: Int): Long = 1000L shl attempt.coerceIn(1, 6)

    /** Startposition: vorhandene Teilgroesse, ausser sie ist unplausibel (leer oder groesser als erwartet) */
    fun startOffset(existingBytes: Long, expectedBytes: Long?): Long = when {
        existingBytes <= 0L -> 0L
        expectedBytes != null && existingBytes > expectedBytes -> 0L
        else -> existingBytes
    }

    /** Teildatei hat bereits die erwartete Groesse (nur Pruefsumme fehlt noch) */
    fun isComplete(existingBytes: Long, expectedBytes: Long?): Boolean =
        expectedBytes != null && existingBytes > 0L && existingBytes == expectedBytes

    /** Wert des Range-Headers, null wenn von vorn gestartet wird */
    fun rangeHeader(offset: Long): String? = if (offset > 0L) "bytes=$offset-" else null

    /** Entscheidung anhand HTTP-Status; [offset] = angefragte Startposition */
    fun decide(statusCode: Int, offset: Long): Action = when {
        statusCode == 206 && offset > 0L -> Action.APPEND
        statusCode == 200 -> Action.FROM_START
        statusCode == 416 && offset > 0L -> Action.DISCARD_AND_RETRY
        else -> Action.REJECT
    }

    /** Gesamtgroesse fuer die Fortschrittsanzeige: erwartete Groesse, sonst Offset + Content-Length */
    fun totalBytes(expectedBytes: Long?, offset: Long, contentLength: Long?): Long =
        expectedBytes ?: ((contentLength ?: 1L) + offset)

    /** Noch benoetigter freier Speicher: Rest des Modells + Label-Datei + Reserve (Teildatei zaehlt nicht doppelt) */
    fun neededFreeBytes(modelBytes: Long?, labelBytes: Long?, partialBytes: Long, marginBytes: Long): Long {
        val model = modelBytes ?: 0L
        val remaining = if (partialBytes in 1..model) model - partialBytes else model
        return remaining + (labelBytes ?: 0L) + marginBytes
    }
}
