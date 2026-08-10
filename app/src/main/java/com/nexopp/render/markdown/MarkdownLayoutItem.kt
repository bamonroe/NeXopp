package com.nexopp.render.markdown

/** Measures [text] in the face named by [style] at [fontSizePt], in points. Injected, so layout is pure. */
typealias SizedMeasurer = (style: RunStyle, fontSizePt: Double, text: String) -> Float

/**
 * One drawable piece of a markdown page, before pagination has decided where it sits vertically.
 * [heightPt] is all pagination needs to know: everything advances the pen by its own height.
 */
sealed interface MarkdownItem {
    val heightPt: Double

    /**
     * A wrapped line of styled text. [fragments] carry x offsets relative to [indentPt], so the
     * emitter draws each at `margin + indentPt + fragment.xPt`. [marker] is a list bullet or number
     * drawn once, at [markerXPt] relative to the same indent (negative: markers hang left).
     */
    data class Line(
        val fragments: List<RunFragment>,
        val indentPt: Double,
        val fontSizePt: Double,
        override val heightPt: Double,
        val marker: String? = null,
        val markerXPt: Double = 0.0,
        val markerStyle: RunStyle = RunStyle.REGULAR,
    ) : MarkdownItem

    /** A thematic break, drawn as a hairline across the content width from [indentPt]. */
    data class Rule(
        val indentPt: Double,
        val widthPt: Double,
        val thicknessPt: Double,
        override val heightPt: Double,
    ) : MarkdownItem

    /** Blank vertical space between blocks. Collapsed away at the top of a page. */
    data class Space(override val heightPt: Double) : MarkdownItem
}

/**
 * An item placed on a page. [yPt] is measured down from the page top: for a [MarkdownItem.Line] it
 * is the text baseline, for a [MarkdownItem.Rule] the centre of the stroke.
 */
data class PlacedItem(val item: MarkdownItem, val yPt: Double)

/** One laid-out page: its items in reading order. */
data class MarkdownPage(val items: List<PlacedItem>)

/**
 * The items of a single block, kept together for page-breaking decisions. [keepWithNext] marks a
 * block that must not be orphaned at the foot of a page — a heading, which needs at least the first
 * line of whatever follows it to stay on the same page.
 */
data class MarkdownGroup(val items: List<MarkdownItem>, val keepWithNext: Boolean = false) {
    val heightPt: Double get() = items.sumOf { it.heightPt }
}
