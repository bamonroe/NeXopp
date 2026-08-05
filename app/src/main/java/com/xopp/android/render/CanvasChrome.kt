package com.xopp.android.render

import android.graphics.DashPathEffect
import com.xopp.android.format.XoppColor.withAlpha
import android.graphics.Paint
import android.graphics.Path

/**
 * Every non-document brush the canvas paints with: the backdrop behind the page stack, the
 * selection outline and its handles, the drag-select band, the setsquare/compass overlay, the
 * page-overview reorder/selection feedback, the stylus hover dot and the PDF-text highlight.
 *
 * These are chrome, not content — nothing here is ever written to the `.xopp`. Keeping them in one
 * holder is what lets [DrawingSurfaceView] stay about input and document state: the view asks for a
 * brush, and [applyChromeColors] is the single place the app's Material 3 scheme lands (the
 * `SurfaceView` sits outside the Compose tree, so the colours are pushed in from the hosting
 * composable — see `com.xopp.android.ui.theme.CanvasChromeColors`).
 */
internal class CanvasChrome {

    /** Backdrop behind the pages — replaced by the app's colour scheme via [applyChromeColors]. */
    var backdropColor = BACKDROP
        private set

    val selectionStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = SELECTION_COLOR
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    val selectionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SELECTION_FILL
    }
    val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SELECTION_COLOR
    }
    val handleArm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = SELECTION_COLOR
    }
    val bandFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BAND_FILL
    }

    // The setsquare/compass overlay: a solid outline (it is a physical-feeling instrument, not a
    // marquee, so no dashes) over a faint wash, plus filled grab handles.
    val guideStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = GUIDE_COLOR
    }
    val guideFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = GUIDE_COLOR.withAlpha(GUIDE_FILL_ALPHA)
    }
    val guideHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = GUIDE_COLOR
    }

    // Page-overview reorder feedback: the lifted page is washed out, the drop slot outlined.
    val pageLift = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x66FFFFFF
    }
    val pageDrop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = GUIDE_COLOR
    }
    // Page-overview multi-select: a picked page is tinted and outlined so the selection reads at a glance.
    val pageSelectFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = GUIDE_COLOR.withAlpha(PAGE_SELECT_FILL_ALPHA)
    }
    val pageSelect = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = GUIDE_COLOR
    }

    /** Hover dot; its colour tracks the pen, so it is set per frame rather than by the scheme. */
    val hover = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    /**
     * The eraser tip outline — a thin black ring at the rubber's true radius, so its boundary is
     * visible while hovering and while erasing. Fixed black: it marks a hole, not a colour.
     */
    val eraserOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0xFF000000.toInt()
    }
    val textSelect = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = TEXT_SELECT_FILL
    }

    // The radial palette: a dimmed disc under two rings of slot marks. Filled slots read solidly,
    // empty ones stay faint, and the slot the pen is over is ringed in the selection colour.
    val paletteScrim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PALETTE_SCRIM
    }
    val paletteRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = PALETTE_RING
    }
    val paletteSlot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PALETTE_SLOT
    }
    val paletteSlotEmpty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PALETTE_SLOT_EMPTY
    }
    val paletteHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = SELECTION_COLOR
    }
    /** Fills a slot's rail icon; the glyph paint below still draws the width numbers. */
    val paletteIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PALETTE_ICON
    }
    val paletteGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = PALETTE_GLYPH_PX
    }

    /** Scratch paths reused each frame so drawing the lasso/guide allocates nothing. */
    val lassoPath = Path()
    val guidePath = Path()

    /**
     * Repaint the chrome from the app's Material 3 colour scheme. The translucent fills keep their
     * original alphas so the washes stay faint enough to draw over.
     */
    fun applyColors(backdrop: Int, selection: Int, guide: Int) {
        backdropColor = backdrop
        selectionStroke.color = selection
        handle.color = selection
        handleArm.color = selection
        selectionFill.color = selection.withAlpha(SELECTION_FILL_ALPHA)
        bandFill.color = selection.withAlpha(BAND_FILL_ALPHA)
        guideStroke.color = guide
        guideHandle.color = guide
        guideFill.color = guide.withAlpha(GUIDE_FILL_ALPHA)
        paletteHighlight.color = selection
    }

    /** Tint the hover dot to match the pen colour, at the fixed hover alpha. */
    fun tintHover(penArgb: Int) {
        hover.color = penArgb.withAlpha(HOVER_ALPHA)
    }

    internal companion object {
        const val BACKDROP = 0xFF3A3A3A.toInt()
        const val SELECTION_COLOR = 0xFF2060E0.toInt()

        /** Outline colour of the setsquare/compass overlay — amber, so it reads as an instrument
         * laid on the page rather than as a selection. */
        const val GUIDE_COLOR = 0xFFE08A20.toInt()

        /** Alpha of the wash filling the setsquare's body — enough to see, faint enough to draw over. */
        const val GUIDE_FILL_ALPHA = 0x22
        const val PAGE_SELECT_FILL_ALPHA = 0x33
        /** Alpha of the wash inside a selection outline. */
        const val SELECTION_FILL_ALPHA = 0x14
        /** Alpha of the wash inside the drag-select band. */
        const val BAND_FILL_ALPHA = 0x22

        const val SELECTION_FILL = 0x142060E0
        const val BAND_FILL = 0x222060E0
        /** Translucent blue wash over selected PDF-text word boxes (like a text highlight). */
        const val TEXT_SELECT_FILL = 0x552196F3
        const val HOVER_ALPHA = 0xB0
        /** Drawn radius of a selection/guide handle dot. */
        const val HANDLE_DRAW_PX = 7f

        /** Wash darkening the page under the open palette, so the ring marks read over any ink. */
        const val PALETTE_SCRIM = 0x66101010
        const val PALETTE_RING = 0x40FFFFFF
        const val PALETTE_SLOT = 0xE0303030.toInt()
        /** Default fill for a slot's icon — preset slots override it with the preset's colour. */
        const val PALETTE_ICON = 0xFFFFFFFF.toInt()
        /** An unassigned slot: present enough to show the ring's shape, faint enough to ignore. */
        const val PALETTE_SLOT_EMPTY = 0x33FFFFFF
        const val PALETTE_GLYPH_PX = 20f
        /** Drawn radius of a slot mark, inner ring and outer. */
        const val PALETTE_SLOT_PX = 20f
        const val PALETTE_SLOT_OUTER_PX = 15f
    }
}
