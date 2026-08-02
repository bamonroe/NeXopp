package com.xopp.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RailItemsTest {

    @Test
    fun `rail item ids are unique`() {
        val ids = RAIL_ITEMS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every tool group has a rail item`() {
        val ids = RAIL_ITEMS.map { it.id }.toSet()
        assertTrue(TOOL_GROUPS.all { it.id in ids })
    }

    @Test
    fun `empty order is the factory order`() {
        assertEquals(RAIL_ITEMS, orderedRailItems(emptyList()))
    }

    @Test
    fun `a partial order keeps its items first and appends the rest`() {
        val ordered = orderedRailItems(listOf("pages", "draw"))
        assertEquals(listOf("pages", "draw"), ordered.take(2).map { it.id })
        assertEquals(RAIL_ITEMS.size, ordered.size)
        assertEquals(RAIL_ITEMS.toSet(), ordered.toSet())
    }

    @Test
    fun `unknown and duplicate ids are ignored`() {
        val ordered = orderedRailItems(listOf("nope", "zoom", "zoom"))
        assertEquals("zoom", ordered.first().id)
        assertEquals(RAIL_ITEMS.size, ordered.size)
    }

    @Test
    fun `hidden items are dropped from the visible rail`() {
        val visible = visibleRailItems(emptyList(), setOf("zoom", "pages"))
        assertEquals(RAIL_ITEMS.size - 2, visible.size)
        assertTrue(visible.none { it.id == "zoom" || it.id == "pages" })
    }

    @Test
    fun `moving an item swaps it with its neighbour`() {
        val moved = moveRailItem(emptyList(), 0, 1)
        val factory = RAIL_ITEMS.map { it.id }
        assertEquals(listOf(factory[1], factory[0]), moved.take(2))
        assertEquals(factory.size, moved.size)
    }

    @Test
    fun `a move off either end is a no-op`() {
        val factory = RAIL_ITEMS.map { it.id }
        assertEquals(factory, moveRailItem(emptyList(), 0, -1))
        assertEquals(factory, moveRailItem(emptyList(), factory.lastIndex, 1))
    }

    @Test
    fun `ids round-trip through encode and decode`() {
        val ids = listOf("pages", "draw", "zoom")
        assertEquals(ids, decodeRailIds(encodeRailIds(ids)))
        assertEquals(emptyList<String>(), decodeRailIds(null))
        assertEquals(listOf("draw"), decodeRailIds("draw,gone"))
    }

    @Test
    fun `shape recognition has a rail slot that is not a tool group`() {
        assertTrue(RAIL_ITEMS.any { it.id == "shapes" })
        assertEquals(null, toolGroupForRailItem("shapes"))
        // Existing installs stored an order without it; it must still appear on the rail.
        assertTrue(visibleRailItems(listOf("pages", "zoom"), emptySet()).any { it.id == "shapes" })
    }

    @Test
    fun `tool groups resolve from their rail id and panels do not`() {
        assertEquals(TOOL_GROUPS.first(), toolGroupForRailItem(TOOL_GROUPS.first().id))
        assertEquals(null, toolGroupForRailItem("zoom"))
    }
}
