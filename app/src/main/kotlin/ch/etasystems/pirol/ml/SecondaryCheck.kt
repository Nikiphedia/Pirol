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

    /**
     * Fensterzuordnung im Cluster (T70/T186): das Ergebnis eines Fensters gilt fuer die Detektion, wenn
     * es ihr bestes Fenster ist oder sie noch kein Ergebnis hat. So traegt ein Cluster das Ergebnis
     * seines besten Fensters; spaetere, schwaechere Fenster ueberschreiben es nicht.
     */
    fun shouldApply(detection: DetectionResult, windowStartSec: Float): Boolean =
        detection.secondaryStatus == null || abs(detection.bestWindowStartSec - windowStartSec) < WINDOW_EPS_SEC

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
     * (Echte Umgebung statt Stille fuer laengere Fenster, z.B. Perch 5 s, braucht einen Ringpuffer → eigenes AP.)
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
    class OverlapGate(windowMs: Int, overlapPercent: Int) {
        private val hopSec = OverlapFallback.hopMsFor(overlapPercent, windowMs) / 1000f
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
