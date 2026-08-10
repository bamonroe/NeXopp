package com.nexopp.tabs

import com.nexopp.format.model.Document
import com.nexopp.format.model.Layer
import com.nexopp.format.model.Page
import com.nexopp.format.model.Background
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tab list's ordering and selection rules — the part of tabs that has no canvas in it. */
class TabManagerTest {

    private fun tab(id: String) = OpenTab(id = id, title = id, document = doc())

    private fun doc() = Document(
        pages = listOf(Page(100.0, 100.0, Background.Solid(-1, "plain"), listOf(Layer(emptyList())))),
    )

    @Test
    fun `opening a tab appends it and makes it active`() {
        val m = TabManager()
        m.open(tab("a"))
        m.open(tab("b"))
        assertEquals(listOf("a", "b"), m.tabs.map { it.id })
        assertEquals(1, m.activeIndex)
        assertEquals("b", m.active?.id)
    }

    @Test
    fun `closing the active tab selects its left neighbour`() {
        val m = TabManager()
        listOf("a", "b", "c").forEach { m.open(tab(it)) }
        m.select(1)
        m.close(1)
        assertEquals(listOf("a", "c"), m.tabs.map { it.id })
        assertEquals("a", m.active?.id)
    }

    @Test
    fun `closing a tab left of the active one keeps the same tab showing`() {
        val m = TabManager()
        listOf("a", "b", "c").forEach { m.open(tab(it)) }
        m.select(2)
        m.close(0)
        assertEquals("c", m.active?.id)
    }

    @Test
    fun `closing a tab right of the active one keeps the same tab showing`() {
        val m = TabManager()
        listOf("a", "b", "c").forEach { m.open(tab(it)) }
        m.select(0)
        m.close(2)
        assertEquals("a", m.active?.id)
    }

    @Test
    fun `closing the first of two leaves the survivor showing`() {
        val m = TabManager()
        listOf("a", "b").forEach { m.open(tab(it)) }
        m.select(0)
        m.close(0)
        assertEquals("b", m.active?.id)
        assertEquals(0, m.activeIndex)
    }

    @Test
    fun `closing the last tab leaves an empty manager`() {
        val m = TabManager()
        m.open(tab("a"))
        m.close(0)
        assertTrue(m.isEmpty)
        assertNull(m.active)
    }

    @Test
    fun `an out-of-range close or select is ignored`() {
        val m = TabManager()
        m.open(tab("a"))
        assertNull(m.close(4))
        assertEquals(false, m.select(9))
        assertEquals(false, m.select(0)) // already showing
        assertEquals(1, m.tabs.size)
    }

    @Test
    fun `updateActive rewrites only the showing tab`() {
        val m = TabManager()
        listOf("a", "b").forEach { m.open(tab(it)) }
        m.updateActive { it.copy(title = "renamed") }
        assertEquals(listOf("a", "renamed"), m.tabs.map { it.title })
    }

    @Test
    fun `updateMatching rewrites every view of one document, showing or not`() {
        val m = TabManager()
        listOf("a", "b", "c").forEach { m.open(tab(it)) }
        m.updateMatching({ it.id != "b" }) { it.copy(title = it.title + "!") }
        assertEquals(listOf("a!", "b", "c!"), m.tabs.map { it.title })
    }

    @Test
    fun `move slides a tab to a later slot and the selection follows it`() {
        val m = TabManager()
        listOf("a", "b", "c").forEach { m.open(tab(it)) }
        m.select(0)
        assertEquals(true, m.move(0, 2))
        assertEquals(listOf("b", "c", "a"), m.tabs.map { it.title })
        assertEquals(2, m.activeIndex)
    }

    @Test
    fun `move to an earlier slot shifts the tabs it passes`() {
        val m = TabManager()
        listOf("a", "b", "c").forEach { m.open(tab(it)) }
        m.select(1)
        assertEquals(true, m.move(2, 0))
        assertEquals(listOf("c", "a", "b"), m.tabs.map { it.title })
        assertEquals(2, m.activeIndex) // still showing "b"
    }

    @Test
    fun `move ignores a no-op or out-of-range index`() {
        val m = TabManager()
        listOf("a", "b").forEach { m.open(tab(it)) }
        assertEquals(false, m.move(0, 0))
        assertEquals(false, m.move(0, 5))
        assertEquals(false, m.move(-1, 1))
        assertEquals(listOf("a", "b"), m.tabs.map { it.title })
    }

    @Test
    fun `a restored manager clamps a stale active index`() {
        val m = TabManager(listOf(tab("a")), activeIndex = 7)
        assertEquals(0, m.activeIndex)
    }
}
