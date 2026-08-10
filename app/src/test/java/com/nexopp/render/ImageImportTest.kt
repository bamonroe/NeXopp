package com.nexopp.render

import com.nexopp.format.model.Background
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The model-shaping half of [ImageImport]; reading pixel dimensions needs a real `BitmapFactory`
 * and so belongs to the emulator, not the JVM suite.
 */
class ImageImportTest {

    @Test
    fun `an image becomes one page sized from its pixels`() {
        val doc = ImageImport.documentFor(1600, 900, "content://pics/1")
        assertEquals(1, doc.pages.size)
        val page = doc.pages.single()
        assertEquals(1600.0, page.width, 0.0)
        assertEquals(900.0, page.height, 0.0)
    }

    @Test
    fun `the page carries the image as an absolute pixmap background`() {
        val page = ImageImport.documentFor(10, 10, "content://pics/1").pages.single()
        val background = page.background as Background.Pixmap
        assertEquals(ABSOLUTE_DOMAIN, background.domain)
        assertEquals("content://pics/1", background.filename)
    }

    @Test
    fun `the page starts with a single empty layer to draw on`() {
        val page = ImageImport.documentFor(10, 10, "content://pics/1").pages.single()
        assertEquals(1, page.layers.size)
        assertEquals(emptyList<Any>(), page.layers.single().elements)
    }
}
