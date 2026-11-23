package ch.etasystems.pirol.ml

import kotlin.math.abs

/**
 * T224: reine Regeln des Zweitmodells (nur Anzeige, ohne Android-Abhaengigkeit, testbar).
 *
 * Das Zweitmodell prueft nur Fenster mit mindestens einer Detektion (Variante B, Bericht T150) und
 * schreibt sein Ergebnis in die Felder `secondary*` der Detektion. Alarm, Filter, Score und Cluster
 * bleiben unberuehrt.
 */
object SecondaryCheck {

    /** Art bekannt, Score vorhanden, keine andere Art deutlich vorne */
    const val STATUS_CHECKED = "CHECKED"
    /** Art fehlt in den Labels des Zweitmodells → nie als Widerspruch zeigen */
    const val STATUS_NOT_CHECKABLE = "NOT_CHECKABLE"
    /** Zweitmodell hat eine andere Top-Art (>= Schwelle des Zweitmodells, hoeher als die Detektionsart) */
    const val STATUS_DISAGREES = "DISAGREES"

    /** Toleranz fuer den Vergleich von Fensterstarts (s) */
    private const val WINDOW_EPS_SEC = 0.01f

    data class Outcome(
        val status: String,
        val confidence: Float?,
        val topSpecies: String? = null,
        val topConfidence: Float? = null
    )

    /**
     * Status einer Detektionsart aus den Ergebnissen des Zweitmodells.
     *
     * @param species wissenschaftlicher Name der Detektion (Hauptmodell)
     * @param results alle Scores des Zweitmodells (absteigend sortiert)
     * @param labelKeys Artschluessel ([SpeciesKey.of]) aller Labels des Zweitmodells
     * @param threshold Schwelle des Zweitmodells fuer eine abweichende Top-Art
     * @param isPlausible Regionsfilter fuer die angezeigte Top-Art (die Detektionsart selbst wird nicht gefiltert)
     * @return null, wenn das Zweitmodell nichts geliefert hat (Fehler, NaN) → Detektion bleibt ungeprueft
     */
    fun evaluate(
        species: String,
        results: List<AudioClassifier.ClassifierResult>,
        labelKeys: Set<String>,
        threshold: Float,
        isPlausible: (String) -> Boolean = { true }
    ): Outcome? {
        if (results.isEmpty()) return null
        val key = SpeciesKey.of(species)
        if (key !in labelKeys) return Outcome(STATUS_NOT_CHECKABLE, null)
        val score = results.firstOrNull { SpeciesKey.of(it.scientificName) == key }?.confidence ?: 0f
        val top = results.firstOrNull { isPlausible(it.scientificName) }
        return if (top != null && SpeciesKey.of(top.scientificName) != key &&
            top.confidence >= threshold && top.confidence > score
        ) {
            Outcome(STATUS_DISAGREES, score, top.scientificName, top.confidence)
        } else {
            Outcome(STATUS_CHECKED, score)
        }
    }

    /** T250/N3: Index-Map Artschluessel -> Label-Index (einmal je Modell aufbauen) */
    fun keyIndex(names: List<String>): Map<String, Int> {
        val map = HashMap<String, Int>(names.size * 2)
        for (i in names.indices) map.putIfAbsent(SpeciesKey.of(names[i]), i)
        return map
    }

