package com.nexopp.ui

import com.nexopp.format.model.LineStyle
import com.nexopp.render.GuideKind
import com.nexopp.render.LayerInfo

/**
 * Grouped layer-management callbacks for the toolbar. Replaces the 10 individual layer parameters
 * in [SideToolbar].
 */
data class ToolbarLayerCallbacks(
    val layers: List<LayerInfo>,
    val hasSelection: Boolean,
    val onAddLayer: () -> Unit,
    val onDeleteLayer: (Int) -> Unit,
    val onMergeLayerDown: (Int) -> Unit,
    val onRenameLayer: (Int, String) -> Unit,
    val onMoveLayer: (Int, Int) -> Unit,
    val onActivateLayer: (Int) -> Unit,
    val onToggleLayerHidden: (Int, Boolean) -> Unit,
    val onMoveSelectionToLayer: (Int) -> Unit,
)

/**
 * Grouped page-management callbacks for the toolbar. Replaces the 17 individual page parameters
 * in [SideToolbar].
 */
data class ToolbarPagesCallbacks(
    val pageCount: Int,
    val currentPage: Int,
    val onAddPage: () -> Unit,
    val onRemovePage: () -> Unit,
    val onGoToPage: (Int) -> Unit,
    val pageSize: Pair<Double, Double>?,
    val onPageSize: (Double, Double) -> Unit,
    val pageColumns: Int,
    val onPageColumns: (Int) -> Unit,
    val pagesEditMode: Boolean,
    val onPagesEditMode: (Boolean) -> Unit,
    val selectedPages: Int,
    val onDeleteSelectedPages: () -> Unit,
    val onClearPageSelection: () -> Unit,
    val copiedPages: Int,
    val onCopySelectedPages: () -> Unit,
    val onPastePages: () -> Unit,
)

/**
 * Grouped style callbacks for the toolbar. Replaces the 12 individual style parameters in
 * [SideToolbar].
 */
data class ToolbarStyleCallbacks(
    val color: Int,
    val onColor: (Int) -> Unit,
    val palette: ColorPaletteState,
    val onRedefineCustom: (Int) -> Unit,
    val width: Float,
    val onWidth: (Float) -> Unit,
    val widthSlots: List<Float>,
    val onRedefineSlot: (Int, Float) -> Unit,
    val lineStyle: LineStyle,
    val onLineStyle: (LineStyle) -> Unit,
    val fill: Int?,
    val onFill: (Int?) -> Unit,
)
