package com.xopp.android.render.markdown

/**
 * The first stage of [MarkdownInlineParser]: a single left-to-right walk over inline source that
 * resolves everything decidable on the spot and defers only emphasis.
 *
 * Precedence follows CommonMark: a backslash escape beats everything, a code span beats emphasis
 * (so `` `*not em*` `` is literal), and a link label is scanned as its own little document, which is
 * why emphasis can never leak across a link boundary. `*` and `_` are emitted as unresolved
 * [InlineToken.Delimiter] runs for [InlineEmphasis] to pair up.
 */
internal class InlineScanner(private val src: String) {

    private val tokens = mutableListOf<InlineToken>()
    private val literal = StringBuilder()
    private var i = 0

    fun scan(): MutableList<InlineToken> {
        while (i < src.length) {
            when (val c = src[i]) {
                '\\' -> escape()
                '`' -> if (!codeSpan()) literal.append(c).also { i++ }
                '*', '_' -> delimiterRun(c)
                '[' -> if (!link(0)) literal.append(c).also { i++ }
                '!' -> if (!image()) literal.append(c).also { i++ }
                else -> { literal.append(c); i++ }
            }
        }
        flush()
        return tokens
    }

    /** `\*` is a literal `*`; a backslash before anything else (or at the end) is just a backslash. */
    private fun escape() {
        val next = src.getOrNull(i + 1)
        if (next != null && next in PUNCTUATION) {
            flush()
            tokens.add(InlineToken.Text(next.toString()))
            i += 2
        } else {
            literal.append('\\')
            i++
        }
    }

    /**
     * `` `code` `` — an opening run of n backticks closes on the next run of *exactly* n, which is
     * how a double-backtick span holds a backtick. Content is verbatim, with the one symmetric pad
     * space CommonMark strips. Returns false when nothing closes it, leaving the ticks literal.
     */
    private fun codeSpan(): Boolean {
        val open = runLength('`', i)
        val end = closingBackticks(i + open, open) ?: return false
        flush()
        tokens.add(InlineToken.Text(unpad(src.substring(i + open, end)), code = true))
        i = end + open
        return true
    }

    private fun closingBackticks(from: Int, want: Int): Int? {
        var j = from
        while (j < src.length) {
            if (src[j] != '`') { j++; continue }
            val len = runLength('`', j)
            if (len == want) return j
            j += len
        }
        return null
    }

    /** CommonMark's code-span padding rule: strip one space at each end, but not an all-space span. */
    private fun unpad(text: String): String =
        if (text.length >= 2 && text.first() == ' ' && text.last() == ' ' && text.isNotBlank()) {
            text.substring(1, text.length - 1)
        } else {
            text
        }

    /** Emit a run of `*` or `_`, tagged with whether it may open and/or close emphasis. */
    private fun delimiterRun(c: Char) {
        val len = runLength(c, i)
        val before = src.getOrNull(i - 1)
        val after = src.getOrNull(i + len)
        val leftFlanking = after != null && !after.isWhitespace() &&
            (after !in PUNCTUATION || before == null || before.isWhitespace() || before in PUNCTUATION)
        val rightFlanking = before != null && !before.isWhitespace() &&
            (before !in PUNCTUATION || after == null || after.isWhitespace() || after in PUNCTUATION)
        // `_` is intraword-shy so snake_case_names survive; `*` has no such restriction.
        val open = if (c == '_') leftFlanking && (!rightFlanking || before!! in PUNCTUATION) else leftFlanking
        val close = if (c == '_') rightFlanking && (!leftFlanking || after!! in PUNCTUATION) else rightFlanking
        flush()
        tokens.add(InlineToken.Delimiter(c, len, open, close))
        i += len
    }

    /** `![alt](url)` — the alt text renders exactly as a link label would. */
    private fun image(): Boolean = src.getOrNull(i + 1) == '[' && link(1)

    /**
     * `[label](url)` — emit the label's own tokens and drop the URL (see [MarkdownInlineParser] for
     * why). [lead] is 1 for an image's `!`. Returns false if this isn't a complete link, so the
     * bracket stays literal.
     */
    private fun link(lead: Int): Boolean {
        val labelEnd = matching(i + lead, '[', ']') ?: return false
        if (src.getOrNull(labelEnd + 1) != '(') return false
        val urlEnd = matching(labelEnd + 1, '(', ')') ?: return false
        flush()
        // The label is parsed to finished runs, not tokens: freezing it here is what stops a
        // leftover delimiter inside the label from pairing with one outside it.
        val label = MarkdownInlineParser.parse(src.substring(i + lead + 1, labelEnd))
        label.forEach { tokens.add(InlineToken.Text(it.text, it.bold, it.italic, it.code)) }
        i = urlEnd + 1
        return true
    }

    /** Index of the [close] matching the [open] at [from], honouring nesting and escapes. */
    private fun matching(from: Int, open: Char, close: Char): Int? {
        var depth = 0
        var j = from
        while (j < src.length) {
            when (src[j]) {
                '\\' -> j++
                open -> depth++
                close -> if (--depth == 0) return j
            }
            j++
        }
        return null
    }

    private fun runLength(c: Char, from: Int): Int {
        var j = from
        while (j < src.length && src[j] == c) j++
        return j - from
    }

    private fun flush() {
        if (literal.isNotEmpty()) {
            tokens.add(InlineToken.Text(literal.toString()))
            literal.setLength(0)
        }
    }

    companion object {
        /** The ASCII punctuation a backslash may escape — also what the flanking rules count. */
        internal const val PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
    }
}
