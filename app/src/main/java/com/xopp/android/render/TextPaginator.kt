package com.xopp.android.render

/**
 * Turns a plain-text file into pages of laid-out lines, ready to be typeset into a generated PDF
 * background (see the text-import path in [PdfImport]).
 *
 * Kept free of Android *and* PDFBox types: text measurement is injected as `(String) -> Float`, so
 * wrapping and pagination are pure functions and unit-testable on the JVM. The caller supplies a
 * measurer backed by whatever font it will actually draw with.
 *
 * Page size is hardcoded to A4 ([DrawingSurfaceView.A4_WIDTH_PT] / [DrawingSurfaceView.A4_HEIGHT_PT])
 * because there is no user paper-size preference yet; when one lands, pass it in via [PageSpec].
 */
object TextPaginator {

    /** Columns a tab advances to, when expanding tabs to spaces. */
    const val TAB_WIDTH = TextWrapping.TAB_WIDTH

    /** Page geometry in points: the sheet plus its margins. Defaults to A4 with 1 inch margins. */
    data class PageSpec(
        val widthPt: Double = DrawingSurfaceView.A4_WIDTH_PT,
        val heightPt: Double = DrawingSurfaceView.A4_HEIGHT_PT,
        val marginPt: Double = 72.0,
        val fontSizePt: Double = 10.0,
        /** Matches `PdfVectorPainter.LINE_HEIGHT_RATIO`, which is private to that painter. */
        val lineHeightRatio: Double = 1.2,
    ) {
        val contentWidthPt: Double get() = widthPt - 2 * marginPt
        val contentHeightPt: Double get() = heightPt - 2 * marginPt
        val lineHeightPt: Double get() = fontSizePt * lineHeightRatio

        /** How many laid-out lines fit between the top and bottom margins (at least one). */
        val linesPerPage: Int
            get() = maxOf(1, Math.floor(contentHeightPt / lineHeightPt).toInt())
    }

    /**
     * Replace tabs with spaces so measurement is well defined. Tabs advance to the next multiple of
     * [TAB_WIDTH] columns, counted from the start of the line.
     */
    fun expandTabs(line: String, tabWidth: Int = TAB_WIDTH): String =
        TextWrapping.expandTabs(line, tabWidth)

    /**
     * Wrap [text] to [maxWidth] points. Existing newlines are preserved verbatim (an empty source
     * line stays an empty output line), tabs are expanded, words are broken on whitespace, and a
     * single run too long to fit on its own line (a URL, a log line with no spaces) is hard-broken
     * at the last character that fits.
     */
    fun wrap(text: String, maxWidth: Double, measure: (String) -> Float): List<String> =
        text.split("\n").flatMap { wrapLine(expandTabs(it), maxWidth, measure) }

    private fun wrapLine(line: String, maxWidth: Double, measure: (String) -> Float): List<String> {
        if (line.isEmpty()) return listOf("")
        if (measure(line) <= maxWidth) return listOf(line)

        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (word in line.split(" ")) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
                continue
            }
            if (current.isNotEmpty()) {
                out.add(current.toString())
                current = StringBuilder()
            }
            // The word alone may still overflow — hard-break it into fitting chunks.
            val chunks = hardBreak(word, maxWidth, measure)
            out.addAll(chunks.dropLast(1))
            current = StringBuilder(chunks.last())
        }
        out.add(current.toString())
        return out
    }

    /** Split an unbreakable run into chunks that each fit [maxWidth]; always returns ≥1 chunk. */
    private fun hardBreak(word: String, maxWidth: Double, measure: (String) -> Float): List<String> =
        TextWrapping.hardBreak(word, maxWidth, maxWidth, measure)

    /** Chunk already-wrapped [lines] into pages of [spec].linesPerPage lines each. */
    fun paginate(lines: List<String>, spec: PageSpec = PageSpec()): List<List<String>> =
        if (lines.isEmpty()) listOf(emptyList()) else lines.chunked(spec.linesPerPage)

    /** Wrap then paginate [text] in one step — the entry point callers normally want. */
    fun layout(text: String, spec: PageSpec = PageSpec(), measure: (String) -> Float): List<List<String>> =
        paginate(wrap(text, spec.contentWidthPt, measure), spec)

    /**
     * Baseline Y (points, from the page top) for the line at [index] on a page: the first baseline
     * sits one line-height below the top margin, each following line advances by one line height.
     */
    fun baselineFromTop(index: Int, spec: PageSpec = PageSpec()): Double =
        spec.marginPt + (index + 1) * spec.lineHeightPt
}
