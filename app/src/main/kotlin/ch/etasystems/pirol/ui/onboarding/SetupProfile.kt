package ch.etasystems.pirol.ui.onboarding

import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.GPS_MODE_ON_DETECTION
import ch.etasystems.pirol.ml.GeoCombine
import ch.etasystems.pirol.ml.GeoLogic

/** T226: Einsatzzweck im Einrichtungsassistenten (Mehrfachwahl). Reihenfolge = Vorrang bei Konflikten. */
enum class SetupPurpose { FIELD_BIRDS, ORTHOPTERA_AMPHIBIANS, MONITORING, RESEARCH }

/** T226: Wahl der Artenliste. CUSTOM laesst die Liste unveraendert (Auswahl in den Einstellungen). */
enum class SetupList(val regionId: String?) {
    AUTO(GeoLogic.REGION_ID),
    SWITZERLAND("ch_breeding"),
    REGION("central_europe"),
    CUSTOM(null)
}

/** T226: Antworten des Users im Assistenten. Overrides gibt es nur im Experten-Modus. */
data class SetupChoices(
    val expert: Boolean = false,
    val purposes: Set<SetupPurpose> = setOf(SetupPurpose.FIELD_BIRDS),
    val list: SetupList = SetupList.AUTO,
    val thresholdOverride: Float? = null,
    val overlapOverride: Int? = null,
    val secondaryOverride: Boolean? = null
)

/** T226: Werte eines Profils. null = Einstellung unveraendert lassen. */
data class ProfileValues(
    val threshold: Float,
    val alternativesFloor: Float,
    val overlapPercent: Int,
    val secondary: Boolean,
    val geoYearly: Boolean = false,
    val freqMaxHz: Float? = null,
    val gpsMode: String? = null,
    val compactCard: Boolean? = null,
    val trackRecording: Boolean? = null,
    val maxRecordingMinutes: Int? = null
)

/** T226: fertig aufgeloeste Einstellungen (Ergebnis von [SetupProfile.resolve]). */
data class SetupSettings(
    val regionFilter: String?,
    val geoCombine: String,
    val geoYearly: Boolean,
    val threshold: Float,
    val alternativesFloor: Float,
    val overlapPercent: Int,
    val secondaryModelFileName: String?,
    val respectListSeason: Boolean,
    val freqMaxHz: Float?,
    val gpsMode: String?,
    val compactCard: Boolean?,
    val trackRecording: Boolean?,
    val maxRecordingMinutes: Int?
)

/** T226: Profil -> bestehende Einstellungen (reine Logik, ohne Android). */
object SetupProfile {

    /** Tabelle der Profile (Begruendung im Handover T226). */
    fun values(purpose: SetupPurpose): ProfileValues = when (purpose) {
        SetupPurpose.FIELD_BIRDS -> ProfileValues(
            threshold = 0.5f, alternativesFloor = 0.10f, overlapPercent = 0, secondary = false,
            gpsMode = GPS_MODE_ON_DETECTION, compactCard = true
        )
        SetupPurpose.ORTHOPTERA_AMPHIBIANS -> ProfileValues(
            threshold = 0.3f, alternativesFloor = 0.10f, overlapPercent = 50, secondary = false,
            geoYearly = true, freqMaxHz = 24000f
        )
        SetupPurpose.MONITORING -> ProfileValues(
            threshold = 0.5f, alternativesFloor = 0.20f, overlapPercent = 0, secondary = false,
            trackRecording = true, maxRecordingMinutes = 60
        )
        SetupPurpose.RESEARCH -> ProfileValues(
            threshold = 0.3f, alternativesFloor = 0.05f, overlapPercent = 50, secondary = true,
            compactCard = false
        )
    }

    /**
     * Mehrfachwahl zusammenfuehren: empfindlichste Schwelle und Alternativen-Grenze (Minimum),
     * hoechste Ueberlappung, Zweitmodell/Jahresliste wenn irgendein Profil sie will,
     * hoechster Frequenzbereich; optionale Werte vom ersten Profil (Reihenfolge des Enums), das sie setzt.
     * Ohne Wahl gilt "Voegel im Feld".
     */
    fun resolve(choices: SetupChoices, secondaryCandidate: String?): SetupSettings {
        val ps = choices.purposes.ifEmpty { setOf(SetupPurpose.FIELD_BIRDS) }
            .sortedBy { it.ordinal }
        val vs = ps.map { values(it) }
        fun <T> first(sel: (ProfileValues) -> T?): T? = vs.firstNotNullOfOrNull(sel)

        val wantSecondary = choices.secondaryOverride ?: vs.any { it.secondary }
        return SetupSettings(
            regionFilter = choices.list.regionId,
            geoCombine = GeoCombine.GEO_ONLY.name,
            geoYearly = vs.any { it.geoYearly },
            threshold = choices.thresholdOverride ?: vs.minOf { it.threshold },
            alternativesFloor = vs.minOf { it.alternativesFloor },
            overlapPercent = choices.overlapOverride ?: vs.maxOf { it.overlapPercent },
            secondaryModelFileName = if (wantSecondary) secondaryCandidate else null,
            respectListSeason = false,
            freqMaxHz = vs.mapNotNull { it.freqMaxHz }.maxOrNull(),
            gpsMode = first { it.gpsMode },
            compactCard = first { it.compactCard },
            trackRecording = first { it.trackRecording },
            maxRecordingMinutes = first { it.maxRecordingMinutes }
        )
    }

    /** Werte in die bestehenden Preferences schreiben (nur vorhandene Einstellungen). */
    fun apply(s: SetupSettings, expert: Boolean, prefs: AppPreferences) {
        prefs.expertMode = expert
        s.regionFilter?.let { prefs.regionFilter = it }
        prefs.geoCombine = s.geoCombine
        prefs.geoYearly = s.geoYearly
        prefs.confidenceThreshold = s.threshold
        prefs.alternativesFloor = s.alternativesFloor
        prefs.overlapPercent = s.overlapPercent
        prefs.secondaryModelFileName = s.secondaryModelFileName
        prefs.respectListSeason = s.respectListSeason
        s.freqMaxHz?.let { prefs.spectrogramFreqMaxHz = it }
        s.gpsMode?.let { prefs.gpsMode = it }
        s.compactCard?.let { prefs.compactSpeciesCard = it }
        s.trackRecording?.let { prefs.trackRecordingEnabled = it }
        s.maxRecordingMinutes?.let { prefs.maxRecordingMinutes = it }
    }
}
