package com.xopp.android.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * The app's static brand palette.
 *
 * These are *fallback* colours only: [XoppTheme] prefers Material You dynamic colour, and reaches
 * for this palette when the device can't supply one (below Android 12). It seeds the light/dark
 * `ColorScheme`s in `Theme.kt`, which in turn is what `ChromeColors.kt` reads to derive the ARGB
 * ints the non-Compose `DrawingSurfaceView` paints its chrome with — so a change here can move the
 * canvas' selection and guide colours too.
 *
 * This is chrome only. Ink, page backgrounds and the pen palette are document data that lives in
 * the `.xopp` file, and are deliberately not defined here.
 */

/** Brand primary under a light scheme — deep violet, dark enough to carry white text. */
val Purple = Color(0xFF4A2C7A)

/** Brand primary under a dark scheme — the same violet lightened so it reads on a dark surface. */
val PurpleDark = Color(0xFFB69DDB)

/** Brand secondary in both schemes; the accent behind guide overlays on the canvas. */
val Amber = Color(0xFFFFB300)
