package com.perchance.docreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3859D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE2FF),
    onPrimaryContainer = Color(0xFF001452),
    secondary = Color(0xFF5A5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF171A2C),
    tertiary = Color(0xFF76546F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8F4),
    onTertiaryContainer = Color(0xFF2C1229),
    background = Color(0xFFF9F9FF),
    surface = Color(0xFFF9F9FF),
    surfaceVariant = Color(0xFFE3E2EC),
    onBackground = Color(0xFF1A1B20),
    onSurface = Color(0xFF1A1B20),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF757780),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF092478),
    primaryContainer = Color(0xFF203DAA),
    onPrimaryContainer = Color(0xFFDDE2FF),
    secondary = Color(0xFFC3C4DD),
    onSecondary = Color(0xFF2C2E41),
    secondaryContainer = Color(0xFF424459),
    onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFE6B9DA),
    onTertiary = Color(0xFF43263E),
    tertiaryContainer = Color(0xFF5C3C56),
    onTertiaryContainer = Color(0xFFFFD8F4),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF45464F),
    onBackground = Color(0xFFE3E2E9),
    onSurface = Color(0xFFE3E2E9),
    onSurfaceVariant = Color(0xFFC6C6D0),
    outline = Color(0xFF8F9099),
)

@Composable
fun DocReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
