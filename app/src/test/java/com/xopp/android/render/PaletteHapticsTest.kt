package com.xopp.android.render

import com.xopp.android.ui.PaletteAction
import com.xopp.android.ui.RadialHit
import com.xopp.android.ui.RadialRing
import com.xopp.android.ui.RadialSlot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteHapticsTest {

    private fun slot(index: Int, action: PaletteAction? = PaletteAction.Undo) =
        RadialHit.Slot(RadialSlot(RadialRing.INNER, index), action)

    @Test
    fun `crossing into a different slot ticks`() {
        assertTrue(PaletteHaptics.shouldTick(slot(0), slot(1)))
        assertTrue(PaletteHaptics.shouldTick(RadialHit.Inert, slot(0)))
    }

    @Test
    fun `staying on the same slot or falling into the hollow centre stays silent`() {
        assertFalse(PaletteHaptics.shouldTick(slot(0), slot(0)))
        assertFalse(PaletteHaptics.shouldTick(slot(0), RadialHit.Inert))
        assertFalse(PaletteHaptics.shouldTick(RadialHit.Inert, RadialHit.Inert))
    }

    @Test
    fun `an empty slot still ticks, since the highlight really did move`() {
        assertTrue(PaletteHaptics.shouldTick(slot(0), slot(1, action = null)))
    }

    @Test
    fun `only a commit that runs an action confirms`() {
        assertTrue(PaletteHaptics.shouldConfirm(slot(0)))
        assertFalse(PaletteHaptics.shouldConfirm(slot(0, action = null)))
        assertFalse(PaletteHaptics.shouldConfirm(RadialHit.Inert))
    }
}
