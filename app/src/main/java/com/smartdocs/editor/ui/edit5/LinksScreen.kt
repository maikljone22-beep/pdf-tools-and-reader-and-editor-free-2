package com.smartdocs.editor.ui.edit5

import android.graphics.RectF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.AutoLinks
import com.smartdocs.editor.core.edit5.DestFit
import com.smartdocs.editor.core.edit5.LinkGeom
import com.smartdocs.editor.core.edit5.LinkKind
import com.smartdocs.editor.core.edit5.LinkOps
import com.smartdocs.editor.core.edit5.LinkSpec
import com.smartdocs.editor.core.edit5.PageLinkInfo
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.view5.PrintLayout
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.launch
import java.io.File

/** Page scope of the text tools (Foxit "Current page / All pages / From–To"). */
private enum class Scope { ALL, CURRENT, RANGE }

/** Which automatic link tool a dialog runs. */
private enum class AutoTool { URLS, PAGE_NUMBERS, CROSS_REFS, KEYWORD }

private fun box(r: RectF) = LinkGeom.Box(minOf(r.left, r.right), minOf(r.top, r.bottom), maxOf(r.left, r.right), maxOf(r.top, r.bottom))
private fun rect(b: LinkGeom.Box) = RectF(b.left, b.top, b.right, b.bottom)

