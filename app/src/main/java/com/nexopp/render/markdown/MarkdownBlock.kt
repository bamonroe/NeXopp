package com.nexopp.render.markdown

/**
 * The block structure of a markdown document: what [MarkdownParser] produces and what markdown
 * layout consumes. A *data model only* — nothing here measures, wraps or paginates, and nothing
 * here knows about Android, PDFBox or fonts.
 *
 * **Nesting is the tree, not a number.** A block quote holds the blocks inside it and a list item
 * holds the blocks inside it, so "depth" is simply how deep a block sits — there is no `depth`
 * field to keep in sync with the structure, and the awkward cases (a list item containing a code
 * block, a quote containing a list) fall out for free. Layout tracks depth as it descends.
 *
 * **Inline markup is not decoded here.** Every text-carrying block keeps its raw inline source
 * (`**bold**` and `[a](b)` intact); turning that into styled runs is the inline parser's job, a
 * deliberately separate pass so block structure can be tested on its own.
 */
sealed interface MarkdownBlock {

    /** `# Heading` (ATX) or a setext-underlined line. [level] is 1–6; [text] is raw inline source. */
    data class Heading(val level: Int, val text: String) : MarkdownBlock

    /** A run of text lines. [text] keeps its internal newlines (soft breaks) and raw inline source. */
    data class Paragraph(val text: String) : MarkdownBlock

    /**
     * A literal code block. [code] is verbatim — never inline-parsed, never re-wrapped by choice.
     * [language] is the fenced info string when given; [fenced] separates ``` fences from the
     * four-space indented form, which layout may want to render differently.
     */
    data class CodeBlock(val code: String, val language: String? = null, val fenced: Boolean = true) : MarkdownBlock

    /**
     * A bullet or numbered list. [ordered] picks the marker style, [start] is the first number of an
     * ordered list (markdown lets a list start at 3), and each item holds its own child blocks.
     */
    data class ListBlock(val ordered: Boolean, val items: List<ListItem>, val start: Int = 1) : MarkdownBlock

    /** `> quoted` — holds the blocks parsed from its stripped content, so quotes nest. */
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    /** A thematic break: `---`, `***` or `___`. */
    data object Rule : MarkdownBlock
}

/** One entry of a [MarkdownBlock.ListBlock], holding whatever blocks its content parsed into. */
data class ListItem(val blocks: List<MarkdownBlock>)
