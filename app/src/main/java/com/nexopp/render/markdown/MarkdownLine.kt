package com.nexopp.render.markdown

/**
 * Line-level recognisers for [MarkdownParser]: pure, stateless answers to "what could this single
 * line start?". Kept apart from the parser so each rule can be tested on one string, and so the
 * parser itself is only the assembly logic.
 *
 * Every recogniser tolerates **up to three leading spaces**, as markdown does — four or more means
 * indented code, which is why the threshold appears here rather than being hardcoded per rule.
 */
internal object MarkdownLine {

    /** Leading spaces from which a line counts as indented code rather than as markup. */
    const val CODE_INDENT = 4

    /** A fence opener: three or more backticks or tildes, plus an optional info string. */
    data class Fence(val marker: Char, val length: Int, val info: String?, val indent: Int)

    /** A list marker: which kind, its number if ordered, and where its content starts on the line. */
    data class Marker(val ordered: Boolean, val number: Int, val contentIndent: Int)

    fun isBlank(line: String): Boolean = line.isBlank()

    /** Leading spaces, capped at [CODE_INDENT] so callers can ask "is this indented code?" cheaply. */
    fun indent(line: String): Int = line.takeWhile { it == ' ' }.length

    fun isIndentedCode(line: String): Boolean = !isBlank(line) && indent(line) >= CODE_INDENT

    /** `---`, `***`, `___`: three or more of one character, spaces allowed between them. */
    fun isRule(line: String): Boolean {
        if (indent(line) >= CODE_INDENT) return false
        val body = line.trim().filter { it != ' ' }
        if (body.length < 3) return false
        return body.all { it == '-' } || body.all { it == '*' } || body.all { it == '_' }
    }

    /** `## Heading` → level 2. Requires a space after the hashes (or nothing but hashes). */
    fun atxHeading(line: String): MarkdownBlock.Heading? {
        if (indent(line) >= CODE_INDENT) return null
        val trimmed = line.trimStart(' ')
        val hashes = trimmed.takeWhile { it == '#' }.length
        if (hashes !in 1..6) return null
        val rest = trimmed.drop(hashes)
        if (rest.isNotEmpty() && !rest.startsWith(' ')) return null
        // A closing run of hashes is decoration, not content: `## Title ##`.
        val text = rest.trim().trimEnd('#').trim()
        return MarkdownBlock.Heading(hashes, text)
    }

    /** A setext underline under a paragraph: `===` → level 1, `---` → level 2. */
    fun setextLevel(line: String): Int? {
        if (isBlank(line) || indent(line) >= CODE_INDENT) return null
        val body = line.trim()
        return when {
            body.all { it == '=' } -> 1
            body.all { it == '-' } -> 2
            else -> null
        }
    }

    fun fence(line: String): Fence? {
        val indent = indent(line)
        if (indent >= CODE_INDENT) return null
        val body = line.trimStart(' ')
        val marker = body.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val length = body.takeWhile { it == marker }.length
        if (length < 3) return null
        val info = body.drop(length).trim().ifEmpty { null }
        // A backtick fence's info string may not itself contain a backtick.
        if (marker == '`' && info?.contains('`') == true) return null
        return Fence(marker, length, info, indent)
    }

    /** Whether [line] closes an open [fence]: the same marker, at least as long, and nothing else. */
    fun closesFence(line: String, open: Fence): Boolean {
        if (indent(line) >= CODE_INDENT) return false
        val body = line.trim()
        return body.isNotEmpty() && body.all { it == open.marker } && body.length >= open.length
    }

    fun isQuote(line: String): Boolean = indent(line) < CODE_INDENT && line.trimStart(' ').startsWith('>')

    /** Drop one `>` level, plus the single optional space markdown allows after it. */
    fun stripQuote(line: String): String {
        val body = line.trimStart(' ')
        if (!body.startsWith('>')) return line
        return body.drop(1).removePrefix(" ")
    }

    /**
     * A list marker at the head of [line], or null. `-`, `*` and `+` bullet; `1.` and `1)` number.
     * A rule (`***`, `---`) wins over a bullet, so it is rejected here rather than by the caller.
     */
    fun marker(line: String): Marker? {
        val lead = indent(line)
        if (lead >= CODE_INDENT || isRule(line)) return null
        val body = line.drop(lead)
        val found = bullet(body) ?: ordered(body) ?: return null
        return found.copy(contentIndent = lead + found.contentIndent)
    }

    private fun bullet(body: String): Marker? {
        if (body.firstOrNull() !in listOf('-', '*', '+')) return null
        val after = body.drop(1)
        // `-item` is not a list; the marker needs whitespace after it (or to be an empty item).
        if (after.isNotEmpty() && !after.startsWith(' ')) return null
        return Marker(ordered = false, number = 0, contentIndent = 1 + spacesAfterMarker(after))
    }

    private fun ordered(body: String): Marker? {
        val digits = body.takeWhile { it.isDigit() }
        if (digits.isEmpty() || digits.length > 9) return null
        val rest = body.drop(digits.length)
        if (rest.firstOrNull() !in listOf('.', ')')) return null
        val after = rest.drop(1)
        if (after.isNotEmpty() && !after.startsWith(' ')) return null
        return Marker(
            ordered = true,
            number = digits.toInt(),
            contentIndent = digits.length + 1 + spacesAfterMarker(after),
        )
    }

    /**
     * How far past the marker an item's content starts. One space is the normal case; a run of
     * spaces indents the content to where it actually begins, except that a marker followed by
     * *only* spaces (an empty item) counts as one.
     */
    private fun spacesAfterMarker(after: String): Int {
        val spaces = after.takeWhile { it == ' ' }.length
        return if (spaces == 0 || spaces >= after.length || spaces > CODE_INDENT) 1 else spaces
    }
}
