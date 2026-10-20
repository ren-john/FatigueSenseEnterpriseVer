package com.example.fatiguesense.enterprise.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = FatiguePrimary,
    secondary = FatigueSecondary,
    background = FatigueBackground,
    surface = FatigueSurface,
    onPrimary = FatigueBackground,
    onSecondary = FatigueBackground,
    onBackground = FatigueOnSurface,
    onSurface = FatigueOnSurface,
)

@Composable
fun FatigueSenseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content,
    )
}

