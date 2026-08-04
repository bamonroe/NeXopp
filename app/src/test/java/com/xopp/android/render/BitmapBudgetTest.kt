package com.xopp.android.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared budget's accounting and reclaim order — the point of the class is that two caches see
 * one bound, so these pin the cross-client behaviour rather than either cache's own policy.
 */
class BitmapBudgetTest {

    /** A client holding [held] bytes that gives back whatever it is asked for, up to what it has. */
    private class FakeClient(var held: Long) : BitmapBudget.Client {
        var calls = 0
        override fun trim(bytes: Long): Long {
            calls++
            val freed = minOf(held, bytes)
            held -= freed
            return freed
        }
    }

    @Test
    fun `charging under the total evicts nobody`() {
        val budget = BitmapBudget(1000)
        val a = FakeClient(0)
        budget.register(a)
        budget.charge(a, 400)
        assertEquals(400L, budget.used())
        assertEquals(600L, budget.headroom())
        assertEquals(0, a.calls)
    }

    @Test
    fun `one cache going over reclaims from the other`() {
        val budget = BitmapBudget(1000)
        val ink = FakeClient(600)
        val pdf = FakeClient(0)
        budget.register(ink)
        budget.register(pdf)
        budget.charge(ink, 600)
        budget.charge(pdf, 600) // 1200 charged: 200 over

        assertEquals(1000L, budget.used())
        assertEquals(400L, ink.held) // the other client paid, not the one that just allocated
        assertEquals(0, pdf.calls)
    }

    @Test
    fun `the charging cache pays only once the others are drained`() {
        val budget = BitmapBudget(1000)
        val ink = FakeClient(100)
        val pdf = FakeClient(900)
        budget.register(ink)
        budget.register(pdf)
        budget.charge(ink, 100)
        budget.charge(pdf, 900)
        budget.charge(pdf, 400) // 1400 charged: 400 over, ink can only give 100

        assertEquals(1000L, budget.used())
        assertEquals(0L, ink.held)
        assertTrue(pdf.calls > 0)
    }

    @Test
    fun `credit never drives the total negative`() {
        val budget = BitmapBudget(1000)
        val a = FakeClient(0)
        budget.register(a)
        budget.charge(a, 100)
        budget.credit(500)
        assertEquals(0L, budget.used())
    }

    @Test
    fun `an unregistered cache is no longer asked for memory`() {
        val budget = BitmapBudget(1000)
        val gone = FakeClient(800)
        val live = FakeClient(800)
        budget.register(gone)
        budget.register(live)
        budget.unregister(gone)
        budget.charge(live, 1600)
        assertEquals(0, gone.calls)
        assertEquals(1000L, budget.used())
    }

    @Test
    fun `per-entry ceiling is a share of the whole budget`() {
        val budget = BitmapBudget(64L shl 20)
        assertEquals(16L shl 20, budget.perEntryBytes(0.25))
    }
}
