package com.smartdocs.editor.core.rpf

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf

/**
 * reader-prefs: page display and full-screen preferences of the reader (Foxit §37 / old plan PREF-5), stored in
 * prefs "rpf_prefs". Getters read the version state, so Compose recomposes after every write.
 */
object RpfPrefs {
    private var sp: SharedPreferences? = null
    val version = mutableIntStateOf(0)

    fun init(context: Context) {
        if (sp == null) { sp = context.applicationContext.getSharedPreferences("rpf_prefs", Context.MODE_PRIVATE); version.intValue++ }
    }

    private fun touch() { version.intValue }
    private fun b(k: String, d: Boolean): Boolean { touch(); return sp?.getBoolean(k, d) ?: d }
    private fun i(k: String, d: Int): Int { touch(); return sp?.getInt(k, d) ?: d }
    private fun s(k: String, d: String): String { touch(); return sp?.getString(k, d) ?: d }
    private fun put(block: SharedPreferences.Editor.() -> Unit) { sp?.edit()?.apply(block)?.apply(); version.intValue++ }

    fun setBool(k: String, v: Boolean) = put { putBoolean(k, v) }
    fun setInt(k: String, v: Int) = put { putInt(k, v) }
    fun setString(k: String, v: String) = put { putString(k, v) }

    object K {
        const val FACING_COUNT = "facing_count"; const val PAGE_GAP = "page_gap"; const val PAGE_SIZE = "show_page_size"
        const val KEEP_DEST_ZOOM = "keep_dest_zoom"; const val FS_REQUEST = "fs_request"
        const val FS_TABS = "fs_tabs"; const val FS_TOOLBAR = "fs_toolbar"; const val FS_PAGE_NAV = "fs_page_nav"
        const val PDFA_VIEW = "pdfa_view"; const val FIELD_TTS = "field_tts"
    }

    /** Pages side by side in the facing / cover layouts (2 or 3). */
    val facingCount: Int get() = i(K.FACING_COUNT, 2).coerceIn(2, 3)
    /** Gap between pages in dp (continuous list and, with the separator, between facing pages). */
    val pageGapDp: Int get() = i(K.PAGE_GAP, 8).coerceIn(0, 48)
    /** Always show the size of the current page over the page area. */
    val showPageSize: Boolean get() = b(K.PAGE_SIZE, false)
    /** Keep the current zoom when a link goes to a destination (off: use the destination's zoom / fit). */
    val keepZoomOnDestination: Boolean get() = b(K.KEEP_DEST_ZOOM, true)
    /** What to do when a document asks for full screen: "ask", "allow" or "never". */
    val fullScreenRequest: String get() = s(K.FS_REQUEST, "ask")
    /** Items still shown in full screen. */
    val fsTabs: Boolean get() = b(K.FS_TABS, false)
    val fsToolbar: Boolean get() = b(K.FS_TOOLBAR, false)
    val fsPageNav: Boolean get() = b(K.FS_PAGE_NAV, true)
    /** Open files that claim PDF/A read-only, with a banner to enable editing. */
    val pdfaViewMode: Boolean get() = b(K.PDFA_VIEW, true)
    /** Form fields: speak each field when it gets the focus (Tab-through reading). */
    val fieldTts: Boolean get() = b(K.FIELD_TTS, false)
}
