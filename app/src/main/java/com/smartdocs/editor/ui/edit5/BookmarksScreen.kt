package com.smartdocs.editor.ui.edit5

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit.OutlineModel
import com.smartdocs.editor.core.edit5.AutoBmLevel
import com.smartdocs.editor.core.edit5.AutoBmSettings
import com.smartdocs.editor.core.edit5.AutoBookmarks
import com.smartdocs.editor.core.edit5.AutoInsert
import com.smartdocs.editor.core.edit5.BookmarkXml
import com.smartdocs.editor.core.edit5.DestFit
import com.smartdocs.editor.core.edit5.E5Bookmark
import com.smartdocs.editor.core.edit5.E5Font
import com.smartdocs.editor.core.edit5.LeadingNumbers
import com.smartdocs.editor.core.edit5.NavAction
import com.smartdocs.editor.core.edit5.NavIo
import com.smartdocs.editor.core.edit5.OutlineNav
import com.smartdocs.editor.core.edit5.OutlineStore
import com.smartdocs.editor.core.edit5.OutlineTools
import com.smartdocs.editor.core.edit5.TocOrientation
import com.smartdocs.editor.core.edit5.TocStyle
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BM_COLORS = listOf(0x000000, 0xC62828, 0x1565C0, 0x2E7D32, 0x6A1B9A, 0xEF6C00)

