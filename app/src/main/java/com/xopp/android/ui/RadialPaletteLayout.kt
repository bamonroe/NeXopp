package com.xopp.android.ui

import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the palette's parts land on screen.
 *
 * Like [RadialPalette] and the hit test this is plain Kotlin — the renderer
 * (`com.xopp.android.render.RadialPaletteRenderer`) only turns these numbers into `Canvas` calls, so
 * the placement rules (anchor clamping, slot centres) stay unit-testable on the JVM and match the
 * geometry the flick is measured against ([RadialPaletteGeometry]).
 */

/** A point in view pixels. */
data class RadialPoint(val x: Float, val y: Float)

/**
 * The anchor to actually draw at when the pen opened the menu at ([x], [y]) on a view of
 * [viewWidth] × [viewHeight].
 *
 * The whole outer ring must stay reachable, so the anchor is pulled in far enough from every edge
 * that a flick in any direction still lands on screen. A view too small to fit the menu centres it
 * rather than fighting itself.
 */
fun clampAnchor(
    x: Float,
    y: Float,
    viewWidth: Float,
    viewHeight: Float,
    geometry: RadialPaletteGeometry = RadialPaletteGeometry(),
    margin: Float = DEFAULT_ANCHOR_MARGIN,
): RadialPoint {
    val inset = geometry.outerRingRadius + margin
    return RadialPoint(clampAxis(x, viewWidth, inset), clampAxis(y, viewHeight, inset))
}

private fun clampAxis(value: Float, extent: Float, inset: Float): Float =
    if (extent < inset * 2f) extent / 2f else value.coerceIn(inset, extent - inset)

/** Distance from the anchor at which [ring]'s slot marks are drawn — the middle of its band. */
fun slotDrawRadius(ring: RadialRing, geometry: RadialPaletteGeometry = RadialPaletteGeometry()): Float =
    when (ring) {
        RadialRing.INNER -> (geometry.deadZoneRadius + geometry.innerRingRadius) / 2f
        RadialRing.OUTER -> (geometry.innerRingRadius + geometry.outerRingRadius) / 2f
    }

/** Where [slot]'s mark is drawn when the menu is anchored at ([anchorX], [anchorY]). */
fun RadialSlot.drawCenter(
    anchorX: Float,
    anchorY: Float,
    geometry: RadialPaletteGeometry = RadialPaletteGeometry(),
): RadialPoint {
    val radians = Math.toRadians(centerDegrees().toDouble())
    val r = slotDrawRadius(ring, geometry)
    return RadialPoint(
        anchorX + (sin(radians) * r).toFloat(),
        anchorY - (cos(radians) * r).toFloat(),
    )
}

/** How much room the anchor keeps beyond the outer ring, so the flick target isn't clipped. */
const val DEFAULT_ANCHOR_MARGIN = 16f
