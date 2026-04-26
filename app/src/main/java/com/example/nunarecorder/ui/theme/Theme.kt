package com.example.nunarecorder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary          = NunaPrimary,
    onPrimary        = NunaSurface,
    primaryContainer = NunaPrimaryLight,
    onPrimaryContainer = NunaSurface,
    secondary        = NunaSecondary,
    onSecondary      = NunaSurface,
    secondaryContainer = NunaSecondaryLight,
    background       = NunaBg,
    onBackground     = Color(0xFF1A222C),
    surface          = NunaSurface,
    onSurface        = Color(0xFF1A222C),
    outline          = NunaOutline,
    error            = NunaError,
    onError          = NunaSurface
)

private val DarkColors = darkColorScheme(
    primary          = NunaPrimaryDarkScheme,
    onPrimary        = NunaBgDark,
    primaryContainer = NunaPrimaryDark,
    onPrimaryContainer = NunaPrimaryLight,
    secondary        = NunaSecondaryLight,
    onSecondary      = NunaBgDark,
    background       = NunaBgDark,
    onBackground     = Color(0xFFD6E4EF),
    surface          = NunaSurfaceDark,
    onSurface        = Color(0xFFD6E4EF),
    outline          = NunaOutlineDark,
    error            = NunaError,
    onError          = NunaBgDark
)

@Composable
fun NunaRecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography  = Typography,
        content     = content
    )
}
