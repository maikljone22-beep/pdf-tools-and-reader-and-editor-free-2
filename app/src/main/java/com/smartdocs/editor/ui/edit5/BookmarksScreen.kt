package com.smartdocs.editor.ui.edit5

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
import com.smartdocs.editor.core.edit5.DestFit
import com.smartdocs.editor.core.edit5.E5Bookmark
import com.smartdocs.editor.core.edit5.E5Font
import com.smartdocs.editor.core.edit5.OutlineStore
import com.smartdocs.editor.core.edit5.OutlineTools
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.ColorChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.launch

private val BM_COLORS = listOf(0x000000, 0xC62828, 0x1565C0, 0x2E7D32, 0x6A1B9A, 0xEF6C00)

/** Bookmarks editor keeping colour, bold/italic, open state and destination zoom; auto bookmarks; clickable TOC page. */
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
    val processing = stringResource(R.string.processing)
    val noHeadings = stringResource(R.string.e5_bm_no_headings)
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
    ToolScaffold(stringResource(R.string.e5_bm_title), nav, runner, snackbar = snack, actions = {
        IconButton(onClick = { scope.launch { doc.undo(); dirty = false } }, enabled = doc.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.e5_undo)) }
    }) {
        E5FileRow(doc)
        if (doc.work != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { editing = -1 }, label = { Text(stringResource(R.string.e5_bm_add)) }, leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) })
                AssistChip(onClick = { showAuto = true }, label = { Text(stringResource(R.string.e5_bm_auto)) }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null) })
                AssistChip(onClick = { showToc = true }, label = { Text(stringResource(R.string.e5_bm_toc)) }, leadingIcon = { Icon(Icons.Default.Toc, null) })
            }
            if (list.isEmpty()) Text(stringResource(R.string.e5_bm_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.forEachIndexed { i, b ->
                Row(Modifier.fillMaxWidth().clickable { editing = i }.padding(start = (16 * b.level).dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(Color(0xFF000000.toInt() or (b.color ?: 0x000000)), MaterialTheme.shapes.extraSmall))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (b.bold) FontWeight.Bold else null, fontStyle = if (b.italic) FontStyle.Italic else null)
                        Text(b.actionLabel ?: stringResource(R.string.e5_link_to_page, b.page + 1) + (if (b.zoom > 0f) " • ${(b.zoom * 100).toInt()}%" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { update(OutlineStore.tree2(list) { OutlineModel.moveUp(it, i) }.first) }) { Icon(Icons.Default.ArrowUpward, null) }
                    IconButton(onClick = { update(OutlineStore.tree2(list) { OutlineModel.moveDown(it, i) }.first) }) { Icon(Icons.Default.ArrowDownward, null) }
                    IconButton(onClick = { update(OutlineStore.tree(list) { OutlineModel.outdent(it, i) }) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, null) }
                    IconButton(onClick = { update(OutlineStore.tree(list) { OutlineModel.indent(it, i) }) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, null) }
                    IconButton(onClick = { update(OutlineStore.tree(list) { OutlineModel.delete(it, i) }) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                }
            }
            PrimaryButton(stringResource(R.string.e5_save_copy), enabled = dirty || doc.changed, icon = Icons.Default.Save) {
                if (dirty) saveOutline { runner.run(processing) { doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "bookmarks") } }
                else runner.run(processing) { doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "bookmarks") }
            }
        }
    }
    if (busy) ProgressDialog(processing)
    editing?.let { idx ->
        val b = list.getOrNull(idx) ?: E5Bookmark(OutlineModel.newKey(), "", 0, list.getOrNull(list.lastIndex)?.level ?: 0)
        BookmarkSheet(b, doc.pageCount, onDismiss = { editing = null }) { nb ->
            editing = null
            if (idx >= 0) update(list.toMutableList().also { it[idx] = nb })
            else update(OutlineStore.tree(list + nb) { OutlineModel.normalize(it) })
        }
    }
    if (showAuto) AutoBookmarksDialog(onDismiss = { showAuto = false }) { levels, ratio, replace ->
        showAuto = false; busy = true
        scope.launch {
            val w = doc.work
            val found = if (w != null) runCatching { OutlineTools.detectHeadings(w, doc.password, levels, ratio) }.getOrDefault(emptyList()) else emptyList()
            busy = false
            if (found.isEmpty()) snack.showSnackbar(noHeadings) else update(if (replace) OutlineTools.toBookmarks(found) else list + OutlineTools.toBookmarks(found))
        }
    }
    if (showToc) TocDialog(onDismiss = { showToc = false }) { title, maxLevel, rtl, arDigits, font ->
        showToc = false
        saveOutline {
            busy = true
            scope.launch {
                runCatching { doc.apply(app.documents.tempDir) { s, o, p -> OutlineTools.insertToc(ctx, s, o, p, OutlineStore.load(s, p).first, title, 0, maxLevel, rtl, arDigits, font) } }
                    .onFailure { snack.showSnackbar(it.message ?: it.toString()) }
                doc.work?.let { w -> list = runCatching { OutlineStore.load(w, doc.password).first }.getOrDefault(list) }
                busy = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkSheet(initial: E5Bookmark, pageCount: Int, onDismiss: () -> Unit, onSave: (E5Bookmark) -> Unit) {
    var b by remember { mutableStateOf(initial) }
    var pageText by remember { mutableStateOf((initial.page + 1).toString()) }
    var zoomText by remember { mutableStateOf(if (initial.zoom > 0f) (initial.zoom * 100).toInt().toString() else "") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(b.title, { b = b.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.e5_bm_name)) })
            if (b.keepAction) Text(stringResource(R.string.e5_bm_action_kept, b.actionLabel ?: ""), style = MaterialTheme.typography.bodySmall)
            else {
                OutlinedTextField(pageText, { pageText = it.filter { c -> c.isDigit() }.take(6) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.e5_link_page_of, pageCount)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                ChoiceChips(DestFit.values().toList(), b.fit, { stringResource(fitLabel(it)) }) { b = b.copy(fit = it) }
                if (b.fit == DestFit.XYZ) OutlinedTextField(zoomText, { zoomText = it.filter { c -> c.isDigit() }.take(4) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.e5_link_zoom)) }, placeholder = { Text(stringResource(R.string.e5_link_zoom_keep)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Text(stringResource(R.string.e5_color))
            ColorChips(BM_COLORS, b.color ?: 0x000000) { b = b.copy(color = if (it == 0x000000) null else it) }
            SwitchRow(stringResource(R.string.e5_bold), b.bold) { b = b.copy(bold = it) }
            SwitchRow(stringResource(R.string.e5_italic), b.italic) { b = b.copy(italic = it) }
            SwitchRow(stringResource(R.string.e5_bm_open), b.open) { b = b.copy(open = it) }
            Row { Spacer(Modifier.weight(1f)); TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(enabled = b.title.isNotBlank(), onClick = {
                    val p = ((pageText.toIntOrNull() ?: 1) - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                    val pageChanged = p != initial.page
                    onSave(b.copy(page = p, zoom = zoomText.toIntOrNull()?.let { it / 100f } ?: 0f, top = if (pageChanged) null else b.top, left = if (pageChanged) null else b.left))
                }) { Text(stringResource(R.string.save)) } }
        }
    }
}

@Composable
private fun AutoBookmarksDialog(onDismiss: () -> Unit, onRun: (Int, Float, Boolean) -> Unit) {
    var levels by remember { mutableIntStateOf(2) }
    var ratio by remember { mutableFloatStateOf(1.15f) }
    var replace by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.e5_bm_auto)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.e5_bm_auto_help), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.e5_bm_levels)); ChoiceChips(listOf(1, 2, 3, 4), levels, { it.toString() }) { levels = it }
            Text(stringResource(R.string.e5_bm_ratio, (ratio * 100).toInt())); Slider(ratio, { ratio = it }, valueRange = 1.05f..1.8f)
            SwitchRow(stringResource(R.string.e5_bm_replace), replace) { replace = it }
        }
    }, confirmButton = { Button(onClick = { onRun(levels, ratio, replace) }) { Text(stringResource(R.string.e5_run)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun TocDialog(onDismiss: () -> Unit, onRun: (String, Int, Boolean, Boolean, E5Font) -> Unit) {
    val def = stringResource(R.string.e5_toc_default_title)
    var title by remember { mutableStateOf(def) }
    var maxLevel by remember { mutableIntStateOf(1) }
    var rtl by remember { mutableStateOf(true) }
    var ar by remember { mutableStateOf(false) }
    var font by remember { mutableStateOf(E5Font.NASKH) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.e5_bm_toc)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.e5_toc_help), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it }, singleLine = true, label = { Text(stringResource(R.string.e5_text)) })
            Text(stringResource(R.string.e5_bm_levels)); ChoiceChips(listOf(0, 1, 2, 3), maxLevel, { (it + 1).toString() }) { maxLevel = it }
            SwitchRow(stringResource(R.string.e5_rtl_layout), rtl) { rtl = it }
            SwitchRow(stringResource(R.string.e5_arabic_digits), ar) { ar = it }
            FontChoice(font) { font = it }
        }
    }, confirmButton = { Button(onClick = { onRun(title, maxLevel, rtl, ar, font) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.e5_insert)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
