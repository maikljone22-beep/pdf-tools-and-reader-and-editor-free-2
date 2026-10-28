package com.smartdocs.editor.ui.reader.view

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ViewMode

/**
 * View-related state of the reader that is not part of the core [ReaderState]: facing separator, crop mode,
 * floating magnifier, auto-flip interval, orientation lock, screen timeout and reflow font scale.
 * Persisted values live in SharedPreferences "view".
 */
class ViewExtras(private val state: ReaderState) {
    private val prefs = state.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Gap between the two pages of a facing / cover-facing spread (reference default: on). */
    var facingSeparator by mutableStateOf(prefs.getBoolean(K_SEP, true)); private set
    /** Auto-flip interval in seconds, 20..100 step 10, default 60 (persisted — the reference forgets it). */
    var autoFlipInterval by mutableIntStateOf(snapFlip(prefs.getInt(K_FLIP, 60))); private set
    /** 0..1 progress towards the next automatic page turn (drives the circular indicator). */
    var autoFlipProgress by mutableFloatStateOf(0f)
    var orientationLock by mutableStateOf(prefs.getBoolean(K_LOCK, false)); private set
    /** true = locked in landscape, false = portrait (only meaningful when [orientationLock]). */
    var lockedLandscape by mutableStateOf(prefs.getBoolean(K_LOCK_LAND, false)); private set
    /** Keep-screen-on timeout in seconds after the last touch; -1 = never turn off. */
    var screenTimeoutSec by mutableIntStateOf(prefs.getInt(K_TIMEOUT, 600)); private set
    /** Reflow font scale: ×1.25 / ×0.8 steps within 1..8 (as the reference). */
    var reflowScale by mutableFloatStateOf(prefs.getFloat(K_REFLOW, 1.25f).coerceIn(REFLOW_MIN, REFLOW_MAX)); private set
    /** Floating "pan & zoom" magnifier window visible. */
    var magnifier by mutableStateOf(false)
    /** uptimeMillis of the last touch anywhere in the reader (throttled), resets the screen timeout. */
    var lastTouch by mutableLongStateOf(0L)

    val crop = CropController(state)

    fun updateFacingSeparator(on: Boolean) { facingSeparator = on; prefs.edit().putBoolean(K_SEP, on).apply() }

    fun updateAutoFlipInterval(sec: Int) { autoFlipInterval = snapFlip(sec); prefs.edit().putInt(K_FLIP, autoFlipInterval).apply() }

    fun updateOrientationLock(on: Boolean, landscape: Boolean) {
        orientationLock = on; lockedLandscape = landscape
        prefs.edit().putBoolean(K_LOCK, on).putBoolean(K_LOCK_LAND, landscape).apply()
    }

    /** Value for Activity.requestedOrientation. */
    fun requestedOrientation(): Int = when {
        !orientationLock -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        lockedLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    fun updateScreenTimeout(sec: Int) { screenTimeoutSec = sec; prefs.edit().putInt(K_TIMEOUT, sec).apply() }

    fun reflowLarger() = setReflow(reflowScale * 1.25f)
    fun reflowSmaller() = setReflow(reflowScale * 0.8f)
    private fun setReflow(v: Float) { reflowScale = v.coerceIn(REFLOW_MIN, REFLOW_MAX); prefs.edit().putFloat(K_REFLOW, reflowScale).apply() }

    // ---------------- page stepping (spread aware) ----------------

    fun spread(): PageSpread = PageSpread(state.viewMode)

    /** First page of the screen [delta] screens away from the current one, or null when out of range. */
    fun flipTarget(delta: Int): Int? = spread().step(state.currentPage, delta, state.pageCount)

    fun canFlip(delta: Int): Boolean = flipTarget(delta) != null

    /** Turn [delta] screens (two pages in facing modes, honouring the cover page). */
    fun flip(delta: Int): Boolean {
        val t = flipTarget(delta) ?: return false
        state.jumpTo(t); return true
    }

    /**
     * Hardware keys: volume (when enabled) flips screens like the reference (up = previous, down = next);
     * arrows / page up / page down always flip (mirrored left/right in RTL).
     */
    fun handleKeyCode(code: Int, volumeKeys: Boolean, rtl: Boolean): Boolean = when (code) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> volumeKeys && state.session != null && (flip(1) || true)
        KeyEvent.KEYCODE_VOLUME_UP -> volumeKeys && state.session != null && (flip(-1) || true)
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> flip(1)
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_UP -> flip(-1)
        KeyEvent.KEYCODE_DPAD_RIGHT -> flip(if (rtl) -1 else 1)
        KeyEvent.KEYCODE_DPAD_LEFT -> flip(if (rtl) 1 else -1)
        else -> false
    }

