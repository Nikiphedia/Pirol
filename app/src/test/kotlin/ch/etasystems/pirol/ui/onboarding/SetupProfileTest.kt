package ch.etasystems.pirol.ui.onboarding

import ch.etasystems.pirol.data.GPS_MODE_ON_DETECTION
import ch.etasystems.pirol.ml.TaxonGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T226: Profil -> Einstellungen */
class SetupProfileTest {

    private fun r(vararg p: SetupPurpose, list: SetupList = SetupList.AUTO, sec: String? = "second.onnx") =
        SetupProfile.resolve(SetupChoices(purposes = p.toSet(), list = list), listOfNotNull(sec))

    @Test fun voegelImFeld() {
        val s = r(SetupPurpose.FIELD_BIRDS)
        assertEquals(0.5f, s.threshold); assertEquals(0.10f, s.alternativesFloor)
        assertEquals(0, s.overlapPercent); assertNull(s.secondaryModelFileName)
        assertEquals(GPS_MODE_ON_DETECTION, s.gpsMode); assertEquals(true, s.compactCard)
        assertEquals("auto_geo", s.birdsSource)
        assertEquals(mapOf(TaxonGroup.ORTHOPTERA to "off", TaxonGroup.AMPHIBIANS to "off",
            TaxonGroup.MAMMALS to "off", TaxonGroup.OTHER to "off"), s.groupSources)
        assertFalse(s.geoYearly); assertFalse(s.respectListSeason)
    }

    @Test fun heuschreckenAmphibien() {
        val s = r(SetupPurpose.ORTHOPTERA_AMPHIBIANS)
        assertEquals(0.3f, s.threshold); assertEquals(50, s.overlapPercent)
        assertTrue(s.geoYearly); assertEquals(24000f, s.freqMaxHz); assertNull(s.secondaryModelFileName)
    }

    // T232: Heuschrecken/Amphibien -> mitgelieferte Liste je Gruppe, Voegel folgen der Listenwahl
    @Test fun heuschreckenAmphibienJeGruppe() {
        val s = r(SetupPurpose.ORTHOPTERA_AMPHIBIANS)
        assertEquals("auto_geo", s.birdsSource)
        assertEquals("ch_orthoptera_anura", s.groupSources[TaxonGroup.ORTHOPTERA])
        assertEquals("ch_orthoptera_anura", s.groupSources[TaxonGroup.AMPHIBIANS])
        assertEquals("off", s.groupSources[TaxonGroup.MAMMALS])
        assertEquals("off", s.groupSources[TaxonGroup.OTHER])
        assertFalse(s.respectListSeason)
        val manual = r(SetupPurpose.ORTHOPTERA_AMPHIBIANS, list = SetupList.SWITZERLAND)
        assertEquals("ch_breeding", manual.birdsSource)
        assertEquals("ch_orthoptera_anura", manual.groupSources[TaxonGroup.ORTHOPTERA])
        assertEquals("off", r(SetupPurpose.FIELD_BIRDS).groupSources[TaxonGroup.ORTHOPTERA])
        assertEquals("ch_orthoptera_anura",
            r(SetupPurpose.FIELD_BIRDS, SetupPurpose.ORTHOPTERA_AMPHIBIANS).groupSources[TaxonGroup.AMPHIBIANS])
    }

    @Test fun monitoringNurVoegel() {
        val s = r(SetupPurpose.MONITORING)
        assertEquals("auto_geo", s.birdsSource)
        assertTrue(s.groupSources.values.all { it == "off" })
    }

    @Test fun forschungGruppen() {
        val s = r(SetupPurpose.RESEARCH)
        assertEquals("auto_geo", s.birdsSource)
        assertEquals("auto_geo", s.groupSources[TaxonGroup.ORTHOPTERA])
        assertEquals("auto_geo", s.groupSources[TaxonGroup.AMPHIBIANS])
        assertEquals("all", s.groupSources[TaxonGroup.MAMMALS])
        assertEquals("off", s.groupSources[TaxonGroup.OTHER])
        // Heuschrecken-Profil steht vor Forschung -> Liste gewinnt
        assertEquals("ch_orthoptera_anura",
            r(SetupPurpose.ORTHOPTERA_AMPHIBIANS, SetupPurpose.RESEARCH).groupSources[TaxonGroup.ORTHOPTERA])
        assertEquals("all", r(SetupPurpose.ORTHOPTERA_AMPHIBIANS, SetupPurpose.RESEARCH).groupSources[TaxonGroup.MAMMALS])
    }

