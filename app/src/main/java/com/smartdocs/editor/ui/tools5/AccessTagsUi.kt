package com.smartdocs.editor.ui.tools5

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tools5.AccessTags
import com.smartdocs.editor.core.tools5.TagAttrs
import com.smartdocs.editor.core.tools5.TagNode
import com.smartdocs.editor.core.tools5.Unmarked
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foxit 34 tags panel: the structure tree (expand / collapse), properties (type, Title, Alt, ActualText, Lang,
 * expansion, ID, table and list attributes), new child / sibling, delete, delete empty tags, move, indent / outdent,
 * role map, "document is tagged" flag, clear one page's structure, find and tag unmarked annotations. Edits go to a
 * working copy; [onSaved] gets the saved file.
 */
@Composable
/** audit 5 A-01: [password] opens [source] (PdfOps.load keeps its protection on save). */
fun TagsEditor(nav: NavHostController, source: File, onClose: () -> Unit, onSaved: (File) -> Unit, password: String? = null) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val job = rememberT5Job()
    val snackbar = remember { SnackbarHostState() }
    val work = remember(source) { File(app.documents.tempDir, "tags_${System.nanoTime()}.pdf") }
    var ready by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var nodes by remember { mutableStateOf<List<TagNode>>(emptyList()) }
    var roles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var tagged by remember { mutableStateOf(false) }
    var unmarked by remember { mutableStateOf<List<Unmarked>>(emptyList()) }
    var collapsed by remember { mutableStateOf(setOf<List<Int>>()) }
    var selected by remember { mutableStateOf<TagNode?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var clearPage by remember { mutableStateOf("") }
    var roleFrom by remember { mutableStateOf("") }
    var roleTo by remember { mutableStateOf("P") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(source) { io { source.copyTo(work, true) }; ready = true; version++ }
    LaunchedEffect(version) {
        if (!ready) return@LaunchedEffect
        runCatching { io { com.smartdocs.editor.core.pdf.PdfOps.load(work, password).use { d -> listOf(AccessTags.tree(d), AccessTags.roleMap(d), AccessTags.isTaggedFlag(d), AccessTags.unmarked(d)) } } }
            .onSuccess { l -> @Suppress("UNCHECKED_CAST") run { nodes = l[0] as List<TagNode>; roles = l[1] as Map<String, String>; tagged = l[2] as Boolean; unmarked = l[3] as List<Unmarked> } }
            .onFailure { error = it.message }
    }
    fun edit(block: (PDDocument) -> Unit) {
        scope.launch {
            runCatching { io { com.smartdocs.editor.core.pdf.PdfOps.load(work, password).use { d -> block(d); val tmp = File(work.parentFile, work.name + ".new"); d.save(tmp); tmp.copyTo(work, true); tmp.delete() } } }
                .onSuccess { dirty = true; version++ }.onFailure { error = it.message ?: it.toString() }
        }
    }
    val saving = stringResource(R.string.processing)

    ToolScaffold(stringResource(R.string.pc_tags_title), nav, snackbar = snackbar, actions = {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.pc_pf_close)) }
    }) {
        Text(source.name, style = MaterialTheme.typography.titleMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        var findQ by remember { mutableStateOf("") }
        var findType by remember { mutableStateOf<String?>(null) }
        var bmDialog by remember { mutableStateOf(false) }
        SwitchRow(stringResource(R.string.pc_tags_flag), tagged) { v -> edit { AccessTags.setTaggedFlag(it, v) } }
        if (nodes.isEmpty()) Text(stringResource(R.string.pc_tags_none), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { edit { AccessTags.newChild(it, emptyList(), "Sect") } }, label = { Text(stringResource(R.string.pc_tags_new_root)) })
            AssistChip(onClick = { edit { AccessTags.deleteEmpty(it) } }, label = { Text(stringResource(R.string.pc_tags_delete_empty)) })
            AssistChip(onClick = { collapsed = nodes.filter { it.children > 0 }.map { it.path }.toSet() }, label = { Text(stringResource(R.string.pc_tags_collapse_all)) })
            AssistChip(onClick = { collapsed = emptySet() }, label = { Text(stringResource(R.string.pc_tags_expand_all)) })
            AssistChip(onClick = { bmDialog = true }, label = { Text(stringResource(R.string.cmp_bm_struct)) })     // 34.18
        }
        // ---- 34.49 Find Element: by text and / or type; the tree then shows the matches only
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(findQ, { findQ = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.cmp_find_element)) },
                placeholder = { Text(stringResource(R.string.cmp_find_hint)) })
            if (findQ.isNotEmpty() || findType != null) IconButton(onClick = { findQ = ""; findType = null }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            com.smartdocs.editor.core.tools5.AccessFind.TYPES.forEach { t -> FilterChip(findType == t, { findType = if (findType == t) null else t }, label = { Text(t) }) }
        }
        val matches = if (findQ.isBlank() && findType == null) null
            else com.smartdocs.editor.core.tools5.AccessFind.find(nodes, setOfNotNull(findType), findQ).map { it.path }.toSet()
        matches?.let { Text(stringResource(R.string.cmp_find_count, it.size), style = MaterialTheme.typography.labelMedium) }
        if (bmDialog) {
            var types by remember { mutableStateOf(setOf("H1", "H2", "H3")) }
            val parent = stringResource(R.string.cmp_bm_parent)
            val untitledFmt = stringResource(R.string.cmp_bm_untitled)
            AlertDialog(onDismissRequest = { bmDialog = false }, title = { Text(stringResource(R.string.cmp_bm_struct)) },
                text = { Column {
                    Text(stringResource(R.string.cmp_bm_types), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        com.smartdocs.editor.core.tools5.AccessFind.TYPES.forEach { t -> FilterChip(t in types, { types = if (t in types) types - t else types + t }, label = { Text(t) }) }
                    }
                } },
                confirmButton = { TextButton(enabled = types.isNotEmpty(), onClick = {
                    bmDialog = false
                    edit { d -> com.smartdocs.editor.core.tools5.AccessFind.addBookmarks(d, types, parent) { ty -> untitledFmt.format(ty) } }
                }) { Text(stringResource(R.string.ok)) } },
                dismissButton = { TextButton(onClick = { bmDialog = false }) { Text(stringResource(R.string.cancel)) } })
        }
        // ---- Order panel (Foxit 34.44): reading order of one page, drag or move up / down
        var mode by remember { mutableIntStateOf(0) }   // 0 tree, 1 order, 2 content (FINAL_CHECK_2 #20), 3 order on the page (#19)
        val orderMode = mode == 1
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(mode == 0, { mode = 0 }, label = { Text(stringResource(R.string.pa_tags_tree)) })
            FilterChip(mode == 1, { mode = 1 }, label = { Text(stringResource(R.string.pa_tags_order)) })
            FilterChip(mode == 3, { mode = 3 }, label = { Text(stringResource(R.string.pa_tags_order_page)) })
            FilterChip(mode == 2, { mode = 2 }, label = { Text(stringResource(R.string.pa_tags_content)) })
        }
        if (orderMode) OrderPanel(nodes, ::edit)
        if (mode == 2) ContentPanel(work, password, version, ::edit)
        if (mode == 3) OrderOnPage(work, password, version, ::edit)
        // ---- tree
        if (mode == 0) T5Card {
            nodes.forEach { n ->
                if (matches != null) { if (n.path !in matches) return@forEach }
                else if (collapsed.any { c -> n.path.size > c.size && n.path.subList(0, c.size) == c }) return@forEach
                val sel = selected?.path == n.path
                Row(Modifier.fillMaxWidth().clickable { selected = n }.padding(start = (14 * n.depth).dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (n.children > 0) Icon(if (n.path in collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, stringResource(R.string.pc_tags_toggle),
                        Modifier.size(20.dp).clickable { collapsed = if (n.path in collapsed) collapsed - setOf(n.path) else collapsed + setOf(n.path) })
                    else Spacer(Modifier.width(20.dp))
                    Text("<${n.type}>" + (n.mapped?.let { " → $it" } ?: ""), style = MaterialTheme.typography.labelLarge,
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(6.dp))
                    Text(listOfNotNull(n.title, n.alt?.let { "Alt: $it" }, n.actualText?.let { "“$it”" }, if (n.page >= 0) stringResource(R.string.pc_tags_page, n.page + 1) else null,
                        if (n.contentItems > 0) stringResource(R.string.pc_tags_content, n.contentItems) else null).joinToString(" · "),
                        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // ---- selected tag
        selected?.let { s -> nodes.firstOrNull { it.path == s.path }?.let { n -> TagProps(n, work, version, ::edit, password) { selected = it } } }
        // ---- role map
        T5Card(stringResource(R.string.pc_tags_rolemap), Icons.Default.SwapHoriz) {
            roles.forEach { (k, v) -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$k → $v", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { edit { AccessTags.setRole(it, k, null) } }) { Icon(Icons.Default.Delete, stringResource(R.string.t5_remove)) }
            } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(roleFrom, { roleFrom = it.filter { c -> !c.isWhitespace() } }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.pc_tags_custom)) })
                OutlinedTextField(roleTo, { roleTo = it.filter { c -> !c.isWhitespace() } }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.pc_tags_standard)) })
                TextButton(enabled = roleFrom.isNotBlank() && roleTo in AccessTags.TYPES, onClick = { val a = roleFrom; val b = roleTo; roleFrom = ""; edit { AccessTags.setRole(it, a, b) } }) { Text(stringResource(R.string.pc_add)) }
            }
        }
        // ---- find unmarked, clear page structure
        T5Card(stringResource(R.string.pc_tags_find), Icons.Default.Search) {
            Text(if (unmarked.isEmpty()) stringResource(R.string.pc_tags_unmarked_none) else stringResource(R.string.pc_tags_unmarked, unmarked.size,
                unmarked.groupBy { it.subtype }.entries.joinToString { "${it.key} ×${it.value.size}" }), style = MaterialTheme.typography.bodySmall)
            if (unmarked.isNotEmpty() && nodes.isNotEmpty()) OutlinedButton(onClick = { edit { AccessTags.tagAnnotations(it) } }) { Text(stringResource(R.string.pc_tags_tag_annots)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(clearPage, { clearPage = it.filter { c -> c.isDigit() }.take(5) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.pc_tags_clear_page)) })
                TextButton(enabled = clearPage.isNotBlank(), onClick = { val p = clearPage.toInt() - 1; edit { d -> if (p in 0 until d.numberOfPages) AccessTags.clearPage(d, p) } }) { Text(stringResource(R.string.pc_tags_clear)) }
            }
        }
        PrimaryButton(stringResource(R.string.pc_pf_save), enabled = dirty && !job.running, icon = Icons.Default.Save) {
            job.launch(saving) {
                val out = app.documents.uniqueFile(app.documents.docsDir, source.nameWithoutExtension + "_tags")
                io { work.copyTo(out, true) }
                result = out; dirty = false; onSaved(out)
            }
        }
    }
    T5JobUi(job, nav, snackbar)
}

