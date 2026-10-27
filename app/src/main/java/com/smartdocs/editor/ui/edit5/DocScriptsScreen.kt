package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.DocJsConsole
import com.smartdocs.editor.core.edit5.DocJsStore
import com.smartdocs.editor.core.edit5.DocScripts
import com.smartdocs.editor.core.edit5.DocTrigger
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.launch

/**
 * Document JavaScript (Nitro "JavaScript" group): document-level scripts / functions (new, edit, rename, delete),
 * document actions (will close, will save, did save, will print, did print) and a console that runs a script in the
 * form sandbox against the open document. Scripts are only stored here; whether a document's scripts run is the
 * "Run JavaScript" setting, which the console also obeys.
 */
@Composable
fun DocScriptsScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var data by remember { mutableStateOf(DocScripts()) }
    var dirty by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }      // (title key, source)
    var editIndex by remember { mutableIntStateOf(-1) }                        // script index, -1 = new, -2 = trigger
    var editTrigger by remember { mutableStateOf<DocTrigger?>(null) }
    var busy by remember { mutableStateOf(false) }
    val processing = stringResource(R.string.processing)
    LaunchedEffect(doc.work, doc.version) {
        val w = doc.work ?: return@LaunchedEffect
        if (!dirty) data = runCatching { DocJsStore.load(w, doc.password) }.getOrDefault(DocScripts())
    }
    fun update(d: DocScripts) { data = d; dirty = true }
    fun save(then: (() -> Unit)? = null) {
        val d = data; busy = true
        scope.launch {
            runCatching { doc.apply(app.documents.tempDir) { s, o, p -> DocJsStore.save(s, o, p, d) }; dirty = false }.onFailure { snack.showSnackbar(it.message ?: it.toString()) }
            busy = false; then?.invoke()
        }
    }
    ToolScaffold(stringResource(R.string.nbm_js_title), nav, runner, snackbar = snack, actions = {
        IconButton(onClick = { scope.launch { doc.undo(); dirty = false } }, enabled = doc.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.e5_undo)) }
    }) {
        E5FileRow(doc)
        if (doc.work != null) {
            if (!LocalSettings.current.runJavaScript) Text(stringResource(R.string.nbm_js_off_note), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TabRow(tab) {
                listOf(R.string.nbm_js_tab_scripts, R.string.nbm_js_tab_actions, R.string.nbm_js_tab_console).forEachIndexed { i, t ->
                    Tab(tab == i, { tab = i }, text = { Text(stringResource(t)) })
                }
            }
            when (tab) {
                0 -> {
                    Text(stringResource(R.string.nbm_js_scripts_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { editIndex = -1; editing = "" to "function myFunction() {\n  \n}\n" }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nbm_js_new)) }
                    if (data.scripts.isEmpty()) Text(stringResource(R.string.nbm_js_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    data.scripts.forEachIndexed { i, (name, src) ->
                        ListItem(headlineContent = { Text(name) }, supportingContent = { Text(stringResource(R.string.nbm_js_lines, src.lines().size), style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { Icon(Icons.Default.Code, null) },
                            trailingContent = { IconButton(onClick = { update(data.copy(scripts = data.scripts.filterIndexed { k, _ -> k != i })) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } },
                            modifier = Modifier.clickable { editIndex = i; editing = name to src })
                    }
                }
                1 -> {
                    Text(stringResource(R.string.nbm_js_actions_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DocTrigger.values().forEach { t ->
                        val src = data.triggers[t].orEmpty()
                        ListItem(headlineContent = { Text(stringResource(triggerLabel(t))) },
                            supportingContent = { Text(if (src.isBlank()) stringResource(R.string.nbm_js_no_script) else src.lineSequence().first().take(60), style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { Icon(Icons.Default.Bolt, null) },
                            trailingContent = { if (src.isNotBlank()) IconButton(onClick = { update(data.copy(triggers = data.triggers - t)) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } },
                            modifier = Modifier.clickable { editIndex = -2; editTrigger = t; editing = t.key to src })
                    }
                }
                else -> ConsolePane(doc, dirty) { save(it) }
            }
            PrimaryButton(stringResource(R.string.e5_save_copy), enabled = dirty || doc.changed, icon = Icons.Default.Save) {
                if (dirty) save { runner.run(processing) { doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "scripts") } }
                else runner.run(processing) { doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "scripts") }
            }
        }
    }
    if (busy) com.smartdocs.editor.ui.common.ProgressDialog(processing)
    editing?.let { (name, src) ->
        val trig = editTrigger.takeIf { editIndex == -2 }
        ScriptEditorDialog(if (trig != null) stringResource(triggerLabel(trig)) else null, name, src,
            taken = data.scripts.mapIndexedNotNull { k, p -> p.first.takeIf { k != editIndex } }.toSet(),
            onDismiss = { editing = null; editTrigger = null }) { n, s ->
            editing = null
            when {
                trig != null -> update(data.copy(triggers = data.triggers + (trig to s)))
                editIndex >= 0 -> update(data.copy(scripts = data.scripts.toMutableList().also { it[editIndex] = n to s }))
                else -> update(data.copy(scripts = data.scripts + (n to s)))
            }
            editTrigger = null
        }
    }
}

private fun triggerLabel(t: DocTrigger) = when (t) {
    DocTrigger.WILL_CLOSE -> R.string.nbm_trig_wc; DocTrigger.WILL_SAVE -> R.string.nbm_trig_ws; DocTrigger.DID_SAVE -> R.string.nbm_trig_ds
    DocTrigger.WILL_PRINT -> R.string.nbm_trig_wp; DocTrigger.DID_PRINT -> R.string.nbm_trig_dp
}

/**
 * Code editor: name (document scripts), monospace source with line count, "Go to line", "Check syntax" (compile
 * only, nothing runs) and Clear. [fixedTitle] non-null = a trigger (no name field).
 */
@Composable
private fun ScriptEditorDialog(fixedTitle: String?, name0: String, src0: String, taken: Set<String>, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(name0) }
    var code by remember { mutableStateOf(TextFieldValue(src0)) }
    var line by remember { mutableStateOf("") }
    var check by remember { mutableStateOf<String?>(null) }
    val ok = stringResource(R.string.nbm_js_syntax_ok)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(fixedTitle ?: stringResource(R.string.nbm_js_edit)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fixedTitle == null) OutlinedTextField(name, { name = it.filter { c -> !c.isISOControl() } }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.nbm_js_name)) }, isError = name.trim() in taken, supportingText = { if (name.trim() in taken) Text(stringResource(R.string.nbm_dest_taken)) })
            OutlinedTextField(code, { code = it; check = null }, Modifier.fillMaxWidth().heightIn(min = 220.dp), label = { Text(stringResource(R.string.nbm_js_code)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            val lines = code.text.count { it == '\n' } + 1
            val cur = code.text.substring(0, code.selection.start.coerceIn(0, code.text.length)).count { it == '\n' } + 1
            Text(stringResource(R.string.nbm_js_line_of, cur, lines), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(line, { line = it.filter(Char::isDigit).take(5) }, Modifier.width(96.dp), singleLine = true, label = { Text(stringResource(R.string.nbm_js_line)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                TextButton(onClick = { line.toIntOrNull()?.let { n -> val o = DocJsStore.lineOffset(code.text, n); code = code.copy(selection = TextRange(o)) } }) { Text(stringResource(R.string.nbm_js_goto_line)) }
                TextButton(onClick = { check = DocJsStore.syntaxError(code.text) ?: ok }) { Text(stringResource(R.string.nbm_js_check)) }
                TextButton(onClick = { code = TextFieldValue(""); check = null }) { Text(stringResource(R.string.nbm_clear)) }
            }
            check?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it == ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        Button(enabled = fixedTitle != null || (name.isNotBlank() && name.trim() !in taken), onClick = { onSave(name.trim(), code.text) }) { Text(stringResource(R.string.save)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Console: script input, Execute (with the document's fields and document scripts), Clear, output with errors. */
@Composable
private fun ConsolePane(doc: E5Doc, dirty: Boolean, saveFirst: ((() -> Unit)?) -> Unit) {
    val scope = rememberCoroutineScope()
    val jsOn = LocalSettings.current.runJavaScript
    var input by remember { mutableStateOf("console.println(this.numPages);") }
    val out = remember { mutableStateListOf<Pair<Boolean, String>>() }    // (is error, text)
    var running by remember { mutableStateOf(false) }
    val off = stringResource(R.string.nbm_js_console_off)
    val noValue = stringResource(R.string.nbm_js_no_value)
    Text(stringResource(R.string.nbm_js_console_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text(stringResource(R.string.nbm_js_code)) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = jsOn && !running && input.isNotBlank(), onClick = {
            fun go() {
                val w = doc.work ?: return
                running = true
                scope.launch {
                    runCatching { DocJsConsole.run(w, doc.password, input) }
                        .onSuccess { r ->
                            r.docErrors.forEach { out += true to it }
                            r.log.forEach { out += false to it }
                            r.alerts.forEach { out += false to "alert: $it" }
                            if (r.error != null) out += true to r.error else out += false to (r.value ?: noValue)
                        }
                        .onFailure { out += true to (if (it is DocJsConsole.Disabled) off else it.message ?: it.toString()) }
                    running = false
                }
            }
            // run against the scripts as edited (stored first)
            if (dirty) saveFirst { go() } else go()
        }) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nbm_js_execute)) }
        OutlinedButton(onClick = { out.clear() }) { Text(stringResource(R.string.nbm_clear)) }
    }
    if (!jsOn) Text(off, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp).heightIn(min = 80.dp)) {
            if (out.isEmpty()) Text(stringResource(R.string.nbm_js_output), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            out.forEach { (err, t) -> Text(t, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
        }
    }
}
