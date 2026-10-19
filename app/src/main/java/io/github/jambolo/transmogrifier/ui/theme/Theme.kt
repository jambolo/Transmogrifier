package io.github.jambolo.transmogrifier.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = Citrus,
    onPrimary = Ink,
    primaryContainer = Citrus,
    onPrimaryContainer = Ink,
    inversePrimary = Ink,
    secondary = Sky,
    onSecondary = Ink,
    secondaryContainer = Sky,
    onSecondaryContainer = Ink,
    tertiary = Lilac,
    onTertiary = Ink,
    tertiaryContainer = Lilac,
    onTertiaryContainer = Ink,
    background = Ink,
    onBackground = Paper,
    surface = DarkSurface,
    onSurface = Paper,
    surfaceVariant = Color(0xFF403C50),
    onSurfaceVariant = Color(0xFFD6D0DD),
    surfaceTint = DarkSurface,
    inverseSurface = Paper,
    inverseOnSurface = Ink,
    error = Coral,
    onError = Ink,
    errorContainer = Color(0xFF612A35),
    onErrorContainer = Color(0xFFFFE5E2),
    outline = Lilac,
    outlineVariant = Outline,
    scrim = Ink,
    surfaceBright = Color(0xFF494457),
    surfaceDim = Ink,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Color(0xFF2B283C),
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = Color(0xFF403C50),
    surfaceContainerHighest = Color(0xFF494457)
)

private val LightColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    primaryContainer = Citrus,
    onPrimaryContainer = Ink,
    inversePrimary = Citrus,
    secondary = Color(0xFF315D70),
    onSecondary = Color.White,
    secondaryContainer = Sky,
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF6A437F),
    onTertiary = Color.White,
    tertiaryContainer = Lilac,
    onTertiaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0EAE3),
    onSurfaceVariant = Muted,
    surfaceTint = Color.White,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    error = BrandError,
    onError = Color.White,
    errorContainer = Color(0xFFFBE4E4),
    onErrorContainer = Color(0xFF641D25),
    outline = Outline,
    outlineVariant = Color(0xFFDCD5DD),
    scrim = Ink,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE6E0D9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Paper,
    surfaceContainer = Color(0xFFF6F0E8),
    surfaceContainerHigh = Color(0xFFF0EAE3),
    surfaceContainerHighest = Color(0xFFE6E0D9)
)

private val BrandShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TransmogrifierTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = BrandShapes,
        content = content
    )
}
