package com.childhelper.app.child.ui.theme

import androidx.compose.ui.graphics.Color

// Warm, calming color palette designed for children
// Soft blues and greens — no alarming reds

object ChildColors {
    // Primary palette — soft, friendly blue
    val Primary = Color(0xFF5B9BD5)
    val PrimaryLight = Color(0xFF8FBDE4)
    val PrimaryDark = Color(0xFF3A7BC8)

    // Secondary — gentle teal/green
    val Secondary = Color(0xFF5BC0A3)
    val SecondaryLight = Color(0xFF8DD4C0)
    val SecondaryDark = Color(0xFF3A9E82)

    // Accent — warm peach
    val Accent = Color(0xFFF4A261)
    val AccentLight = Color(0xFFF7B88A)

    // Backgrounds
    val BackgroundLight = Color(0xFFF8F9FA)
    val BackgroundDark = Color(0xFF1A1C1E)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceDark = Color(0xFF2C2E31)

    // Bedtime mode — deep calming tones
    val BedtimeBackground = Color(0xFF1A1F36)
    val BedtimeSurface = Color(0xFF252B45)
    val BedtimeAccent = Color(0xFF7B8ED0)
    val BedtimeGlow = Color(0xFF4A5580)

    // Text colors
    val TextPrimaryLight = Color(0xFF2C3E50)
    val TextSecondaryLight = Color(0xFF6B7B8D)
    val TextPrimaryDark = Color(0xFFE0E3E8)
    val TextSecondaryDark = Color(0xFF9BA3AD)

    // Button colors for contacts
    val MomButton = Color(0xFFE88C6A)
    val MomButtonLight = Color(0xFFF0B09A)
    val DadButton = Color(0xFF5B9BD5)
    val DadButtonLight = Color(0xFF8FBDE4)

    // SOS — uses amber/orange, NOT red (child-friendly)
    val SosActive = Color(0xFFF4A261)
    val SosPressed = Color(0xFFE07B39)

    // Status indicators
    val Online = Color(0xFF5BC0A3)
    val Offline = Color(0xFF9BA3AD)
    val Monitoring = Color(0xFF5B9BD5)

    // Calming gradient colors for bedtime
    val BedtimeGradientStart = Color(0xFF1A1F36)
    val BedtimeGradientEnd = Color(0xFF2D1F36)
}
