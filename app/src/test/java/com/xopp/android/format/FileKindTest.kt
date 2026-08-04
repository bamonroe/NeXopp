package com.xopp.android.format

import com.xopp.android.format.model.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.Assert.assertEquals

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
        assertEquals(FileKind.UNKNOWN, sniff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertEquals(FileKind.UNKNOWN, sniff("text then a NUL".toByteArray() + 0x00.toByte() + " more".toByteArray()))
        assertEquals(FileKind.UNKNOWN, sniff(byteArrayOf(0xC3.toByte(), 0x28))) // invalid UTF-8
        assertEquals(FileKind.UNKNOWN, sniff(byteArrayOf(0x80.toByte(), 0x80.toByte())))
    }

    @Test
    fun `a multi-byte character cut off by the sample limit still reads as text`() {
        val long = "é".repeat(FileKind.MAGIC_BYTES) // 2 bytes each, so the sample ends mid-character
        assertEquals(FileKind.TEXT, sniff(long.toByteArray()))
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
