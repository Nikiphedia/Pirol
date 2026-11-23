package ch.etasystems.pirol.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * T249: Raender der Schattenkopie (Bericht Fable 2026-11-23).
 *  - H2: Lesen schreibt nie; ein Leser mitten in einem Ersetzen liefert eine vollstaendige Fassung
 *    und kann eine danach angehaengte Zeile nicht mehr abschneiden.
 *  - M7: Wiederherstellung bricht ab, wenn die Schattenkopie nicht lesbar ist (nichts loeschen).
 *  - M3b: Provider, der den Schattennamen nicht anlegen kann -> direkter Rewrite statt Dauerfehler.
 */
class ShadowEdgeTest {

    private val name = "detections.jsonl"
    private val shadow = ShadowReplace.shadowName(name)
    private val marker = ShadowReplace.markerName(name)
    private val old = "{\"id\":\"a\",\"count\":1}\n{\"id\":\"b\",\"count\":1}\n"
    private val new = "{\"id\":\"a\",\"count\":5}\n{\"id\":\"b\",\"count\":1}\n"

    /** Speicher im RAM; zaehlt veraendernde Zugriffe und erlaubt Haken/Fehler je Datei. */
    private open class Store : ShadowReplace.Store {
        val files = LinkedHashMap<String, String>()
        var mutations = 0
        var failReadOnce: String? = null
        var unsupported: Set<String> = emptySet()
        var onWrite: ((String) -> Unit)? = null
        override fun exists(name: String) = name in files
        override fun read(name: String): String? {
            if (failReadOnce == name) { failReadOnce = null; return null }
            return files[name]
        }
        override fun write(name: String, content: String) {
            if (name in unsupported) throw ShadowReplace.UnsupportedNameException(name)
            mutations++
            files[name] = content
            onWrite?.invoke(name)
        }
        override fun create(name: String) {
            if (name in unsupported) throw ShadowReplace.UnsupportedNameException(name)
            mutations++
            files[name] = ""
        }
        override fun delete(name: String) { mutations++; files.remove(name) }
        fun append(name: String, line: String) { files[name] = (files[name] ?: "") + line }
    }

    // ── H2: Lesen ohne Schreiben ──────────────────────────────────────

    @Test fun lesen_ohneReste_liefertOriginal_ohneSchreiben() {
        val s = Store().apply { files[name] = old }
        assertEquals(old, ShadowReplace.read(s, name))
        assertEquals(0, s.mutations)
    }

    @Test fun lesen_mitMarke_liefertSchattenkopie_ohneSchreiben() {
        // Zustand nach Kill beim Ueberschreiben des Originals: Original halb, Kopie + Marke vollstaendig
        val s = Store().apply { files[name] = new.take(10); files[shadow] = new; files[marker] = "" }
        assertEquals(new, ShadowReplace.read(s, name))
        assertEquals(0, s.mutations)
        assertTrue("Reste bleiben fuer die Wiederherstellung unter Sperre", s.exists(marker) && s.exists(shadow))
    }

    @Test fun lesen_nurSchattenkopie_ohneMarke_liefertOriginal() {
        val s = Store().apply { files[name] = old; files[shadow] = new.take(7) }
        assertEquals(old, ShadowReplace.read(s, name))
        assertEquals(0, s.mutations)
    }

    @Test fun lesen_schattenkopieVerschwindetWaehrendDesLesens_liefertOriginal() {
        // Schreiber ist fertig geworden (Marke und Kopie geloescht), bevor der Leser die Kopie oeffnet
        val s = object : Store() {
            override fun read(name: String): String? {
                if (name == shadow) { files.remove(marker); files.remove(shadow); return null }
                return files[name]
            }
        }.apply { files[name] = new; files[shadow] = new; files[marker] = "" }
        assertEquals(new, ShadowReplace.read(s, name))
    }

    @Test fun lesen_schattenkopieUnlesbar_wirft() {
        val s = Store().apply { files[name] = new.take(10); files[shadow] = new; files[marker] = ""; failReadOnce = shadow }
        try {
            ShadowReplace.read(s, name)
            fail("abgeschnittenes Original darf nicht als gueltig gelten")
        } catch (_: IOException) { }
        assertEquals(0, s.mutations)
    }

    @Test fun race_leserWaehrendErsetzen_neueZeileBleibt() {
        // Szenario H2: Leser kommt, waehrend der Flush das Original ueberschreibt. Danach wird eine Zeile
        // angehaengt. Frueher stellte der Leser (ohne Sperre) die Kopie wieder her und schnitt sie ab.
        val s = Store().apply { files[name] = old }
        var seen: String? = null
        s.onWrite = { written -> if (written == name && seen == null) seen = ShadowReplace.read(s, name) }
        ShadowReplace.replace(s, name, new)
        s.append(name, "{\"id\":\"c\",\"count\":1}\n")
        assertEquals("Leser sieht eine vollstaendige Fassung", new, seen)
        assertEquals(new + "{\"id\":\"c\",\"count\":1}\n", s.files[name])
        assertEquals(setOf(name), s.files.keys)
    }

    // ── M7: Wiederherstellung bei Lesefehler ─────────────────────────

    @Test fun recover_schattenkopieLiefertNull_brichtAb_loeschtNichts() {
        val half = new.take(10)
        val s = Store().apply { files[name] = half; files[shadow] = new; files[marker] = ""; failReadOnce = shadow }
        try {
            ShadowReplace.recover(s, name)
            fail("muss abbrechen")
        } catch (_: IOException) { }
        assertEquals("Original unberuehrt", half, s.files[name])
        assertTrue(s.exists(shadow) && s.exists(marker))
        // Naechster Versuch stellt her
        assertTrue(ShadowReplace.recover(s, name))
        assertEquals(new, s.files[name])
        assertEquals(setOf(name), s.files.keys)
    }

    @Test fun replace_wennWiederherstellungScheitert_schreibtNichts() {
        val s = Store().apply { files[name] = new.take(10); files[shadow] = new; files[marker] = ""; failReadOnce = shadow }
        try {
            ShadowReplace.replace(s, name, "x\n")
            fail("muss abbrechen")
        } catch (_: IOException) { }
        assertEquals(new.take(10), s.files[name])
        assertEquals(0, s.mutations)
    }

    // ── M3b: Rueckfall bei Providern ohne Schattennamen ─────────────────

    @Test fun replace_providerKannSchattennamenNichtAnlegen_direkterRewrite() {
        val s = Store().apply { files[name] = old; unsupported = setOf(shadow) }
        ShadowReplace.replace(s, name, new)
        assertEquals(new, s.files[name])
        assertEquals(setOf(name), s.files.keys)
    }

    @Test fun replace_providerKannMarkeNichtAnlegen_direkterRewrite_keineReste() {
        val s = Store().apply { files[name] = old; unsupported = setOf(marker) }
        ShadowReplace.replace(s, name, new)
        assertEquals(new, s.files[name])
        assertEquals(setOf(name), s.files.keys)
    }

    @Test fun replace_ioFehlerInSchattenkopie_keinRueckfall_originalIntakt() {
        // Voller Speicher o.ae.: kein direkter "wt"-Rewrite (der koennte das Original kuerzen)
        val s = object : Store() {
            override fun write(name: String, content: String) {
                if (name == shadow) throw IOException("voll")
                super.write(name, content)
            }
        }.apply { files[name] = old }
        try {
            ShadowReplace.replace(s, name, new)
            fail("Fehler muss beim Aufrufer ankommen (Warteschlange bleibt)")
        } catch (_: IOException) { }
        assertEquals(old, s.files[name])
        assertFalse(s.exists(marker))
    }
}
