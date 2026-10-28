package com.smartdocs.editor.ui.nve

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.ReadingBookmark
import com.smartdocs.editor.core.edit.Affine
import com.smartdocs.editor.core.edit.ContentEdit
import com.smartdocs.editor.core.edit.EditTextStyle
import com.smartdocs.editor.core.edit.PageTextModel
import com.smartdocs.editor.core.edit.TextBlock
import com.smartdocs.editor.core.nve.CharBox
import com.smartdocs.editor.core.nve.StyledCopy
import com.smartdocs.editor.core.nve.StyledRun
import com.smartdocs.editor.core.nve.TableCopy
import com.smartdocs.editor.core.nve.TextStats
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.TextSelection
import com.smartdocs.editor.ui.reader.TextSelectionLogic
import com.smartdocs.editor.ui.reader.ToolChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Operations on the reader's text selection added by the nitro-view-edit work. */
object NveSelectionOps {
    /** Select every character of [page]. */
    suspend fun selectAll(s: ReaderState, page: Int) {
        val text = s.pageText(page); val boxes = s.charBoxes(page)
        if (text.isEmpty() || boxes.isEmpty()) return
        val end = minOf(text.length, boxes.size) - 1
        s.selection = TextSelection(page, 0, end, TextSelectionLogic.rects(boxes, 0, end), TextSelectionLogic.text(text, 0, end, boxes))
    }

    /** Characters of [sel] with their boxes (PDF space; for a column selection only the ones inside its rects). */
    suspend fun chars(s: ReaderState, sel: TextSelection): List<CharBox> {
        val text = s.pageText(sel.page); val boxes = s.charBoxes(sel.page)
        val out = ArrayList<CharBox>()
        val lo = sel.start.coerceAtLeast(0); val hi = minOf(sel.end, text.length - 1, boxes.size - 1)
        for (i in lo..hi) {
            val b = PageGeometry.normPdf(boxes[i])
            val cx = (b.left + b.right) / 2f; val cy = (b.top + b.bottom) / 2f
            if (sel.rects.isNotEmpty() && sel.rects.none { r -> val n = PageGeometry.normPdf(r); cx in n.left - 1f..n.right + 1f && cy in n.bottom - 1f..n.top + 1f }) continue
            out.add(CharBox(text[i], b.left, b.top, b.right, b.bottom))
        }
        return out
    }

    private fun clip(ctx: Context, plain: String, html: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        runCatching { cm.setPrimaryClip(ClipData.newHtmlText("SmartDocs", plain.take(200_000), html.take(400_000))) }
    }

    /** "Copy as table": rows by baseline, cells by wide gaps; HTML table + tab-separated text on the clipboard. */
    suspend fun copyAsTable(ctx: Context, s: ReaderState, sel: TextSelection): Int {
        if (!s.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) return -1      // nitro-protect2: author restrictions / Protected View
        val cs = chars(s, sel)
        val rtl = cs.count { TextStats.isArabic(it.c) } > cs.count { it.c.isLetter() } / 2
        val cells = TableCopy.cells(cs, rtl = rtl)
        if (cells.isEmpty()) return 0
        clip(ctx, TableCopy.toTsv(cells), TableCopy.toHtml(cells, rtl))
        toast(ctx, ctx.getString(R.string.nv_table_copied, cells.size, cells.maxOf { it.size }))
        return cells.size
    }

    /** Text blocks of the page that intersect the selection (display space comparison). */
    private suspend fun blocksOf(s: ReaderState, sel: TextSelection): List<TextBlock> {
        val f = s.file ?: return emptyList()
        val m = runCatching { PageTextModel.load(f, s.password, sel.page) }.getOrNull() ?: return emptyList()
        val toDisplay = Affine.fromValues(m.toDisplay)
        val rects = sel.rects.map { r ->
            val n = PageGeometry.normPdf(r)
            val (x0, y0) = Affine.apply(toDisplay, n.left, n.bottom); val (x1, y1) = Affine.apply(toDisplay, n.right, n.top)
            floatArrayOf(minOf(x0, x1), maxOf(y0, y1), maxOf(x0, x1), minOf(y0, y1))
        }
        return m.blocks.filter { b -> rects.any { r -> b.rect.right > r[0] && b.rect.left < r[2] && b.rect.top > r[3] && b.rect.bottom < r[1] } }
    }

