package com.smartdocs.editor.ui.nve.articles

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.NveArticles
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

/** Per-reader state of the Articles feature (weak map keyed by the reader, like V5State). */
class NveArticlesState private constructor(val s: ReaderState) {
    var articles by mutableStateOf<List<NveArticles.Article>>(emptyList())
    /** Boxes being drawn: for a new article (target = -1) or to insert into article [target] at [insertAt]. */
    var drawing by mutableStateOf(false)
    var target by mutableIntStateOf(-1)
    var insertAt by mutableIntStateOf(-1)
    val pending = mutableStateListOf<NveArticles.Bead>()
    /** Article being read and its current box. */
    var reading by mutableStateOf<Pair<Int, Int>?>(null)
    /** Show every article box on the pages (panel open / drawing / reading). */
    var showBoxes by mutableStateOf(false)
    var askInfoForNew by mutableStateOf(false)
    /** §22: box (article, bead) being moved / resized and its rect while dragging ([llx, lly, urx, ury], user space). */
    var adjusting by mutableStateOf<Pair<Int, Int>?>(null)
    var adjustRect by mutableStateOf<FloatArray?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loadedVersion = -1

    fun prefs(): android.content.SharedPreferences = s.context.getSharedPreferences("nve_articles", Context.MODE_PRIVATE)
    var hideAfterUse: Boolean
        get() = prefs().getBoolean("hide_after_use", true)
        set(v) { prefs().edit().putBoolean("hide_after_use", v).apply() }

    suspend fun load() {
        val f = s.file ?: return
        articles = withContext(Dispatchers.IO) { runCatching { PdfOps.load(f, s.password).use { NveArticles.list(it) } }.getOrDefault(emptyList()) }
        loadedVersion = s.renderVersion
    }

    /** One undoable document change (file step of the unified history). */
    suspend fun change(label: String, op: (com.tom_roush.pdfbox.pdmodel.PDDocument) -> Unit) {
        if (busy) return
        busy = true
        try {
            s.applyInPlace(label = { label }) { src, out, pw ->
                withContext(Dispatchers.IO) { PdfOps.load(src, pw).use { d -> op(d); d.save(out) } }
            }
            load()
        } catch (e: Throwable) { error = e.message ?: e.toString() } finally { busy = false }
    }

    fun startNew() { pending.clear(); target = -1; insertAt = -1; drawing = true; showBoxes = true; reading = null }
    fun startInsert(article: Int, at: Int) { pending.clear(); target = article; insertAt = at; drawing = true; showBoxes = true; reading = null }
    fun cancelDrawing() { pending.clear(); drawing = false }
    fun startAdjust(article: Int, bead: Int) {
        val b = articles.getOrNull(article)?.beads?.getOrNull(bead) ?: return
        cancelDrawing(); reading = null; showBoxes = true
        adjusting = article to bead; adjustRect = b.rect.copyOf()
    }
    fun cancelAdjust() { adjusting = null; adjustRect = null }

    companion object {
        private val map = WeakHashMap<ReaderState, NveArticlesState>()
        fun of(s: ReaderState): NveArticlesState = synchronized(map) { map.getOrPut(s) { NveArticlesState(s) } }
        fun forget(s: ReaderState) { synchronized(map) { map.remove(s) } }
    }
}

