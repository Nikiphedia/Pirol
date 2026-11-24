package ch.etasystems.pirol.ml

import java.util.Locale

/**
 * T261: Welche Modell-Klassen menschliche Stimmen bedeuten (fuer das Ausblenden im Export).
 *
 * Erkennung ueber den Label-Namen (nicht ueber feste Indizes), damit jedes Profil passt:
 * - BirdNET 3 Preview 3/3.1: `Homo sapiens` (human) und `Homo Sapiens` (Human vocal)
 * - BirdNET 3.0 final (12K): `Homo sapiens` (Human)
 * - BirdNET 2.4 (Global 6K): `Human vocal`, `Human whistle` (`Human non-vocal` = Schritte/Klatschen, keine Stimme)
 * - Perch v2 (iNat2024 + FSD50K): `Homo sapiens` und die Stimm-Klassen aus FSD50K (Speech, Conversation, ...)
 *
 * Vergleich ohne Gross-/Kleinschreibung, Unterstrich = Leerzeichen. Reine Logik (unit-testbar).
 */
object HumanLabels {

    /** Normalisierte Namen (klein, Leerzeichen) der Stimm-Klassen aller bekannten Profile */
    private val NAMES: Set<String> = setOf(
        // BirdNET 3 (Preview, final), Perch (iNat)
        "homo sapiens",
        // BirdNET 2.4
        "human vocal",
        "human whistle",
        // Perch v2: FSD50K-Klassen mit menschlicher Stimme
        "human voice",
        "speech",
        "male speech and man speaking",
        "female speech and woman speaking",
        "child speech and kid speaking",
        "conversation",
        "speech synthesizer",
        "chatter",
        "crowd",
        "cheering",
        "shout",
        "yell",
        "screaming",
        "whispering",
        "laughter",
        "giggle",
        "chuckle and chortle",
        "crying and sobbing",
        "singing",
        "male singing",
        "female singing"
    )

    fun normalize(name: String): String = name.replace('_', ' ').trim().lowercase(Locale.ROOT)

    fun isHuman(name: String): Boolean = normalize(name) in NAMES

    /** Indizes der Stimm-Klassen in [names] (Label-Reihenfolge des Modells); leer = Modell erkennt keine Menschen */
    fun indicesOf(names: List<String>): IntArray =
        names.indices.filter { isHuman(names[it]) }.toIntArray()

    /**
     * Mensch-Score eines Fensters: Summe der Stimm-Klassen, hoechstens 1. Summe statt Maximum, weil sich die
     * Wahrscheinlichkeit bei Softmax-Modellen (Perch) auf mehrere Stimm-Klassen verteilt; bei Sigmoid-Modellen
     * ist sie hoechstens vorsichtiger. NaN (Stille bei 3.0 final) zaehlt als 0.
     * @return null, wenn [indices] leer ist (Modell kennt keine Menschen)
     */
    fun score(scores: FloatArray, indices: IntArray): Float? {
        if (indices.isEmpty()) return null
        var sum = 0f
        for (i in indices) {
            if (i !in scores.indices) continue
            val s = scores[i]
            if (!s.isNaN() && s > 0f) sum += s
        }
        return sum.coerceAtMost(1f)
    }
}