/**
 * FINAL_CHECK_2 #20 Content panel: the marked-content items of one page (tag, MCID, text / picture, owning element);
 * "mark page content" gives unmarked runs MCIDs; "create tag from selection" makes one element of the chosen items.
 */
@Composable
private fun ContentPanel(work: File, password: String?, version: Int, edit: ((PDDocument) -> Unit) -> Unit) {
    var pageText by remember { mutableStateOf("1") }
    var items by remember { mutableStateOf<List<com.smartdocs.editor.core.tools5.AccessContent.Item>>(emptyList()) }
    var pageCount by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    var type by remember { mutableStateOf("P") }
    val page = (pageText.toIntOrNull() ?: 1) - 1
    LaunchedEffect(version, page) {
        picked = emptySet()
        runCatching { io { com.smartdocs.editor.core.pdf.PdfOps.load(work, password).use { d -> pageCount = d.numberOfPages; if (page in 0 until d.numberOfPages) com.smartdocs.editor.core.tools5.AccessContent.items(d, page) else emptyList() } } }
            .onSuccess { items = it }.onFailure { items = emptyList() }
    }
    T5Card(stringResource(R.string.pa_tags_content), Icons.Default.Article) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(pageText, { pageText = it.filter { c -> c.isDigit() }.take(5) }, Modifier.width(110.dp), singleLine = true, label = { Text(stringResource(R.string.pc_tags_page_n, pageCount)) })
            OutlinedButton(onClick = { edit { d -> if (page in 0 until d.numberOfPages) com.smartdocs.editor.core.tools5.AccessContent.markPage(d, page) } }) { Text(stringResource(R.string.pa_content_mark)) }
        }
        Text(stringResource(R.string.pa_content_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (items.isEmpty()) Text(stringResource(R.string.pa_content_empty), style = MaterialTheme.typography.bodySmall)
        items.forEachIndexed { i, it ->
            Row(Modifier.fillMaxWidth().clickable(enabled = it.mcid >= 0) { picked = if (i in picked) picked - i else picked + i }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(i in picked, { v -> picked = if (v) picked + i else picked - i }, enabled = it.mcid >= 0)
                Icon(when (it.kind) { "image" -> Icons.Default.Image; "text" -> Icons.Default.TextFields; else -> Icons.Default.Widgets }, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text((if (it.mcid >= 0) "MCID ${it.mcid} · " else "") + (it.elemType?.let { t -> "<$t>" } ?: if (it.artifact) stringResource(R.string.pa_content_artifact) else stringResource(R.string.pa_content_untagged)),
                        style = MaterialTheme.typography.labelMedium, color = if (it.tagged || it.artifact) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    if (it.text.isNotEmpty()) Text(it.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (picked.isNotEmpty()) {
            Text(stringResource(R.string.pa_content_new_tag, picked.size), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("P", "H1", "H2", "H3", "Figure", "Caption", "L", "LI", "Table", "Span", "Artifact").forEach { t -> FilterChip(type == t, { type = t }, label = { Text(t) }) }
            }
            Button(onClick = {
                val mcids = picked.mapNotNull { items.getOrNull(it)?.mcid }.filter { it >= 0 }
                if (mcids.isNotEmpty()) edit { d -> com.smartdocs.editor.core.tools5.AccessContent.createTag(d, page, mcids, type) }
            }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.pa_content_create)) }
        }
    }
}

/**
 * FINAL_CHECK_2 #19 — reading order drawn on the page and the on-page touch-up tool: the page picture with a numbered
 * box per element (reading order); tap a box to select it, then move it up / down in the order, change its type,
 * or make it an artifact (its content leaves the structure).
 */
@Composable
private fun OrderOnPage(work: File, password: String?, version: Int, edit: ((PDDocument) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var pageText by remember { mutableStateOf("1") }
    var pageCount by remember { mutableIntStateOf(0) }
    var boxes by remember { mutableStateOf<List<com.smartdocs.editor.core.tools5.AccessContent.ElemBox>>(emptyList()) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var selected by remember { mutableStateOf<List<Int>?>(null) }
    val page = (pageText.toIntOrNull() ?: 1) - 1
    LaunchedEffect(version, page) {
        selected = null
        runCatching {
            io { com.smartdocs.editor.core.pdf.PdfOps.load(work, password).use { d -> pageCount = d.numberOfPages; boxes = if (page in 0 until d.numberOfPages) com.smartdocs.editor.core.tools5.AccessContent.elementBoxes(d, page) else emptyList() } }
            val s = com.smartdocs.editor.core.pdf.PdfSession.open(ctx, work, password)
            try {
                if (page in 0 until s.pageCount) {
                    val (w, h) = s.pageSize(page)
                    val tw = 900; val th = (tw * h / w.coerceAtLeast(1f)).toInt().coerceIn(1, 2400)
                    val old = bitmap; bitmap = s.renderOwned(page, tw, th, annotations = true); old?.recycle()
                }
            } finally { s.closeAsync() }
        }.onFailure { boxes = emptyList() }
    }
    DisposableEffect(Unit) { onDispose { bitmap?.recycle() } }
    val sel = boxes.indexOfFirst { it.path == selected }
    T5Card(stringResource(R.string.pa_tags_order_page), Icons.Default.Map) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(pageText, { pageText = it.filter { c -> c.isDigit() }.take(5) }, Modifier.width(110.dp), singleLine = true, label = { Text(stringResource(R.string.pc_tags_page_n, pageCount)) })
            Text(stringResource(R.string.pa_order_page_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            val aspect = bmp.width.toFloat() / bmp.height
            var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            Box(Modifier.fillMaxWidth().aspectRatio(aspect).onSizeChanged { size = it }.pointerInput(boxes) {
                detectTapGestures { o ->
                    val fx = o.x / size.width; val fy = o.y / size.height
                    // the smallest box under the tap wins (a line inside a section)
                    val hit = boxes.filter { fx in it.left..it.right && fy in it.top..it.bottom }.minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
                    selected = hit?.path
                }
            }) {
                androidx.compose.foundation.Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                val primary = MaterialTheme.colorScheme.primary; val accent = MaterialTheme.colorScheme.tertiary
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    boxes.forEachIndexed { i, b ->
                        val on = b.path == selected
                        val l = b.left * this.size.width; val t = b.top * this.size.height; val w = (b.right - b.left) * this.size.width; val h = (b.bottom - b.top) * this.size.height
                        val col = if (on) accent else primary
                        drawRect(col.copy(alpha = if (on) 0.25f else 0.08f), androidx.compose.ui.geometry.Offset(l, t), androidx.compose.ui.geometry.Size(w, h))
                        drawRect(col, androidx.compose.ui.geometry.Offset(l, t), androidx.compose.ui.geometry.Size(w, h), style = androidx.compose.ui.graphics.drawscope.Stroke(if (on) 4f else 2f))
                        val label = measurer.measure("${i + 1}", androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = androidx.compose.ui.graphics.Color.White))
                        drawRect(col, androidx.compose.ui.geometry.Offset(l, t), androidx.compose.ui.geometry.Size(label.size.width + 8f, label.size.height + 2f))
                        drawText(label, topLeft = androidx.compose.ui.geometry.Offset(l + 4f, t + 1f))
                    }
                }
            }
        }
        if (boxes.isEmpty()) Text(stringResource(R.string.pa_order_none), style = MaterialTheme.typography.bodySmall)
        if (sel >= 0) {
            val b = boxes[sel]
            Text("${sel + 1}. <${b.type}>", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AssistChip(onClick = { edit { AccessTags.move(it, b.path, -1) } }, label = { Text(stringResource(R.string.pc_move_up)) }, leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) })
                AssistChip(onClick = { edit { AccessTags.move(it, b.path, 1) } }, label = { Text(stringResource(R.string.pc_move_down)) }, leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) })
                AssistChip(onClick = { edit { AccessTags.setType(it, b.path, "Artifact") } }, label = { Text(stringResource(R.string.pc_tags_artifact)) })
                AssistChip(onClick = { edit { AccessTags.delete(it, b.path) } }, label = { Text(stringResource(R.string.pc_tags_delete)) })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("P", "H1", "H2", "H3", "Figure", "Caption", "L", "LI", "Table", "TH", "TD", "Span").forEach { t ->
                    FilterChip(b.type == t, { if (b.type != t) edit { AccessTags.setType(it, b.path, t) } }, label = { Text(t) })
                }
            }
        }
    }
}

