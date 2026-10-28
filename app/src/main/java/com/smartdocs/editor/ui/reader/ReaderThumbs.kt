package com.smartdocs.editor.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.ThumbMoves
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.security.DocAction
import com.smartdocs.editor.core.selmenu.SelAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** fix/thumbs-panel: the thumbnail width of the reader panel (dp, one of [ThumbMoves.SIZES]), kept across documents. */
object ThumbPanelPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("thumbs_panel", Context.MODE_PRIVATE)
    fun size(ctx: Context): Int = ThumbMoves.normalize(prefs(ctx).getInt("size", ThumbMoves.DEFAULT_SIZE))
    fun setSize(ctx: Context, v: Int) = prefs(ctx).edit().putInt("size", ThumbMoves.normalize(v)).apply()
}

/** Drag bookkeeping (as in ui/pages/PageGrid). */
private class ThumbDrag {
    var from by mutableStateOf<Int?>(null)
    var to by mutableStateOf<Int?>(null)
    var offset by mutableStateOf(Offset.Zero)
    var moved = false
    var overscroll by mutableFloatStateOf(0f)
    fun reset() { from = null; to = null; offset = Offset.Zero; moved = false; overscroll = 0f }
}

private fun LazyGridState.itemAt(p: Offset): LazyGridItemInfo? = layoutInfo.visibleItemsInfo.firstOrNull { i ->
    p.x >= i.offset.x && p.x <= i.offset.x + i.size.width && p.y >= i.offset.y && p.y <= i.offset.y + i.size.height
}

/** What a picked PDF is for: insert its pages at [at], or replace [pages] with its first pages. */
private sealed class PickFor {
    data class Insert(val at: Int) : PickFor()
    data class Replace(val pages: List<Int>) : PickFor()
}

/**
 * fix/thumbs-panel (FINAL_CHECK §17): the reader's thumbnails panel organises pages, not only navigates.
 * - tap = go to the page (in select mode: select / deselect); long-press = the thumbnail menu (selection-menu registry,
 *   [com.smartdocs.editor.ui.selmenu.ThumbSelMenu]); long-press and drag = move the page, or the whole selection when the
 *   dragged page is selected;
 * - the action row acts on the selection, or on the current page when nothing is selected: rotate, delete, duplicate,
 *   extract, insert a blank page / pages from a file, replace with pages from a file, print;
 * - the thumbnail size is a setting (− / +, remembered).
 * Every change goes through [ReaderState.applyInPlace] (permission and signed-file gate, one undo step, write-back) with
 * the Organize core (PdfOps / PageOps5, page tree changed in place so bookmarks and links follow the moved pages).
 */
