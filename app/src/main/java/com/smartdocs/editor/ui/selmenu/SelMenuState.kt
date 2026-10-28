package com.smartdocs.editor.ui.selmenu

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.smartdocs.editor.core.pdf.PageLink
import com.smartdocs.editor.core.security.Block
import com.smartdocs.editor.core.security.DocAction
import com.smartdocs.editor.core.selmenu.AnnotFacts
import com.smartdocs.editor.core.selmenu.FieldFacts
import com.smartdocs.editor.core.selmenu.ImageFacts
import com.smartdocs.editor.core.selmenu.Need
import com.smartdocs.editor.core.selmenu.SelCtx
import com.smartdocs.editor.core.selmenu.SelEnv
import com.smartdocs.editor.core.selmenu.SelTab
import com.smartdocs.editor.core.selmenu.Why
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ReaderTab
import com.smartdocs.editor.ui.reader.StampClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

/** A long-press / right-click target that is not a text selection: a link, an empty spot or a form field of a page (PDF space). */
data class SelObject(val ctx: SelCtx, val page: Int, val x: Float, val y: Float, val link: PageLink? = null, val widget: com.smartdocs.editor.core.forms5.FWidget? = null)

/**
 * Per-reader state of the selection menus (feature/selection-menus). The page canvas publishes where the selection
 * (or the long-pressed object) is on screen; the floating layer in ReaderScreen reads it and places the bar.
 */
class SelMenuState private constructor(val s: ReaderState) {
    /** Union of the selection's line rects in root (window) px, published by the page that holds the selection. */
    var anchor by mutableStateOf<Rect?>(null)
    /** The selection [anchor] belongs to (a stale anchor of an older selection is ignored). */
    var anchorKey: Any? = null
    /** Long-press target without text (link / empty area); its point in root px is [objectAnchor]. */
    var target by mutableStateOf<SelObject?>(null)
    var objectAnchor by mutableStateOf<Offset?>(null)
    /** The "More" sheet is open. */
    var moreOpen by mutableStateOf(false)
    /** Right-click / keyboard menu position in the page area's own px (same space as NveState.contextMenuAt). */
    var dropdownAt by mutableStateOf<Offset?>(null)
    /** Whether the open file carries a digital signature (null until known). */
    var signed by mutableStateOf<Boolean?>(null)
    /** fix/selmenu-rest: the reader's actions, published by SelMenuLayer for the annotation bar (Reply opens the comments panel). */
    var actions: com.smartdocs.editor.ui.reader.ReaderActions? = null
    /** "Sign here": the point the signature chooser places the picked signature at. */
    var signAt by mutableStateOf<SelObject?>(null)
    /** Field menu dialog (properties / spell check) and the field it is for. */
    var fieldDialog by mutableStateOf<Pair<com.smartdocs.editor.core.selmenu.SelAction, com.smartdocs.editor.core.forms5.FWidget>?>(null)

    fun closeObject() { target = null; objectAnchor = null }

    fun closeAll() { closeObject(); moreOpen = false; dropdownAt = null }

    /** The registry environment for the current tab and context. */
    fun env(ctx: SelCtx, text: String, aiEnabled: Boolean, hasRects: Boolean = true, link: PageLink? = null,
            annot: AnnotFacts? = null, field: FieldFacts? = null, image: ImageFacts? = null): SelEnv {
        val g = s.guard
        val blocked = HashMap<Need, Why>()
        for ((need, act) in listOf(Need.COPY to DocAction.COPY, Need.ANNOTATE to DocAction.ANNOTATE, Need.MODIFY to DocAction.MODIFY, Need.PRINT to DocAction.PRINT, Need.FILL to DocAction.FILL_FORMS)) {
            g.blockOf(act)?.let { blocked[need] = if (it == Block.AUTHOR) Why.AUTHOR else Why.PROTECTED_VIEW }
        }
        return SelEnv(
            tab = tabOf(s.tab), ctx = ctx, text = text, blocked = blocked, signed = signed == true, aiEnabled = aiEnabled,
            clipboardHasContent = clipboardHasContent(s.context), stampOnClipboard = StampClipboard.annot != null,
            hasRects = hasRects, linkIsWeb = link?.uri?.isNotBlank() == true, annot = annot, field = field, image = image,
        )
    }

    companion object {
        private val map = WeakHashMap<ReaderState, SelMenuState>()
        fun of(s: ReaderState): SelMenuState = synchronized(map) { map.getOrPut(s) { SelMenuState(s) } }

        fun tabOf(t: ReaderTab): SelTab = when (t) {
            ReaderTab.VIEW -> SelTab.VIEW; ReaderTab.COMMENT -> SelTab.COMMENT; ReaderTab.EDIT -> SelTab.EDIT
            ReaderTab.FILL_SIGN -> SelTab.FILL_SIGN; ReaderTab.SIGN -> SelTab.SIGN; ReaderTab.TOOLS -> SelTab.TOOLS
            ReaderTab.PAGES -> SelTab.PAGES; ReaderTab.CUSTOM -> SelTab.CUSTOM
        }

        fun clipboardHasContent(ctx: Context): Boolean = runCatching {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).hasPrimaryClip()
        }.getOrDefault(false)
    }
}

