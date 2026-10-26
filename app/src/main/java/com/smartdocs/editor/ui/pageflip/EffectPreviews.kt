package com.smartdocs.editor.ui.pageflip

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pageflip.PageFlipPrefs
import com.smartdocs.editor.core.pageflip.PageSide
import com.smartdocs.editor.core.pageflip.PfEffect
import com.smartdocs.editor.core.pageflip.PfSpread
import com.smartdocs.editor.ui.reader.ReaderState
import kotlin.math.min
import kotlin.math.roundToInt

/** Label / description resources of an effect. */
fun PfEffect.labelRes(): Int = when (this) {
    PfEffect.CURL -> R.string.pf_fx_curl; PfEffect.BOOK -> R.string.pf_fx_book; PfEffect.SLIDE -> R.string.pf_fx_slide
    PfEffect.REVEAL -> R.string.pf_fx_reveal; PfEffect.PARALLAX -> R.string.pf_fx_parallax; PfEffect.FADE -> R.string.pf_fx_fade
    PfEffect.SCALE_FADE -> R.string.pf_fx_scale_fade; PfEffect.ZOOM -> R.string.pf_fx_zoom; PfEffect.WIPE -> R.string.pf_fx_wipe
    PfEffect.ROTATE -> R.string.pf_fx_rotate; PfEffect.ROTATE_3D -> R.string.pf_fx_rotate3d; PfEffect.CUBE -> R.string.pf_fx_cube
    PfEffect.FLIP_CARD -> R.string.pf_fx_flip; PfEffect.NONE -> R.string.pf_fx_none
}

fun PfEffect.descRes(): Int = when (this) {
    PfEffect.CURL -> R.string.pf_fx_curl_d; PfEffect.BOOK -> R.string.pf_fx_book_d; PfEffect.SLIDE -> R.string.pf_fx_slide_d
    PfEffect.REVEAL -> R.string.pf_fx_reveal_d; PfEffect.PARALLAX -> R.string.pf_fx_parallax_d; PfEffect.FADE -> R.string.pf_fx_fade_d
    PfEffect.SCALE_FADE -> R.string.pf_fx_scale_fade_d; PfEffect.ZOOM -> R.string.pf_fx_zoom_d; PfEffect.WIPE -> R.string.pf_fx_wipe_d
    PfEffect.ROTATE -> R.string.pf_fx_rotate_d; PfEffect.ROTATE_3D -> R.string.pf_fx_rotate3d_d; PfEffect.CUBE -> R.string.pf_fx_cube_d
    PfEffect.FLIP_CARD -> R.string.pf_fx_flip_d; PfEffect.NONE -> R.string.pf_fx_none_d
}

/**
 * Four sample pages for the previews: pages of the open document from the current one (rendered small through the
 * session cache — not owned here), or generated sample pages when no document is open (owned, recycled on dispose).
 */
@Composable
fun rememberSamplePages(state: ReaderState?, widthPx: Int = 220): List<Bitmap?> {
    val session = state?.session
    val generated = remember(session) { if (session == null) (1..4).map { samplePage(it, widthPx, (widthPx * 1.414f).roundToInt()) } else emptyList() }
    DisposableEffect(generated) { onDispose { generated.forEach { runCatching { it.recycle() } } } }
    if (session == null || state == null) return generated
    val start = state.currentPage
    val pages by produceState<List<Bitmap?>>(listOf(null, null, null, null), session, start) {
        value = (0 until 4).map { k ->
            val p = (start + k).coerceAtMost(session.pageCount - 1).coerceAtLeast(0)
            val (pw, ph) = state.pageSize(p)
            runCatching { session.render(p, widthPx, (widthPx * ph / pw.coerceAtLeast(1f)).roundToInt().coerceIn(1, 2000), state.nightMode, true) }.getOrNull()
        }
    }
    return pages
}

/** A generated sample page: heading, text lines and a big page number (used when no document is open). */
fun samplePage(n: Int, w: Int, h: Int): Bitmap {
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    c.drawColor(android.graphics.Color.WHITE)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val hues = intArrayOf(0xFF3F51B5.toInt(), 0xFF00897B.toInt(), 0xFFF4511E.toInt(), 0xFF8E24AA.toInt())
    p.color = hues[(n - 1).mod(hues.size)]
    c.drawRect(w * 0.1f, h * 0.07f, w * 0.75f, h * 0.12f, p)
    p.color = 0xFFBDBDBD.toInt()
    var y = h * 0.18f
    var i = 0
    while (y < h * 0.8f) {
        val len = if (i % 5 == 4) 0.55f else 0.8f
        c.drawRect(w * 0.1f, y, w * (0.1f + len), y + h * 0.012f, p)
        y += h * 0.035f; i++
    }
    p.color = 0xFF424242.toInt(); p.textSize = h * 0.09f; p.textAlign = Paint.Align.CENTER
    c.drawText(n.toString(), w / 2f, h * 0.94f, p)
    return b
}

