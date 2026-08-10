package com.nexopp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The recently-used-colour list behind the pen picker's "Recent" row. */
class RecentColorsTest {

    @Test
    fun `most recent colour comes first`() {
        val s = AppSettings().withColorUsed(RED).withColorUsed(BLUE)
        assertEquals(listOf(BLUE, RED), s.recentColors)
        assertEquals(BLUE, s.lastColor)
    }

    @Test
    fun `re-picking a colour moves it to the front without duplicating`() {
        val s = AppSettings().withColorUsed(RED).withColorUsed(BLUE).withColorUsed(RED)
        assertEquals(listOf(RED, BLUE), s.recentColors)
    }

    @Test
    fun `the list is capped and drops the oldest`() {
        var s = AppSettings()
        val picked = (1..AppSettings.MAX_RECENT_COLORS + 3).map { 0xFF000000.toInt() or it }
        picked.forEach { s = s.withColorUsed(it) }
        assertEquals(AppSettings.MAX_RECENT_COLORS, s.recentColors.size)
        assertEquals(picked.takeLast(AppSettings.MAX_RECENT_COLORS).reversed(), s.recentColors)
    }

    private companion object {
        val RED = 0xFFE00000.toInt()
        val BLUE = 0xFF2060E0.toInt()
    }
}
