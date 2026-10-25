package com.smartdocs.editor.ui.reader.view

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Display-only crop ("crop mode" of the reference): pages are shown cut to their content box.
 * The file is never changed. Rects are fractions (0..1) of the unrotated page, origin top-left.
 *
 * - automatic: each page is rendered small once, the non-white bounding box (+ margin) is cached per page;
 * - manual: one rect for every page, optionally mirrored on even pages ("symmetric").
 *
 * The page canvas keeps its full-page geometry; the page view only scales/offsets it inside a clipped box,
 * so annotation, selection and link coordinates are unaffected.
 */
class CropController(private val state: ReaderState) {
    var enabled by mutableStateOf(false); private set
    /** Manual rect applied to all pages (null = automatic per-page detection). */
    var manual by mutableStateOf<Rect?>(null); private set
    var symmetric by mutableStateOf(false); private set

    private val auto = mutableStateMapOf<Int, Rect>()
    private val running = HashSet<Int>()
    private var autoVersion = -1
    private var autoKey: String? = null

    fun enableAuto() { manual = null; enabled = true; persist() }

    fun enableManual(r: Rect, symmetric: Boolean) {
        manual = normalise(r); this.symmetric = symmetric; enabled = true; persist()
    }

    fun disable() { enabled = false; manual = null; persist() }

    /** Crop of [page] in unrotated fractions, or null when the page is shown whole (or not detected yet). */
    fun rectFor(page: Int): Rect? {
        if (!enabled) return null
        manual?.let { m -> return if (symmetric && page % 2 == 1) Rect(1f - m.right, m.top, 1f - m.left, m.bottom) else m }
        return auto[page]?.takeIf { it != FULL }
    }

    /** Detected content box of [page] (for the manual editor's "reset"), or null if not known yet. */
    fun detected(page: Int): Rect? = auto[page]

    /** Start detecting [page] in the background if automatic crop needs it. */
    fun ensure(page: Int) {
        if (!enabled || manual != null) return
        val s = state.session ?: return
        if (state.renderVersion != autoVersion) { auto.clear(); synchronized(running) { running.clear() }; autoVersion = state.renderVersion }
        if (auto.containsKey(page)) return
        synchronized(running) { if (!running.add(page)) return }
        state.scope.launch {
            val r = runCatching { detect(page) }.getOrNull() ?: FULL
            auto[page] = r
            synchronized(running) { running.remove(page) }
            if (s.isClosed) synchronized(running) { running.clear() }
        }
    }

    /** Render [page] about 160 px wide and find the bounding box of non-white pixels with a safety margin. */
    suspend fun detect(page: Int): Rect {
        val s = state.session ?: return FULL
        if (s.isClosed || page !in 0 until s.pageCount) return FULL
        val (pw, ph) = state.pageSize(page)
        val w = 160
        val h = (w * ph / pw.coerceAtLeast(1f)).roundToInt().coerceIn(16, 1200)
        val bmp = s.render(page, w, h, false, true)
        return withContext(Dispatchers.Default) {
            val bw = bmp.width; val bh = bmp.height
            val px = IntArray(bw * bh)
            bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
            var l = bw; var t = bh; var r = -1; var b = -1
            for (y in 0 until bh) {
                val row = y * bw
                for (x in 0 until bw) {
                    val c = px[row + x]
                    if ((c ushr 24) < 16) continue                       // transparent
                    val rr = (c shr 16) and 0xFF; val gg = (c shr 8) and 0xFF; val bb = c and 0xFF
                    if (rr < THRESHOLD || gg < THRESHOLD || bb < THRESHOLD) {
                        if (x < l) l = x; if (x > r) r = x
                        if (y < t) t = y; if (y > b) b = y
                    }
                }
            }
            if (r < 0 || b < 0) return@withContext FULL                 // blank page: show it whole
            val mx = MARGIN; val my = MARGIN * bw / bh.toFloat()
            var fl = l / bw.toFloat() - mx; var fr = (r + 1) / bw.toFloat() + mx
            var ft = t / bh.toFloat() - my; var fb = (b + 1) / bh.toFloat() + my
            fl = fl.coerceAtLeast(0f); ft = ft.coerceAtLeast(0f); fr = fr.coerceAtMost(1f); fb = fb.coerceAtMost(1f)
            // never zoom into a speck: at least 25% of each dimension
            if (fr - fl < MIN_SIZE) { val c = (fl + fr) / 2; fl = (c - MIN_SIZE / 2).coerceIn(0f, 1f - MIN_SIZE); fr = fl + MIN_SIZE }
            if (fb - ft < MIN_SIZE) { val c = (ft + fb) / 2; ft = (c - MIN_SIZE / 2).coerceIn(0f, 1f - MIN_SIZE); fb = ft + MIN_SIZE }
            val rect = Rect(fl, ft, fr, fb)
            if (rect.width > 0.97f && rect.height > 0.97f) FULL else rect
        }
    }

