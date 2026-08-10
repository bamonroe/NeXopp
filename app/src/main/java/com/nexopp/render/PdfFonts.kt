package com.nexopp.render

import android.content.res.AssetManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font

/**
 * Loads the app's bundled Unicode fonts and embeds them into a [PDDocument].
 *
 * The base-14 PDF fonts used by [PdfVectorPainter] for `.xopp` text elements only encode WinAnsi, so
 * anything outside Latin-1 — CJK, Cyrillic, Greek, box drawing, emoji — is lost. An imported plain
 * text file is arbitrary UTF-8, so the text-import path (see [TextPaginator] and the generated PDF
 * background) draws with these embedded `PDType0Font`s instead: PDFBox subsets them into the output,
 * so the file stays small and renders identically everywhere without the font installed.
 *
 * The bundled faces are **DejaVu Sans** (proportional, with its bold, oblique and bold-oblique
 * companions for markdown emphasis) and **DejaVu Sans Mono** (monospace), from
 * `app/src/main/assets/fonts/`. They are permissively licensed — Bitstream Vera + Arev, with the
 * DejaVu changes in the public domain — and the full licence ships alongside them as
 * `assets/fonts/LICENSE.txt`. See `docs/architecture.md` for why this pair.
 *
 * Fonts must be embedded per-document, so instances are cached by the [PDDocument] they were loaded
 * into; hold one [PdfFonts] for the app and call [load] as often as you like.
 */
class PdfFonts(private val assets: AssetManager) {

    /** The bundled faces, by role. */
    enum class Face(val assetPath: String) {
        PROPORTIONAL("fonts/DejaVuSans.ttf"),
        PROPORTIONAL_BOLD("fonts/DejaVuSans-Bold.ttf"),
        PROPORTIONAL_ITALIC("fonts/DejaVuSans-Oblique.ttf"),
        PROPORTIONAL_BOLD_ITALIC("fonts/DejaVuSans-BoldOblique.ttf"),
        MONOSPACE("fonts/DejaVuSansMono.ttf"),
    }

    /**
     * A font embedded in one document, paired with the [GlyphSanitizer] that keeps unsupported
     * codepoints from throwing, plus the metrics [TextPaginator] needs to wrap against it.
     */
    class Embedded(val font: PDFont, private val sanitizer: GlyphSanitizer) {

        /** [text] with every codepoint this font can't encode replaced by a substitution glyph. */
        fun sanitize(text: String): String = sanitizer.sanitize(text)

        /** Width of [text] at [sizePt], in points. Sanitises first, so it never throws on odd input. */
        fun widthPt(text: String, sizePt: Double): Float =
            runCatching { font.getStringWidth(sanitize(text)) / 1000f * sizePt.toFloat() }.getOrDefault(0f)

        /** A measurer for [TextPaginator.layout]/[TextPaginator.wrap] at a fixed size. */
        fun measurer(sizePt: Double): (String) -> Float = { widthPt(it, sizePt) }
    }

    private val cache = HashMap<Key, Embedded>()

    /**
     * Embed [face] into [doc], reusing the instance if it is already embedded there. Throws
     * [java.io.IOException] if the asset is missing or unreadable — a packaging error, not something
     * a caller can recover from.
     */
    fun load(doc: PDDocument, face: Face): Embedded = cache.getOrPut(Key(System.identityHashCode(doc), face)) {
        val font = assets.open(face.assetPath).use { PDType0Font.load(doc, it, true) }
        Embedded(font, GlyphSanitizer { s -> runCatching { font.getStringWidth(s) }.isSuccess })
    }

    /** Drop cached fonts for [doc]. Call once the document is saved and closed. */
    fun release(doc: PDDocument) {
        val id = System.identityHashCode(doc)
        cache.keys.removeAll { it.docId == id }
    }

    private data class Key(val docId: Int, val face: Face)
}
