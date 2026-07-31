package com.xopp.android.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    @Test fun startsEmpty() {
        val h = EditHistory<String>()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertNull(h.undo("a"))
        assertNull(h.redo("a"))
    }

    @Test fun recordThenUndoRestoresPreviousSnapshot() {
        val h = EditHistory<String>()
        h.record("a")        // edited a -> b
        assertTrue(h.canUndo)
        assertEquals("a", h.undo("b")) // restore a; b goes to redo
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)
    }

    @Test fun redoReappliesTheUndoneEdit() {
        val h = EditHistory<String>()
        h.record("a")
        assertEquals("a", h.undo("b"))
        assertEquals("b", h.redo("a")) // re-apply b; a goes back to undo
        assertTrue(h.canUndo)
        assertFalse(h.canRedo)
    }

    @Test fun recordingANewEditClearsTheRedoBranch() {
        val h = EditHistory<String>()
        h.record("a")
        h.undo("b")          // now redo branch holds b
        assertTrue(h.canRedo)
        h.record("a")        // a new edit a -> c
        assertFalse(h.canRedo)
        assertNull(h.redo("c"))
    }

    @Test fun undoWalksBackThroughMultipleEdits() {
        val h = EditHistory<String>()
        h.record("a") // a -> b
        h.record("b") // b -> c
        assertEquals("b", h.undo("c"))
        assertEquals("a", h.undo("b"))
        assertFalse(h.canUndo)
    }

    @Test fun clearDropsBothBranches() {
        val h = EditHistory<String>()
        h.record("a")
        h.undo("b")
        h.clear()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
    }
}
