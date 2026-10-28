package com.smartdocs.editor.ui.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.R

/** audit 5 C-22: a spoken name for a colour swatch (TalkBack), from its hue / lightness. */
enum class ColorKind { BLACK, WHITE, GRAY, RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, PINK }

/** The colour family of [rgb] (0xRRGGBB). Pure, see ColorNamesTest. */
fun colorKind(rgb: Int): ColorKind {
    val r = ((rgb shr 16) and 0xFF) / 255f; val g = ((rgb shr 8) and 0xFF) / 255f; val b = (rgb and 0xFF) / 255f
    val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
    val l = (max + min) / 2f
    val s = if (d == 0f) 0f else d / (1f - kotlin.math.abs(2f * l - 1f)).coerceAtLeast(1e-6f)
    if (d < 0.08f || s < 0.15f) return when { l < 0.2f -> ColorKind.BLACK; l > 0.85f -> ColorKind.WHITE; else -> ColorKind.GRAY }
    var h = when (max) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    if (h < 0f) h += 360f
    return when {
        h < 15f || h >= 345f -> ColorKind.RED
        h < 45f -> ColorKind.ORANGE
        h < 70f -> ColorKind.YELLOW
        h < 165f -> ColorKind.GREEN
        h < 200f -> ColorKind.CYAN
        h < 260f -> ColorKind.BLUE
        h < 290f -> ColorKind.PURPLE
        else -> ColorKind.PINK
    }
}

/** Localized name of [rgb] for content descriptions. */
@Composable
fun colorLabel(rgb: Int): String = stringResource(when (colorKind(rgb and 0xFFFFFF)) {
    ColorKind.BLACK -> R.string.ttcx_color_black; ColorKind.WHITE -> R.string.ttcx_color_white; ColorKind.GRAY -> R.string.ttcx_color_gray
    ColorKind.RED -> R.string.ttcx_color_red; ColorKind.ORANGE -> R.string.ttcx_color_orange; ColorKind.YELLOW -> R.string.ttcx_color_yellow
    ColorKind.GREEN -> R.string.ttcx_color_green; ColorKind.CYAN -> R.string.ttcx_color_cyan; ColorKind.BLUE -> R.string.ttcx_color_blue
    ColorKind.PURPLE -> R.string.ttcx_color_purple; ColorKind.PINK -> R.string.ttcx_color_pink
})
