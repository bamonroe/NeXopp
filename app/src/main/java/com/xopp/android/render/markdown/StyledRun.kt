package com.xopp.android.render.markdown

/**
 * A stretch of text carrying one combination of inline styles: the unit markdown layout measures and
 * wraps. [MarkdownInlineParser] produces these from a block's raw inline source, and a run is
 * deliberately *flat* — nested `**bold *and* italic**` becomes adjacent runs with different flags
 * rather than a tree, because wrapping only ever needs to know which font to measure the next word
 * in.
 *
 * [code] runs are literal: their text is whatever sat between the backticks, with no further markup
 * decoded, and layout is expected to draw them in a monospaced face.
 */
data class StyledRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
) {
    /** True when this run carries no text at all — such runs are dropped rather than emitted. */
    val isEmpty: Boolean get() = text.isEmpty()
}