    /** "Copy with formatting": HTML with font size / bold / italic / colour / family of the text blocks under the selection. */
    suspend fun copyWithFormatting(ctx: Context, s: ReaderState, sel: TextSelection) {
        if (!s.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) return
        val blocks = blocksOf(s, sel).sortedWith(compareByDescending<TextBlock> { it.rect.top }.thenBy { it.rect.left })
        val rtl = sel.text.count { TextStats.isArabic(it) } > sel.text.count { it.isLetter() } / 2
        val runs = if (blocks.size <= 1) {
            val b = blocks.firstOrNull()
            listOf(StyledRun(sel.text, b?.size ?: 12f, b?.bold ?: false, b?.italic ?: false, b?.color ?: 0, b?.fontName ?: ""))
        } else blocks.mapIndexed { i, b ->
            // first / last block: only the part that is in the selection when it can be found
            val t = when {
                i == 0 && sel.text.length < b.text.length && b.text.indexOf(sel.text.take(12)) >= 0 -> b.text.substring(b.text.indexOf(sel.text.take(12)))
                else -> b.text
            }
            StyledRun(t, b.size, b.bold, b.italic, b.color, b.fontName, newLine = i > 0)
        }
        clip(ctx, sel.text, StyledCopy.html(runs, rtl))
        toast(ctx, ctx.getString(R.string.nv_copied_formatting))
    }

    /**
     * Replace the selected text in the page content (AI rewrite / grammar results): the one text block that holds the
     * selection is rewritten with the same style; one undo step. Returns false when the selection spans several blocks
     * or its text is not found in the block (the caller then offers a note instead).
     */
    suspend fun replaceInContent(ctx: Context, s: ReaderState, sel: TextSelection, replacement: String): Boolean {
        if (!s.guard.check(com.smartdocs.editor.core.security.DocAction.MODIFY)) return true      // refused: the guard already said why
        val blocks = blocksOf(s, sel)
        val b = blocks.singleOrNull() ?: return false
        val norm = { x: String -> x.replace(Regex("\\s+"), " ").trim() }
        val bt = b.text
        val exact = bt.indexOf(sel.text) >= 0
        val source = if (exact) bt else norm(bt)
        val key = if (exact) sel.text else norm(sel.text)
        val at = source.indexOf(key)
        if (at < 0 || key.isEmpty()) return false
        val newText = source.substring(0, at) + replacement + source.substring(at + key.length)
        val st = EditTextStyle(size = b.size, color = b.color, bold = b.bold, italic = b.italic, align = b.align, family = b.family)
        val page = sel.page
        runCatching { s.applyInPlace(label = { ctx.getString(R.string.hi_content_edit, page + 1) }) { src, out, pw ->
            ContentEdit.replaceText(ctx, src, out, pw, page, b.ops, newText, st)
        } }.getOrNull() ?: return false
        s.selection = null
        return true
    }

