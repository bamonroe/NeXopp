package com.xopp.android.render.markdown

/**
 * Lays a markdown document out into pages: parse → compose ([MarkdownComposer]) → break into pages.
 * The entry point for the markdown typesetting flavour, and the counterpart of
 * [com.xopp.android.render.TextPaginator] on the plain-text path.
 *
 * Page breaking works on whole blocks, which is why composition hands back
 * [MarkdownGroup]s rather than a flat line list:
 *
 * - blank space between blocks **collapses at a page top**, so a page never opens with a gap;
 * - a block longer than a page simply flows onto the next one, line by line;
 * - a block marked `keepWithNext` (a heading) moves to the next page rather than sit alone at the
 *   foot, taking the first line of what follows it as the minimum it must be joined by.
 *
 * Pure Kotlin: measurement is injected as a [SizedMeasurer], so the whole path is unit-testable.
 */
object MarkdownLayout {

    /** Parse [source] and lay it out. Always returns at least one (possibly empty) page. */
    fun layout(source: String, style: MarkdownStyle = MarkdownStyle(), measure: SizedMeasurer): List<MarkdownPage> =
        paginate(MarkdownComposer(style, measure).compose(MarkdownParser.parse(source)), style)

    /** Break composed [groups] into pages of [style]'s content height. */
    fun paginate(groups: List<MarkdownGroup>, style: MarkdownStyle = MarkdownStyle()): List<MarkdownPage> {
        val pager = Pager(style)
        groups.forEachIndexed { index, group ->
            pager.place(group, nextItemHeight(groups, index))
        }
        return pager.finish()
    }

    /** Height of the first drawable item after group [index] — what a `keepWithNext` group needs. */
    private fun nextItemHeight(groups: List<MarkdownGroup>, index: Int): Double =
        groups.getOrNull(index + 1)
            ?.items?.firstOrNull { it !is MarkdownItem.Space }
            ?.heightPt ?: 0.0

    /** Fills pages top to bottom, tracking the pen's Y offset from the top margin. */
    private class Pager(val style: MarkdownStyle) {
        private val pages = mutableListOf<MarkdownPage>()
        private val current = mutableListOf<PlacedItem>()

        /** Distance from the top margin to the pen, in points. */
        private var yPt = 0.0

        private val atPageTop: Boolean get() = current.isEmpty()

        fun place(group: MarkdownGroup, followingHeightPt: Double) {
            if (group.keepWithNext) keepTogether(group, followingHeightPt)
            for (item in group.items) placeItem(item)
        }

        /** Break *before* a heading whose block (plus one line of the next) won't fit as it stands. */
        private fun keepTogether(group: MarkdownGroup, followingHeightPt: Double) {
            if (atPageTop) return
            val needed = group.items.filter { it !is MarkdownItem.Space }.sumOf { it.heightPt } + followingHeightPt
            if (yPt + needed > style.contentHeightPt) breakPage()
        }

        private fun placeItem(item: MarkdownItem) {
            if (item is MarkdownItem.Space) {
                // A gap that would open a page, or overflow one, simply disappears.
                if (!atPageTop) yPt = minOf(yPt + item.heightPt, style.contentHeightPt)
                return
            }
            if (!atPageTop && yPt + item.heightPt > style.contentHeightPt) breakPage()
            current.add(PlacedItem(item, style.marginPt + baselineOffset(item)))
            yPt += item.heightPt
        }

        /** Where the item is drawn within its own box: a text baseline sits at its foot, a rule mid-box. */
        private fun baselineOffset(item: MarkdownItem): Double = when (item) {
            is MarkdownItem.Line -> yPt + item.heightPt
            is MarkdownItem.Rule -> yPt + item.heightPt / 2
            is MarkdownItem.Space -> yPt
        }

        private fun breakPage() {
            pages.add(MarkdownPage(current.toList()))
            current.clear()
            yPt = 0.0
        }

        fun finish(): List<MarkdownPage> {
            if (current.isNotEmpty() || pages.isEmpty()) pages.add(MarkdownPage(current.toList()))
            return pages.toList()
        }
    }
}
