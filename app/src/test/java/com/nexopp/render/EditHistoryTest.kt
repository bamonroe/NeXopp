package com.nexopp.render

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

    @Test fun depthCapDropsOldestStepsAndKeepsNewestUndoable() {
        val h = EditHistory<Int>(maxDepth = 3)
        repeat(5) { h.record(it) } // records 0,1,2,3,4; only 2,3,4 survive
        assertEquals(4, h.undo(5))
        assertEquals(3, h.undo(4))
        assertEquals(2, h.undo(3))
        assertFalse(h.canUndo)
        // the whole dropped branch is still redoable back to where we started
        assertEquals(3, h.redo(2))
        assertEquals(4, h.redo(3))
        assertEquals(5, h.redo(4))
    }

    @Test fun defaultDepthIsDeepEnoughForALongSession() {
        val h = EditHistory<Int>()
        repeat(EditHistory.DEFAULT_MAX_DEPTH) { h.record(it) }
        repeat(EditHistory.DEFAULT_MAX_DEPTH) { h.undo(0) }
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
