package com.smartdocs.editor.ui.nve

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.smartdocs.editor.ui.reader.ReaderState
import java.util.WeakHashMap

/** Reader panes added by the nitro-view-edit work (shown as a sheet on phones, beside the pages on tablets). */
enum class NvePane { ATTACHMENTS, SIGNATURES, TAGS, FIELDS, DESTINATIONS, ARTICLES }

/** What a rectangle drag on the page does. */
enum class NveDrag { ZOOM_IN, ZOOM_OUT, COLUMN_SELECT, SNAPSHOT, PLACE }

/** One-at-a-time sheets / dialogs of the nitro-view-edit work. */
enum class NveSurface { NONE, ZOOM, WORD_COUNT, TOOLBAR_CUSTOMIZE, TEXT_VIEWER, TRANSITIONS, GRID, SPELL_DOC, SNAPSHOT, PASTE, SAVE_AS, DOC_INFO, QUAD_SPLIT, EDIT5, KEYS }

/** A visible page in viewport pixels ([rect] is the whole page box, possibly partly off screen). */
data class VisiblePage(val page: Int, val rect: Rect)

/** Ruler / grid units. */
enum class NveUnit(val ptPerUnit: Float, val label: String) { MM(72f / 25.4f, "mm"), CM(72f / 2.54f, "cm"), IN(72f, "in"), PT(1f, "pt"), PICA(12f, "pc") }

/**
 * Per-reader state of the nitro-view-edit work, kept outside ReaderState (weak map keyed by the reader state, as
 * view5 does) so the shared reader files only need one-line hooks. View options are remembered in prefs "nve_view".
 */
class NveState private constructor(val s: ReaderState) {
    val prefs: SharedPreferences = s.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var surface by mutableStateOf(NveSurface.NONE)
    var pane by mutableStateOf<NvePane?>(null)

    // ---- page area geometry published by PageView (hook) ----
    var viewport by mutableStateOf(Size.Zero)
    /** Displayed width (px) of [page] at zoom 1 (fit width / fit page base). */
    internal var baseWidth: (Int) -> Float = { viewport.width }
    /** PageView's own zoom: factor, centroid, pan (viewport px). */
    internal var applyZoom: ((Float, Offset, Offset) -> Unit)? = null
    /** Visible pages in viewport px. */
    internal var visiblePages: () -> List<VisiblePage> = { emptyList() }

    // ---- view modes ----
    var continuousFacing by mutableStateOf(prefs.getBoolean("cont_facing", false))
    var continuousCover by mutableStateOf(prefs.getBoolean("cont_cover", false))
    var reverseView by mutableStateOf(prefs.getBoolean("reverse", false))
    /** Rectangle-drag mode of the page overlay (marquee zoom, column selection, area snapshot, placing a paste). */
    var dragMode by mutableStateOf<NveDrag?>(null)
    /** Clipboard content waiting to be placed on a page (PLACE mode). */
    var pasteImage by mutableStateOf<android.graphics.Bitmap?>(null)
    var pasteText by mutableStateOf<String?>(null)
    /** Text whose words the word-count dialog counts (null = whole document). */
    var wordCountText by mutableStateOf<String?>(null)
    /** "Edit text" from the selection: page + PDF rect; the reader opens Edit mode there (hook). */
    var requestEdit by mutableStateOf<Pair<Int, android.graphics.RectF?>?>(null)
    /** Selected user-defined reader tab (customise ribbon), null = a built-in tab. */
    var userTab by mutableStateOf<String?>(null)
    /** Address of a tapped link waiting for Open / Copy. */
    var linkPreview by mutableStateOf<String?>(null)
    /** Mouse pointer over the page area (viewport px), for the coordinates read-out. */
    var cursor by mutableStateOf<Offset?>(null)
    /** Right-click position (viewport px) of the open context menu. */
    var contextMenuAt by mutableStateOf<Offset?>(null)
    /** 0 = free, 1 = horizontal locked, 2 = vertical locked. */
    var scrollLock by mutableIntStateOf(0)

    // ---- rulers / grid / guides ----
    var unit by mutableStateOf(NveUnit.entries.getOrElse(prefs.getInt("unit", 0)) { NveUnit.MM })
    var gridColor by mutableIntStateOf(prefs.getInt("grid_c", 0x2196F3))
    var gridSubdiv by mutableIntStateOf(prefs.getInt("grid_sub", 5))
    var gridOffX by mutableFloatStateOf(prefs.getFloat("grid_ox", 0f))
    var gridOffY by mutableFloatStateOf(prefs.getFloat("grid_oy", 0f))
    var gridPageAnchored by mutableStateOf(prefs.getBoolean("grid_page", true))
    var snapToGrid by mutableStateOf(prefs.getBoolean("snap_grid", false))
    var rulerVertical by mutableStateOf(prefs.getBoolean("ruler_v", true))
    var rulerHorizontal by mutableStateOf(prefs.getBoolean("ruler_h", true))
    var rulerOpacity by mutableFloatStateOf(prefs.getFloat("ruler_o", 0.9f))
    var guidesOn by mutableStateOf(prefs.getBoolean("guides", true))
    /** Guides in page points: x guides (vertical lines) and y guides (horizontal lines, from the top). */
    val guidesX = mutableStateListOf<Float>()
    val guidesY = mutableStateListOf<Float>()
    var lensW by mutableIntStateOf(prefs.getInt("lens_w", 420))
    var lensH by mutableIntStateOf(prefs.getInt("lens_h", 260))

    // ---- auto scroll extras ----
    var autoReverse by mutableStateOf(false)

    // ---- banners ----
    val dismissedBanners = mutableStateListOf<String>()


    init {
        prefs.getString("guides_x", "")?.split(',')?.mapNotNull { it.toFloatOrNull() }?.let { guidesX += it }
        prefs.getString("guides_y", "")?.split(',')?.mapNotNull { it.toFloatOrNull() }?.let { guidesY += it }
    }

    fun open(x: NveSurface) { surface = x }
    fun close() { surface = NveSurface.NONE }

    fun saveView() {
        prefs.edit().putBoolean("cont_facing", continuousFacing).putBoolean("cont_cover", continuousCover).putBoolean("reverse", reverseView)
            .putInt("unit", unit.ordinal).putInt("grid_c", gridColor).putInt("grid_sub", gridSubdiv).putFloat("grid_ox", gridOffX).putFloat("grid_oy", gridOffY)
            .putBoolean("grid_page", gridPageAnchored).putBoolean("snap_grid", snapToGrid).putBoolean("ruler_v", rulerVertical).putBoolean("ruler_h", rulerHorizontal)
            .putFloat("ruler_o", rulerOpacity).putBoolean("guides", guidesOn).putInt("lens_w", lensW).putInt("lens_h", lensH)
            .putString("guides_x", guidesX.joinToString(",")).putString("guides_y", guidesY.joinToString(",")).apply()
    }

    /** Back button: closes our surfaces / modes first. True when consumed. */
    fun onBack(): Boolean {
        when {
            surface != NveSurface.NONE -> { surface = NveSurface.NONE; return true }
            pane != null -> { pane = null; return true }
            dragMode != null -> { dragMode = null; pasteImage = null; pasteText = null; return true }
        }
        return false
    }

    companion object {
        const val PREFS = "nve_view"
        private val map = WeakHashMap<ReaderState, NveState>()
        fun of(s: ReaderState): NveState = synchronized(map) { map.getOrPut(s) { NveState(s) } }
        fun forget(s: ReaderState) { synchronized(map) { map.remove(s) } }
        /** Prefs value read without a reader (PageView before the state exists, settings). */
        fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
