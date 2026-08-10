package com.nexopp.render

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File

/**
 * The JVM stand-in for [PdfFonts], which needs an Android `AssetManager`. Loads the very same TTFs
 * straight off disk, so unit tests measure and draw with the fonts the app ships.
 */
object AssetFonts {

    fun file(face: PdfFonts.Face): File = File("src/main/assets/" + face.assetPath)

    /**
     * A loader for [TextPdfGenerator]. [onDocument] fires once per document the loader is first
     * asked for a face in — the hook tests use to count how many PDFs were actually typeset,
     * independent of how many faces a flavour happens to embed.
     */
    fun loader(onDocument: (PDDocument) -> Unit = {}): (PDDocument, PdfFonts.Face) -> PdfFonts.Embedded {
        val seen = HashSet<Int>()
        val cache = HashMap<Pair<Int, PdfFonts.Face>, PdfFonts.Embedded>()
        return { doc, face ->
            val id = System.identityHashCode(doc)
            if (seen.add(id)) onDocument(doc)
            cache.getOrPut(id to face) { embed(doc, face) }
        }
    }

    fun embed(doc: PDDocument, face: PdfFonts.Face): PdfFonts.Embedded {
        val font = file(face).inputStream().use { PDType0Font.load(doc, it, true) }
        return PdfFonts.Embedded(font, GlyphSanitizer { s -> runCatching { font.getStringWidth(s) }.isSuccess })
    }
}
