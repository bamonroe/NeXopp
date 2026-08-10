package com.nexopp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ToolGroupsTest {

    @Test
    fun `every tool belongs to exactly one group`() {
        for (tool in EditorTool.entries) {
            assertEquals("group count for $tool", 1, TOOL_GROUPS.count { tool in it.tools })
            assertNotNull(groupOf(tool))
        }
    }

    @Test
    fun `group ids are unique`() {
        assertEquals(TOOL_GROUPS.size, TOOL_GROUPS.map { it.id }.toSet().size)
    }

    @Test
    fun `background select lives in the select group`() {
        assertEquals("select", groupOf(EditorTool.BG_SELECT)?.id)
    }

    @Test
    fun `selection defaults to the first member`() {
        val draw = groupOf(EditorTool.PEN)!!
        assertEquals(EditorTool.PEN, draw.selected(emptyMap()))
        assertEquals(EditorTool.HIGHLIGHTER, draw.selected(mapOf(draw.id to EditorTool.HIGHLIGHTER)))
    }

    @Test
    fun `a non-member selection is ignored on both write and read`() {
        val draw = groupOf(EditorTool.PEN)!!
        assertEquals(emptyMap<String, EditorTool>(), draw.withSelection(emptyMap(), EditorTool.ARROW))
        assertEquals(EditorTool.PEN, draw.selected(mapOf(draw.id to EditorTool.ARROW)))
    }

    @Test
    fun `selections round-trip through the preference encoding`() {
        val draw = groupOf(EditorTool.HIGHLIGHTER)!!
        val insert = groupOf(EditorTool.TEXIMAGE)!!
        val selections = mapOf(draw.id to EditorTool.HIGHLIGHTER, insert.id to EditorTool.TEXIMAGE)
        assertEquals(selections, decodeToolGroupSelections(encodeToolGroupSelections(selections)))
    }

    @Test
    fun `corrupt or stale preference entries are dropped`() {
        val draw = groupOf(EditorTool.PEN)!!
        val raw = "nosuchgroup:PEN,${draw.id}:NOSUCHTOOL,${draw.id}:ARROW,junk,${draw.id}:HIGHLIGHTER"
        assertEquals(mapOf(draw.id to EditorTool.HIGHLIGHTER), decodeToolGroupSelections(raw))
    }

    @Test
    fun `the starting tool honours the default tool's persisted group choice`() {
        val draw = groupOf(EditorTool.PEN)!!
        assertEquals(EditorTool.PEN, startingTool(EditorTool.PEN, emptyMap()))
        assertEquals(
            EditorTool.HIGHLIGHTER,
            startingTool(EditorTool.PEN, mapOf(draw.id to EditorTool.HIGHLIGHTER)),
        )
        // A choice in another group doesn't move the starting tool.
        val shape = groupOf(EditorTool.RECTANGLE)!!
        assertEquals(EditorTool.PEN, startingTool(EditorTool.PEN, mapOf(shape.id to EditorTool.ELLIPSE)))
    }

    @Test
    fun `an empty preference decodes to no selections`() {
        assertEquals(emptyMap<String, EditorTool>(), decodeToolGroupSelections(null))
        assertEquals(emptyMap<String, EditorTool>(), decodeToolGroupSelections(""))
    }
}
