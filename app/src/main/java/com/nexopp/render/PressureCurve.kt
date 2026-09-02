package com.nexopp.render

import kotlin.math.pow

/**
 * Maps a raw pen pressure (0..1) onto a stroke-width multiplier. Pure and JVM-testable (see
 * `PressureCurveTest`); `DrawingSurfaceView` multiplies the pen's base width by [factor] at every
 * sample so pressure feeds width at the fidelity `.xopp` stores (round-trip safety starts here).
 *
 * The mapping is `min + (max - min) · pressure^gamma`:
 *  - `gamma = 1` is the historical linear response (`0.4 + 0.6·pressure` with the defaults below).
 *  - `gamma < 1` (soft) reaches full width with lighter pressure; `gamma > 1` (firm) needs a
 *    harder press — the "sensitivity" setting picks the exponent via [PressureSensitivity].
 */
object PressureCurve {

    /** Width floor as a fraction of base width, so a zero-pressure sample still leaves a visible line. */
    // Desktop Xournal++ multiplies the nominal width by the raw pressure and only clamps it just
    // above zero, so its strokes taper far more than a 0.4 floor allows. 0.25 gets close to that
    // look while still leaving a light touch visible on digitisers that under-report pressure.
    const val MIN = 0.25f

    /** Width ceiling as a fraction of base width, at full pressure. */
    const val MAX = 1.0f

    /** Multiplier for [baseWidth]-scaled width from a raw [pressure], shaped by [gamma]. */
    fun factor(pressure: Float, gamma: Float = 1f, min: Float = MIN, max: Float = MAX): Float {
        val p = pressure.coerceIn(0f, 1f)
        val shaped = if (gamma == 1f) p else p.toDouble().pow(gamma.toDouble()).toFloat()
        return min + (max - min) * shaped
    }
}

/**
 * The user-facing **Line thickness** setting: what fraction of the pen's nominal width a
 * constant-width shape or spline draws at. Pure and JVM-testable (see `PressureCurveTest`).
 *
 * This has to be a setting rather than a constant because the right value depends on how hard the
 * owner of the stylus presses. A shape has no pressure stream, so it draws at a flat width, while a
 * freehand stroke is scaled by [PressureCurve] the whole way along — someone with a light touch
 * spends their stroke near [PressureCurve.MIN] and needs a thinner shape to match, someone leaning
 * on the pen sits near [PressureCurve.MAX] and needs a thicker one. The slider lets each user land
 * it by eye instead of us guessing a constant.
 */
object ShapeWidth {
    /** Thinnest the slider goes: hairline shapes, at [STEP] of the pen's width. */
    const val MIN = 0f

    /** The default: shapes at 80% of the nominal pen width. */
    const val DEFAULT = 0.8f

    /** Widest the slider goes — twice the nominal pen width. */
    const val MAX = 2.0f

    /** Granularity the fraction snaps to — 5%, the persisted precision and the slider's grid. */
    const val STEP = 0.05f

    /** Clamp an arbitrary fraction into the valid [MIN]..[MAX] range. */
    fun coerce(value: Float): Float = if (value.isNaN()) DEFAULT else value.coerceIn(MIN, MAX)

    /** Snap to the [STEP] grid and clamp — mirrors [PanSensitivity.snap] for the continuous slider. */
    fun snap(value: Float): Float = coerce(kotlin.math.round(value / STEP) * STEP)

    /** The fraction as the whole-number percentage the settings UI shows (0…200). */
    fun percent(value: Float): Int = kotlin.math.round(coerce(value) * 100f).toInt()
}

/** The user-facing pressure "feel" presets, each an exponent for [PressureCurve.factor]. */
enum class PressureSensitivity(val gamma: Float, val label: String) {
    /** Reaches full width with a light touch. */
    SOFT(0.55f, "Soft"),

    /** The historical linear response. */
    LINEAR(1.0f, "Linear"),

    /** Needs a firmer press before the line thickens. */
    FIRM(1.8f, "Firm"),
}
