package com.xopp.android.render

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.xopp.android.render.markdown.RunStyle
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end cover for the markdown flavour: source in, real PDF out, text stripped back out again.
 *
 * The point of stripping is that it proves the styled runs became a genuine selectable text layer —
 * drawn in four different embedded faces at four different x offsets, yet still recoverable as the
 * words the author typed, with the markup syntax gone.
 */
class MarkdownPdfWriterTest {

    private fun render(source: String): ByteArray {
        val out = ByteArrayOutputStream()
        TextPdfGenerator(AssetFonts.loader()).generate(
            source,
            out,
            TextPaginator.PageSpec(),
            TextFlavor.MARKDOWN,
        )
        return out.toByteArray()
    }

    private fun stripped(source: String): String =
        PDDocument.load(render(source)).use { PDFTextStripper().getText(it) }

    @Test
    fun `markdown text is recoverable and its markup is gone`() {
        val text = stripped("# Title\n\nSome **bold** and *italic* words with `code`.\n")
        assertTrue(text, text.contains("Title"))
        assertTrue(text, text.contains("bold"))
        assertTrue(text, text.contains("italic"))
        assertTrue(text, text.contains("code"))
        assertTrue("markup should not survive into the text layer: $text", !text.contains("**"))
        assertTrue("heading hashes should not survive: $text", !text.contains("# Title"))
    }

    @Test
    fun `list markers and quotes are drawn`() {
        val text = stripped("- first\n- second\n\n> quoted line\n")
        assertTrue(text, text.contains("first"))
        assertTrue(text, text.contains("second"))
        assertTrue(text, text.contains("•"))
        assertTrue(text, text.contains("quoted line"))
    }

    @Test
    fun `an ordered list keeps its numbering`() {
        val text = stripped("3. third\n4. fourth\n")
        assertTrue(text, text.contains("3."))
        assertTrue(text, text.contains("4."))
    }

    @Test
    fun `a rule is drawn as a filled rect, not as text`() {
        val withRule = stripped("before\n\n---\n\nafter\n")
        assertTrue(withRule, withRule.contains("before") && withRule.contains("after"))
        assertTrue("the rule is graphics, not characters: $withRule", !withRule.contains("---"))
    }

    @Test
    fun `bold and regular are embedded as different faces`() {
        PDDocument.load(render("plain words and **bold words**\n")).use { doc ->
            val names = doc.getPage(0).resources.fontNames.toList()
            assertTrue("expected more than one face on the page: $names", names.size > 1)
        }
    }

    @Test
    fun `an empty source still yields one blank page`() {
        PDDocument.load(render("")).use { assertEquals(1, it.numberOfPages) }
    }

    @Test
    fun `long markdown paginates`() {
        val source = (1..400).joinToString("\n\n") { "Paragraph $it with a few words in it." }
        PDDocument.load(render(source)).use { assertTrue(it.numberOfPages > 1) }
    }

    @Test
    fun `wrapping uses the same faces the text is drawn in`() {
        // Bold DejaVu is wider than regular, so a bold measurer that fell back to the regular face
        // would let a line overrun. Same words, one styled: the bold version must take more room.
        PDDocument().use { doc ->
            val writer = MarkdownPdfWriter.forDocument(doc) { d, face -> AssetFonts.embed(d, face) }
            val regular = writer.measure(RunStyle.REGULAR, 10.0, "measure me")
            val bold = writer.measure(RunStyle.BOLD, 10.0, "measure me")
            assertNotEquals(regular, bold)
            assertTrue("bold should be at least as wide: $regular vs $bold", bold > regular)
        }
    }

    @Test
    fun `non latin text survives the markdown path`() {
        val text = stripped("# Заголовок\n\n**Ελληνικά** text\n")
        assertTrue(text, text.contains("Заголовок"))
        assertTrue(text, text.contains("Ελληνικά"))
    }
}
