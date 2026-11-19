package ch.etasystems.pirol.data.repository

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIdFormatTest {
    @Test
    fun sessionIdUsesGivenLocalTime() {
        val id = buildSessionId(LocalDateTime.of(2026, 11, 19, 8, 10, 5), "abc123")
        assertEquals("2026-11-19T08-10-05_abc123", id)
    }

    @Test
    fun sessionIdIsFilesystemSafe() {
        val id = buildSessionId(LocalDateTime.of(2026, 1, 2, 23, 59, 59), "ffffff")
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}_[0-9a-f]{6}""").matches(id))
    }
}
