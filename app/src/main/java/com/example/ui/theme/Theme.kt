package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = BrandContainer,
    onPrimaryContainer = BrandDeepText,
    secondary = BrandSecondary,
    onSecondary = BrandOnSecondary,
    background = BrandBg,
    onBackground = BrandText,
    surface = BrandSurface,
    onSurface = BrandText,
    outline = BrandBorder,
    surfaceVariant = BrandSurface,
    onSurfaceVariant = BrandText
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandSecondary,
    onPrimary = BrandOnSecondary,
    primaryContainer = BrandPrimary,
    onPrimaryContainer = Color.White,
    secondary = BrandSecondary,
    onSecondary = BrandOnSecondary,
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF25242A),
    onSurface = Color(0xFFE6E1E5),
    outline = Color(0xFF49454F),
    surfaceVariant = Color(0xFF25242A),
    onSurfaceVariant = Color(0xFFE6E1E5)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
