package com.smartdocs.editor.ui.pageflip

import android.view.KeyEvent
import com.smartdocs.editor.core.pageflip.PfAxis
import com.smartdocs.editor.ui.reader.ReaderState

/**
 * Keyboard of the page-turn hosts (hook in ReaderScreen's onPreviewKeyEvent, before V5Keys): Space / Shift+Space,
 * left / right arrows in the *document's* reading direction (an Arabic PDF in an English UI still turns right-to-left),
 * up / down in vertical paging. PgUp / PgDn, Home / End, volume keys keep their existing handlers; every turn goes
 * through `jumpTo`, which the host animates.
 */
object PageFlipKeys {
    fun onKey(s: ReaderState, e: KeyEvent, uiRtl: Boolean): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        val pf = PageFlipController.of(s)
        if (!pf.hosts() || s.session == null || s.tool != null) return false
        if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return false
        val rtl = pf.rtl(uiRtl)
        return when (e.keyCode) {
            KeyEvent.KEYCODE_SPACE -> { s.vx.flip(if (e.isShiftPressed) -1 else 1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { s.vx.flip(if (rtl) -1 else 1); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { s.vx.flip(if (rtl) 1 else -1); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (pf.prefs.axis == PfAxis.VERTICAL) { s.vx.flip(1); true } else false
            KeyEvent.KEYCODE_DPAD_UP -> if (pf.prefs.axis == PfAxis.VERTICAL) { s.vx.flip(-1); true } else false
            else -> false
        }
    }
}
