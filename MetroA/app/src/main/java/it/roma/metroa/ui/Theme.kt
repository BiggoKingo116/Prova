package it.roma.metroa.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import it.roma.metroa.ThemeMode

data class Palette(
    val bg: Color, val ink: Color, val mute: Color, val rule: Color,
    val lineA: Color, val lineSoft: Color, val surface: Color,
)

private val Light = Palette(
    Color(0xFFF2F3F1), Color(0xFF1C2126), Color(0xFF7C848A), Color(0xFFDADDDA),
    Color(0xFFE4572E), Color(0xFFF7D7CB), Color(0xFFFFFFFF),
)
private val Dark = Palette(
    Color(0xFF15191C), Color(0xFFE9ECEA), Color(0xFF879096), Color(0xFF2A3034),
    Color(0xFFF06A40), Color(0xFF4A2519), Color(0xFF1E2327),
)

val LocalPalette = staticCompositionLocalOf { Light }

@Composable
fun MetroTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val p = if (dark) Dark else Light
    val scheme = if (dark)
        darkColorScheme(background = p.bg, surface = p.surface, primary = p.lineA, onBackground = p.ink, onSurface = p.ink)
    else
        lightColorScheme(background = p.bg, surface = p.surface, primary = p.lineA, onBackground = p.ink, onSurface = p.ink)
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
