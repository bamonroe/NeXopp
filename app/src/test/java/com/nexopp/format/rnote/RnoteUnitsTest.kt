package com.nexopp.format.rnote

import com.nexopp.format.XoppColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RnoteUnitsTest {

    @Test
    fun `points and pixels round trip across the 72 to 96 dpi scale`() {
        assertEquals(40.0, ptToPx(30.0), 1e-9)
        assertEquals(30.0, pxToPt(40.0), 1e-9)
        assertEquals(12.5, pxToPt(ptToPx(12.5)), 1e-9)
    }

    @Test
    fun `a rnote colour packs to the same ARGB int the xopp parser produces`() {
        val packed = RnoteColor(1.0, 1.0, 0.0, 0.5).toXopp()
        assertEquals(XoppColor.parse("#ffff0080"), packed)
    }

    @Test
    fun `unpacking an ARGB int round trips back to the same colour`() {
        val original = RnoteColor(1.0, 1.0, 0.0, 0.5)
        val back = rnoteColorOf(original.toXopp())
        assertEquals(1.0, back.r, 1e-9)
        assertEquals(1.0, back.g, 1e-9)
        assertEquals(0.0, back.b, 1e-9)
        assertEquals(0x80 / 255.0, back.a, 1e-9)
        assertEquals(original.toXopp(), back.toXopp())
    }

    @Test
    fun `channels outside the unit range are clamped`() {
        assertEquals(0xFFFF0000.toInt(), RnoteColor(2.0, -1.0, -0.5, 3.0).toXopp())
    }

    @Test
    fun `affineTranslation reads elements two and five`() {
        val affine = listOf(1.0, 0.0, 17.5, 0.0, 1.0, -4.25, 0.0, 0.0, 1.0)
        assertEquals(17.5 to -4.25, affineTranslation(affine))
    }

    @Test
    fun `affineTranslation rejects a matrix that is not nine elements`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            affineTranslation(listOf(1.0, 0.0, 0.0, 1.0))
        }
        assertEquals("malformed transform.affine", error.message)
    }
}
