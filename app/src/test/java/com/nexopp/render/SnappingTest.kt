package com.nexopp.render

import com.nexopp.format.model.Background
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

/** Grid/rotation snapping: the ruling a style snaps to, and the rounding itself. */
class SnappingTest {

    private fun solid(style: String) = Background.Solid(0xFFFFFFFF.toInt(), style)

    @Test fun graphSnapsBothAxesToTheGridSpacing() {
        assertEquals(BackgroundGrid.GRID_SPACING_PT, Snapping.spacingX(solid("graph")), 1e-9)
        assertEquals(BackgroundGrid.GRID_SPACING_PT, Snapping.spacingY(solid("graph")), 1e-9)
    }

    @Test fun dottedSnapsBothAxesLikeGraph() {
        assertEquals(BackgroundGrid.GRID_SPACING_PT, Snapping.spacingX(solid("dotted")), 1e-9)
        assertEquals(BackgroundGrid.GRID_SPACING_PT, Snapping.spacingY(solid("dotted")), 1e-9)
    }

    @Test fun linedSnapsVerticallyOnlyBecauseItRulesNoVerticals() {
        assertEquals(0.0, Snapping.spacingX(solid("lined")), 1e-9)
        assertEquals(BackgroundGrid.RULE_SPACING_PT, Snapping.spacingY(solid("lined")), 1e-9)
        assertEquals(BackgroundGrid.RULE_SPACING_PT, Snapping.spacingY(solid("ruled")), 1e-9)
    }

    @Test fun plainUnknownAndNonSolidBackgroundsSnapNothing() {
        for (b in listOf(solid("plain"), solid("weird"), Background.Pdf("a.pdf", 1, "absolute"), null)) {
            assertEquals(0.0, Snapping.spacingX(b), 1e-9)
            assertEquals(0.0, Snapping.spacingY(b), 1e-9)
        }
    }

    @Test fun snapRoundsToTheNearestMultiple() {
        assertEquals(20.0, Snapping.snap(23.0, 10.0), 1e-9)
        assertEquals(30.0, Snapping.snap(26.0, 10.0), 1e-9)
        assertEquals(-30.0, Snapping.snap(-27.0, 10.0), 1e-9)
        assertEquals(0.0, Snapping.snap(4.9, 10.0), 1e-9)
    }

    @Test fun snapLeavesValuesAloneWhenSpacingIsNonPositive() {
        assertEquals(23.4, Snapping.snap(23.4, 0.0), 1e-9)
        assertEquals(23.4, Snapping.snap(23.4, -5.0), 1e-9)
    }

    @Test fun snapAngleLandsOnFifteenDegreeSteps() {
        val step = 15.0 * PI / 180.0
        assertEquals(step, Snapping.snapAngle(step * 1.2), 1e-9)
        assertEquals(0.0, Snapping.snapAngle(step * 0.4), 1e-9)
        assertEquals(-2 * step, Snapping.snapAngle(-step * 1.9), 1e-9)
        assertEquals(PI / 2, Snapping.snapAngle(PI / 2 + 0.01), 1e-9)
    }

    @Test fun snapAngleWithANonPositiveStepIsAPassThrough() {
        assertEquals(0.37, Snapping.snapAngle(0.37, 0.0), 1e-9)
    }
}
