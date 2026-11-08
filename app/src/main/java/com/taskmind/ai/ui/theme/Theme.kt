package com.taskmind.ai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskmind.ai.data.db.Priority
import com.taskmind.ai.data.prefs.ThemeMode

private val Violet = Color(0xFF6D5EF5)
private val VioletDeep = Color(0xFF4F3FD9)
private val Teal = Color(0xFF14B8A6)
private val Amber = Color(0xFFF59E0B)
private val Rose = Color(0xFFE5484D)

private val LightColors = lightColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E5FF),
    onPrimaryContainer = Color(0xFF1C1366),
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCF5EF),
    onSecondaryContainer = Color(0xFF00382F),
    tertiary = Amber,
    onTertiary = Color(0xFF2A1800),
    tertiaryContainer = Color(0xFFFFEBC7),
    onTertiaryContainer = Color(0xFF3D2600),
    error = Rose,
    background = Color(0xFFF7F7FB),
    onBackground = Color(0xFF15151F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15151F),
    surfaceVariant = Color(0xFFEFEFF6),
    onSurfaceVariant = Color(0xFF5B5B6E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFE),
    surfaceContainer = Color(0xFFF2F2F8),
    surfaceContainerHigh = Color(0xFFECECF4),
    surfaceContainerHighest = Color(0xFFE6E6EF),
    outline = Color(0xFFCACADA),
    outlineVariant = Color(0xFFE3E3EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9D92FF),
    onPrimary = Color(0xFF1B1259),
    primaryContainer = Color(0xFF2E2685),
    onPrimaryContainer = Color(0xFFE6E2FF),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0E4A42),
    onSecondaryContainer = Color(0xFFC6F7EF),
    tertiary = Color(0xFFFCC66B),
    onTertiary = Color(0xFF3D2600),
    tertiaryContainer = Color(0xFF4F3500),
    onTertiaryContainer = Color(0xFFFFE3B0),
    error = Color(0xFFFF7A7E),
    background = Color(0xFF0E0E16),
    onBackground = Color(0xFFEDEDF7),
    surface = Color(0xFF14141E),
    onSurface = Color(0xFFEDEDF7),
    surfaceVariant = Color(0xFF22222F),
    onSurfaceVariant = Color(0xFFA9A9BE),
    surfaceContainerLowest = Color(0xFF0B0B12),
    surfaceContainerLow = Color(0xFF15151F),
    surfaceContainer = Color(0xFF1A1A26),
    surfaceContainerHigh = Color(0xFF20202D),
    surfaceContainerHighest = Color(0xFF282836),
    outline = Color(0xFF45455A),
    outlineVariant = Color(0xFF2C2C3B),
)

@Immutable
data class TmExtras(
    val heroGradient: Brush,
    val accentGradient: Brush,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val isDark: Boolean,
)

val LocalTm = staticCompositionLocalOf {
    TmExtras(Brush.linearGradient(listOf(Violet, VioletDeep)), Brush.linearGradient(listOf(Violet, Teal)), Teal, Amber, Rose, false)
}

private val Sans = FontFamily.SansSerif

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun TaskMindTheme(themeMode: ThemeMode?, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val extras = TmExtras(
        heroGradient = if (dark) Brush.linearGradient(listOf(Color(0xFF3B2FC9), Color(0xFF6D5EF5), Color(0xFF0F766E)))
        else Brush.linearGradient(listOf(Color(0xFF5B4BF0), Color(0xFF7C6CFF), Color(0xFF14B8A6))),
        accentGradient = Brush.linearGradient(listOf(scheme.primary, scheme.secondary)),
        success = if (dark) Color(0xFF4ADE80) else Color(0xFF16A34A),
        warning = if (dark) Color(0xFFFCC66B) else Color(0xFFD97706),
        danger = if (dark) Color(0xFFFF7A7E) else Rose,
        isDark = dark,
    )
    CompositionLocalProvider(LocalTm provides extras) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

@Composable
fun priorityColor(p: Priority): Color = when (p) {
    Priority.URGENT -> LocalTm.current.danger
    Priority.HIGH -> LocalTm.current.warning
    Priority.MEDIUM -> MaterialTheme.colorScheme.primary
    Priority.LOW -> MaterialTheme.colorScheme.outline
}
