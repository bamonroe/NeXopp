package com.xopp.android.ui

import com.xopp.android.render.PaletteInvocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The palette-invocation setting. `SettingsStore` itself needs an Android `Context`, so what is
 * checked here is the part the store relies on: the default, and that every constant survives the
 * name→enum round trip the pref file stores it as (a renamed constant would silently reset users).
 */
class PaletteInvocationTest {

    @Test
    fun `barrel double-click is the default`() {
        assertEquals(PaletteInvocation.BARREL_DOUBLE_CLICK, AppSettings().paletteInvocation)
    }

    @Test
    fun `every invocation round-trips through its stored name`() {
        for (invocation in PaletteInvocation.entries) {
            assertEquals(invocation, PaletteInvocation.valueOf(invocation.name))
        }
    }

    @Test
    fun `the setting is carried on AppSettings`() {
        val settings = AppSettings().copy(paletteInvocation = PaletteInvocation.TWO_FINGER_TAP)
        assertEquals(PaletteInvocation.TWO_FINGER_TAP, settings.paletteInvocation)
    }

    @Test
    fun `every invocation has a label for the settings screen`() {
        for (invocation in PaletteInvocation.entries) {
            assertNotNull(invocation.label)
            assert(invocation.label.isNotBlank())
        }
    }
}
