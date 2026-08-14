package com.nexopp.format.rnote

import com.nexopp.format.Xopp
import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.Element
import com.nexopp.format.model.ImageElement
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.RawElement
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.TexImageElement
import com.nexopp.format.model.TextElement
import com.nexopp.format.model.Tool
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **report** rows of the `.xopp` ↔ `.rnote` feature-gap matrix, one test each: a document that
 * loses something must say so, and one that loses nothing must say nothing at all.
 */
class RnoteExportWarningsTest {

    /** Five A4 pages, one per background style — same size, five different backgrounds. */
    private val backgrounds: Document = fixture("fixtures/backgrounds.xopp")

    /** One page, one layer: pen strokes and a highlighter, nothing that can't travel. */
    private val plain: Document = fixture("fixtures/plain.xopp")

    @Test
    fun `five different backgrounds warn about the background only`() {
        assertEquals(
            listOf(
                "Pages have different backgrounds; Rnote keeps only one, so page 1's background " +
                    "will be used for all.",
            ),
            exportWarnings(backgrounds),
        )
    }

    @Test
    fun `a document that loses nothing says nothing`() {
        assertEquals(emptyList<String>(), exportWarnings(plain))
        assertEquals(emptyList<String>(), exportWarnings(Document()))
    }

    @Test
    fun `a pdf background is reported`() {
        val document = Document(
            pages = listOf(page(Background.Pdf(filename = "notes.pdf", pageNo = 0, domain = "absolute"))),
        )
        assertTrue(
            exportWarnings(document).any { it.startsWith("PDF page backgrounds are not saved") },
        )
    }

    @Test
    fun `a pixmap background is reported`() {
        val document = Document(
            pages = listOf(page(Background.Pixmap(domain = "absolute", filename = "/tmp/scan.png"))),
        )
        assertEquals(
            listOf("Image page backgrounds are not saved in .rnote."),
            exportWarnings(document),
        )
    }

