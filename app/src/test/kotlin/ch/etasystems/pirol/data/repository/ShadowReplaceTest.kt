package ch.etasystems.pirol.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T236: Kill-Simulation fuer das SAF-Ersetzen (Schattenkopie mit Fertig-Marke).
 * Der Prozess wird nach jedem einzelnen Schritt bzw. mitten in einem Schreibvorgang "beendet";
 * danach muss die Wiederherstellung immer eine vollstaendige Fassung liefern (alt oder neu), nie eine
 * leere oder abgeschnittene Datei.
 */
class ShadowReplaceTest {

    private class Killed : RuntimeException("Prozess beendet")

    /** Dateien im Speicher; [killAfter] = Zahl erlaubter Schreibschritte, danach Abbruch (Schreiben halb). */
    private class FakeStore(var killAfter: Int = Int.MAX_VALUE) : ShadowReplace.Store {
        val files = LinkedHashMap<String, String>()
        var steps = 0
        private fun step(): Boolean = (steps++ >= killAfter)
        override fun exists(name: String) = name in files
        override fun read(name: String) = files[name]
        override fun write(name: String, content: String) {
            if (step()) {
                // Kill mitten im Schreiben: gekuerzt + halber Inhalt ("wt"-Verhalten)
                files[name] = content.take(content.length / 2)
                throw Killed()
            }
            files[name] = content
        }
        override fun create(name: String) {
            if (step()) throw Killed()
            files[name] = ""
        }
        override fun delete(name: String) {
            if (step()) throw Killed()
            files.remove(name)
        }
    }

    private val name = "detections.jsonl"
    private val old = (1..50).joinToString("") { "{\"id\":\"d$it\",\"count\":1}\n" }
    private val new = (1..50).joinToString("") { "{\"id\":\"d$it\",\"count\":${if (it % 7 == 0) 9 else 1}}\n" }

    @Test fun ohneAbbruch_neuerInhalt_keineReste() {
        val store = FakeStore().apply { files[name] = old }
        ShadowReplace.replace(store, name, new)
        assertEquals(new, store.files[name])
        assertEquals(setOf(name), store.files.keys)
    }

    @Test fun killNachJedemSchritt_immerVollstaendig() {
        // 5 Schritte: write .new, create .ok, write Original, delete .ok, delete .new
        for (k in 0..5) {
            val store = FakeStore(killAfter = k).apply { files[name] = old }
            try { ShadowReplace.replace(store, name, new) } catch (_: Killed) {}
            // Neustart: Wiederherstellung ohne Abbruch
            store.killAfter = Int.MAX_VALUE
            ShadowReplace.recover(store, name)
            val content = store.files[name]
            assertTrue("k=$k: alt oder neu, nie leer/abgeschnitten", content == old || content == new)
            assertEquals("k=$k: keine Reste", setOf(name), store.files.keys)
            // Ab der Fertig-Marke gilt der neue Stand
            if (k >= 2) assertEquals("k=$k", new, content)
        }
    }

    @Test fun tempVorhanden_originalIntakt() {
        // Kill beim Schreiben der Schattenkopie: Temp halb, Original unberuehrt
        val store = FakeStore(killAfter = 0).apply { files[name] = old }
        try { ShadowReplace.replace(store, name, new) } catch (_: Killed) {}
        assertTrue(store.exists(ShadowReplace.shadowName(name)))
        assertFalse(store.exists(ShadowReplace.markerName(name)))
        assertEquals(old, store.files[name])
        assertTrue(ShadowReplace.needsRecovery(store.files.keys, name))
        store.killAfter = Int.MAX_VALUE
        assertTrue(ShadowReplace.recover(store, name))
        assertEquals(old, store.files[name])
        assertFalse(ShadowReplace.needsRecovery(store.files.keys, name))
    }

    @Test fun killWaehrendOriginalUeberschrieben_wirdAusSchattenkopieHergestellt() {
        // Schritt 3 = Original ueberschreiben, dort abgebrochen -> Original halb
        val store = FakeStore(killAfter = 2).apply { files[name] = old }
        try { ShadowReplace.replace(store, name, new) } catch (_: Killed) {}
        assertEquals(new.take(new.length / 2), store.files[name])
        store.killAfter = Int.MAX_VALUE
        ShadowReplace.recover(store, name)
        assertEquals(new, store.files[name])
    }

    @Test fun killWaehrendWiederherstellung_naechsteWiederherstellungGelingt() {
        val store = FakeStore(killAfter = 2).apply { files[name] = old }
        try { ShadowReplace.replace(store, name, new) } catch (_: Killed) {}
        // Wiederherstellung selbst wird beim Zurueckkopieren beendet
        store.steps = 0; store.killAfter = 0
        try { ShadowReplace.recover(store, name) } catch (_: Killed) {}
        store.killAfter = Int.MAX_VALUE
        ShadowReplace.recover(store, name)
        assertEquals(new, store.files[name])
        assertEquals(setOf(name), store.files.keys)
    }

    @Test fun naechstesErsetzen_raeumtAlteResteZuerstAuf() {
        val store = FakeStore(killAfter = 2).apply { files[name] = old }
        try { ShadowReplace.replace(store, name, new) } catch (_: Killed) {}
        store.killAfter = Int.MAX_VALUE
        val newer = new + "{\"id\":\"x\"}\n"
        ShadowReplace.replace(store, name, newer)
        assertEquals(newer, store.files[name])
        assertEquals(setOf(name), store.files.keys)
    }

    @Test fun ohneReste_keineAktion() {
        val store = FakeStore().apply { files[name] = old }
        assertFalse(ShadowReplace.recover(store, name))
        assertEquals(0, store.steps)
    }

    @Test fun `T240 Schattennamen erkennen und ausschliessen`() {
        assertTrue(ShadowReplace.isShadowFile("detections.jsonl.new"))
        assertTrue(ShadowReplace.isShadowFile("detections.jsonl.new.ok"))
        assertFalse(ShadowReplace.isShadowFile("detections.jsonl"))
        assertFalse(ShadowReplace.isShadowFile("recording.wav"))
        assertEquals("detections.jsonl", ShadowReplace.originalOf("detections.jsonl.new"))
        assertEquals("detections.jsonl", ShadowReplace.originalOf("detections.jsonl.new.ok"))
        assertEquals(null, ShadowReplace.originalOf("session.json"))
    }
}
