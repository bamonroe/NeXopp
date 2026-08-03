package com.xopp.android.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only documents open in more than one tab get a dot, and their views share one colour. */
class DocColorsTest {

    @Test
    fun `a document open once gets no dot`() {
        assertEquals(emptyMap<String, Int>(), DocColors.assign(listOf("a", "b", "c")))
    }

    @Test
    fun `two views of one document share a colour`() {
        val dots = DocColors.assign(listOf("a", "b", "a"))
        assertEquals(DocColors.PALETTE[0], dots["a"])
        assertNull(dots["b"])
    }

    @Test
    fun `separate mirrored documents get separate colours`() {
        val dots = DocColors.assign(listOf("a", "b", "a", "b"))
        assertEquals(DocColors.PALETTE[0], dots["a"])
        assertEquals(DocColors.PALETTE[1], dots["b"])
    }

    @Test
    fun `more mirrored documents than colours wraps round the palette`() {
        val keys = (0..DocColors.PALETTE.size).flatMap { listOf("d$it", "d$it") }
        val dots = DocColors.assign(keys)
        assertEquals(DocColors.PALETTE.size + 1, dots.size)
        assertEquals(DocColors.PALETTE[0], dots["d${DocColors.PALETTE.size}"])
    }
}
