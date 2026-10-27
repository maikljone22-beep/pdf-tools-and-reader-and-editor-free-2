package com.smartdocs.editor.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * SHL-13 accent colours (NVE-shell): the app's colour scheme re-tinted around a user-chosen accent.
 * 0 = app default, 1 = Android 12+ dynamic colour, otherwise 0xRRGGBB.
 */
object AccentColors {
    const val DEFAULT = 0L
    const val DYNAMIC = 1L

    /** Offered accents (Material-like hues). */
    val PRESETS: List<Long> = listOf(0x1565C0, 0x00897B, 0x2E7D32, 0x6A1B9A, 0xC2185B, 0xD84315, 0xF9A825, 0x5D4037, 0x455A64, 0x283593)

    private fun mix(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t, blue = a.blue + (b.blue - a.blue) * t, alpha = 1f,
    )

    private fun on(c: Color) = if (c.luminance() > 0.45f) Color(0xFF111111) else Color.White

    fun apply(base: ColorScheme, rgb: Long, dark: Boolean): ColorScheme {
        val a = Color(0xFF000000 or (rgb and 0xFFFFFF))
        return if (!dark) {
            val container = mix(a, Color.White, 0.80f)
            val sec = mix(a, Color(0xFF607D8B), 0.45f)
            base.copy(
                primary = a, onPrimary = on(a), primaryContainer = container, onPrimaryContainer = mix(a, Color.Black, 0.55f),
                inversePrimary = mix(a, Color.White, 0.45f), surfaceTint = a,
                secondary = sec, onSecondary = on(sec), secondaryContainer = mix(sec, Color.White, 0.80f), onSecondaryContainer = mix(sec, Color.Black, 0.6f),
            )
        } else {
            val p = mix(a, Color.White, 0.40f)
            val sec = mix(p, Color(0xFF90A4AE), 0.45f)
            base.copy(
                primary = p, onPrimary = on(p), primaryContainer = mix(a, Color.Black, 0.45f), onPrimaryContainer = mix(a, Color.White, 0.80f),
                inversePrimary = a, surfaceTint = p,
                secondary = sec, onSecondary = on(sec), secondaryContainer = mix(sec, Color.Black, 0.55f), onSecondaryContainer = mix(sec, Color.White, 0.8f),
            )
        }
    }
}
