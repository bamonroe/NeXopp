package com.xopp.android.render

/**
 * Pure text-layout helpers for a `<text>` box. Kept free of Android types so the line/baseline
 * geometry is unit-testable; [ElementRenderer] supplies the font metrics from a real `Paint`.
 */
object TextBlock {

    /** Split content into the lines that are drawn — newline-delimited, preserving empty lines. */
    fun lines(content: String): List<String> = content.split("\n")

    /**
     * Baseline Y (px) for each of [lineCount] lines. The box top is [topPx]; [ascentPx] is the
     * font's ascent (negative, as Android reports it) so the first baseline sits one ascent below
     * the top, and each subsequent line advances by [lineHeightPx].
     */
    fun baselines(lineCount: Int, topPx: Float, ascentPx: Float, lineHeightPx: Float): List<Float> =
        (0 until lineCount).map { topPx - ascentPx + it * lineHeightPx }
}
