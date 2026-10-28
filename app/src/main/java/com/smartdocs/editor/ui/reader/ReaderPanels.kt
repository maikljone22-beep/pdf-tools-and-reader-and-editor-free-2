package com.smartdocs.editor.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.ReadingBookmark
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.OutlineItem
import com.smartdocs.editor.ui.common.EmptyState
import kotlinx.coroutines.launch

/**
 * Side panels: outline / bookmarks / annotations / thumbnails / attachments / info. Shown in a sheet on phones, beside the
 * pages on tablets. [onOpenPdf] opens a PDF attachment in the reader (nav-attach).
 */
@Composable
fun PanelsContent(state: ReaderState, panel: ReaderPanel, onPanel: (ReaderPanel) -> Unit, onJump: (Int) -> Unit, onAiOutline: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
                  onOpenPdf: ((java.io.File) -> Unit)? = null) {
    val tabs = listOf(
        ReaderPanel.OUTLINE to Icons.Default.FormatListBulleted, ReaderPanel.BOOKMARKS to Icons.Default.Bookmark,
        ReaderPanel.ANNOTATIONS to Icons.Default.Comment, ReaderPanel.THUMBNAILS to Icons.Default.GridView,
        ReaderPanel.ATTACHMENTS to Icons.Default.AttachFile, ReaderPanel.INFO to Icons.Default.Info,
    )
    // nav-attach: a dot on the attachments tab when the document has any (Foxit "indicator")
    val attachments = com.smartdocs.editor.ui.attachments.rememberReaderAttachments(state)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { (p, icon) ->
                val sel = p == panel
                IconButton(onClick = { onPanel(p) }, colors = IconButtonDefaults.iconButtonColors(contentColor = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                    if (p == ReaderPanel.ATTACHMENTS && !attachments.isNullOrEmpty()) BadgedBox(badge = { Badge() }) { Icon(icon, panelTitle(p)) }
                    else Icon(icon, panelTitle(p))
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
        }
        Text(panelTitle(panel), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            when (panel) {
                ReaderPanel.OUTLINE -> OutlinePanel(state, onJump, onAiOutline)
                ReaderPanel.BOOKMARKS -> BookmarksPanel(state, onJump)
                // threads, review status, type + author filters, sorting, export / import (MODULE_annot2)
                ReaderPanel.ANNOTATIONS -> com.smartdocs.editor.ui.reader.annot.CommentsPanel(state, onJump)
                ReaderPanel.THUMBNAILS -> ThumbnailsPanel(state, onJump)
                ReaderPanel.ATTACHMENTS -> com.smartdocs.editor.ui.attachments.ReaderAttachmentsPanel(state, onJump, onOpenPdf)   // nav-attach
                ReaderPanel.INFO -> NpfInfoPanels(state, onJump)   // nav-prefs: + layers, security, fields, destinations, tags, articles, standards, …
            }
        }
    }
}

@Composable
fun panelTitle(p: ReaderPanel): String = stringResource(
    when (p) {
        ReaderPanel.OUTLINE -> R.string.panel_outline; ReaderPanel.BOOKMARKS -> R.string.panel_bookmarks
        ReaderPanel.ANNOTATIONS -> R.string.panel_annotations; ReaderPanel.THUMBNAILS -> R.string.panel_thumbnails
        ReaderPanel.ATTACHMENTS -> R.string.panel_attachments
        ReaderPanel.INFO -> R.string.tool_doc_info
    }
)

@Composable
private fun OutlinePanel(state: ReaderState, onJump: (Int) -> Unit, onAiOutline: () -> Unit) {
    val outline by produceState<List<OutlineItem>?>(null, state.session, state.renderVersion) { value = runCatching { state.session?.outline() }.getOrNull() ?: emptyList() }
    val flat = remember(outline) { outline?.let { flatten(it) } ?: emptyList() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // nav-bookmarks: search, find current bookmark, expand / collapse all, wrap and text size
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    var wrap by remember { mutableStateOf(com.smartdocs.editor.ui.edit5.OutlinePrefs.wrap(ctx)) }
    var size by remember { mutableIntStateOf(com.smartdocs.editor.ui.edit5.OutlinePrefs.size(ctx)) }
    var current by remember { mutableStateOf<String?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    val matches = remember(flat, query) {
        if (query.isBlank()) null else com.smartdocs.editor.core.edit5.OutlineNav.search(flat.map { it.first.title }, flat.map { it.first.level }, query)
    }
    Column(Modifier.fillMaxWidth()) {
        if (outline == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (flat.isEmpty()) EmptyState(Icons.Default.FormatListBulleted, stringResource(R.string.no_outline)) {
            if (com.smartdocs.editor.LocalSettings.current.aiEnabled) Button(onClick = onAiOutline) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ai_bookmarks)) }
        }
        else {
            val visible = flat.withIndex().filter { (i, e) -> if (matches != null) i in matches else e.second.dropLast(1).let { p -> p.indices.all { k -> expanded[p.subList(0, k + 1).joinToString("/")] != false } } }
            com.smartdocs.editor.ui.edit5.OutlineToolbar(query, { query = it },
                onFindCurrent = {
                    val i = com.smartdocs.editor.core.edit5.OutlineNav.current(flat.map { it.first.page }, state.currentPage)
                    if (i >= 0) {
                        query = ""
                        val path = flat[i].second
                        for (k in 1 until path.size) expanded[path.subList(0, k).joinToString("/")] = true
                        current = path.joinToString("/")
                        val pos = flat.withIndex().filter { (_, e) -> e.second.dropLast(1).let { p -> p.indices.all { k -> expanded[p.subList(0, k + 1).joinToString("/")] != false } } }.indexOfFirst { it.index == i }
                        if (pos >= 0) scope.launch { listState.animateScrollToItem(pos) }
                    }
                },
                onExpandAll = { flat.forEach { (o, p) -> if (o.children.isNotEmpty()) expanded[p.joinToString("/")] = true } },
                onCollapseAll = { flat.forEach { (o, p) -> if (o.children.isNotEmpty()) expanded[p.joinToString("/")] = false } },
                wrap = wrap, onWrap = { wrap = it; com.smartdocs.editor.ui.edit5.OutlinePrefs.setWrap(ctx, it) },
                size = size, onSize = { size = it.coerceIn(-2, 4); com.smartdocs.editor.ui.edit5.OutlinePrefs.setSize(ctx, size) })
            if (matches != null && visible.isEmpty()) Text(stringResource(R.string.nbm_no_match), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val fs = (MaterialTheme.typography.bodyLarge.fontSize.value + size * 2).sp
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), state = listState) {
                items(visible, key = { it.value.second.joinToString("/") }) { (idx, e) ->
                    val (item, path) = e
                    val key = path.joinToString("/")
                    val hasKids = item.children.isNotEmpty()
                    ListItem(
                        headlineContent = { Text(item.title, maxLines = if (wrap) 6 else 1, overflow = TextOverflow.Ellipsis, fontSize = fs) },
                        leadingContent = {
                            if (hasKids && matches == null) IconButton(onClick = { expanded[key] = !(expanded[key] ?: true) }, Modifier.size(28.dp)) {
                                Icon(if (expanded[key] != false) Icons.Default.ExpandMore else Icons.Default.ChevronRight, stringResource(if (expanded[key] != false) R.string.pc_cd_collapse else R.string.pc_cd_expand))
                            } else Spacer(Modifier.size(28.dp))
                        },
                        trailingContent = {
                            if (item.page >= 0) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text((item.page + 1).toString(), style = MaterialTheme.typography.labelMedium)
                                OutlinePrintMenu(state, flat, idx)   // nav-batch: print this page / section
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = if (key == current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                        modifier = Modifier.padding(start = (item.level * 16).dp).clickable {
                            current = key
                            if (item.page >= 0) onJump(item.page) else com.smartdocs.editor.ui.edit5.runOutlineActions(state, idx, onJump)
                        },
                    )
                }
            }
        }
    }
}

/** nav-batch: Foxit "Print Page(s)" / "Print Section(s)" from a bookmark (the section ends before the next one of its level). */
@Composable
private fun OutlinePrintMenu(state: ReaderState, flat: List<Pair<OutlineItem, List<String>>>, index: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    fun print(range: IntRange) {
        val f = state.file ?: return
        scope.launch {
            runCatching {
                val (out, n) = com.smartdocs.editor.core.view5.PrintPipeline.prepare(ctx, f, state.password,
                    com.smartdocs.editor.core.view5.PrintOptions(range = "${range.first + 1}-${range.last + 1}"), com.smartdocs.editor.core.view5.PrintExtras(), state.app.documents.tempDir)
                com.smartdocs.editor.core.util.PdfPrint.print(ctx, out, f.nameWithoutExtension, n)
            }
        }
    }
    Box {
        IconButton(onClick = { open = true }, Modifier.size(32.dp)) { Icon(Icons.Default.Print, stringResource(R.string.print), Modifier.size(18.dp)) }
        DropdownMenu(open, { open = false }) {
            val item = flat.getOrNull(index)?.first
            DropdownMenuItem(text = { Text(stringResource(R.string.nbt_print_bm_page)) }, onClick = { open = false; item?.let { print(it.page..it.page) } })
            DropdownMenuItem(text = { Text(stringResource(R.string.nbt_print_bm_section)) }, onClick = {
                open = false
                if (index >= 0) print(com.smartdocs.editor.core.view5.PrintLayout.section(flat.map { it.first.level }, flat.map { it.first.page }, index, state.pageCount))
            })
        }
    }
}

private fun flatten(items: List<OutlineItem>, prefix: List<String> = emptyList()): List<Pair<OutlineItem, List<String>>> {
    val out = ArrayList<Pair<OutlineItem, List<String>>>()
    items.forEachIndexed { i, it -> val p = prefix + i.toString(); out += it to p; out += flatten(it.children, p) }
    return out
}

@Composable
private fun BookmarksPanel(state: ReaderState, onJump: (Int) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val uri = state.fileUri
    val marks by app.db.bookmarks().forDoc(uri).collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("") }
    val defaultTitle = stringResource(R.string.page_of, state.currentPage + 1, state.pageCount)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(stringResource(R.string.rd_bookmark_hint, state.currentPage + 1)) })
            FilledTonalButton(onClick = {
                val t = title.ifBlank { defaultTitle }
                scope.launch { app.db.bookmarks().insert(ReadingBookmark(uri = uri, page = state.currentPage, title = t)); title = "" }
            }) { Icon(Icons.Default.BookmarkAdd, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.add_bookmark)) }
        }
        // nav-bookmarks: search box
        var q by remember { mutableStateOf("") }
        if (marks.size > 3) OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(stringResource(R.string.nbm_search_bookmarks)) })
        val shown = if (q.isBlank()) marks else marks.filter { com.smartdocs.editor.core.edit5.OutlineNav.fold(it.title).contains(com.smartdocs.editor.core.edit5.OutlineNav.fold(q)) }
        if (marks.isEmpty()) EmptyState(Icons.Default.BookmarkBorder, stringResource(R.string.no_bookmarks))
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(shown, key = { it.id }) { b ->
                ListItem(
                    headlineContent = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(stringResource(R.string.page_of, b.page + 1, state.pageCount)) },
                    leadingContent = { Icon(Icons.Default.Bookmark, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { IconButton(onClick = { scope.launch { app.db.bookmarks().delete(b) } }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } },
                    modifier = Modifier.clickable { onJump(b.page) },
                )
            }
        }
    }
}

