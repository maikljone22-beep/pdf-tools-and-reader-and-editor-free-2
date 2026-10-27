package com.smartdocs.editor.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf

/**
 * nav-prefs: the "More preferences" options (Nitro §23 / Foxit §37 / old-plan PREF-* and SHL-*), kept in their own
 * SharedPreferences file "npf_prefs" so they can be read synchronously from non-UI code (DocumentRepository, HTTP
 * clients, workers) and so [AppSettings] / [SettingsStore] stay untouched. Every getter reads [version], a snapshot
 * state, so a composable that reads a preference recomposes when it changes. Before [init] (JVM tests) every getter
 * returns its default.
 */
object NpfPrefs {
    private var sp: SharedPreferences? = null
    /** Bumped on every write (Compose observes it through the getters). */
    val version = mutableIntStateOf(0)

    fun init(context: Context) {
        if (sp == null) sp = context.applicationContext.getSharedPreferences("npf_prefs", Context.MODE_PRIVATE)
    }

    private fun touch() { version.intValue }
    private fun b(k: String, d: Boolean): Boolean { touch(); return sp?.getBoolean(k, d) ?: d }
    private fun i(k: String, d: Int): Int { touch(); return sp?.getInt(k, d) ?: d }
    private fun f(k: String, d: Float): Float { touch(); return sp?.getFloat(k, d) ?: d }
    private fun s(k: String, d: String): String { touch(); return sp?.getString(k, d) ?: d }
    private fun l(k: String, d: Long): Long { touch(); return sp?.getLong(k, d) ?: d }
    private fun put(block: SharedPreferences.Editor.() -> Unit) { sp?.edit()?.apply(block)?.apply(); version.intValue++ }

    fun setBool(k: String, v: Boolean) = put { putBoolean(k, v) }
    fun setInt(k: String, v: Int) = put { putInt(k, v) }
    fun setFloat(k: String, v: Float) = put { putFloat(k, v) }
    fun setString(k: String, v: String) = put { putString(k, v) }
    fun setLong(k: String, v: Long) = put { putLong(k, v) }

    // ---- keys (also used by the Settings rows) ----
    object K {
        const val SAVE_TREE = "save_tree"; const val SAVE_LAST = "save_last"
        const val RECENT_LIMIT = "recent_limit"; const val RECOVERY_MIN = "recovery_min"; const val AUTOSAVE_MIN = "autosave_min"
        const val LAYOUT = "layout"; const val ZOOM = "zoom"; const val PANEL = "panel_on_open"; const val PANEL_W = "panel_width"; const val TAB_LIMIT = "tab_limit"; const val PRESENT_BG = "present_bg"; const val DOC_TITLE = "doc_title"
        const val HF_IGNORE_ROT = "hf_ignore_rotation"
        const val UNITS = "units"; const val GRID_COLOR = "grid_color"
        const val GRID_OFF_X = "grid_off_x"; const val GRID_OFF_Y = "grid_off_y"; const val GRID_SUB = "grid_sub"
        const val GUIDES = "guides"; const val GUIDES_H = "guides_h"; const val GUIDES_V = "guides_v"
        const val NOTE_FONT = "note_font"
        const val RETAIN_TOOLS = "retain_tools"; const val COPY_SEL = "copy_sel_to_markup"
        const val XFA_AUTO = "xfa_auto_open"; const val XFA_BANNER = "xfa_banner"
        const val DIGITS = "digits"
        const val DOC_VIEW = "doc_view_first_open"
        const val BM_DELETE = "bm_remove_on_delete"; const val BM_IMPORT = "bm_import_on_replace"
        const val OCR_OUTPUT = "ocr_output"
        const val N_SYNC = "n_sync"; const val N_REVIEW = "n_review"; const val N_TASKS = "n_tasks"
        const val S_CASE = "s_case"; const val S_WHOLE = "s_whole"; const val S_HIT = "s_hit"; const val S_HIT_CUR = "s_hit_cur"
        const val S_SCOPES = "s_scopes"; const val S_ATTACH = "s_attach_content"; const val S_MAX = "s_max"
        const val A11Y_ON = "a11y_on"; const val A11Y_BG = "a11y_bg"; const val A11Y_FG = "a11y_fg"; const val READ_ORDER = "read_order"
        const val FONT_MAP = "font_map"
        const val OFFLINE = "offline"; const val ACCENT = "accent"
    }

    // ---- files & saving ----
    /** SAF tree chosen as the default save folder ("" = the app's Documents folder only). */
    val saveTree: String get() = s(K.SAVE_TREE, "")
    /** Newest modification time already copied to [saveTree] (catch-up after a restart). */
    var saveLast: Long
        get() = l(K.SAVE_LAST, 0L)
        set(v) { sp?.edit()?.putLong(K.SAVE_LAST, v)?.apply() }
    /** Recent list size on Home; 0 = no limit (the database keeps at most 50). */
    val recentLimit: Int get() = i(K.RECENT_LIMIT, 0)
    /** Minutes between recovery copies of a document with unsaved edits; 0 = off. */
    val recoveryMinutes: Int get() = i(K.RECOVERY_MIN, 5)
    /** Minutes between automatic saves while editing (with Settings → autosave on); 0 = only when leaving. */
    val autoSaveMinutes: Int get() = i(K.AUTOSAVE_MIN, 0)

