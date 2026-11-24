package ch.etasystems.pirol.ui.help

/**
 * T266: Schlanker Markdown-Parser fuer die Bedienungsanleitung (keine Bibliothek noetig).
 * Unterstuetzt: Ueberschriften (#..####), Absaetze, Listen (-, *, 1.), einfache Tabellen (|),
 * Trennlinien (---), Zitate (>) als Absatz; inline **fett**, *kursiv*, `code`.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(val ordered: Boolean, val marker: String, val text: String, val indent: Int) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

data class TocEntry(val level: Int, val title: String, val blockIndex: Int)

data class ManualDoc(val blocks: List<MdBlock>, val toc: List<TocEntry>) {
    /** Index der Ueberschrift, die mit "<kapitel>." beginnt (z.B. "6" -> "## 6. Analyse-Tab"), sonst null */
    fun chapterIndex(chapter: String): Int? =
        toc.firstOrNull { it.title.startsWith("$chapter.") || it.title.startsWith("$chapter ") }?.blockIndex
}

/** Inline-Abschnitt mit Formatierung */
data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

object ManualMarkdown {

    private val headingRe = Regex("""^(#{1,6})\s+(.*)$""")
    private val bulletRe = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val orderedRe = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
    private val ruleRe = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val tableSepRe = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun parse(source: String): ManualDoc {
        val lines = source.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<MdBlock>()
        val para = StringBuilder()

        fun flushPara() {
            if (para.isNotBlank()) blocks += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flushPara()
                headingRe.matches(trimmed) -> {
                    flushPara()
                    val m = headingRe.find(trimmed)!!
                    blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim().trimEnd('#').trim())
                }
                ruleRe.matches(trimmed) -> { flushPara(); blocks += MdBlock.Rule }
                trimmed.startsWith("|") && i + 1 < lines.size && tableSepRe.matches(lines[i + 1]) -> {
                    flushPara()
                    val header = splitRow(trimmed)
                    val rows = mutableListOf<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        rows += splitRow(lines[i].trim())
                        i++
                    }
                    blocks += MdBlock.Table(header, rows)
                    continue
                }
                bulletRe.matches(line) -> {
                    flushPara()
                    val m = bulletRe.find(line)!!
                    blocks += MdBlock.ListItem(false, "•", m.groupValues[3].trim(), m.groupValues[1].length / 2)
                }
                orderedRe.matches(line) -> {
                    flushPara()
                    val m = orderedRe.find(line)!!
                    blocks += MdBlock.ListItem(true, m.groupValues[2] + ".", m.groupValues[3].trim(), m.groupValues[1].length / 2)
                }
                trimmed.startsWith(">") -> {
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(trimmed.removePrefix(">").trim())
                }
                else -> {
                    // Fortsetzungszeile eines Listenpunkts (eingerueckt, direkt danach)
                    val last = blocks.lastOrNull()
                    if (para.isEmpty() && last is MdBlock.ListItem && line.startsWith(" ") &&
                        i > 0 && lines[i - 1].isNotBlank()
                    ) {
                        blocks[blocks.lastIndex] = last.copy(text = last.text + " " + trimmed)
                    } else {
                        if (para.isNotEmpty()) para.append(' ')
                        para.append(trimmed)
                    }
                }
            }
            i++
        }
        flushPara()

        val toc = blocks.mapIndexedNotNull { idx, b ->
            if (b is MdBlock.Heading && b.level in 2..3) TocEntry(b.level, plain(b.text), idx) else null
        }
        return ManualDoc(blocks, toc)
    }

    private fun splitRow(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** Inline-Formatierung in Abschnitte zerlegen */
    fun inline(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        fun flush() {
            if (buf.isNotEmpty()) out += MdSpan(buf.toString(), bold = bold, italic = italic)
            buf.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        out += MdSpan(text.substring(i + 1, end), bold = bold, italic = italic, code = true)
                        i = end + 1
                        continue
                    }
                    buf.append(c)
                }
                c == '*' && text.startsWith("**", i) -> { flush(); bold = !bold; i += 2; continue }
                c == '*' && (italic || (i + 1 < text.length && !text[i + 1].isWhitespace())) &&
                    (italic || text.indexOf('*', i + 1) > i) -> { flush(); italic = !italic }
                else -> buf.append(c)
            }
            i++
        }
        flush()
        return out
    }

    /** Text ohne Formatierungszeichen (fuer Inhaltsverzeichnis) */
    fun plain(text: String): String = inline(text).joinToString("") { it.text }
}
