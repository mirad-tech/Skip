package com.example.skip.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = TealPrimaryDark,
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF115E59),
    onPrimaryContainer = Color(0xFFD9FFFA),
    secondary = BlueSecondaryDark,
    onSecondary = Color(0xFF102A56),
    secondaryContainer = Color(0xFF1E3A8A),
    onSecondaryContainer = Color(0xFFEAF2FF),
    tertiary = AmberTertiaryDark,
    onTertiary = Color(0xFF3C2200),
    background = DarkBackground,
    onBackground = Color(0xFFE8EEF2),
    surface = DarkSurface,
    onSurface = Color(0xFFE8EEF2),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB9C6CC),
    surfaceContainer = Color(0xFF182229),
    outline = DarkOutline
)

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F5EF),
    onPrimaryContainer = Color(0xFF083F3B),
    secondary = BlueSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEBFF),
    onSecondaryContainer = Color(0xFF153B7D),
    tertiary = AmberTertiary,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = Color(0xFF172126),
    surface = LightSurface,
    onSurface = Color(0xFF172126),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF53636B),
    surfaceContainer = Color(0xFFECF2F5),
    outline = LightOutline
)

@Composable
fun SkipTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> if (darkTheme) DarkColorScheme else LightColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
