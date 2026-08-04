package com.xopp.android.render

import com.xopp.android.ui.RadialHit

/**
 * When the open radial palette should buzz. The flick happens under the user's hand — often with
 * the pen off the glass — so a tactile tick is the only confirmation that the highlight moved.
 * Kept as pure functions so the policy is unit-testable without a View or a vibrator.
 */
object PaletteHaptics {

    /**
     * True when moving the pen from [previous] to [next] should fire a tick: only real slot
     * changes count, so sliding around inside one slot — or through the dead zone — stays silent.
     */
    fun shouldTick(previous: RadialHit, next: RadialHit): Boolean =
        next is RadialHit.Slot && next != previous

    /** True when committing on [hit] should fire the stronger confirm buzz — an empty slot won't. */
    fun shouldConfirm(hit: RadialHit): Boolean = (hit as? RadialHit.Slot)?.action != null
}
