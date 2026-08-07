package com.xopp.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Rectangle
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.ui.graphics.vector.ImageVector
import com.xopp.android.format.model.Tool

/**
 * The editor's interaction modes. PEN/HIGHLIGHTER/ERASER map to the document [Tool]; HAND is a
 * view-only pan mode; SELECT rubber-band-selects objects to move/delete them; TEXT/IMAGE/TEXIMAGE
 * are authoring modes where a canvas tap places (or edits) that element
 * (see [com.xopp.android.render.PlaceKind]).
 */
enum class EditorTool {
    PEN, HIGHLIGHTER, ERASER, ERASER_WHOLE, HAND, SELECT, LASSO_SELECT, TEXT_SELECT, BG_SELECT,
    TEXT, IMAGE, TEXIMAGE,
    LINE, ARROW, DOUBLE_ARROW, COORDINATE_AXIS, RECTANGLE, ELLIPSE, SPLINE, VERTICAL_SPACE,
    PLAY_OBJECT,
}

/** The geometric shape tools — drawn as ordinary pen strokes (see [ShapeKind]). */
val SHAPE_TOOLS: List<EditorTool> = listOf(
    EditorTool.LINE, EditorTool.ARROW, EditorTool.DOUBLE_ARROW, EditorTool.COORDINATE_AXIS,
    EditorTool.RECTANGLE, EditorTool.ELLIPSE, EditorTool.SPLINE,
)

private data class ToolInfo(val tool: EditorTool, val label: String, val icon: ImageVector)

private val TOOLS: List<ToolInfo> = listOf(
    ToolInfo(EditorTool.PEN, "Pen", Icons.Filled.Create),
    ToolInfo(EditorTool.HIGHLIGHTER, "Highlighter", Icons.Filled.Brush),
    ToolInfo(EditorTool.ERASER, "Eraser (partial)", Icons.Filled.Delete),
    ToolInfo(EditorTool.ERASER_WHOLE, "Eraser (whole stroke)", Icons.Filled.DeleteSweep),
    ToolInfo(EditorTool.LINE, "Line", Icons.Filled.HorizontalRule),
    ToolInfo(EditorTool.ARROW, "Arrow", Icons.Filled.ArrowRightAlt),
    ToolInfo(EditorTool.DOUBLE_ARROW, "Double arrow", Icons.Filled.SwapHoriz),
    ToolInfo(EditorTool.COORDINATE_AXIS, "Coordinate axis", Icons.Filled.ShowChart),
    ToolInfo(EditorTool.RECTANGLE, "Rectangle", Icons.Filled.Rectangle),
    ToolInfo(EditorTool.ELLIPSE, "Ellipse", Icons.Filled.RadioButtonUnchecked),
    ToolInfo(EditorTool.SPLINE, "Spline", Icons.Filled.Gesture),
    ToolInfo(EditorTool.HAND, "Hand (pan)", Icons.Filled.PanTool),
    ToolInfo(EditorTool.SELECT, "Select rectangle", Icons.Filled.HighlightAlt),
    ToolInfo(EditorTool.LASSO_SELECT, "Select lasso", Icons.Filled.Polyline),
    ToolInfo(EditorTool.TEXT_SELECT, "Select text (PDF)", Icons.Filled.SelectAll),
    ToolInfo(EditorTool.BG_SELECT, "Select background (flatten)", Icons.Filled.Crop),
    ToolInfo(EditorTool.TEXT, "Text", Icons.Filled.TextFields),
    ToolInfo(EditorTool.IMAGE, "Image", Icons.Filled.Image),
    ToolInfo(EditorTool.TEXIMAGE, "LaTeX", Icons.Filled.Functions),
    ToolInfo(EditorTool.VERTICAL_SPACE, "Vertical space", Icons.Filled.SwapVert),
    ToolInfo(EditorTool.PLAY_OBJECT, "Play object", Icons.Filled.PlayCircleOutline),
)

/** Human-readable label for a tool (the same text the rail's tool menu shows). */
val EditorTool.label: String get() = TOOLS.first { it.tool == this }.label

/** The rail icon for a tool. */
val EditorTool.icon: ImageVector get() = TOOLS.first { it.tool == this }.icon

/**
 * The tools that make sense to *start* a document in, offered by the "Default tool" setting. The
 * place-modes (TEXT/IMAGE/TEXIMAGE) and SELECT aren't here: opening straight into them would strand
 * the user in a mode with nothing to act on, so a default is one of the four drawing/pan tools.
 */
val DEFAULT_TOOL_CHOICES: List<EditorTool> =
    listOf(EditorTool.PEN, EditorTool.HIGHLIGHTER, EditorTool.ERASER, EditorTool.HAND)
