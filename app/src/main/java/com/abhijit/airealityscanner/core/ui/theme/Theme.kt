package com.abhijit.airealityscanner.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhijit.airealityscanner.domain.model.ThemeMode

val Cyan = Color(0xFF4FD8EB)
val Violet = Color(0xFF8B7CFF)
val Mint = Color(0xFF5BD68A)
val Amber = Color(0xFFFFB74D)
val Coral = Color(0xFFFF6B6B)
val ScannerBlack = Color(0xFF0A0E14)

private val DarkColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF9EEFFD),
    secondary = Violet,
    onSecondary = Color(0xFF1E1065),
    secondaryContainer = Color(0xFF352A7A),
    onSecondaryContainer = Color(0xFFE4DFFF),
    tertiary = Mint,
    background = ScannerBlack,
    onBackground = Color(0xFFE3E8EF),
    surface = Color(0xFF0F141B),
    onSurface = Color(0xFFE3E8EF),
    surfaceVariant = Color(0xFF1A2230),
    onSurfaceVariant = Color(0xFFA9B4C2),
    surfaceContainer = Color(0xFF141A23),
    surfaceContainerHigh = Color(0xFF1A212C),
    surfaceContainerHighest = Color(0xFF222A36),
    outline = Color(0xFF3A4556),
    outlineVariant = Color(0xFF263040),
    error = Coral,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EEFFD),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = Color(0xFF5B4FCF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4DFFF),
    onSecondaryContainer = Color(0xFF170065),
    tertiary = Color(0xFF1B7A46),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF151A21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF151A21),
    surfaceVariant = Color(0xFFE5EBF2),
    onSurfaceVariant = Color(0xFF4A5563),
    surfaceContainer = Color(0xFFF0F3F7),
    surfaceContainerHigh = Color(0xFFE9EDF2),
    surfaceContainerHighest = Color(0xFFE2E7ED),
    outline = Color(0xFFB5BFCC),
    outlineVariant = Color(0xFFD6DDE6),
    error = Color(0xFFBA1A1A),
)

private val AppTypography = Typography().let { base ->
    val family = FontFamily.SansSerif
    Typography(
        displaySmall = base.displaySmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = family, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = family, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = family, lineHeight = 21.sp),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelMedium = base.labelMedium.copy(fontFamily = family, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontFamily = family, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)

@Composable
fun AppTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
