package com.nexopp.format

/**
 * Turns a user-typed page selection (the "Pages" field of the Export dialog) into the page indices
 * an exporter should walk.
 *
 * Users think in 1-based page numbers — the "3" they type is the third page, the one the page
 * counter shows. The document model indexes pages from 0, so everything downstream (Document.pages,
 * the rasteriser, the PDF and SVG exporters) wants zero-based indices. Converting once, here, keeps
 * the off-by-one in a single tested place instead of at every call site.
 *
 * Parsing is deliberately forgiving: this runs on every keystroke of a text field, so a
 * half-finished spec like `"1-"` or `"4,"` must not throw or complain — it just yields whatever is
 * meaningful so far. A spec that means nothing at all returns an empty list, and the caller decides
 * whether that disables the Export button, falls back to all pages, or shows a hint.
 */
object PageRange {

    /**
     * Parses [spec] against a document of [pageCount] pages, returning sorted, de-duplicated
     * zero-based page indices.
     *
     * Accepted syntax is a comma-separated list of tokens, each either a single page (`"5"`) or a
     * range (`"1-3"`). A range with no end (`"8-"`) runs to the last page. Surrounding whitespace is
     * trimmed anywhere it appears.
     *
     * Anything nonsensical is dropped rather than rejected: a blank spec means every page, page
     * numbers outside `1..pageCount` are clamped away, a reversed range (`"5-2"`) is read as `2-5`,
     * and a non-numeric token is skipped. This never throws.
     */
    fun parse(spec: String, pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        // A blank field is the common case — the user wants the whole document, not nothing.
        if (spec.isBlank()) return (0 until pageCount).toList()

        val pages = sortedSetOf<Int>()
        for (token in spec.split(',')) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue
            val dash = trimmed.indexOf('-')
            if (dash < 0) {
                singlePage(trimmed, pageCount)?.let(pages::add)
            } else {
                pages.addAll(rangePages(trimmed, dash, pageCount))
            }
        }
        return pages.toList()
    }

    /** A bare `"5"` token: one page, or null when it isn't a number in range. */
    private fun singlePage(token: String, pageCount: Int): Int? {
        val number = token.toIntOrNull() ?: return null
        return if (number in 1..pageCount) number - 1 else null
    }

    /**
     * A `"1-3"` / `"8-"` token, split at [dash]. An absent or unparseable end runs to the last page,
     * which is what makes a mid-typing `"8-"` behave sensibly.
     */
    private fun rangePages(token: String, dash: Int, pageCount: Int): List<Int> {
        val startText = token.substring(0, dash).trim()
        val endText = token.substring(dash + 1).trim()
        val start = startText.toIntOrNull() ?: return emptyList()
        val end = if (endText.isEmpty()) pageCount else endText.toIntOrNull() ?: return emptyList()

        // "5-2" is a typo, not an error: read it as the range the user clearly meant.
        val low = minOf(start, end).coerceAtLeast(1)
        val high = maxOf(start, end).coerceAtMost(pageCount)
        if (low > high) return emptyList()
        return (low - 1..high - 1).toList()
    }
}