/**
 * Small hooks called from the reader files (each call site is marked "selmenu"), kept here so the reader files only
 * gain one line each.
 */
object SelHooks {
    /**
     * PageCanvas long-press: a link under the finger opens the link menu (returns true); otherwise the caller goes on
     * selecting the word.
     */
    fun longPressLink(state: ReaderState, page: Int, x: Float, y: Float, links: List<PageLink>): Boolean {
        val l = links.firstOrNull { l -> val r = PageGeometry.normPdf(l.bounds); x >= r.left && x <= r.right && y <= r.top && y >= r.bottom } ?: return false
        val m = SelMenuState.of(state)
        state.selection = null
        m.target = SelObject(SelCtx.LINK, page, x, y, l)
        return true
    }

    /** PageCanvas long-press found no character near the finger: the empty-area menu at that point. */
    fun longPressEmpty(state: ReaderState, page: Int, x: Float, y: Float) {
        val m = SelMenuState.of(state)
        // the empty-area menu carries "Paste here" itself (nitro-fillsign2's separate bar would duplicate it)
        StampClipboard.offer = null
        m.target = SelObject(SelCtx.EMPTY, page, x, y)
    }

    /** fix/selmenu-rest: long-press on a form field of the in-page form layer opens the field menu at the finger. */
    fun longPressField(state: ReaderState, w: com.smartdocs.editor.core.forms5.FWidget) {
        val m = SelMenuState.of(state)
        state.selection = null
        state.edit.clear()
        m.target = SelObject(SelCtx.FIELD, w.page, (w.box.left + w.box.right) / 2f, maxOf(w.box.top, w.box.bottom), widget = w)
    }

    /**
     * PageCanvas long-press: a form field under the finger that has no touch box of its own (read-only or calculated
     * fields) opens the field menu too (returns true). Fields with a box handle their long-press in InPageFormLayer.
     */
    fun longPressFieldAt(state: ReaderState, page: Int, x: Float, y: Float): Boolean {
        val fs = com.smartdocs.editor.ui.forms5.FormFill.of(state)
        if (!fs.enabled) return false
        val w = fs.model.onPage(page).lastOrNull { w ->
            val b = w.box
            !fs.isHidden(w) && x >= minOf(b.left, b.right) && x <= maxOf(b.left, b.right) && y >= minOf(b.top, b.bottom) && y <= maxOf(b.top, b.bottom)
        } ?: return false
        longPressField(state, w); return true
    }

    /**
     * fix/selmenu-rest: a tap on a page (or a form field) while a link / empty-area / field menu is open closes it and
     * is used up (true). The menus draw no full-screen catcher, so the page keeps scrolling and zooming meanwhile.
     */
    fun onPageTap(state: ReaderState): Boolean {
        val m = SelMenuState.of(state)
        if (m.target == null || m.dropdownAt != null) return false
        m.closeObject(); return true
    }

    /** The old horizontal chip row in the bottom bar (ReaderOverlays.SelectionMenu); kept compiling, not shown. */
    const val LEGACY_BAR = false

    /** NveContextMenu: the right-click menu is drawn by the selection layer instead (one registry for every input). */
    fun ownsContextMenu(): Boolean = true

    /** Back: closes an open link / empty-area / right-click menu (true when it did). */
    fun onBack(state: ReaderState): Boolean {
        val m = SelMenuState.of(state)
        if (m.target == null && m.dropdownAt == null && !m.moreOpen) return false
        m.closeAll(); return true
    }

    /** Key handler: Shift+F10 / the Menu key open the context menu of the selection (or of the page). */
    fun onKey(state: ReaderState, e: android.view.KeyEvent): Boolean {
        if (e.action != android.view.KeyEvent.ACTION_DOWN) return false
        val menuKey = e.keyCode == android.view.KeyEvent.KEYCODE_MENU || (e.keyCode == android.view.KeyEvent.KEYCODE_F10 && e.isShiftPressed)
        if (!menuKey) return false
        val m = SelMenuState.of(state)
        if (state.selection == null && state.pageCount > 0) {
            // no selection: the page menu of the current page, "here" being its centre
            val p = state.currentPage
            val (w, h) = state.pageSizes[p] ?: (595f to 842f)
            m.target = SelObject(SelCtx.EMPTY, p, w / 2f, h / 2f)
        }
        m.dropdownAt = Offset(-1f, -1f)     // resolved by the layer: next to the selection, or the centre of the page area
        return true
    }
}

/** Learns once per file whether it is signed (content-changing items are then refused with a reason). */
@Composable
internal fun SignedProbe(s: ReaderState) {
    val m = SelMenuState.of(s)
    val f = s.file
    LaunchedEffect(f, s.password) {
        m.signed = if (f == null) null else withContext(Dispatchers.IO) { runCatching { com.smartdocs.editor.core.pdf.SignedSave.isSigned(f, s.password) }.getOrNull() }
    }
}
