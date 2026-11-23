package ch.etasystems.pirol.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** T238: Modell-Session wird nie waehrend einer Inferenz geschlossen und verwaist nie. */
class SessionGuardTest {

    private class FakeSession(val id: Int) {
        val closed = AtomicInteger(0)
        @Volatile var inUse = 0
    }

    @Test
    fun `Freigabe waehrend Inferenz wartet auf das Laufende`() {
        val g = SessionGuard<FakeSession> { it.closed.incrementAndGet() }
        val s = FakeSession(1)
        g.install(s)
        assertSame(s, g.begin())
        assertFalse("nie waehrend run()", g.releaseIfIdle())
        assertTrue(g.detach())               // Modellwechsel waehrend Inferenz
        assertEquals(0, s.closed.get())
        assertEquals(1, g.pendingClose)
        assertNull(g.session)
        g.end()
        assertEquals(1, s.closed.get())
        assertEquals(0, g.pendingClose)
    }

    @Test
    fun `ohne Inferenz sofort geschlossen, ersetzte Session verwaist nicht`() {
        val g = SessionGuard<FakeSession> { it.closed.incrementAndGet() }
        val a = FakeSession(1)
        val b = FakeSession(2)
        g.install(a)
        g.install(b)
        assertEquals(1, a.closed.get())
        assertTrue(g.releaseIfIdle())
        assertEquals(1, b.closed.get())
        assertFalse(g.releaseIfIdle())
        assertNull(g.begin())
    }

    @Test
    fun `Stresstest - jede Session genau einmal und nie in Benutzung geschlossen`() {
        val created = Collections.synchronizedList(mutableListOf<FakeSession>())
        val violations = AtomicInteger(0)
        val g = SessionGuard<FakeSession> {
            if (it.inUse > 0) violations.incrementAndGet()
            it.closed.incrementAndGet()
        }
        val lock = Any()
        // wie BirdNetV3Classifier: Erzeugen + begin unter einer Sperre, end ohne
        fun acquire(): FakeSession? = synchronized(lock) {
            if (g.session == null) g.install(FakeSession(created.size).also { created.add(it) })
            g.begin()?.also { synchronized(it) { it.inUse++ } }
        }
        fun finish(s: FakeSession) {
            synchronized(s) { s.inUse-- }
            g.end()
        }
        val stop = AtomicBoolean(false)
        val runners = (1..4).map {
            thread {
                while (!stop.get()) {
                    val s = acquire() ?: continue
                    if (s.closed.get() > 0) violations.incrementAndGet()
                    Thread.yield()
                    finish(s)
                }
            }
        }
        val resetter = thread {
            repeat(2000) { i ->
                synchronized(lock) { if (i % 2 == 0) g.detach() else g.releaseIfIdle() }
                Thread.yield()
            }
        }
        resetter.join()
        stop.set(true)
        runners.forEach { it.join() }
        synchronized(lock) { g.detach() }

        assertEquals("Session waehrend Inferenz geschlossen", 0, violations.get())
        assertEquals(0, g.activeRuns)
        assertEquals(0, g.pendingClose)
        assertTrue(created.size > 1)
        assertTrue("jede Session genau einmal geschlossen", created.all { it.closed.get() == 1 })
    }
}
