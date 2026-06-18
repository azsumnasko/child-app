package com.childhelper.app.child.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ChildLightColorScheme = lightColorScheme(
    primary = ChildColors.Primary,
    onPrimary = Color.White,
    primaryContainer = ChildColors.PrimaryLight,
    onPrimaryContainer = ChildColors.PrimaryDark,
    secondary = ChildColors.Secondary,
    onSecondary = Color.White,
    secondaryContainer = ChildColors.SecondaryLight,
    onSecondaryContainer = ChildColors.SecondaryDark,
    tertiary = ChildColors.Accent,
    onTertiary = Color.White,
    tertiaryContainer = ChildColors.AccentLight,
    onTertiaryContainer = Color(0xFF8B5E34),
    background = ChildColors.BackgroundLight,
    onBackground = ChildColors.TextPrimaryLight,
    surface = ChildColors.SurfaceLight,
    onSurface = ChildColors.TextPrimaryLight,
    surfaceVariant = Color(0xFFEEF1F4),
    onSurfaceVariant = ChildColors.TextSecondaryLight,
    error = Color(0xFFD9534F),
    onError = Color.White,
    errorContainer = Color(0xFFFADBD8),
    onErrorContainer = Color(0xFFA94442),
    outline = Color(0xFFD0D5DA),
    outlineVariant = Color(0xFFE0E4E8),
    inversePrimary = ChildColors.PrimaryLight,
    inverseSurface = Color(0xFF2C3E50),
    inverseOnSurface = Color(0xFFEEF1F4),
    surfaceTint = ChildColors.Primary
)

private val ChildDarkColorScheme = darkColorScheme(
    primary = ChildColors.PrimaryLight,
    onPrimary = Color(0xFF1A3A5C),
    primaryContainer = ChildColors.PrimaryDark,
    onPrimaryContainer = ChildColors.PrimaryLight,
    secondary = ChildColors.SecondaryLight,
    onSecondary = Color(0xFF1A4D3E),
    secondaryContainer = ChildColors.SecondaryDark,
    onSecondaryContainer = ChildColors.SecondaryLight,
    tertiary = ChildColors.AccentLight,
    onTertiary = Color(0xFF6B4226),
    tertiaryContainer = Color(0xFF8B5E34),
    onTertiaryContainer = ChildColors.AccentLight,
    background = ChildColors.BackgroundDark,
    onBackground = ChildColors.TextPrimaryDark,
    surface = ChildColors.SurfaceDark,
    onSurface = ChildColors.TextPrimaryDark,
    surfaceVariant = Color(0xFF3A3D42),
    onSurfaceVariant = ChildColors.TextSecondaryDark,
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5C1A1A),
    errorContainer = Color(0xFF8B3A3A),
    onErrorContainer = Color(0xFFFFCDD2),
    outline = Color(0xFF5A5D63),
    outlineVariant = Color(0xFF4A4D52),
    inversePrimary = ChildColors.Primary,
    inverseSurface = Color(0xFFE0E3E8),
    inverseOnSurface = Color(0xFF2C3E50),
    surfaceTint = ChildColors.PrimaryLight
)

/**
 * Child-friendly Material 3 theme for the child-facing app.
 *
 * Uses warm, calming colors (soft blues, greens, and peach accents).
 * No alarming red colors — even the SOS button uses warm amber/orange.
 *
 * @param darkTheme Whether to use the dark color scheme. Defaults to system setting.
 * @param content The composable content to be themed.
 */
@Composable
fun ChildTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        ChildDarkColorScheme
    } else {
        ChildLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = childTypography,
        content = content
    )
}

/**
 * Bedtime-specific theme that overrides the background with deep calming tones.
 */
@Composable
fun BedtimeTheme(
    content: @Composable () -> Unit
) {
    val bedtimeColorScheme = darkColorScheme(
        primary = ChildColors.BedtimeAccent,
        onPrimary = Color.White,
        primaryContainer = ChildColors.BedtimeGlow,
        onPrimaryContainer = Color(0xFFB0B8D8),
        secondary = ChildColors.SecondaryLight,
        onSecondary = Color(0xFF1A4D3E),
        secondaryContainer = ChildColors.SecondaryDark,
        onSecondaryContainer = ChildColors.SecondaryLight,
        tertiary = Color(0xFFD4A574),
        onTertiary = Color(0xFF4D3319),
        background = ChildColors.BedtimeBackground,
        onBackground = Color(0xFFC5C9D6),
        surface = ChildColors.BedtimeSurface,
        onSurface = Color(0xFFC5C9D6),
        surfaceVariant = Color(0xFF2E3350),
        onSurfaceVariant = Color(0xFF8B8FA3),
        error = Color(0xFFFF8A80),
        onError = Color(0xFF5C1A1A),
        outline = Color(0xFF4A4F68),
        outlineVariant = Color(0xFF3A3F58)
    )

    MaterialTheme(
        colorScheme = bedtimeColorScheme,
        typography = childTypography,
        content = content
    )
}

/**
 * Child-friendly typography with larger defaults for readability.
 */
val childTypography: androidx.compose.material3.Typography
    @Composable
    get() = androidx.compose.material3.Typography().let { defaults ->
        defaults.copy(
            displayLarge = defaults.displayLarge.copy(
                fontSize = androidx.compose.ui.unit.TextUnit(36f, androidx.compose.ui.unit.TextUnitType.Sp)
            ),
            displayMedium = defaults.displayMedium.copy(
                fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp)
            ),
            headlineLarge = defaults.headlineLarge.copy(
                fontSize = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)
            ),
            titleLarge = defaults.titleLarge.copy(
                fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)
            ),
            bodyLarge = defaults.bodyLarge.copy(
                fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)
            ),
            labelLarge = defaults.labelLarge.copy(
                fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)
            )
        )
    }
