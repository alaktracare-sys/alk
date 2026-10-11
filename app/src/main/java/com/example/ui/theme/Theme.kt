package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Alaktra Premium Dark Palette
val AlaktraBackground = Color(0xFF0C0D12)
val AlaktraSurface = Color(0xFF14151E)
val AlaktraCard = Color(0xFF1C1D28)
val AlaktraCardElevated = Color(0xFF242634)
val AlaktraBorder = Color(0xFF2A2C3C)

// Alaktra Accent System
val AlaktraMint = Color(0xFF00E599)
val AlaktraMintDark = Color(0xFF00B377)
val AlaktraCyan = Color(0xFF00D2FF)
val AlaktraViolet = Color(0xFF8B5CF6)
val AlaktraCoral = Color(0xFFFF5277)

// Text Colors
val AlaktraTextPrimary = Color(0xFFFFFFFF)
val AlaktraTextSecondary = Color(0xFFA2A4B8)
val AlaktraTextMuted = Color(0xFF65677E)

// Backwards compatibility tokens
val GreenPrimary = AlaktraMint
val GreenLight = Color(0xFF33EBAC)
val DarkBackground = AlaktraBackground
val DarkSurface = AlaktraSurface
val DarkSurfaceVariant = AlaktraCard
val TextPrimary = AlaktraTextPrimary
val TextSecondary = AlaktraTextSecondary
val CardBackground = AlaktraCard

object AlaktraGradients {
    val AccentGradient = Brush.horizontalGradient(
        colors = listOf(AlaktraMint, AlaktraCyan)
    )
    val VerticalAccent = Brush.verticalGradient(
        colors = listOf(AlaktraMint, AlaktraMintDark)
    )
    val CardGradient = Brush.verticalGradient(
        colors = listOf(AlaktraCardElevated, AlaktraCard)
    )
    val DarkAtmosphere = Brush.verticalGradient(
        colors = listOf(Color(0xFF161824), AlaktraBackground)
    )
    val LikedSongsGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF4C1D95), Color(0xFF2563EB), AlaktraMint)
    )
}

private val DarkColorScheme = darkColorScheme(
    primary = AlaktraMint,
    onPrimary = Color(0xFF051B11),
    primaryContainer = Color(0xFF0B3322),
    onPrimaryContainer = AlaktraMint,
    secondary = AlaktraCyan,
    onSecondary = Color(0xFF001F29),
    secondaryContainer = Color(0xFF003847),
    onSecondaryContainer = AlaktraCyan,
    tertiary = AlaktraViolet,
    background = AlaktraBackground,
    onBackground = AlaktraTextPrimary,
    surface = AlaktraSurface,
    onSurface = AlaktraTextPrimary,
    surfaceVariant = AlaktraCard,
    onSurfaceVariant = AlaktraTextSecondary,
    outline = AlaktraBorder,
    error = Color(0xFFFF4B55)
)

@Composable
fun AlaktraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

@Composable
fun AlreadyMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    AlaktraTheme(darkTheme = darkTheme, content = content)
}

