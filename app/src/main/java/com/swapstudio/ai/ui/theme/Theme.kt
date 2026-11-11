package com.swapstudio.ai.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Palette of SwapStudio AI.
 *
 * Dark first, CapCut / Remini style: near black background, one electric mint accent, a violet
 * secondary for "AI" affordances, and a hot rose for destructive or "live" states.
 */
object SwapColors {
    val Background = Color(0xFF07070A)
    val Surface = Color(0xFF121218)
    val SurfaceElevated = Color(0xFF1A1A22)
    val SurfaceHigh = Color(0xFF23232D)
    val Accent = Color(0xFF00E5C0)
    val AccentDim = Color(0xFF00A88E)
    val Secondary = Color(0xFF7C5CFF)
    val Hot = Color(0xFFFF3D71)
    val Warning = Color(0xFFFFB020)
    val Success = Color(0xFF31E981)
    val TextPrimary = Color(0xFFF4F4F7)
    val TextSecondary = Color(0xFF9A9AA8)
    val TextDisabled = Color(0xFF5A5A66)
    val Outline = Color(0xFF2C2C38)
    val Mesh = Color(0xFF00E5C0)
}

private val StudioColorScheme = darkColorScheme(
    primary = SwapColors.Accent,
    onPrimary = Color(0xFF00201A),
    primaryContainer = SwapColors.AccentDim,
    onPrimaryContainer = Color(0xFF001410),
    secondary = SwapColors.Secondary,
    onSecondary = Color.White,
    tertiary = SwapColors.Hot,
    onTertiary = Color.White,
    background = SwapColors.Background,
    onBackground = SwapColors.TextPrimary,
    surface = SwapColors.Surface,
    onSurface = SwapColors.TextPrimary,
    surfaceVariant = SwapColors.SurfaceElevated,
    onSurfaceVariant = SwapColors.TextSecondary,
    outline = SwapColors.Outline,
    error = SwapColors.Hot,
    onError = Color.White,
)

private val StudioTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 32.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 0.8.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.6.sp,
    ),
)

/**
 * Applies the Studio theme and makes the system bars transparent so the gradient headers run edge to
 * edge. The app is always dark: a light mode would break the "preview the swap" contrast, and photo
 * editors are dark by convention.
 */
@Composable
fun SwapStudioTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = StudioTypography,
        content = content,
    )
}