    @Test
    fun `a highlighter above an ink layer is reported`() {
        val document = Document(
            pages = listOf(
                page(
                    layers = listOf(
                        Layer(listOf(stroke(Tool.PEN))),
                        Layer(listOf(stroke(Tool.HIGHLIGHTER))),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(
                "Rnote always draws highlighter below ink, so highlighting that currently covers " +
                    "ink will move behind it.",
            ),
            exportWarnings(document),
        )
    }

    @Test
    fun `a highlighter below the ink is already where rnote puts it`() {
        val document = Document(
            pages = listOf(
                page(
                    layers = listOf(
                        Layer(listOf(stroke(Tool.HIGHLIGHTER))),
                        Layer(listOf(stroke(Tool.PEN))),
                    ),
                ),
            ),
        )
        assertEquals(emptyList<String>(), exportWarnings(document))
    }

    @Test
    fun `mixed page sizes are reported`() {
        val document = Document(
            pages = listOf(page(), page(height = 1000.0)),
        )
        assertEquals(
            listOf(
                "Pages have different sizes; Rnote uses one page size, so reopening may split " +
                    "pages differently.",
            ),
            exportWarnings(document),
        )
    }

    @Test
    fun `an eraser stroke is reported`() {
        val document = Document(pages = listOf(page(layers = listOf(Layer(listOf(stroke(Tool.ERASER)))))))
        assertEquals(listOf("Eraser strokes are not saved in .rnote."), exportWarnings(document))
    }

    @Test
    fun `raw elements and latex boxes are counted and pluralised`() {
        val one = Document(
            pages = listOf(page(layers = listOf(Layer(listOf(RawElement("vendor:thing"), texImage()))))),
        )
        assertEquals(
            listOf(
                "1 unrecognised element from the original file is not saved in .rnote.",
                "1 LaTeX box is saved as a plain image; the LaTeX source is lost.",
            ),
            exportWarnings(one),
        )

        val many = Document(
            pages = listOf(
                page(
                    layers = listOf(
                        Layer(listOf(RawElement("vendor:thing"), RawElement("vendor:other"), texImage())),
                        Layer(listOf(texImage())),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(
                "2 unrecognised elements from the original file are not saved in .rnote.",
                "2 LaTeX boxes are saved as plain images; the LaTeX source is lost.",
            ),
            exportWarnings(many),
        )
    }

    @Test
    fun `a picture Rnote cannot store is reported, and a PNG is not`() {
        // Rnote holds raw pixels, so only what RawImageCodec can decode actually crosses.
        val png = ImageElement(0.0, 0.0, 10.0, 10.0, pngPixel())
        assertEquals(emptyList<String>(), exportWarnings(documentOf(png)))

        val jpeg = ImageElement(0.0, 0.0, 10.0, 10.0, jpegBytes())
        assertEquals(
            listOf("1 picture is in a format Rnote cannot store and will not be saved."),
            exportWarnings(documentOf(jpeg)),
        )
        assertEquals(
            listOf("2 pictures are in a format Rnote cannot store and will not be saved."),
            exportWarnings(documentOf(jpeg, png, jpeg)),
        )
    }

    @Test
    fun `a LaTeX box with no rendering is reported as lost, not as an image`() {
        val unrendered = texImage().copy(data = null)
        assertEquals(
            listOf("1 LaTeX box has no saved rendering and will not be saved."),
            exportWarnings(documentOf(unrendered)),
        )
        assertEquals(
            listOf(
                "1 LaTeX box is saved as a plain image; the LaTeX source is lost.",
                "2 LaTeX boxes have no saved rendering and will not be saved.",
            ),
            exportWarnings(documentOf(texImage(), unrendered, unrendered)),
        )
    }

    @Test
    fun `an audio link on a stroke or a text box is reported`() {
        val audioStroke = stroke(Tool.PEN).copy(extraAttrs = mapOf("fn" to "rec.wav", "ts" to "1200"))
        val audioText = text().copy(extraAttrs = mapOf("fn" to "rec.wav", "ts" to "0"))
        val expected = listOf("Audio recording links are not saved in .rnote.")
        assertEquals(expected, exportWarnings(documentOf(audioStroke)))
        assertEquals(expected, exportWarnings(documentOf(audioText)))
    }

    @Test
    fun `an empty audio filename is not a link`() {
        val unlinked = stroke(Tool.PEN).copy(extraAttrs = mapOf("fn" to "", "ts" to "0"))
        assertEquals(emptyList<String>(), exportWarnings(documentOf(unlinked)))
    }

    @Test
    fun `a layer name rnote cannot hold is reported`() {
        val named = Document(pages = listOf(page(layers = listOf(Layer(emptyList(), name = "Ink")))))
        assertEquals(listOf("Layer names are not saved in .rnote."), exportWarnings(named))
    }

    @Test
    fun `slot names and unnamed layers survive as they are`() {
        val slots = Document(
            pages = listOf(
                page(
                    layers = listOf(
                        Layer(emptyList()),
                        Layer(emptyList(), name = "document"),
                        Layer(emptyList(), name = "highlighter"),
                        Layer(emptyList(), name = "user_layer 3"),
                    ),
                ),
            ),
        )
        assertEquals(emptyList<String>(), exportWarnings(slots))
    }

    // ---- fixtures ------------------------------------------------------------------------

    private fun fixture(path: String): Document = javaClass.classLoader!!
        .getResourceAsStream(path)
        ?.use { Xopp.parseXml(GZIPInputStream(it).readBytes().decodeToString()) }
        ?: error("missing fixture $path")

    private fun page(
        background: Background = Background.Solid(color = 0xFFFFFFFF.toInt(), style = "plain"),
        layers: List<Layer> = emptyList(),
        height: Double = 841.89,
    ) = Page(width = 595.28, height = height, background = background, layers = layers)

    private fun documentOf(vararg elements: Element) =
        Document(pages = listOf(page(layers = listOf(Layer(elements.toList())))))

    private fun stroke(tool: Tool) = Stroke(
        tool = tool,
        color = 0xFF000000.toInt(),
        capStyle = null,
        points = listOf(StrokePoint(0.0, 0.0, 1.0)),
        uniformWidth = true,
    )

    private fun text() = TextElement(
        font = "Sans",
        size = 12.0,
        x = 10.0,
        y = 10.0,
        color = 0xFF000000.toInt(),
        content = "hello",
    )

    /** A LaTeX box the desktop already rendered, so it exports as that picture. */
    private fun texImage() = TexImageElement(
        left = 0.0,
        top = 0.0,
        right = 20.0,
        bottom = 10.0,
        latex = "x^2",
        color = 0xFF000000.toInt(),
        data = pngPixel(),
    )

    /** A 1x1 opaque PNG — the smallest picture `RawImageCodec` will decode back to raw pixels. */
    private fun pngPixel() = RawImageCodec.encodePng(ByteArray(4) { 0xFF.toByte() }, 1, 1)

    /** A JPEG's opening marker: nothing in the format layer decodes it, so it cannot be exported. */
    private fun jpegBytes() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
}
