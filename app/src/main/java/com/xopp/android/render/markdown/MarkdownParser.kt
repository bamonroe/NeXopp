package com.xopp.android.render.markdown

import com.xopp.android.render.TextPaginator

/**
 * Turns markdown source into the block tree of [MarkdownBlock]. A pure function of a string — no
 * Android, no PDFBox, no dependencies — and a deliberate sibling of [TextPaginator]: this pass
 * decides *structure*, the layout pass decides geometry, and neither knows about the other.
 *
 * It is a **line-based recursive descent**. The source is normalised into lines once, then a cursor
 * walks them: each step recognises what the current line starts (see [MarkdownLine]) and consumes
 * the whole run belonging to it. Containers — block quotes and list items — collect their own lines,
 * strip the container marker, and **recurse on the result**, which is what makes a quote containing
 * a list, or a list item containing a code block, work without a special case for either.
 *
 * The dialect is the common core of CommonMark, not all of it: ATX and setext headings, paragraphs
 * with lazy continuation, fenced and indented code, ordered and unordered lists with nesting, block
 * quotes, and thematic breaks. Reference links, tables, HTML blocks and footnotes are out of scope —
 * their source survives verbatim inside a paragraph rather than being mangled.
 *
 * Inline markup is untouched; [MarkdownBlock] explains why that is a separate pass.
 */
object MarkdownParser {

    /** Parse [source] into top-level blocks. Empty or blank input yields an empty list. */
    fun parse(source: String): List<MarkdownBlock> = parseLines(normalise(source))

    /**
     * Split into lines the rest of the parser can trust: CRLF and lone CR both become LF, tabs
     * expand to spaces (so indentation is countable), and a trailing newline does not invent a
     * final empty line.
     */
    internal fun normalise(source: String): List<String> =
        source.replace("\r\n", "\n").replace('\r', '\n')
            .split("\n")
            .map { TextPaginator.expandTabs(it) }
            .dropLastWhile { it.isBlank() }

