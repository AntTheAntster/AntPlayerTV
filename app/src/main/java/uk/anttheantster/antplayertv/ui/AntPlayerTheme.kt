package uk.anttheantster.antplayertv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AntPlayerDarkColorScheme = darkColorScheme(
    primary = Color(0xFF7C3AED),        // purple accent
    onPrimary = Color.White,
    background = Color(0xFF050509),     // near-black
    onBackground = Color(0xFFF2F2F7),   // off-white text
    surface = Color(0xFF101018),
    onSurface = Color(0xFFF2F2F7),
)

private val AntPlayerTypography = Typography()

@Composable
fun AntPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AntPlayerDarkColorScheme,
        typography = AntPlayerTypography,
        content = content
    )
}
