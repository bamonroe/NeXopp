package com.xopp.android.render

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The desktop setsquare (geometry triangle) and compass, as pure page-space geometry.
 *
 * A guide is *purely an input aid*: it never becomes part of the document. While one is active,
 * every drawn vertex that falls within [GRAB_PT] of the guide's drawing edge is pulled onto it, so
 * a freehand stroke along the setsquare's hypotenuse comes out ruler-straight and a stroke swept
 * around the compass comes out as a clean arc. What lands in the `.xopp` file is an ordinary
 * stroke — which is why a guide has no place in the format and none is written there.
 *
 * Kept free of Android types so the projection maths is unit-testable on the JVM;
 * [DrawingSurfaceView] owns the live pose, draws the overlay and applies [project].
 */
sealed interface DrawingGuide {

    /** The guide's anchor in page-local pt — the point a drag moves. */
    val x: Double
    val y: Double

    /** [x]/[y] displaced by ([dx], [dy]) pt, everything else unchanged. */
    fun moved(dx: Double, dy: Double): DrawingGuide

    /**
     * ([px], [py]) pulled onto the guide's nearest drawing edge, or returned unchanged when it is
     * further than [GRAB_PT] away. The tolerance is what lets the pen leave the guide and keep
     * drawing freehand without switching the guide off.
     */
    fun project(px: Double, py: Double): Pair<Double, Double>

    /**
     * The 30/60/90 geometry triangle. [x]/[y] is the right-angle corner and [angle] (radians) the
     * direction of its long leg; the short leg runs perpendicular. Its three sides are all drawing
     * edges, so it rules the long side, the short side and the hypotenuse without being re-posed.
     */
    data class Setsquare(
        override val x: Double,
        override val y: Double,
        /** Length of the long leg in pt; the short leg is [SHORT_LEG_RATIO] of it. */
        val size: Double = DEFAULT_SIZE_PT,
        /** Rotation of the long leg, in radians, measured from page +X. */
        val angle: Double = 0.0,
    ) : DrawingGuide {

        /** The right-angle corner, the long-leg tip and the short-leg tip, in page pt. */
        fun corners(): List<Pair<Double, Double>> {
            val ux = cos(angle); val uy = sin(angle)
            return listOf(
                x to y,
                (x + ux * size) to (y + uy * size),
                (x - uy * size * SHORT_LEG_RATIO) to (y + ux * size * SHORT_LEG_RATIO),
            )
        }

        override fun moved(dx: Double, dy: Double): Setsquare = copy(x = x + dx, y = y + dy)

        /**
         * True when ([px], [py]) lies inside the triangle. This is the *grab* region, kept distinct
         * from the edges: you slide the setsquare by its body and rule along its outside, so a
         * stroke drawn against an edge never drags the instrument away with it.
         */
        fun contains(px: Double, py: Double): Boolean {
            val (a, b, c) = corners()
            // Same-side test: inside means all three edge cross-products share a sign.
            val d1 = cross(a, b, px, py)
            val d2 = cross(b, c, px, py)
            val d3 = cross(c, a, px, py)
            val anyNeg = d1 < 0 || d2 < 0 || d3 < 0
            val anyPos = d1 > 0 || d2 > 0 || d3 > 0
            return !(anyNeg && anyPos)
        }

        private fun cross(
            a: Pair<Double, Double>,
            b: Pair<Double, Double>,
            px: Double,
            py: Double,
        ): Double = (b.first - a.first) * (py - a.second) - (b.second - a.second) * (px - a.first)

        /** This setsquare re-posed so its long leg points at ([tx], [ty]), keeping its corner put. */
        fun aimedAt(tx: Double, ty: Double, snapAngle: Boolean): Setsquare {
            val raw = atan2(ty - y, tx - x)
            val len = hypot(tx - x, ty - y).coerceAtLeast(MIN_SIZE_PT)
            return copy(angle = if (snapAngle) Snapping.snapAngle(raw) else raw, size = len)
        }

        override fun project(px: Double, py: Double): Pair<Double, Double> {
            val c = corners()
            val edges = listOf(c[0] to c[1], c[0] to c[2], c[1] to c[2])
            var best: Pair<Double, Double>? = null
            var bestDist = GRAB_PT
            for ((a, b) in edges) {
                val q = closestOnSegment(px, py, a.first, a.second, b.first, b.second)
                val d = hypot(px - q.first, py - q.second)
                if (d < bestDist) { bestDist = d; best = q }
            }
            return best ?: (px to py)
        }

        companion object {
            /** Short leg as a fraction of the long leg — the 30/60/90 triangle's 1/√3. */
            const val SHORT_LEG_RATIO: Double = 0.5773502691896257
        }
    }

    /**
     * The compass: a circle of [radius] pt about the centre ([x], [y]). Its single drawing edge is
     * the circumference, so a swept stroke traces an arc of exactly that radius.
     */
    data class Compass(
        override val x: Double,
        override val y: Double,
        val radius: Double = DEFAULT_RADIUS_PT,
    ) : DrawingGuide {

        override fun moved(dx: Double, dy: Double): Compass = copy(x = x + dx, y = y + dy)

        /** This compass opened so its circumference passes through ([tx], [ty]). */
        fun openedTo(tx: Double, ty: Double): Compass =
            copy(radius = hypot(tx - x, ty - y).coerceAtLeast(MIN_SIZE_PT))

        override fun project(px: Double, py: Double): Pair<Double, Double> {
            val dx = px - x
            val dy = py - y
            val d = hypot(dx, dy)
            // Dead centre has no defined direction to push outward along — leave it alone.
            if (d < 1e-9) return px to py
            if (kotlin.math.abs(d - radius) > GRAB_PT) return px to py
            return (x + dx / d * radius) to (y + dy / d * radius)
        }
    }

    companion object {
        /** How near (pt) the pen must be to an edge for the guide to capture it. */
        const val GRAB_PT: Double = 18.0

        /** Long-leg length of a freshly placed setsquare, in pt (~2.5 in). */
        const val DEFAULT_SIZE_PT: Double = 180.0

        /** Radius of a freshly placed compass, in pt (~1.4 in). */
        const val DEFAULT_RADIUS_PT: Double = 100.0

        /** Smallest a guide can be dragged down to, so it never collapses to an unhittable point. */
        const val MIN_SIZE_PT: Double = 20.0

        /** The point on segment A→B nearest ([px], [py]); a degenerate segment returns A. */
        fun closestOnSegment(
            px: Double, py: Double,
            ax: Double, ay: Double, bx: Double, by: Double,
        ): Pair<Double, Double> {
            val vx = bx - ax
            val vy = by - ay
            val len2 = vx * vx + vy * vy
            if (len2 < 1e-12) return ax to ay
            val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0.0, 1.0)
            return (ax + t * vx) to (ay + t * vy)
        }
    }
}

/** Which guide overlay is on the canvas, if any — the user-facing choice behind [DrawingGuide]. */
enum class GuideKind(val label: String) {
    NONE("Off"),
    SETSQUARE("Setsquare"),
    COMPASS("Compass"),
    ;

    /** A freshly placed guide of this kind, centred on ([cx], [cy]) page pt; null for [NONE]. */
    fun place(cx: Double, cy: Double): DrawingGuide? = when (this) {
        NONE -> null
        SETSQUARE -> DrawingGuide.Setsquare(
            x = cx - DrawingGuide.DEFAULT_SIZE_PT / 2, y = cy + DrawingGuide.DEFAULT_SIZE_PT / 4,
        )
        COMPASS -> DrawingGuide.Compass(cx, cy)
    }
}
