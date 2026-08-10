package com.nexopp.render

/**
 * How a text import should be typeset: verbatim, or with its markup interpreted.
 *
 * Both flavours are the same *content* kind — printable UTF-8 — so sniffing cannot tell them
 * apart; the file's display name does (see `FileKind.isMarkdownName`). The flavour rides through
 * [TextPdfGenerator] rather than forking the import path, because everything downstream of the
 * generated PDF is identical.
 *
 * [cachePrefix] keeps the two apart in the `PdfStore` index: the same bytes opened as `notes.txt`
 * and as `notes.md` must not hand back one another's PDF.
 */
enum class TextFlavor(val cachePrefix: String) {
    /** Typeset the characters as they are — logs, source, prose. */
    PLAIN("text:"),

    /** Interpret markdown syntax (headings, emphasis, lists) when laying the page out. */
    MARKDOWN("markdown:"),
}
