package com.nexopp.format

import com.nexopp.format.model.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** The open path must classify by content, since the picker is unfiltered and URIs carry no suffix. */
class FileKindTest {

    private fun sniff(bytes: ByteArray) = FileKind.sniff(ByteArrayInputStream(bytes).buffered())

    @Test
    fun `real gzip xopp bytes are gzip`() {
        val out = ByteArrayOutputStream()
        Xopp.save(Document(), out)
        assertEquals(FileKind.GZIP, sniff(out.toByteArray()))
    }

    @Test
    fun `rnote fixtures are told apart from a gzip xopp`() {
        // Both are gzip, so the verdict can only come from the decompressed payload.
        for (name in listOf("empty", "plain", "backgrounds", "layers", "text-image")) {
            val bytes = javaClass.classLoader!!.getResourceAsStream("fixtures/rnote/$name.rnote")
                ?.use { it.readBytes() }
                ?: error("missing fixture fixtures/rnote/$name.rnote")
            assertEquals("fixtures/rnote/$name.rnote", FileKind.RNOTE, sniff(bytes))
        }
    }

    @Test
    fun `real zip package bytes are zip`() {
        val out = ByteArrayOutputStream()
        XoppZip.save(Document(), null, out)
        assertEquals(FileKind.ZIP, sniff(out.toByteArray()))
    }

    @Test
    fun `pdf header is detected`() {
        assertEquals(FileKind.PDF, sniff("%PDF-1.7\n%âãÏÓ".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `uncompressed xournal xml is detected`() {
        assertEquals(FileKind.XML, sniff(Xopp.toXml(Document()).toByteArray()))
        assertEquals(FileKind.XML, sniff("<xournal creator=\"x\">".toByteArray()))
    }

    @Test
    fun `unknown and short input do not crash`() {
        assertEquals(FileKind.UNKNOWN, sniff(ByteArray(0)))
        assertEquals(FileKind.UNKNOWN, sniff(byteArrayOf(0x00, 0x01, 0x02)))
    }

    @Test
    fun `plain ascii and utf8 prose are text`() {
        assertEquals(FileKind.TEXT, sniff("hi".toByteArray()))
        assertEquals(FileKind.TEXT, sniff("not a document at all".toByteArray()))
        assertEquals(FileKind.TEXT, sniff("# Heading\n\nA line\twith a tab\r\n".toByteArray()))
        assertEquals(FileKind.TEXT, sniff("héllo — ünïcode ✓ 漢字\n".toByteArray()))
    }

    @Test
    fun `a utf8 bom does not stop text being text`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(FileKind.TEXT, sniff(bom + "plain notes\n".toByteArray()))
        assertEquals(FileKind.UNKNOWN, sniff(bom))
    }

    @Test
    fun `binary content is not text`() {
        // A PNG signature cut short is neither an image nor text.
        assertEquals(FileKind.UNKNOWN, sniff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertEquals(FileKind.UNKNOWN, sniff("text then a NUL".toByteArray() + 0x00.toByte() + " more".toByteArray()))
        assertEquals(FileKind.UNKNOWN, sniff(byteArrayOf(0xC3.toByte(), 0x28))) // invalid UTF-8
        assertEquals(FileKind.UNKNOWN, sniff(byteArrayOf(0x80.toByte(), 0x80.toByte())))
    }

    @Test
    fun `png jpeg and webp are recognised as images`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(8)
        assertEquals(FileKind.IMAGE, sniff(png))
        assertEquals(FileKind.IMAGE, sniff(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())))
        val webp = "RIFF".toByteArray() + byteArrayOf(0x24, 0, 0, 0) + "WEBPVP8 ".toByteArray()
        assertEquals(FileKind.IMAGE, sniff(webp))
    }

    @Test
    fun `a riff container that is not webp is not an image`() {
        val wav = "RIFF".toByteArray() + byteArrayOf(0x24, 0, 0, 0) + "WAVEfmt ".toByteArray()
        assertEquals(FileKind.UNKNOWN, sniff(wav))
    }

    @Test
    fun `a multi-byte character cut off by the sample limit still reads as text`() {
        val long = "é".repeat(FileKind.MAGIC_BYTES) // 2 bytes each, so the sample ends mid-character
        assertEquals(FileKind.TEXT, sniff(long.toByteArray()))
    }

    @Test
    fun `markdown is a name verdict, not a content one`() {
        // The bytes of a markdown file are plain text; only the display name says otherwise.
        assertEquals(FileKind.TEXT, sniff("# heading\n\nsome *emphasis*\n".toByteArray()))
        assertTrue(FileKind.isMarkdownName("notes.md"))
        assertTrue(FileKind.isMarkdownName("notes.markdown"))
        assertTrue(FileKind.isMarkdownName("READ.ME.MD"))
        assertFalse(FileKind.isMarkdownName("notes.txt"))
        assertFalse(FileKind.isMarkdownName("md"))
        assertFalse(FileKind.isMarkdownName("notes.md.txt"))
    }

    @Test
    fun `sniffing leaves the stream readable from the start`() {
        val out = ByteArrayOutputStream()
        Xopp.save(Document(creator = "sniff-test"), out)
        val input = ByteArrayInputStream(out.toByteArray()).buffered()
        assertEquals(FileKind.GZIP, FileKind.sniff(input))
        assertEquals("sniff-test", Xopp.open(input).creator)
    }
}
