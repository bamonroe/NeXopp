package com.xopp.android.render

/**
 * Pure geometry for page-background rulings — the evenly-spaced line/dot offsets a background
 * style needs, in the page's own units (pt). Kept free of Android types so it is unit-testable on
 * the JVM; [BackgroundRenderer] turns these offsets into canvas draws.
 */
object BackgroundGrid {

    /** Interior gridline offsets: `spacing, 2·spacing, …` strictly inside `(0, extent)`. */
    fun lines(extent: Double, spacing: Double): List<Double> {
        if (spacing <= 0.0 || extent <= 0.0) return emptyList()
        val out = ArrayList<Double>(((extent / spacing).toInt()).coerceAtLeast(0))
        var v = spacing
        while (v < extent) {
            out += v
            v += spacing
        }
        return out
    }
}
