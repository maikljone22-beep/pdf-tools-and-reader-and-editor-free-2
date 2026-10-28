package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.BlankPages
import com.smartdocs.editor.core.edit5.PageBoxOps
import com.smartdocs.editor.core.edit5.PageTemplate
import com.smartdocs.editor.core.edit5.PageTemplates
import com.smartdocs.editor.core.edit5.PaperStyle
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.ColorChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.launch

/** Page templates stored in the document (/Names /Pages and /Templates): save, insert copies, show/hide, delete. */
@Composable
fun TemplatesScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var list by remember { mutableStateOf<List<PageTemplate>>(emptyList()) }
    var page by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var hide by remember { mutableStateOf(true) }
    var at by remember { mutableFloatStateOf(1f) }
    var count by remember { mutableFloatStateOf(1f) }
    var busy by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    val processing = stringResource(R.string.processing)
    LaunchedEffect(doc.work, doc.version) { val w = doc.work ?: return@LaunchedEffect; list = runCatching { PageTemplates.list(w, doc.password) }.getOrDefault(emptyList()) }
    fun run(op: suspend (java.io.File, java.io.File, String?) -> Unit) {
        busy = true
        scope.launch { runCatching { doc.apply(app.documents.tempDir, op) }.onFailure { snack.showSnackbar(it.message ?: it.toString()) }; busy = false }
    }
    ToolScaffold(stringResource(R.string.e5_tpl_title), nav, runner, snackbar = snack, actions = {
        IconButton(onClick = { scope.launch { doc.undo() } }, enabled = doc.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.e5_undo)) }
    }) {
        E5FileRow(doc)
        Text(stringResource(R.string.e5_tpl_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val info = doc.infos.getOrNull(page)
        if (doc.work != null && info != null) {
            E5TwoPane(options = {
                E5Section(stringResource(R.string.e5_tpl_save)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.e5_tpl_name)) })
                    SwitchRow(stringResource(R.string.e5_tpl_hide), hide) { hide = it }
                    Button(onClick = { val p = page; val nm = name; val h = hide; run { s, o, pw -> PageTemplates.save(s, o, pw, p, nm, h) }; name = "" }, enabled = doc.pageCount > 1 || !hide) {
                        Text(stringResource(R.string.e5_tpl_save_page, page + 1))
                    }
                }
                E5Section(stringResource(R.string.e5_tpl_list, list.size)) {
                    if (list.isEmpty()) Text(stringResource(R.string.e5_tpl_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (list.isNotEmpty()) {
                        FloatField(at, stringResource(R.string.e5_insert_before_page, doc.pageCount + 1)) { at = it.coerceIn(1f, (doc.pageCount + 1).toFloat()) }
                        FloatField(count, stringResource(R.string.e5_copies)) { count = it.coerceIn(1f, 200f) }
                    }
                    list.forEach { t ->
                        ListItem(headlineContent = { Text(t.name) },
                            supportingContent = { Text(if (t.visible) stringResource(R.string.e5_tpl_visible, t.pageIndex + 1) else stringResource(R.string.e5_tpl_hidden)) },
                            trailingContent = {
                                Row {
                                    // go to a visible template's page, rename, replace with the page shown in the preview
                                    if (t.visible && t.pageIndex >= 0) IconButton(onClick = { page = t.pageIndex }) { Icon(Icons.Default.Pageview, stringResource(R.string.f8_tpl_goto)) }
                                    IconButton(onClick = { renaming = t.name; newName = t.name }) { Icon(Icons.Default.DriveFileRenameOutline, stringResource(R.string.f8_tpl_rename)) }
                                    IconButton(onClick = { val p = page; run { s, o, pw -> PageTemplates.replaceWithPage(s, o, pw, t.name, p) } }) { Icon(Icons.Default.FindReplace, stringResource(R.string.f8_tpl_replace, page + 1)) }
                                    IconButton(onClick = { val a = at.toInt() - 1; val c = count.toInt(); run { s, o, pw -> PageTemplates.instantiate(s, o, pw, t.name, a, c) } }) { Icon(Icons.Default.AddToPhotos, stringResource(R.string.e5_tpl_insert)) }
                                    IconButton(onClick = { run { s, o, pw -> PageTemplates.setVisible(s, o, pw, t.name, !t.visible) } }) { Icon(if (t.visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (t.visible) R.string.pc_cd_hide else R.string.pc_cd_show)) }
                                    IconButton(onClick = { run { s, o, pw -> PageTemplates.delete(s, o, pw, t.name) } }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                                }
                            })
                    }
                }
            }, preview = {
                PageStepper(page, doc.pageCount) { page = it }
                E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth())
            })
            PrimaryButton(stringResource(R.string.e5_save_copy), enabled = doc.changed, icon = Icons.Default.Save) {
                runner.run(processing) { doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "templates") }
            }
        }
    }
    if (busy) ProgressDialog(processing)
    renaming?.let { old ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.f8_tpl_rename)) },
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text(stringResource(R.string.e5_tpl_name)) }) },
            confirmButton = {
                TextButton(onClick = {
                    val nn = newName.trim(); renaming = null
                    if (nn.isNotEmpty() && nn != old) run { s, o, pw -> PageTemplates.rename(s, o, pw, old, nn) }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

fun paperStyleLabel(s: PaperStyle) = when (s) {
    PaperStyle.BLANK -> R.string.e5_ps_blank; PaperStyle.LINED -> R.string.e5_ps_lined; PaperStyle.NARROW -> R.string.e5_ps_narrow; PaperStyle.GRID -> R.string.e5_ps_grid
    PaperStyle.GRAPH_MM -> R.string.e5_ps_graph; PaperStyle.DOTS -> R.string.e5_ps_dots; PaperStyle.ISOMETRIC -> R.string.e5_ps_iso; PaperStyle.MUSIC -> R.string.e5_ps_music; PaperStyle.CORNELL -> R.string.e5_ps_cornell
}

private val LINE_COLORS = listOf(0xB0BEC5, 0x90CAF9, 0xA5D6A7, 0xEF9A9A, 0x9E9E9E, 0x424242)
private val PAPER_COLORS = listOf(0xFFFFFF, 0xFFF8E1, 0xF1F8E9, 0xE3F2FD, 0xFCE4EC, 0xECEFF1)

/** Insert N blank pages (blank, lined, grid, millimetre graph paper, dots, isometric, music staves, Cornell notes). */
@Composable
fun InsertBlankScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    var style by remember { mutableStateOf(PaperStyle.GRAPH_MM) }
    var count by remember { mutableFloatStateOf(1f) }
    var at by remember { mutableFloatStateOf(1f) }
    var paperName by remember { mutableStateOf("") }
    var landscape by remember { mutableStateOf(false) }
    var line by remember { mutableIntStateOf(LINE_COLORS[0]) }
    var paper by remember { mutableIntStateOf(0xFFFFFF) }
    var rtl by remember { mutableStateOf(true) }
    val same = stringResource(R.string.e5_same_size)
    val processing = stringResource(R.string.processing)
    LaunchedEffect(doc.pageCount) { if (doc.pageCount > 0) at = (doc.pageCount + 1).toFloat() }
    ToolScaffold(stringResource(R.string.e5_blank_title), nav, runner) {
        E5FileRow(doc)
        E5Section(stringResource(R.string.e5_paper_style)) {
            ChoiceChips(PaperStyle.values().toList(), style, { stringResource(paperStyleLabel(it)) }) { style = it }
            Text(stringResource(R.string.e5_line_color)); ColorChips(LINE_COLORS, line) { line = it }
            Text(stringResource(R.string.e5_paper_color)); ColorChips(PAPER_COLORS, paper) { paper = it }
            if (style == PaperStyle.NARROW || style == PaperStyle.CORNELL) SwitchRow(stringResource(R.string.e5_rtl_layout), rtl) { rtl = it }
        }
        E5Section(stringResource(R.string.e5_paper)) {
            ChoiceChips(listOf(same) + PageBoxOps.PAPERS.map { it.first }, paperName.ifEmpty { same }, { it }) { paperName = if (it == same) "" else it }
            if (paperName.isNotEmpty()) SwitchRow(stringResource(R.string.e5_landscape), landscape) { landscape = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatField(count, stringResource(R.string.e5_count), Modifier.weight(1f)) { count = it.coerceIn(1f, 500f) }
                FloatField(at, stringResource(R.string.e5_insert_before_page, doc.pageCount + 1), Modifier.weight(1f)) { at = it.coerceIn(1f, (doc.pageCount + 1).toFloat()) }
            }
        }
        PrimaryButton(stringResource(R.string.e5_insert), enabled = doc.work != null, icon = Icons.Default.NoteAdd) {
            val st = style; val c = count.toInt(); val a = at.toInt() - 1; val size = PageBoxOps.PAPERS.firstOrNull { it.first == paperName }?.second
            val ls = landscape; val lc = line; val pc = paper; val r = rtl
            runner.run(processing) {
                doc.apply(app.documents.tempDir) { s, o, p -> BlankPages.insert(s, o, p, a, c, size, ls, st, lc, pc, r) }
                doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "pages")
            }
        }
    }
}
