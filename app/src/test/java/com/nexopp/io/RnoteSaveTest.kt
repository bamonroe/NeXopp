package com.nexopp.io

import com.nexopp.format.SaveFormat
import com.nexopp.format.model.Background
import com.nexopp.format.model.Document
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.Stroke
import com.nexopp.format.model.StrokePoint
import com.nexopp.format.model.Tool
import com.nexopp.format.rnote.writeRnote
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The save path for `.rnote` files, joined to the open path: [writeRnote] is what
 * `DocumentIo.encode` runs for [SaveFormat.RNOTE] and [readRnote] is what `DocumentIo.read` runs on
 * a [com.nexopp.format.FileKind.RNOTE] verdict, so writing with one and opening with the other is
 * exactly what a Save then a reopen does. `DocumentIo` itself needs a `ContentResolver`, so the two
 * halves are exercised here rather than through it.
 */
class RnoteSaveTest {

    private fun stroke(y: Double) = Stroke(
        tool = Tool.PEN,
        color = 0xFF3333CC.toInt(),
        capStyle = "round",
        points = listOf(StrokePoint(20.0, y, 1.2), StrokePoint(60.0, y + 20.0, 0.8)),
        uniformWidth = false,
    )

    private fun page(vararg elements: Stroke) = Page(
        width = 595.28,
        height = 841.89,
        background = Background.Solid(0xFFFFFFFF.toInt(), "plain"),
        layers = listOf(Layer(elements.toList(), "user_layer 0")),
    )

    /** Save, then open: the two calls `DocumentIo` makes, back to back. */
    private fun saveAndOpen(document: Document): LoadedFile.Doc {
        val bytes = ByteArrayOutputStream()
        writeRnote(document, bytes)
        return ByteArrayInputStream(bytes.toByteArray()).use { readRnote(it) }
    }

    @Test
    fun `a saved rnote reopens with its pages and strokes and stays sticky at RNOTE`() {
        val source = Document(pages = listOf(page(stroke(40.0), stroke(80.0)), page(stroke(40.0))))
        val loaded = saveAndOpen(source)

        assertEquals(SaveFormat.RNOTE, loaded.format)
        assertEquals(2, loaded.document.pages.size)
        assertEquals(3, loaded.document.pages.sumOf { p -> p.layers.sumOf { it.elements.size } })
        assertEquals(listOf(2, 1), loaded.document.pages.map { p -> p.layers.sumOf { it.elements.size } })
    }

    @Test
    fun `a saved rnote resolves nothing on the side`() {
        // The format has no PDF background and no pixmap reference, so both side tables stay empty
        // however the document was written.
        val loaded = saveAndOpen(Document(pages = listOf(page(stroke(40.0)))))
        assertEquals(null, loaded.pdf)
        assertEquals(false, loaded.missingPdf)
        assertTrue(loaded.images.isEmpty())
        assertEquals(false, loaded.missingImage)
    }
}
