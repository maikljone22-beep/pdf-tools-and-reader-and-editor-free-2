package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.E5Io
import com.smartdocs.editor.core.edit5.LayerRow
import com.smartdocs.editor.core.edit5.LayerUse
import com.smartdocs.editor.core.edit5.Layers
import com.smartdocs.editor.core.edit5.PageSet
import com.smartdocs.editor.core.redact.SanitizeItem
import com.smartdocs.editor.core.redact.Sanitizer
import com.smartdocs.editor.ui.common.rememberImagePicker
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun useLabel(u: LayerUse) = when (u) { LayerUse.WHEN_ON -> R.string.pc_ly_when_on; LayerUse.NEVER -> R.string.pc_ly_never; LayerUse.ALWAYS -> R.string.pc_ly_always }

/** Content of switched-off layers goes (the sanitizer's hidden-layer pass). */
private val removeHidden: (com.tom_roush.pdfbox.pdmodel.PDDocument) -> Unit = { d -> Sanitizer.clean(d, setOf(SanitizeItem.HIDDEN_LAYERS)) }

/**
 * Nitro 18 / Foxit 22 layers: show / hide with a live preview, all on / off, reset to the initial visibility, groups
 * from the layer order (collapsible), pages per layer; properties (name, intent, lock, view / print / export usage);
 * reorder, merge, delete, flatten; new layer; import a picture or a PDF page as a layer; a bookmark that switches
 * layers; apply print / export overrides.
 */
