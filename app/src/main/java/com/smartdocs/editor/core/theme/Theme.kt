package com.smartdocs.editor.core.theme

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat

enum class ThemeMode { SYSTEM, LIGHT, DARK }

val LocalBrandTiles = staticCompositionLocalOf { LightTiles }
val LocalIsDark = staticCompositionLocalOf { false }

/** Brand extras that Material's ColorScheme has no slot for. */
object Brand {
    val tiles: BrandTiles @Composable @ReadOnlyComposable get() = LocalBrandTiles.current
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalIsDark.current
}

@Composable
fun SmartDocsTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    rtl: Boolean = true,
    dynamicColor: Boolean = false, // off: brand consistency
    accent: Long = AccentColors.DEFAULT,   // NVE-shell: AccentColors.DYNAMIC = Android 12+ dynamic colour; the accent colour itself is NpfPrefs.accent
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    // nav-prefs accent (NpfPrefs.accent) is the colour; NVE-shell adds the Android 12+ dynamic colour option
    val base = when {
        (dynamicColor || accent == AccentColors.DYNAMIC) && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    val scheme = if (accent == AccentColors.DYNAMIC) base else npfAccent(base, dark, com.smartdocs.editor.core.data.NpfPrefs.accent)   // nav-prefs (SHL-13): accent colour
    // status/navigation bar icons follow the app theme (not only the system one)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            var c = view.context
            while (c is ContextWrapper && c !is Activity) c = c.baseContext
            (c as? Activity)?.window?.let { w ->
                val ctl = WindowCompat.getInsetsController(w, view)
                ctl.isAppearanceLightStatusBars = !dark
                ctl.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        LocalBrandTiles provides if (dark) DarkTiles else LightTiles,
        LocalIsDark provides dark,
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

/** nav-prefs (SHL-13): replace the primary colours with the accent [rgb] (0 = keep the brand scheme). */
fun npfAccent(base: androidx.compose.material3.ColorScheme, dark: Boolean, rgb: Int): androidx.compose.material3.ColorScheme {
    if (rgb == 0) return base
    val c = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or rgb)
    fun mix(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color, t: Float) =
        androidx.compose.ui.graphics.Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)
    val white = androidx.compose.ui.graphics.Color.White; val black = androidx.compose.ui.graphics.Color.Black
    val primary = if (dark) mix(c, white, 0.35f) else c
    val onPrimary = if (primary.npfLum() > 0.5f) black else white
    val container = if (dark) mix(c, black, 0.55f) else mix(c, white, 0.8f)
    val onContainer = if (dark) mix(c, white, 0.8f) else mix(c, black, 0.6f)
    return base.copy(primary = primary, onPrimary = onPrimary, primaryContainer = container, onPrimaryContainer = onContainer,
        inversePrimary = if (dark) c else mix(c, white, 0.4f), surfaceTint = primary)
}

private fun androidx.compose.ui.graphics.Color.npfLum(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