/** Previous simple list (kept for reference; the panel now uses annot.CommentsPanel). */
@Suppress("unused")
@Composable
private fun AnnotationsPanel(state: ReaderState, onJump: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val pending = state.annots
    val existing = state.existing
    if (pending.isEmpty() && existing.isEmpty()) { EmptyState(Icons.Default.Comment, stringResource(R.string.no_annotations)); return }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        if (pending.isNotEmpty()) item { Text(stringResource(R.string.rd_pending_annots), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 8.dp)) }
        items(pending, key = { "p" + it.id }) { a ->
            ListItem(
                headlineContent = { Text(kindLabel(a.kind)) },
                supportingContent = { Text(listOf(stringResource(R.string.page_of, a.page + 1, state.pageCount), a.text.take(80)).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(annotColor(a.color))) },
                trailingContent = { IconButton(onClick = { state.removeAnnot(a.id) }) { Icon(Icons.Default.Delete, stringResource(R.string.annot_delete)) } },
                modifier = Modifier.clickable { onJump(a.page) },
            )
        }
        if (existing.isNotEmpty()) item { Text(stringResource(R.string.rd_saved_annots), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 8.dp)) }
        items(existing, key = { "e${it.page}_${it.index}" }) { e ->
            ListItem(
                headlineContent = { Text(subtypeLabel(e.subtype)) },
                supportingContent = { Text(listOf(stringResource(R.string.page_of, e.page + 1, state.pageCount), e.author, e.contents.take(80)).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(Icons.Default.Comment, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent = { IconButton(onClick = { scope.launch { state.deleteExisting(e) } }, enabled = !state.saving) { Icon(Icons.Default.Delete, stringResource(R.string.annot_delete)) } },
                modifier = Modifier.clickable { onJump(e.page) },
            )
        }
    }
}

@Composable
fun kindLabel(k: AnnotKind): String = stringResource(
    when (k) {
        AnnotKind.HIGHLIGHT -> R.string.annot_highlight; AnnotKind.UNDERLINE -> R.string.annot_underline; AnnotKind.STRIKEOUT -> R.string.annot_strikeout
        AnnotKind.SQUIGGLY -> R.string.annot_squiggly; AnnotKind.INK -> R.string.annot_ink; AnnotKind.NOTE -> R.string.annot_note
        AnnotKind.FREETEXT -> R.string.annot_text; AnnotKind.LINE -> R.string.annot_line; AnnotKind.ARROW -> R.string.annot_arrow
        AnnotKind.RECT -> R.string.annot_rect; AnnotKind.OVAL -> R.string.annot_oval; AnnotKind.STAMP_IMAGE -> R.string.annot_image
        AnnotKind.STAMP_TEXT -> R.string.annot_stamp; AnnotKind.REDACT -> R.string.annot_redact
        AnnotKind.POLYGON -> R.string.annot_polygon; AnnotKind.POLYLINE -> R.string.an_polyline; AnnotKind.CLOUD -> R.string.annot_cloud
        AnnotKind.CARET -> R.string.an_caret; AnnotKind.REPLACE -> R.string.an_replace; AnnotKind.FILE_ATTACH -> R.string.annot_attachment
        AnnotKind.SOUND -> R.string.annot_audio; AnnotKind.VIDEO -> R.string.an_video
    }
)

@Composable
private fun subtypeLabel(sub: String): String = when (sub) {
    "Highlight" -> stringResource(R.string.annot_highlight); "Underline" -> stringResource(R.string.annot_underline)
    "StrikeOut" -> stringResource(R.string.annot_strikeout); "Squiggly" -> stringResource(R.string.annot_squiggly)
    "Ink" -> stringResource(R.string.annot_ink); "Text" -> stringResource(R.string.annot_note); "FreeText" -> stringResource(R.string.annot_text)
    "Line" -> stringResource(R.string.annot_line); "Square" -> stringResource(R.string.annot_rect); "Circle" -> stringResource(R.string.annot_oval)
    "Stamp" -> stringResource(R.string.annot_stamp); else -> sub
}

@Composable
private fun ThumbnailsPanel(state: ReaderState, onJump: (Int) -> Unit) {
    val session = state.session ?: return
    // audit 5 G-15: opens at the current page, shows page labels, and keeps rendered thumbnails while scrolling
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState(initialFirstVisibleItemIndex = state.currentPage.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)))
    val labels = com.smartdocs.editor.ui.edit5.rememberPageLabels(state)
    val cache = remember(session, state.renderVersion, state.nightMode) {
        object : android.util.LruCache<Int, Bitmap>(12 shl 20) { override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount }
    }
    LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxWidth().heightIn(max = 560.dp), state = grid, contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items((0 until state.pageCount).toList(), key = { it }) { page ->
            // keyed on the session too: after a save the old session is closed and a fresh one replaces it
            val bmp by produceState<Bitmap?>(cache.get(page), page, session, state.renderVersion, state.nightMode) {
                if (value != null) return@produceState
                if (session.isClosed || page >= session.pageCount) return@produceState
                val (w, h) = state.pageSize(page)
                value = runCatching { session.render(page, 160, (160 * h / w).toInt().coerceAtLeast(1), state.nightMode, true) }.getOrNull()
                    ?.also { cache.put(page, it) }
            }
            val current = page == state.currentPage
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onJump(page) }) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(6.dp)).background(Color.White)
                        .border(if (current) 2.dp else 1.dp, if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                val label = labels?.getOrNull(page)?.takeIf { it.isNotBlank() && it != (page + 1).toString() }
                Text(if (label != null) "$label (${page + 1})" else (page + 1).toString(), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
