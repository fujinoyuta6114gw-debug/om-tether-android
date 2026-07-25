package com.example.omtether.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OmDarkColors = darkColorScheme(
    primary = Color(0xFFFFB000),
    onPrimary = Color(0xFF201800),
    secondary = Color(0xFF7DD3FC),
    background = Color(0xFF090B0E),
    onBackground = Color(0xFFF4F5F7),
    surface = Color(0xFF12161B),
    onSurface = Color(0xFFF4F5F7),
    surfaceVariant = Color(0xFF20262D),
    onSurfaceVariant = Color(0xFFCAD0D7),
    error = Color(0xFFFF6B6B),
)

@Composable
fun OmTetherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OmDarkColors,
        content = content,
    )
}
