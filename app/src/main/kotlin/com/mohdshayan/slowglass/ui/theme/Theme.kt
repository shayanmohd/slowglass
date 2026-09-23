package com.mohdshayan.slowglass.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val LightColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightOnAccent,
    secondary = LightAccent,
    onSecondary = LightOnAccent,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainer = LightSurface,
    surfaceContainerLow = LightSurface,
    surfaceContainerHigh = LightSurface,
    surfaceContainerHighest = LightSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline,
    error = LightError,
    scrim = DarkBackground,
    onError = LightOnError,
)

private val DarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    secondary = DarkAccent,
    onSecondary = DarkOnAccent,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainer = DarkSurface,
    surfaceContainerLow = DarkSurface,
    surfaceContainerHigh = DarkSurface,
    surfaceContainerHighest = DarkSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = DarkError,
    scrim = DarkBackground,
    onError = DarkOnError,
)

/**
 * The app theme. Dynamic colour is off: Ultraviolet is part of the product and looks the same on
 * every phone. The Capture screen always asks for [darkTheme]; system bar icons are set by AppNav
 * for the destination on screen.
 */
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalReducedMotion provides rememberReducedMotion()) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
