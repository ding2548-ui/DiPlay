package com.shilapi.xcertplay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.shilapi.xcertplay.DiPlayPalette

private fun color(value: Int) = Color(value)

private fun darkScheme(palette: DiPlayPalette) = darkColorScheme(
    primary = color(palette.accent),
    onPrimary = color(palette.onAccent),
    background = color(palette.background),
    onBackground = color(palette.primaryText),
    surface = color(palette.surface),
    onSurface = color(palette.primaryText),
    error = color(palette.danger),
)

private fun lightScheme(palette: DiPlayPalette) = lightColorScheme(
    primary = color(palette.accent),
    onPrimary = color(palette.onAccent),
    background = color(palette.background),
    onBackground = color(palette.primaryText),
    surface = color(palette.surface),
    onSurface = color(palette.primaryText),
    error = color(palette.danger),
)

@Composable
fun XcertplayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = DiPlayPalette.of(darkTheme)
    val colorScheme = if (darkTheme) darkScheme(palette) else lightScheme(palette)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
