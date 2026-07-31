package com.xopp.android.render

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * Extracts the positioned text layer of an imported PDF into a [PdfTextIndex] using PDFBox
 * ([PDFTextStripper]) — the same dependency the exporter uses, so no OCR engine is needed for
 * born-digital PDFs. A scanned (image-only) page simply yields no words ([PdfTextIndex.hasAnyText]
 * is then false); adding OCR for that case is a tracked follow-up. Any failure degrades to an empty
 * index so import never breaks. PDFBox's stripper reports boxes in a top-left origin, y-down frame
 * in points — the same frame `.xopp` geometry uses — so words map to the screen like strokes.
 */
class PdfTextExtractor {

    fun extract(file: File): PdfTextIndex {
        val doc = runCatching { PDDocument.load(file) }.getOrNull() ?: return PdfTextIndex(emptyList())
        return try {
            val stripper = WordStripper()
            val pages = ArrayList<List<PdfWord>>(doc.numberOfPages)
            for (i in 0 until doc.numberOfPages) {
                stripper.begin(i + 1) // PDFTextStripper page range is 1-based
                stripper.getText(doc)
                pages += stripper.take()
            }
            PdfTextIndex(pages)
        } catch (_: Throwable) {
            PdfTextIndex(emptyList())
        } finally {
            doc.close()
        }
    }

    /** Collects one page's glyphs into words via the pure [PdfWordGrouper]. */
    private class WordStripper : PDFTextStripper() {
        private val collected = ArrayList<PdfWord>()

        init {
            sortByPosition = true
        }

        fun begin(page: Int) {
            collected.clear()
            startPage = page
            endPage = page
        }

        fun take(): List<PdfWord> = collected.toList()

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            val chars = ArrayList<CharBox>(textPositions.size)
            for (tp in textPositions) {
                val unicode = tp.unicode ?: continue
                if (unicode.isEmpty()) continue
                val left = tp.xDirAdj.toDouble()
                val top = tp.yDirAdj.toDouble()
                val height = tp.heightDir.toDouble().let { if (it > 0.0) it else tp.fontSizeInPt.toDouble() }
                val right = left + tp.widthDirAdj.toDouble()
                val bottom = top + height
                for (c in unicode) chars.add(CharBox(c, left, top, right, bottom))
            }
            collected += PdfWordGrouper.group(chars)
        }
    }
}
