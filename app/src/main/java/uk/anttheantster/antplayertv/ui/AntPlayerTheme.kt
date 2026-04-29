package uk.anttheantster.antplayertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AntPlayer TV v2.0 — refined dark palette.
 * Aim: closer to a modern streaming-app feel (deep navy/black with a vibrant
 * indigo-purple accent), not the slightly washed-out original.
 */

object AntColors {
    val AccentPurple   = Color(0xFF8B5CF6) // primary accent
    val AccentDeep     = Color(0xFF6D28D9) // pressed / deep accent
    val AccentSoft     = Color(0xFFB39DDB) // tints / glow

    val SurfaceBase    = Color(0xFF0A0A12) // darkest
    val SurfaceElev1   = Color(0xFF12121C)
    val SurfaceElev2   = Color(0xFF1A1A26)
    val SurfaceElev3   = Color(0xFF22222F)

    val TextPrimary    = Color(0xFFF5F5FA)
    val TextSecondary  = Color(0xFFB0B0BE)
    val TextMuted      = Color(0xFF7A7A88)

    val Divider        = Color(0x1FFFFFFF)
}

private val AntPlayerDarkColorScheme = darkColorScheme(
    primary          = AntColors.AccentPurple,
    onPrimary        = Color.White,
    primaryContainer = AntColors.AccentDeep,
    onPrimaryContainer = Color.White,
    secondary        = AntColors.AccentSoft,
    onSecondary      = Color.Black,
    background       = AntColors.SurfaceBase,
    onBackground     = AntColors.TextPrimary,
    surface          = AntColors.SurfaceElev1,
    onSurface        = AntColors.TextPrimary,
    surfaceVariant   = AntColors.SurfaceElev2,
    onSurfaceVariant = AntColors.TextSecondary,
    outline          = AntColors.Divider,
)

private val AntPlayerTypography = Typography(
    displayMedium = TextStyle(fontWeight = FontWeight.Light,    fontSize = 56.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge    = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium   = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 16.sp),
    bodyLarge     = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 16.sp, letterSpacing = 0.15.sp),
    bodyMedium    = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 14.sp, letterSpacing = 0.20.sp),
    labelLarge    = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 14.sp, letterSpacing = 0.50.sp),
)

/**
 * A reusable radial / vertical gradient suited for hero / boot screens.
 * Subtle indigo glow at top-left fading into deep black.
 */
val AntBackgroundBrush: Brush
    get() = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF14101F),
            AntColors.SurfaceBase,
            Color(0xFF000004),
        )
    )

val AntCardShape = RoundedCornerShape(20.dp)

@Composable
fun AntPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AntPlayerDarkColorScheme,
        typography = AntPlayerTypography,
        content = content
    )
}

/**
 * Convenience full-screen background container — used by the new launcher hub
 * and boot sequence so every "professional" screen has a consistent backdrop.
 */
@Composable
fun AntScreenBackground(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AntBackgroundBrush)
    ) {
        content()
    }
}
