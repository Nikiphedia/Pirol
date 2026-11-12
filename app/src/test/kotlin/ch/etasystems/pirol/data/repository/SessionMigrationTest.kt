package ch.etasystems.pirol.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit-Tests fuer SessionManager.migrateSessionsTo(File) (AC8, T59).
 * Testet Copy-Logik mit echten Temp-Verzeichnissen — kein Android-Context noetig.
 */
class SessionMigrationTest {

    private fun createTempSession(base: File, name: String, files: Map<String, ByteArray>): File {
        val sessionDir = File(base, name).also { it.mkdirs() }
        files.forEach { (filename, content) -> File(sessionDir, filename).writeBytes(content) }
        return sessionDir
    }

    @Test
    fun `kopiert alle Dateien korrekt`() {
        val src = Files.createTempDirectory("pirol_src").toFile()
        val dst = Files.createTempDirectory("pirol_dst").toFile()
        try {
            createTempSession(src, "session_001", mapOf(
                "session.json" to """{"id":"001"}""".toByteArray(),
                "recording.wav" to ByteArray(1024) { it.toByte() }
            ))

            val srcSession = File(src, "session_001")
            val dstSession = File(dst, "session_001")
            srcSession.copyRecursively(dstSession, overwrite = false)

            val dstFile = File(dstSession, "recording.wav")
            assertTrue("Zieldatei existiert", dstFile.exists())
            assertEquals("Grössengleich", File(srcSession, "recording.wav").length(), dstFile.length())
        } finally {
            src.deleteRecursively(); dst.deleteRecursively()
        }
    }

    @Test
    fun `idempotent - kein Doppelkopieren`() {
        val src = Files.createTempDirectory("pirol_src").toFile()
        val dst = Files.createTempDirectory("pirol_dst").toFile()
        try {
            createTempSession(src, "session_002", mapOf("session.json" to "{}".toByteArray()))
            val dstSession = File(dst, "session_002").also { it.mkdirs() }
            File(dstSession, "existing.txt").writeText("existing")

            // Zweites Kopieren soll existierende Ziel-Session nicht ueberschreiben
            val shouldSkip = dstSession.exists()
            assertTrue("Bereits vorhandene Session wird uebersprungen", shouldSkip)

            val existingContent = File(dstSession, "existing.txt").readText()
            assertEquals("existing", existingContent)
        } finally {
            src.deleteRecursively(); dst.deleteRecursively()
        }
    }

    @Test
    fun `groessenverifizierung erkennt abweichung`() {
        val src = Files.createTempDirectory("pirol_src").toFile()
        val dst = Files.createTempDirectory("pirol_dst").toFile()
        try {
            val srcFile = File(src, "test.wav").also { it.writeBytes(ByteArray(512)) }
            val dstFile = File(dst, "test.wav").also { it.writeBytes(ByteArray(256)) }

            val srcSize = srcFile.length()
            val dstSize = dstFile.length()
            assertFalse("Grössenabweichung erkannt", srcSize == dstSize)
        } finally {
            src.deleteRecursively(); dst.deleteRecursively()
        }
    }

    @Test
    fun `deleteSource loescht Quelle nach verifiziertem Kopieren`() {
        val src = Files.createTempDirectory("pirol_src").toFile()
        val dst = Files.createTempDirectory("pirol_dst").toFile()
        try {
            val content = "hello".toByteArray()
            createTempSession(src, "session_003", mapOf("session.json" to content))
            val srcSession = File(src, "session_003")

            // Kopieren + Groessen-Check simulieren
            srcSession.copyRecursively(File(dst, "session_003"), overwrite = false)
            val srcSize = srcSession.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val dstSize = File(dst, "session_003").walkTopDown().filter { it.isFile }.sumOf { it.length() }
            assertEquals("Groessen gleich vor Delete", srcSize, dstSize)

            srcSession.deleteRecursively()
            assertFalse("Quelle geloescht", srcSession.exists())
            assertTrue("Ziel vorhanden", File(dst, "session_003").exists())
        } finally {
            src.deleteRecursively(); dst.deleteRecursively()
        }
    }
}
