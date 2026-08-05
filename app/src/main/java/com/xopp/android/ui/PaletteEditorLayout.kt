package com.xopp.android.ui

import kotlin.math.hypot

/**
 * The geometry behind the palette editor's diagram — the two rings drawn in the PALETTE settings
 * page so a slot can be picked with a tap instead of a flick.
 *
 * The editor shows the *same* palette the pen sees, so it reuses the canvas geometry
 * ([RadialPaletteGeometry], [RadialSlot.drawCenter]) rather than inventing a second layout; the
 * only difference is a uniform scale, because the diagram has to fit a settings card instead of a
 * full-screen canvas. Like the rest of the palette model this is plain Kotlin, so the placement
 * and the tap mapping are unit-testable on the JVM.
 */

/** One slot as the editor draws it: what it holds, where its mark lands, and how big that mark is. */
data class PaletteEditorSlot(
    val slot: RadialSlot,
    val action: PaletteAction?,
    val center: RadialPoint,
    val radius: Float,
)

/** The mark radius the canvas renderer uses for [ring], in unscaled canvas pixels. */
fun slotMarkRadius(ring: RadialRing): Float = when (ring) {
    RadialRing.INNER -> INNER_SLOT_MARK_RADIUS
    RadialRing.OUTER -> OUTER_SLOT_MARK_RADIUS
}

/**
 * How much to shrink the canvas geometry so the whole diagram — outer ring plus the marks sitting
 * on it — fits a square box of [sizePx] on a side.
 */
fun paletteEditorScale(sizePx: Float, geometry: RadialPaletteGeometry = RadialPaletteGeometry()): Float {
    val needed = geometry.outerRingRadius + INNER_SLOT_MARK_RADIUS
    return if (needed <= 0f || sizePx <= 0f) 0f else (sizePx / 2f) / needed
}

/**
 * Every slot of [this] palette placed inside a square box of [sizePx] on a side, centred in it —
 * inner ring first, the same order the canvas draws in ([RadialPalette.slots]).
 */
fun RadialPalette.editorSlots(
    sizePx: Float,
    geometry: RadialPaletteGeometry = RadialPaletteGeometry(),
): List<PaletteEditorSlot> {
    val scale = paletteEditorScale(sizePx, geometry)
    val scaled = geometry.scaledBy(scale)
    val center = sizePx / 2f
    return slots().map { (slot, action) ->
        PaletteEditorSlot(
            slot = slot,
            action = action,
            center = slot.drawCenter(center, center, scaled),
            radius = slotMarkRadius(slot.ring) * scale,
        )
    }
}

/**
 * The slot a tap at ([x], [y]) inside a box of [sizePx] selects, or `null` when the tap missed —
 * in the dead zone at the middle, or outside the outer ring. Unlike the flick hit test a tap is
 * not clamped outwards: a miss is a miss, since there is no gesture in flight to rescue.
 */
fun RadialPalette.editorSlotAt(
    x: Float,
    y: Float,
    sizePx: Float,
    geometry: RadialPaletteGeometry = RadialPaletteGeometry(),
): RadialSlot? {
    val scale = paletteEditorScale(sizePx, geometry)
    if (scale <= 0f) return null
    val scaled = geometry.scaledBy(scale)
    val center = sizePx / 2f
    val radius = hypot(x - center, y - center)
    if (radius <= scaled.deadZoneRadius) return null
    if (radius > scaled.outerRingRadius + OUTER_SLOT_MARK_RADIUS * scale) return null
    return (hitTest(center, center, x, y, scaled) as? RadialHit.Slot)?.slot
}

/**
 * The same geometry at [scale]. *Every* radius scales, dismiss ring included — leaving that one
 * unscaled let a wide diagram (a tablet's settings card) push the scaled outer ring past it, which
 * trips `RadialPaletteGeometry`'s "dismiss ring must lie outside the outer ring" check mid-draw.
 */
private fun RadialPaletteGeometry.scaledBy(scale: Float) = RadialPaletteGeometry(
    deadZoneRadius = deadZoneRadius * scale,
    innerRingRadius = innerRingRadius * scale,
    outerRingRadius = outerRingRadius * scale,
    dismissRadius = dismissRadius * scale,
)

/**
 * The mark radius for [ring] at [geometry]'s size — the base radius scaled by however much the
 * geometry itself has been scaled away from the canvas default. This is what makes a hit test on a
 * shrunken diagram (the settings editor) demand the same *relative* precision as the full-size menu.
 */
fun slotMarkRadius(ring: RadialRing, geometry: RadialPaletteGeometry): Float =
    slotMarkRadius(ring) * (geometry.outerRingRadius / RadialPaletteGeometry.DEFAULT_OUTER_RING_RADIUS)

/** Mirrors `CanvasChrome.PALETTE_SLOT_PX` — the inner ring's marks are the larger ones. */
const val INNER_SLOT_MARK_RADIUS = 20f

/** Mirrors `CanvasChrome.PALETTE_SLOT_OUTER_PX`; the outer ring packs twice as many marks. */
const val OUTER_SLOT_MARK_RADIUS = 15f
