package com.xopp.android.render.markdown

import com.xopp.android.render.TextWrapping

/**
 * Turns a parsed block tree into a flat stream of [MarkdownGroup]s — wrapped lines, rules and the
 * blank space between blocks — with every size taken from a [MarkdownStyle]. Vertical positions and
 * page breaks are *not* decided here; that is [MarkdownLayout]'s job, and keeping the two apart is
 * what lets page breaking reason about whole blocks.
 *
 * Nesting is handled by recursion over the tree: a quote or list item composes its children at a
 * larger indent, so a list inside a quote inside a list falls out with no depth bookkeeping.
 *
 * Block gaps *collapse*: the space below one block and above the next is the larger of the two, not
 * their sum, so a heading after a paragraph doesn't open a double gap.
 */
internal class MarkdownComposer(
    private val style: MarkdownStyle,
    private val measure: SizedMeasurer,
) {

    private val groups = mutableListOf<MarkdownGroup>()

    /** Space owed above the *next* block, from the block just emitted. */
    private var pendingSpacePt = 0.0

    fun compose(blocks: List<MarkdownBlock>): List<MarkdownGroup> {
        emitBlocks(blocks, indentPt = 0.0)
        return groups.toList()
    }

    private fun emitBlocks(blocks: List<MarkdownBlock>, indentPt: Double) {
        for (block in blocks) when (block) {
            is MarkdownBlock.Heading -> emitHeading(block, indentPt)
            is MarkdownBlock.Paragraph -> emitParagraph(block, indentPt)
            is MarkdownBlock.CodeBlock -> emitCode(block, indentPt)
            is MarkdownBlock.Quote -> emitQuote(block, indentPt)
            is MarkdownBlock.ListBlock -> emitList(block, indentPt)
            MarkdownBlock.Rule -> emitRule(indentPt)
        }
    }

    // --- blocks ------------------------------------------------------------------------------

    private fun emitHeading(block: MarkdownBlock.Heading, indentPt: Double) {
        val sizePt = style.headingSizePt(block.level)
        val runs = MarkdownInlineParser.parse(block.text).map { it.copy(bold = true) }
        val lines = wrapLines(runs, indentPt, sizePt)
        // A heading is glued to what follows it, so it can never sit alone at the page foot.
        emitGroup(
            lines,
            spaceBefore = sizePt * style.headingSpaceBeforeRatio,
            spaceAfter = sizePt * style.headingSpaceAfterRatio,
            keepWithNext = true,
        )
    }

    private fun emitParagraph(block: MarkdownBlock.Paragraph, indentPt: Double) {
        val runs = MarkdownInlineParser.parse(block.text)
        emitGroup(wrapLines(runs, indentPt, style.bodyFontSizePt), blockSpacePt())
    }

    /** Code is verbatim: never inline-parsed, never re-flowed on words — only hard-broken to fit. */
    private fun emitCode(block: MarkdownBlock.CodeBlock, indentPt: Double) {
        val indent = indentPt + style.codeIndentPt
        val sizePt = style.codeFontSizePt
        val width = style.contentWidthPt - indent
        val height = style.lineHeightPt(sizePt)
        val lines = block.code.split("\n").flatMap { source ->
            val text = TextWrapping.expandTabs(source)
            TextWrapping.hardBreak(text, width, width) { measure(RunStyle.CODE, sizePt, it) }
        }.map { chunk ->
            val fragments =
                if (chunk.isEmpty()) emptyList()
                else listOf(RunFragment(chunk, RunStyle.CODE, 0.0, measure(RunStyle.CODE, sizePt, chunk).toDouble()))
            MarkdownItem.Line(fragments, indent, sizePt, height)
        }
        emitGroup(lines, blockSpacePt())
    }

    private fun emitQuote(block: MarkdownBlock.Quote, indentPt: Double) {
        pendingSpacePt = maxOf(pendingSpacePt, blockSpacePt())
        emitBlocks(block.blocks, indentPt + style.quoteIndentPt)
    }

    private fun emitRule(indentPt: Double) {
        val rule = MarkdownItem.Rule(
            indentPt = indentPt,
            widthPt = style.contentWidthPt - indentPt,
            thicknessPt = style.ruleThicknessPt,
            heightPt = style.ruleHeightPt,
        )
        emitGroup(listOf(rule), blockSpacePt())
    }

    /**
     * Each item's blocks are composed one indent step in, then its marker is hung on the first line
     * that came out — so the bullet aligns with the item's first baseline whatever that block is.
     */
    private fun emitList(block: MarkdownBlock.ListBlock, indentPt: Double) {
        val indent = indentPt + style.listIndentPt
        block.items.forEachIndexed { index, item ->
            pendingSpacePt = maxOf(pendingSpacePt, blockSpacePt())
            val before = groups.size
            emitBlocks(item.blocks, indent)
            attachMarker(before, markerText(block, index))
        }
    }

    private fun markerText(block: MarkdownBlock.ListBlock, index: Int): String =
        if (block.ordered) "${block.start + index}." else "•"

    /** Stamp [marker] onto the first [MarkdownItem.Line] emitted from group index [from] onward. */
    private fun attachMarker(from: Int, marker: String) {
        for (g in from until groups.size) {
            val group = groups[g]
            val i = group.items.indexOfFirst { it is MarkdownItem.Line }
            if (i < 0) continue
            val line = group.items[i] as MarkdownItem.Line
            val items = group.items.toMutableList()
            // Markers hang in the gutter the item's indent opened up, one step left of its text.
            items[i] = line.copy(marker = marker, markerXPt = -style.listIndentPt)
            groups[g] = group.copy(items = items)
            return
        }
    }

    // --- shared helpers ----------------------------------------------------------------------

    private fun blockSpacePt(): Double = style.bodyLineHeightPt * style.blockSpacingRatio

    /** Wrap [runs] to the width left at [indentPt] and turn each wrapped line into a [MarkdownItem]. */
    private fun wrapLines(runs: List<StyledRun>, indentPt: Double, fontSizePt: Double): List<MarkdownItem.Line> {
        val height = style.lineHeightPt(fontSizePt)
        val width = style.contentWidthPt - indentPt
        return StyledWrapper.wrap(runs, width) { s, text -> measure(s, fontSizePt, text) }
            .map { MarkdownItem.Line(it, indentPt, fontSizePt, height) }
    }

    /** Append one block's items as a group, prefixed by the collapsed gap above it. */
    private fun emitGroup(
        items: List<MarkdownItem>,
        spaceBefore: Double,
        spaceAfter: Double = spaceBefore,
        keepWithNext: Boolean = false,
    ) {
        if (items.isEmpty()) return
        val gap = maxOf(pendingSpacePt, spaceBefore).takeIf { groups.isNotEmpty() && it > 0.0 }
        val withSpace = if (gap != null) listOf(MarkdownItem.Space(gap)) + items else items
        groups.add(MarkdownGroup(withSpace, keepWithNext))
        pendingSpacePt = spaceAfter
    }
}
