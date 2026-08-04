package com.xopp.android.format

import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool
import com.xopp.android.render.attachPixmapFilename
import com.xopp.android.render.documentWithPixmapAttachments
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The pixmap counterpart of [PdfBackgroundRoundTripTest]: locks in that an image-backed document —
 * the shape "open an image" builds (see `ImageImport`) — survives write→read unchanged through both
 * save paths, and that annotations drawn over the picture come back with it.
 *
 * Two reference domains matter on disk. `domain="absolute"` **links** the picture where it already
 * lives (a path or a `content://` URI carried verbatim), and must stay external — a linking save
 * that quietly inlined or rewrote the reference would break the file for desktop Xournal++.
 * `domain="attach"` **bundles** it as an archive entry of a ZIP-package `.xopp`.
 */
class PixmapBackgroundRoundTripTest {

    @get:Rule val tmp = TemporaryFolder()

    private val strokes = listOf(
        Stroke(Tool.PEN, 0x000000, null, listOf(StrokePoint(10.0, 20.0, 0.85), StrokePoint(11.0, 21.0, 0.85)), true),
    )

    private fun pixmapDoc(filename: String, domain: String = "absolute"): Document = Document(
        pages = listOf(
            Page(800.0, 600.0, Background.Pixmap(domain, filename), listOf(Layer(strokes))),
        ),
    )

    /** A one-pixel PNG on disk, so [documentWithPixmapAttachments] sniffs a real extension. */
    private fun pngFile(name: String = "shot"): File =
        tmp.newFile(name).apply {
            writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(64))
        }

    @Test fun pixmapBackgroundSurvivesReserialize() {
        val doc = pixmapDoc("/storage/emulated/0/Pictures/shot.png")
        assertEquals(doc, Xopp.parseXml(Xopp.toXml(doc)))
    }

    /**
     * An absolute reference is written verbatim and the picture's bytes never enter the file: the
     * linking save keeps the image external, which is what makes the reference resolvable on the
     * desktop side.
     */
    @Test fun absoluteReferenceStaysExternal() {
        val path = "/storage/emulated/0/Pictures/shot.png"
        val xml = Xopp.toXml(pixmapDoc(path))
        assertTrue(xml.contains("type=\"pixmap\""))
        assertTrue(xml.contains("domain=\"absolute\""))
        assertTrue(xml.contains("filename=\"$path\""))

        val bg = Xopp.parseXml(xml).pages[0].background as Background.Pixmap
        assertEquals("absolute", bg.domain)
        assertEquals(path, bg.filename)
    }

    /**
     * Desktop Xournal++ has no `relative` domain — a path relative to the `.xopp`'s own folder rides
     * under `absolute`, exactly as for a PDF background. The string must come through untouched.
     */
    @Test fun relativeReferenceSurvivesUnderAbsoluteDomain() {
        val doc = pixmapDoc("scans/shot.png")
        val xml = Xopp.toXml(doc)
        assertTrue(xml.contains("filename=\"scans/shot.png\""))
        assertEquals(doc, Xopp.parseXml(xml))
    }

    /** A `content://` URI is opaque to the format and must not be normalised or path-ified. */
    @Test fun contentUriReferenceSurvivesVerbatim() {
        val uri = "content://media/external/images/media/1000000023"
        val bg = Xopp.parseXml(Xopp.toXml(pixmapDoc(uri))).pages[0].background as Background.Pixmap
        assertEquals(uri, bg.filename)
    }

    /**
     * A `domain="attach"` reference names an archive entry (or, on a non-zip document, the
     * `<xoppname>.<filename>` sibling) and must round-trip verbatim, so a document opened with an
     * unreachable attachment is still saved with its reference intact rather than losing the
     * background.
     */
    @Test fun attachReferenceSurvivesVerbatim() {
        val bg = Xopp.parseXml(Xopp.toXml(pixmapDoc("bg-0.png", "attach"))).pages[0].background as Background.Pixmap
        assertEquals("attach", bg.domain)
        assertEquals("bg-0.png", bg.filename)
    }

    /**
     * Every image-backed page gets its own numbered entry when bundled — unlike a PDF background
     * there can be many — and a page whose picture can't be reached keeps its original reference.
     */
    @Test fun bundlingNumbersEachPageAndLeavesUnreachableOnesAlone() {
        val a = pngFile("a.png")
        val doc = Document(
            pages = listOf(
                Page(800.0, 600.0, Background.Pixmap("absolute", "/pics/a.png"), listOf(Layer(strokes))),
                Page(800.0, 600.0, Background.Pixmap("absolute", "/pics/gone.png"), listOf(Layer(emptyList()))),
                Page(800.0, 600.0, Background.Pixmap("absolute", "/pics/b.png"), listOf(Layer(emptyList()))),
            ),
        )
        val bundled = documentWithPixmapAttachments(doc) { if (it == "/pics/gone.png") null else a }

        assertEquals(listOf("bg-0.png", "bg-1.png"), bundled.entries.keys.toList())
        assertEquals("bg-0.png", (bundled.document.pages[0].background as Background.Pixmap).filename)
        assertEquals("/pics/gone.png", (bundled.document.pages[1].background as Background.Pixmap).filename)
        assertEquals("absolute", (bundled.document.pages[1].background as Background.Pixmap).domain)
        assertEquals("bg-1.png", (bundled.document.pages[2].background as Background.Pixmap).filename)
        assertEquals(attachPixmapFilename(0, "png"), bundled.entries.keys.first())
    }

    /**
     * The full bundling save path: re-point the background, write the ZIP package, read it back, and
     * find the same document, the picture's bytes intact under its entry name, and the annotations
     * still on the page.
     */
    @Test fun bundledPictureAndAnnotationsSurviveZipRoundTrip() {
        val picture = pngFile()
        val bundled = documentWithPixmapAttachments(pixmapDoc("/pics/shot.png")) { picture }

        val bytes = ByteArrayOutputStream()
            .also { XoppZip.save(bundled.document, null, it, bundled.entries) }
            .toByteArray()

        val extracted = tmp.newFolder()
        var n = 0
        val loaded = XoppZip.open(
            ByteArrayInputStream(bytes),
            { File(extracted, "bg.pdf") },
            { File(extracted, "img-${n++}") },
        )

        assertEquals(bundled.document, loaded.doc)
        val bg = loaded.doc.pages[0].background as Background.Pixmap
        assertEquals("attach", bg.domain)
        assertEquals("bg-0.png", bg.filename)
        assertEquals(strokes, loaded.doc.pages[0].layers[0].elements)
        assertArrayEquals(picture.readBytes(), loaded.images.getValue("bg-0.png").readBytes())
    }

    /** A linking save must not smuggle the picture's bytes into the XML. */
    @Test fun linkingSaveWritesNoImageBytes() {
        val xml = Xopp.toXml(pixmapDoc("/pics/shot.png"))
        assertFalse("the picture is linked, never inlined", xml.contains("base64"))
        assertTrue(xml.contains("<stroke")) // the annotations over it are still written
    }
}