    // ---- page display ----
    /** "auto" (follows the continuous switch), "single", "continuous", "facing", "cover". */
    val defaultLayout: String get() = s(K.LAYOUT, "auto")
    /** "width", "page", "z150", "z200" (zoom relative to fit width). */
    val defaultZoom: String get() = s(K.ZOOM, "width")
    /** First open of a document: its own layout / open page / zoom / panel win over the defaults above. */
    val docViewFirstOpen: Boolean get() = b(K.DOC_VIEW, true)
    /** Panel shown when a document opens: "none", "outline", "thumbnails", "bookmarks". */
    val panelOnOpen: String get() = s(K.PANEL, "none")
    /** Open documents kept as reader tabs. */
    val tabLimit: Int get() = i(K.TAB_LIMIT, 5).coerceIn(2, 10)
    /** Presentation (full screen) background, RGB. */
    val presentBackground: Int get() = i(K.PRESENT_BG, 0x000000)
    /** Width of the side panel on tablets (dp). */
    val panelWidthDp: Int get() = i(K.PANEL_W, 340).coerceIn(260, 520)
    /** Reader title: the document /Title instead of the file name (when the PDF has one). */
    val showDocTitle: Boolean get() = b(K.DOC_TITLE, false)
    /** Header / footer / watermark: place on the unrotated page (true) instead of the page as displayed. */
    val hfIgnoreRotation: Boolean get() = b(K.HF_IGNORE_ROT, false)

    // ---- units, grid, guides ----
    /** "cm", "mm", "in", "pt". */
    val units: String get() = s(K.UNITS, "cm")
    /** RGB of the layout grid. */
    val gridColor: Int get() = i(K.GRID_COLOR, 0x2196F3)
    val gridOffsetXmm: Float get() = f(K.GRID_OFF_X, 0f)
    val gridOffsetYmm: Float get() = f(K.GRID_OFF_Y, 0f)
    /** Minor lines per major line. */
    val gridSubdivisions: Int get() = i(K.GRID_SUB, 5)
    val guides: Boolean get() = b(K.GUIDES, false)
    /** Guide positions as fractions (0..1) of the page area. */
    val guidesH: List<Float> get() = floats(s(K.GUIDES_H, ""))
    val guidesV: List<Float> get() = floats(s(K.GUIDES_V, ""))
    fun setGuides(h: List<Float>, v: List<Float>) = put { putString(K.GUIDES_H, h.joinToString(",")); putString(K.GUIDES_V, v.joinToString(",")) }

    // ---- commenting ----
    /** "sans", "serif", "mono". */
    val noteFont: String get() = s(K.NOTE_FONT, "sans")
    // pop-up size and opacity: prefs "comment_prefs" (note_font / note_opacity) of the comment tools (CommentExtras)
    /** Keep the markup tool selected after a comment is made (the reader's "continue" chip). */
    val retainTools: Boolean get() = b(K.RETAIN_TOOLS, true)
    /** Highlight / underline / strikeout / squiggly from a selection get the selected text as their comment. */
    val copySelection: Boolean get() = b(K.COPY_SEL, true)

    // ---- forms ----
    /** Dynamic XFA: open the generated fillable form automatically. */
    val xfaAutoOpen: Boolean get() = b(K.XFA_AUTO, false)
    val xfaBanner: Boolean get() = b(K.XFA_BANNER, true)

    // ---- editing / languages ----
    /** Digits typed in text comments and edit text: "keep", "arabic" (٠١٢), "latin" (012). */
    val digits: String get() = s(K.DIGITS, "keep")

    // ---- pages / bookmarks ----
    val removeBookmarksOnDelete: Boolean get() = b(K.BM_DELETE, true)
    val importBookmarksOnReplace: Boolean get() = b(K.BM_IMPORT, false)

    // ---- tools ----
    /** OCR tool output: "searchable" (invisible text layer) or "text". Pre-clean / auto-detect live in prefs "t5_ocr". */
    val ocrOutput: String get() = s(K.OCR_OUTPUT, "searchable")
    /** Font substitutions for conversion: "Missing Font=Replacement" lines. */
    val fontMap: String get() = s(K.FONT_MAP, "")

    // ---- notifications ----
    val notifySync: Boolean get() = b(K.N_SYNC, true)
    val notifyReview: Boolean get() = b(K.N_REVIEW, true)
    val notifyTasks: Boolean get() = b(K.N_TASKS, true)

    // ---- search ----
    val searchMatchCase: Boolean get() = b(K.S_CASE, false)
    val searchWholeWords: Boolean get() = b(K.S_WHOLE, false)
    /** ARGB of search hits and of the current hit. */
    val hitColor: Int get() = i(K.S_HIT, 0x66FFEB3B)
    val hitCurrentColor: Int get() = i(K.S_HIT_CUR, 0x99FF9800.toInt())
    /** Reader search also lists hits in comments, bookmarks, fields and attachments. */
    val searchScopes: Boolean get() = b(K.S_SCOPES, true)
    /** Attachment search reads the content of text and PDF attachments. */
    val searchAttachmentContent: Boolean get() = b(K.S_ATTACH, true)
    /** Maximum reader search hits (0 = no limit). */
    val searchMax: Int get() = i(K.S_MAX, 0)

    // ---- accessibility ----
    /** Replace document colours: page [a11yBackground] and text / line art [a11yText]. */
    val a11yOverride: Boolean get() = b(K.A11Y_ON, false)
    val a11yBackground: Int get() = i(K.A11Y_BG, 0x000000)
    val a11yText: Int get() = i(K.A11Y_FG, 0xFFFF00)
    /** Read aloud order: "infer" (reflow blocks / columns) or "raw" (content stream order). */
    val readingOrder: String get() = s(K.READ_ORDER, "infer")

    // ---- general ----
    /** Offline mode: every feature that needs the Internet is off (see core/data/NpfNet). */
    val offline: Boolean get() = b(K.OFFLINE, false)
    /** Accent colour RGB; 0 = the brand colour. */
    val accent: Int get() = i(K.ACCENT, 0)

    internal fun floats(v: String): List<Float> = v.split(',').mapNotNull { it.trim().toFloatOrNull() }.filter { it in 0f..1f }
}
