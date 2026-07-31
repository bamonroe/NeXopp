package com.xopp.android.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureCurveTest {

    @Test fun linearDefaultMatchesHistoricalMapping() {
        // The old hard-coded response was 0.4 + 0.6·pressure; gamma=1 must reproduce it exactly.
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals("p=$p", 0.4f + 0.6f * p, PressureCurve.factor(p, gamma = 1f), 1e-6f)
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