/**
 * Articles panel: list of article threads, New article (draw boxes), Read article, properties, delete, combine,
 * renumber, and per-box show / move / insert / delete. [onShowRect] = jump to page and zoom to the box
 * (rect = [llx, lly, urx, ury] in the page's PDF user space).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NveArticlesPane(state: ReaderState, onShowRect: (Int, FloatArray) -> Unit, onClose: (() -> Unit)? = null) {
    val a = NveArticlesState.of(state)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    LaunchedEffect(state.renderVersion, state.file) { a.load() }
    DisposableEffect(Unit) { a.showBoxes = true; onDispose { if (!a.drawing && a.reading == null) a.showBoxes = false } }
    var editInfo by remember { mutableStateOf<Int?>(null) }
    var combineFrom by remember { mutableStateOf<Int?>(null) }
    var expanded by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf<Int?>(null) }
    var hide by remember { mutableStateOf(a.hideAfterUse) }
    fun lbl(id: Int) = ctx.getString(id)
    fun read(i: Int, box: Int = 0) {
        val art = a.articles.getOrNull(i) ?: return
        val b = art.beads.getOrNull(box) ?: return
        a.reading = i to box; a.showBoxes = true
        onShowRect(b.page, b.rect)
        if (a.hideAfterUse) onClose?.invoke()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ns_art_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { a.startNew(); if (a.hideAfterUse) onClose?.invoke() }, enabled = !a.busy) {
                Icon(Icons.Default.AddBox, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ns_art_new))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(hide, { hide = it; a.hideAfterUse = it }); Text(stringResource(R.string.ns_art_hide_after_use), style = MaterialTheme.typography.bodySmall)
        }
        a.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (a.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (a.articles.isEmpty()) Text(stringResource(R.string.ns_art_none), Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            a.articles.forEachIndexed { i, art ->
                item(key = "a$i") {
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text(art.info.title.ifBlank { stringResource(R.string.ns_art_untitled, i + 1) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(stringResource(R.string.ns_art_boxes, art.beads.size) + (art.info.author.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { read(i) }, enabled = art.beads.isNotEmpty()) { Icon(Icons.Default.PlayArrow, stringResource(R.string.ns_art_read)) }
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.ns_more)) }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.ns_art_properties)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; editInfo = i })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.ns_art_add_box)) }, leadingIcon = { Icon(Icons.Default.AddBox, null) },
                                            onClick = { menu = false; a.startInsert(i, art.beads.size); if (a.hideAfterUse) onClose?.invoke() })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.ns_art_renumber)) }, leadingIcon = { Icon(Icons.Default.FormatListNumbered, null) },
                                            onClick = { menu = false; val rtl = art.info.title.any { it.code in 0x0600..0x06FF }; scope.launch { a.change(lbl(R.string.ns_art_h_renumber)) { NveArticles.renumber(it, i, rtl) } } })
                                        if (a.articles.size > 1) DropdownMenuItem(text = { Text(stringResource(R.string.ns_art_combine)) }, leadingIcon = { Icon(Icons.Default.CallMerge, null) }, onClick = { menu = false; combineFrom = i })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.ns_art_delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; confirmDelete = i })
                                    }
                                }
                            }
                        },
                        modifier = Modifier.clickable { expanded = if (expanded == i) null else i },
                    )
                }
                if (expanded == i) itemsIndexed(art.beads, key = { k, _ -> "b$i:$k" }) { k, b ->
                    Row(Modifier.fillMaxWidth().padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ns_art_box_n, k + 1, b.page + 1), Modifier.weight(1f).clickable { read(i, k) }, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { scope.launch { a.change(lbl(R.string.ns_art_h_move)) { NveArticles.moveBead(it, i, k, k - 1) } } }, enabled = k > 0 && !a.busy) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.ns_up)) }
                        IconButton(onClick = { scope.launch { a.change(lbl(R.string.ns_art_h_move)) { NveArticles.moveBead(it, i, k, k + 1) } } }, enabled = k < art.beads.size - 1 && !a.busy) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.ns_down)) }
                        IconButton(onClick = { a.startInsert(i, k + 1); if (a.hideAfterUse) onClose?.invoke() }) { Icon(Icons.Default.AddBox, stringResource(R.string.ns_art_insert_after)) }
                        // §22: move / resize this box on the page
                        IconButton(onClick = { a.startAdjust(i, k); onShowRect(b.page, b.rect); if (a.hideAfterUse) onClose?.invoke() }, enabled = !a.busy) {
                            Icon(Icons.Default.OpenWith, stringResource(R.string.ett_art_adjust))
                        }
                        IconButton(onClick = { scope.launch { a.change(lbl(R.string.ns_art_h_del_box)) { NveArticles.deleteBead(it, i, k) } } }, enabled = !a.busy) { Icon(Icons.Default.Delete, stringResource(R.string.ns_art_delete_box)) }
                    }
                }
            }
        }
    }
    editInfo?.let { i ->
        val art = a.articles.getOrNull(i)
        if (art == null) editInfo = null else ArticleInfoDialog(art.info, onDismiss = { editInfo = null }) { info ->
            editInfo = null; scope.launch { a.change(lbl(R.string.ns_art_h_props)) { NveArticles.setInfo(it, i, info) } }
        }
    }
    combineFrom?.let { from ->
        AlertDialog(onDismissRequest = { combineFrom = null }, title = { Text(stringResource(R.string.ns_art_combine_into)) },
            text = {
                Column {
                    a.articles.forEachIndexed { j, other -> if (j != from) TextButton(onClick = {
                        combineFrom = null; scope.launch { a.change(lbl(R.string.ns_art_h_combine)) { NveArticles.combine(it, j, from) } }
                    }) { Text(other.info.title.ifBlank { ctx.getString(R.string.ns_art_untitled, j + 1) }) } }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { combineFrom = null }) { Text(stringResource(R.string.cancel)) } })
    }
    confirmDelete?.let { i ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, text = { Text(stringResource(R.string.ns_art_delete_confirm)) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; scope.launch { a.change(lbl(R.string.ns_art_h_delete)) { NveArticles.delete(it, i) } } }) { Text(stringResource(R.string.ns_art_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ArticleInfoDialog(info: NveArticles.Info, onDismiss: () -> Unit, onOk: (NveArticles.Info) -> Unit) {
    var t by remember { mutableStateOf(info.title) }; var s by remember { mutableStateOf(info.subject) }
    var au by remember { mutableStateOf(info.author) }; var k by remember { mutableStateOf(info.keywords) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.ns_art_properties)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(t, { t = it }, label = { Text(stringResource(R.string.ns_c_title)) }, singleLine = true)
                OutlinedTextField(s, { s = it }, label = { Text(stringResource(R.string.ns_c_subject)) }, singleLine = true)
                OutlinedTextField(au, { au = it }, label = { Text(stringResource(R.string.ns_c_author)) }, singleLine = true)
                OutlinedTextField(k, { k = it }, label = { Text(stringResource(R.string.ns_c_keywords)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onOk(NveArticles.Info(t.trim(), s.trim(), au.trim(), k.trim())) }) { Text(stringResource(R.string.ns_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Geometry of a visible page from its full-page rect in overlay px. */