    private fun parseLines(lines: List<String>): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                MarkdownLine.isBlank(line) -> i++
                MarkdownLine.fence(line) != null -> i = fencedCode(lines, i, blocks)
                MarkdownLine.isRule(line) -> { blocks.add(MarkdownBlock.Rule); i++ }
                MarkdownLine.atxHeading(line) != null -> { blocks.add(MarkdownLine.atxHeading(line)!!); i++ }
                MarkdownLine.isQuote(line) -> i = quote(lines, i, blocks)
                MarkdownLine.marker(line) != null -> i = list(lines, i, blocks)
                MarkdownLine.isIndentedCode(line) -> i = indentedCode(lines, i, blocks)
                else -> i = paragraph(lines, i, blocks)
            }
        }
        return blocks
    }

    /** ``` … ``` — everything between the fences is verbatim, and an unclosed fence runs to the end. */
    private fun fencedCode(lines: List<String>, start: Int, out: MutableList<MarkdownBlock>): Int {
        val open = MarkdownLine.fence(lines[start])!!
        val body = mutableListOf<String>()
        var i = start + 1
        while (i < lines.size && !MarkdownLine.closesFence(lines[i], open)) {
            // The opener's own indentation is stripped from each line, as markdown specifies.
            body.add(lines[i].drop(minOf(open.indent, MarkdownLine.indent(lines[i]))))
            i++
        }
        out.add(MarkdownBlock.CodeBlock(body.joinToString("\n"), open.info, fenced = true))
        return if (i < lines.size) i + 1 else i
    }

    /**
     * A run of four-space-indented lines. Interior blank lines belong to the block, trailing ones
     * do not — otherwise every indented block would end with a stray empty line.
     */
    private fun indentedCode(lines: List<String>, start: Int, out: MutableList<MarkdownBlock>): Int {
        val body = mutableListOf<String>()
        var i = start
        while (i < lines.size && (MarkdownLine.isIndentedCode(lines[i]) || MarkdownLine.isBlank(lines[i]))) {
            body.add(lines[i].drop(MarkdownLine.CODE_INDENT))
            i++
        }
        val trimmed = body.dropLastWhile { it.isBlank() }
        out.add(MarkdownBlock.CodeBlock(trimmed.joinToString("\n"), language = null, fenced = false))
        return start + trimmed.size
    }

    /**
     * A block quote, including **lazy continuation**: a plain text line directly under a quoted line
     * continues that quote's paragraph, so it is swallowed too. A blank line ends the quote.
     */
    private fun quote(lines: List<String>, start: Int, out: MutableList<MarkdownBlock>): Int {
        val body = mutableListOf<String>()
        var i = start
        while (i < lines.size && !MarkdownLine.isBlank(lines[i])) {
            val quoted = MarkdownLine.isQuote(lines[i])
            if (!quoted && !lazyContinues(lines[i])) break
            body.add(if (quoted) MarkdownLine.stripQuote(lines[i]) else lines[i])
            i++
        }
        out.add(MarkdownBlock.Quote(parseLines(body)))
        return i
    }

    /** Whether a non-quoted line may lazily continue an open paragraph rather than start a block. */
    private fun lazyContinues(line: String): Boolean =
        MarkdownLine.fence(line) == null &&
            !MarkdownLine.isRule(line) &&
            MarkdownLine.atxHeading(line) == null &&
            MarkdownLine.marker(line) == null &&
            !MarkdownLine.isIndentedCode(line)

    /** A paragraph: text lines up to a blank line, another block's opener, or a setext underline. */
    private fun paragraph(lines: List<String>, start: Int, out: MutableList<MarkdownBlock>): Int {
        val body = mutableListOf(lines[start].trim())
        var i = start + 1
        while (i < lines.size && !MarkdownLine.isBlank(lines[i])) {
            MarkdownLine.setextLevel(lines[i])?.let { level ->
                out.add(MarkdownBlock.Heading(level, body.joinToString("\n")))
                return i + 1
            }
            if (!lazyContinues(lines[i]) || MarkdownLine.isQuote(lines[i])) break
            body.add(lines[i].trim())
            i++
        }
        out.add(MarkdownBlock.Paragraph(body.joinToString("\n")))
        return i
    }

    /**
     * A list: consecutive items of the same kind. Each item's own lines — its first line past the
     * marker, plus any following lines indented to the marker's content column — are dedented and
     * parsed as a document of their own, so items nest lists, hold code blocks, and hold quotes.
     */
    private fun list(lines: List<String>, start: Int, out: MutableList<MarkdownBlock>): Int {
        val first = MarkdownLine.marker(lines[start])!!
        val items = mutableListOf<ListItem>()
        var i = start
        while (i < lines.size) {
            // A blank line between items separates them without ending the list ("loose" list).
            val at = lines.drop(i).indexOfFirst { !MarkdownLine.isBlank(it) }.takeIf { it >= 0 }?.plus(i) ?: break
            val marker = MarkdownLine.marker(lines[at])?.takeIf { it.ordered == first.ordered } ?: break
            val (body, next) = itemLines(lines, at, marker.contentIndent)
            items.add(ListItem(parseLines(body)))
            i = next
        }
        out.add(MarkdownBlock.ListBlock(first.ordered, items, start = if (first.ordered) first.number else 1))
        return i
    }

    /** One item's dedented content lines, and the index of the first line after it. */
    private fun itemLines(lines: List<String>, start: Int, contentIndent: Int): Pair<List<String>, Int> {
        val body = mutableListOf(lines[start].drop(contentIndent))
        var i = start + 1
        while (i < lines.size) {
            val line = lines[i]
            if (MarkdownLine.isBlank(line)) {
                // A blank line only stays in the item if indented content follows it.
                val continues = lines.drop(i + 1).firstOrNull()?.let { MarkdownLine.indent(it) >= contentIndent }
                if (continues != true) break
                body.add("")
                i++
                continue
            }
            if (MarkdownLine.indent(line) < contentIndent) {
                // Not indented to the content column: either the next item, or the list is over.
                if (MarkdownLine.marker(line) != null || !lazyContinues(line)) break
                body.add(line.trim()) // lazy continuation of the item's paragraph
            } else {
                body.add(line.drop(contentIndent))
            }
            i++
        }
        return body.dropLastWhile { it.isBlank() } to i
    }
}
