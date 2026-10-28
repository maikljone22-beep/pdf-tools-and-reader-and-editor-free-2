package com.smartdocs.editor.ui.selmenu

import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.selmenu.AnnotFacts
import com.smartdocs.editor.core.selmenu.SelAction
import com.smartdocs.editor.core.selmenu.SelCtx
import com.smartdocs.editor.core.selmenu.SelItem
import com.smartdocs.editor.core.selmenu.SelMenus
import com.smartdocs.editor.core.selmenu.SelObjects
import com.smartdocs.editor.ui.reader.AnnotTarget
import com.smartdocs.editor.ui.reader.ReaderPanel
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.hasEditableText
import com.smartdocs.editor.ui.reader.hasStroke

/**
 * fix/selmenu-rest: the selected annotation's menu, built by the registry (ANNOT context). The floating bar
 * (ui/reader/AnnotEditUi.AnnotActionBar) draws [inline] as its row and [more] as its overflow menu, and runs
 * every item through the handlers it always used.
 */
object SelAnnotMenu {
    private val MARKUP = setOf(AnnotKind.HIGHLIGHT, AnnotKind.UNDERLINE, AnnotKind.STRIKEOUT, AnnotKind.SQUIGGLY)

    /** Registry facts of the selected annotation ([annot] null while a stored one is still loading). */
    fun facts(s: ReaderState, annot: Annot?, sigPath: String?): AnnotFacts {
        val stored = (s.edit.target as? AnnotTarget.Stored)?.e
        val video = annot?.kind == AnnotKind.VIDEO
        val nativeAudio = stored?.let { it.subtype == "Sound" || (it.subtype == "Screen" && it.video == null) } == true
        val cx = s.annot2.cx
        return AnnotFacts(
            color = annot?.kind != AnnotKind.STAMP_IMAGE && !video, opacity = !video, stroke = hasStroke(annot), editText = hasEditableText(annot),
            textFormat = annot?.kind == AnnotKind.FREETEXT, playable = nativeAudio || annot?.kind == AnnotKind.SOUND,
            stamp = annot?.kind == AnnotKind.STAMP_IMAGE, sigDefaultSize = sigPath != null, attachment = annot?.kind == AnnotKind.FILE_ATTACH,
            video = video, ink = annot?.kind == AnnotKind.INK,
            imageProps = stored != null && (annot?.kind == AnnotKind.STAMP_IMAGE || stored.subtype == "Stamp"),
            markup = annot != null && annot.kind in MARKUP, locked = cx.selectionLocked(), stored = stored != null,
            canPaste = cx.canPaste, multiPage = s.pageCount > 1,
        )
    }

    /** Row items (bar order, disabled ones kept and greyed) and overflow items of the selected annotation. */
    data class Model(val inline: List<SelItem>, val more: List<SelItem>)

    fun model(s: ReaderState, annot: Annot?, sigPath: String?, aiEnabled: Boolean): Model {
        val env = SelMenuState.of(s).env(SelCtx.ANNOT, annot?.text.orEmpty(), aiEnabled, annot = facts(s, annot, sigPath))
        val all = SelMenus.build(env, Int.MAX_VALUE).all
        val order = SelObjects.barOrder(SelCtx.ANNOT)
        val inline = all.filter { it.action !in SelObjects.ANNOT_MORE }.sortedBy { order.indexOf(it.action).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        return Model(inline, all.filter { it.action in SelObjects.ANNOT_MORE })
    }

    /**
     * Runs the overflow items and the simple row items (the ones with a sub-panel or a dialog are handled by the bar).
     * Returns false for an item it does not run.
     */
    fun run(s: ReaderState, item: SelItem): Boolean {
        val cx = s.annot2.cx
        val ed = s.edit
        when (item.action) {
            SelAction.A_COPY -> ed.copy()
            SelAction.A_PLAY, SelAction.A_OPEN_FILE -> s.annot2.openSelected()
            SelAction.A_VIDEO_PLAY -> s.video.playSelected()
            SelAction.A_VIDEO_OPTIONS -> s.video.optionsOpen = true
            SelAction.A_VIDEO_SHARE -> s.video.shareSelected()
            SelAction.A_VIDEO_SAVE -> s.video.saveSelected()
            SelAction.A_RECOGNIZE -> s.annot2.startHandwriting()
            SelAction.A_DELETE -> ed.delete()
            // Foxit 24.9: the markup range word by word at either end
            SelAction.A_RANGE_START_LESS -> cx.adjustMarkup(true, -1)
            SelAction.A_RANGE_START_MORE -> cx.adjustMarkup(true, 1)
            SelAction.A_RANGE_END_LESS -> cx.adjustMarkup(false, -1)
            SelAction.A_RANGE_END_MORE -> cx.adjustMarkup(false, 1)
            // the thread of a saved comment in the comments panel (as Ctrl+Enter does)
            SelAction.A_REPLY -> {
                val t = ed.target as? AnnotTarget.Stored ?: return true
                com.smartdocs.editor.ui.nve.NveState.of(s).replyTo = t.e.page to t.e.index
                SelMenuState.of(s).actions?.panels?.invoke(ReaderPanel.ANNOTATIONS)
            }
            SelAction.A_PROPS -> cx.openProps()
            SelAction.A_LOCK -> cx.setLocked(true)
            SelAction.A_UNLOCK -> cx.setLocked(false)
            SelAction.A_CUT -> cx.copy(cut = true)
            SelAction.A_CLIP_COPY -> cx.copy()
            SelAction.A_PASTE_IN_PLACE -> cx.paste(true)
            SelAction.A_ROTATE_LEFT -> cx.rotate(90f)
            SelAction.A_ROTATE_RIGHT -> cx.rotate(-90f)
            SelAction.A_ROTATE_CUSTOM -> cx.rotateReq = cx.target()
            SelAction.A_PLACE_PAGES -> cx.target()?.let { cx.pagesReq = com.smartdocs.editor.ui.reader.annot.PagesReq(it) }
            SelAction.A_FLATTEN -> cx.flatten()
            SelAction.A_EXPORT_XFDF -> s.annot2.launchExportSelected(true, false, false)
            SelAction.A_EXPORT_FDF -> s.annot2.launchExportSelected(false, false, false)
            SelAction.A_EMAIL_FDF -> s.annot2.launchExportSelected(false, true, false)
            else -> return false
        }
        return true
    }
}
