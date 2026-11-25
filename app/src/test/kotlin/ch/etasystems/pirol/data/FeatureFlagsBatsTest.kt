package ch.etasystems.pirol.data

import ch.etasystems.pirol.data.importer.ImportPlanner
import ch.etasystems.pirol.data.importer.ImportTimeParser
import ch.etasystems.pirol.ml.GroupSource
import ch.etasystems.pirol.ml.ModelProfile
import ch.etasystems.pirol.ml.SpeciesGroupSelection
import ch.etasystems.pirol.ml.TaxonGroup
import ch.etasystems.pirol.ml.TaxonGroups
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** T297: Fledermaus-Funktionen hinter dem versteckten Schalter. */
class FeatureFlagsBatsTest {
    @Before fun off() { FeatureFlags.bats = false }
    @After fun reset() { FeatureFlags.bats = false }

    @Test fun importRateOffDownsamples() {
        assertEquals(48_000, ImportPlanner.targetRate(384_000))
        assertEquals(96_000, ImportPlanner.targetRate(96_000))
        FeatureFlags.bats = true
        assertEquals(384_000, ImportPlanner.targetRate(384_000))
    }

    @Test fun guanoTeIgnoredWhenOff() {
        val z = ZoneId.of("Europe/Zurich")
        assertEquals(1, ImportTimeParser.parseGuano("TE: 10", z).timeExpansion)
        FeatureFlags.bats = true
        assertEquals(10, ImportTimeParser.parseGuano("TE: 10", z).timeExpansion)
    }

    @Test fun batGroupHiddenWhenOff() {
        val sel = SpeciesGroupSelection(mapOf(TaxonGroup.BATS to GroupSource.ALL))
        assertEquals(GroupSource.OFF, sel.sourceOf(TaxonGroup.BATS))
        assertFalse(SpeciesGroupSelection.shortLabel(sel, mapOf(TaxonGroup.BATS to "F")) { it }.contains("F:"))
        assertEquals(TaxonGroup.MAMMALS, TaxonGroups.of("Myotis myotis"))
        FeatureFlags.bats = true
        assertEquals(GroupSource.ALL, sel.sourceOf(TaxonGroup.BATS))
        assertEquals(TaxonGroup.BATS, TaxonGroups.of("Myotis myotis"))
    }

    @Test fun ultrasonicProfileDetected() {
        // Grundlage der Ablehnung im Modellimport und der ausgeblendeten Modelltabelle
        assertTrue(ModelProfile.CATALOG.first().copy(minSourceRate = 192_000).isUltrasonic)
    }
}
