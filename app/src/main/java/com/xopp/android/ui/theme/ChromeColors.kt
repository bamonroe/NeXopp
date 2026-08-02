package com.xopp.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * The chrome colours the canvas needs, as plain ARGB ints.
 *
 * The canvas is a classic `SurfaceView` living outside the Compose tree, so it can't read
 * `MaterialTheme` itself. This is the one place that maps the app's [androidx.compose.material3.ColorScheme]
 * onto the canvas' non-Compose chrome, so there is still a single colour scheme driving every surface.
 * Ink, page backgrounds and the pen palette are *document data*, not chrome, and are deliberately absent.
 */
data class CanvasChromeColors(
    /** The surround behind the pages. */
    val backdrop: Int,
    /** Selection marquee, resize/rotate handles and their arms. */
    val selection: Int,
    /** Outline and grab handles of the setsquare/compass overlay. */
    val guide: Int,
)

/** Derives the canvas chrome colours from the ambient Material 3 scheme. */
@Composable
fun rememberCanvasChromeColors(): CanvasChromeColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        // Pages are light, so the surround has to be the scheme's *dark* neutral in either theme:
        // `surfaceVariant` is dark under a dark scheme, `inverseSurface` is dark under a light one.
        val dark =
            if (scheme.surfaceVariant.luminance() < 0.5f) scheme.surfaceVariant else scheme.inverseSurface
        // Lifted a little off pure dark so the surround reads as a desk rather than a void.
        val backdrop = lerp(dark, scheme.surfaceVariant, 0.2f)
        CanvasChromeColors(
            backdrop = backdrop.toArgb(),
            selection = scheme.primary.toArgb(),
            guide = scheme.secondary.toArgb(),
        )
    }
}
