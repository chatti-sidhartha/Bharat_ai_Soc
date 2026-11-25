package com.example.learnedge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = BlueContainer,
    onPrimaryContainer = OnBlueContainer,
    secondary = Green,
    onSecondary = Color.White,
    secondaryContainer = GreenContainer,
    onSecondaryContainer = OnGreenContainer,
    tertiary = Amber,
    onTertiary = Color.White,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = OnAmberContainer,
    background = Background,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = InkSoft,
    outline = Outline,
    outlineVariant = OutlineSoft,
    error = ErrorRed,
    errorContainer = ErrorContainer,
    // Cards, sheets and bars are white on the soft grey background.
    surfaceContainerLowest = Surface,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = Surface,
    surfaceContainerHighest = Surface,
)

private val DarkColorScheme = darkColorScheme(
    primary = BlueDark,
    primaryContainer = BlueContainerDark,
    secondary = GreenDark,
    secondaryContainer = GreenContainerDark,
    tertiary = AmberDark,
    tertiaryContainer = AmberContainerDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    onBackground = InkDark,
    onSurface = InkDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = InkSoftDark,
    surfaceContainerLowest = SurfaceDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceVariantDark,
    surfaceContainerHighest = SurfaceVariantDark,
)

/**
 * The app always looks the same: light and calm. Dynamic (wallpaper) colour is off on purpose, because it
 * made the app's colours depend on the phone's wallpaper. Pass darkTheme = true to try the dark palette.
 */
@Composable
fun LearnEdgeTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
