package com.jarvis.phoneguardian.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF5558C9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E1FF),
    onPrimaryContainer = Color(0xFF12134D),
    secondary = Color(0xFF006A67),
    secondaryContainer = Color(0xFF8AF3EC),
    surface = Color(0xFFFAF8FF),
    surfaceVariant = Color(0xFFE6E1EC),
    background = Color(0xFFFAF8FF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC1C1FF),
    primaryContainer = Color(0xFF3E3F91),
    secondary = Color(0xFF6BD6D0),
    background = Color(0xFF12121A),
    surface = Color(0xFF12121A)
)

@Composable
fun PhoneGuardianTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
