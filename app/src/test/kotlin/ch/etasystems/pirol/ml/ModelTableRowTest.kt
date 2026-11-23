package ch.etasystems.pirol.ml

import ch.etasystems.pirol.ml.ModelRoles.DeleteBlock
import ch.etasystems.pirol.ml.ModelTableRow.Trailing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T227: Zeilenzustand der Modelltabelle (Papierkorb / Lade-Symbol / Fortschritt) */
class ModelTableRowTest {

    @Test fun installedShowsDelete() {
        assertEquals(Trailing.DELETE, ModelTableRow.trailing(true, false, false, DeleteBlock.NONE))
    }

    @Test fun installedPrimaryOrRecordingDeleteDisabled() {
        assertEquals(Trailing.DELETE_DISABLED, ModelTableRow.trailing(true, false, false, DeleteBlock.PRIMARY))
        assertEquals(Trailing.DELETE_DISABLED, ModelTableRow.trailing(true, false, false, DeleteBlock.RECORDING))
    }

    @Test fun notInstalledShowsDownload() {
        assertEquals(Trailing.DOWNLOAD, ModelTableRow.trailing(false, false, false, DeleteBlock.NONE))
    }

    @Test fun downloadingThisShowsProgress() {
        assertEquals(Trailing.PROGRESS, ModelTableRow.trailing(false, true, false, DeleteBlock.NONE))
    }

    @Test fun otherDownloadRunningDisablesDownload() {
        assertEquals(Trailing.DOWNLOAD_DISABLED, ModelTableRow.trailing(false, false, true, DeleteBlock.NONE))
    }

    @Test fun rolesOnlyForInstalled() {
        assertFalse(ModelTableRow.primaryEnabled(false))
        assertTrue(ModelTableRow.primaryEnabled(true))
        assertFalse(ModelTableRow.secondaryEnabled(false, false))
        assertFalse(ModelTableRow.secondaryEnabled(true, true))
        assertTrue(ModelTableRow.secondaryEnabled(true, false))
    }

    @Test fun missingCatalogListsOnlyUninstalledDownloadable() {
        val downloadable = ModelProfile.CATALOG.filter { it.downloadUrl != null }
        assertEquals(downloadable.size, ModelTableRow.missingCatalog(ModelProfile.CATALOG, emptySet()).size)
        val all = downloadable.map { it.fileName }.toSet()
        assertTrue(ModelTableRow.missingCatalog(ModelProfile.CATALOG, all).isEmpty())
    }
}
