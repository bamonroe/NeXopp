package com.xopp.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.xopp.android.format.model.Tool
import com.xopp.android.render.DrawingSurfaceView

/**
 * The single editor screen: a Material 3 top bar (open/save), the stylus canvas, and the tool
 * palette. The canvas is a classic [DrawingSurfaceView] hosted via [AndroidView] for low-latency
 * stylus rendering (see `docs/architecture.md` for why it isn't a Compose `Canvas`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSurfaceCreated: (DrawingSurfaceView) -> Unit,
) {
    var tool by remember { mutableStateOf(Tool.PEN) }
    var color by remember { mutableStateOf(PEN_COLORS.first()) }
    var width by remember { mutableStateOf(PEN_WIDTHS[1].pt) }
    var surface by remember { mutableStateOf<DrawingSurfaceView?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Xopp") },
                actions = {
                    IconButton(onClick = onOpen) {
                        Icon(Icons.Filled.FileOpen, contentDescription = "Open .xopp")
                    }
                    IconButton(onClick = onSave) {
                        Icon(Icons.Filled.Save, contentDescription = "Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { ctx ->
                    DrawingSurfaceView(ctx).also {
                        it.tool = tool
                        it.colorArgb = color
                        it.baseWidthPt = width
                        surface = it
                        onSurfaceCreated(it)
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            PenSettings(
                selectedColor = color,
                onColor = { color = it; surface?.colorArgb = it },
                selectedWidth = width,
                onWidth = { width = it; surface?.baseWidthPt = it },
                modifier = Modifier.fillMaxWidth(),
            )
            ToolPalette(
                selected = tool,
                onSelect = {
                    tool = it
                    surface?.tool = it
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
