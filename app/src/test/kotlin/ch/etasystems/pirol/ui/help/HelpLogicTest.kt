package ch.etasystems.pirol.ui.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T266: Doppeltipp/Umschalten, Sprachwahl der Anleitung, Abfang-Entscheidung, Hilfetexte */
class HelpLogicTest {

    @Test
    fun singleTapTogglesImmediately() {
        val d = HelpTapDecider()
        assertEquals(HelpTapDecider.Result(active = true, openManual = false), d.onTap(1_000, activeNow = false))
        // naechster Tipp ausserhalb des Fensters: wieder einfaches Umschalten
        assertEquals(HelpTapDecider.Result(active = false, openManual = false), d.onTap(2_000, activeNow = true))
    }

    @Test
    fun doubleTapRestoresStateAndOpensManual() {
        val d = HelpTapDecider()
        val first = d.onTap(1_000, activeNow = false)
        assertTrue(first.active)
        val second = d.onTap(1_250, activeNow = first.active)
        assertEquals(HelpTapDecider.Result(active = false, openManual = true), second)
        // dritter Tipp kurz danach ist wieder ein neuer erster Tipp
        assertEquals(HelpTapDecider.Result(active = true, openManual = false), d.onTap(1_400, activeNow = false))
    }

    @Test
    fun doubleTapWhileActiveKeepsHelpModeOn() {
        val d = HelpTapDecider()
        val first = d.onTap(5_000, activeNow = true)
        assertFalse(first.active)
        assertEquals(HelpTapDecider.Result(active = true, openManual = true), d.onTap(5_300, activeNow = false))
    }

    @Test
    fun tapJustOutsideWindowIsNoDoubleTap() {
        val d = HelpTapDecider(windowMs = 300)
        d.onTap(0, false)
        assertFalse(d.onTap(301, true).openManual)
    }

    @Test
    fun manualLanguageSelection() {
        val all = setOf("BDA_PIROL.md", "BDA_PIROL.en.md", "BDA_PIROL.gsw-CH.md")
        assertEquals("BDA_PIROL.md", ManualLanguage.select("de", "en", all))
        assertEquals("BDA_PIROL.en.md", ManualLanguage.select("en", "de", all))
        assertEquals("BDA_PIROL.gsw-CH.md", ManualLanguage.select("gsw-CH", "de", all))
        // Systemsprache, wenn keine App-Sprache gesetzt ist
        assertEquals("BDA_PIROL.en.md", ManualLanguage.select("", "en", all))
        // Basissprache aus regionalem Tag
        assertEquals("BDA_PIROL.en.md", ManualLanguage.select("en-GB", "de", all))
        // Rueckfall Deutsch
        assertEquals("BDA_PIROL.md", ManualLanguage.select("fr", "fr", all))
        assertEquals("BDA_PIROL.md", ManualLanguage.select("", "", setOf("BDA_PIROL.md")))
        assertEquals("BDA_PIROL.md", ManualLanguage.select("en", "en", emptySet()))
    }

    @Test
    fun interceptOnlyInHelpMode() {
        assertTrue(HelpIntercept.shouldIntercept(helpModeActive = true, hasTopic = true))
        assertFalse(HelpIntercept.shouldIntercept(helpModeActive = false, hasTopic = true))
        assertFalse(HelpIntercept.shouldIntercept(helpModeActive = true, hasTopic = false))
    }

    /** Jeder im Code verwendete help_*-Schluessel existiert in de und en und ist kurz (max. 3 Saetze). */
    @Test
    fun usedHelpKeysExistAndAreShort() {
        val src = File("src/main/kotlin")
        val used = src.walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("""R\.string\.(help_[a-z0-9_]+)""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
        assertTrue("zu wenige Hilfetexte: ${used.size}", used.size > 50)
        for (lang in listOf("values", "values-en")) {
            val xml = File("src/main/res/$lang/strings.xml").readText()
            val texts = Regex("""<string name="(help_[a-z0-9_]+)">(.*?)</string>""").findAll(xml)
                .associate { it.groupValues[1] to it.groupValues[2] }
            val missing = used - texts.keys
            assertTrue("$lang fehlt: $missing", missing.isEmpty())
            texts.forEach { (k, v) ->
                val sentences = v.split(Regex("""[.!?](\s|$)""")).count { it.isNotBlank() }
                assertTrue("$lang/$k hat $sentences Saetze", sentences <= 3)
            }
        }
    }
}
