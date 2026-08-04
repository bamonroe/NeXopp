package com.xopp.android.render

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the real PDFBox authoring path: the bundled monospace face is embedded straight from
 * `src/main/assets` (no `AssetManager` on the JVM), and the output is re-opened and stripped to
 * prove the text layer is genuinely selectable rather than drawn.
 */
class TextPdfGeneratorTest {

    private fun generator() = TextPdfGenerator(AssetFonts.loader())

    private fun render(text: String, spec: TextPaginator.PageSpec = TextPaginator.PageSpec()): Pair<Int, ByteArray> {
        val out = ByteArrayOutputStream()
        val pages = generator().generate(text, out, spec)
        return pages to out.toByteArray()
    }

    private fun stripped(bytes: ByteArray): String =
        PDDocument.load(bytes).use { PDFTextStripper().getText(it) }

    @Test
    fun `writes an openable pdf with one page for short text`() {
        val (pages, bytes) = render("hello world")
        assertEquals(1, pages)
        PDDocument.load(bytes).use { assertEquals(1, it.numberOfPages) }
    }

    @Test
    fun `empty text still yields a single blank page`() {
        val (pages, bytes) = render("")
        assertEquals(1, pages)
        PDDocument.load(bytes).use { assertEquals(1, it.numberOfPages) }
    }

    @Test
    fun `text is recoverable from the pdf text layer`() {
        val (_, bytes) = render("alpha beta\ngamma delta")
        val text = stripped(bytes)
        assertTrue(text, text.contains("alpha beta"))
        assertTrue(text, text.contains("gamma delta"))
    }

    @Test
    fun `line count past a page break paginates`() {
        val spec = TextPaginator.PageSpec()
        val lines = spec.linesPerPage + 3
        val source = (1..lines).joinToString("\n") { "line$it" }
        val (pages, bytes) = render(source, spec)
        assertEquals(2, pages)
        PDDocument.load(bytes).use { assertEquals(2, it.numberOfPages) }
        val text = stripped(bytes)
        assertTrue(text.contains("line1"))
        assertTrue(text.contains("line$lines"))
    }

    @Test
    fun `non latin1 text survives the embedded font`() {
        val (_, bytes) = render("привет мир")
        assertTrue(stripped(bytes).contains("привет мир"))
    }
}
