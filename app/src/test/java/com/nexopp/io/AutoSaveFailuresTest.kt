package com.nexopp.io

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "tell them once" rule for a target that keeps refusing autosaves. */
class AutoSaveFailuresTest {
    private val a = "content://dropbox/document/aaaa"
    private val b = "content://dropbox/document/bbbb"

    @Test
    fun `a short run of failures stays silent`() {
        val failures = AutoSaveFailures(threshold = 3)
        assertFalse(failures.noteFailure(a))
        assertFalse(failures.noteFailure(a))
    }

    @Test
    fun `the third failure against the same target is reported, and only that one`() {
        val failures = AutoSaveFailures(threshold = 3)
        failures.noteFailure(a)
        failures.noteFailure(a)
        assertTrue(failures.noteFailure(a))
        assertFalse(failures.noteFailure(a))
        assertFalse(failures.noteFailure(a))
    }

    @Test
    fun `a save that lands starts the count over`() {
        val failures = AutoSaveFailures(threshold = 2)
        failures.noteFailure(a)
        assertTrue(failures.noteFailure(a))
        failures.noteSaved()
        assertFalse(failures.noteFailure(a))
        assertTrue(failures.noteFailure(a))
    }

    @Test
    fun `switching target restarts the count rather than inheriting it`() {
        val failures = AutoSaveFailures(threshold = 2)
        failures.noteFailure(a)
        assertFalse(failures.noteFailure(b))
        assertTrue(failures.noteFailure(b))
    }

    @Test
    fun `a target already reported is reported again after coming back`() {
        val failures = AutoSaveFailures(threshold = 1)
        assertTrue(failures.noteFailure(a))
        assertFalse(failures.noteFailure(a))
        assertTrue(failures.noteFailure(b))
        assertTrue(failures.noteFailure(a))
    }
}
