package com.andychen.magpie_android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.andychen.magpie_android.ui.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEEDFD),
    onPrimaryContainer = Color(0xFF3428BB),
    background = Color(0xFFF4F4F6),
    onBackground = Color(0xFF1C1C21),
    surface = Color.White,
    onSurface = Color(0xFF1C1C21),
    onSurfaceVariant = Color(0xFF63636B),
    surfaceVariant = Color(0xFFF1F1F4),
    surfaceContainer = Color(0xFFF1F1F4),
    outline = Color(0xFF85858D),
    outlineVariant = Color(0xFFE3E3E8),
    secondary = Color(0xFF147743),
    error = Color(0xFFB63434)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF14142A),
    primaryContainer = Color(0xFF33345A),
    onPrimaryContainer = Color(0xFFDDE2FF),
    background = Color(0xFF1A1A1E),
    onBackground = Color(0xFFEDEDF1),
    surface = Color(0xFF232327),
    onSurface = Color(0xFFEDEDF1),
    onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceVariant = Color(0xFF2D2D33),
    surfaceContainer = Color(0xFF2D2D33),
    outline = Color(0xFF8B8B94),
    outlineVariant = Color(0xFF333339),
    secondary = Color(0xFF6FD99B),
    error = Color(0xFFF28B82)
)

@Composable
internal fun MagpieTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val window = (LocalContext.current as? Activity)?.window
    SideEffect {
        window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
