package com.xopp.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.xopp.android.ui.ThemeMode

private val LightColors = lightColorScheme(primary = Purple, secondary = Amber)
private val DarkColors = darkColorScheme(primary = PurpleDark, secondary = Amber)

/**
 * Whether [mode] means "paint dark" right now — SYSTEM defers to the OS setting.
 *
 * Use this to decide which Material 3 colour scheme to apply.
 */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * The app's Material 3 (Material You) theme. Uses dynamic colour on Android 12+.
 *
 * @param darkTheme Whether to use the dark colour scheme. Defaults to the system setting.
 * @param dynamicColor Whether to use Android 12+ dynamic colours from the wallpaper.
 * @param content The composable content to render with this theme.
 */
@Composable
fun XoppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    // Paint the status bar with the app's own surface colour instead of leaving it transparent over
    // the launch theme's (always white) window background, then pick the icon contrast from that
    // colour's luminance. Deriving it from `darkTheme` alone gave white icons on a white bar.
    val statusBar = colorScheme.surface
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = statusBar.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                statusBar.luminance() > 0.5f
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
