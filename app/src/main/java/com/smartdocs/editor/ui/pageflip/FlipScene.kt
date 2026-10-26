package com.smartdocs.editor.ui.pageflip

import android.graphics.Bitmap
import android.graphics.RectF
import com.smartdocs.editor.core.pageflip.PageSide
import com.smartdocs.editor.core.pageflip.PfAxis
import com.smartdocs.editor.core.pageflip.PfEffect

/** One page of a screen as drawn by a transition: viewport rect (px), side in the spread, snapshot (null = blank). */
class PageSnap(val page: Int, val rect: RectF, val side: PageSide, val bitmap: Bitmap?)

/** One screen (a single page or a spread). */
class ItemSnap(val item: Int, val pages: List<PageSnap>) {
    fun onSide(side: PageSide): PageSnap? = pages.firstOrNull { it.side == side }
    val bounds: RectF
        get() = RectF().also { r -> pages.forEachIndexed { i, p -> if (i == 0) r.set(p.rect) else r.union(p.rect) } }
}

/**
 * One frame of a transition between the earlier screen [a] and the later screen [b] at progress [t]
 * (0 = [a] fully shown, 1 = [b] fully shown). A backward turn is the same scene played from 1 to 0, so every effect
 * reverses naturally.
 *
 * Curl effects: [finger] is the grabbed point in leaf space (px from the spine top, x towards the free edge) or null
 * for the automatic path; [grabY] 0..1 is the grabbed height. [stack] = intermediate screens of a multi-page jump
 * (low-resolution leaves turned in sequence between [a] and [b]).
 */
class FlipScene(
    val effect: PfEffect,
    val axis: PfAxis,
    val rtl: Boolean,
    val a: ItemSnap,
    val b: ItemSnap,
    val t: Float,
    val finger: FloatArray? = null,
    val grabY: Float = 1f,
    val stack: List<ItemSnap> = emptyList(),
    /** The leaf is a rigid cover board (cover open / back cover close in a cover layout). */
    val hardCover: Boolean = false,
)

/** Drawing options shared by the reader host and the live previews. */
class FlipStyle(
    val intensity: Float,
    val shadows: Boolean,
    val curvature: Float,
    val thickness: Boolean,
    /** Viewport background (ARGB). */
    val background: Int,
    val density: Float,
    /** Pages before the first page of [FlipScene.a] / after the last page of [FlipScene.b] (paper stack edges). */
    val pagesBefore: Int = 0,
    val pagesAfter: Int = 0,
    /** Book decorations (spine shadow, stack edges) — the BOOK effect. */
    val book: Boolean = false,
    /** Page colour preset (ARGB, white = none) applied with the same mapping as the reader's page tint. */
    val pageColor: Long = 0xFFFFFFFFL,
)