/** Properties and actions of one tag. */
@Composable
private fun TagProps(n: TagNode, work: File, version: Int, edit: ((PDDocument) -> Unit) -> Unit, password: String?, onSelect: (TagNode?) -> Unit) {
    var type by remember(n.path, version) { mutableStateOf(n.type) }
    var title by remember(n.path, version) { mutableStateOf(n.title.orEmpty()) }
    var alt by remember(n.path, version) { mutableStateOf(n.alt.orEmpty()) }
    var actual by remember(n.path, version) { mutableStateOf(n.actualText.orEmpty()) }
    var lang by remember(n.path, version) { mutableStateOf(n.lang.orEmpty()) }
    var exp by remember(n.path, version) { mutableStateOf(n.expansion.orEmpty()) }
    var id by remember(n.path, version) { mutableStateOf(n.id.orEmpty()) }
    var attrs by remember(n.path, version) { mutableStateOf(TagAttrs()) }
    LaunchedEffect(n.path, version) { attrs = runCatching { io { com.smartdocs.editor.core.pdf.PdfOps.load(work, password).use { AccessTags.attrs(it, n.path) } } }.getOrDefault(TagAttrs()) }
    val table = type in setOf("TH", "TD", "Table", "TR")
    T5Card(stringResource(R.string.pc_tags_props, n.type), Icons.Default.Label) {
        Text(stringResource(R.string.pc_tags_type), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AccessTags.TYPES.forEach { t -> FilterChip(type == t, { type = t }, label = { Text(t) }) }
        }
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.pc_tags_title_attr)) })
        OutlinedTextField(alt, { alt = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.t5_acc_alt)) })
        OutlinedTextField(actual, { actual = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pc_tags_actual)) })
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(lang, { lang = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.t5_acc_doc_lang)) })
            OutlinedTextField(id, { id = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text("ID") })
        }
        OutlinedTextField(exp, { exp = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.pc_tags_expansion)) })
        if (table) {
            Text(stringResource(R.string.pc_tags_table), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("", "Row", "Column", "Both"), attrs.scope ?: "", { if (it.isEmpty()) "—" else it }) { attrs = attrs.copy(scope = it.ifEmpty { null }) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(attrs.rowSpan?.toString() ?: "", { attrs = attrs.copy(rowSpan = it.toIntOrNull()) }, Modifier.weight(1f), singleLine = true, label = { Text("RowSpan") })
                OutlinedTextField(attrs.colSpan?.toString() ?: "", { attrs = attrs.copy(colSpan = it.toIntOrNull()) }, Modifier.weight(1f), singleLine = true, label = { Text("ColSpan") })
            }
            OutlinedTextField(attrs.headers.orEmpty(), { attrs = attrs.copy(headers = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.pc_tags_headers)) })
            if (type == "Table") OutlinedTextField(attrs.summary.orEmpty(), { attrs = attrs.copy(summary = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pc_tags_summary)) })
        }
        if (type == "L") ChoiceChips(listOf("", "Disc", "Circle", "Square", "Decimal", "UpperRoman", "LowerRoman", "UpperAlpha", "LowerAlpha"), attrs.listNumbering ?: "", { if (it.isEmpty()) "—" else it }) { attrs = attrs.copy(listNumbering = it) }
        Button(onClick = {
            val p = n.path; val t = type; val a = attrs; val tt = title; val al = alt; val ac = actual; val lg = lang; val ex = exp; val i = id
            edit { d ->
                if (t != n.type) AccessTags.setType(d, p, t)
                AccessTags.setProps(d, p, tt, al, ac, lg, ex, i)
                if (table || t == "L") AccessTags.setAttrs(d, p, if (t == "L") TagAttrs(listNumbering = a.listNumbering ?: "") else a)
            }
        }) { Text(stringResource(R.string.t5_apply)) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AssistChip(onClick = { edit { AccessTags.newChild(it, n.path, "P") } }, label = { Text(stringResource(R.string.pc_tags_new_child)) })
            AssistChip(onClick = { edit { AccessTags.newSibling(it, n.path, "P") } }, label = { Text(stringResource(R.string.pc_tags_new_sibling)) })
            AssistChip(onClick = { edit { AccessTags.move(it, n.path, -1) }; onSelect(null) }, label = { Text(stringResource(R.string.pc_move_up)) })
            AssistChip(onClick = { edit { AccessTags.move(it, n.path, 1) }; onSelect(null) }, label = { Text(stringResource(R.string.pc_move_down)) })
            AssistChip(onClick = { edit { AccessTags.indent(it, n.path) }; onSelect(null) }, label = { Text(stringResource(R.string.pc_tags_indent)) })
            AssistChip(onClick = { edit { AccessTags.outdent(it, n.path) }; onSelect(null) }, label = { Text(stringResource(R.string.pc_tags_outdent)) })
            AssistChip(onClick = { edit { AccessTags.setType(it, n.path, "Artifact") } }, label = { Text(stringResource(R.string.pc_tags_artifact)) })
            AssistChip(onClick = { edit { AccessTags.delete(it, n.path) }; onSelect(null) }, label = { Text(stringResource(R.string.pc_tags_delete)) })
        }
    }
}

