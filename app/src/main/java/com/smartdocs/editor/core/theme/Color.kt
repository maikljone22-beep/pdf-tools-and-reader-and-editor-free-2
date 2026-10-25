package com.smartdocs.editor.core.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// ---- brand ----
val Primary = Color(0xFF1565C0)       // deep blue
val PrimaryDark = Color(0xFFABC7FF)   // primary on dark surfaces
val Accent = Color(0xFFFFB300)        // warm amber accent
val Teal = Color(0xFF00897B)

internal val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    inversePrimary = PrimaryDark,
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2F0E6),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF8B5000),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF2C1600),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FE),
    onBackground = Color(0xFF191C21),
    surface = Color(0xFFF8F9FE),
    onSurface = Color(0xFF191C21),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceTint = Primary,
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFEFF0F7),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    scrim = Color.Black,
    surfaceBright = Color(0xFFF8F9FE),
    surfaceDim = Color(0xFFD8DAE0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F3F9),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE0E2E8),
)

internal val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = Color(0xFF002F65),
    primaryContainer = Color(0xFF0D4A94),
    onPrimaryContainer = Color(0xFFD7E3FF),
    inversePrimary = Primary,
    secondary = Color(0xFF80D6C8),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFB2F0E6),
    tertiary = Color(0xFFFFB95C),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3C00),
    onTertiaryContainer = Color(0xFFFFDDB3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE1E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE1E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceTint = PrimaryDark,
    inverseSurface = Color(0xFFE1E2E9),
    inverseOnSurface = Color(0xFF2E3036),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    scrim = Color.Black,
    surfaceBright = Color(0xFF37393E),
    surfaceDim = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2025),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

/** A colored icon tile: soft container + strong glyph color. */
@Immutable
data class TileColors(val container: Color, val content: Color)

/** Category palette used for icon tiles (tools, quick actions, settings groups). */
@Immutable
data class BrandTiles(
    val blue: TileColors,
    val teal: TileColors,
    val amber: TileColors,
    val purple: TileColors,
    val rose: TileColors,
    val green: TileColors,
) {
    val all: List<TileColors> get() = listOf(blue, teal, amber, purple, rose, green)
    operator fun get(i: Int): TileColors = all[((i % all.size) + all.size) % all.size]
}

internal val LightTiles = BrandTiles(
    blue = TileColors(Color(0xFFDCE8FF), Color(0xFF1565C0)),
    teal = TileColors(Color(0xFFD2F4EE), Color(0xFF00796B)),
    amber = TileColors(Color(0xFFFFEDC7), Color(0xFFB26A00)),
    purple = TileColors(Color(0xFFEDE3FF), Color(0xFF6A3FC8)),
    rose = TileColors(Color(0xFFFFE1E6), Color(0xFFC2185B)),
    green = TileColors(Color(0xFFE0F3D8), Color(0xFF2E7D32)),
)

internal val DarkTiles = BrandTiles(
    blue = TileColors(Color(0xFF1B3358), Color(0xFFABC7FF)),
    teal = TileColors(Color(0xFF0F3B37), Color(0xFF80D6C8)),
    amber = TileColors(Color(0xFF433013), Color(0xFFFFC56E)),
    purple = TileColors(Color(0xFF33284D), Color(0xFFD0BCFF)),
    rose = TileColors(Color(0xFF4A2030), Color(0xFFFFB0C8)),
    green = TileColors(Color(0xFF233A22), Color(0xFFA5D99B)),
)