@Composable
internal fun ReaderThumbnailsPanel(state: ReaderState, onJump: (Int) -> Unit) {
    val session = state.session ?: return
    val ctx = LocalContext.current
    val ai = com.smartdocs.editor.LocalSettings.current.aiEnabled
    var size by remember { mutableIntStateOf(ThumbPanelPrefs.size(ctx)) }
    var selectMode by remember(state.file) { mutableStateOf(false) }
    var selectedRaw by remember(state.file) { mutableStateOf(setOf<Int>()) }
    val selected = selectedRaw.filter { it in 0 until state.pageCount }.toSet()
    var busy by remember { mutableStateOf(false) }
    var menuPage by remember { mutableStateOf<Int?>(null) }
    var pickFor by remember { mutableStateOf<PickFor?>(null) }
    var locked by remember { mutableStateOf<Pair<File, PickFor>?>(null) }
    var deleteAsk by remember { mutableStateOf<List<Int>?>(null) }

    val failed = stringResource(R.string.tpn_failed)
    val cannotDeleteAll = stringResource(R.string.pg_cannot_delete_all)
    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun setSelection(s: Set<Int>) { selectedRaw = s; selectMode = s.isNotEmpty() }
    /** The pages the action row works on. */
    fun acting(): List<Int> = selected.sorted().ifEmpty { listOf(state.currentPage.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))) }

    // one in-place step; on the reader's scope so closing the panel does not cancel a running change
    fun inPlace(label: Int, after: () -> Unit = {}, op: suspend (File, File, String?) -> Unit) {
        if (busy || state.saving) return
        busy = true
        state.scope.launch {
            try { if (state.applyInPlace(label = { ctx.getString(label) }, op = op) != null) after() }
            catch (e: CancellationException) { throw e }
            catch (e: ReaderGuard.Refused) { toast(e.message ?: failed) }
            catch (e: Throwable) { toast(failed) }
            finally { busy = false }
        }
    }

    fun rotate(pages: List<Int>, deg: Int) = inPlace(R.string.tpn_hi_rotate) { s, o, pw -> PdfOps.rotatePages(s, pages, deg, o, pw) }
    fun delete(pages: List<Int>) {
        if (pages.size >= state.pageCount) { toast(cannotDeleteAll); return }
        inPlace(R.string.tpn_hi_delete, after = { setSelection(emptySet()) }) { s, o, pw -> PdfOps.deletePages(s, pages.toSet(), o, pw) }
    }
    fun duplicate(pages: List<Int>) = inPlace(R.string.tpn_hi_duplicate, after = { setSelection(emptySet()) }) { s, o, pw ->
        com.smartdocs.editor.core.edit5.PageOps5.duplicate(s, o, pw, pages, 1, false)
    }
    fun insertBlank(at: Int) = inPlace(R.string.tpn_hi_blank, after = { setSelection(emptySet()); onJump(at) }) { s, o, pw -> PdfOps.insertBlankPage(s, at, o, password = pw) }
    fun move(order: List<Int>, moved: List<Int>) = inPlace(R.string.tpn_hi_move, after = { if (selected.isNotEmpty()) setSelection(ThumbMoves.indexesAfter(order, moved)) }) { s, o, pw ->
        PdfOps.reorder(s, order, o, pw)
    }
    fun useFile(f: File, purpose: PickFor, otherPw: String?) = when (purpose) {
        is PickFor.Insert -> inPlace(R.string.tpn_hi_insert, after = { setSelection(emptySet()) }) { s, o, pw ->
            // a password-protected source is unlocked into a temp copy first (as in Organize)
            val u = withContext(Dispatchers.IO) { com.smartdocs.editor.core.pdf.LockedPdf.unlockedForInsert(f, otherPw, state.app.documents.tempDir) }
            try { PdfOps.insertPagesFrom(s, u ?: f, purpose.at, o, pw) } finally { u?.delete() }
        }
        // pairwise: page k of the selection becomes page k of the picked file (PageOps5.replace re-points bookmarks and links)
        is PickFor.Replace -> inPlace(R.string.tpn_hi_replace) { s, o, pw ->
            com.smartdocs.editor.core.edit5.PageOps5.replace(s, o, pw, purpose.pages, f, otherPw, purpose.pages.indices.toList())
        }
    }
    val pick = com.smartdocs.editor.ui.common.rememberPdfPicker { files ->
        val f = files.firstOrNull(); val p = pickFor; pickFor = null
        if (f != null && p != null) state.scope.launch {
            if (withContext(Dispatchers.IO) { com.smartdocs.editor.core.pdf.LockedPdf.needsPassword(f) }) locked = f to p else useFile(f, p, null)
        }
    }
    fun extract(pages: List<Int>) {
        if (!state.guard.check(DocAction.COPY)) return
        val f = state.file ?: return
        state.scope.launch {
            try {
                if (state.dirty && !state.save()) return@launch
                val docs = state.app.documents
                val out = docs.uniqueFile(docs.docsDir, f.nameWithoutExtension + "_pages")
                PdfOps.keepPages(f, pages, out, state.password)
                runCatching { docs.touchRecent(out, pages.size) }
                toast(ctx.getString(R.string.tpn_extracted, out.name))
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { toast(failed) }
        }
    }
    fun print(pages: List<Int>) {
        if (!state.guard.check(DocAction.PRINT)) return
        val f = state.file ?: return
        state.scope.launch {
            try {
                if (state.dirty && !state.save()) return@launch
                com.smartdocs.editor.core.util.PdfPrint.printDocument(ctx, f, state.password, com.smartdocs.editor.ui.pages.OrganizeLogic.rangeSpec(pages),
                    f.nameWithoutExtension, state.app.documents.tempDir)
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { toast(failed) }
        }
    }
    fun runAction(a: SelAction, pages: List<Int>, pressed: Int) {
        when (a) {
            SelAction.THUMB_OPEN -> onJump(pressed)
            SelAction.THUMB_SELECT -> setSelection(selected + pressed)
            SelAction.THUMB_DESELECT -> setSelection(selected - pressed)
            SelAction.THUMB_ROTATE_LEFT -> rotate(pages, -90)
            SelAction.THUMB_ROTATE_RIGHT -> rotate(pages, 90)
            SelAction.THUMB_DUPLICATE -> duplicate(pages)
            SelAction.THUMB_DELETE -> deleteAsk = pages
            SelAction.THUMB_EXTRACT -> extract(pages)
            SelAction.THUMB_INSERT_BLANK -> insertBlank(pages.max() + 1)
            SelAction.THUMB_INSERT_FILE -> { pickFor = PickFor.Insert(pages.max() + 1); pick() }
            SelAction.THUMB_REPLACE -> { pickFor = PickFor.Replace(pages); pick() }
            SelAction.THUMB_PRINT -> print(pages)
            else -> Unit
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // size and selection
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(selectMode, { on -> selectMode = on; if (!on) selectedRaw = emptySet() }) {
                Icon(if (selectMode) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, stringResource(R.string.tpn_select_mode))
            }
            IconButton(onClick = { setSelection(if (selected.size == state.pageCount) emptySet() else (0 until state.pageCount).toSet()) }) {
                Icon(Icons.Default.SelectAll, stringResource(R.string.tpn_select_all))
            }
            if (selected.isNotEmpty()) Text(stringResource(R.string.tpn_n_selected, selected.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { size = ThumbMoves.step(size, -1); ThumbPanelPrefs.setSize(ctx, size) }, enabled = size > ThumbMoves.SIZES.first()) {
                Icon(Icons.Default.ZoomOut, stringResource(R.string.tpn_smaller))
            }
            IconButton(onClick = { size = ThumbMoves.step(size, 1); ThumbPanelPrefs.setSize(ctx, size) }, enabled = size < ThumbMoves.SIZES.last()) {
                Icon(Icons.Default.ZoomIn, stringResource(R.string.tpn_larger))
            }
        }
        // page actions: the selection, else the current page
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val acts = listOf(
                SelAction.THUMB_ROTATE_LEFT, SelAction.THUMB_ROTATE_RIGHT, SelAction.THUMB_DELETE, SelAction.THUMB_DUPLICATE, SelAction.THUMB_EXTRACT,
                SelAction.THUMB_INSERT_BLANK, SelAction.THUMB_INSERT_FILE, SelAction.THUMB_REPLACE, SelAction.THUMB_PRINT,
            )
            acts.forEach { a ->
                val label = stringResource(com.smartdocs.editor.ui.selmenu.SelBindings.label(a))
                IconButton(onClick = { val p = acting(); runAction(a, p, p.first()) }, enabled = !busy) {
                    Icon(com.smartdocs.editor.ui.selmenu.SelBindings.icon(a), label)
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))

        ThumbGrid(state, session, size, selected, selectMode, enabled = !busy && !state.saving,
            onTap = { p -> if (selectMode) setSelection(if (p in selected) selected - p else selected + p) else onJump(p) },
            onLongPress = { p -> menuPage = p },
            onMove = { from, to ->
                // the selection moves as a block when the dragged page is part of it
                val moving = if (from in selected) selected.toList() else listOf(from)
                ThumbMoves.moveOrder(state.pageCount, moving, to, forward = to > from)?.let { move(it, moving) }
            },
            menu = { p ->
                if (menuPage == p) com.smartdocs.editor.ui.selmenu.ThumbSelMenu(
                    com.smartdocs.editor.ui.selmenu.readerThumbEnv(state, ai), p, selected, state.pageCount,
                    onDismiss = { menuPage = null },
                    onItem = { a, pages -> runAction(a, pages, p) },
                )
            })
    }

    deleteAsk?.let { pages ->
        AlertDialog(onDismissRequest = { deleteAsk = null },
            title = { Text(if (pages.size == 1) stringResource(R.string.tpn_delete_one_q, pages[0] + 1) else stringResource(R.string.tpn_delete_many_q, pages.size)) },
            confirmButton = { TextButton(onClick = { deleteAsk = null; delete(pages) }) { Text(stringResource(R.string.tpn_delete)) } },
            dismissButton = { TextButton(onClick = { deleteAsk = null }) { Text(stringResource(R.string.cancel)) } })
    }
    locked?.let { (f, purpose) ->
        com.smartdocs.editor.ui.tools.FilePasswordDialog(f, onUnlocked = { pw -> locked = null; useFile(f, purpose, pw) }, onRemove = { locked = null })
    }
}

@Composable
private fun ThumbGrid(
    state: ReaderState, session: com.smartdocs.editor.core.pdf.PdfSession, sizeDp: Int, selected: Set<Int>, selectMode: Boolean, enabled: Boolean,
    onTap: (Int) -> Unit, onLongPress: (Int) -> Unit, onMove: (Int, Int) -> Unit, menu: @Composable (Int) -> Unit,
) {
    // audit 5 G-15: opens at the current page, shows page labels, and keeps rendered thumbnails while scrolling
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState(initialFirstVisibleItemIndex = state.currentPage.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)))
    val labels = com.smartdocs.editor.ui.edit5.rememberPageLabels(state)
    val renderPx = with(LocalDensity.current) { sizeDp.dp.roundToPx() }.coerceIn(96, 720)
    val cache = remember(session, state.renderVersion, state.nightMode, renderPx) {
        object : android.util.LruCache<Int, Bitmap>(16 shl 20) { override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount }
    }
    val drag = remember { ThumbDrag() }
    // the gesture coroutine outlives recompositions: always call the latest callbacks (current selection)
    val longPressNow by rememberUpdatedState(onLongPress)
    val moveNow by rememberUpdatedState(onMove)
    LaunchedEffect(drag.from) {
        while (drag.from != null) { if (drag.overscroll != 0f) grid.scrollBy(drag.overscroll); delay(16) }
    }
    val count = state.pageCount
    LazyVerticalGrid(GridCells.Adaptive(sizeDp.dp), Modifier.fillMaxWidth().heightIn(max = 560.dp).pointerInput(enabled, count) {
        detectDragGesturesAfterLongPress(
            onDragStart = { p -> grid.itemAt(p)?.let { drag.from = it.index; drag.to = it.index; drag.offset = Offset.Zero; drag.moved = false } },
            onDrag = { change, amount ->
                change.consume()
                drag.offset += amount
                if (enabled && drag.offset.getDistance() > 12f) drag.moved = true
                val from = drag.from ?: return@detectDragGesturesAfterLongPress
                grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == from }?.let { info ->
                    val c = Offset(info.offset.x + info.size.width / 2f + drag.offset.x, info.offset.y + info.size.height / 2f + drag.offset.y)
                    grid.itemAt(c)?.let { drag.to = it.index }
                    val vh = grid.layoutInfo.viewportEndOffset.toFloat()
                    drag.overscroll = when { c.y < 60f -> -16f; c.y > vh - 60f -> 16f; else -> 0f }
                }
            },
            onDragEnd = {
                val from = drag.from; val to = drag.to
                if (from != null) {
                    if (!drag.moved) longPressNow(from)
                    else if (enabled && to != null && to != from) moveNow(from, to)
                }
                drag.reset()
            },
            onDragCancel = { drag.reset() },
        )
    }, state = grid, contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items((0 until count).toList(), key = { it }) { page ->
            // keyed on the session too: after a save the old session is closed and a fresh one replaces it
            val bmp by produceState<Bitmap?>(cache.get(page), page, session, state.renderVersion, state.nightMode, renderPx) {
                if (value != null) return@produceState
                if (session.isClosed || page >= session.pageCount) return@produceState
                val (w, h) = state.pageSize(page)
                value = runCatching { session.render(page, renderPx, (renderPx * h / w).toInt().coerceAtLeast(1), state.nightMode, true) }.getOrNull()
                    ?.also { cache.put(page, it) }
            }
            val current = page == state.currentPage
            val sel = page in selected
            val dragging = drag.from == page && drag.moved
            val target = drag.to == page && drag.moved && drag.from != page
            val border = when { target -> MaterialTheme.colorScheme.tertiary; sel -> MaterialTheme.colorScheme.primary; current -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.outlineVariant }
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { if (dragging) { translationX = drag.offset.x; translationY = drag.offset.y; scaleX = 1.05f; scaleY = 1.05f; shadowElevation = 16f } }
                    .clickable { onTap(page) }) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(6.dp)).background(Color.White)
                        .border(if (current || sel || target) 2.dp else 1.dp, border, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    if (selectMode || sel) Icon(
                        if (sel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                        Modifier.align(Alignment.TopStart).padding(4.dp).size(20.dp).background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(50)),
                        tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                    if (sel) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)))
                }
                val label = labels?.getOrNull(page)?.takeIf { it.isNotBlank() && it != (page + 1).toString() }
                Text(if (label != null) "$label (${page + 1})" else (page + 1).toString(), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (current || sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                menu(page)
            }
        }
    }
}
