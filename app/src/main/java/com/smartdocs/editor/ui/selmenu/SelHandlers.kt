package com.smartdocs.editor.ui.selmenu

import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.widget.Toast
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai5.TextAction
import com.smartdocs.editor.core.data.ReadingBookmark
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.PageLink
import com.smartdocs.editor.core.security.DocAction
import com.smartdocs.editor.core.selmenu.Need
import com.smartdocs.editor.core.selmenu.SelAction
import com.smartdocs.editor.core.selmenu.SelEntities
import com.smartdocs.editor.core.selmenu.SelItem
import com.smartdocs.editor.core.selmenu.SelText
import com.smartdocs.editor.ui.nve.NveClipboard
import com.smartdocs.editor.ui.nve.NveDrag
import com.smartdocs.editor.ui.nve.NveSelectionOps
import com.smartdocs.editor.ui.nve.NveState
import com.smartdocs.editor.ui.nve.NveSurface
import com.smartdocs.editor.ui.quick.QuickController
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderActions
import com.smartdocs.editor.ui.reader.ReaderIntents
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ReaderTab
import com.smartdocs.editor.ui.reader.ReaderTool
import com.smartdocs.editor.ui.reader.TextPrompt
import com.smartdocs.editor.ui.reader.TextSelection
import com.smartdocs.editor.ui.reader.TextSelectionLogic
import com.smartdocs.editor.ui.reader.pasteStampAt
import kotlinx.coroutines.launch

/** Actions that open a dialog of the layer instead of running at once. */
enum class SelDialog { PDF_BOOKMARK, CREATE_LINK, WORD_COUNT, EXPORT }

/**
 * Runs a registry action by reusing the reader's existing handlers (markup, notes, AI text tools, redaction,
 * read aloud, NVE copy variants, link / bookmark dialogs …). Built by [SelMenuLayer] with ReaderScreen's callbacks.
 */