@Composable
fun LayersScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<LayerRow>>(emptyList()) }
    var initial by remember { mutableStateOf<Map<Int, Boolean>?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var editing by remember { mutableStateOf<LayerRow?>(null) }
    var mergeFrom by remember { mutableStateOf<LayerRow?>(null) }
    var newName by remember { mutableStateOf("") }
    var importName by remember { mutableStateOf("") }
    var importRange by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(doc.work, doc.version) {
        val w = doc.work ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { E5Io.load(w, doc.password).use { d -> if (initial == null) initial = Layers.states(d); Layers.list(d, initial.orEmpty()) } } }
            .onSuccess { rows = it }.onFailure { message = it.message }
    }
    // audit 5 E-12: one change at a time on the work file (fast toggles used to run concurrently); controls are
    // disabled while [busy]
    var busy by remember { mutableStateOf(false) }
    fun edit(block: (com.tom_roush.pdfbox.pdmodel.PDDocument) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { runCatching { doc.apply(app.documents.tempDir) { s, o, p -> withContext(Dispatchers.IO) { E5Io.edit(s, o, p) { block(it) } } } }.onFailure { message = it.message ?: it.toString() } }
            finally { busy = false }
        }
    }
    // audit 5 E-11: a locked PDF to import as a layer asks for its password
    val unlocker = rememberE5Unlocker()
    val pickImage = rememberImagePicker(multiple = false) { fs ->
        val f = fs.firstOrNull() ?: return@rememberImagePicker
        val name = importName.ifBlank { f.nameWithoutExtension }; val rg = importRange; val n = doc.pageCount
        edit { d ->
            val img = com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromFileByContent(f, d)
            // fitted into the page with a 10 % margin, centred
            Layers.importImage(d, img, name, PageSet.parse(rg, n)) { w, h ->
                val k = minOf(w * 0.8f / img.width, h * 0.8f / img.height); val iw = img.width * k; val ih = img.height * k
                floatArrayOf((w - iw) / 2, (h - ih) / 2, iw, ih)
            }
        }
    }
    val pickPdf = rememberPdfPicker { fs ->
        val f = fs.firstOrNull() ?: return@rememberPdfPicker
        val name = importName.ifBlank { f.nameWithoutExtension }; val rg = importRange; val n = doc.pageCount
        scope.launch {
            val ok = runCatching { unlocker.unlock(f) }.getOrElse { message = it.message ?: it.toString(); false }
            if (!ok) return@launch
            val pw = unlocker.password(f)
            edit { d -> E5Io.load(f, pw).use { src -> Layers.importPage(d, src, 0, name, PageSet.parse(rg, n)) } }
        }
    }
    val processing = stringResource(R.string.processing)
    val showOnly = stringResource(R.string.pc_ly_show_only_bm)

    ToolScaffold(stringResource(R.string.pc_ly_title), nav, runner, actions = {
        IconButton(onClick = { scope.launch { doc.undo() } }, enabled = doc.canUndo && !busy) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.e5_undo)) }
    }) {
        E5FileRow(doc)
        E5UnlockDialog(unlocker)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val info = doc.infos.getOrNull(page)
        if (doc.work != null && info != null) {
            PageStepper(page, doc.pageCount) { page = it }
            E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth(0.7f).align(Alignment.CenterHorizontally))
        }
        if (doc.work != null && rows.isEmpty()) Text(stringResource(R.string.pc_ly_none), style = MaterialTheme.typography.bodySmall)
        if (rows.isNotEmpty()) E5Section(stringResource(R.string.pc_ly_title)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { edit { Layers.showAll(it, true) } }, enabled = !busy, label = { Text(stringResource(R.string.pc_ly_all_on)) })
                AssistChip(onClick = { edit { Layers.showAll(it, false) } }, enabled = !busy, label = { Text(stringResource(R.string.pc_ly_all_off)) })
                AssistChip(onClick = { val i = initial ?: return@AssistChip; edit { Layers.reset(it, i) } }, enabled = !busy, label = { Text(stringResource(R.string.pc_ly_reset)) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { edit { Layers.applyOverrides(it, "Print") } }, enabled = !busy, label = { Text(stringResource(R.string.pc_ly_apply_print)) })
                AssistChip(onClick = { edit { Layers.applyOverrides(it, "Export") } }, enabled = !busy, label = { Text(stringResource(R.string.pc_ly_apply_export)) })
            }
            var lastGroup: String? = null
            rows.forEach { r ->
                if (r.group != null && r.group != lastGroup) {
                    val g = r.group
                    Row(Modifier.fillMaxWidth().clickable { collapsed = if (g in collapsed) collapsed - g else collapsed + g }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (g in collapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore, null)
                        Text(g, style = MaterialTheme.typography.labelLarge)
                    }
                }
                lastGroup = r.group
                if (r.group != null && r.group in collapsed) return@forEach
                key(r.index) {
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(start = (16 * r.depth).dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (r.locked) "🔒 " else "") + r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        val pages = if (r.pages.isEmpty()) stringResource(R.string.pc_ly_no_pages) else stringResource(R.string.pc_ly_pages, r.pages.take(8).joinToString(", ") { "${it + 1}" } + if (r.pages.size > 8) "…" else "")
                        Text(pages + (if (r.on != r.initialOn) " · " + stringResource(R.string.pc_ly_changed) else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(r.on, { v -> edit { Layers.setOn(it, r.index, v) } }, enabled = !r.locked && !busy)
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.pc_pf_actions, r.name)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.pc_ly_properties)) }, onClick = { menu = false; editing = r })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pc_move_up)) }, onClick = { menu = false; edit { Layers.move(it, r.index, -1) } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pc_move_down)) }, onClick = { menu = false; edit { Layers.move(it, r.index, 1) } })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pc_ly_merge)) }, onClick = { menu = false; mergeFrom = r })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pc_ly_bookmark)) }, onClick = {
                                menu = false; val others = rows.map { it.index } - r.index
                                edit { Layers.addStateBookmark(it, showOnly.format(r.name), listOf(r.index), others) }
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pc_ly_delete), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; initial = null; edit { Layers.delete(it, r.index, removeHidden) } })
                        }
                    }
                }
                }
            }
            OutlinedButton(onClick = { initial = null; edit { Layers.flatten(it, removeHidden) } }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Layers, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.pc_ly_flatten)) }
        }
        if (doc.work != null) E5Section(stringResource(R.string.pc_ly_new)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.pc_ly_name)) })
                OutlinedButton(enabled = newName.isNotBlank(), onClick = { val n = newName.trim(); newName = ""; edit { Layers.create(it, n) } }) { Text(stringResource(R.string.pc_add)) }
            }
            Text(stringResource(R.string.pc_ly_import), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(importName, { importName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.pc_ly_name)) })
            OutlinedTextField(importRange, { importRange = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.page_range)) }, placeholder = { Text(stringResource(R.string.e5_all_pages)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = pickImage, Modifier.weight(1f)) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.pc_ly_import_image)) }
                OutlinedButton(onClick = pickPdf, Modifier.weight(1f)) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.pc_ly_import_pdf)) }
            }
        }
        PrimaryButton(stringResource(R.string.e5_save_copy), enabled = doc.changed && !busy, icon = Icons.Default.Save) {
            runner.run(processing) { doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "layers") }
        }
    }

    editing?.let { r ->
        var name by remember(r) { mutableStateOf(r.name) }
        var intent by remember(r) { mutableStateOf(r.intent) }
        var locked by remember(r) { mutableStateOf(r.locked) }
        var on by remember(r) { mutableStateOf(r.initialOn) }
        var view by remember(r) { mutableStateOf(r.view) }
        var print by remember(r) { mutableStateOf(r.print) }
        var export by remember(r) { mutableStateOf(r.export) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.pc_ly_properties)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.pc_ly_name)) })
                ChoiceChips(listOf("View", "Design"), intent, { stringResource(if (it == "View") R.string.pc_ly_intent_view else R.string.pc_ly_intent_design) }) { intent = it }
                SwitchRow(stringResource(R.string.pc_ly_default_on), on) { on = it }
                SwitchRow(stringResource(R.string.pc_ly_locked), locked) { locked = it }
                Text(stringResource(R.string.pc_ly_visibility), style = MaterialTheme.typography.labelLarge)
                ChoiceChips(LayerUse.entries, view, { stringResource(useLabel(it)) }) { view = it }
                Text(stringResource(R.string.pc_ly_print), style = MaterialTheme.typography.labelLarge)
                ChoiceChips(LayerUse.entries, print, { stringResource(useLabel(it)) }) { print = it }
                Text(stringResource(R.string.pc_ly_export), style = MaterialTheme.typography.labelLarge)
                ChoiceChips(LayerUse.entries, export, { stringResource(useLabel(it)) }) { export = it }
            }
        }, confirmButton = {
            TextButton(onClick = {
                editing = null
                val i = r.index; val nm = name.trim().ifBlank { r.name }; val it0 = intent; val lk = locked; val o = on; val v = view; val p = print; val e = export
                edit { d ->
                    Layers.rename(d, i, nm); Layers.setIntent(d, i, it0); Layers.setLocked(d, i, lk); Layers.setOn(d, i, o)
                    Layers.setUse(d, i, "View", v); Layers.setUse(d, i, "Print", p); Layers.setUse(d, i, "Export", e)
                }
                // the default state set here is also the new "initial" one
                initial = initial.orEmpty() + (i to o)
            }) { Text(stringResource(R.string.ok)) }
        }, dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
    mergeFrom?.let { src ->
        AlertDialog(onDismissRequest = { mergeFrom = null }, title = { Text(stringResource(R.string.pc_ly_merge_into, src.name)) }, text = {
            Column { rows.filter { it.index != src.index }.forEach { t ->
                TextButton(onClick = { mergeFrom = null; initial = null; edit { Layers.merge(it, listOf(src.index), t.index) } }) { Text(t.name) }
            } }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { mergeFrom = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
