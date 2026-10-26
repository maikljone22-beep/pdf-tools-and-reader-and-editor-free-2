package com.smartdocs.editor.ui.pageflip

import android.graphics.Bitmap
import androidx.compose.runtime.mutableIntStateOf
import com.smartdocs.editor.core.pageflip.BoundedLru
import com.smartdocs.editor.core.pdf.PdfSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Page snapshots for transitions: bitmaps of the pages around the current screen at display size, rendered by
 * [PdfSession] on its IO dispatcher. Sizes follow PageCanvas' render key (width quantised to 48 px, max 3200 px),
 * so a snapshot is normally the very bitmap the interactive page already shows (a session cache hit) and no
 * second copy is made.
 *
 * Bitmaps returned by PdfSession are owned by the session's own LRU and shared with PageCanvas; this cache only
 * keeps references within a byte budget and NEVER recycles them (evicting drops the reference, the GC frees it once
 * nobody draws it any more). Only the pages of the current screen ±1 (±2 when memory allows) are requested.
 */
class PageSnapshotCache(private val session: PdfSession, private val night: Boolean, lowRam: Boolean) {
    data class Key(val page: Int, val w: Int, val h: Int)

    /** Snapshot resolution factor (low-RAM devices render at 0.6× and the GPU scales it up while moving). */
    val scale: Float = if (lowRam) 0.6f else 1f
    private val lru = BoundedLru<Key, Bitmap>(if (lowRam) 24L shl 20 else 48L shl 20, { it.byteCount.toLong() })
    private val inFlight = HashMap<Key, Job>()
    /** Bumped whenever a snapshot arrives so canvases that read it redraw. */
    val version = mutableIntStateOf(0)

    /** Exact snapshot if cached, else the largest cached one of the same page (scaled while drawing), else null. */
    fun peek(page: Int, w: Int, h: Int): Bitmap? = synchronized(lru) {
        val k = key(page, w, h)
        lru[k]?.takeUnless { it.isRecycled } ?: lru.keys.filter { it.page == page }.maxByOrNull { it.w }?.let { lru[it] }?.takeUnless { it.isRecycled }
    }

    /** Make sure a snapshot of [page] at [w]×[h] display px exists (renders in [scope] when missing). */
    fun request(scope: CoroutineScope, page: Int, w: Int, h: Int) {
        if (page !in 0 until session.pageCount || w <= 0 || h <= 0 || session.isClosed) return
        val k = key(page, w, h)
        synchronized(lru) { if (lru.contains(k) || inFlight.containsKey(k)) return }
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val bmp = try { runCatching { session.render(page, k.w, k.h, night, true) }.getOrNull() }
            finally { synchronized(lru) { inFlight.remove(k) } }
            if (bmp != null && !bmp.isRecycled) {
                synchronized(lru) { lru.put(k, bmp) }
                version.intValue++
            }
        }
        synchronized(lru) { inFlight[k] = job }
        job.start()
    }

    /** Small thumbnail (stack leaves, scrubber bubble) — width [w] px. */
    fun requestThumb(scope: CoroutineScope, page: Int, w: Int, aspect: Float) =
        request(scope, page, w, (w / aspect.coerceAtLeast(0.05f)).roundToInt())

    /** Forget pages farther than [keep] from [center] (memory stays bounded while reading a long document). */
    fun retainAround(center: Int, keep: Int) = synchronized(lru) {
        lru.removeIf { it.page < center - keep || it.page > center + keep }
        inFlight.entries.removeAll { (k, j) -> (k.page < center - keep || k.page > center + keep).also { if (it) j.cancel() } }
    }

    fun clear() = synchronized(lru) { lru.clear(); inFlight.values.forEach { it.cancel() }; inFlight.clear() }

    private fun key(page: Int, w: Int, h: Int): Key {
        val (rw, rh) = renderSize(w * scale, h * scale)
        return Key(page, rw, rh)
    }

    companion object {
        private const val MAX_RENDER_PX = 3200f

        /** PageCanvas' render size for a page displayed at [w]×[h] px (same quantisation -> shared cache entries). */
        fun renderSize(w: Float, h: Float): Pair<Int, Int> {
            if (w <= 0f || h <= 0f) return 1 to 1
            val factor = min(1f, MAX_RENDER_PX / max(w, h))
            val rw = ((w * factor).roundToInt() / 48 * 48).coerceAtLeast(48)
            val rh = (rw * h / w).roundToInt().coerceAtLeast(1)
            return rw to rh
        }
    }
}