class SelRunner(
    private val ctx: Context,
    private val s: ReaderState,
    private val actions: ReaderActions,
    private val quick: QuickController,
    private val toast: (String) -> Unit,
    private val onTextTool: (TextSelection, TextAction) -> Unit,
    private val onReadAloud: (page: Int, fromChar: Int) -> Unit,
    private val onPrompt: (TextPrompt) -> Unit,
    private val onOpenLink: (PageLink) -> Unit,
    private val openDialog: (SelDialog, TextSelection) -> Unit,
    /** fix/selmenu-rest: "Sign" / "Insert image" of the empty-area menu place at the pressed point. */
    private val onSignAt: (SelObject) -> Unit = {},
    private val onImageAt: (SelObject) -> Unit = {},
    /** fix/selmenu-rest: the form-field menu (FieldMenu.run); returns true when the menu should close. */
    private val onField: (SelItem, SelObject) -> Boolean = { _, _ -> true },
) {
    private val m get() = SelMenuState.of(s)

    private fun need(n: Need): Boolean = when (n) {
        Need.NONE -> true
        Need.COPY -> s.guard.check(DocAction.COPY)
        Need.ANNOTATE -> s.guard.check(DocAction.ANNOTATE)
        Need.MODIFY -> s.guard.check(DocAction.MODIFY)
        Need.PRINT -> s.guard.check(DocAction.PRINT)
        Need.FILL -> s.guard.check(DocAction.FILL_FORMS)
    }

    private fun copy(text: String, label: String = "text") {
        ReaderIntents.copy(ctx, text, label)
        toast(ctx.getString(R.string.sm_copied))
    }

    private fun start(i: Intent) {
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(ctx, R.string.sm_no_app, Toast.LENGTH_SHORT).show() }
    }

    /** Runs [item]; a disabled one explains why instead. Returns true when the menu should close. */
    fun run(item: SelItem, sel: TextSelection?, obj: SelObject?): Boolean {
        if (!item.enabled) {
            Toast.makeText(ctx, item.why?.let { ctx.getString(SelBindings.why(it)) } ?: ctx.getString(R.string.sm_unavailable), Toast.LENGTH_SHORT).show()
            return false
        }
        val a = item.action
        if (!need(a.need)) return false
        val e = item.entity
        val text = sel?.text.orEmpty()
        when (a) {
            // fix/thumbs-panel: thumbnail items are run by the grid that shows them (ThumbSelMenu), never from the page
            SelAction.THUMB_OPEN, SelAction.THUMB_SELECT, SelAction.THUMB_DESELECT, SelAction.THUMB_ROTATE_LEFT, SelAction.THUMB_ROTATE_RIGHT,
            SelAction.THUMB_DUPLICATE, SelAction.THUMB_DELETE, SelAction.THUMB_EXTRACT, SelAction.THUMB_INSERT_BLANK, SelAction.THUMB_INSERT_FILE,
            SelAction.THUMB_REPLACE, SelAction.THUMB_PRINT -> Unit
            // ---- smart actions
            SelAction.CALL -> e?.let { start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + it.value))) }
            SelAction.WHATSAPP -> e?.let { SelEntities.whatsAppUri(it) }?.let { start(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            SelAction.SMS -> e?.let { start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + it.value))) }
            SelAction.ADD_CONTACT -> e?.let {
                start(Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE).putExtra(ContactsContract.Intents.Insert.PHONE, it.value))
            }
            SelAction.EMAIL -> e?.let { start(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + it.value))) }
            SelAction.OPEN_URL -> e?.let { ReaderIntents.openUri(ctx, it.value) }
            SelAction.COPY_URL -> e?.let { copy(it.value, "link") }
            SelAction.MAP -> e?.let { start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(it.value)))) }
            SelAction.CALENDAR -> e?.let { ent ->
                val millis = runCatching { java.time.LocalDate.parse(ent.value).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
                val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                    .putExtra(CalendarContract.Events.TITLE, s.file?.nameWithoutExtension.orEmpty())
                if (millis != null) i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, millis + 86_400_000L)
                start(i)
            }
            SelAction.COPY_NUMBER -> e?.let { copy(it.value, "number") }

            // ---- clipboard
            SelAction.COPY -> { copy(text); s.selection = null }
            SelAction.COPY_FORMATTED -> sel?.let { s.scope.launch { NveSelectionOps.copyWithFormatting(ctx, s, it); toast(ctx.getString(R.string.sm_copied)) } }
            SelAction.COPY_PLAIN -> copy(SelText.joinLines(text))
            SelAction.COPY_TABLE -> sel?.let { s.scope.launch { if (NveSelectionOps.copyAsTable(ctx, s, it) == 0) toast(ctx.getString(R.string.nv_failed)) else toast(ctx.getString(R.string.sm_copied)) } }
            SelAction.COPY_NO_DIACRITICS -> copy(SelText.stripDiacritics(text))
            SelAction.COPY_NORMALIZED -> copy(SelText.normalize(text))
            SelAction.COPY_LATIN_DIGITS -> copy(SelText.latinDigits(text))
            SelAction.COPY_ARABIC_DIGITS -> copy(SelText.arabicDigits(text))

            // ---- selection
            SelAction.SELECT_SENTENCE -> sel?.let { cur -> s.scope.launch {
                val pageText = s.pageText(cur.page); val boxes = s.charBoxes(cur.page)
                if (pageText.isEmpty()) return@launch
                val r = SelText.sentenceAt(pageText, cur.start)
                val hi = minOf(r.last, boxes.size - 1)
                if (hi >= r.first) s.selection = TextSelection(cur.page, r.first, hi, TextSelectionLogic.rects(boxes, r.first, hi), TextSelectionLogic.text(pageText, r.first, hi, boxes))
            }; return false }
            SelAction.SELECT_ALL -> { val p = sel?.page ?: obj?.page ?: s.currentPage; m.closeObject(); s.scope.launch { NveSelectionOps.selectAll(s, p) }; return false }
            SelAction.COLUMN_SELECT -> { s.selection = null; NveState.of(s).dragMode = NveDrag.COLUMN_SELECT }

            // ---- markup / comments (existing reader handlers)
            SelAction.HIGHLIGHT -> s.markupFromSelection(AnnotKind.HIGHLIGHT)
            SelAction.UNDERLINE -> s.markupFromSelection(AnnotKind.UNDERLINE)
            SelAction.STRIKEOUT -> s.markupFromSelection(AnnotKind.STRIKEOUT)
            SelAction.SQUIGGLY -> s.markupFromSelection(AnnotKind.SQUIGGLY)
            SelAction.REPLACE_TEXT -> s.annot2.pick(ReaderTool.REPLACE)
            SelAction.INSERT_TEXT -> s.annot2.pick(ReaderTool.CARET)
            SelAction.ADD_NOTE -> if (sel != null) sel.rects.firstOrNull()?.let { raw ->
                val r = PageGeometry.normPdf(raw)
                val rtl = SelText.hasArabic(text)
                val x = if (rtl) r.left - 20f else r.right
                s.addAnnot(s.newAnnot(AnnotKind.NOTE, sel.page, rects = listOf(RectF(x, r.top, x + 20f, r.top - 20f)), text = text.take(500)))
                s.selection = null
            }
            SelAction.READING_BOOKMARK -> {
                val page = sel?.page ?: obj?.page ?: s.currentPage
                val title = text.replace('\n', ' ').take(80).ifBlank { ctx.getString(R.string.page_of, page + 1, s.pageCount) }
                s.scope.launch { runCatching { s.app.db.bookmarks().insert(ReadingBookmark(uri = s.fileUri, page = page, title = title)) }; toast(ctx.getString(R.string.nv_bookmark_added)) }
                s.selection = null
            }
            SelAction.PDF_BOOKMARK -> sel?.let { openDialog(SelDialog.PDF_BOOKMARK, it); return true }
            SelAction.CREATE_LINK -> sel?.let { openDialog(SelDialog.CREATE_LINK, it); return true }

            // ---- look up
            SelAction.SEARCH_DOC -> { s.searchOpen = true; s.runSearch(text.replace('\n', ' ').trim().take(120)); s.selection = null }
            SelAction.WEB_SEARCH -> ReaderIntents.webSearch(ctx, text)
            SelAction.READ_ALOUD -> sel?.let { onReadAloud(it.page, it.start); s.selection = null }
            SelAction.WORD_COUNT -> sel?.let { openDialog(SelDialog.WORD_COUNT, it); return true }
            SelAction.OTHER_APPS -> runCatching {
                val i = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, text.take(5000)).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                ctx.startActivity(Intent.createChooser(i, ctx.getString(R.string.sm_other_apps)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Toast.makeText(ctx, R.string.sm_no_app, Toast.LENGTH_SHORT).show() }

            // ---- AI (ReaderScreen's TextToolsSheet; rewrite there can replace the text in the document)
            SelAction.AI_TRANSLATE -> sel?.let { onTextTool(it, TextAction.TRANSLATE) }
            SelAction.AI_EXPLAIN -> sel?.let { onTextTool(it, TextAction.EXPLAIN) }
            SelAction.AI_DEFINE -> sel?.let { onTextTool(it, TextAction.DEFINE) }
            SelAction.AI_SUMMARIZE -> sel?.let { onTextTool(it, TextAction.SUMMARIZE) }
            SelAction.AI_REWRITE -> sel?.let { onTextTool(it, TextAction.REWRITE) }

            // ---- edit
            SelAction.EDIT_TEXT -> sel?.let { NveState.of(s).requestEdit = it.page to it.rects.firstOrNull()?.let { r -> PageGeometry.normPdf(r) } }
            SelAction.REDACT -> sel?.let { com.smartdocs.editor.ui.search.ReaderRedact.markSelection(ctx, s, quick, it) }

            // ---- send out
            SelAction.SHARE -> ReaderIntents.shareText(ctx, text)
            SelAction.EXPORT_TXT -> sel?.let { openDialog(SelDialog.EXPORT, it); return true }

            // ---- link
            SelAction.LINK_OPEN -> obj?.link?.let { onOpenLink(it) }
            SelAction.LINK_COPY -> obj?.link?.uri?.let { copy(it, "link") }
            SelAction.LINK_SHARE -> obj?.link?.uri?.let { ReaderIntents.shareText(ctx, it) }

            // ---- empty area
            SelAction.PASTE -> NveClipboard.paste(ctx, s)
            SelAction.PASTE_STAMP -> obj?.let { s.pasteStampAt(it.page, it.x, it.y) }
            SelAction.NOTE_HERE -> obj?.let { onPrompt(TextPrompt(ReaderTool.NOTE, it.page, it.x, it.y)) }
            SelAction.TEXT_HERE -> obj?.let { onPrompt(TextPrompt(ReaderTool.FREETEXT, it.page, it.x, it.y)) }
            SelAction.FILL_TEXT_HERE -> obj?.let { onPrompt(TextPrompt(ReaderTool.FS_TEXT, it.page, it.x, it.y)) }
            // fix/selmenu-rest: both drop their result at the pressed point (FsGeom: rotation and crop box)
            SelAction.IMAGE_HERE -> obj?.let { onImageAt(it) } ?: actions.pickImage()
            SelAction.SIGN_HERE -> obj?.let { onSignAt(it) } ?: run { if (s.tab != ReaderTab.SIGN) s.tab = ReaderTab.SIGN; toast(ctx.getString(R.string.sm_sign_hint)) }
            SelAction.SNAPSHOT -> NveState.of(s).open(NveSurface.SNAPSHOT)
            SelAction.READ_PAGE -> onReadAloud(obj?.page ?: s.currentPage, 0)
            SelAction.SEARCH -> actions.search()
            SelAction.MARQUEE_ZOOM -> NveState.of(s).dragMode = NveDrag.ZOOM_IN
            SelAction.ZOOM -> NveState.of(s).open(NveSurface.ZOOM)
            SelAction.GO_TO_PAGE -> actions.goToPage()
            SelAction.ROTATE_VIEW -> s.rotation = (s.rotation + 90) % 360
            SelAction.PRINT -> actions.print()
            SelAction.DOC_INFO -> actions.docInfo()

            // ---- form field (fix/selmenu-rest)
            SelAction.F_COPY_VALUE, SelAction.F_PASTE, SelAction.F_CLEAR, SelAction.F_RESET, SelAction.F_PICK_IMAGE, SelAction.F_SIGN,
            SelAction.F_SPELL, SelAction.F_CALCULATE, SelAction.F_PROPS, SelAction.F_DESIGNER, SelAction.F_DUPLICATE, SelAction.F_DELETE,
            SelAction.F_TAB_ORDER -> return obj?.let { onField(item, it) } ?: true

            // ---- annotation / page-content picture: run by their own bars (AnnotMenu, EditImageMenu)
            else -> return false
        }
        return true
    }

}
