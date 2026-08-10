package com.nexopp.render

import com.nexopp.format.XoppZip
import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.io.ImageStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Bundling `pixmap` backgrounds into a ZIP-package save, and reading them back out. */
class PixmapBackgroundDomainTest {

    @get:Rule val tmp = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) +
        ByteArray(8)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(10)

    private fun file(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    private fun docWith(vararg references: String) = Document(
        pages = references.map { ref ->
            Page(
                width = 100.0,
                height = 100.0,
                background = Background.Pixmap(domain = ABSOLUTE_DOMAIN, filename = ref),
                layers = listOf(Layer(emptyList())),
            )
        },
    )

    @Test fun eachPixmapGetsItsOwnNumberedEntry() {
        val a = file("a", png)
        val b = file("b", jpeg)
        val sources = mapOf("content://a" to a, "/tmp/b.jpg" to b)
        val bundled = documentWithPixmapAttachments(docWith("content://a", "/tmp/b.jpg")) { sources[it] }

        assertEquals(listOf("bg-0.png", "bg-1.jpg"), bundled.entries.keys.toList())
        assertSame(a, bundled.entries["bg-0.png"])
        val backgrounds = bundled.document.pages.map { it.background as Background.Pixmap }
        assertTrue(backgrounds.all { it.domain == ATTACH_DOMAIN })
        assertEquals(listOf("bg-0.png", "bg-1.jpg"), backgrounds.map { it.filename })
    }

    @Test fun anUnreachablePictureKeepsItsOriginalReference() {
        val bundled = documentWithPixmapAttachments(docWith("content://gone")) { null }
        assertTrue(bundled.entries.isEmpty())
        val bg = bundled.document.pages.single().background as Background.Pixmap
        assertEquals(ABSOLUTE_DOMAIN, bg.domain)
        assertEquals("content://gone", bg.filename)
    }

    @Test fun theExtensionComesFromTheBytesNotTheReference() {
        assertEquals("png", extensionFor(file("no-suffix", png)))
        assertEquals("jpg", extensionFor(file("lying.png", jpeg)))
    }

    @Test fun bundledPicturesRoundTripThroughTheArchive() {
        val source = file("pic", png)
        val bundled = documentWithPixmapAttachments(docWith("content://pic")) { source }
        val bytes = ByteArrayOutputStream()
            .also { XoppZip.save(bundled.document, null, it, bundled.entries) }.toByteArray()

        val store = ImageStore(tmp.newFolder())
        val loaded = XoppZip.open(ByteArrayInputStream(bytes), { tmp.newFile() }, store::newFile)
        // The reopened document names the entry, and that entry resolves to the very same bytes.
        val bg = loaded.doc.pages.single().background as Background.Pixmap
        assertEquals("bg-0.png", bg.filename)
        assertArrayEquals(png, loaded.images.getValue(bg.filename).readBytes())
    }
}
