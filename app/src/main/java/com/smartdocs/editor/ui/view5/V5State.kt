package com.smartdocs.editor.ui.view5

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.core.view5.MUnit
import com.smartdocs.editor.core.view5.MeasureScale
import com.smartdocs.editor.core.view5.PositionHistory
import com.smartdocs.editor.ui.reader.ReaderState
import java.util.WeakHashMap

/** Full-screen surfaces and sheets owned by view5 (one at a time). */
enum class V5Surface { NONE, WORKBENCH, MEASURE_LIST, COMMENTS, STAMPS, SPLIT, COMPARE, PRESENT, COMMANDS, PRINT, SHORTCUTS, OVERLAY_SETTINGS, AUTO_SCROLL, READ_ALOUD }

/** Tools of the page workbench (measure + comment tools that need several taps). */
enum class WTool { DISTANCE, PERIMETER, AREA, CIRCLE, ANGLE, COUNT, CALLOUT, TYPEWRITER, TEXTBOX, TICK, CROSS, AREA_HIGHLIGHT, LINE }

/**
 * Per-reader state of MODULE view5, kept outside ReaderState (weak map keyed by the reader state) so no
 * shared file needs a new field. Overlay toggles and measure settings are remembered in prefs "view5".
 */
class V5State private constructor(val s: ReaderState) {
    val prefs: SharedPreferences = s.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var surface by mutableStateOf(V5Surface.NONE)
    var workbenchTool by mutableStateOf(WTool.DISTANCE)

    // ---- page overlays ----
    var rulers by mutableStateOf(prefs.getBoolean("rulers", false))
    var grid by mutableStateOf(prefs.getBoolean("grid", false))
    var gridMm by mutableFloatStateOf(prefs.getFloat("grid_mm", 10f))
    var readingRuler by mutableStateOf(false)
    var rulerY by mutableFloatStateOf(0.4f)              // band centre, fraction of the page area height
    var rulerHeightDp by mutableFloatStateOf(prefs.getFloat("rr_h", 56f))
    var rulerDim by mutableStateOf(prefs.getBoolean("rr_dim", true))
    var rulerColor by mutableIntStateOf(prefs.getInt("rr_c", 0xFFEB3B))
    var lens by mutableStateOf(false)
    var lensZoom by mutableFloatStateOf(prefs.getFloat("lens_z", 2.5f))

    // ---- auto scroll (continuous mode; paged modes use the existing auto-flip) ----
    var autoScroll by mutableStateOf(false)
    var autoPaused by mutableStateOf(false)
    var autoSpeed by mutableIntStateOf(prefs.getInt("as_speed", 3))      // 1..10

    // ---- position history ----
    val history = PositionHistory()
    var historyVersion by mutableIntStateOf(0); private set
    internal var navigatingHistory = false
    var showHistoryPill by mutableStateOf(false)

    // ---- measuring ----
    var scale by mutableStateOf(MeasureScale.parse(prefs.getString("scale", "") ?: "") ?: MeasureScale(1.0, MUnit.CM, 1.0, MUnit.CM))
    var precision by mutableIntStateOf(prefs.getInt("precision", 2))
    var snap by mutableStateOf(prefs.getBoolean("snap", true))
    /** Snap points to the overlay grid ([gridMm] spacing), for every workbench tool (7.14). */
    var snapGrid by mutableStateOf(prefs.getBoolean("snap_grid", false))
    /** Description of the current scale (7.51), saved with it in the page viewport. */
    var scaleDescription by mutableStateOf(prefs.getString("scale_desc", "") ?: "")
    /** Scales stored in the document (/VP or measurement /Measure), by page (7.47 / 25.10). */
    var docScales by mutableStateOf<Map<Int, com.smartdocs.editor.core.view5.ScaleStore.PageScale>>(emptyMap())
    var ortho by mutableStateOf(false)

    // ---- command search / pins (set by View5ReaderHost) ----
    var env by mutableStateOf<V5Env?>(null)
    var pinsVersion by mutableIntStateOf(0)

    fun open(x: V5Surface) { surface = x }
    fun close() { surface = V5Surface.NONE }

    fun saveOverlays() {
        prefs.edit().putBoolean("rulers", rulers).putBoolean("grid", grid).putFloat("grid_mm", gridMm).putFloat("rr_h", rulerHeightDp)
            .putBoolean("rr_dim", rulerDim).putInt("rr_c", rulerColor).putFloat("lens_z", lensZoom).putInt("as_speed", autoSpeed).apply()
    }

    fun saveMeasure() { prefs.edit().putString("scale", scale.label()).putInt("precision", precision).putBoolean("snap", snap).putBoolean("snap_grid", snapGrid).putString("scale_desc", scaleDescription).apply() }

    /** A jump from [from] to [to] (recorded unless it came from back / forward, or is a one-page step). */
    fun recordJump(from: Int, to: Int) {
        if (navigatingHistory) { navigatingHistory = false; return }
        if (kotlin.math.abs(from - to) <= 2) return          // page flips (one page or one spread) are not jumps
        history.visit(from, to); historyVersion++; showHistoryPill = true
    }

    fun back(): Boolean {
        val t = history.back(s.currentPage) ?: return false
        navigatingHistory = true; historyVersion++; s.jumpTo(t); return true
    }

    fun forward(): Boolean {
        val t = history.forward(s.currentPage) ?: return false
        navigatingHistory = true; historyVersion++; s.jumpTo(t); return true
    }

    /** Back button: closes our surfaces and overlays first. Returns true when it consumed the press. */
    fun onBack(): Boolean {
        when {
            surface != V5Surface.NONE -> { surface = V5Surface.NONE; return true }
            lens -> { lens = false; return true }
            readingRuler -> { readingRuler = false; return true }
            autoScroll -> { autoScroll = false; return true }
        }
        return false
    }

    companion object {
        const val PREFS = "view5"
        private val map = WeakHashMap<ReaderState, V5State>()
        fun of(s: ReaderState): V5State = synchronized(map) { map.getOrPut(s) { V5State(s) } }
        /** The reader was closed: drop its state (the value holds the reader, so the weak key alone never clears it). */
        fun forget(reader: ReaderState) { synchronized(map) { map.remove(reader) } }
    }
}
