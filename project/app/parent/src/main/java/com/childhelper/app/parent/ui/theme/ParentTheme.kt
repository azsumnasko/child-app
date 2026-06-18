package com.childhelper.app.parent.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Light color scheme for the parent dashboard.
 * Professional, clean Material 3 theme with teal primary.
 */
private val LightColorScheme = lightColorScheme(
    primary = Teal40,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Teal90,
    onPrimaryContainer = Color(0xFF00251A),
    secondary = BlueGray40,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = BlueGray90,
    onSecondaryContainer = Color(0xFF1C313A),
    tertiary = Amber40,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Amber90,
    onTertiaryContainer = Color(0xFF261900),
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = Color(0xFFE0F2F1),
    onSurfaceVariant = TextSecondaryLight,
    outline = DividerLight,
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

/**
 * Dark color scheme for the parent dashboard.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005045),
    onPrimaryContainer = Teal90,
    secondary = BlueGray80,
    onSecondary = Color(0xFF253238),
    secondaryContainer = Color(0xFF37474F),
    onSecondaryContainer = BlueGray90,
    tertiary = Amber80,
    onTertiary = Color(0xFF422D00),
    tertiaryContainer = Color(0xFF5E4200),
    onTertiaryContainer = Amber90,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = Color(0xFF263238),
    onSurfaceVariant = TextSecondaryDark,
    outline = DividerDark,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * Parent theme for the dashboard app.
 * Supports dynamic colors on Android 12+, light/dark modes, and accessibility.
 */
@Composable
fun ParentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
