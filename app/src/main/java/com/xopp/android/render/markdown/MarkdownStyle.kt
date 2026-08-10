package com.xopp.android.render.markdown

import com.xopp.android.render.DrawingSurfaceDefaults

/**
 * Every size markdown layout uses, in one place — the markdown-flavoured sibling of
 * [com.xopp.android.render.TextPaginator.PageSpec]. Page geometry is the same idea (sheet plus
 * margins, A4 by default because there is no paper-size preference yet); on top of it sit the block
 * sizes markdown needs: per-heading-level type, the gaps between blocks, and the indent steps for
 * lists, quotes and code.
 *
 * Points throughout. Nothing here is Android- or PDFBox-aware, so layout stays unit-testable.
 */
data class MarkdownStyle(
    /** Page width in points (default A4). */
    val widthPt: Double = DrawingSurfaceDefaults.A4_WIDTH_PT,
    /** Page height in points (default A4). */
    val heightPt: Double = DrawingSurfaceDefaults.A4_HEIGHT_PT,
    /** Page margin on all sides in points. */
    val marginPt: Double = 72.0,
    /** Body text font size in points. */
    val bodyFontSizePt: Double = 10.0,
    /** Line height as a multiple of font size. */
    val lineHeightRatio: Double = 1.2,
    /** Type size for headings 1–6, largest first. Headings are also drawn bold. */
    val headingSizesPt: List<Double> = listOf(20.0, 17.0, 14.5, 12.5, 11.0, 10.0),
    /** Gap above a heading — bigger for a higher level, so sections read as sections. */
    val headingSpaceBeforeRatio: Double = 0.8,
    /** Gap below a heading, as a fraction of the heading's own size. */
    val headingSpaceAfterRatio: Double = 0.35,
    /** Gap between two ordinary blocks (paragraph, list, quote, code), in body line heights. */
    val blockSpacingRatio: Double = 0.6,
    /** How far one level of list nesting indents; also the space the marker is drawn in. */
    val listIndentPt: Double = 18.0,
    /** How far a block quote's contents indent from their surroundings. */
    val quoteIndentPt: Double = 18.0,
    /** How far a code block's contents indent from their surroundings. */
    val codeIndentPt: Double = 12.0,
    /** Code is set monospaced at its own fixed size, never scaled to the surrounding block. */
    val codeFontSizePt: Double = 9.0,
    /** Total vertical space a horizontal rule occupies, including the air around it. */
    val ruleHeightPt: Double = 10.0,
    /** Stroke width of a horizontal rule. */
    val ruleThicknessPt: Double = 0.75,
) {
    /** Usable content width in points (widthPt - 2 * marginPt). */
    val contentWidthPt: Double get() = widthPt - 2 * marginPt
    /** Usable content height in points (heightPt - 2 * marginPt). */
    val contentHeightPt: Double get() = heightPt - 2 * marginPt
    /** Body text line height in points (bodyFontSizePt * lineHeightRatio). */
    val bodyLineHeightPt: Double get() = bodyFontSizePt * lineHeightRatio

    /**
     * Type size for a heading of [level] (1–6), clamped to the ends of [headingSizesPt].
     * @param level Heading level (1 = largest, 6 = smallest).
     * @return Font size in points for the given heading level.
     */
    fun headingSizePt(level: Int): Double =
        headingSizesPt[level.coerceIn(1, headingSizesPt.size) - 1]

    /**
     * The line height text of [fontSizePt] is set on.
     * @param fontSizePt Font size in points.
     * @return Line height in points.
     */
    fun lineHeightPt(fontSizePt: Double): Double = fontSizePt * lineHeightRatio
}