/**
 * Bookmarks editor keeping colour, bold/italic, open state and destination zoom; auto bookmarks; clickable TOC page.
 * nav-bookmarks: custom colours, every destination view, action lists, expand / collapse / delete all, selection
 * (delete / export), XML export / import, section pages (extract / delete) and the full auto-bookmark settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookmarksScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var list by remember { mutableStateOf<List<E5Bookmark>>(emptyList()) }
    var dirty by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }          // index; -1 = new
    var showAuto by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var deleteSection by remember { mutableStateOf<Int?>(null) }
    val processing = stringResource(R.string.processing)
    val noHeadings = stringResource(R.string.e5_bm_no_headings)
    val importedN = stringResource(R.string.nbm_bm_imported_n)
    val importBad = stringResource(R.string.nbm_import_failed)
    LaunchedEffect(doc.work, doc.version) {
        val w = doc.work ?: return@LaunchedEffect
        if (!dirty) list = runCatching { OutlineStore.load(w, doc.password).first }.getOrDefault(emptyList())
    }
    fun update(l: List<E5Bookmark>) { list = l; dirty = true }
    fun saveOutline(then: (() -> Unit)? = null) {
        val l = list; busy = true
        scope.launch {
            runCatching {
                doc.apply(app.documents.tempDir) { s, o, p -> OutlineStore.save(s, o, p, l) }
                dirty = false
                doc.work?.let { w -> list = OutlineStore.load(w, doc.password).first }   // fresh item indexes for kept actions
            }.onFailure { snack.showSnackbar(it.message ?: it.toString()) }
            busy = false; then?.invoke()
        }
    }
    // XML export of all bookmarks, or of the selected ones (with their levels)
    var exportList by remember { mutableStateOf<List<E5Bookmark>>(emptyList()) }
    val exportXml = rememberTextExport("text/xml") { BookmarkXml.write(exportList) }
    val importXml = rememberTextImport(arrayOf("text/xml", "application/xml", "*/*")) { bytes ->
        val r = runCatching { BookmarkXml.read(bytes.inputStream(), doc.pageCount) }
        scope.launch {
            r.onSuccess { l -> update(list + l); snack.showSnackbar(importedN.format(l.size)) }.onFailure { snack.showSnackbar(importBad) }
        }
    }
    val base = doc.source?.nameWithoutExtension ?: "bookmarks"

    ToolScaffold(stringResource(R.string.e5_bm_title), nav, runner, snackbar = snack, actions = {
        IconButton(onClick = { scope.launch { doc.undo(); dirty = false } }, enabled = doc.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.e5_undo)) }
    }) {
        E5FileRow(doc)
        if (doc.work != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { editing = -1 }, label = { Text(stringResource(R.string.e5_bm_add)) }, leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) })
                AssistChip(onClick = { showAuto = true }, label = { Text(stringResource(R.string.e5_bm_auto)) }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null) })
                AssistChip(onClick = { showToc = true }, label = { Text(stringResource(R.string.e5_bm_toc)) }, leadingIcon = { Icon(Icons.Default.Toc, null) })
                AssistChip(onClick = { update(OutlineNav.setOpenAll(list, true)) }, enabled = list.isNotEmpty(), label = { Text(stringResource(R.string.nbm_expand_all)) }, leadingIcon = { Icon(Icons.Default.UnfoldMore, null) })
                AssistChip(onClick = { update(OutlineNav.setOpenAll(list, false)) }, enabled = list.isNotEmpty(), label = { Text(stringResource(R.string.nbm_collapse_all)) }, leadingIcon = { Icon(Icons.Default.UnfoldLess, null) })
                AssistChip(onClick = { selecting = !selecting; selected = emptySet() }, enabled = list.isNotEmpty(), label = { Text(stringResource(R.string.nbm_select)) }, leadingIcon = { Icon(Icons.Default.Checklist, null) })
                AssistChip(onClick = { exportList = list; exportXml("$base-bookmarks.xml") }, enabled = list.isNotEmpty(), label = { Text(stringResource(R.string.nbm_export_xml)) }, leadingIcon = { Icon(Icons.Default.Upload, null) })
                AssistChip(onClick = { importXml() }, label = { Text(stringResource(R.string.nbm_import_xml)) }, leadingIcon = { Icon(Icons.Default.Download, null) })
                AssistChip(onClick = { confirmDeleteAll = true }, enabled = list.isNotEmpty(), label = { Text(stringResource(R.string.nbm_delete_all)) }, leadingIcon = { Icon(Icons.Default.DeleteSweep, null) })
            }
            if (selecting) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.nbm_selected_n, selected.size), Modifier.weight(1f))
                TextButton(onClick = { selected = if (selected.size == list.size) emptySet() else list.map { it.key }.toSet() }) { Text(stringResource(R.string.nbm_select_all)) }
                TextButton(enabled = selected.isNotEmpty(), onClick = { exportList = list.filter { it.key in selected }; exportXml("$base-bookmarks.xml") }) { Text(stringResource(R.string.nbm_export)) }
                TextButton(enabled = selected.isNotEmpty(), onClick = { update(list.filter { it.key !in selected }.let { l -> OutlineStore.tree(l) { OutlineModel.normalize(it) } }); selected = emptySet() }) { Text(stringResource(R.string.delete)) }
            }
            if (list.isEmpty()) Text(stringResource(R.string.e5_bm_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.forEachIndexed { i, b ->
                Row(Modifier.fillMaxWidth().clickable { if (selecting) selected = if (b.key in selected) selected - b.key else selected + b.key else editing = i }
                    .padding(start = (16 * b.level).dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) Checkbox(b.key in selected, { selected = if (it) selected + b.key else selected - b.key })
                    Box(Modifier.size(10.dp).background(Color(0xFF000000.toInt() or (b.color ?: 0x000000)), MaterialTheme.shapes.extraSmall))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (b.bold) FontWeight.Bold else null, fontStyle = if (b.italic) FontStyle.Italic else null)
                        val sub = if (b.actions.isNotEmpty()) actionSummary(b.actions.first()) + (if (b.actions.size > 1) " +${b.actions.size - 1}" else "")
                            else stringResource(R.string.e5_link_to_page, b.page + 1) + (if (b.zoom > 0f) " • ${(b.zoom * 100).toInt()}%" else "")
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!selecting) {
                        IconButton(onClick = { update(OutlineStore.tree2(list) { OutlineModel.moveUp(it, i) }.first) }) { Icon(Icons.Default.ArrowUpward, null) }
                        IconButton(onClick = { update(OutlineStore.tree2(list) { OutlineModel.moveDown(it, i) }.first) }) { Icon(Icons.Default.ArrowDownward, null) }
                        IconButton(onClick = { update(OutlineStore.tree(list) { OutlineModel.outdent(it, i) }) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, null) }
                        IconButton(onClick = { update(OutlineStore.tree(list) { OutlineModel.indent(it, i) }) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, null) }
                        IconButton(onClick = { update(OutlineStore.tree(list) { OutlineModel.delete(it, i) }) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.nbm_more)) }
                            DropdownMenu(menu, { menu = false }) {
                                val sec = OutlineNav.sectionPages(list, i, doc.pageCount)
                                DropdownMenuItem(text = { Text(stringResource(R.string.nbm_section_extract, sec.count())) }, enabled = !sec.isEmpty(), leadingIcon = { Icon(Icons.Default.ContentCut, null) }, onClick = {
                                    menu = false
                                    val w = doc.work ?: return@DropdownMenuItem
                                    runner.run(processing) {
                                        val out = app.documents.uniqueFile(app.documents.docsDir, "${base}_${b.title.take(40)}")
                                        PdfOps.keepPages(w, sec.toList(), out, doc.password); out
                                    }
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.nbm_section_delete, sec.count())) }, enabled = !sec.isEmpty() && sec.count() < doc.pageCount,
                                    leadingIcon = { Icon(Icons.Default.DeleteForever, null) }, onClick = { menu = false; deleteSection = i })
                            }
                        }
                    }
                }
            }
            PrimaryButton(stringResource(R.string.e5_save_copy), enabled = dirty || doc.changed, icon = Icons.Default.Save) {
                if (dirty) saveOutline { runner.run(processing) { doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "bookmarks") } }
                else runner.run(processing) { doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "bookmarks") }
            }
        }
    }
    if (busy) ProgressDialog(processing)
    if (confirmDeleteAll) AlertDialog(onDismissRequest = { confirmDeleteAll = false }, title = { Text(stringResource(R.string.nbm_delete_all)) },
        text = { Text(stringResource(R.string.nbm_delete_all_q, list.size)) },
        confirmButton = { Button(onClick = { confirmDeleteAll = false; update(emptyList()); selecting = false }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.cancel)) } })
    deleteSection?.let { i ->
        val sec = OutlineNav.sectionPages(list, i, doc.pageCount)
        AlertDialog(onDismissRequest = { deleteSection = null }, title = { Text(stringResource(R.string.nbm_section_delete, sec.count())) },
            text = { Text(stringResource(R.string.nbm_section_delete_q, sec.first + 1, sec.last + 1)) },
            confirmButton = { Button(onClick = {
                deleteSection = null
                val pages = sec.toSet()
                saveOutline {
                    busy = true
                    scope.launch {
                        runCatching {
                            doc.apply(app.documents.tempDir) { s, o, p ->
                                val cur = OutlineStore.load(s, p).first
                                withContext(Dispatchers.IO) {
                                    NavIo.edit(s, o, p) { d, changed ->
                                        com.smartdocs.editor.core.edit5.PageLabelRemap.keep(d) { pages.sortedDescending().forEach { d.removePage(it) } }   // audit 5 E-5
                                        OutlineStore.write(d, OutlineNav.afterDelete(cur, pages))
                                        d.pages.forEach { changed += it }
                                    }
                                }
                            }
                            doc.work?.let { w -> list = OutlineStore.load(w, doc.password).first }
                        }.onFailure { snack.showSnackbar(it.message ?: it.toString()) }
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleteSection = null }) { Text(stringResource(R.string.cancel)) } })
    }
    editing?.let { idx ->
        val b = list.getOrNull(idx) ?: E5Bookmark(OutlineModel.newKey(), "", 0, list.getOrNull(list.lastIndex)?.level ?: 0)
        BookmarkSheet(b, doc.pageCount, doc.infos.getOrNull(b.page.coerceAtLeast(0)), onDismiss = { editing = null }) { nb ->
            editing = null
            if (idx >= 0) update(list.toMutableList().also { it[idx] = nb })
            else update(OutlineStore.tree(list + nb) { OutlineModel.normalize(it) })
        }
    }
    if (showAuto) AutoBookmarksDialog(onDismiss = { showAuto = false }) { settings ->
        showAuto = false; busy = true
        AutoBmPrefs.save(ctx, settings)
        scope.launch {
            val w = doc.work
            val found = if (w != null) runCatching { OutlineTools.autoBookmarks(w, doc.password, settings, doc.pageCount) }.getOrDefault(emptyList()) else emptyList()
            busy = false
            if (found.isEmpty()) snack.showSnackbar(noHeadings) else update(OutlineNav.insert(list, found, settings.insert))
        }
    }
    if (showToc) TocDialog(onDismiss = { showToc = false }) { title, maxLevel, rtl, arDigits, font, style ->
        showToc = false
        saveOutline {
            busy = true
            scope.launch {
                runCatching { doc.apply(app.documents.tempDir) { s, o, p -> OutlineTools.insertToc(ctx, s, o, p, OutlineStore.load(s, p).first, title, 0, maxLevel, rtl, arDigits, font, style = style) } }
                    .onFailure { snack.showSnackbar(it.message ?: it.toString()) }
                doc.work?.let { w -> list = runCatching { OutlineStore.load(w, doc.password).first }.getOrDefault(list) }
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkSheet(initial: E5Bookmark, pageCount: Int, info: com.smartdocs.editor.core.edit5.PageInfo?, onDismiss: () -> Unit, onSave: (E5Bookmark) -> Unit) {
    var b by remember { mutableStateOf(initial) }
    var pageText by remember { mutableStateOf((initial.page.coerceAtLeast(0) + 1).toString()) }
    var useActions by remember { mutableStateOf(initial.actions.isNotEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(b.title, { b = b.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.e5_bm_name)) })
            ChoiceChips(listOf(false, true), useActions, { stringResource(if (it) R.string.nbm_action_list else R.string.nbm_go_to_view) }) { useActions = it }
            if (useActions) ActionListEditor(b.actions, pageCount) { b = b.copy(actions = it) }
            else {
                OutlinedTextField(pageText, { pageText = it.filter { c -> c.isDigit() }.take(6) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.e5_link_page_of, pageCount)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                DestViewFields(b.view, info?.displayW ?: 595f, info?.displayH ?: 842f) { b = b.withView(it) }
            }
            Text(stringResource(R.string.e5_color))
            NbmColorRow(BM_COLORS, b.color ?: 0x000000) { b = b.copy(color = if (it == 0x000000) null else it) }
            SwitchRow(stringResource(R.string.e5_bold), b.bold) { b = b.copy(bold = it) }
            SwitchRow(stringResource(R.string.e5_italic), b.italic) { b = b.copy(italic = it) }
            SwitchRow(stringResource(R.string.e5_bm_open), b.open) { b = b.copy(open = it) }
            Row { Spacer(Modifier.weight(1f)); TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(enabled = b.title.isNotBlank() && (!useActions || b.actions.isNotEmpty()), onClick = {
                    val p = ((pageText.toIntOrNull() ?: 1) - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                    if (useActions) onSave(b.copy(page = (b.actions.firstOrNull { it is NavAction.GoTo } as? NavAction.GoTo)?.page ?: -1))
                    else {
                        val pageChanged = p != initial.page
                        onSave(b.copy(page = p, actions = emptyList(), top = if (pageChanged && b.fit == DestFit.XYZ) null else b.top, left = if (pageChanged && b.fit == DestFit.XYZ) null else b.left))
                    }
                }) { Text(stringResource(R.string.save)) } }
        }
    }
}

/** Last auto-bookmark settings (kept between runs). */
private object AutoBmPrefs {
    private fun p(c: Context) = c.getSharedPreferences("nbm_autobm", Context.MODE_PRIVATE)
    fun load(c: Context): AutoBmSettings = p(c).getString("s", null)?.let { runCatching { AutoBookmarks.fromJson(it) }.getOrNull() } ?: AutoBmSettings()
    fun save(c: Context, s: AutoBmSettings) = p(c).edit().putString("s", AutoBookmarks.toJson(s)).apply()
}

@Composable
private fun AutoBookmarksDialog(onDismiss: () -> Unit, onRun: (AutoBmSettings) -> Unit) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(AutoBmPrefs.load(ctx)) }
    var openLevel by remember { mutableIntStateOf(-1) }
    val bad = stringResource(R.string.nbm_import_failed)
    val exportSettings = rememberTextExport("application/json") { AutoBookmarks.toJson(s) }
    val importSettings = rememberTextImport(arrayOf("application/json", "text/plain", "*/*")) { bytes ->
        runCatching { AutoBookmarks.fromJson(bytes.toString(Charsets.UTF_8)) }.onSuccess { s = it }
            .onFailure { android.widget.Toast.makeText(ctx, bad, android.widget.Toast.LENGTH_LONG).show() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.e5_bm_auto)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.e5_bm_auto_help), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.e5_bm_levels))
            ChoiceChips((1..AutoBmSettings.MAX_LEVELS).toList(), s.levels.size, { it.toString() }) { n ->
                s = s.copy(levels = if (n <= s.levels.size) s.levels.take(n) else s.levels + List(n - s.levels.size) { AutoBmLevel() })
            }
            s.levels.forEachIndexed { i, l ->
                OutlinedButton(onClick = { openLevel = if (openLevel == i) -1 else i }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.nbm_level_n, i + 1) + " • " + (if (l.size > 0f) "${l.size}pt" else stringResource(R.string.nbm_auto_size)), Modifier.weight(1f))
                    Icon(if (openLevel == i) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                if (openLevel == i) LevelRule(l) { nl -> s = s.copy(levels = s.levels.toMutableList().also { it[i] = nl }) }
            }
            Text(stringResource(R.string.e5_bm_ratio, (s.minRatio * 100).toInt())); Slider(s.minRatio, { s = s.copy(minRatio = it) }, valueRange = 1.05f..1.8f)
            Text(stringResource(R.string.nbm_leading_numbers))
            ChoiceChips(LeadingNumbers.values().toList(), s.leadingNumbers, { stringResource(when (it) { LeadingNumbers.KEEP -> R.string.nbm_ln_keep; LeadingNumbers.STRIP -> R.string.nbm_ln_strip; LeadingNumbers.REQUIRE -> R.string.nbm_ln_require }) }) { s = s.copy(leadingNumbers = it) }
            SwitchRow(stringResource(R.string.nbm_exact_text), s.exactText) { s = s.copy(exactText = it) }
            SwitchRow(stringResource(R.string.nbm_skip_repeated), s.skipRepeated) { s = s.copy(skipRepeated = it) }
            SwitchRow(stringResource(R.string.nbm_collapse_new), s.collapse) { s = s.copy(collapse = it) }
            Text(stringResource(R.string.nbm_magnification))
            DestViewFields(s.view) { s = s.copy(view = it) }
            Text(stringResource(R.string.nbm_insert_where))
            ChoiceChips(AutoInsert.values().toList(), s.insert, { stringResource(when (it) { AutoInsert.REPLACE -> R.string.nbm_ins_replace; AutoInsert.BEFORE -> R.string.nbm_ins_before; AutoInsert.AFTER -> R.string.nbm_ins_after }) }) { s = s.copy(insert = it) }
            OutlinedTextField(s.pages, { s = s.copy(pages = it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbm_page_range)) },
                placeholder = { Text(stringResource(R.string.nbm_all_pages)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { importSettings() }) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nbm_import_settings)) }
                TextButton(onClick = { exportSettings("autobookmarks.json") }) { Icon(Icons.Default.Upload, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nbm_export_settings)) }
            }
        }
    }, confirmButton = { Button(onClick = { onRun(s) }) { Text(stringResource(R.string.e5_run)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** One level's rule: size (0 = automatic) and tolerance, font, colour, indent, text mask, keywords. */
@Composable
private fun LevelRule(l: AutoBmLevel, onChange: (AutoBmLevel) -> Unit) {
    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FloatField(l.size, stringResource(R.string.nbm_font_size_auto), Modifier.weight(1f)) { onChange(l.copy(size = it.coerceAtLeast(0f))) }
            FloatField(l.sizeTolerance, stringResource(R.string.nbm_tolerance), Modifier.weight(1f)) { onChange(l.copy(sizeTolerance = it.coerceIn(0f, 20f))) }
        }
        OutlinedTextField(l.font, { onChange(l.copy(font = it)) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbm_font_name)) })
        SwitchRow(stringResource(R.string.nbm_match_color), l.color != null) { onChange(l.copy(color = if (it) 0x000000 else null)) }
        l.color?.let { c -> NbmColorRow(BM_COLORS, c) { onChange(l.copy(color = it)) } }
        SwitchRow(stringResource(R.string.nbm_match_indent), l.indent != null) { onChange(l.copy(indent = if (it) 72f else null)) }
        l.indent?.let { ind ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FloatField(ind, stringResource(R.string.nbm_left_indent), Modifier.weight(1f)) { onChange(l.copy(indent = it)) }
                FloatField(l.indentTolerance, stringResource(R.string.nbm_tolerance), Modifier.weight(1f)) { onChange(l.copy(indentTolerance = it.coerceAtLeast(0f))) }
            }
        }
        OutlinedTextField(l.mask, { onChange(l.copy(mask = it)) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbm_text_mask)) },
            supportingText = { Text(stringResource(R.string.nbm_text_mask_help)) })
        OutlinedTextField(l.include, { onChange(l.copy(include = it)) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbm_keywords_in)) })
        OutlinedTextField(l.exclude, { onChange(l.copy(exclude = it)) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbm_keywords_out)) })
    }
}

