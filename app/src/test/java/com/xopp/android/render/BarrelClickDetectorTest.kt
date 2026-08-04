package com.xopp.android.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The barrel double-click recogniser: two presses inside the window, and nothing else. */
class BarrelClickDetectorTest {

    @Test fun singlePressDoesNotFire() {
        assertFalse(BarrelClickDetector(300).press(1_000))
    }

    @Test fun twoPressesInsideTheWindowFire() {
        val d = BarrelClickDetector(300)
        assertFalse(d.press(1_000))
        assertTrue(d.press(1_200))
    }

    @Test fun twoPressesOutsideTheWindowDoNotFire() {
        val d = BarrelClickDetector(300)
        assertFalse(d.press(1_000))
        assertFalse(d.press(1_400))
    }

    @Test fun theSecondPressBecomesTheNewFirstWhenTooSlow() {
        val d = BarrelClickDetector(300)
        d.press(1_000)
        assertFalse(d.press(1_400))
        assertTrue(d.press(1_500))
    }

    @Test fun aTripleClickFiresOnlyOnce() {
        val d = BarrelClickDetector(300)
        d.press(1_000)
        assertTrue(d.press(1_100))
        assertFalse(d.press(1_200))
    }

    @Test fun resetDropsThePendingFirstClick() {
        val d = BarrelClickDetector(300)
        d.press(1_000)
        d.reset()
        assertFalse(d.press(1_100))
    }
}
