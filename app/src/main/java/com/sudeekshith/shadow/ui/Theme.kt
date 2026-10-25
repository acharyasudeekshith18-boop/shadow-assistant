package com.sudeekshith.shadow.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object ShadowColors {
    val Void = Color(0xFF05010B)
    val Deep = Color(0xFF12051F)
    val Panel = Color(0xFF1A0B2E)
    val PanelHi = Color(0xFF26113F)
    val Violet = Color(0xFF8B5CF6)
    val Glow = Color(0xFFB794FF)
    val Core = Color(0xFFF1E8FF)
    val Magenta = Color(0xFFD946EF)
    val Text = Color(0xFFEDE7F6)
    val Muted = Color(0xFF9C8DB8)
}

@Composable
fun ShadowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ShadowColors.Violet, secondary = ShadowColors.Magenta,
            background = ShadowColors.Void, surface = ShadowColors.Panel,
            onPrimary = Color.White, onBackground = ShadowColors.Text, onSurface = ShadowColors.Text,
            surfaceVariant = ShadowColors.PanelHi, onSurfaceVariant = ShadowColors.Muted,
        ),
        content = content
    )
}