    /**
     * T250/N3: wie [evaluate], aber direkt auf dem Score-Array (kein Objekt je Label, keine Sortierung).
     * Die Regionsfilter-Pruefung laeuft nur fuer Kandidaten, die die bisher beste Art uebertreffen.
     */
    fun evaluateScores(
        species: String,
        scores: FloatArray,
        names: List<String>,
        keyIndex: Map<String, Int>,
        threshold: Float,
        isPlausible: (String) -> Boolean = { true }
    ): Outcome? {
        if (scores.isEmpty() || scores.all { it.isNaN() }) return null
        val key = SpeciesKey.of(species)
        val idx = keyIndex[key] ?: return Outcome(STATUS_NOT_CHECKABLE, null)
        val score = scores[idx].let { if (it.isNaN()) 0f else it }
        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in scores.indices) {
            val v = scores[i]
            if (v.isNaN() || v < 0f || v <= bestScore) continue
            if (isPlausible(names[i])) { best = i; bestScore = v }
        }
        return if (best >= 0 && best != idx && SpeciesKey.of(names[best]) != key &&
            bestScore >= threshold && bestScore > score
        ) {
            Outcome(STATUS_DISAGREES, score, names[best], bestScore)
        } else {
            Outcome(STATUS_CHECKED, score)
        }
    }

    /**
     * Fensterzuordnung im Cluster (T70/T186): das Ergebnis eines Fensters gilt fuer die Detektion, wenn
     * es ihr bestes Fenster ist oder sie noch kein Ergebnis hat. So traegt ein Cluster das Ergebnis
     * seines besten Fensters; spaetere, schwaechere Fenster ueberschreiben es nicht.
     */
    fun shouldApply(detection: DetectionResult, windowStartSec: Float): Boolean =
        detection.secondaryStatus == null || abs(detection.bestWindowStartSec - windowStartSec) < WINDOW_EPS_SEC

    /** T250/N1: Ergebnis gilt nur, wenn die Detektion noch die Art des Ziels traegt und das Fenster passt */
    fun appliesTo(detection: DetectionResult, targetSpecies: String, windowStartSec: Float): Boolean =
        SpeciesKey.of(detection.scientificName) == SpeciesKey.of(targetSpecies) && shouldApply(detection, windowStartSec)

    /** Felder des Zweitmodells setzen (alle anderen Felder bleiben) */
    fun apply(detection: DetectionResult, modelId: String?, outcome: Outcome): DetectionResult =
        detection.copy(
            secondaryModelId = modelId,
            secondaryStatus = outcome.status,
            secondaryConfidence = outcome.confidence,
            secondaryTopSpecies = outcome.topSpecies,
            secondaryTopConfidence = outcome.topConfidence
        )

    /**
     * Fenster des Hauptmodells auf die Fensterlaenge des Zweitmodells bringen (gleiche Abtastrate):
     * gleich lang → unveraendert; kuerzer → Mitte ausschneiden; laenger → zentriert mit Stille auffuellen.
     * T231: laengere Fenster (Perch 5 s) holen echtes Umgebungsaudio aus dem Ringpuffer ([contextRange]);
     * Stille nur noch als Rueckfall (Aufnahme kuerzer als das Fenster, Puffer verpasst).
     */
    fun fitWindow(pcm: ShortArray, targetSamples: Int): ShortArray {
        if (targetSamples <= 0 || pcm.size == targetSamples) return pcm
        return if (pcm.size > targetSamples) {
            val from = (pcm.size - targetSamples) / 2
            pcm.copyOfRange(from, from + targetSamples)
        } else {
            val out = ShortArray(targetSamples)
            pcm.copyInto(out, (targetSamples - pcm.size) / 2)
            out
        }
    }

    /** T231: Audiobereich [from, to) auf der Session-Achse (Samples) */
    data class SampleRange(val from: Long, val to: Long) {
        val length: Long get() = to - from
    }

    /**
     * T231: Bereich fuer das Zweitmodell, zentriert um das Fenster des Hauptmodells
     * (Perch 5 s um BirdNET 3 s: 1 s davor, 1 s danach). Am Sessionanfang wird nach rechts geschoben
     * (beginnt bei [minIndex]), damit keine Stille noetig ist. Das Fenster liegt immer ganz im Bereich.
     */
    fun contextRange(windowStart: Long, windowLen: Int, targetLen: Int, minIndex: Long = 0L): SampleRange {
        val from = maxOf(minIndex, windowStart - (targetLen - windowLen) / 2)
        return SampleRange(from, from + targetLen)
    }

    /**
     * T231: Rueckfall, wenn das Audio nach dem Fenster nicht rechtzeitig kommt (Aufnahme gestoppt):
     * rechtsbuendig bis [availableEnd], hoechstens [targetLen] lang, nicht vor [minIndex].
     * Kuerzer als [targetLen] nur, wenn insgesamt zu wenig Audio vorliegt (dann Stille, Hinweis im Log).
     */
    fun rightAlignedRange(availableEnd: Long, targetLen: Int, minIndex: Long = 0L): SampleRange =
        SampleRange(maxOf(minIndex, availableEnd - targetLen), availableEnd)

    /** T231: Fensterstart (s, Session-Achse) → Sample-Index bei [sampleRate] */
    fun startIndexOf(windowStartSec: Float, sampleRate: Int): Long =
        Math.round(windowStartSec.toDouble() * sampleRate)

    /** Kurzer Modellname fuer die Artkarte ("BirdNET 3.0 FP16 (empfohlen)" → "BirdNET 3.0 FP16"); unbekannt → ID */
    fun modelLabel(modelId: String?): String? {
        if (modelId == null) return null
        val name = ModelProfile.byId(modelId)?.name ?: return modelId
        return name.substringBefore(" (").trim().ifEmpty { name }
    }

    /** Anzahl Samples des Zweitmodell-Fensters bei der Abtastrate [sampleRate] der Aufnahme */
    fun windowSamplesAt(windowMs: Int, sampleRate: Int): Int =
        (windowMs.toLong() * sampleRate / 1000L).toInt()

    /**
     * Ueberlappung des Zweitmodells (Pref `pirol_secondary_overlap_percent`): pro Art wird hoechstens
     * alle Hop-Sekunden ein Fenster geprueft (0 % = ein Fenster pro Fensterlaenge). Neue Arten werden
     * immer geprueft. Nicht thread-safe (nur aus der Analyse-Coroutine aufrufen).
     */
    class OverlapGate(windowMs: Int, overlapPercent: Int, maxHopMs: Int = Int.MAX_VALUE) {
        // T231: Hop wie beim Hauptmodell hoechstens maxHopMs (Perch 5 s: pro Art alle 3 s)
        private val hopSec = OverlapFallback.hopMsFor(overlapPercent, windowMs, maxHopMs) / 1000f
        private val lastStart = HashMap<String, Float>()

        /** true = Art in diesem Fenster pruefen (und als geprueft merken) */
        fun accept(species: String, windowStartSec: Float): Boolean {
            val key = SpeciesKey.of(species)
            val last = lastStart[key]
            val ok = last == null || windowStartSec < last || windowStartSec - last >= hopSec - WINDOW_EPS_SEC
            if (ok) lastStart[key] = windowStartSec
            return ok
        }
    }
}
