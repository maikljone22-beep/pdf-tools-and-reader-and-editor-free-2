package com.smartdocs.editor.ui.pageflip

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.core.pageflip.PageFlipPrefs
import com.smartdocs.editor.core.pageflip.PfLayout
import com.smartdocs.editor.core.pageflip.PfSpread
import com.smartdocs.editor.core.pageflip.ScriptDirection
import com.smartdocs.editor.core.pageflip.PfDirection
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ViewMode
import java.util.WeakHashMap

/**
 * Per-reader state of the page navigation library (kept outside ReaderState in a weak map, like V5State).
 * Preferences are stored in SharedPreferences "pageflip": one global set ("global") and optional per-document
 * overrides ("doc_<hash of uri>").
 */
class PageFlipController private constructor(val s: ReaderState) {
    private val sp = s.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val docKey = "doc_" + Integer.toHexString(s.uriString.hashCode())

    var perDocument by mutableStateOf(sp.contains(docKey)); private set
    var prefs by mutableStateOf(PageFlipPrefs.decode(sp.getString(docKey, null) ?: sp.getString(K_GLOBAL, null))); private set
    /** Settings sheet visible (opened from the view settings / commands). */
    var sheetOpen by mutableStateOf(false)
    /** Detected document direction (null = unknown yet / no text). */
    var docRtl by mutableStateOf<Boolean?>(null); private set
    /** A transition is on screen (the interactive page is covered by snapshots). */
    var animating by mutableStateOf(false)
    private var appliedOpen = false

    fun update(p: PageFlipPrefs) {
        prefs = p
        sp.edit().putString(if (perDocument) docKey else K_GLOBAL, p.encode()).apply()
        applySpread()
    }

    /** Switch between remembering the settings for this document only and the global settings. */
    fun updatePerDocument(on: Boolean) {
        perDocument = on
        if (on) sp.edit().putString(docKey, prefs.encode()).apply()
        else { sp.edit().remove(docKey).apply(); prefs = PageFlipPrefs.decode(sp.getString(K_GLOBAL, null)); applySpread() }
    }

    /** Resolved reading direction. */
    fun rtl(uiRtl: Boolean): Boolean = when (prefs.direction) {
        PfDirection.LTR -> false
        PfDirection.RTL -> true
        PfDirection.AUTO -> docRtl ?: uiRtl
    }

    /** Spread of the paged host = the reader's view mode (so the view sheet's mode cards keep working). */
    fun spread(): PfSpread = when (s.viewMode) {
        ViewMode.FACING -> PfSpread.TWO
        ViewMode.COVER -> PfSpread.TWO_COVER
        else -> PfSpread.SINGLE
    }

    private fun applySpread() {
        if (prefs.layout == PfLayout.CLASSIC) return
        val m = when (prefs.spread) { PfSpread.SINGLE -> ViewMode.SINGLE; PfSpread.TWO -> ViewMode.FACING; PfSpread.TWO_COVER -> ViewMode.COVER }
        // horizontal scrolling is page by page (keys / auto flip step one page)
        if (prefs.layout == PfLayout.SCROLL_H) { if (s.viewMode != ViewMode.SINGLE) { s.viewMode = ViewMode.SINGLE; s.zoom = 1f } }
        else if (s.viewMode != m) { s.viewMode = m; s.zoom = 1f }
    }

    /** Once per opened document: restore the remembered reading mode. */
    fun onOpened() {
        if (appliedOpen) return
        appliedOpen = true
        applySpread()
    }

    /** Remember a spread chosen elsewhere (view settings sheet) while the paged host is used. */
    fun rememberSpread() {
        if (prefs.layout != PfLayout.PAGED || s.viewMode == ViewMode.CONTINUOUS) return
        val sp2 = spread()
        if (sp2 != prefs.spread) update(prefs.copy(spread = sp2))
    }

    /** True when the page-turn / horizontal hosts replace the classic PageView. */
    fun hosts(): Boolean {
        val p = prefs
        if (p.layout == PfLayout.CLASSIC || s.reflow || s.rotation != 0 || s.vx.crop.enabled) return false
        // picking "continuous" in the view settings returns to the vertical list
        return s.viewMode != ViewMode.CONTINUOUS
    }

    /** Detect the document's direction from the text of a few pages (Arabic documents -> RTL). */
    suspend fun detectDirection() {
        if (docRtl != null || s.pageCount <= 0) return
        val pages = listOf(s.currentPage, 0, 1, 2, s.pageCount / 2).distinct().filter { it in 0 until s.pageCount }
        val sb = StringBuilder()
        for (p in pages) {
            sb.append(runCatching { s.pageText(p) }.getOrDefault(""))
            ScriptDirection.detect(sb.toString(), 60)?.let { docRtl = it; return }
        }
        docRtl = ScriptDirection.detect(sb.toString())
    }

    fun haptic(view: View, strong: Boolean = false) {
        if (!prefs.haptics) return
        runCatching { view.performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK) }
    }

    companion object {
        const val PREFS = "pageflip"
        private const val K_GLOBAL = "global"
        private val map = WeakHashMap<ReaderState, PageFlipController>()
        fun of(s: ReaderState): PageFlipController = synchronized(map) { map.getOrPut(s) { PageFlipController(s) } }
        /** The reader was closed: drop its state (the value holds the reader, so the weak key alone never clears it). */
        fun forget(reader: ReaderState) { synchronized(map) { map.remove(reader) } }

        /** System "animator duration scale" (developer options / accessibility "remove animations" sets 0). */
        fun animatorScale(ctx: Context): Float =
            runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

        fun lowRam(ctx: Context): Boolean {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            return am.isLowRamDevice || am.memoryClass <= 128
        }
    }
}
