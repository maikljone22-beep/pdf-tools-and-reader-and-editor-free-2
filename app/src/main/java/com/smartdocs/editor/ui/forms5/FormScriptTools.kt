package com.smartdocs.editor.ui.forms5

import android.content.Intent
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.forms5.AcroJs
import com.smartdocs.editor.core.forms5.DocScripts
import com.smartdocs.editor.core.forms5.FormPrefs
import com.smartdocs.editor.core.forms5.FormScripts
import com.smartdocs.editor.core.formsx.DataFormat
import com.smartdocs.editor.core.formsx.FormData
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.reader.ReaderState
import com.smartdocs.editor.ui.reader.ReaderTool
import com.smartdocs.editor.ui.reader.ToolChip
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class FormDlg { NONE, CONSOLE, DOC_JS, DOC_ACTIONS, ALL_JS, COMB, COMB_CONVERT, CSV_ROW, MAIL }

/**
 * Fill & Sign extras (hook: FillSignToolbar): rectangle, comb text, and the form menu (calculate now, data
 * import / CSV row / e-mail, JavaScript console, document JavaScripts, document actions, all JavaScripts).
 */
@Composable
fun FillSignFormChips(state: ReaderState) {
    val ctx = LocalContext.current
    val fs = remember(state) { FormFill.of(state) }
    var menu by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf(FormDlg.NONE) }
    LaunchedEffect(Unit) { FormPrefs.load(ctx) }

    Box {
        // Fill & Sign rectangle and comb text are pdf-3c's tools (ReaderToolbars: FS_RRECT, FS_COMB)
    ToolChip(Icons.Default.MoreHoriz, stringResource(R.string.f8_form_menu), selected = menu) { menu = true }
        DropdownMenu(menu, { menu = false }) {
            if (!fs.model.isEmpty) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.f8_calc_now)) }, leadingIcon = { Icon(Icons.Default.Calculate, null) },
                    onClick = { menu = false; fs.calculateNow(); fs.message = FormMessage.Calculated })
                DropdownMenuItem(text = { Text(stringResource(R.string.f8_import_data)) }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { menu = false; fs.importPick = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.f8_import_csv_row)) }, leadingIcon = { Icon(Icons.Default.TableRows, null) }, onClick = { menu = false; dlg = FormDlg.CSV_ROW })
                DropdownMenuItem(text = { Text(stringResource(R.string.f8_mail_data)) }, leadingIcon = { Icon(Icons.Default.Email, null) }, onClick = { menu = false; dlg = FormDlg.MAIL })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.f8_js_console)) }, leadingIcon = { Icon(Icons.Default.Terminal, null) }, onClick = { menu = false; dlg = FormDlg.CONSOLE })
            DropdownMenuItem(text = { Text(stringResource(R.string.f8_doc_js)) }, leadingIcon = { Icon(Icons.Default.Code, null) }, onClick = { menu = false; dlg = FormDlg.DOC_JS })
            DropdownMenuItem(text = { Text(stringResource(R.string.f8_doc_actions)) }, leadingIcon = { Icon(Icons.Default.Event, null) }, onClick = { menu = false; dlg = FormDlg.DOC_ACTIONS })
            DropdownMenuItem(text = { Text(stringResource(R.string.f8_all_js)) }, leadingIcon = { Icon(Icons.Default.Functions, null) }, onClick = { menu = false; dlg = FormDlg.ALL_JS })
        }
    }

    val close = { dlg = FormDlg.NONE }
    when (dlg) {
        FormDlg.CONSOLE -> JsConsoleDialog(fs, close)
        FormDlg.DOC_JS -> DocJsDialog(state, close)
        FormDlg.DOC_ACTIONS -> DocActionsDialog(state, close)
        FormDlg.ALL_JS -> AllJsDialog(state, close)
        FormDlg.COMB -> CombDialog(state, close)
        FormDlg.COMB_CONVERT -> CombConvertDialog(state, close)
        FormDlg.CSV_ROW -> CsvRowDialog(fs, close)
        FormDlg.MAIL -> MailDataDialog(fs, close)
        FormDlg.NONE -> {}
    }
}

// ====================================================================== JavaScript

/** Load [state]'s document read-only. */
private suspend fun <T> readDoc(state: ReaderState, block: (PDDocument) -> T): T? = withContext(Dispatchers.IO) {
    val f = state.file ?: return@withContext null
    runCatching { PdfOps.load(f, state.password).use(block) }.getOrNull()
}

