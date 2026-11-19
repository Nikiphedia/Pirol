package ch.etasystems.pirol.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T141: reine Entscheidungs- und Formatlogik der Session-Migration. */
class SessionMigrationLogicTest {

    @Test
    fun `Aufnahme laeuft verweigert vor allem anderen`() {
        assertEquals(MigrationRefusal.RECORDING_ACTIVE, SessionMigrationLogic.refusal(true, 5, false))
        assertEquals(MigrationRefusal.RECORDING_ACTIVE, SessionMigrationLogic.refusal(true, 0, true))
    }

    @Test
    fun `gleicher Ort und keine Sessions werden verweigert`() {
        assertEquals(MigrationRefusal.SAME_LOCATION, SessionMigrationLogic.refusal(false, 5, true))
        assertEquals(MigrationRefusal.NO_SESSIONS, SessionMigrationLogic.refusal(false, 0, false))
        assertNull(SessionMigrationLogic.refusal(false, 3, false))
    }

    @Test
    fun `Originale nur bei Wunsch und ohne Fehler loeschen`() {
        assertTrue(SessionMigrationLogic.shouldDeleteSources(true, 0))
        assertFalse(SessionMigrationLogic.shouldDeleteSources(true, 1))
        assertFalse(SessionMigrationLogic.shouldDeleteSources(false, 0))
    }

    @Test
    fun `Speicherort nur bei fehlerfreiem Lauf umstellen`() {
        assertTrue(SessionMigrationLogic.shouldSwitchStorage(true, 0))
        assertFalse(SessionMigrationLogic.shouldSwitchStorage(true, 2))
        assertFalse(SessionMigrationLogic.shouldSwitchStorage(false, 0))
    }

    @Test
    fun `Groessenformat`() {
        assertEquals("999 B", SessionMigrationLogic.formatSize(999))
        assertEquals("1.5 KB", SessionMigrationLogic.formatSize(1_500))
        assertEquals("12.3 MB", SessionMigrationLogic.formatSize(12_300_000))
        assertEquals("1.20 GB", SessionMigrationLogic.formatSize(1_200_000_000))
    }

    @Test
    fun `Tagesordner wird aus Elternordner erkannt`() {
        assertEquals("2026-04-20", SessionMigrationLogic.dayDirOf(File("/x/PIROL/2026-04-20/s1")))
        assertNull(SessionMigrationLogic.dayDirOf(File("/x/PIROL/s1")))
        assertEquals("2026-04-20", SessionMigrationLogic.dayDirOf(SessionFilesLogic.safKey("2026-04-20", "s1")))
        assertNull(SessionMigrationLogic.dayDirOf(SessionFilesLogic.safKey(null, "s1")))
    }

    @Test
    fun `Prozent begrenzt und ohne Division durch null`() {
        assertEquals(0, SessionMigrationLogic.percent(0, 0))
        assertEquals(50, SessionMigrationLogic.percent(1, 2))
        assertEquals(100, SessionMigrationLogic.percent(5, 3))
    }
}
