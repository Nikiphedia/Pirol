package ch.etasystems.pirol.ui.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T266: Markdown-Parser der Anleitung */
class ManualMarkdownTest {

    private val sample = """
        # PIROL — Bedienungsanleitung

        Einleitung mit **fett** und `code`.
        Zweite Zeile desselben Absatzes.

        ---

        ## 1. Erster Start

        1. App oeffnen.
        2. Liste waehlen.

        ### Unterpunkt

        - Punkt A
          Fortsetzung A
        - Punkt **B**

        | Knopf | Zustand |
        |-------|---------|
        | gruen | Preroll |
        | rot | Aufnahme |

        ## 12. Upload

        Text.
    """.trimIndent()

    @Test
    fun parsesBlocksAndToc() {
        val doc = ManualMarkdown.parse(sample)
        val headings = doc.blocks.filterIsInstance<MdBlock.Heading>()
        assertEquals(listOf(1, 2, 3, 2), headings.map { it.level })
        // Inhaltsverzeichnis nur ## und ###
        assertEquals(listOf("1. Erster Start", "Unterpunkt", "12. Upload"), doc.toc.map { it.title })
        doc.toc.forEach { assertTrue(doc.blocks[it.blockIndex] is MdBlock.Heading) }

        val para = doc.blocks.filterIsInstance<MdBlock.Paragraph>().first()
        assertEquals("Einleitung mit **fett** und `code`. Zweite Zeile desselben Absatzes.", para.text)
        assertTrue(doc.blocks.contains(MdBlock.Rule))

        val items = doc.blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(4, items.size)
        assertEquals(true, items[0].ordered)
        assertEquals("1.", items[0].marker)
        assertEquals("Punkt A Fortsetzung A", items[2].text)

        val table = doc.blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("Knopf", "Zustand"), table.header)
        assertEquals(listOf(listOf("gruen", "Preroll"), listOf("rot", "Aufnahme")), table.rows)
    }

    @Test
    fun chapterLookup() {
        val doc = ManualMarkdown.parse(sample)
        val i1 = doc.chapterIndex("1")
        val i12 = doc.chapterIndex("12")
        assertNotNull(i1)
        assertEquals("1. Erster Start", (doc.blocks[i1!!] as MdBlock.Heading).text)
        assertEquals("12. Upload", (doc.blocks[i12!!] as MdBlock.Heading).text)
        assertEquals(null, doc.chapterIndex("99"))
    }

    @Test
    fun inlineSpans() {
        val spans = ManualMarkdown.inline("a **b** `c*d` *e* 3 * 4")
        assertEquals(
            listOf(
                MdSpan("a "), MdSpan("b", bold = true), MdSpan(" "), MdSpan("c*d", code = true),
                MdSpan(" "), MdSpan("e", italic = true), MdSpan(" 3 * 4")
            ),
            spans
        )
        assertEquals("Verschieben (Setup)", ManualMarkdown.plain("**Verschieben** (Setup)"))
    }

    /** Die echte Anleitung parst, hat ein Inhaltsverzeichnis und alle in Hilfetexten verlinkten Kapitel. */
    @Test
    fun realManualHasReferencedChapters() {
        val f = File("../BDA_PIROL.md")
        assertTrue(f.exists())
        val doc = ManualMarkdown.parse(f.readText())
        assertTrue(doc.toc.size > 15)
        val chapters = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("""helpTopic\(R\.string\.help_[a-z0-9_]+, "(\d+)"\)""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet() + setOf("2", "6", "8", "9", "17", "16", "13", "11", "3", "14", "7")
        chapters.forEach { assertNotNull("Kapitel $it fehlt", doc.chapterIndex(it)) }
    }
}
