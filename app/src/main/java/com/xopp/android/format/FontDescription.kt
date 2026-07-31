package com.xopp.android.format

/**
 * The `.xopp` `<text font=…>` value is a Pango-style **font description**: a family name
 * followed by optional style tokens, e.g. `Sans`, `Sans Bold`, `Serif Italic`,
 * `Sans Bold Italic`. There is no underline token — underline is not representable in this
 * format (see the "format is the boundary" scope rule in `CLAUDE.md`), so this helper only
 * models family + bold + italic.
 *
 * [parse] and [compose] are exact inverses for the canonical `Family [Bold] [Italic]` form,
 * which keeps text styling round-tripping losslessly to and from desktop Xournal++.
 */
data class FontDescription(val family: String, val bold: Boolean, val italic: Boolean) {

    /** The canonical `.xopp` font string: family, then `Bold`, then `Italic` (Pango order). */
    fun compose(): String = buildString {
        append(family.ifBlank { DEFAULT_FAMILY })
        if (bold) append(" Bold")
        if (italic) append(" Italic")
    }

    companion object {
        const val DEFAULT_FAMILY = "Sans"

        /** Style tokens Pango appends after the family; stripped to recover family + flags. */
        private val STYLE_TOKENS = setOf("bold", "italic", "oblique")

        /**
         * Parse a `.xopp` font string into family + bold/italic. Trailing style tokens
         * (`Bold`/`Italic`/`Oblique`, case-insensitive) are consumed as flags; whatever
         * remains is the family. `Oblique` is treated as italic. A blank input yields the
         * default family with no styling.
         */
        fun parse(font: String?): FontDescription {
            val tokens = font.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            var bold = false
            var italic = false
            var end = tokens.size
            while (end > 0) {
                when (tokens[end - 1].lowercase()) {
                    "bold" -> bold = true
                    "italic", "oblique" -> italic = true
                    else -> break
                }
                end--
            }
            val family = tokens.subList(0, end).joinToString(" ").ifBlank { DEFAULT_FAMILY }
            return FontDescription(family, bold, italic)
        }
    }
}