    companion object {
        const val PREFS = "view"
        private const val K_SEP = "have_facing_separator"
        private const val K_FLIP = "auto_flip_interval"
        private const val K_LOCK = "orientation_lock"
        private const val K_LOCK_LAND = "orientation_lock_landscape"
        private const val K_TIMEOUT = "screen_on_time"
        private const val K_REFLOW = "reflow_scale"
        const val FLIP_MIN = 20
        const val FLIP_MAX = 100
        const val FLIP_STEP = 10
        const val REFLOW_MIN = 1f
        const val REFLOW_MAX = 8f
        /** Base reflow font size (sp) multiplied by [reflowScale]. */
        const val REFLOW_BASE_SP = 14f
        /** Screen timeout choices (seconds) in the reference order; -1 = never. */
        val TIMEOUTS = listOf(30, 60, 120, 300, 600, 1800, 3600, -1)

        fun snapFlip(sec: Int): Int = (((sec + FLIP_STEP / 2) / FLIP_STEP) * FLIP_STEP).coerceIn(FLIP_MIN, FLIP_MAX)
    }
}

/**
 * Pager item <-> page mapping for the paged modes.
 * SINGLE / CONTINUOUS: one page per item. FACING: (0,1) (2,3)…  COVER: (0) (1,2) (3,4)…
 */
class PageSpread(val mode: ViewMode) {
    val paired: Boolean get() = mode == ViewMode.FACING || mode == ViewMode.COVER
    val perItem: Int get() = if (paired) 2 else 1

    fun itemCount(pageCount: Int): Int = when (mode) {
        ViewMode.FACING -> (pageCount + 1) / 2
        ViewMode.COVER -> if (pageCount <= 1) pageCount else 1 + pageCount / 2
        else -> pageCount
    }

    fun pageToItem(page: Int): Int = when (mode) {
        ViewMode.FACING -> (page / 2).coerceAtLeast(0)
        ViewMode.COVER -> if (page <= 0) 0 else (page + 1) / 2
        else -> page.coerceAtLeast(0)
    }

    fun firstPage(item: Int): Int = when (mode) {
        ViewMode.FACING -> item * 2
        ViewMode.COVER -> if (item <= 0) 0 else item * 2 - 1
        else -> item
    }

    /**
     * First page of the screen [delta] screens away from the screen showing [current], or null when out of range
     * (pure: unit-tested; tab-view-core V-09 routes Space / Ctrl+PgDn / Ctrl+PgUp through it).
     */
    fun step(current: Int, delta: Int, pageCount: Int): Int? {
        if (pageCount <= 0) return null
        val item = pageToItem(current) + delta
        if (item < 0 || item >= itemCount(pageCount)) return null
        return firstPage(item)
    }

    fun pages(item: Int, pageCount: Int): List<Int> {
        val f = firstPage(item)
        val list = when {
            !paired -> listOf(f)
            mode == ViewMode.COVER && item == 0 -> listOf(0)
            else -> listOf(f, f + 1)
        }
        return list.filter { it in 0 until pageCount }
    }
}
