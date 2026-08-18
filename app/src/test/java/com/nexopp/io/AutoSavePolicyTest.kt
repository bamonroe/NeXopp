package com.nexopp.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the two autosave timers say a document is owed a write. The timeline is fabricated, so this
 * covers the whole decision without a clock or a Looper — see [AutoSavePolicy].
 */
class AutoSavePolicyTest {

    private val loaded = 100_000L

    @Test
    fun `both timers off means nothing is ever due`() {
        val policy = AutoSavePolicy.OFF
        assertFalse(policy.isEnabled)
        assertNull(policy.dueAt(lastEditMs = loaded + 5_000, lastSaveMs = loaded))
    }

    @Test
    fun `a clean document is never due`() {
        val policy = AutoSavePolicy(idleSeconds = 30, intervalSeconds = 300)
        assertNull(policy.dueAt(lastEditMs = null, lastSaveMs = loaded))
    }

    @Test
    fun `an edit that predates the last save leaves the document clean`() {
        val policy = AutoSavePolicy(idleSeconds = 30, intervalSeconds = 300)
        assertNull(policy.dueAt(lastEditMs = loaded - 1, lastSaveMs = loaded))
    }

    @Test
    fun `the idle timer counts from the last edit`() {
        val policy = AutoSavePolicy(idleSeconds = 30)
        val edited = loaded + 5_000
        assertEquals(edited + 30_000, policy.dueAt(lastEditMs = edited, lastSaveMs = loaded))
    }

    @Test
    fun `a later edit pushes the idle deadline back`() {
        val policy = AutoSavePolicy(idleSeconds = 30)
        val first = policy.dueAt(lastEditMs = loaded + 1_000, lastSaveMs = loaded)!!
        val second = policy.dueAt(lastEditMs = loaded + 9_000, lastSaveMs = loaded)!!
        assertEquals(8_000L, second - first)
    }

    @Test
    fun `the interval timer counts from the last save, not the last edit`() {
        val policy = AutoSavePolicy(intervalSeconds = 300)
        // Still drawing 4 minutes in: the deadline stays pinned to the save, so it doesn't recede.
        assertEquals(loaded + 300_000, policy.dueAt(lastEditMs = loaded + 240_000, lastSaveMs = loaded))
    }

    @Test
    fun `with both on, whichever comes first wins`() {
        val policy = AutoSavePolicy(idleSeconds = 30, intervalSeconds = 300)
        // A pause right after opening: the idle timer is the earlier of the two.
        assertEquals(loaded + 31_000, policy.dueAt(lastEditMs = loaded + 1_000, lastSaveMs = loaded))
        // Editing continuously for five minutes: the interval fires despite the idle timer resetting.
        assertEquals(loaded + 300_000, policy.dueAt(lastEditMs = loaded + 299_000, lastSaveMs = loaded))
    }

    @Test
    fun `either timer alone counts as enabled`() {
        assertTrue(AutoSavePolicy(idleSeconds = 5).isEnabled)
        assertTrue(AutoSavePolicy(intervalSeconds = 60).isEnabled)
    }

    @Test
    fun `an overdue save reports no delay rather than a negative one`() {
        val policy = AutoSavePolicy(idleSeconds = 30)
        val edited = loaded + 1_000
        // Woken up long after the deadline (the app was in the background): fire at once.
        assertEquals(0L, policy.delayUntilDue(nowMs = loaded + 600_000, lastEditMs = edited, lastSaveMs = loaded))
    }

    @Test
    fun `a pending save reports the time still to run`() {
        val policy = AutoSavePolicy(idleSeconds = 30)
        val edited = loaded + 1_000
        assertEquals(
            25_000L,
            policy.delayUntilDue(nowMs = edited + 5_000, lastEditMs = edited, lastSaveMs = loaded),
        )
    }

    @Test
    fun `labels read as off, seconds, or whole minutes`() {
        assertEquals("Off", AutoSavePolicy.label(0))
        assertEquals("15 seconds", AutoSavePolicy.label(15))
        assertEquals("1 minute", AutoSavePolicy.label(60))
        assertEquals("5 minutes", AutoSavePolicy.label(300))
    }

    @Test
    fun `every offered choice is off or a positive number of seconds`() {
        (AutoSavePolicy.IDLE_CHOICES + AutoSavePolicy.INTERVAL_CHOICES).forEach {
            assertTrue("choice $it", it >= 0)
        }
        assertEquals(0, AutoSavePolicy.IDLE_CHOICES.first())
        assertEquals(0, AutoSavePolicy.INTERVAL_CHOICES.first())
    }

    @Test
    fun `an interval save that comes due mid-stroke fires once, when the stroke ends`() {
        val policy = AutoSavePolicy(intervalSeconds = 60)
        val gate = AutoSaveGate()
        var saves = 0
        val fire = { if (gate.request()) saves++ }

        gate.setStrokeInProgress(true)
        // The interval elapses with the pen still down: due, but held.
        assertEquals(0L, policy.delayUntilDue(nowMs = 61_000, lastEditMs = 10_000, lastSaveMs = 0))
        fire()
        assertEquals(0, saves)
        assertTrue(gate.isPending)

        // Pen lifts: exactly one save runs, and the gate is clear again.
        assertTrue(gate.setStrokeInProgress(false))
        saves++
        assertEquals(1, saves)
        assertFalse(gate.isPending)
        assertFalse(gate.setStrokeInProgress(false))
        assertEquals(1, saves)
    }

    @Test
    fun `a save that comes due with no stroke in progress runs straight away`() {
        val gate = AutoSaveGate()
        assertTrue(gate.request())
        assertFalse(gate.isPending)
    }

    @Test
    fun `an autosave runs only when every condition allows it`() {
        assertTrue(
            canAutoSave(
                hasSurface = true,
                blocking = false,
                alreadySaving = false,
                hasWritableTarget = true,
            )
        )
    }

    @Test
    fun `no canvas blocks the autosave`() {
        assertFalse(
            canAutoSave(
                hasSurface = false,
                blocking = false,
                alreadySaving = false,
                hasWritableTarget = true,
            )
        )
    }

    @Test
    fun `a blocking operation blocks the autosave`() {
        assertFalse(
            canAutoSave(
                hasSurface = true,
                blocking = true,
                alreadySaving = false,
                hasWritableTarget = true,
            )
        )
    }

    @Test
    fun `a save already in flight blocks the autosave`() {
        assertFalse(
            canAutoSave(
                hasSurface = true,
                blocking = false,
                alreadySaving = true,
                hasWritableTarget = true,
            )
        )
    }

    @Test
    fun `a scratch document with no writable target blocks the autosave`() {
        assertFalse(
            canAutoSave(
                hasSurface = true,
                blocking = false,
                alreadySaving = false,
                hasWritableTarget = false,
            )
        )
    }
}
