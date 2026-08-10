package com.nexopp.ui

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Turning a pointer position into a palette slot.
 *
 * The menu is anchored where the gesture opened it; the flick that follows is read purely as a
 * direction and a distance from that anchor. Like [RadialPalette] this is plain Kotlin with no
 * Compose or Android types, so the whole mapping is unit-testable on the JVM and the renderer and
 * the gesture handler can share one definition of "which slot is the pen over".
 *
 * Distance picks the ring, angle picks the slot within it:
 *
 * - inside [RadialPaletteGeometry.deadZoneRadius] — the hollow centre: no choice, and releasing
 *   here does nothing;
 * - out to [RadialPaletteGeometry.innerRingRadius] — the inner ring's 8 slots;
 * - out to [RadialPaletteGeometry.outerRingRadius] — the outer ring's 16;
 * - beyond [RadialPaletteGeometry.dismissRadius] — off the menu entirely: releasing there
 *   dismisses it. The dismiss radius sits on the drawn outer border by default, so there is no
 *   invisible margin that still counts as "on the menu".
 *
 * Ring and angle only name the *candidate* slot: the pointer must then land inside that slot's
 * drawn mark ([slotMarkRadius]) for it to count as a hit. Anywhere else on the menu reads as
 * [RadialHit.Outside], so selection always takes a direct hit and the gaps between icons dismiss.
 *
 * Angles are measured from 12 o'clock and increase clockwise, matching the order the slots are
 * stored in ([RadialPalette.ring]). Each slot is *centred* on its angle, so slot 0 straddles
 * straight up and its wedge wraps across 0°/360°.
 */

/** The radii, in pixels, that separate the dead zone from the two rings. */
data class RadialPaletteGeometry(
    val deadZoneRadius: Float = DEFAULT_DEAD_ZONE_RADIUS,
    val innerRingRadius: Float = DEFAULT_INNER_RING_RADIUS,
    val outerRingRadius: Float = DEFAULT_OUTER_RING_RADIUS,
    val dismissRadius: Float = DEFAULT_DISMISS_RADIUS,
) {
    init {
        require(deadZoneRadius >= 0f) { "dead zone radius must not be negative" }
        require(innerRingRadius > deadZoneRadius) { "inner ring must lie outside the dead zone" }
        require(outerRingRadius > innerRingRadius) { "outer ring must lie outside the inner ring" }
        require(dismissRadius >= outerRingRadius) { "dismiss ring must not lie inside the outer ring" }
    }

    companion object {
        /**
         * The rings hug their icons: each band is only as wide as the marks it carries plus a
         * small breathing gap, so the menu covers roughly a third of the area it used to. The
         * floor is the *angular* packing — 16 outer slots of radius
         * [OUTER_SLOT_MARK_RADIUS] need a mid-band radius past ~76px before they touch — so
         * these can't shrink much further without dropping slots.
         */
        const val DEFAULT_DEAD_ZONE_RADIUS = 32f
        const val DEFAULT_INNER_RING_RADIUS = 100f
        const val DEFAULT_OUTER_RING_RADIUS = 160f

        /**
         * How far out still counts as being on the menu. It sits exactly on the drawn outer
         * border: anything past the visible edge is *off* the menu, which is how the menu is
         * dismissed now that picking a slot leaves it open.
         */
        const val DEFAULT_DISMISS_RADIUS = DEFAULT_OUTER_RING_RADIUS
    }
}

/** Where a pointer currently sits over the menu. */
sealed interface RadialHit {
    /**
     * In the hollow centre: no slot is targeted and releasing there does nothing at all. The menu
     * reads as a donut, so its inert middle is not a hidden dismiss button.
     */
    data object Inert : RadialHit

    /** Clear of the whole menu: releasing here is the "click off it" that closes it. */
    data object Outside : RadialHit

    /** Over [slot]; [action] is what it holds, `null` when the slot is empty. */
    data class Slot(val slot: RadialSlot, val action: PaletteAction?) : RadialHit
}

/**
 * The slot under a pointer at ([x], [y]) when the menu is anchored at ([anchorX], [anchorY]),
 * in the same pixel coordinate space with y growing downwards (Android's).
 */
fun RadialPalette.hitTest(
    anchorX: Float,
    anchorY: Float,
    x: Float,
    y: Float,
    geometry: RadialPaletteGeometry = RadialPaletteGeometry(),
): RadialHit {
    val dx = x - anchorX
    val dy = y - anchorY
    val radius = hypot(dx, dy)
    if (radius <= geometry.deadZoneRadius) return RadialHit.Inert
    if (radius > geometry.dismissRadius) return RadialHit.Outside

    val ring = if (radius <= geometry.innerRingRadius) RadialRing.INNER else RadialRing.OUTER
    val index = slotIndexAt(clockwiseDegrees(dx, dy), ring)
    val slot = RadialSlot(ring, index)
    if (!slot.covers(anchorX, anchorY, x, y, geometry)) return RadialHit.Outside
    return RadialHit.Slot(slot, this[slot])
}

/**
 * Whether ([x], [y]) lands on [this] slot's drawn mark rather than merely in its wedge. The ring
 * bands are far wider than the icons that sit in them, so a nearest-wedge match alone selected a
 * slot from well away from anything visible; requiring the point to fall inside the mark itself
 * makes every selection a direct hit and leaves the rest of the ring free to dismiss the menu.
 */
internal fun RadialSlot.covers(
    anchorX: Float,
    anchorY: Float,
    x: Float,
    y: Float,
    geometry: RadialPaletteGeometry,
): Boolean {
    val c = drawCenter(anchorX, anchorY, geometry)
    return hypot(x - c.x, y - c.y) <= slotMarkRadius(ring, geometry)
}

/**
 * The bearing of the offset ([dx], [dy]) in degrees clockwise from straight up, in `[0, 360)`.
 * Straight up is negative dy because the coordinate space is screen-space.
 */
internal fun clockwiseDegrees(dx: Float, dy: Float): Float {
    val degrees = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
    return (degrees + 360f) % 360f
}

/** Which slot of [ring] the bearing [degrees] falls in, with slot 0 centred on 12 o'clock. */
internal fun slotIndexAt(degrees: Float, ring: RadialRing): Int {
    val wedge = 360f / ring.slotCount
    val shifted = (degrees + wedge / 2f + 360f) % 360f
    return floor(shifted / wedge).toInt().coerceIn(0, ring.slotCount - 1)
}

/** The centre angle of [slot], in degrees clockwise from 12 o'clock — what the renderer draws at. */
fun RadialSlot.centerDegrees(): Float = index * (360f / ring.slotCount)
