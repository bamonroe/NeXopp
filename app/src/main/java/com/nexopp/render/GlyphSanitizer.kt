package com.nexopp.render

/**
 * Replaces codepoints an embedded font can't encode, so `showText` substitutes a visible glyph
 * instead of throwing mid-export.
 *
 * Kept free of PDFBox and Android types — encodability is injected as a `(String) -> Boolean`
 * predicate over a single codepoint's string — so the substitution rules are pure and unit-testable
 * on the JVM. [PdfFonts] supplies the real predicate, backed by the loaded `PDType0Font`.
 *
 * Results are memoised per codepoint: an imported text file is overwhelmingly repeat characters, and
 * probing the font's cmap for every glyph on every page would dominate export time.
 */
class GlyphSanitizer(private val encodable: (String) -> Boolean) {

    private val resolved = HashMap<Int, String>()

    /**
     * The substitution glyph, resolved once against this font: U+FFFD REPLACEMENT CHARACTER when the
     * font has it (DejaVu does), otherwise a plain `?`, which every usable font carries. If neither
     * encodes, we fall back to a space so a pathological font still exports rather than aborting.
     */
    val substitute: String by lazy {
        listOf(REPLACEMENT, QUESTION_MARK, " ").firstOrNull { runCatching { encodable(it) }.getOrDefault(false) }
            ?: " "
    }

    /**
     * Map [text] onto glyphs this font can draw. Encodable codepoints pass through unchanged;
     * anything else — an unsupported script, an unpaired surrogate, a control character — becomes
     * [substitute]. Iterates by codepoint, so astral characters (emoji) survive as one unit rather
     * than splitting into surrogate halves.
     */
    fun sanitize(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out.append(glyphFor(cp))
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    private fun glyphFor(cp: Int): String = resolved.getOrPut(cp) {
        val s = String(Character.toChars(cp))
        if (runCatching { encodable(s) }.getOrDefault(false)) s else substitute
    }

    private companion object {
        const val REPLACEMENT = "�"
        const val QUESTION_MARK = "?"
    }
}