/**
 * Real link annotations: draw a rectangle to add, tap a link to edit / delete, convert text URLs, remove all.
 * nav-bookmarks: page scope and phone numbers for URL links, Auto Page Links, cross-reference links, Auto Keyword
 * Links, and a selection mode (align, distribute, same size, move, delete).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinksScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var page by remember(doc.source) { mutableIntStateOf(0) }
    var links by remember { mutableStateOf<List<PageLinkInfo>>(emptyList()) }
    var editing by remember { mutableStateOf<Pair<LinkSpec, PageLinkInfo?>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<List<Int>>(emptyList()) }     // annotation indexes, in selection order
    var auto by remember { mutableStateOf<AutoTool?>(null) }
    var step by remember { mutableFloatStateOf(1f) }
    val processing = stringResource(R.string.processing)
    val doneN = stringResource(R.string.e5_links_added_n)
    val removedN = stringResource(R.string.e5_links_removed_n)
    val hint = stringResource(R.string.e5_links_hint)

    LaunchedEffect(doc.work, doc.version, page) {
        val w = doc.work ?: return@LaunchedEffect
        links = runCatching { LinkOps.list(w, doc.password, page) }.getOrDefault(emptyList())
        selected = selected.filter { i -> links.any { it.index == i } }
    }
    LaunchedEffect(page) { selected = emptyList() }
    fun run(op: suspend (File, File, String?) -> Unit, after: (() -> Unit)? = null) {
        busy = true
        scope.launch {
            runCatching { doc.apply(app.documents.tempDir, op) }.onFailure { snack.showSnackbar(it.message ?: it.toString()) }
            busy = false; after?.invoke()
        }
    }
    /** New rects for the selected links of this page. */
    fun moveSelected(f: (List<LinkGeom.Box>) -> List<LinkGeom.Box>) {
        val sel = selected.mapNotNull { i -> links.firstOrNull { it.index == i } }
        if (sel.isEmpty()) return
        val out = f(sel.map { box(it.spec.rect) })
        val map = sel.mapIndexed { k, l -> l.index to rect(out[k]) }.toMap()
        run({ s, o, p -> LinkOps.setRects(s, o, p, page, map) })
    }
    /** Run a text-based tool over [pages]: [make] returns the specs of one page from its text and character boxes. */
    fun textTool(pages: List<Int>, make: (Int, String, List<RectF>) -> List<LinkSpec>) {
        busy = true
        scope.launch {
            var n = 0
            runCatching {
                doc.apply(app.documents.tempDir) { src, out, pw ->
                    val s = PdfSession.open(ctx, src, pw)
                    val add = try { pages.associateWith { p -> make(p, s.text(p), s.charBoxes(p)) }.filterValues { it.isNotEmpty() } } finally { s.closeAsync() }
                    n = add.values.sumOf { it.size }
                    if (n > 0) LinkOps.add(src, out, pw, add) else src.copyTo(out, overwrite = true)
                }
            }.onFailure { snack.showSnackbar(it.message ?: it.toString()) }
            busy = false
            snack.showSnackbar(doneN.format(n))
        }
    }

    ToolScaffold(stringResource(R.string.e5_links_title), nav, runner, snackbar = snack, actions = {
        IconButton(onClick = { selecting = !selecting; selected = emptyList() }) {
            Icon(Icons.Default.Checklist, stringResource(R.string.nbm_select), tint = if (selecting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = { scope.launch { doc.undo() } }, enabled = doc.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.e5_undo)) }
    }) {
        E5FileRow(doc)
        val info = doc.infos.getOrNull(page)
        if (doc.work != null && info != null) {
            E5TwoPane(options = {
                Text(if (selecting) stringResource(R.string.nbm_links_select_hint) else hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (selecting) E5Section(stringResource(R.string.nbm_selected_n, selected.size)) {
                    val one = selected.isNotEmpty(); val two = selected.size >= 2; val three = selected.size >= 3
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(LinkGeom.Align.LEFT to Icons.Default.AlignHorizontalLeft, LinkGeom.Align.CENTER_H to Icons.Default.AlignHorizontalCenter,
                            LinkGeom.Align.RIGHT to Icons.Default.AlignHorizontalRight, LinkGeom.Align.TOP to Icons.Default.AlignVerticalTop,
                            LinkGeom.Align.CENTER_V to Icons.Default.AlignVerticalCenter, LinkGeom.Align.BOTTOM to Icons.Default.AlignVerticalBottom).forEach { (m, ic) ->
                            IconButton(onClick = { moveSelected { LinkGeom.align(it, m) } }, enabled = two) { Icon(ic, stringResource(alignLabel(m))) }
                        }
                        IconButton(onClick = { moveSelected { LinkGeom.distribute(it, LinkGeom.Spread.HORIZONTAL) } }, enabled = three) { Icon(Icons.Default.HorizontalDistribute, stringResource(R.string.nbm_distribute_h)) }
                        IconButton(onClick = { moveSelected { LinkGeom.distribute(it, LinkGeom.Spread.VERTICAL) } }, enabled = three) { Icon(Icons.Default.VerticalDistribute, stringResource(R.string.nbm_distribute_v)) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { moveSelected { LinkGeom.sameSize(it, LinkGeom.Size.WIDTH) } }, enabled = two, label = { Text(stringResource(R.string.nbm_same_width)) })
                        AssistChip(onClick = { moveSelected { LinkGeom.sameSize(it, LinkGeom.Size.HEIGHT) } }, enabled = two, label = { Text(stringResource(R.string.nbm_same_height)) })
                        AssistChip(onClick = { moveSelected { LinkGeom.sameSize(it, LinkGeom.Size.BOTH) } }, enabled = two, label = { Text(stringResource(R.string.nbm_same_size)) })
                    }
                    Text(stringResource(R.string.nbm_move_by, step.toInt()))
                    ChoiceChips(listOf(1f, 5f, 10f, 36f), step, { it.toInt().toString() }) { step = it }
                    Row {
                        IconButton(onClick = { moveSelected { l -> l.map { it.moved(-step, 0f) } } }, enabled = one) { Icon(Icons.Default.KeyboardArrowLeft, stringResource(R.string.nbm_move_left)) }
                        IconButton(onClick = { moveSelected { l -> l.map { it.moved(step, 0f) } } }, enabled = one) { Icon(Icons.Default.KeyboardArrowRight, stringResource(R.string.nbm_move_right)) }
                        IconButton(onClick = { moveSelected { l -> l.map { it.moved(0f, step) } } }, enabled = one) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.nbm_move_up)) }
                        IconButton(onClick = { moveSelected { l -> l.map { it.moved(0f, -step) } } }, enabled = one) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.nbm_move_down)) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { val idx = selected.toSet(); selected = emptyList(); run({ s, o, p -> LinkOps.delete(s, o, p, mapOf(page to idx)) }) }, enabled = one) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete))
                        }
                    }
                }
                E5Section(stringResource(R.string.e5_links_on_page, links.size)) {
                    if (links.isEmpty()) Text(stringResource(R.string.e5_links_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    links.forEach { l ->
                        val sel = l.index in selected
                        ListItem(headlineContent = { Text(linkSummary(l.spec), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(if (l.spec.kind == LinkKind.OTHER) stringResource(R.string.nbm_action_list) else stringResource(kindLabel(l.spec.kind))) },
                            leadingContent = {
                                if (selecting) Checkbox(sel, null)
                                else Icon(if (l.spec.kind == LinkKind.PAGE) Icons.Default.Bookmark else Icons.Default.Link, null)
                            },
                            modifier = Modifier.clickable { if (selecting) selected = if (sel) selected - l.index else selected + l.index else editing = l.spec to l })
                    }
                }
                E5Section(stringResource(R.string.e5_links_tools)) {
                    OutlinedButton(onClick = { auto = AutoTool.URLS }, Modifier.fillMaxWidth()) { Icon(Icons.Default.AddLink, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.e5_links_linkify)) }
                    OutlinedButton(onClick = { auto = AutoTool.PAGE_NUMBERS }, Modifier.fillMaxWidth()) { Icon(Icons.Default.FormatListNumbered, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.nbm_auto_page_links)) }
                    OutlinedButton(onClick = { auto = AutoTool.CROSS_REFS }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Shortcut, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.nbm_xref_links)) }
                    OutlinedButton(onClick = { auto = AutoTool.KEYWORD }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.nbm_keyword_links)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { var n = 0; run({ s, o, p -> n = LinkOps.removeAll(s, o, p, webOnly = true) }) { scope.launch { snack.showSnackbar(removedN.format(n)) } } }, Modifier.weight(1f)) { Text(stringResource(R.string.e5_links_remove_web)) }
                        OutlinedButton(onClick = { var n = 0; run({ s, o, p -> n = LinkOps.removeAll(s, o, p, webOnly = false) }) { scope.launch { snack.showSnackbar(removedN.format(n)) } } }, Modifier.weight(1f)) { Text(stringResource(R.string.e5_links_remove_all)) }
                    }
                }
            }, preview = {
                PageStepper(page, doc.pageCount) { page = it }
                E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth(),
                    onTap = { x, y ->
                        val (ux, uy) = info.toUser(x, y)
                        links.lastOrNull { val r = it.spec.rect; ux in minOf(r.left, r.right)..maxOf(r.left, r.right) && uy in minOf(r.top, r.bottom)..maxOf(r.top, r.bottom) }?.let {
                            if (selecting) selected = if (it.index in selected) selected - it.index else selected + it.index else editing = it.spec to it
                        }
                    },
                    onRect = { r -> if (!selecting && r.width() > 6f && r.top - r.bottom > 6f) editing = LinkSpec(info.rectToUser(r)) to null }) { map ->
                    links.forEach { l -> e5Box(map(info.rectToDisplay(l.spec.rect)), when { l.index in selected -> Color(0xFFEF6C00); l.editable -> Color(0xFF1565C0); else -> Color.Gray }) }
                }
            })
            PrimaryButton(stringResource(R.string.e5_save_copy), enabled = doc.changed, icon = Icons.Default.Save) {
                runner.run(processing) { doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "links") }
            }
        }
    }
    if (busy) com.smartdocs.editor.ui.common.ProgressDialog(processing)
    editing?.let { (spec, existing) ->
        LinkEditorSheet(spec, doc.pageCount, isNew = existing == null, onDismiss = { editing = null },
            onDelete = existing?.let { e -> { editing = null; run({ s, o, p -> LinkOps.delete(s, o, p, mapOf(e.page to setOf(e.index))) }) } }) { result ->
            editing = null
            if (existing == null) run({ s, o, p -> LinkOps.add(s, o, p, mapOf(page to listOf(result))) })
            else run({ s, o, p -> LinkOps.update(s, o, p, existing.page, existing.index, movedLink(result, existing.spec)) })
        }
    }
    auto?.let { tool ->
        AutoLinkDialog(tool, page, doc.pageCount, onDismiss = { auto = null }) { pages, opts ->
            auto = null
            val base = LinkDefaults.apply(ctx, LinkSpec(RectF()))
            when (tool) {
                AutoTool.URLS -> textTool(pages) { _, text, boxes ->
                    com.smartdocs.editor.core.edit5.LinkDetect.find(text, opts.phones).let { found ->
                        AutoLinks.specs(found.map { it.range }, boxes) { k, u, lines ->
                            val t = found[k].target
                            base.copy(rect = u, quads = lines, kind = if (t.startsWith("mailto:")) LinkKind.MAIL else LinkKind.URI, uri = t)
                        }
                    }
                }
                AutoTool.PAGE_NUMBERS -> textTool(pages) { _, text, boxes -> AutoLinks.pageLinks(AutoLinks.tocNumbers(text, doc.pageCount, opts.offset, opts.wholeLine), boxes, base.copy(fit = opts.fit)) }
                AutoTool.CROSS_REFS -> textTool(pages) { _, text, boxes -> AutoLinks.pageLinks(AutoLinks.crossRefs(text, doc.pageCount, opts.offset), boxes, base.copy(fit = opts.fit)) }
                AutoTool.KEYWORD -> textTool(pages) { _, text, boxes ->
                    AutoLinks.specs(AutoLinks.keyword(text, opts.phrase, opts.matchCase, opts.wholeWord), boxes) { _, u, lines -> opts.target.copy(rect = u, quads = lines) }
                }
            }
        }
    }
}

