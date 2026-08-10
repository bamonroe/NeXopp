package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Test

/** The y-flip and crop-origin math that lands `.xopp` (top-left) points in PDF (bottom-left) space. */
class PdfPageTransformTest {

    @Test fun flipsYAboutPageHeightWithZeroOrigin() {
        val t = PdfPageTransform(0f, 0f, 800f)
        assertEquals(0f, t.x(0.0), 1e-4f)
        assertEquals(800f, t.y(0.0), 1e-4f)      // model top → PDF top edge
        assertEquals(0f, t.y(800.0), 1e-4f)      // model bottom → PDF origin
        assertEquals(500f, t.y(300.0), 1e-4f)
    }

    @Test fun offsetsByCropBoxLowerLeft() {
        val t = PdfPageTransform(10f, 20f, 800f)
        assertEquals(15f, t.x(5.0), 1e-4f)       // originX + x
        assertEquals(20f + 800f, t.y(0.0), 1e-4f) // originY + (height - 0)
        assertEquals(20f + 700f, t.y(100.0), 1e-4f)
    }
}
