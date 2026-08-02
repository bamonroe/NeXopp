package com.xopp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fill toggle + alpha pair behind the Style pop-up: `fillEnabled`/`fillAlpha` are what persist,
 * and the surface's `currentFill` is derived from them (null when off).
 */
class FillSettingTest {

    /** Mirrors the derivation in `EditorScreen`. */
    private fun AppSettings.currentFill(): Int? = if (fillEnabled) fillAlpha else null

    @Test
    fun `fill is off by default`() {
        assertNull(AppSettings().currentFill())
        assertEquals(DEFAULT_FILL_ALPHA, AppSettings().fillAlpha)
    }

    @Test
    fun `switching fill on yields the remembered alpha`() {
        val s = AppSettings(fillEnabled = true, fillAlpha = 200)
        assertEquals(200, s.currentFill())
    }

    @Test
    fun `switching fill off keeps the alpha for next time`() {
        val s = AppSettings(fillEnabled = true, fillAlpha = 200).copy(fillEnabled = false)
        assertNull(s.currentFill())
        assertEquals(200, s.fillAlpha)
    }

    @Test
    fun `alpha reads back as a rounded percentage`() {
        assertEquals(100, alphaPercent(255))
        assertEquals(50, alphaPercent(128))
        assertEquals(0, alphaPercent(1))
    }
}
