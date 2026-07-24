package com.libreplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.libreplayer.data.repository.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5A68),
    onPrimary = Color(0xFFF6FFFD),
    primaryContainer = Color(0xFFC5E7E5),
    secondary = Color(0xFF53636A),
    tertiary = Color(0xFF76546B),
    background = Color(0xFFF7FAF9),
    surface = Color(0xFFF7FAF9),
    surfaceContainerHighest = Color(0xFFDDE5E3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ACCCB),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF224B54),
    secondary = Color(0xFFBBCBD1),
    tertiary = Color(0xFFE3BAD7),
    background = Color(0xFF101514),
    surface = Color(0xFF101514),
    surfaceContainerHighest = Color(0xFF28302F),
)

@Composable
fun LibrePlayerTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && themeMode == ThemeMode.SYSTEM) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) DarkColors else LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

