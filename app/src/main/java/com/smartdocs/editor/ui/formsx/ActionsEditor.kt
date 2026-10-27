package com.smartdocs.editor.ui.formsx

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.formsx.*

private val TRIGGERS = listOf(FxTrigger.MOUSE_UP to R.string.fx_tr_up, FxTrigger.MOUSE_DOWN to R.string.fx_tr_down, FxTrigger.ENTER to R.string.fx_tr_enter,
    FxTrigger.EXIT to R.string.fx_tr_exit, FxTrigger.FOCUS to R.string.fx_tr_focus, FxTrigger.BLUR to R.string.fx_tr_blur)

private enum class AKind(val label: Int) {
    GOTO(R.string.fx_act_goto), NAMED(R.string.fx_act_named), REMOTE(R.string.fx_act_remote), URL(R.string.fx_act_url), SUBMIT(R.string.fx_act_submit),
    RESET(R.string.fx_act_reset), SHOWHIDE(R.string.fx_act_showhide), JS(R.string.fx_act_js), MENU(R.string.fx_act_menu), IMPORT(R.string.fx_act_import)
}

private fun kindOf(a: FxAction): AKind? = when (a) {
    is FxAction.GoToPage -> AKind.GOTO; is FxAction.GoToNamed -> AKind.NAMED; is FxAction.GoToRemote -> AKind.REMOTE; is FxAction.OpenUrl -> AKind.URL
    is FxAction.Submit -> AKind.SUBMIT; is FxAction.Reset -> AKind.RESET; is FxAction.ShowHide -> AKind.SHOWHIDE; is FxAction.JavaScript -> AKind.JS
    is FxAction.Named -> AKind.MENU; is FxAction.ImportData -> AKind.IMPORT; is FxAction.Raw -> null
}

private fun blank(k: AKind): FxAction = when (k) {
    AKind.GOTO -> FxAction.GoToPage(0); AKind.NAMED -> FxAction.GoToNamed(""); AKind.REMOTE -> FxAction.GoToRemote("", 0); AKind.URL -> FxAction.OpenUrl("https://")
    AKind.SUBMIT -> FxAction.Submit("https://"); AKind.RESET -> FxAction.Reset(); AKind.SHOWHIDE -> FxAction.ShowHide(emptyList(), true)
    AKind.JS -> FxAction.JavaScript(""); AKind.MENU -> FxAction.Named("Print"); AKind.IMPORT -> FxAction.ImportData("")
}

/** Named actions offered as "Execute a menu item". */
private val MENU_ITEMS = listOf("Print", "NextPage", "PrevPage", "FirstPage", "LastPage", "GoBack", "GoForward", "Find", "FullScreen", "SaveAs", "Close")

@Composable
private fun summary(a: FxAction): String = when (a) {
    is FxAction.GoToPage -> stringResource(R.string.fx_act_goto) + " " + (a.page + 1)
    is FxAction.GoToNamed -> stringResource(R.string.fx_act_named) + ": " + a.dest
    is FxAction.GoToRemote -> a.file + " #" + (a.page + 1)
    is FxAction.OpenUrl -> a.url
    is FxAction.Submit -> stringResource(R.string.fx_act_submit) + " (" + a.format.name + "): " + a.url
    is FxAction.Reset -> stringResource(R.string.fx_act_reset) + if (a.fields.isEmpty()) "" else ": " + a.fields.joinToString(", ")
    is FxAction.ShowHide -> stringResource(if (a.hide) R.string.fx_hide else R.string.fx_show) + ": " + a.fields.joinToString(", ")
    is FxAction.JavaScript -> "JS: " + a.code.take(60)
    is FxAction.Named -> stringResource(R.string.fx_act_menu) + ": " + a.name
    is FxAction.ImportData -> stringResource(R.string.fx_act_import) + ": " + a.file
    is FxAction.Raw -> stringResource(R.string.fx_act_other) + " (" + a.subtype + ")"
}

/** Actions tab: a trigger, its ordered list (up / down / edit / delete) and "add action". */
@Composable
internal fun ActionsTab(s: FieldSpec, names: List<String>, pageCount: Int, set: (FieldSpec) -> Unit) {
    var trigger by remember { mutableStateOf(FxTrigger.MOUSE_UP) }
    var editing by remember { mutableStateOf<Pair<Int, FxAction>?>(null) }
    var adding by remember { mutableStateOf(false) }
    val list = s.actions[trigger].orEmpty()
    fun setList(l: List<FxAction>) = set(s.copy(actions = s.actions.toMutableMap().apply { if (l.isEmpty()) remove(trigger) else put(trigger, l) }))
    Text(stringResource(R.string.fx_trigger), style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TRIGGERS.forEach { (t, l) ->
            val n = s.actions[t].orEmpty().size
            FilterChip(trigger == t, { trigger = t }, label = { Text(stringResource(l) + if (n > 0) " ($n)" else "") })
        }
    }
    if (list.isEmpty()) Text(stringResource(R.string.fx_no_actions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    list.forEachIndexed { i, a ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(summary(a), Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { setList(list.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.fx_up)) }
                IconButton(onClick = { setList(list.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < list.size - 1) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.fx_down)) }
                if (a !is FxAction.Raw) IconButton(onClick = { editing = i to a }) { Icon(Icons.Default.Edit, stringResource(R.string.fx_edit)) }
                IconButton(onClick = { setList(list.filterIndexed { k, _ -> k != i }) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
            }
        }
    }
    Box {
        FilledTonalButton(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.fx_add_action)) }
        DropdownMenu(adding, { adding = false }) {
            AKind.entries.forEach { k -> DropdownMenuItem(text = { Text(stringResource(k.label)) }, onClick = { adding = false; editing = -1 to blank(k) }) }
        }
    }
    editing?.let { (idx, a) ->
        ActionDialog(a, names, pageCount, onDismiss = { editing = null }) { na ->
            setList(if (idx < 0) list + na else list.toMutableList().also { it[idx] = na })
            editing = null
        }
    }
}

