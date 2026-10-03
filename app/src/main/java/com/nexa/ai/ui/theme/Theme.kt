package com.nexa.ai.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Material 3 Expressive palette
val Primary = Color(0xFF7C5CFF)
val PrimaryBright = Color(0xFF9D8CFF)
val Tertiary = Color(0xFF00C7A7)
val ErrorC = Color(0xFFFF5470)
val BgDark = Color(0xFF0C0E20)
val SurfaceDark = Color(0xFF14172E)
val SurfaceDarkHigh = Color(0xFF1E2244)
val SurfaceLight = Color(0xFFFDFBFF)
val SurfaceLightVar = Color(0xFFEEEAFF)

private val DarkScheme = darkColorScheme(
    primary = PrimaryBright,
    onPrimary = Color(0xFF1A1040),
    primaryContainer = Color(0xFF3B2A80),
    onPrimaryContainer = Color(0xFFE5DEFF),
    secondary = Color(0xFFB9A8FF),
    onSecondary = Color(0xFF2A1B60),
    tertiary = Tertiary,
    onTertiary = Color(0xFF00201A),
    tertiaryContainer = Color(0xFF00443A),
    onTertiaryContainer = Color(0xFF7FF5DD),
    error = ErrorC,
    errorContainer = Color(0xFF4A1022),
    onErrorContainer = Color(0xFFFFD9DE),
    background = BgDark,
    onBackground = Color(0xFFECEAFB),
    surface = SurfaceDark,
    onSurface = Color(0xFFECEAFB),
    surfaceVariant = SurfaceDarkHigh,
    onSurfaceVariant = Color(0xFFA5A2CC),
    outline = Color(0xFF3E4270),
    outlineVariant = Color(0xFF2A2D52)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B3FD9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5DEFF),
    onPrimaryContainer = Color(0xFF1E1055),
    secondary = Color(0xFF6B4FD8),
    onSecondary = Color.White,
    tertiary = Color(0xFF00A58A),
    onTertiary = Color.White,
    error = Color(0xFFD92B4B),
    errorContainer = Color(0xFFFFDAD9),
    onErrorContainer = Color(0xFF410009),
    background = SurfaceLight,
    onBackground = Color(0xFF1A1B30),
    surface = SurfaceLight,
    onSurface = Color(0xFF1A1B30),
    surfaceVariant = SurfaceLightVar,
    onSurfaceVariant = Color(0xFF5B5880),
    outline = Color(0xFFC5C0E8),
    outlineVariant = Color(0xFFE2DEF5)
)

// Expressive shape scale — bigger radii, squital-esque
data class Shapes(
    val small: Shape = RoundedCornerShape(12.dp),
    val medium: Shape = RoundedCornerShape(18.dp),
    val large: Shape = RoundedCornerShape(26.dp),
    val pill: Shape = RoundedCornerShape(100)
)

val LocalShapes = staticCompositionLocalOf { Shapes() }

@Composable
fun NexaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(LocalShapes provides Shapes()) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
