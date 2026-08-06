/**
 * [DrawingSurfaceView]'s shared tuning constants ([DrawingSurfaceDefaults]) and the blank-document
 * factories every empty canvas starts from. Values only — no behaviour lives here, so the rest of
 * the `DrawingSurface*.kt` family can read a number without pulling in another file's logic.
 */
package com.xopp.android.render

import android.graphics.Color as AndroidColor
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page

/**
 * The tuning constants [DrawingSurfaceView] and its collaborators share: page geometry, zoom and
 * pinch limits, hit-test paddings and the guide-drag states. They live here — outside the view —
 * so the surface's split-out extension files and the helper classes around it (selection, guide
 * drag, page commands) can all read one copy.
 */
internal object DrawingSurfaceDefaults {
    const val A4_WIDTH_PT = 595.276
    const val A4_HEIGHT_PT = 841.89
    const val PAGE_SIZE_MIN_PT = 72.0     // 1 in — floor on a page dimension
    const val PAGE_SIZE_MAX_PT = 14400.0  // 200 in — ceiling on a page dimension
    const val GAP_PX = 24f
    const val ERASER_RADIUS_PX = 18f

    /** Document scroll per mouse-wheel notch, in dp (roughly three text lines). */
    const val WHEEL_SCROLL_DP = 64f

    /** Highlighter width as a multiple of the pen's base width: broad, flat, and pressure-independent. */
    const val HIGHLIGHTER_WIDTH_FACTOR = 6f
    const val ZOOM_STEP = ViewportState.ZOOM_STEP
    const val MIN_ZOOM = ViewportState.MIN_ZOOM
    const val MAX_ZOOM = ViewportState.MAX_ZOOM

    /** Below this two-finger span (view px) the pinch ratio is too noisy to zoom by, so it's ignored. */
    const val PINCH_MIN_SPAN_PX = 40f

    /**
     * How long two fingers may rest before their lift stops counting as a palette tap. Kept
     * short (well under the long-press timeout) so a deliberate two-finger pan never trips it.
     */
    const val TWO_FINGER_TAP_MS = 250L
    const val TAP_SLOP_PX = 16f
    const val SELECT_PAD_PX = 6f
    const val MOVE_GRAB_PAD = 8.0
    const val HANDLE_HIT_PX = 30f      // touch radius for grabbing a resize/rotate handle
    const val ROTATE_ARM_PX = 40f      // gap from the right edge out to the rotate knob
    const val MIN_RESIZE = 0.05        // clamp on the live uniform-resize factor
    const val MAX_RESIZE = 20.0
    const val PASTE_OFFSET_PT = 12.0   // paste/duplicate nudge so copies don't hide the original

    // Which part of the guide a finger is holding.
    const val GUIDE_DRAG_NONE = 0
    const val GUIDE_DRAG_BODY = 1
    const val GUIDE_DRAG_TIP = 2
}

/** A fresh one-page document — what a new tab starts on (see `com.xopp.android.tabs`). */
internal fun blankDocument() = Document(pages = listOf(blankPage()))

/** A fresh blank A4 page: white, graph-ruled, one empty layer. */
internal fun blankPage() = Page(
    DrawingSurfaceDefaults.A4_WIDTH_PT,
    DrawingSurfaceDefaults.A4_HEIGHT_PT,
    Background.Solid(AndroidColor.WHITE, "graph"),
    listOf(Layer(emptyList())),
)