    // ---------------- per-document persistence ("view" prefs) ----------------

    /** Restore the crop settings saved for the document at [key] (its file path). */
    fun restore(key: String) {
        if (autoKey == key) return
        autoKey = key
        val p = state.context.getSharedPreferences(ViewExtras.PREFS, Context.MODE_PRIVATE)
        val v = p.getString("crop:$key", null) ?: return
        val parts = v.split(',')
        when {
            parts.firstOrNull() == "auto" -> enableAuto()
            parts.firstOrNull() == "manual" && parts.size >= 6 -> {
                val n = parts.drop(1).take(4).mapNotNull { it.toFloatOrNull() }
                if (n.size == 4) enableManual(Rect(n[0], n[1], n[2], n[3]), parts[5] == "1")
            }
        }
    }

    private fun persist() {
        val key = autoKey ?: return
        val p = state.context.getSharedPreferences(ViewExtras.PREFS, Context.MODE_PRIVATE).edit()
        val m = manual
        when {
            !enabled -> p.remove("crop:$key")
            m == null -> p.putString("crop:$key", "auto")
            else -> p.putString("crop:$key", "manual,${m.left},${m.top},${m.right},${m.bottom},${if (symmetric) 1 else 0}")
        }
        p.apply()
    }

    companion object {
        val FULL = Rect(0f, 0f, 1f, 1f)
        private const val THRESHOLD = 236
        private const val MARGIN = 0.025f
        private const val MIN_SIZE = 0.25f

        fun normalise(r: Rect): Rect {
            val l = min(r.left, r.right).coerceIn(0f, 1f); val rr = max(r.left, r.right).coerceIn(0f, 1f)
            val t = min(r.top, r.bottom).coerceIn(0f, 1f); val b = max(r.top, r.bottom).coerceIn(0f, 1f)
            return Rect(l, t, max(rr, l + 0.1f).coerceAtMost(1f), max(b, t + 0.1f).coerceAtMost(1f))
        }

        /** Unrotated page fractions -> fractions of the page as laid out with the view [rotation] (clockwise). */
        fun toLayout(r: Rect, rotation: Int): Rect = when (((rotation % 360) + 360) % 360) {
            90 -> Rect(1f - r.bottom, r.left, 1f - r.top, r.right)
            180 -> Rect(1f - r.right, 1f - r.bottom, 1f - r.left, 1f - r.top)
            270 -> Rect(r.top, 1f - r.right, r.bottom, 1f - r.left)
            else -> r
        }

        /** A rect given in fractions of the displayed (cropped) box -> fractions of the full page layout. */
        fun boxToFull(v: Rect, crop: Rect): Rect = Rect(
            crop.left + v.left * crop.width, crop.top + v.top * crop.height,
            crop.left + v.right * crop.width, crop.top + v.bottom * crop.height,
        )
    }
}
