package com.nexopp.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class XoppZipTest {

    @get:Rule val tmp = TemporaryFolder()

    private val sample = """
        <?xml version="1.0" standalone="no"?>
        <xournal creator="xournalpp 1.1.1+dev" fileversion="4">
        <page width="595.27559100" height="841.88976400">
        <background type="solid" color="#ffffffff" style="graph"/>
        <layer>
        <stroke tool="pen" color="#000000ff" width="0.85">10.0 20.0 11.0 21.0</stroke>
        </layer>
        </page>
        </xournal>
    """.trimIndent()

    private fun writeZip(pdf: java.io.File? = null): ByteArray {
        val doc = Xopp.parseXml(sample)
        return ByteArrayOutputStream().also { XoppZip.save(doc, pdf, it) }.toByteArray()
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return out
    }

    @Test fun documentSurvivesZipRoundTrip() {
        val doc1 = Xopp.parseXml(sample)
        val loaded = XoppZip.open(ByteArrayInputStream(writeZip()), pdfStore(tmp.newFolder())::newFile)
        assertEquals(doc1, loaded.doc)
        assertNull(loaded.pdf) // no PDF was embedded
    }

    @Test fun archiveHasExpectedEntries() {
        val entries = entries(writeZip())
        assertTrue("mimetype" in entries)
        assertTrue("META-INF/version" in entries)
        assertTrue("content.xml" in entries)
        // content.xml is plain XML, NOT gzipped.
        assertTrue(String(entries["content.xml"]!!).startsWith("<?xml"))
        assertEquals("current=4\nmin=1", String(entries["META-INF/version"]!!))
    }

    // Intentional deviation: desktop Xournal++ 1.3.5's mimetype check is inverted, so we must NOT
    // write the canonical string. If this ever equals it, the released reader would reject the file.
    @Test fun mimetypeIsNotTheCanonicalString() {
        val mimetype = String(entries(writeZip())["mimetype"]!!)
        assertEquals(XoppZip.MIMETYPE, mimetype)
        assertNotEquals("application/xournal++", mimetype)
        assertTrue("mimetype must stay NUL-terminated in the reader's 25-byte buffer", mimetype.length < 25)
    }

    @Test fun embeddedPdfIsExtractedOnOpen() {
        val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31) // "%PDF-1"
        val pdfIn = tmp.newFile("in.pdf").apply { writeBytes(pdfBytes) }
        val zip = writeZip(pdfIn)
        assertTrue(XoppZip.PDF_ENTRY in entries(zip))

        val loaded = XoppZip.open(ByteArrayInputStream(zip), pdfStore(tmp.newFolder())::newFile)
        assertArrayEquals(pdfBytes, loaded.pdf!!.readBytes())
    }

    /**
     * Two packages opened into the same store must land in two files. They used to share one fixed
     * `background.pdf`, which blanked the pages of whichever document was still rendering from it.
     */
    @Test fun twoPackagesGetTheirOwnPdfFiles() {
        val store = pdfStore(tmp.newFolder())
        val first = tmp.newFile("first.pdf").apply { writeBytes("%PDF-first".toByteArray()) }
        val second = tmp.newFile("second.pdf").apply { writeBytes("%PDF-second".toByteArray()) }

        val a = XoppZip.open(ByteArrayInputStream(writeZip(first)), store::newFile).pdf!!
        val b = XoppZip.open(ByteArrayInputStream(writeZip(second)), store::newFile).pdf!!

        assertNotEquals(a.absolutePath, b.absolutePath)
        // Both must still be on disk: the first document keeps rasterising from its file while the
        // second one opens, so "unique path" is only useful if neither file was replaced or removed.
        assertTrue(a.isFile && b.isFile)
        assertEquals("%PDF-first", a.readText())
        assertEquals("%PDF-second", b.readText())
    }

    private fun pdfStore(dir: java.io.File) = com.nexopp.io.PdfStore(dir)
}