@Composable
private fun ActionDialog(initial: FxAction, names: List<String>, pageCount: Int, onDismiss: () -> Unit, onOk: (FxAction) -> Unit) {
    var a by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(kindOf(initial)?.label ?: R.string.fx_act_other)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val x = a) {
                    is FxAction.GoToPage -> {
                        var p by remember { mutableStateOf((x.page + 1).toString()) }
                        var z by remember { mutableStateOf(x.zoom?.toString() ?: "") }
                        NumField(R.string.fx_page_n, p) { v -> p = v; v.toIntOrNull()?.let { a = x.copy(page = (it - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))) } }
                        NumField(R.string.fx_zoom, z) { v -> z = v; a = x.copy(zoom = v.toFloatOrNull()?.div(100f)) }
                    }
                    is FxAction.GoToNamed -> OutlinedTextField(x.dest, { a = x.copy(dest = it) }, label = { Text(stringResource(R.string.fx_dest_name)) }, singleLine = true)
                    is FxAction.GoToRemote -> {
                        OutlinedTextField(x.file, { a = x.copy(file = it) }, label = { Text(stringResource(R.string.fx_file)) }, singleLine = true)
                        var p by remember { mutableStateOf((x.page + 1).toString()) }
                        NumField(R.string.fx_page_n, p) { v -> p = v; v.toIntOrNull()?.let { a = x.copy(page = (it - 1).coerceAtLeast(0)) } }
                    }
                    is FxAction.OpenUrl -> OutlinedTextField(x.url, { a = x.copy(url = it.trim()) }, label = { Text(stringResource(R.string.fx_url)) }, singleLine = true)
                    is FxAction.Submit -> {
                        OutlinedTextField(x.url, { a = x.copy(url = it.trim()) }, label = { Text(stringResource(R.string.fx_url)) }, singleLine = true)
                        Text(stringResource(R.string.fx_submit_fmt), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FxSubmitFormat.entries.forEach { f -> FilterChip(x.format == f, { a = x.copy(format = f) }, label = { Text(f.name) }) }
                        }
                        FieldPicker(R.string.fx_fields_list, names, x.fields) { a = x.copy(fields = it) }
                        if (x.fields.isNotEmpty()) SwitchRow(R.string.fx_exclude, x.exclude) { a = x.copy(exclude = it) }
                        SwitchRow(R.string.fx_include_empty, x.includeEmpty) { a = x.copy(includeEmpty = it) }
                        SwitchRow(R.string.fx_canonical, x.canonicalDates) { a = x.copy(canonicalDates = it) }
                        if (x.format == FxSubmitFormat.FDF) SwitchRow(R.string.fx_include_annots, x.includeAnnots) { a = x.copy(includeAnnots = it) }
                        if (x.format == FxSubmitFormat.HTML) SwitchRow(R.string.fx_get, x.get) { a = x.copy(get = it) }
                    }
                    is FxAction.Reset -> {
                        FieldPicker(R.string.fx_fields_list, names, x.fields) { a = x.copy(fields = it) }
                        if (x.fields.isNotEmpty()) SwitchRow(R.string.fx_exclude, x.exclude) { a = x.copy(exclude = it) }
                    }
                    is FxAction.ShowHide -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(x.hide, { a = x.copy(hide = true) }, label = { Text(stringResource(R.string.fx_hide)) })
                            FilterChip(!x.hide, { a = x.copy(hide = false) }, label = { Text(stringResource(R.string.fx_show)) })
                        }
                        FieldPicker(R.string.fx_fields, names, x.fields) { a = x.copy(fields = it) }
                    }
                    is FxAction.JavaScript -> Script(R.string.fx_script, x.code) { a = x.copy(code = it) }
                    is FxAction.Named -> {
                        Text(stringResource(R.string.fx_menu_item), style = MaterialTheme.typography.labelLarge)
                        FlowRowCompat(MENU_ITEMS) { m -> FilterChip(x.name == m, { a = x.copy(name = m) }, label = { Text(m) }) }
                    }
                    is FxAction.ImportData -> OutlinedTextField(x.file, { a = x.copy(file = it) }, label = { Text(stringResource(R.string.fx_file)) }, singleLine = true)
                    is FxAction.Raw -> {}
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(a) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> FlowRowCompat(items: List<T>, item: @Composable (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items.forEach { item(it) } }
}
