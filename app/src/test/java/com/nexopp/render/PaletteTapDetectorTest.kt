package com.nexopp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The two-finger palette tap: what counts as a tap, and everything that disqualifies one. */
class PaletteTapDetectorTest {

    private fun detector() = PaletteTapDetector(slopPx = 10f, timeoutMs = 250L)

    @Test
    fun `a still, quick two-finger tap opens midway between the fingers`() {
        val d = detector()
        d.start(1000L, 100f, 200f, 300f, 400f)
        val at = d.release(1100L)
        assertNotNull(at)
        assertEquals(200f, at!!.first, 0.001f)
        assertEquals(300f, at.second, 0.001f)
    }

    @Test
    fun `travel past the slop makes it a pan, not a tap`() {
        val d = detector()
        d.start(0L, 0f, 0f, 100f, 0f)
        d.move(0f, 40f, 100f, 40f)
        assertNull(d.release(100L))
    }

    @Test
    fun `a small wobble inside the slop still taps, and reports where the fingers ended`() {
        val d = detector()
        d.start(0L, 0f, 0f, 100f, 0f)
        d.move(2f, 2f, 102f, 2f)
        val at = d.release(100L)
        assertNotNull(at)
        assertEquals(52f, at!!.first, 0.001f)
    }

    @Test
    fun `holding past the timeout is a deliberate gesture, not a tap`() {
        val d = detector()
        d.start(0L, 0f, 0f, 100f, 0f)
        assertNull(d.release(400L))
    }

    @Test
    fun `a cancelled candidate never fires`() {
        val d = detector()
        d.start(0L, 0f, 0f, 100f, 0f)
        d.cancel()
        assertNull(d.release(50L))
    }

    @Test
    fun `one gesture fires at most once`() {
        val d = detector()
        d.start(0L, 0f, 0f, 100f, 0f)
        assertNotNull(d.release(50L))
        assertNull(d.release(60L))
    }

    @Test
    fun `moves before any candidate are ignored`() {
        val d = detector()
        d.move(500f, 500f, 600f, 600f)
        assertNull(d.release(10L))
    }
}
