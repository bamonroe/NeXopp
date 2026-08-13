package com.nexopp.format.rnote

/**
 * The import-side layer rule that turns Rnote's per-stroke layer slots into the stacked `Layer`s
 * every page gets. See `docs/architecture.md`, "Decision (2026-08-12): canvas ↔ pages, layers &
 * backgrounds" — this file is the layer half of that rule, as [RnotePagination] is the page half.
 * It works on slot names alone, so it knows nothing about pages or the document model.
 */

/** The slot-name prefix for Rnote's numbered pen layers. */
private const val USER_LAYER_PREFIX = "user_layer "

/** Where a slot we don't recognise sorts: above every known one, so it is never dropped. */
private const val UNKNOWN_ORDER = Int.MAX_VALUE

/**
 * The layer slot a stroke belongs on, as a name we can also read back on export.
 *
 * @param stroke The stroke, carrying Rnote's own `layer` / `user_layer` pair.
 * @return `user_layer n` for a pen stroke (layer 0 when the number is missing), else the slot name
 *   verbatim — including one a newer Rnote invented.
 */
fun layerSlotName(stroke: RnoteStroke): String =
    if (stroke.layer == "user_layer") USER_LAYER_PREFIX + (stroke.userLayer ?: 0) else stroke.layer

/**
 * The fixed upstream z-order of a slot, bottom first, per `StrokeLayer: Ord`: `document`, `image`,
 * `highlighter`, `user_layer 0`, `user_layer 1`, … The **highlighter deliberately sits below** the
 * pen layers, which is why highlighting doesn't cover ink.
 *
 * @param slotName A name from [layerSlotName].
 * @return Its rank, ascending from 0 at the bottom; unknown names sort last.
 */
fun layerOrder(slotName: String): Int = when {
    slotName == "document" -> 0
    slotName == "image" -> 1
    slotName == "highlighter" -> 2
    slotName.startsWith(USER_LAYER_PREFIX) -> {
        val number = slotName.removePrefix(USER_LAYER_PREFIX).toIntOrNull()
        if (number == null || number < 0) UNKNOWN_ORDER else 3 + number.coerceAtMost(UNKNOWN_ORDER - 4)
    }
    else -> UNKNOWN_ORDER
}

/**
 * The layer stack for a whole canvas: one slot per distinct layer that actually has strokes, in
 * z-order. Every page gets this same stack, so page-to-page layer indices line up.
 *
 * @param strokes Every stroke on the canvas.
 * @return The slot names, bottom layer first; empty when there are no strokes.
 */
fun layerSlots(strokes: List<RnoteStroke>): List<String> =
    strokes.map(::layerSlotName).distinct().sortedBy(::layerOrder)
