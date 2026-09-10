package com.nexopp.ui

import com.nexopp.render.PaletteInvocation
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
    fun `no pen-tip gesture is the default`() {
        assertEquals(PaletteInvocation.NONE, AppSettings().paletteInvocation)
    }

    /** The barrel button has exactly one owner — the double-click setting — so it is absent here. */
    @Test
    fun `the setting never refers to the pen barrel button`() {
        assert(PaletteInvocation.entries.none { it.name.contains("BARREL") })
    }

    /**
     * Fingers reach the palette through the Touch section's tap gestures instead, so this setting
     * must offer none of its own — two owners of the two-finger tap is exactly the collision the
     * split was made to remove.
     */
    @Test
    fun `the setting offers no finger gesture`() {
        assert(PaletteInvocation.entries.none { it.name.contains("FINGER") })
    }

    @Test
    fun `every invocation round-trips through its stored name`() {
        for (invocation in PaletteInvocation.entries) {
            assertEquals(invocation, PaletteInvocation.valueOf(invocation.name))
        }
    }

    @Test
    fun `the setting is carried on AppSettings`() {
        val settings = AppSettings().copy(paletteInvocation = PaletteInvocation.PEN_TIP_LONG_PRESS)
        assertEquals(PaletteInvocation.PEN_TIP_LONG_PRESS, settings.paletteInvocation)
    }

    @Test
    fun `every invocation has a label for the settings screen`() {
        for (invocation in PaletteInvocation.entries) {
            assertNotNull(invocation.label)
            assert(invocation.label.isNotBlank())
        }
    }
}
