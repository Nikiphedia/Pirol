package ch.etasystems.pirol.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UploadConfigTest {

    private val ok = SmbSettings("nas.local", "feld", "PIROL", "user")

    @Test fun validSettingsHaveNoErrors() {
        assertTrue(UploadConfig.validateSmb(ok, "pw").isEmpty())
    }

    @Test fun missingFieldsReported() {
        val errors = UploadConfig.validateSmb(SmbSettings("", "", "", ""), null)
        assertEquals(4, errors.size)
    }

    @Test fun hostWithSlashRejected() {
        assertTrue(UploadConfig.validateSmb(ok.copy(host = "nas/x"), "pw").isNotEmpty())
        assertTrue(UploadConfig.validateSmb(ok.copy(host = "n as"), "pw").isNotEmpty())
    }

    @Test fun shareWithIllegalCharRejected() {
        assertTrue(UploadConfig.validateSmb(ok.copy(share = "a\\b"), "pw").isNotEmpty())
        assertTrue(UploadConfig.validateSmb(ok.copy(share = "a*"), "pw").isNotEmpty())
    }

    @Test fun pathNormalization() {
        assertEquals("a/b/c", UploadConfig.normalizeFolderPath("\\a\\b//c/"))
        assertEquals("", UploadConfig.normalizeFolderPath("  "))
        assertEquals("a/b", UploadConfig.normalizeFolderPath("a/../b/./"))
        assertNull(UploadConfig.normalizeFolderPath("a/b?c"))
    }

    @Test fun remotePathBuilding() {
        assertEquals("PIROL/x.zip", UploadConfig.buildRemotePath("PIROL", "x.zip"))
        assertEquals("x.zip", UploadConfig.buildRemotePath("", "x.zip"))
        assertEquals("a/b/x.zip", UploadConfig.buildRemotePath("/a\\b/", "x.zip"))
    }

    @Test fun remotePathCannotEscape() {
        assertEquals("a/x.zip", UploadConfig.buildRemotePath("../../a", "x.zip"))
        assertEquals("a/_x.zip", UploadConfig.buildRemotePath("a", "/x.zip"))
    }

    @Test fun invalidPathThrows() {
        try {
            UploadConfig.buildRemotePath("a:b", "x.zip")
            fail()
        } catch (_: UploadConfigException) {
        }
    }

    @Test fun fileNameSanitizing() {
        assertEquals("a_b_c.zip", UploadConfig.sanitizeFileName("a/b:c.zip"))
        assertEquals("session.zip", UploadConfig.sanitizeFileName("   "))
        assertEquals("_x.zip", UploadConfig.sanitizeFileName("../x.zip"))
    }

    @Test fun userSplit() {
        assertEquals("DOM" to "hans", UploadConfig.splitUser("DOM\\hans"))
        assertEquals("" to "hans", UploadConfig.splitUser(" hans "))
    }

    @Test fun targetTypeDefaultsToOff() {
        assertEquals(UploadTargetType.OFF, UploadTargetType.fromName(null))
        assertEquals(UploadTargetType.OFF, UploadTargetType.fromName("quatsch"))
        assertEquals(UploadTargetType.SMB, UploadTargetType.fromName("SMB"))
    }

    @Test fun errorDescription() {
        assertEquals(
            "Anmeldung fehlgeschlagen (Benutzer/Passwort)",
            UploadConfig.describeError(RuntimeException("STATUS_LOGON_FAILURE"))
        )
        assertEquals("Host nicht gefunden", UploadConfig.describeError(java.net.UnknownHostException("x")))
    }
}
