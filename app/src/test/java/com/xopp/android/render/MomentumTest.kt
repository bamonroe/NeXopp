package com.xopp.android.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The momentum velocity→coast response ([Momentum.seed]) and its selectable [MomentumCurve] shapes.
 * Coast distance is proportional to the seed speed, so these assertions on seed magnitude are
 * assertions on how far a flick glides.
 */
class MomentumTest {

    private val ref = Momentum.REFERENCE_SPEED_PX

    private fun speed(p: Pair<Float, Float>) = hypot(p.first, p.second)

    private fun seedSpeed(releaseSpeed: Float, strength: Float, curve: MomentumCurve) =
        speed(Momentum.seed(releaseSpeed, 0f, strength, curve))

    @Test fun everyCurveSharesTheReferencePoint() {
        // factor(1) == 1: a reference-speed flick at NORMAL coasts at its own speed on every curve.
        for (curve in MomentumCurve.values()) {
            assertEquals("curve=$curve", ref, seedSpeed(ref, Momentum.NORMAL, curve), 1e-1f)
        }
    }

    @Test fun everyCurveIsZeroAtNoMotionOrOff() {
        for (curve in MomentumCurve.values()) {
            assertEquals("curve=$curve", 0f, seedSpeed(4000f, Momentum.OFF, curve), 1e-4f)
            assertEquals("curve=$curve", 0f, speed(Momentum.seed(0f, 0f, Momentum.NORMAL, curve)), 1e-4f)
        }
    }

    @Test fun linearCoastsInStepWithSpeed() {
        assertEquals(ref / 2f, seedSpeed(ref / 2f, Momentum.NORMAL, MomentumCurve.LINEAR), 1e-2f)
        assertEquals(ref * 2f, seedSpeed(ref * 2f, Momentum.NORMAL, MomentumCurve.LINEAR), 1e-2f)
    }

    @Test fun quadraticCoastGrowsWithSquareOfSpeed() {
        // Half the reference → quarter the coast (0.5²); double → 4× (2²).
        assertEquals(ref / 4f, seedSpeed(ref / 2f, Momentum.NORMAL, MomentumCurve.QUADRATIC), 1e-2f)
        assertEquals(ref * 4f, seedSpeed(ref * 2f, Momentum.NORMAL, MomentumCurve.QUADRATIC), 1e-2f)
    }

    @Test fun cubicCoastGrowsWithCubeOfSpeed() {
        assertEquals(ref * 8f, seedSpeed(ref * 2f, Momentum.NORMAL, MomentumCurve.CUBIC), 1e-1f)
    }

    @Test fun aggressivenessOrdersLinearQuadraticCubicExponentialAboveReference() {
        // At twice the reference speed each curve rewards the fast flick more than the last.
        val fast = ref * 2f
        val lin = seedSpeed(fast, Momentum.NORMAL, MomentumCurve.LINEAR)
        val quad = seedSpeed(fast, Momentum.NORMAL, MomentumCurve.QUADRATIC)
        val cube = seedSpeed(fast, Momentum.NORMAL, MomentumCurve.CUBIC)
        val exp = seedSpeed(fast, Momentum.NORMAL, MomentumCurve.EXPONENTIAL)
        assertTrue("linear<quadratic", lin < quad)
        assertTrue("quadratic<cubic", quad < cube)
        assertTrue("cubic<exponential", cube < exp)
    }

    @Test fun belowReferenceHigherCurvesDampMore() {
        // Below the reference speed the ordering flips: sharper curves suppress small flicks harder.
        val slow = ref / 2f
        val lin = seedSpeed(slow, Momentum.NORMAL, MomentumCurve.LINEAR)
        val quad = seedSpeed(slow, Momentum.NORMAL, MomentumCurve.QUADRATIC)
        val cube = seedSpeed(slow, Momentum.NORMAL, MomentumCurve.CUBIC)
        assertTrue("cubic<quadratic", cube < quad)
        assertTrue("quadratic<linear", quad < lin)
    }

    @Test fun strengthScalesEveryCurveLinearly() {
        for (curve in MomentumCurve.values()) {
            val normal = seedSpeed(3000f, Momentum.NORMAL, curve)
            val strong = seedSpeed(3000f, 5f, curve)
            assertEquals("curve=$curve", 5f, strong / normal, 1e-2f)
        }
    }

    @Test fun preservesDirection() {
        // Quadratic: speed 5000 → scale 2.5, so (3000,4000) → (7500,10000).
        val s = Momentum.seed(3000f, 4000f, Momentum.NORMAL, MomentumCurve.QUADRATIC)
        assertEquals(7500f, s.first, 1e-2f)
        assertEquals(10000f, s.second, 1e-2f)
    }
}
