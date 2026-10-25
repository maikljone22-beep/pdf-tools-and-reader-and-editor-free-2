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
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
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