private fun alignLabel(m: LinkGeom.Align) = when (m) {
    LinkGeom.Align.LEFT -> R.string.nbm_align_left; LinkGeom.Align.RIGHT -> R.string.nbm_align_right; LinkGeom.Align.TOP -> R.string.nbm_align_top
    LinkGeom.Align.BOTTOM -> R.string.nbm_align_bottom; LinkGeom.Align.CENTER_H -> R.string.nbm_align_center_h; LinkGeom.Align.CENTER_V -> R.string.nbm_align_center_v
}

/** Options of the automatic link tools. */
private data class AutoOpts(
    val phones: Boolean = false, val offset: Int = 0, val wholeLine: Boolean = false, val fit: DestFit = DestFit.FIT,
    val phrase: String = "", val matchCase: Boolean = false, val wholeWord: Boolean = true, val target: LinkSpec = LinkSpec(RectF(), LinkKind.URI, uri = "https://"),
)

@Composable
private fun AutoLinkDialog(tool: AutoTool, current: Int, pageCount: Int, onDismiss: () -> Unit, onRun: (List<Int>, AutoOpts) -> Unit) {
    var sc by remember { mutableStateOf(Scope.ALL) }
    var range by remember { mutableStateOf("") }
    var o by remember { mutableStateOf(AutoOpts()) }
    var offsetText by remember { mutableStateOf("0") }
    var editTarget by remember { mutableStateOf(false) }
    val title = stringResource(when (tool) { AutoTool.URLS -> R.string.e5_links_linkify; AutoTool.PAGE_NUMBERS -> R.string.nbm_auto_page_links; AutoTool.CROSS_REFS -> R.string.nbm_xref_links; AutoTool.KEYWORD -> R.string.nbm_keyword_links })
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when (tool) { AutoTool.URLS -> R.string.nbm_urls_help; AutoTool.PAGE_NUMBERS -> R.string.nbm_page_links_help; AutoTool.CROSS_REFS -> R.string.nbm_xref_help; AutoTool.KEYWORD -> R.string.nbm_keyword_help }),
                style = MaterialTheme.typography.bodySmall)
            ChoiceChips(Scope.values().toList(), sc, { stringResource(when (it) { Scope.ALL -> R.string.nbm_all_pages; Scope.CURRENT -> R.string.nbm_current_page; Scope.RANGE -> R.string.nbm_page_range }) }) { sc = it }
            if (sc == Scope.RANGE) OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("1-3, 5") })
            when (tool) {
                AutoTool.URLS -> SwitchRow(stringResource(R.string.nbm_phone_links), o.phones) { o = o.copy(phones = it) }
                AutoTool.PAGE_NUMBERS, AutoTool.CROSS_REFS -> {
                    OutlinedTextField(offsetText, { t -> offsetText = t.filter { it.isDigit() || it == '-' }.take(5); o = o.copy(offset = offsetText.toIntOrNull() ?: 0) },
                        Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbm_page_offset)) }, supportingText = { Text(stringResource(R.string.nbm_page_offset_help)) })
                    if (tool == AutoTool.PAGE_NUMBERS) SwitchRow(stringResource(R.string.nbm_link_whole_line), o.wholeLine) { o = o.copy(wholeLine = it) }
                    ChoiceChips(listOf(DestFit.FIT, DestFit.FITH, DestFit.XYZ), o.fit, { stringResource(nbmFitLabel(it)) }) { o = o.copy(fit = it) }
                }
                AutoTool.KEYWORD -> {
                    OutlinedTextField(o.phrase, { o = o.copy(phrase = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbm_keyword)) })
                    SwitchRow(stringResource(R.string.case_sensitive), o.matchCase) { o = o.copy(matchCase = it) }
                    SwitchRow(stringResource(R.string.whole_words), o.wholeWord) { o = o.copy(wholeWord = it) }
                    OutlinedButton(onClick = { editTarget = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(8.dp)); Text(linkSummary(o.target), maxLines = 1) }
                }
            }
        }
    }, confirmButton = {
        Button(enabled = tool != AutoTool.KEYWORD || o.phrase.isNotBlank(), onClick = {
            val pages = when (sc) { Scope.ALL -> (0 until pageCount).toList(); Scope.CURRENT -> listOf(current); Scope.RANGE -> PrintLayout.parseRange(range, pageCount) }
            onRun(pages, o)
        }) { Text(stringResource(R.string.e5_run)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
    if (editTarget) LinkEditorSheet(o.target, pageCount, isNew = true, onDismiss = { editTarget = false }) { s -> editTarget = false; o = o.copy(target = s) }
}

/** Display-space rect helper used by previews of other screens. */
internal fun RectF.normalized(): RectF = RectF(minOf(left, right), maxOf(top, bottom), maxOf(left, right), minOf(top, bottom))
