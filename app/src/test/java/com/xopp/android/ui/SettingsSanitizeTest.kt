package com.xopp.android.ui

import com.xopp.android.render.PageStacker
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `SettingsStore.load` needs an Android `Context`, so what is checked here is the clamping it
 * applies to whatever the pref file holds — the part that keeps a corrupt or legacy value from
 * reaching a palette slot (which requires an in-range width and throws otherwise) or from making
 * the pages popup unreachable.
 */
class SettingsSanitizeTest {

    @Test fun outOfRangePenWidthsAreClamped() {
        val s = AppSettings(penWidths = listOf(-3f, 0f, 999f)).sanitized()
        assertEquals(listOf(PEN_WIDTH_MIN, PEN_WIDTH_MIN, PEN_WIDTH_MAX), s.penWidths)
    }

    @Test fun outOfRangeLastWidthIsClamped() {
        assertEquals(PEN_WIDTH_MAX, AppSettings(lastWidth = 1e6f).sanitized().lastWidth)
        assertEquals(PEN_WIDTH_MIN, AppSettings(lastWidth = -1f).sanitized().lastWidth)
    }

    @Test fun pageColumnsStayWithinTheChoicesTheUiOffers() {
        assertEquals(1, AppSettings(pageColumns = 0).sanitized().pageColumns)
        assertEquals(1, AppSettings(pageColumns = -7).sanitized().pageColumns)
        assertEquals(
            PageStacker.COLUMN_CHOICES.last(),
            AppSettings(pageColumns = 99).sanitized().pageColumns,
        )
    }

    @Test fun valuesAlreadyInRangeAreUntouched() {
        val s = AppSettings()
        assertEquals(s, s.sanitized())
    }
}