private val TOC_COLORS = listOf(0x212121, 0x424242, 0xC62828, 0x1565C0, 0x2E7D32, 0x6A1B9A)

@Composable
private fun TocDialog(onDismiss: () -> Unit, onRun: (String, Int, Boolean, Boolean, E5Font, TocStyle) -> Unit) {
    val def = stringResource(R.string.e5_toc_default_title)
    var title by remember { mutableStateOf(def) }
    var maxLevel by remember { mutableIntStateOf(1) }
    var rtl by remember { mutableStateOf(true) }
    var ar by remember { mutableStateOf(false) }
    var font by remember { mutableStateOf(E5Font.NASKH) }
    var st by remember { mutableStateOf(TocStyle()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.e5_bm_toc)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.e5_toc_help), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it }, singleLine = true, label = { Text(stringResource(R.string.e5_text)) })
            Text(stringResource(R.string.e5_bm_levels)); ChoiceChips(listOf(0, 1, 2, 3), maxLevel, { (it + 1).toString() }) { maxLevel = it }
            SwitchRow(stringResource(R.string.e5_rtl_layout), rtl) { rtl = it }
            SwitchRow(stringResource(R.string.e5_arabic_digits), ar) { ar = it }
            FontChoice(font) { font = it }
            Text(stringResource(R.string.nbm_toc_orientation))
            ChoiceChips(TocOrientation.values().toList(), st.orientation, { stringResource(when (it) { TocOrientation.AUTO -> R.string.nbm_orient_auto; TocOrientation.PORTRAIT -> R.string.nbm_orient_portrait; TocOrientation.LANDSCAPE -> R.string.nbm_orient_landscape }) }) { st = st.copy(orientation = it) }
            HorizontalDivider()
            Text(stringResource(R.string.nbm_toc_title_style), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.nbm_size_pt, st.titleSize.toInt())); Slider(st.titleSize, { st = st.copy(titleSize = it.toInt().toFloat()) }, valueRange = 12f..36f)
            NbmColorRow(TOC_COLORS, st.titleColor) { st = st.copy(titleColor = it) }
            SwitchRow(stringResource(R.string.e5_bold), st.titleBold) { st = st.copy(titleBold = it) }
            SwitchRow(stringResource(R.string.e5_italic), st.titleItalic) { st = st.copy(titleItalic = it) }
            SwitchRow(stringResource(R.string.nbm_centered), st.titleCentered) { st = st.copy(titleCentered = it) }
            HorizontalDivider()
            Text(stringResource(R.string.nbm_toc_content_style), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.nbm_size_pt, st.contentSize.toInt())); Slider(st.contentSize, { st = st.copy(contentSize = it.toInt().toFloat()) }, valueRange = 8f..18f)
            NbmColorRow(TOC_COLORS, st.contentColor) { st = st.copy(contentColor = it, subColor = it) }
            SwitchRow(stringResource(R.string.e5_italic), st.contentItalic) { st = st.copy(contentItalic = it) }
            SwitchRow(stringResource(R.string.nbm_bold_top), st.boldTopLevel) { st = st.copy(boldTopLevel = it) }
            SwitchRow(stringResource(R.string.nbm_indent_levels), st.indentLevels) { st = st.copy(indentLevels = it) }
            SwitchRow(stringResource(R.string.nbm_page_numbers), st.pageNumbers) { st = st.copy(pageNumbers = it) }
        }
    }, confirmButton = { Button(onClick = { onRun(title, maxLevel, rtl, ar, font, st) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.e5_insert)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

