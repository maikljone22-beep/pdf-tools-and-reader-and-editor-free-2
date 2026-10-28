package com.smartdocs.editor.ui.reader.view

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs
import androidx.compose.ui.draw.drawWithContent

/** A page colour preset: background replaces white, text replaces black (linear colour mapping). */
data class PageColorPreset(val background: Long, val text: Long)

/** The reference's 8 fixed presets, in its order. White = normal rendering. */
val PAGE_COLOR_PRESETS = listOf(
    PageColorPreset(0xFFFFFFFFL, 0xFF323232L),
    PageColorPreset(0xFFE8E8E8L, 0xFF2B2B2BL),
    PageColorPreset(0xFFF0E8D8L, 0xFF323232L),
    PageColorPreset(0xFFCCC5B2L, 0xFF5F3811L),
    PageColorPreset(0xFF99CFA1L, 0xFF323232L),
    PageColorPreset(0xFF2E5073L, 0xFF6D7E88L),
    PageColorPreset(0xFF644F47L, 0xFF95938FL),
    PageColorPreset(0xFF9A9A9AL, 0xFF000000L),
)

/** Text colour matching a page background ([PAGE_COLOR_PRESETS]); black for any other colour. */
fun pageTextColor(background: Long): Long =
    PAGE_COLOR_PRESETS.firstOrNull { (it.background and 0xFFFFFFL) == (background and 0xFFFFFFL) }?.text ?: 0xFF000000L

/** True when the page colour leaves the page as rendered. */
fun isPlainPage(background: Long): Boolean = (background and 0xFFFFFFL) == 0xFFFFFFL

/** Holder so callers can use the tint with `with(PageTintDraw) { drawPageTint(...) }` without imports. */
object PageTintDraw {
    /**
     * Draw the page-colour mapping over an already drawn page bitmap of [w]x[h]:
     * every channel c (0..1) becomes text + c * (background - text), i.e. white -> background, black -> text.
     * Light presets (background brighter than text in every channel): Multiply by (bg - fg), then add fg.
     * Dark presets (background darker): Multiply by (fg - bg), then Difference with fg (= fg - c*(fg - bg)).
     * Mixed presets fall back to a plain multiply of the background colour.
     */
    fun DrawScope.drawPageTint(background: Long, w: Float, h: Float) {
        if (isPlainPage(background)) return
        val bg = background or 0xFF000000L
        val fg = pageTextColor(background) or 0xFF000000L
        val br = ((bg shr 16) and 0xFF).toInt(); val bgc = ((bg shr 8) and 0xFF).toInt(); val bb = (bg and 0xFF).toInt()
        val fr = ((fg shr 16) and 0xFF).toInt(); val fgc = ((fg shr 8) and 0xFF).toInt(); val fb = (fg and 0xFF).toInt()
        val size = Size(w, h)
        when {
            br >= fr && bgc >= fgc && bb >= fb -> {
                drawRect(Color(br - fr, bgc - fgc, bb - fb), Offset.Zero, size, blendMode = BlendMode.Multiply)
                if ((fr or fgc or fb) != 0) drawRect(Color(fr, fgc, fb), Offset.Zero, size, blendMode = BlendMode.Plus)
            }
            // BlendMode.Difference needs API 29; older devices get the plain multiply below
            android.os.Build.VERSION.SDK_INT >= 29 && br <= fr && bgc <= fgc && bb <= fb -> {
                drawRect(Color(abs(fr - br), abs(fgc - bgc), abs(fb - bb)), Offset.Zero, size, blendMode = BlendMode.Multiply)
                drawRect(Color(fr, fgc, fb), Offset.Zero, size, blendMode = BlendMode.Difference)
            }
            else -> drawRect(Color(bg), Offset.Zero, size, blendMode = BlendMode.Multiply)
        }
    }
}

/**
 * tab-view-core V-38: the reader's page colour on a secondary page renderer (thumbnails, split / compare panes,
 * presentation), drawn over the bitmap like PageCanvas does; nothing in night mode (the render is already inverted).
 */
fun androidx.compose.ui.Modifier.pageTint(background: Long, night: Boolean): androidx.compose.ui.Modifier =
    if (night || isPlainPage(background)) this
    else this.then(androidx.compose.ui.Modifier.drawWithContent { drawContent(); with(PageTintDraw) { drawPageTint(background, size.width, size.height) } })
