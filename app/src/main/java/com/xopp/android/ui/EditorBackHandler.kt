package com.xopp.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.xopp.android.render.cancelSpline
import com.xopp.android.render.splineInProgress
import com.xopp.android.render.closePalette

/**
 * The Android back button/gesture as a **navigation** control rather than a quit button.
 *
 * The editor is one screen with a stack of transient states layered on it — a radial palette, an
 * unfinished spline, a selection, page-overview edit mode, immersive full-page view. Back peels off
 * the topmost of those, one press at a time, and only leaves the app once none is left.
 *
 * Two kinds of layer are **not** handled here because they already handle themselves, and doing it
 * twice would eat two states per press:
 * - Compose dialogs ([androidx.compose.material3.AlertDialog]) and dropdown/popup menus dismiss on
 *   back through their own `onDismissRequest`.
 * - The Settings overlay owns a nested handler ([SettingsScreen]); being composed later, it wins the
 *   dispatcher and pops its section before closing itself.
 *
 * The order below is "most transient first" — see the cascade in [EditorBackHandler].
 */
@Composable
fun EditorBackHandler(
    ui: EditorUiState,
    pane: PaneState,
    /** A document transfer is in flight; back is swallowed rather than acted on. */
    busy: Boolean,
    /** Nothing left to dismiss: leave the app (the activity's default back). */
    onExit: () -> Unit,
) {
    // Always enabled: the surface-owned states (palette, spline) live outside Compose and so can't
    // gate this, and the last branch has to be able to fall through to the real back.
    BackHandler(enabled = true) {
        if (busy) return@BackHandler
        val surface = pane.surface

        when {
            // The radial palette owns every pointer while it's up, so it comes off first.
            surface != null && surface.paletteOpen -> surface.closePalette()

            // A multi-tap spline gesture in progress: back throws the open curve away, as Escape does.
            surface != null && surface.splineInProgress() -> surface.cancelSpline()

            // Editing inside a text box, then a plain element selection.
            pane.hasTextSelection -> surface?.cancelTextEdit()
            pane.hasSelection -> surface?.clearSelection()

            // In the page overview: drop the picked pages first, then leave edit mode.
            pane.selectedPages > 0 -> surface?.clearPageSelection()
            pane.pagesEditMode -> surface?.setPagesEditMode(false)

            // Immersive view: back brings the chrome back rather than closing the document.
            ui.fullPage -> ui.fullPage = false

            else -> onExit()
        }
    }
}
