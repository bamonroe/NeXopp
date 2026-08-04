package com.xopp.android.render

/**
 * The character-level pieces of line breaking, shared by the plain-text path ([TextPaginator]) and
 * the styled markdown path ([com.xopp.android.render.markdown.StyledWrapper]).
 *
 * Both wrappers greedily fill a line word by word and fall back to breaking mid-word when a single
 * unbreakable run (a URL, a long log line) cannot fit on its own; only that fallback and the tab
 * expansion are genuinely common, so only they live here. Measurement stays injected as
 * `(String) -> Float` so everything is pure and testable on the JVM.
 */
internal object TextWrapping {

    /** Columns a tab advances to, when expanding tabs to spaces. */
    const val TAB_WIDTH = 4

    /**
     * Replace tabs with spaces so measurement is well defined. Tabs advance to the next multiple of
     * [tabWidth] columns, counted from the start of the line.
     */
    fun expandTabs(line: String, tabWidth: Int = TAB_WIDTH): String {
        if ('\t' !in line) return line
        val out = StringBuilder()
        for (ch in line) {
            if (ch == '\t') {
                val pad = tabWidth - (out.length % tabWidth)
                repeat(pad) { out.append(' ') }
            } else {
                out.append(ch)
            }
        }
        return out.toString()
    }

    /**
     * Split an unbreakable [word] into chunks that each fit: the first into [firstWidth] points (the
     * space left on the line already in progress), every later one into [restWidth] (a fresh line).
     * Always returns at least one chunk, and a chunk may be empty only when nothing at all fits in
     * [firstWidth] — the caller flushes the current line and retries.
     */
    fun hardBreak(
        word: String,
        firstWidth: Double,
        restWidth: Double,
        measure: (String) -> Float,
    ): List<String> {
        if (measure(word) <= firstWidth) return listOf(word)
        val out = mutableListOf<String>()
        var current = StringBuilder()
        var available = firstWidth
        for (ch in word) {
            if (measure("$current$ch") > available && current.isNotEmpty()) {
                out.add(current.toString())
                current = StringBuilder()
                available = restWidth
            }
            current.append(ch)
        }
        out.add(current.toString())
        return out
    }
}
