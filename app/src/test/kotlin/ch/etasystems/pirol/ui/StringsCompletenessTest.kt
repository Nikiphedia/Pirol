package ch.etasystems.pirol.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T247: values/strings.xml (de, Basis) und values-en/strings.xml muessen dieselben Schluessel haben
 * (ersetzt das abgeschaltete Lint MissingTranslation, T243). translatable="false" wird ausgenommen.
 */
class StringsCompletenessTest {

    private val nameRegex = Regex("""<(string|plurals|string-array)\s+([^>]*?)>""")

    private fun keys(file: File): Set<String> {
        assertTrue("Datei fehlt: ${file.absolutePath}", file.exists())
        return nameRegex.findAll(file.readText()).mapNotNull { m ->
            val attrs = m.groupValues[2]
            if (attrs.contains("translatable=\"false\"")) null
            else Regex("""name="([^"]+)"""").find(attrs)?.groupValues?.get(1)
        }.toSet()
    }

    @Test
    fun germanAndEnglishHaveSameKeys() {
        val res = File("src/main/res")
        val de = keys(File(res, "values/strings.xml"))
        val en = keys(File(res, "values-en/strings.xml"))
        val missingEn = (de - en).sorted()
        val missingDe = (en - de).sorted()
        assertTrue("Fehlt in values-en: $missingEn", missingEn.isEmpty())
        assertTrue("Fehlt in values (de): $missingDe", missingDe.isEmpty())
    }
}