/**
 * Order panel: the content tags of one page in reading order, numbered. Long-press and drag a row, or use the arrows;
 * a tag moves among its own siblings (another group: its parent has to move, which the tree does).
 */
@Composable
private fun OrderPanel(nodes: List<TagNode>, edit: ((PDDocument) -> Unit) -> Unit) {
    val pages = remember(nodes) { nodes.map { it.page }.filter { it >= 0 }.distinct().sorted() }
    var page by remember(pages) { mutableIntStateOf(pages.firstOrNull() ?: 0) }
    val order = AccessTags.readingOrder(nodes, page)
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val otherGroup = stringResource(R.string.pa_order_other_group)
    var note by remember { mutableStateOf<String?>(null) }
    fun moveTo(from: TagNode, to: TagNode) {
        if (from.path.dropLast(1) != to.path.dropLast(1)) { note = otherGroup; return }
        note = null; edit { d -> AccessTags.moveTo(d, from.path, to.path.last()) }
    }
    T5Card(stringResource(R.string.pa_tags_order), Icons.Default.FormatListNumbered) {
        if (pages.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            pages.forEach { p -> FilterChip(page == p, { page = p }, label = { Text("${p + 1}") }) }
        }
        if (order.isEmpty()) Text(stringResource(R.string.pa_order_none), style = MaterialTheme.typography.bodySmall)
        note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val rowH = 48f * androidx.compose.ui.platform.LocalDensity.current.density
        order.forEachIndexed { i, n ->
            key(n.path) {
                Row(Modifier.fillMaxWidth().height(48.dp)
                    .graphicsLayer { translationY = if (dragFrom == i) dragDy else 0f }
                    .pointerInput(n.path, order.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragFrom = i; dragDy = 0f },
                            onDrag = { ch, d -> ch.consume(); dragDy += d.y },
                            onDragEnd = {
                                val to = (i + Math.round(dragDy / rowH)).coerceIn(0, order.lastIndex)
                                dragFrom = null; dragDy = 0f
                                if (to != i) moveTo(n, order[to])
                            },
                            onDragCancel = { dragFrom = null; dragDy = 0f },
                        )
                    }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", Modifier.width(32.dp), style = MaterialTheme.typography.labelLarge)
                    Text("<${n.type}> " + (n.actualText ?: n.alt ?: n.title ?: ""), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { if (i > 0) moveTo(n, order[i - 1]) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.pa_order_up, i + 1)) }
                    IconButton(onClick = { if (i < order.lastIndex) moveTo(n, order[i + 1]) }, enabled = i < order.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.pa_order_down, i + 1)) }
                }
            }
        }
    }
}
