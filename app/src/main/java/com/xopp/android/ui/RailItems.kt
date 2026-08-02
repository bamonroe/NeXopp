package com.xopp.android.ui

/**
 * One button position on the tool rail — either a tool slot (a [ToolGroup]) or one of the popup
 * panels (colour, size, …). The Toolbar settings section reorders and hides these; [SideToolbar]
 * renders whatever [visibleRailItems] returns, in that order.
 */
data class RailItem(
    /** Stable key — the SharedPreferences identity of this position. Never rename. */
    val id: String,
    /** Human-readable name, shown in the Toolbar settings list. */
    val label: String,
)

/** The popup-panel positions, in factory order. Tool slots come from [TOOL_GROUPS]. */
val PANEL_RAIL_ITEMS: List<RailItem> = listOf(
    RailItem("color", "Colour"),
    RailItem("size", "Size"),
    RailItem("style", "Style"),
    RailItem("shapes", "Shape recognition"),
    RailItem("guides", "Guides"),
    RailItem("layers", "Layers"),
    RailItem("zoom", "Zoom"),
    RailItem("background", "Background"),
    RailItem("pages", "Pages"),
    RailItem("audio", "Audio"),
)

/**
 * Every rail position in factory order: the tool slots first, then the panels. Ids are unique across
 * both halves, which is what lets a single id list express the whole rail's order.
 */
val RAIL_ITEMS: List<RailItem> =
    TOOL_GROUPS.map { RailItem(it.id, it.label) } + PANEL_RAIL_ITEMS

/** The tool group behind a rail item, or null when the item is a popup panel. */
fun toolGroupForRailItem(id: String): ToolGroup? = TOOL_GROUPS.firstOrNull { it.id == id }

/**
 * The rail's positions in the user's [order]: known ids first in the order given, then any item the
 * saved order doesn't mention, appended in factory order. Appending rather than dropping is what
 * makes a rail item added in a later release still show up for an existing install.
 */
fun orderedRailItems(order: List<String>): List<RailItem> {
    val byId = RAIL_ITEMS.associateBy { it.id }
    val listed = order.distinct().mapNotNull { byId[it] }
    return listed + RAIL_ITEMS.filterNot { it in listed }
}

/** [orderedRailItems] minus the positions the user has hidden. */
fun visibleRailItems(order: List<String>, hidden: Set<String>): List<RailItem> =
    orderedRailItems(order).filterNot { it.id in hidden }

/**
 * [order] with the item at [index] moved by [delta] positions. Out-of-range moves are no-ops, and
 * the result is always a full explicit order so the next move has something stable to permute.
 */
fun moveRailItem(order: List<String>, index: Int, delta: Int): List<String> {
    val ids = orderedRailItems(order).map { it.id }.toMutableList()
    val to = index + delta
    if (index !in ids.indices || to !in ids.indices) return ids
    ids.add(to, ids.removeAt(index))
    return ids
}

/** Encode an id list for SharedPreferences: comma-separated. */
fun encodeRailIds(ids: Collection<String>): String = ids.joinToString(",")

/** Parse what [encodeRailIds] wrote, dropping ids that no longer name a rail position. */
fun decodeRailIds(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    val known = RAIL_ITEMS.map { it.id }.toSet()
    return raw.split(',').map { it.trim() }.filter { it in known }.distinct()
}