/**
 * Live preview of [effect] with the current [prefs] on the sample pages: loops "turn forward, pause, turn back".
 * [dragT] (0..1) overrides the loop while the user drags on a large preview.
 */
@Composable
fun EffectPreview(effect: PfEffect, prefs: PageFlipPrefs, rtl: Boolean, samples: List<Bitmap?>, modifier: Modifier = Modifier, animate: Boolean = true, interactive: Boolean = false) {
    val renderer = remember { FlipRenderer() }
    val density = LocalDensity.current.density
    val bg = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val period = ((if (effect == PfEffect.NONE) 400 else effect.baseMs) / prefs.speed).roundToInt().coerceAtLeast(150)
    val loop = if (animate) rememberInfiniteTransition(label = "pf_preview").animateFloat(
        initialValue = 0f, targetValue = 0f,
        animationSpec = infiniteRepeatable(keyframes {
            durationMillis = 2 * period + 1500
            0f at 0
            0f at 600 using FastOutSlowInEasing
            1f at 600 + period
            1f at 1350 + period using FastOutSlowInEasing
            0f at 1350 + 2 * period
        }),
        label = "pf_t",
    ) else remember { mutableFloatStateOf(0.42f) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val dragMod = if (!interactive) Modifier else Modifier.pointerInput(rtl) {
        detectHorizontalDragGestures(
            onDragStart = { drag = 0f },
            onDragEnd = { drag = null }, onDragCancel = { drag = null },
        ) { ch, dx ->
            val d = drag ?: 0f
            drag = (d + (if (rtl) dx else -dx) / size.width).coerceIn(0f, 1f)
            ch.consume()
        }
    }
    Canvas(modifier.then(dragMod)) {
        val w = size.width; val h = size.height
        val twoUp = effect == PfEffect.BOOK || (effect.book && prefs.spread != PfSpread.SINGLE)
        val (a, b) = sampleItems(w, h, samples, rtl, twoUp)
        val style = FlipStyle(prefs.intensity, prefs.shadows, prefs.curvature, prefs.thickness, bg, density * 0.6f,
            pagesBefore = 12, pagesAfter = 40, book = effect == PfEffect.BOOK)
        val t = drag ?: loop.value
        drawIntoCanvas { renderer.draw(it.nativeCanvas, w, h, FlipScene(effect, prefs.axis, rtl, a, b, t, null, 0.86f), style) }
    }
}

private fun sampleItems(w: Float, h: Float, s: List<Bitmap?>, rtl: Boolean, twoUp: Boolean): Pair<ItemSnap, ItemSnap> {
    fun bmp(i: Int) = s.getOrNull(i)?.takeUnless { it.isRecycled }
    fun aspect(i: Int) = bmp(i)?.let { it.width.toFloat() / it.height } ?: 0.707f
    val m = min(w, h) * 0.08f
    if (!twoUp) {
        val a = aspect(0)
        val ph = min(h - 2 * m, (w - 2 * m) / a); val pw = ph * a
        val r = RectF((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2)
        return ItemSnap(0, listOf(PageSnap(0, r, PageSide.CENTER, bmp(0)))) to ItemSnap(1, listOf(PageSnap(1, RectF(r), PageSide.CENTER, bmp(1))))
    }
    val a = aspect(0)
    val ph = min(h - 2 * m, (w - 2 * m) / (2 * a)); val pw = ph * a
    val left = RectF(w / 2 - pw, (h - ph) / 2, w / 2, (h + ph) / 2)
    val right = RectF(w / 2, (h - ph) / 2, w / 2 + pw, (h + ph) / 2)
    val first = if (rtl) PageSide.RIGHT else PageSide.LEFT
    val second = if (rtl) PageSide.LEFT else PageSide.RIGHT
    fun rect(side: PageSide) = RectF(if (side == PageSide.LEFT) left else right)
    val ia = ItemSnap(0, listOf(PageSnap(0, rect(first), first, bmp(0)), PageSnap(1, rect(second), second, bmp(1))))
    val ib = ItemSnap(1, listOf(PageSnap(2, rect(first), first, bmp(2)), PageSnap(3, rect(second), second, bmp(3))))
    return ia to ib
}
