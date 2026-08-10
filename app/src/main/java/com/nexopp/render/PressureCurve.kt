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

/** The user-facing pressure "feel" presets, each an exponent for [PressureCurve.factor]. */
enum class PressureSensitivity(val gamma: Float, val label: String) {
    /** Reaches full width with a light touch. */
    SOFT(0.55f, "Soft"),

    /** The historical linear response. */
    LINEAR(1.0f, "Linear"),

    /** Needs a firmer press before the line thickens. */
    FIRM(1.8f, "Firm"),
}
