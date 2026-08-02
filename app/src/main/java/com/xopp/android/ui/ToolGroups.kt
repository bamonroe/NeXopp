package com.xopp.android.ui

/**
 * A toolbar slot that stands for several related tools. The rail shows **one** button per group —
 * the group's currently selected tool. Tapping the button activates that tool; long-pressing opens a
 * picker that changes which member the slot stands for (see `ToolGroupButton` in [SideToolbar.kt]).
 *
 * Selections are persisted per group id in [AppSettings.toolGroupSelections], so the rail looks the
 * same after the app is closed and reopened.
 */
data class ToolGroup(
    /** Stable key — the SharedPreferences identity of this group's selection. Never rename. */
    val id: String,
    /** Human-readable name, shown as the picker's heading. */
    val label: String,
    /** The group's members, in picker order; the first is the factory-default selection. */
    val tools: List<EditorTool>,
)

/**
 * The rail's tool slots, in display order. Every [EditorTool] belongs to exactly one group; a group
 * with a single member (eraser, pan) is just a plain button with nothing to pick.
 */
val TOOL_GROUPS: List<ToolGroup> = listOf(
    ToolGroup("draw", "Draw", listOf(EditorTool.PEN, EditorTool.HIGHLIGHTER)),
    ToolGroup("eraser", "Eraser", listOf(EditorTool.ERASER)),
    ToolGroup(
        "line", "Line",
        listOf(EditorTool.LINE, EditorTool.ARROW, EditorTool.DOUBLE_ARROW, EditorTool.SPLINE),
    ),
    ToolGroup(
        "shape", "Shape",
        listOf(EditorTool.RECTANGLE, EditorTool.ELLIPSE, EditorTool.COORDINATE_AXIS),
    ),
    ToolGroup("pan", "Pan", listOf(EditorTool.HAND)),
    ToolGroup("select", "Select", listOf(EditorTool.SELECT, EditorTool.TEXT_SELECT)),
    ToolGroup("insert", "Insert", listOf(EditorTool.TEXT, EditorTool.TEXIMAGE, EditorTool.IMAGE)),
    ToolGroup("vspace", "Vertical space", listOf(EditorTool.VERTICAL_SPACE)),
    ToolGroup("play", "Play object", listOf(EditorTool.PLAY_OBJECT)),
)

/** The group [tool] belongs to, or null if it is somehow ungrouped. */
fun groupOf(tool: EditorTool): ToolGroup? = TOOL_GROUPS.firstOrNull { tool in it.tools }

/**
 * The tool a freshly opened document should start in: [defaultTool]'s slot, faced with whatever the
 * user last picked for it. Without this the rail would open showing a persisted choice (highlighter,
 * say) while the canvas was really in the group's first tool.
 */
fun startingTool(defaultTool: EditorTool, selections: Map<String, EditorTool>): EditorTool =
    groupOf(defaultTool)?.selected(selections) ?: defaultTool

/**
 * The tool this group's slot currently stands for: the persisted choice when it is still a member of
 * the group, otherwise the group's first tool. Guarding on membership keeps a stale or hand-edited
 * preference from pointing a slot at a tool that has since moved groups.
 */
fun ToolGroup.selected(selections: Map<String, EditorTool>): EditorTool =
    selections[id]?.takeIf { it in tools } ?: tools.first()

/**
 * [selections] with this group's slot pointed at [tool]. A tool that isn't a member is ignored, so a
 * bad call can't corrupt the persisted map.
 */
fun ToolGroup.withSelection(
    selections: Map<String, EditorTool>,
    tool: EditorTool,
): Map<String, EditorTool> =
    if (tool in tools) selections + (id to tool) else selections

/** Encode a selection map for SharedPreferences: `group:TOOL` pairs, comma-separated. */
fun encodeToolGroupSelections(selections: Map<String, EditorTool>): String =
    selections.entries.joinToString(",") { "${it.key}:${it.value.name}" }

/**
 * Parse what [encodeToolGroupSelections] wrote, dropping entries whose group or tool no longer
 * exists (or is no longer a member) so a stale pref degrades to the defaults rather than a crash.
 */
fun decodeToolGroupSelections(raw: String?): Map<String, EditorTool> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(',').mapNotNull { entry ->
        val (id, name) = entry.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val group = TOOL_GROUPS.firstOrNull { it.id == id.trim() } ?: return@mapNotNull null
        val tool = runCatching { enumValueOf<EditorTool>(name.trim()) }.getOrNull() ?: return@mapNotNull null
        if (tool in group.tools) group.id to tool else null
    }.toMap()
}
