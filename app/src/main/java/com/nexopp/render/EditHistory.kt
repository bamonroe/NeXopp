package com.nexopp.render

/**
 * A generic undo/redo history over immutable snapshots of type [T]. Pure and Android-free so it's
 * unit-testable; [DrawingSurfaceView] parameterises it with the whole `Document`. Each edit calls
 * [record] with the pre-edit snapshot; [undo]/[redo] move between branches. Recording a new edit
 * discards the redo branch, as in every editor.
 */
class EditHistory<T>(private val maxDepth: Int = DEFAULT_MAX_DEPTH) {
    companion object {
        /**
         * How many undo steps to keep. Snapshots are structurally shared immutable documents, so a
         * step costs little more than the elements the edit touched; this is deep enough to cover a
         * long drawing session while still bounding memory on a tablet.
         */
        const val DEFAULT_MAX_DEPTH = 200
    }

    init {
        require(maxDepth > 0) { "maxDepth must be positive" }
    }

    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    /** True when there is at least one undo step available. */
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    /** True when there is at least one redo step available. */
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Record that the state changed away from [before]; clears any redo branch. Once the history is
     * [maxDepth] deep the oldest step is dropped, so the newest edits are always undoable.
     * @param before State before the edit (to restore on undo).
     */
    fun record(before: T) {
        undoStack.addLast(before)
        while (undoStack.size > maxDepth) undoStack.removeFirst()
        redoStack.clear()
    }

    /**
     * The snapshot to restore for an undo, pushing [current] onto the redo branch; null if empty.
     * @param current Current state before undo.
     * @return Previous state to restore, or null if no undo available.
     */
    fun undo(current: T): T? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return prev
    }

    /**
     * The snapshot to restore for a redo, pushing [current] onto the undo branch; null if empty.
     * @param current Current state before redo.
     * @return Next state to restore, or null if no redo available.
     */
    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }

    /** Clear both undo and redo stacks. */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
