package com.xopp.android.render

import android.graphics.Canvas
import com.xopp.android.ui.PaletteAction
import com.xopp.android.ui.RadialHit
import com.xopp.android.ui.RadialPalette
import com.xopp.android.ui.RadialPaletteGeometry
import com.xopp.android.ui.RadialRing
import com.xopp.android.ui.RadialSlot
import com.xopp.android.ui.clampAnchor
import com.xopp.android.ui.drawCenter
import com.xopp.android.ui.face
import com.xopp.android.ui.slots

/**
 * Painting the radial palette over the canvas.
 *
 * All the placement arithmetic lives in `com.xopp.android.ui` (see `RadialPaletteLayout.kt` and
 * [com.xopp.android.ui.hitTest]) so what the pen flies over and what the eye sees are the same
 * numbers; this object only turns those into `Canvas` calls with [CanvasChrome]'s brushes.
 */
internal object RadialPaletteRenderer {

    /**
     * The open menu: the palette, where the gesture opened it, and the slot the pen is currently
     * over. [DrawingSurfaceView] keeps one of these while the barrel gesture is held.
     */
    data class Overlay(
        val palette: RadialPalette,
        val anchorX: Float,
        val anchorY: Float,
        val hit: RadialHit = RadialHit.Cancel,
        val geometry: RadialPaletteGeometry = RadialPaletteGeometry(),
    )

    /** Draw [overlay] on a view of [viewWidth] × [viewHeight], clamped to stay on screen. */
    fun draw(canvas: Canvas, chrome: CanvasChrome, overlay: Overlay, viewWidth: Float, viewHeight: Float) {
        val anchor = clampAnchor(overlay.anchorX, overlay.anchorY, viewWidth, viewHeight, overlay.geometry)
        val g = overlay.geometry
        canvas.drawCircle(anchor.x, anchor.y, g.outerRingRadius, chrome.paletteScrim)
        for (r in floatArrayOf(g.deadZoneRadius, g.innerRingRadius, g.outerRingRadius)) {
            canvas.drawCircle(anchor.x, anchor.y, r, chrome.paletteRing)
        }
        val hovered = (overlay.hit as? RadialHit.Slot)?.slot
        for ((slot, action) in overlay.palette.slots()) {
            drawSlot(canvas, chrome, anchor.x, anchor.y, slot, action, slot == hovered, g)
        }
    }

    private fun drawSlot(
        canvas: Canvas,
        chrome: CanvasChrome,
        anchorX: Float,
        anchorY: Float,
        slot: RadialSlot,
        action: PaletteAction?,
        hovered: Boolean,
        geometry: RadialPaletteGeometry,
    ) {
        val c = slot.drawCenter(anchorX, anchorY, geometry)
        val radius = if (slot.ring == RadialRing.INNER) {
            CanvasChrome.PALETTE_SLOT_PX
        } else {
            CanvasChrome.PALETTE_SLOT_OUTER_PX
        }
        if (action == null) {
            canvas.drawCircle(c.x, c.y, radius * EMPTY_SLOT_SCALE, chrome.paletteSlotEmpty)
        } else {
            val faceOf = action.face()
            val swatch = faceOf.swatchArgb
            if (swatch != null) {
                chrome.paletteSlot.color = swatch
                canvas.drawCircle(c.x, c.y, radius, chrome.paletteSlot)
                chrome.paletteSlot.color = CanvasChrome.PALETTE_SLOT
            } else {
                canvas.drawCircle(c.x, c.y, radius, chrome.paletteSlot)
                canvas.drawText(faceOf.glyph, c.x, c.y + GLYPH_BASELINE_OFFSET, chrome.paletteGlyph)
            }
        }
        if (hovered) canvas.drawCircle(c.x, c.y, radius + HOVER_RING_PX, chrome.paletteHighlight)
    }

    /** An empty slot draws as a smaller dot — it marks the position without inviting a flick. */
    private const val EMPTY_SLOT_SCALE = 0.35f

    /** Nudge the glyph down so it sits optically centred on the mark rather than on its baseline. */
    private const val GLYPH_BASELINE_OFFSET = 7f

    /** How far outside a slot mark its hover ring sits. */
    private const val HOVER_RING_PX = 5f
}
