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
        assertEquals(FileKind.UNKNOWN, sniff("hi".toByteArray()))
        assertEquals(FileKind.UNKNOWN, sniff("not a document at all".toByteArray()))
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