/** Change the document's scripts in place (pending changes saved first; one undo step). */
private suspend fun writeDoc(state: ReaderState, change: (PDDocument) -> Unit): Boolean = runCatching {
    state.applyInPlace(label = { state.context.getString(R.string.f8_hi_scripts) }) { src, out, pw ->
        withContext(Dispatchers.IO) { PdfOps.load(src, pw).use { d -> change(d); d.save(out) } }
    } != null
}.getOrElse { state.error = it.message ?: it.toString(); false }

/** Syntax check on a throw-away runtime (null = fine, else the error). */
private fun syntaxError(code: String): String? = runCatching {
    if (code.isBlank()) return null
    val js = AcroJs("[]", emptyList())
    if (js.compiles(code)) null else "syntax"
}.getOrElse { it.message ?: it.toString() }

@Composable
private fun ScriptEditor(title: String, initial: String, onDismiss: () -> Unit, onDelete: (() -> Unit)?, nameField: Boolean = false, onSave: (name: String, code: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(initial) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (nameField) OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.f8_script_name)) }, singleLine = true)
                OutlinedTextField(code, { code = it; err = null }, Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), label = { Text("JavaScript") })
                err?.let { Text(stringResource(R.string.f8_syntax_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val e = syntaxError(code)
                if (e != null && err == null) { err = e; return@TextButton }      // a second tap saves anyway
                if (nameField && name.isBlank()) return@TextButton
                onSave(name.trim(), code)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun JsConsoleDialog(fs: FormFillState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    // a runtime for documents without field scripts (the console works on every form)
    val own = remember(fs.model) { if (fs.scripts == null) runCatching { FormScripts(fs.model.js.copy(hasActionJs = true)) }.getOrNull() else null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.f8_js_console)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Text(fs.consoleLog.joinToString("\n").ifEmpty { stringResource(R.string.f8_console_hint) },
                        Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
                OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("JavaScript") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val src = code.trim(); if (src.isEmpty()) return@TextButton
                val sc = fs.scripts ?: own
                if (sc == null) { fs.consoleLog += "! " + "JavaScript runtime unavailable"; return@TextButton }
                fs.consoleLog += "> $src"
                // a single expression prints its value, like Acrobat's console
                val single = !src.contains('\n') && !src.contains(';') && !src.startsWith("var ") && !src.startsWith("for") && !src.startsWith("if")
                val run = if (single) "event.value = String($src);" else src
                scope.launch {
                    val r = withContext(Dispatchers.Default) { sc.action(null, "Console", run, fs.currentValues(), fs.reader.currentPage) }
                    sc.drainConsole().forEach { fs.consoleLog += it }
                    if (r == null) return@launch
                    if (!r.ok) fs.consoleLog += "! " + (r.error ?: "error")
                    else if (single) fs.consoleLog += r.value
                    fs.applyActionResult(r)
                    FormActions.handleRequests(fs, null, r.requests)
                    while (fs.consoleLog.size > 300) fs.consoleLog.removeAt(0)
                }
                code = ""
            }) { Text(stringResource(R.string.f8_run)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { fs.consoleLog.clear() }) { Text(stringResource(R.string.f8_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}

@Composable
private fun DocJsDialog(state: ReaderState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var version by remember { mutableStateOf(0) }
    var edit by remember { mutableStateOf<Pair<String, String>?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(version) { list = readDoc(state) { DocScripts.named(it) }.orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.f8_doc_js)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.f8_doc_js_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val l = list
                if (l != null && l.isEmpty()) Text(stringResource(R.string.f8_none), Modifier.padding(vertical = 8.dp))
                l?.forEach { (n, c) ->
                    ListItem(headlineContent = { Text(n) }, supportingContent = { Text(c.lineSequence().firstOrNull().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { edit = n to c })
                }
            }
        },
        confirmButton = { TextButton(onClick = { adding = true }) { Text(stringResource(R.string.f8_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
    edit?.let { (n, c) ->
        ScriptEditor(n, c, onDismiss = { edit = null },
            onDelete = { edit = null; scope.launch { if (writeDoc(state) { DocScripts.removeNamed(it, n) }) version++ } }) { _, code ->
            edit = null; scope.launch { if (writeDoc(state) { DocScripts.putNamed(it, n, code) }) version++ }
        }
    }
    if (adding) ScriptEditor(stringResource(R.string.f8_add), "", onDismiss = { adding = false }, onDelete = null, nameField = true) { name, code ->
        adding = false; scope.launch { if (writeDoc(state) { DocScripts.putNamed(it, name, code) }) version++ }
    }
}

@Composable
private fun docActionLabel(k: String): String = stringResource(when (k) {
    "WC" -> R.string.f8_da_wc; "WS" -> R.string.f8_da_ws; "DS" -> R.string.f8_da_ds; "WP" -> R.string.f8_da_wp; else -> R.string.f8_da_dp
})

@Composable
private fun DocActionsDialog(state: ReaderState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var codes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var version by remember { mutableStateOf(0) }
    var edit by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(version) { codes = readDoc(state) { d -> DocScripts.listAll(d).filter { it.kind == "doc" }.associate { it.label to it.code } }.orEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.f8_doc_actions)) },
        text = {
            Column {
                DocScripts.DOC_ACTIONS.forEach { k ->
                    ListItem(headlineContent = { Text(docActionLabel(k)) },
                        supportingContent = { Text(codes[k]?.lineSequence()?.firstOrNull() ?: stringResource(R.string.f8_none), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { edit = k })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
    edit?.let { k ->
        ScriptEditor(docActionLabel(k), codes[k].orEmpty(), onDismiss = { edit = null },
            onDelete = if (codes[k] != null) ({ edit = null; scope.launch { if (writeDoc(state) { DocScripts.setCode(it, "doc:$k", "") }) version++ } }) else null) { _, code ->
            edit = null; scope.launch { if (writeDoc(state) { DocScripts.setCode(it, "doc:$k", code) }) version++ }
        }
    }
}

private data class Target(val locator: String, val label: String)

@Composable
private fun AllJsDialog(state: ReaderState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<DocScripts.Script>?>(null) }
    var targets by remember { mutableStateOf<List<Target>>(emptyList()) }
    var version by remember { mutableStateOf(0) }
    var edit by remember { mutableStateOf<Pair<String, String>?>(null) }      // locator, code
    var addMenu by remember { mutableStateOf(false) }
    val page = state.currentPage
    val pageOpen = stringResource(R.string.f8_page_open, page + 1)
    val pageClose = stringResource(R.string.f8_page_close, page + 1)
    LaunchedEffect(version) {
        readDoc(state) { d ->
            val list = DocScripts.listAll(d)
            val t = ArrayList<Target>()
            t += Target("page:$page:O", pageOpen); t += Target("page:$page:C", pageClose)
            DocScripts.outlines(d).forEachIndexed { i, o -> t += Target("outline:$i", "⚑ " + (o.getString(com.tom_roush.pdfbox.cos.COSName.getPDFName("Title")) ?: "#$i")) }
            if (page in 0 until d.numberOfPages) runCatching { d.getPage(page).annotations }.getOrNull()?.forEachIndexed { ai, an ->
                if (an.subtype == "Link") t += Target("link:$page:$ai", "↪ " + stringResource0(state, R.string.f8_link_n, ai + 1))
            }
            list to t
        }?.let { (l, t) -> all = l; targets = t }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.f8_all_js)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                val l = all
                if (l != null && l.isEmpty()) Text(stringResource(R.string.f8_none), Modifier.padding(vertical = 8.dp))
                l?.forEach { s ->
                    ListItem(overlineContent = { Text(scriptKind(s.kind) + if (s.page >= 0) " · " + stringResource(R.string.f8_page_n, s.page + 1) else "") },
                        headlineContent = { Text(s.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(s.code.lineSequence().firstOrNull().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace) },
                        modifier = Modifier.clickable { edit = s.locator to s.code })
                }
            }
        },
        confirmButton = {
            Box {
                TextButton(onClick = { addMenu = true }) { Text(stringResource(R.string.f8_attach)) }
                DropdownMenu(addMenu, { addMenu = false }) {
                    targets.forEach { t -> DropdownMenuItem(text = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { addMenu = false; edit = t.locator to (all?.firstOrNull { it.locator == t.locator }?.code ?: "") }) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
    edit?.let { (loc, c) ->
        ScriptEditor(loc, c, onDismiss = { edit = null },
            onDelete = if (c.isNotEmpty()) ({ edit = null; scope.launch { if (writeDoc(state) { DocScripts.setCode(it, loc, "") }) version++ } }) else null) { _, code ->
            edit = null; scope.launch { if (writeDoc(state) { DocScripts.setCode(it, loc, code) }) version++ }
        }
    }
}

private fun stringResource0(state: ReaderState, id: Int, vararg args: Any): String = state.context.getString(id, *args)

@Composable
private fun scriptKind(k: String): String = stringResource(when (k) {
    "named" -> R.string.f8_kind_named; "doc" -> R.string.f8_kind_doc; "open" -> R.string.f8_kind_open; "page" -> R.string.f8_kind_page
    "field" -> R.string.f8_kind_field; "widget" -> R.string.f8_kind_widget; "outline" -> R.string.f8_kind_outline; else -> R.string.f8_kind_link
})

// ====================================================================== data

@Composable
private fun CsvRowDialog(fs: FormFillState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Map<String, String>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) { onDismiss(); return@rememberLauncherForActivityResult }
        scope.launch {
            val f = copyPicked(ctx, u, fs.reader.app.documents.tempDir)
            rows = f?.let { withContext(Dispatchers.IO) { runCatching { FormData.parseTable(it.readBytes(), if (it.name.endsWith(".txt", true) || it.name.endsWith(".tsv", true)) '\t' else ',') }.getOrNull() } }.orEmpty()
            f?.delete()
        }
    }
    LaunchedEffect(Unit) { runCatching { picker.launch(arrayOf("text/*", "*/*")) }.onFailure { onDismiss() } }
    val r = rows ?: return
    val names = fs.model.byName.keys
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.f8_import_csv_row)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (r.isEmpty()) Text(stringResource(R.string.f8_none))
                r.forEachIndexed { i, row ->
                    val matched = row.keys.count { it in names }
                    ListItem(headlineContent = { Text(stringResource(R.string.f8_row_n, i + 1)) },
                        supportingContent = { Text(row.values.take(4).joinToString(" · ") + " (" + stringResource(R.string.f8_matched, matched) + ")", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable {
                            val v = FormActions.toFillValues(fs, row)
                            fs.setValues(v); fs.message = FormMessage.Imported(v.size); onDismiss()
                        })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MailDataDialog(fs: FormFillState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var fmt by remember { mutableStateOf(DataFormat.FDF) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.f8_mail_data)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(DataFormat.FDF, DataFormat.XFDF, DataFormat.XML, DataFormat.CSV).forEach { f ->
                    FilterChip(fmt == f, { fmt = f }, label = { Text(f.ext.uppercase()) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val f = fmt; onDismiss()
                scope.launch { mailData(fs, f) }
            }) { Text(stringResource(R.string.f8_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The filled values (pending ones included) as an FDF / XFDF / XML / CSV attachment for an e-mail or share target. */
private suspend fun mailData(fs: FormFillState, fmt: DataFormat) {
    val r = fs.reader
    val file = r.file ?: return
    val out = runCatching {
        val src = fs.exportSource(file, r.password)
        withContext(Dispatchers.IO) {
            val o = File(r.app.documents.tempDir, file.nameWithoutExtension + "_data." + fmt.ext)
            try { PdfOps.load(src, r.password).use { d -> o.outputStream().use { s -> FormData.export(d, fmt, s, file.name) } } } finally { if (src != file) src.delete() }
            o
        }
    }.getOrElse { fs.message = FormMessage.Failed(it.message ?: it.toString()); return }
    runCatching {
        val send = r.app.documents.shareIntent(listOf(out), fmt.mime).apply { putExtra(Intent.EXTRA_SUBJECT, file.name) }
        r.context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }.onFailure { fs.message = FormMessage.Failed(it.message ?: it.toString()) }
}

// ====================================================================== comb text (Fill & Sign)

/** Characters of [text] spread over [cells] boxes of [cell] points from ([x], [top]) — FreeText per character. */
internal fun combAnnots(state: ReaderState, page: Int, text: String, cells: Int, cell: Float, x: Float, top: Float, boxes: Boolean): List<com.smartdocs.editor.core.pdf.Annot> {
    val gid = System.nanoTime()
    val h = cell * 1.25f
    val chars = text.codePoints().toArray().map { String(Character.toChars(it)) }.take(cells)
    val out = ArrayList<com.smartdocs.editor.core.pdf.Annot>()
    var id = System.nanoTime()
    for (i in 0 until cells) {
        val l = x + i * cell
        if (boxes) out += state.newAnnot(AnnotKind.RECT, page, rects = listOf(RectF(l, top, l + cell, top - h))).copy(id = id++, groupId = gid, strokeWidth = 0.75f)
        val ch = chars.getOrNull(i) ?: continue
        if (ch.isBlank()) continue
        out += state.newAnnot(AnnotKind.FREETEXT, page, rects = listOf(RectF(l + cell * 0.18f, top, l + cell, top - h)), text = ch, fontSize = (cell * 0.62f).coerceIn(6f, 48f))
            .copy(id = id++, groupId = gid)
    }
    return out
}

@Composable
private fun CombDialog(state: ReaderState, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var cells by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("18") }
    var boxes by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.f8_fs_comb)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.f8_comb_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(text, { text = it.replace("\n", "") }, label = { Text(stringResource(R.string.f8_comb_text)) }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(cells, { cells = it.filter { c -> c.isDigit() }.take(3) }, Modifier.weight(1f), label = { Text(stringResource(R.string.f8_comb_cells)) }, singleLine = true)
                    OutlinedTextField(width, { width = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, Modifier.weight(1f), label = { Text(stringResource(R.string.f8_comb_width)) }, singleLine = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(boxes, { boxes = it }); Text(stringResource(R.string.f8_comb_boxes), Modifier.padding(start = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = (cells.toIntOrNull() ?: text.codePointCount(0, text.length)).coerceIn(1, 200)
                val cw = (width.toFloatOrNull() ?: 18f).coerceIn(6f, 72f)
                val page = state.currentPage
                val (pw, ph) = state.pageSizes[page] ?: (595f to 842f)
                val x = ((pw - n * cw) / 2f).coerceAtLeast(8f)
                val top = ph / 2f + cw
                val list = combAnnots(state, page, text, n, cw, x, top, boxes)
                if (list.isNotEmpty()) state.batchAnnots { it.addAll(list) }
                onDismiss()
            }) { Text(stringResource(R.string.f8_place)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Comb groups and single texts of the current page, to convert comb <-> text. */
@Composable
private fun CombConvertDialog(state: ReaderState, onDismiss: () -> Unit) {
    val page = state.currentPage
    val texts = state.annots.filter { it.page == page && it.kind == AnnotKind.FREETEXT }
    val combs = texts.filter { it.groupId != null && it.text.codePointCount(0, it.text.length) == 1 }.groupBy { it.groupId!! }.filter { it.value.isNotEmpty() }
    val singles = texts.filter { it.groupId == null && it.text.isNotBlank() && !it.text.contains('\n') }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.f8_comb_convert)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (combs.isEmpty() && singles.isEmpty()) Text(stringResource(R.string.f8_comb_none))
                combs.forEach { (gid, members) ->
                    val joined = members.sortedBy { it.rects.firstOrNull()?.left ?: 0f }.joinToString("") { it.text }
                    ListItem(overlineContent = { Text(stringResource(R.string.f8_comb_to_text)) }, headlineContent = { Text(joined) }, modifier = Modifier.clickable {
                        val all = state.annots.filter { it.groupId == gid }
                        val l = all.minOf { a -> a.rects.minOfOrNull { minOf(it.left, it.right) } ?: Float.MAX_VALUE }
                        val r = all.maxOf { a -> a.rects.maxOfOrNull { maxOf(it.left, it.right) } ?: 0f }
                        val t = all.maxOf { a -> a.rects.maxOfOrNull { maxOf(it.top, it.bottom) } ?: 0f }
                        val b = all.minOf { a -> a.rects.minOfOrNull { minOf(it.top, it.bottom) } ?: Float.MAX_VALUE }
                        val first = members.first()
                        state.batchAnnots { list ->
                            list.removeAll { it.groupId == gid }
                            list += first.copy(id = System.nanoTime(), groupId = null, text = joined, rects = listOf(RectF(l, t, r, b)), nm = null)
                        }
                        onDismiss()
                    })
                }
                singles.forEach { a ->
                    ListItem(overlineContent = { Text(stringResource(R.string.f8_text_to_comb)) }, headlineContent = { Text(a.text) }, modifier = Modifier.clickable {
                        val rect = a.rects.firstOrNull() ?: return@clickable
                        val n = a.text.codePointCount(0, a.text.length).coerceAtLeast(1)
                        val l = minOf(rect.left, rect.right); val t = maxOf(rect.top, rect.bottom)
                        val cw = (kotlin.math.abs(rect.right - rect.left) / n).coerceIn(6f, 72f)
                        val made = combAnnots(state, a.page, a.text, n, cw, l, t, boxes = true)
                        state.batchAnnots { list -> list.removeAll { it.id == a.id }; list.addAll(made) }
                        onDismiss()
                    })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
