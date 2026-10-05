package org.circa.exercise.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** Stock Pixel Watch dark surfaces (sampled from the stock Wear OS reference screenshots). */
private val SURFACE_LOW = Color(0xFF1C1D21)
private val SURFACE = Color(0xFF2F3036)
private val SURFACE_HIGH = Color(0xFF3A3B42)
private val ON_SURFACE = Color(0xFFE3E3E8)
private val ON_SURFACE_VARIANT = Color(0xFFC7C6CD)
private val OUTLINE = Color(0xFF8F9099)
private val OUTLINE_VARIANT = Color(0xFF46464C)
private val ON_ACCENT = Color(0xFF1B1B21)

/**
 * The colour scheme for one accent (the launcher's, copied). Everything that highlights - active toggles, ring
 * progress, notification titles, the "All apps" pill, the picker's selection - reads
 * `MaterialTheme.colorScheme.primary` (or a container derived from it here), so picking another
 * accent recolours the whole UI from this one function. Secondary and tertiary deliberately equal
 * the accent: there is exactly one highlight colour.
 */
fun circaColorScheme(): ColorScheme {
    val primary = ACCENT
    val container = lerp(SURFACE, primary, 0.30f)
    return ColorScheme(
        primary = primary,
        primaryDim = lerp(primary, Color.Black, 0.18f),
        primaryContainer = container,
        onPrimary = ON_ACCENT,
        onPrimaryContainer = primary,
        secondary = primary,
        secondaryDim = lerp(primary, Color.Black, 0.18f),
        secondaryContainer = container,
        onSecondary = ON_ACCENT,
        onSecondaryContainer = primary,
        tertiary = primary,
        tertiaryDim = lerp(primary, Color.Black, 0.18f),
        tertiaryContainer = container,
        onTertiary = ON_ACCENT,
        onTertiaryContainer = primary,
        surfaceContainerLow = SURFACE_LOW,
        surfaceContainer = SURFACE,
        surfaceContainerHigh = SURFACE_HIGH,
        onSurface = ON_SURFACE,
        onSurfaceVariant = ON_SURFACE_VARIANT,
        outline = OUTLINE,
        outlineVariant = OUTLINE_VARIANT,
        background = Color.Black,
        onBackground = ON_SURFACE,
    )
}

@Composable
fun Theme(content: @Composable () -> Unit) {
    val scheme = remember { circaColorScheme() }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Circa accent (Circa Exercise uses the default blue). */
val ACCENT = Color(0xFF8AB4F8)

/** Zone colours Z1..Z5 (design round 2). */
val ZONE_COLORS = listOf(Color(0xFF8AB4F8), Color(0xFF81C995), Color(0xFFFDD663), Color(0xFFF9AB00), Color(0xFFF28B82))
val DIM = Color(0xFF9AA0A6)
val TRACK = Color(0xFF3C4043)
val TONAL = Color(0xFF3C4043)
val AMBER = Color(0xFFFDD663)
val GREEN = Color(0xFF81C995)
val RED = Color(0xFFF28B82)
val ON_RED = Color(0xFF3C0A06)
val ON_ACCENT_DARK = Color(0xFF0B1C3A)
val LAST_USED_BG = Color(0xFF283A5A)
