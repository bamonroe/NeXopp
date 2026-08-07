package com.xopp.android.render

import org.junit.Assert.assertEquals
import org.junit.Test

class InputClassifierTest {

    private val defaults = InputSettings()

    private fun classify(
        kind: PointerKind,
        tool: ActiveTool,
        barrel: Boolean = false,
        settings: InputSettings = defaults,
    ) = InputClassifier.classify(kind, barrel, tool, settings)

    // --- eraser tip wins over everything -------------------------------------------------------

    @Test fun eraserTipErasesRegardlessOfTool() {
        for (tool in ActiveTool.values()) {
            assertEquals("tool=$tool", GestureIntent.ERASE, classify(PointerKind.ERASER_TIP, tool))
        }
    }

    @Test fun eraserTipErasesEvenWithFingerDrawOffAndBarrelHeld() {
        val s = InputSettings(fingerDraws = false, barrelAction = BarrelAction.SELECT)
        assertEquals(GestureIntent.ERASE, classify(PointerKind.ERASER_TIP, ActiveTool.PEN, barrel = true, settings = s))
    }

    // --- barrel button override ----------------------------------------------------------------

    @Test fun barrelDefaultsToEraseWhileHeld() {
        assertEquals(GestureIntent.ERASE, classify(PointerKind.STYLUS, ActiveTool.PEN, barrel = true))
    }

    @Test fun barrelSelectOverridesTool() {
        val s = InputSettings(barrelAction = BarrelAction.SELECT)
        assertEquals(GestureIntent.SELECT, classify(PointerKind.STYLUS, ActiveTool.HIGHLIGHTER, barrel = true, settings = s))
    }

    @Test fun barrelNoneFallsThroughToTool() {
        val s = InputSettings(barrelAction = BarrelAction.NONE)
        assertEquals(GestureIntent.DRAW, classify(PointerKind.STYLUS, ActiveTool.PEN, barrel = true, settings = s))
    }

    @Test fun barrelOnlyAppliesToStylusNotFinger() {
        // A finger reporting a button state (e.g. a mouse) shouldn't hijack the tool.
        assertEquals(GestureIntent.DRAW, classify(PointerKind.FINGER, ActiveTool.PEN, barrel = true))
    }

    // --- finger-draw gate (palm safety for non-stylus use) -------------------------------------

    @Test fun fingerDrawOffMakesDrawingToolsPan() {
        val s = InputSettings(fingerDraws = false)
        assertEquals(GestureIntent.PAN, classify(PointerKind.FINGER, ActiveTool.PEN, settings = s))
        assertEquals(GestureIntent.PAN, classify(PointerKind.FINGER, ActiveTool.HIGHLIGHTER, settings = s))
        assertEquals(GestureIntent.PAN, classify(PointerKind.FINGER, ActiveTool.ERASER, settings = s))
    }

    @Test fun fingerDrawOffMakesEveryToolPan() {
        val s = InputSettings(fingerDraws = false)
        for (tool in ActiveTool.values()) {
            assertEquals("tool=$tool", GestureIntent.PAN, classify(PointerKind.FINGER, tool, settings = s))
        }
    }

    @Test fun fingerDrawOffDoesNotAffectStylus() {
        val s = InputSettings(fingerDraws = false)
        assertEquals(GestureIntent.DRAW, classify(PointerKind.STYLUS, ActiveTool.PEN, settings = s))
    }

    // --- default tool mapping (finger-draw on) -------------------------------------------------

    @Test fun toolMappingWithDefaults() {
        assertEquals(GestureIntent.DRAW, classify(PointerKind.STYLUS, ActiveTool.PEN))
        assertEquals(GestureIntent.DRAW, classify(PointerKind.FINGER, ActiveTool.HIGHLIGHTER))
        assertEquals(GestureIntent.ERASE, classify(PointerKind.STYLUS, ActiveTool.ERASER))
        assertEquals(GestureIntent.SELECT, classify(PointerKind.FINGER, ActiveTool.SELECT))
        assertEquals(GestureIntent.BACKGROUND_SELECT, classify(PointerKind.STYLUS, ActiveTool.BACKGROUND_SELECT))
        assertEquals(GestureIntent.PAN, classify(PointerKind.FINGER, ActiveTool.HAND))
        assertEquals(GestureIntent.PLACE, classify(PointerKind.STYLUS, ActiveTool.PLACE))
    }

    @Test fun unknownPointerDrawsLikeAStylus() {
        // A mouse (UNKNOWN) should draw, and is never treated as a palm.
        assertEquals(GestureIntent.DRAW, classify(PointerKind.UNKNOWN, ActiveTool.PEN, settings = InputSettings(fingerDraws = false)))
    }
}
