package com.xopp.android.render

import com.xopp.android.format.model.Background
import kotlin.math.PI
import kotlin.math.round

/**
 * Pure snapping geometry — pulling a shape's endpoints onto the page background's ruling, and a
 * selection rotation onto a fixed angular step, the way desktop Xournal++ does. Kept free of
 * Android types so it is unit-testable on the JVM; [DrawingSurfaceView] applies it while a shape
 * drag or a rotate handle is live, gated on the user's settings toggles.
 *
 * A background only snaps along the axes it actually rules: `lined`/`ruled` sheets have horizontal
 * lines and so snap Y only, `graph`/`dotted` snap both, and a plain sheet snaps nothing.
 */
object Snapping {

    /** The rotation increment, in degrees, a snapped rotate handle lands on. */
    const val ROTATION_STEP_DEG: Double = 15.0

    /** Horizontal ruling spacing (pt) for [background], or 0 when it rules no vertical lines. */
    fun spacingX(background: Background?): Double = when (styleOf(background)) {
        "graph", "dotted" -> BackgroundGrid.GRID_SPACING_PT
        else -> 0.0
    }

    /** Vertical ruling spacing (pt) for [background], or 0 when it rules no horizontal lines. */
    fun spacingY(background: Background?): Double = when (styleOf(background)) {
        "lined", "ruled" -> BackgroundGrid.RULE_SPACING_PT
        "graph", "dotted" -> BackgroundGrid.GRID_SPACING_PT
        else -> 0.0
    }

    /** [v] pulled to the nearest multiple of [spacing]; a non-positive spacing leaves it alone. */
    fun snap(v: Double, spacing: Double): Double =
        if (spacing <= 0.0) v else round(v / spacing) * spacing

    /** [radians] pulled to the nearest multiple of [stepDeg] degrees. */
    fun snapAngle(radians: Double, stepDeg: Double = ROTATION_STEP_DEG): Double {
        if (stepDeg <= 0.0) return radians
        val step = stepDeg * PI / 180.0
        return round(radians / step) * step
    }

    private fun styleOf(background: Background?): String? =
        (background as? Background.Solid)?.style
}
