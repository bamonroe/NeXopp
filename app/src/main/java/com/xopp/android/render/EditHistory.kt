package com.xopp.android.render

/**
 * A generic undo/redo history over immutable snapshots of type [T]. Pure and Android-free so it's
 * unit-testable; [DrawingSurfaceView] parameterises it with the whole `Document`. Each edit calls
 * [record] with the pre-edit snapshot; [undo]/[redo] move between branches. Recording a new edit
 * discards the redo branch, as in every editor.
 */
class EditHistory<T> {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Record that the state changed away from [before]; clears any redo branch. */
    fun record(before: T) {
        undoStack.addLast(before)
        redoStack.clear()
    }

    /** The snapshot to restore for an undo, pushing [current] onto the redo branch; null if empty. */
    fun undo(current: T): T? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return prev
    }

    /** The snapshot to restore for a redo, pushing [current] onto the undo branch; null if empty. */
    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
