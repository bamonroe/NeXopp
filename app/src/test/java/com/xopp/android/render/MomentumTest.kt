package com.xopp.android.render

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.hypot

/**
 * The momentum velocity→coast response ([Momentum.seed]). Coast distance is proportional to the seed
 * speed, so these assertions on seed magnitude are assertions on how far a flick glides: the response
 * is *quadratic* in release speed, giving a tiny flick almost nothing and a fast swipe a long coast.
 */
class MomentumTest {

    private fun speed(p: Pair<Float, Float>) = hypot(p.first, p.second)

    @Test fun referenceSpeedAtNormalSeedsAtReleaseSpeed() {
        // The linear reference point: a REFERENCE_SPEED_PX flick at NORMAL coasts at its own speed.
        val s = Momentum.seed(Momentum.REFERENCE_SPEED_PX, 0f, Momentum.NORMAL)
        assertEquals(Momentum.REFERENCE_SPEED_PX, s.first, 1e-2f)
        assertEquals(0f, s.second, 1e-2f)
    }

    @Test fun slowFlickIsDampedQuadratically() {
        // Half the reference speed → quarter the seed speed (0.5²), so a small flick barely coasts.
        val s = Momentum.seed(Momentum.REFERENCE_SPEED_PX / 2f, 0f, Momentum.NORMAL)
        assertEquals(Momentum.REFERENCE_SPEED_PX / 4f, s.first, 1e-2f)
    }

    @Test fun fastFlickIsAmplifiedQuadratically() {
        // Double the reference speed → 4× the seed speed (2²), so a fast swipe flies far.
        val s = Momentum.seed(Momentum.REFERENCE_SPEED_PX * 2f, 0f, Momentum.NORMAL)
        assertEquals(Momentum.REFERENCE_SPEED_PX * 4f, s.first, 1e-2f)
    }

    @Test fun coastGrowsWithSquareOfReleaseSpeed() {
        val tiny = speed(Momentum.seed(500f, 0f, Momentum.NORMAL))
        val huge = speed(Momentum.seed(5000f, 0f, Momentum.NORMAL))
        // 10× the release speed → 100× the coast: the wide dynamic range a linear scale couldn't give.
        assertEquals(100f, huge / tiny, 1e-1f)
    }

    @Test fun strengthScalesTheSeedLinearly() {
        val normal = speed(Momentum.seed(3000f, 0f, Momentum.NORMAL))
        val strong = speed(Momentum.seed(3000f, 0f, 5f))
        assertEquals(5f, strong / normal, 1e-3f)
    }

    @Test fun preservesDirection() {
        val s = Momentum.seed(3000f, 4000f, Momentum.NORMAL) // speed 5000, scale 2.5
        assertEquals(7500f, s.first, 1e-2f)
        assertEquals(10000f, s.second, 1e-2f)
    }

    @Test fun zeroAtOffOrNoMotion() {
        assertEquals(0f, speed(Momentum.seed(4000f, 0f, Momentum.OFF)), 1e-4f)
        assertEquals(0f, speed(Momentum.seed(0f, 0f, Momentum.NORMAL)), 1e-4f)
    }
}
