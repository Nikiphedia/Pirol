package ch.etasystems.pirol.ui.onboarding

import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.GPS_MODE_ON_DETECTION
import ch.etasystems.pirol.ml.GroupSource
import ch.etasystems.pirol.ml.TaxonGroup

/** T226: Einsatzzweck im Einrichtungsassistenten (Mehrfachwahl). Reihenfolge = Vorrang bei Konflikten. */
enum class SetupPurpose { FIELD_BIRDS, ORTHOPTERA_AMPHIBIANS, MONITORING, RESEARCH }

/** T226: Wahl der Artenliste (T232: fuer die Voegel). CUSTOM laesst die Wahl unveraendert (Einstellungen). */
enum class SetupList(val regionId: String?) {
    AUTO(GroupSource.AUTO),
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
    val secondaryOverride: Boolean? = null,
    /** T228: gewaehltes Zweitmodell (Dateiname), nur Experte; null = erstes installiertes */
    val secondaryModel: String? = null
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
    val maxRecordingMinutes: Int? = null,
    /** T232: Quelle je Artgruppe ausser Voegeln (Voegel folgen der Listenwahl); fehlend = Aus */
    val groups: Map<TaxonGroup, String> = emptyMap()
)

/** T226: fertig aufgeloeste Einstellungen (Ergebnis von [SetupProfile.resolve]). */
data class SetupSettings(
    /** T232: Quelle der Voegel (Listenwahl); null = unveraendert */
    val birdsSource: String?,
    /** T232: Quelle der uebrigen Artgruppen (immer gesetzt, fehlend = Aus) */
    val groupSources: Map<TaxonGroup, String>,
    val geoYearly: Boolean,
    val threshold: Float,
    val alternativesFloor: Float,
    val overlapPercent: Int,
    val secondaryModelFileName: String?,
    /** T228: Zweitmodell gewuenscht, aber keins installiert (Hinweis mit Weg zum Import) */
    val secondaryMissing: Boolean,
    val respectListSeason: Boolean,
    val freqMaxHz: Float?,
    val gpsMode: String?,
    val compactCard: Boolean?,
    val trackRecording: Boolean?,
    val maxRecordingMinutes: Int?
)

/** T226: Profil -> bestehende Einstellungen (reine Logik, ohne Android). */
object SetupProfile {

    /** T228: mitgelieferte Liste (region_sets.json) Heuschrecken und Froschlurche der Schweiz */
    const val ORTHOPTERA_LIST_ID = "ch_orthoptera_anura"

    /** Tabelle der Profile (Begruendung im Handover T226). */
    fun values(purpose: SetupPurpose): ProfileValues = when (purpose) {
        SetupPurpose.FIELD_BIRDS -> ProfileValues(
            threshold = 0.5f, alternativesFloor = 0.10f, overlapPercent = 0, secondary = false,
            gpsMode = GPS_MODE_ON_DETECTION, compactCard = true
        )
        SetupPurpose.ORTHOPTERA_AMPHIBIANS -> ProfileValues(
            threshold = 0.3f, alternativesFloor = 0.10f, overlapPercent = 50, secondary = false,
            geoYearly = true, freqMaxHz = 24000f,
            groups = mapOf(TaxonGroup.ORTHOPTERA to ORTHOPTERA_LIST_ID, TaxonGroup.AMPHIBIANS to ORTHOPTERA_LIST_ID)
        )
        SetupPurpose.MONITORING -> ProfileValues(
            threshold = 0.5f, alternativesFloor = 0.20f, overlapPercent = 0, secondary = false,
            trackRecording = true, maxRecordingMinutes = 60
        )
        SetupPurpose.RESEARCH -> ProfileValues(
            threshold = 0.3f, alternativesFloor = 0.05f, overlapPercent = 50, secondary = true,
            compactCard = false,
            groups = mapOf(TaxonGroup.ORTHOPTERA to GroupSource.AUTO, TaxonGroup.AMPHIBIANS to GroupSource.AUTO,
                TaxonGroup.MAMMALS to GroupSource.ALL)
        )
    }

    /**
     * Mehrfachwahl zusammenfuehren: empfindlichste Schwelle und Alternativen-Grenze (Minimum),
     * hoechste Ueberlappung, Zweitmodell/Jahresliste wenn irgendein Profil sie will,
     * hoechster Frequenzbereich; optionale Werte vom ersten Profil (Reihenfolge des Enums), das sie setzt.
     * T232: je Artgruppe die erste Quelle (Enum-Reihenfolge), die nicht Aus ist; Voegel = Listenwahl.
     * Ohne Wahl gilt "Voegel im Feld".
     */
    fun resolve(choices: SetupChoices, secondaryCandidates: List<String>): SetupSettings {
        val ps = choices.purposes.ifEmpty { setOf(SetupPurpose.FIELD_BIRDS) }
            .sortedBy { it.ordinal }
        val vs = ps.map { values(it) }
        fun <T> first(sel: (ProfileValues) -> T?): T? = vs.firstNotNullOfOrNull(sel)

        val wantSecondary = choices.secondaryOverride ?: vs.any { it.secondary }
        val secondary = choices.secondaryModel?.takeIf { it in secondaryCandidates }
            ?: secondaryCandidates.firstOrNull()
        val groups = (TaxonGroup.entries - TaxonGroup.BIRDS).associateWith { g ->
            first { it.groups[g] } ?: GroupSource.OFF
        }
        return SetupSettings(
            birdsSource = choices.list.regionId,
            groupSources = groups,
            geoYearly = vs.any { it.geoYearly },
            threshold = choices.thresholdOverride ?: vs.minOf { it.threshold },
            alternativesFloor = vs.minOf { it.alternativesFloor },
            overlapPercent = choices.overlapOverride ?: vs.maxOf { it.overlapPercent },
            secondaryModelFileName = if (wantSecondary) secondary else null,
            secondaryMissing = wantSecondary && secondary == null,
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
        // T232: Liste je Artgruppe
        var sel = prefs.speciesGroups
        s.birdsSource?.let { sel = sel.with(TaxonGroup.BIRDS, it) }
        s.groupSources.forEach { (g, src) -> sel = sel.with(g, src) }
        prefs.speciesGroups = sel
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