    // T228: Zweitmodell-Auswahl und Hinweislogik
    @Test fun zweitmodellAuswahlUndHinweis() {
        val two = listOf("a.onnx", "b.onnx")
        fun res(expert: Boolean, chosen: String?, cands: List<String>, ov: Boolean? = null) = SetupProfile.resolve(
            SetupChoices(expert = expert, purposes = setOf(SetupPurpose.RESEARCH), secondaryModel = chosen, secondaryOverride = ov), cands)
        assertEquals("a.onnx", res(false, null, two).secondaryModelFileName)           // Laie: automatisch erstes
        assertEquals("b.onnx", res(true, "b.onnx", two).secondaryModelFileName)        // Experte: gewaehlt
        assertEquals("a.onnx", res(true, "fehlt.onnx", two).secondaryModelFileName)    // unbekannte Wahl -> erstes
        val none = res(false, null, emptyList())
        assertNull(none.secondaryModelFileName); assertTrue(none.secondaryMissing)     // Hinweis noetig
        assertFalse(res(false, null, two).secondaryMissing)
        assertFalse(res(true, null, emptyList(), ov = false).secondaryMissing)         // nicht gewuenscht -> kein Hinweis
        assertFalse(r(SetupPurpose.FIELD_BIRDS, sec = null).secondaryMissing)
    }

    @Test fun monitoring() {
        val s = r(SetupPurpose.MONITORING)
        assertEquals(0.5f, s.threshold); assertEquals(0.20f, s.alternativesFloor)
        assertEquals(0, s.overlapPercent); assertEquals(60, s.maxRecordingMinutes)
        assertEquals(true, s.trackRecording)
    }

    @Test fun forschungMitUndOhneZweitmodell() {
        val s = r(SetupPurpose.RESEARCH)
        assertEquals(0.3f, s.threshold); assertEquals(0.05f, s.alternativesFloor)
        assertEquals(50, s.overlapPercent); assertEquals("second.onnx", s.secondaryModelFileName)
        assertEquals(false, s.compactCard)
        assertNull(r(SetupPurpose.RESEARCH, sec = null).secondaryModelFileName)
    }

    @Test fun mehrfachwahlFuehrtZusammen() {
        val s = r(SetupPurpose.FIELD_BIRDS, SetupPurpose.MONITORING, SetupPurpose.ORTHOPTERA_AMPHIBIANS)
        assertEquals(0.3f, s.threshold); assertEquals(0.10f, s.alternativesFloor)
        assertEquals(50, s.overlapPercent); assertTrue(s.geoYearly)
        assertEquals(GPS_MODE_ON_DETECTION, s.gpsMode); assertEquals(60, s.maxRecordingMinutes)
    }

    @Test fun ohneWahlGiltVoegelImFeld() {
        val s = SetupProfile.resolve(SetupChoices(purposes = emptySet()), emptyList())
        assertEquals(0.5f, s.threshold)
    }

    @Test fun listenwahl() {
        assertEquals("ch_breeding", r(SetupPurpose.FIELD_BIRDS, list = SetupList.SWITZERLAND).birdsSource)
        assertEquals("central_europe", r(SetupPurpose.FIELD_BIRDS, list = SetupList.REGION).birdsSource)
        assertNull(r(SetupPurpose.FIELD_BIRDS, list = SetupList.CUSTOM).birdsSource)
    }

    @Test fun expertenOverridesGewinnen() {
        val s = SetupProfile.resolve(
            SetupChoices(expert = true, purposes = setOf(SetupPurpose.FIELD_BIRDS),
                thresholdOverride = 0.7f, overlapOverride = 67, secondaryOverride = true), listOf("x.onnx")
        )
        assertEquals(0.7f, s.threshold); assertEquals(67, s.overlapPercent)
        assertEquals("x.onnx", s.secondaryModelFileName)
    }

    @Test fun jedeKombinationLiefertGueltigeWerte() {
        val all = SetupPurpose.entries
        for (mask in 1 until (1 shl all.size)) {
            val ps = all.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            val s = SetupProfile.resolve(SetupChoices(purposes = ps), emptyList())
            assertTrue(s.threshold in 0.3f..0.5f)
            assertTrue(s.alternativesFloor in 0.05f..0.20f)
            assertTrue(s.overlapPercent in listOf(0, 50))
        }
    }
}