    fun toast(ctx: Context, msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * Extra chips of the reader's selection bar (hook at the end of SelectionMenu): squiggly, note, bookmark, word count,
 * copy with formatting, copy as table, select all, column selection, export selection, AI rewrite (replaces the
 * text in the page content), edit text in Edit mode.
 */
@Composable
fun NveSelectionChips(sel: TextSelection, s: ReaderState) {
    val ctx = LocalContext.current
    val app = com.smartdocs.editor.LocalApp.current
    val scope = rememberCoroutineScope()
    val n = NveState.of(s)
    var rewrite by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(sel.text.toByteArray()) } != null }.getOrDefault(false) }
            NveSelectionOps.toast(ctx, ctx.getString(if (ok) R.string.success else R.string.nv_failed))
        }
    }
    VerticalDivider(Modifier.height(36.dp).padding(horizontal = 4.dp))
    ToolChip(Icons.Default.Waves, stringResource(R.string.annot_squiggly)) { s.markupFromSelection(AnnotKind.SQUIGGLY) }
    ToolChip(Icons.Default.StickyNote2, stringResource(R.string.annot_note)) {
        sel.rects.firstOrNull()?.let { r ->
            val top = maxOf(r.top, r.bottom)
            s.addAnnot(s.newAnnot(AnnotKind.NOTE, sel.page, rects = listOf(RectF(maxOf(r.left, r.right), top, maxOf(r.left, r.right) + 20f, top - 20f)), text = sel.text.take(500)))
        }
        s.selection = null
    }
    ToolChip(Icons.Default.BookmarkAdd, stringResource(R.string.add_bookmark)) {
        scope.launch { app.db.bookmarks().insert(ReadingBookmark(uri = s.fileUri, page = sel.page, title = sel.text.replace('\n', ' ').take(80))); NveSelectionOps.toast(ctx, ctx.getString(R.string.nv_bookmark_added)) }
        s.selection = null
    }
    ToolChip(Icons.Default.FormatPaint, stringResource(R.string.nv_copy_formatting)) { scope.launch { NveSelectionOps.copyWithFormatting(ctx, s, sel) } }
    ToolChip(Icons.Default.TableChart, stringResource(R.string.nv_copy_table)) { scope.launch { if (NveSelectionOps.copyAsTable(ctx, s, sel) == 0) NveSelectionOps.toast(ctx, ctx.getString(R.string.nv_failed)) } }
    ToolChip(Icons.Default.SelectAll, stringResource(R.string.nv_select_all)) { scope.launch { NveSelectionOps.selectAll(s, sel.page) } }
    ToolChip(Icons.Default.ViewColumn, stringResource(R.string.nv_column_select)) { s.selection = null; n.dragMode = NveDrag.COLUMN_SELECT }
    ToolChip(Icons.Default.Output, stringResource(R.string.nv_export_selection)) { if (s.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) export.launch((s.file?.nameWithoutExtension ?: "selection") + "_p${sel.page + 1}.txt") }
    ToolChip(Icons.Default.EditNote, stringResource(R.string.nv_edit_text)) { if (s.guard.check(com.smartdocs.editor.core.security.DocAction.MODIFY)) n.requestEdit = sel.page to sel.rects.firstOrNull()?.let { PageGeometry.normPdf(it) } }
    if (LocalSettings.current.aiEnabled) ToolChip(Icons.Default.AutoFixHigh, stringResource(R.string.nv_rewrite)) { if (s.guard.check(com.smartdocs.editor.core.security.DocAction.COPY)) rewrite = true }
    if (rewrite) com.smartdocs.editor.ui.ai5.TextToolsSheet(sel.text, com.smartdocs.editor.core.ai5.TextAction.REWRITE, docContext = null,
        onReplace = { t -> scope.launch { if (!NveSelectionOps.replaceInContent(ctx, s, sel, t)) NveSelectionOps.toast(ctx, ctx.getString(R.string.nv_replace_failed)) } },
        onAddNote = { t -> sel.rects.firstOrNull()?.let { r -> val top = maxOf(r.top, r.bottom); s.addAnnot(s.newAnnot(AnnotKind.NOTE, sel.page, rects = listOf(RectF(r.left, top, r.left + 20f, top - 20f)), text = t)) } },
        onShare = { com.smartdocs.editor.ui.reader.ReaderIntents.shareText(ctx, it) }, onDismiss = { rewrite = false })
}

/** Paste from the system clipboard into the open PDF: an image is placed with the image tool, text as a text box. */
object NveClipboard {
    fun paste(ctx: Context, s: ReaderState) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) { NveSelectionOps.toast(ctx, ctx.getString(R.string.nv_clipboard_empty)); return }
        val item = clip.getItemAt(0)
        val uri = item.uri
        val mime = uri?.let { ctx.contentResolver.getType(it) } ?: clip.description.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) }.firstOrNull { it.startsWith("image/") } }
        if (uri != null && mime?.startsWith("image/") == true) {
            s.scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
                }
                if (bmp == null) { NveSelectionOps.toast(ctx, ctx.getString(R.string.nv_failed)); return@launch }
                s.pendingImage = bmp; s.tool = com.smartdocs.editor.ui.reader.ReaderTool.IMAGE; s.selection = null
                NveSelectionOps.toast(ctx, ctx.getString(R.string.nv_tap_to_place))
            }
            return
        }
        val text = item.coerceToText(ctx)?.toString()?.takeIf { it.isNotBlank() }
        if (text == null) { NveSelectionOps.toast(ctx, ctx.getString(R.string.nv_clipboard_empty)); return }
        val n = NveState.of(s)
        n.pasteText = text; n.dragMode = NveDrag.PLACE
    }
}
