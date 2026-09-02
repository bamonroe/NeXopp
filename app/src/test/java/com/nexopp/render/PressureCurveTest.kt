package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureCurveTest {

    @Test fun linearDefaultMatchesHistoricalMapping() {
        // The old hard-coded response was 0.4 + 0.6·pressure; gamma=1 must reproduce it exactly.
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val expected = PressureCurve.MIN + (PressureCurve.MAX - PressureCurve.MIN) * p
            assertEquals("p=$p", expected, PressureCurve.factor(p, gamma = 1f), 1e-6f)
        }
    }

    @Test fun clampsPressureToUnitRange() {
        assertEquals(PressureCurve.MIN, PressureCurve.factor(-1f), 1e-6f)
        assertEquals(PressureCurve.MAX, PressureCurve.factor(2f), 1e-6f)
    }

    @Test fun softReachesFullWidthSoonerThanFirm() {
        // At mid pressure a soft curve should give more width than a firm one.
        val mid = 0.5f
        val soft = PressureCurve.factor(mid, PressureSensitivity.SOFT.gamma)
        val firm = PressureCurve.factor(mid, PressureSensitivity.FIRM.gamma)
        assertTrue("soft=$soft firm=$firm", soft > firm)
    }

    @Test fun endpointsAreGammaInvariant() {
        for (s in PressureSensitivity.values()) {
            assertEquals(PressureCurve.MIN, PressureCurve.factor(0f, s.gamma), 1e-6f)
            assertEquals(PressureCurve.MAX, PressureCurve.factor(1f, s.gamma), 1e-6f)
        }
    }
}

class ShapeWidthTest {

    @Test fun coerceClampsToSliderRange() {
        assertEquals(ShapeWidth.MIN, ShapeWidth.coerce(-1f), 1e-6f)
        assertEquals(ShapeWidth.MAX, ShapeWidth.coerce(99f), 1e-6f)
        assertEquals(1.25f, ShapeWidth.coerce(1.25f), 1e-6f)
    }

    @Test fun coerceRecoversFromACorruptPreference() {
        // A NaN in SharedPreferences must not make every shape vanish — fall back to the default.
        assertEquals(ShapeWidth.DEFAULT, ShapeWidth.coerce(Float.NaN), 1e-6f)
    }

    @Test fun snapLandsOnTheStepGrid() {
        assertEquals(0.8f, ShapeWidth.snap(0.79f), 1e-6f)
        assertEquals(1.0f, ShapeWidth.snap(0.98f), 1e-6f)
        for (raw in listOf(0f, 0.37f, 1.11f, 2f)) {
            val snapped = ShapeWidth.snap(raw)
            assertEquals("raw=$raw", 0f, snapped / ShapeWidth.STEP % 1f, 1e-4f)
        }
    }

    @Test fun percentIsTheWholeNumberTheSliderShows() {
        assertEquals(80, ShapeWidth.percent(ShapeWidth.DEFAULT))
        assertEquals(0, ShapeWidth.percent(ShapeWidth.MIN))
        assertEquals(200, ShapeWidth.percent(ShapeWidth.MAX))
        assertEquals(145, ShapeWidth.percent(1.45f))
    }

    @Test fun defaultSitsInsideTheRange() {
        assertTrue(ShapeWidth.DEFAULT in ShapeWidth.MIN..ShapeWidth.MAX)
    }
}
