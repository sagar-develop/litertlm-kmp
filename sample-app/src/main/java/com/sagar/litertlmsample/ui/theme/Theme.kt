/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.sagar.litertlmsample.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7AA2FF),
    onPrimary = Color(0xFF0B1130),
    primaryContainer = Color(0xFF1F2A55),
    onPrimaryContainer = Color(0xFFD8E0FF),
    secondary = Color(0xFFFFC36B),
    onSecondary = Color(0xFF2E1A00),
    background = Color(0xFF0A0E1F),
    onBackground = Color(0xFFE4E7F0),
    surface = Color(0xFF11162B),
    onSurface = Color(0xFFE4E7F0),
    surfaceVariant = Color(0xFF1B2342),
    onSurfaceVariant = Color(0xFFB7BDD0),
    outline = Color(0xFF3A4368),
    error = Color(0xFFFF6B7A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F5BD9),
    secondary = Color(0xFFB8741A),
    background = Color(0xFFF5F6FB),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun SampleTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
