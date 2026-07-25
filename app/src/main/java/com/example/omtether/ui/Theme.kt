package com.example.omtether.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OmDarkColors = darkColorScheme(
    primary = Color(0xFFB8BEC7),
    onPrimary = Color(0xFF191B1E),
    secondary = Color(0xFF969DA7),
    background = Color(0xFF101214),
    onBackground = Color(0xFFE7E9EC),
    surface = Color(0xFF1A1D20),
    onSurface = Color(0xFFE7E9EC),
    surfaceVariant = Color(0xFF2A2E33),
    onSurfaceVariant = Color(0xFFC5C9CF),
    error = Color(0xFFC98F8F),
)

@Composable
fun OmTetherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OmDarkColors,
        content = content,
    )
}