private fun geometry(state: ReaderState, page: Int, r: Rect): PageGeometry? {
    val (pw, ph) = state.pageSizes[page] ?: return null
    val rot = state.rotation
    val base = if (rot % 180 == 0) pw else ph
    if (base <= 0f || r.width <= 0f) return null
    val fr = state.pageFrames[page]
    return PageGeometry(pw, ph, r.width / base, rot, fr?.rotate ?: 0, fr?.cropX ?: 0f, fr?.cropY ?: 0f)
}

/**
 * Overlay over the page area: draws article boxes (when [NveArticlesState.showBoxes]), lets the user drag new boxes
 * while drawing (Done asks for the article properties), and shows the Read Article bar (previous / next box).
 * [pageRectsPx] = visible pages with their full page box in this overlay's px.
 */
@Composable
fun NveArticleDrawOverlay(state: ReaderState, pageRectsPx: () -> List<Pair<Int, Rect>>, onShowRect: ((Int, FloatArray) -> Unit)? = null) {
    val a = NveArticlesState.of(state)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    if (!a.showBoxes && !a.drawing && a.reading == null && a.adjusting == null) return
    LaunchedEffect(state.renderVersion, state.file) { if (a.loadedVersion != state.renderVersion) a.load() }
    val rectsNow by rememberUpdatedState(pageRectsPx)
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragNow by remember { mutableStateOf<Offset?>(null) }
    val primary = MaterialTheme.colorScheme.primary
    val label = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = 0xFF1565C0.toInt(); isFakeBoldText = true } }
    Box(Modifier.fillMaxSize()) {
        val drawMod = if (a.drawing) Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { p -> dragStart = p; dragNow = p },
                onDragEnd = {
                    val s0 = dragStart; val e0 = dragNow; dragStart = null; dragNow = null
                    if (s0 != null && e0 != null) {
                        val hit = rectsNow().firstOrNull { it.second.contains(s0) }
                        if (hit != null) {
                            val (page, r) = hit
                            val g = geometry(state, page, r)
                            if (g != null) {
                                val box = Rect(minOf(s0.x, e0.x), minOf(s0.y, e0.y), maxOf(s0.x, e0.x), maxOf(s0.y, e0.y)).intersect(r)
                                if (box.width > 12f && box.height > 12f) {
                                    val pdf = g.layoutRectToPdf(box.translate(-r.left, -r.top))
                                    a.pending += NveArticles.Bead(page, floatArrayOf(pdf.left, pdf.bottom, pdf.right, pdf.top))
                                }
                            }
                        }
                    }
                },
                onDragCancel = { dragStart = null; dragNow = null },
            ) { ch, _ -> ch.consume(); dragNow = ch.position }
        } else if (a.adjusting != null) Modifier.pointerInput(Unit) {
            // §22: drag the box being adjusted (inside: move; a corner: resize); edits stay pending until Done
            var handle: com.smartdocs.editor.core.nve.BoxDrag.Handle? = null
            var screen: FloatArray? = null
            var at: Pair<Int, Rect>? = null
            detectDragGestures(
                onDragStart = { p ->
                    handle = null; screen = null; at = null
                    val (i, k) = a.adjusting ?: return@detectDragGestures
                    val page = a.articles.getOrNull(i)?.beads?.getOrNull(k)?.page ?: return@detectDragGestures
                    val rect = a.adjustRect ?: return@detectDragGestures
                    val hit = rectsNow().firstOrNull { it.first == page } ?: return@detectDragGestures
                    val g = geometry(state, page, hit.second) ?: return@detectDragGestures
                    val lr = g.pdfRectToLayout(android.graphics.RectF(rect[0], rect[3], rect[2], rect[1])).translate(hit.second.left, hit.second.top)
                    val box = floatArrayOf(lr.left, lr.top, lr.right, lr.bottom)
                    handle = com.smartdocs.editor.core.nve.BoxDrag.hit(box, p.x, p.y, 28.dp.toPx())
                    if (handle != null) { screen = box; at = hit }
                },
                onDragEnd = { handle = null },
                onDragCancel = { handle = null },
            ) { ch, d ->
                val h = handle ?: return@detectDragGestures
                val box = screen ?: return@detectDragGestures
                val (page, r) = at ?: return@detectDragGestures
                ch.consume()
                val moved = com.smartdocs.editor.core.nve.BoxDrag.apply(box, h, d.x, d.y, 12.dp.toPx())
                screen = moved
                val g = geometry(state, page, r) ?: return@detectDragGestures
                val pdf = g.layoutRectToPdf(Rect(moved[0], moved[1], moved[2], moved[3]).translate(-r.left, -r.top))
                a.adjustRect = floatArrayOf(pdf.left, pdf.bottom, pdf.right, pdf.top)
            }
        } else Modifier
        Canvas(Modifier.fillMaxSize().then(drawMod)) {
            val pages = pageRectsPx()
            fun drawBead(page: Int, rect: FloatArray, color: Color, text: String?) {
                val r = pages.firstOrNull { it.first == page }?.second ?: return
                val g = geometry(state, page, r) ?: return
                val lr = g.pdfRectToLayout(android.graphics.RectF(rect[0], rect[3], rect[2], rect[1])).translate(r.left, r.top)
                drawRect(color.copy(alpha = 0.10f), lr.topLeft, lr.size)
                drawRect(color, lr.topLeft, lr.size, style = Stroke(3f))
                if (text != null) drawContext.canvas.nativeCanvas.drawText(text, lr.left + 6f, lr.top + 32f, label)
            }
            if (a.showBoxes || a.reading != null) a.articles.forEachIndexed { i, art ->
                art.beads.forEachIndexed { k, b ->
                    val cur = a.reading == (i to k)
                    if (a.adjusting != (i to k)) drawBead(b.page, b.rect, if (cur) Color(0xFFE65100) else Color(0xFF1565C0), "${i + 1}-${k + 1}")
                }
            }
            // §22: the box being adjusted, with its four corner handles
            a.adjusting?.let { (i, k) ->
                val b = a.articles.getOrNull(i)?.beads?.getOrNull(k) ?: return@let
                val rect = a.adjustRect ?: b.rect
                drawBead(b.page, rect, Color(0xFFE65100), "${i + 1}-${k + 1}")
                val r = pages.firstOrNull { it.first == b.page }?.second ?: return@let
                val g = geometry(state, b.page, r) ?: return@let
                val lr = g.pdfRectToLayout(android.graphics.RectF(rect[0], rect[3], rect[2], rect[1])).translate(r.left, r.top)
                listOf(lr.topLeft, lr.topRight, lr.bottomLeft, lr.bottomRight).forEach { c ->
                    drawCircle(Color(0xFFE65100), 9.dp.toPx(), c); drawCircle(Color.White, 4.dp.toPx(), c)
                }
            }
            a.pending.forEachIndexed { k, b -> drawBead(b.page, b.rect, Color(0xFF2E7D32), "+${k + 1}") }
            val s0 = dragStart; val e0 = dragNow
            if (s0 != null && e0 != null) {
                val tl = Offset(minOf(s0.x, e0.x), minOf(s0.y, e0.y))
                drawRect(primary, tl, Size(kotlin.math.abs(e0.x - s0.x), kotlin.math.abs(e0.y - s0.y)), style = Stroke(3f))
            }
        }
        if (a.drawing) Surface(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), shape = RoundedCornerShape(50), tonalElevation = 6.dp, shadowElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ns_art_draw_hint, a.pending.size), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp))
                IconButton(onClick = { if (a.pending.isNotEmpty()) a.pending.removeAt(a.pending.size - 1) }, enabled = a.pending.isNotEmpty()) { Icon(Icons.Default.Undo, stringResource(R.string.ns_art_remove_last)) }
                TextButton(onClick = { a.cancelDrawing() }) { Text(stringResource(R.string.cancel)) }
                Button(onClick = {
                    if (a.pending.isEmpty()) { a.cancelDrawing(); return@Button }
                    if (a.target < 0) a.askInfoForNew = true
                    else {
                        val t = a.target; val at = a.insertAt; val beads = a.pending.toList(); a.cancelDrawing()
                        scope.launch { a.change(ctx.getString(R.string.ns_art_h_add_box)) { d -> beads.forEachIndexed { k, b -> NveArticles.insertBead(d, t, at + k, b) } } }
                    }
                }) { Text(stringResource(R.string.ns_done)) }
            }
        }
        if (a.adjusting != null) Surface(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), shape = RoundedCornerShape(50), tonalElevation = 6.dp, shadowElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ett_art_adjust_hint), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp))
                TextButton(onClick = { a.cancelAdjust() }) { Text(stringResource(R.string.cancel)) }
                Button(onClick = {
                    val (i, k) = a.adjusting ?: return@Button
                    val rect = a.adjustRect
                    a.cancelAdjust()
                    if (rect != null) scope.launch { a.change(ctx.getString(R.string.ett_art_h_adjust)) { d -> NveArticles.setBeadRect(d, i, k, rect) } }
                }, enabled = !a.busy) { Text(stringResource(R.string.ns_done)) }
            }
        }
        a.reading?.let { (i, k) ->
            val art = a.articles.getOrNull(i)
            if (art != null) Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp), shape = RoundedCornerShape(50), tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    fun go(n: Int) { art.beads.getOrNull(n)?.let { b -> a.reading = i to n; onShowRect?.invoke(b.page, b.rect) } }
                    IconButton(onClick = { go(k - 1) }, enabled = k > 0) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ns_art_prev)) }
                    Text(stringResource(R.string.ns_art_reading, art.info.title.ifBlank { stringResource(R.string.ns_art_untitled, i + 1) }, k + 1, art.beads.size),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                    IconButton(onClick = { go(k + 1) }, enabled = k < art.beads.size - 1) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.ns_art_next)) }
                    IconButton(onClick = { a.reading = null; a.showBoxes = false }) { Icon(Icons.Default.Close, stringResource(R.string.ns_art_stop)) }
                }
            }
        }
    }
    if (a.askInfoForNew) ArticleInfoDialog(NveArticles.Info(), onDismiss = { a.askInfoForNew = false }) { info ->
        a.askInfoForNew = false
        val beads = a.pending.toList(); a.cancelDrawing()
        scope.launch { a.change(ctx.getString(R.string.ns_art_h_new)) { NveArticles.add(it, info, beads) } }
    }
}
