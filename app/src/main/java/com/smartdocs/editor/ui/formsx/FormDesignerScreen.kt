package com.smartdocs.editor.ui.formsx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.formsx.*
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Kinds offered on the create bar: the field types plus date (text + AFDate) and image (icon button) fields. */
private enum class NewKind(val type: FxType, val label: Int) {
    TEXT(FxType.TEXT, R.string.fb_type_text), CHECK(FxType.CHECKBOX, R.string.fb_type_checkbox), RADIO(FxType.RADIO, R.string.fb_type_radio),
    COMBO(FxType.COMBO, R.string.fb_type_combo), LIST(FxType.LIST, R.string.fb_type_list), SIGN(FxType.SIGNATURE, R.string.fb_type_signature),
    BUTTON(FxType.BUTTON, R.string.fb_type_button), BARCODE(FxType.BARCODE, R.string.fx_type_barcode),
    DATE(FxType.TEXT, R.string.f5_fb_date), IMAGE(FxType.BUTTON, R.string.f5_fb_image)
}

/**
 * Form designer (Nitro "Form" ribbon / Foxit "Prepare form"): create, select, move, resize, edit and delete any
 * field of the document \u2014 existing ones included \u2014 plus copies, alignment, tab and calculation order, field
 * recognition, form data import / export and form-to-sheet. Works on a copy; Save hands the result to the tool
 * result sheet (replace the document or keep a copy), so the reader records it as one undo step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDesignerScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberToolRunner()
    val st = remember { DesignerState(ctx, scope) }
    DisposableEffect(Unit) { onDispose { st.dispose() } }
    val initial = remember(uri) { uri?.let { runCatching { File(android.net.Uri.parse(it).path!!) }.getOrNull()?.takeIf { f -> f.exists() } } }
    LaunchedEffect(initial) { initial?.let { st.open(it) } }
    val pick = rememberPdfPicker { f -> f.firstOrNull()?.let { st.open(it) } }

    var kind by remember { mutableStateOf(NewKind.TEXT) }
    var pendingNew by remember { mutableStateOf<FieldSpec?>(null) }
    var props by remember { mutableStateOf<List<FieldSpec>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var leaveAsk by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }    // "copies" | "pages" | "calc" | "inherit" | "detect" | "list" | "export" | "combine"
    var iconTarget by remember { mutableStateOf<String?>(null) }
    var exportFormat by remember { mutableStateOf(DataFormat.FDF) }
    val str = { id: Int -> ctx.getString(id) }

    fun back() { if (st.dirty) leaveAsk = true else nav.popBackStack() }
    BackHandler(enabled = st.dirty) { leaveAsk = true }

    // ---------------- pickers ----------------
    val iconPicker = rememberImagePicker(multiple = false) { files ->
        val f = files.firstOrNull() ?: return@rememberImagePicker
        val target = iconTarget ?: return@rememberImagePicker
        st.edit(str(R.string.fx_icon)) { doc ->
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return@edit false
            val img = com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, bmp)
            FxEditor.setIcon(doc, target, FxEditor.iconFromImage(doc, img))
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@rememberLauncherForActivityResult
        scope.launch {
            val tmp = withContext(Dispatchers.IO) { runCatching { app.documents.localFile(u) }.getOrNull() } ?: return@launch
            var n = 0
            st.edit(str(R.string.fx_data_import), after = { st.message = ctx.getString(R.string.fx_imported, n) }) { doc -> n = FormData.importFile(doc, tmp); n > 0 }
        }
    }
    val exportSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { u ->
        val w = st.work ?: return@rememberLauncherForActivityResult
        if (u == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    com.tom_roush.pdfbox.pdmodel.PDDocument.load(w).use { d ->
                        ctx.contentResolver.openOutputStream(u)?.use { out -> FormData.export(d, exportFormat, out, st.source?.name) }
                    }
                }
            }.onSuccess { st.message = str(R.string.fx_exported) }.onFailure { st.error = it.message }
        }
    }
    var combineCsv by remember { mutableStateOf<String?>(null) }
    var combineFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var appendTo by remember { mutableStateOf<ByteArray?>(null) }
    val csvSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { u ->
        val text = combineCsv ?: return@rememberLauncherForActivityResult
        if (u != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(u)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } } }
                .onSuccess { st.message = ctx.getString(R.string.fx_combined, combineFiles.size) }.onFailure { st.error = it.message }
        }
    }
    val csvAppendPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch { appendTo = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull() } }
    }
    val formsPicker = rememberPdfPicker(multiple = true) { files ->
        combineFiles = files
        scope.launch {
            val rows = withContext(Dispatchers.IO) {
                files.mapNotNull { f -> runCatching { com.tom_roush.pdfbox.pdmodel.PDDocument.load(f).use { d -> f.name to FormData.values(d) } }.getOrNull() }
            }
            combineCsv = FormData.collect(rows, appendTo)
            csvSaver.launch("forms.csv")
        }
    }

    Scaffold(topBar = {
        AppTopBar(stringResource(R.string.fx_title), onBack = { back() }, actions = {
            IconButton(onClick = { st.undo() }, enabled = st.canUndo && !st.busy) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
            IconButton(onClick = { st.redo() }, enabled = st.canRedo && !st.busy) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
            if (st.work != null) TextButton(onClick = {
                runner.run(ctx.getString(R.string.processing)) { st.export(app.documents.docsDir) { dir, base -> app.documents.uniqueFile(dir, base) } }
            }, enabled = st.dirty && !st.busy) { Text(stringResource(R.string.save)) }
            Box {
                IconButton(onClick = { menu = true }, enabled = st.work != null) { Icon(Icons.Default.MoreVert, stringResource(R.string.fx_more)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_detect)) }, leadingIcon = { Icon(Icons.Default.AutoFixHigh, null) }, onClick = { menu = false; dialog = "detect" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_fields)) }, leadingIcon = { Icon(Icons.Default.List, null) }, onClick = { menu = false; dialog = "list" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_tabs_menu)) }, leadingIcon = { Icon(Icons.Default.LowPriority, null) }, onClick = { menu = false; dialog = "tabs" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_calc_order)) }, leadingIcon = { Icon(Icons.Default.Calculate, null) }, onClick = { menu = false; dialog = "calc" })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_data_import)) }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { menu = false; importPicker.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_data_export)) }, leadingIcon = { Icon(Icons.Default.Upload, null) }, onClick = { menu = false; dialog = "export" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_combine)) }, leadingIcon = { Icon(Icons.Default.TableChart, null) }, onClick = { menu = false; dialog = "combine" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_distribute)) }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                        menu = false
                        scope.launch {
                            val f = st.export(app.documents.tempDir) { dir, base -> File(dir, "$base.pdf") } ?: return@launch
                            com.smartdocs.editor.ui.share5.S5Share.shareFiles(ctx, listOf(f), "application/pdf", str(R.string.fx_distribute_subject), str(R.string.fx_distribute_text))
                        }
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_grid)) }, leadingIcon = { Checkbox(st.gridSnap > 0f, null) }, onClick = { menu = false; st.gridSnap = if (st.gridSnap > 0f) 0f else 6f })
                    DropdownMenuItem(text = { Text(stringResource(R.string.fx_tabs_numbers)) }, leadingIcon = { Checkbox(st.showTabNumbers, null) }, onClick = { menu = false; st.showTabNumbers = !st.showTabNumbers })
                }
            }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (st.work == null) {
                Column(Modifier.padding(16.dp)) { FilePickRow(null, stringResource(R.string.open_file), pick) }
                if (st.busy) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else EmptyState(Icons.Default.Checklist, stringResource(R.string.fx_open_hint))
                return@Column
            }
            // ---- mode + kinds
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(st.mode == DesignMode.SELECT, { st.mode = DesignMode.SELECT }, label = { Text(stringResource(R.string.fx_mode_select)) }, leadingIcon = { Icon(Icons.Default.NearMe, null, Modifier.size(16.dp)) })
                FilterChip(st.mode == DesignMode.CREATE, { st.mode = DesignMode.CREATE; st.selection.clear(); if (st.suggestions[st.page] == null) st.recognize(listOf(st.page)) },
                    label = { Text(stringResource(R.string.fx_mode_create)) }, leadingIcon = { Icon(Icons.Default.AddBox, null, Modifier.size(16.dp)) })
                FilterChip(st.mode == DesignMode.TAB_ORDER, { st.mode = DesignMode.TAB_ORDER; st.selection.clear(); st.tabPicks.clear() },
                    label = { Text(stringResource(R.string.fx_mode_tabs)) }, leadingIcon = { Icon(Icons.Default.LowPriority, null, Modifier.size(16.dp)) })
            }
            if (st.mode == DesignMode.CREATE) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NewKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, label = { Text(stringResource(k.label)) }) }
            }
            Text(stringResource(when (st.mode) { DesignMode.SELECT -> R.string.fx_hint_select; DesignMode.CREATE -> R.string.fx_hint_create; DesignMode.TAB_ORDER -> R.string.fx_hint_tabs }),
                Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // ---- page navigation
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { st.page = (st.page - 1).coerceAtLeast(0); st.tabPicks.clear() }, enabled = st.page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.pc_cd_next_page)) }
                Text(stringResource(R.string.page_of, st.page + 1, st.pageCount), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { st.page = (st.page + 1).coerceAtMost(st.pageCount - 1); st.tabPicks.clear() }, enabled = st.page < st.pageCount - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.pc_cd_prev_page)) }
                if (st.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            LaunchedEffect(st.page, st.mode) { if (st.mode == DesignMode.CREATE && st.suggestions[st.page] == null) st.recognize(listOf(st.page)) }
            DesignSurface(st, Modifier.weight(1f),
                onCreate = { r ->
                    val base = FxPrefs.defaults(ctx, kind.type)?.let { FxMerge.withLook(FieldSpec("", kind.type, emptyList()), it) } ?: FieldSpec("", kind.type, emptyList())
                    pendingNew = newSpec(base, kind, st, r)
                },
                onSuggestion = { sug ->
                    val base = FxPrefs.defaults(ctx, sug.type)?.let { FxMerge.withLook(FieldSpec("", sug.type, emptyList()), it) }
                    st.edit(str(R.string.fx_mode_create)) { doc -> FieldRecognition.apply(doc, st.page, listOf(sug), base); true }
                    st.suggestions = st.suggestions + (st.page to st.suggestions[st.page].orEmpty().filter { it != sug })
                },
                onOpenProps = { props = st.selectedSpecs().ifEmpty { null } },
            )
            // ---- bottom bars
            if (st.mode == DesignMode.SELECT && st.selection.isNotEmpty()) SelectionBar(st,
                onProps = { props = st.selectedSpecs() },
                onDialog = { dialog = it })
            if (st.mode == DesignMode.TAB_ORDER) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${st.tabPicks.size} / ${st.widgetsOn(st.page).size}", Modifier.weight(1f))
                TextButton(onClick = { st.tabPicks.clear() }) { Text(stringResource(R.string.fx_tabs_reset)) }
                Button(onClick = {
                    val p = st.page
                    val rest = st.widgetsOn(p).map { it.first }.filter { it !in st.tabPicks }
                    val order = st.tabPicks.toList() + rest
                    st.edit(str(R.string.fx_tabs_menu), after = { st.tabPicks.clear(); st.mode = DesignMode.SELECT }) { doc -> FxEditor.setManualTabOrder(doc, p, order); true }
                }, enabled = st.tabPicks.isNotEmpty()) { Text(stringResource(R.string.apply)) }
            }
        }
    }

    // ---------------- dialogs ----------------
    pendingNew?.let { spec ->
        FieldPropsDialog(spec, st.specs.map { it.name }, st.pageCount, 1, layers = st.layers, onDismiss = { pendingNew = null }, onSave = { s ->
            pendingNew = null
            st.edit(str(R.string.fx_mode_create)) { doc ->
                val existing = FxEditor.field(doc, s.name)
                if (s.type == FxType.RADIO && existing is com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton) {
                    val w = s.widgets.first(); FxEditor.addRadioWidget(doc, s.name, w.page, w.rect, w.export ?: "")
                } else FxEditor.create(doc, s)
                true
            }
        })
    }
    props?.let { list ->
        val first = list.first()
        FieldPropsDialog(first, st.specs.map { it.name }, st.pageCount, list.size, onDismiss = { props = null },
            onSave = { edited ->
                props = null
                if (list.any { it.locked } && edited.locked && first.locked) { st.message = str(R.string.fx_locked_msg) }
                st.edit(str(R.string.fx_props)) { doc ->
                    if (list.size == 1) {
                        val newName = FxEditor.update(doc, first.name, edited)
                        if (newName != first.name) scope.launch { st.selection.clear() }
                    } else list.forEach { t -> FxEditor.update(doc, t.name, FxMerge.apply(first, edited, t)) }
                    true
                }
            },
            onIcon = if (list.size == 1 && first.type == FxType.BUTTON) { clear: Boolean ->
                if (clear) st.edit(str(R.string.fx_icon_clear)) { doc -> FxEditor.setIcon(doc, first.name, null) }
                else { iconTarget = first.name; iconPicker() }
            } else null, layers = st.layers)
    }
    when (dialog) {
        "copies" -> CopiesDialog(onDismiss = { dialog = null }) { rows, cols, dx, dy, same ->
            dialog = null
            val name = st.selection.firstOrNull()?.name ?: return@CopiesDialog
            st.edit(str(R.string.fx_copies)) { doc -> FxEditor.makeCopies(doc, name, rows, cols, dx, dy, same).isNotEmpty() }
        }
        "pages" -> PagesDialog(st.pageCount, onDismiss = { dialog = null }) { pages ->
            dialog = null
            val ref = st.selection.firstOrNull() ?: return@PagesDialog
            st.edit(str(R.string.fx_dup_pages)) { doc -> FxEditor.duplicateToPages(doc, ref, pages) > 0 }
        }
        "calc" -> CalcOrderDialog(st.calcOrder, onDismiss = { dialog = null }) { order ->
            dialog = null
            st.edit(str(R.string.fx_calc_order)) { doc -> FxEditor.setCalcOrder(doc, order); true }
        }
        "inherit" -> PickFieldDialog(R.string.fx_pick_source, st.specs.map { it.name }.filter { n -> st.selection.none { it.name == n } }, onDismiss = { dialog = null }) { src ->
            dialog = null
            val source = st.specs.firstOrNull { it.name == src } ?: return@PickFieldDialog
            val targets = st.selectedSpecs()
            st.edit(str(R.string.fx_inherit)) { doc -> targets.forEach { t -> FxEditor.update(doc, t.name, FxEditor.inherit(source, t)) }; true }
        }
        "detect" -> DetectDialog(st, onDismiss = { dialog = null })
        "list" -> FieldListDialog(st, onDismiss = { dialog = null })
        "tabs" -> TabsDialog(st.tabs[st.page], onDismiss = { dialog = null }) { mode ->
            dialog = null
            if (mode == "M") { st.mode = DesignMode.TAB_ORDER; st.tabPicks.clear() }
            else { val p = st.page; st.edit(str(R.string.fx_tabs_menu)) { doc -> FxEditor.setTabs(doc, p, mode); true } }
        }
        "export" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(R.string.fx_data_export)) },
            text = { Column { DataFormat.entries.forEach { f -> Row(Modifier.fillMaxWidth().clickable { exportFormat = f }, verticalAlignment = Alignment.CenterVertically) { RadioButton(exportFormat == f, { exportFormat = f }); Text(f.name) } } } },
            confirmButton = { TextButton(onClick = { dialog = null; exportSaver.launch((st.source?.nameWithoutExtension ?: "form") + "." + exportFormat.ext) }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } })
        "combine" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(R.string.fx_combine)) },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { if (appendTo == null) csvAppendPicker.launch(arrayOf("text/*", "application/csv", "*/*")) else appendTo = null }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(appendTo != null, null); Text(stringResource(R.string.fx_combine_append))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null; formsPicker() }) { Text(stringResource(R.string.open_file)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } })
        else -> {}
    }
    if (leaveAsk) AlertDialog(onDismissRequest = { leaveAsk = false }, text = { Text(stringResource(R.string.fx_unsaved)) },
        confirmButton = { TextButton(onClick = { leaveAsk = false; nav.popBackStack() }) { Text(stringResource(R.string.fx_leave)) } },
        dismissButton = { TextButton(onClick = { leaveAsk = false }) { Text(stringResource(R.string.cancel)) } })
    st.message?.let { m -> AlertDialog(onDismissRequest = { st.message = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { st.message = null }) { Text(stringResource(R.string.ok)) } }) }
    // tab-view-core E-03: password prompt for an encrypted document
    st.needPassword?.let { f -> com.smartdocs.editor.ui.reader.PasswordDialog(st.wrongPassword, onOk = { pw -> st.open(f, pw) }, onCancel = { st.needPassword = null }) }
    st.error?.let { e -> AlertDialog(onDismissRequest = { st.error = null }, text = { Text(e) }, confirmButton = { TextButton(onClick = { st.error = null }) { Text(stringResource(R.string.ok)) } }) }
    if (runner.running) ProgressDialog(runner.message)
    runner.result?.let { f -> ResultSheet(f, nav, onDismiss = { runner.result = null }) }
    runner.error?.let { e -> AlertDialog(onDismissRequest = { runner.error = null }, text = { Text(e) }, confirmButton = { TextButton(onClick = { runner.error = null }) { Text(stringResource(R.string.ok)) } }) }
}

/** A new field spec for a drawn rect: default name, kind specifics (date pattern, image button, radio group). */
private fun newSpec(base: FieldSpec, kind: NewKind, st: DesignerState, r: FxRect): FieldSpec {
    val n = st.specs.size + 1
    val prefix = when (kind) { NewKind.TEXT -> "Text"; NewKind.CHECK -> "Check"; NewKind.RADIO -> "Radio"; NewKind.COMBO -> "Combo"; NewKind.LIST -> "List"
        NewKind.SIGN -> "Signature"; NewKind.BUTTON -> "Button"; NewKind.BARCODE -> "Barcode"; NewKind.DATE -> "Date"; NewKind.IMAGE -> "Image" }
    var name = "$prefix$n"; var k = n
    while (st.specs.any { it.name == name }) name = "$prefix${++k}"
    // radio: offer to join the selected / last radio group (place buttons one by one)
    val group = if (kind == NewKind.RADIO) (st.selectedSpecs().firstOrNull { it.type == FxType.RADIO } ?: st.specs.lastOrNull { it.type == FxType.RADIO }) else null
    val w = FxWidget(st.page, r, export = if (kind == NewKind.RADIO) "Choice${(group?.widgets?.size ?: 0) + 1}" else null)
    val s = base.copy(name = group?.name ?: name, type = kind.type, widgets = listOf(w))
    return when (kind) {
        NewKind.DATE -> s.copy(format = FxFormat.Date("dd/mm/yyyy"), tooltip = "DD/MM/YYYY")
        NewKind.IMAGE -> s.copy(layout = FxButtonLayout.ICON_ONLY, caption = "", actions = mapOf(FxTrigger.MOUSE_UP to listOf(FxAction.JavaScript("event.target.buttonImportIcon();"))))
        NewKind.BUTTON -> s.copy(caption = name, fillColor = s.fillColor ?: 0xD6D6D6, highlight = FxHighlight.PUSH, borderStyle = FxBorderStyle.BEVELED)
        NewKind.COMBO, NewKind.LIST -> s.copy(items = listOf("1" to "1", "2" to "2"))
        NewKind.BARCODE -> s.copy(barcode = FxBarcode(), borderColor = null, borderWidth = 0f)
        NewKind.CHECK -> s.copy(exportValue = "Yes")
        else -> s
    }
}

// ================= the page =================

@Composable
private fun DesignSurface(st: DesignerState, modifier: Modifier, onCreate: (FxRect) -> Unit, onSuggestion: (FieldRecognition.Suggestion) -> Unit, onOpenProps: () -> Unit) {
    val session = st.session ?: return
    val info = st.pages.getOrNull(st.page) ?: return
    val ctx = LocalContext.current
    BoxWithConstraints(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
        val heightPx = (widthPx * info.dispH / info.dispW).toInt().coerceAtLeast(1)
        val scale = widthPx / info.dispW
        val bmp by produceState<Bitmap?>(null, st.page, widthPx, st.version) { value = withContext(Dispatchers.IO) { runCatching { session.render(st.page, widthPx, heightPx) }.getOrNull() } }
        var dragA by remember { mutableStateOf<Offset?>(null) }
        var dragB by remember { mutableStateOf<Offset?>(null) }
        var dragMode by remember { mutableStateOf(0) }   // 1 move, 2 resize, 3 marquee, 4 draw
        val widgets = st.widgetsOn(st.page)
        fun viewRect(r: FxRect): FloatArray = info.rectToView(r).let { a -> FloatArray(4) { a[it] * scale } }
        fun hit(p: Offset): Triple<FxRef, FieldSpec, FxRect>? = widgets.lastOrNull { (_, _, r) -> val v = viewRect(r); p.x in v[0] - 6..v[2] + 6 && p.y in v[1] - 6..v[3] + 6 }
        fun toPdf(p: Offset): Pair<Float, Float> = info.toPdf(p.x / scale, p.y / scale)
        fun snap(r: FxRect): FxRect = if (st.gridSnap <= 0f) r else { val l = FxLayout.snap(r.left, st.gridSnap); val b = FxLayout.snap(r.bottom, st.gridSnap); FxRect(l, b, l + r.width, b + r.height) }
        fun lockedMsg() { st.message = ctx.getString(R.string.fx_locked_msg) }
        Box(Modifier.fillMaxWidth().aspectRatio(info.dispW / info.dispH).background(Color.White)
            .pointerInput(st.mode, st.page, scale, st.version) {
                detectTapGestures(
                    onTap = { p ->
                        when (st.mode) {
                            DesignMode.SELECT -> { val h = hit(p); st.selection.clear(); if (h != null) st.selection.add(h.first) }
                            DesignMode.CREATE -> {
                                val (x, y) = toPdf(p)
                                st.suggestions[st.page]?.firstOrNull { it.rect.contains(x, y) }?.let(onSuggestion)
                            }
                            DesignMode.TAB_ORDER -> hit(p)?.let { h -> if (h.first !in st.tabPicks) st.tabPicks.add(h.first) }
                        }
                    },
                    onLongPress = { p -> if (st.mode == DesignMode.SELECT) hit(p)?.let { h -> if (h.first in st.selection) st.selection.remove(h.first) else st.selection.add(h.first) } },
                    onDoubleTap = { p -> if (st.mode == DesignMode.SELECT) hit(p)?.let { h -> if (h.first !in st.selection) { st.selection.clear(); st.selection.add(h.first) }; onOpenProps() } },
                )
            }
            .pointerInput(st.mode, st.page, scale, st.version) {
                detectDragGestures(
                    onDragStart = { p ->
                        dragA = p; dragB = p
                        dragMode = when (st.mode) {
                            DesignMode.CREATE -> 4
                            DesignMode.TAB_ORDER -> 0
                            DesignMode.SELECT -> {
                                val single = st.selection.singleOrNull()?.let { st.rectOf(it) }?.let { viewRect(it) }
                                val h = hit(p)
                                when {
                                    single != null && kotlin.math.abs(p.x - single[2]) < 40 && kotlin.math.abs(p.y - single[3]) < 40 -> 2
                                    h != null -> { if (h.first !in st.selection) { st.selection.clear(); st.selection.add(h.first) }; 1 }
                                    else -> 3
                                }
                            }
                        }
                    },
                    onDrag = { ch, _ -> ch.consume(); dragB = ch.position },
                    onDragEnd = {
                        val a = dragA; val b = dragB
                        if (a != null && b != null) when (dragMode) {
                            4 -> {
                                val (x0, y0) = toPdf(a); val (x1, y1) = toPdf(b)
                                val r = snap(FxRect(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1)))
                                if (r.width > 6f && r.height > 6f) onCreate(r)
                            }
                            1 -> {
                                val (x0, y0) = toPdf(a); val (x1, y1) = toPdf(b)
                                val refs = st.selection.toList()
                                if (refs.any { r -> st.specOf(r)?.locked == true }) lockedMsg()
                                else st.edit(ctx.getString(R.string.fx_mode_select)) { doc -> refs.forEach { r -> st.rectOf(r)?.let { FxEditor.setRect(doc, r, snap(it.offset(x1 - x0, y1 - y0))) } }; true }
                            }
                            2 -> {
                                val ref = st.selection.singleOrNull()
                                val r0 = ref?.let { st.rectOf(it) }
                                if (ref != null && r0 != null) {
                                    if (st.specOf(ref)?.locked == true) lockedMsg() else {
                                        val v = viewRect(r0)
                                        val nr = info.rectToPdf(v[0] / scale, v[1] / scale, maxOf(v[0] + 8f, v[2] + b.x - a.x) / scale, maxOf(v[1] + 8f, v[3] + b.y - a.y) / scale)
                                        st.edit(ctx.getString(R.string.fx_pos_width)) { doc -> FxEditor.setRect(doc, ref, nr) }
                                    }
                                }
                            }
                            3 -> {
                                val l = minOf(a.x, b.x); val r = maxOf(a.x, b.x); val t = minOf(a.y, b.y); val bt = maxOf(a.y, b.y)
                                st.selection.clear()
                                widgets.filter { (_, _, rr) -> val v = viewRect(rr); v[0] >= l && v[2] <= r && v[1] >= t && v[3] <= bt }.forEach { st.selection.add(it.first) }
                            }
                            else -> {}
                        }
                        dragA = null; dragB = null; dragMode = 0
                    },
                    onDragCancel = { dragA = null; dragB = null; dragMode = 0 },
                )
            }) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
            val primary = MaterialTheme.colorScheme.primary
            val tabOrder = remember(st.version, st.page, st.showTabNumbers) { if (st.showTabNumbers) st.work?.let { f -> runCatching { com.tom_roush.pdfbox.pdmodel.PDDocument.load(f).use { d -> FxEditor.tabOrder(d, st.page, st.specs) } }.getOrNull() } else null }
            Canvas(Modifier.fillMaxSize()) {
                val a = dragA; val b = dragB
                val dx = if (dragMode == 1 && a != null && b != null) b.x - a.x else 0f
                val dy = if (dragMode == 1 && a != null && b != null) b.y - a.y else 0f
                widgets.forEach { (ref, spec, r) ->
                    val v = viewRect(r)
                    val sel = ref in st.selection
                    var l = v[0]; var t = v[1]; var rr = v[2]; var bb = v[3]
                    if (sel && dragMode == 1) { l += dx; rr += dx; t += dy; bb += dy }
                    if (sel && dragMode == 2 && a != null && b != null) { rr = maxOf(l + 8f, rr + b.x - a.x); bb = maxOf(t + 8f, bb + b.y - a.y) }
                    val c = when { sel -> primary; spec.locked -> Color(0xFF9E9E9E); else -> Color(0xFF1565C0) }
                    drawRect(c.copy(alpha = if (sel) 0.22f else 0.10f), Offset(l, t), Size(rr - l, bb - t))
                    drawRect(c, Offset(l, t), Size(rr - l, bb - t), style = Stroke(width = if (sel) 4f else 2f))
                    if (sel && st.selection.size == 1) drawCircle(primary, 12f, Offset(rr, bb))
                    val label = when {
                        st.mode == DesignMode.TAB_ORDER -> st.tabPicks.indexOf(ref).takeIf { it >= 0 }?.let { "${it + 1}" }
                        tabOrder != null -> tabOrder.indexOf(ref).takeIf { it >= 0 }?.let { "${it + 1}" }
                        else -> null
                    } ?: spec.name.takeIf { rr - l > 60f }
                    if (label != null) drawContext.canvas.nativeCanvas.drawText(label, l + 4f, t + 26f, android.graphics.Paint().apply { textSize = 24f; color = android.graphics.Color.argb(220, 21, 101, 192); isAntiAlias = true })
                }
                if (st.mode == DesignMode.CREATE) st.suggestions[st.page].orEmpty().forEach { s ->
                    val v = viewRect(s.rect)
                    drawRect(Color(0xFFEF6C00), Offset(v[0], v[1]), Size(v[2] - v[0], v[3] - v[1]), style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
                }
                if (a != null && b != null && (dragMode == 3 || dragMode == 4)) {
                    val tl = Offset(minOf(a.x, b.x), minOf(a.y, b.y)); val sz = Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
                    val c = if (dragMode == 4) Color(0xFF00897B) else Color(0xFF616161)
                    drawRect(c.copy(alpha = 0.18f), tl, sz); drawRect(c, tl, sz, style = Stroke(width = 3f, pathEffect = if (dragMode == 3) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null))
                }
            }
        }
    }
}

// ================= selection bar =================

@Composable
private fun SelectionBar(st: DesignerState, onProps: () -> Unit, onDialog: (String) -> Unit) {
    val ctx = LocalContext.current
    var alignMenu by remember { mutableStateOf(false) }
    val n = st.selection.size
    fun layout(label: Int, f: (List<FxRect>) -> List<FxRect>) {
        val refs = st.selection.toList()
        val rects = refs.mapNotNull { st.rectOf(it) }
        if (rects.size != refs.size) return
        if (refs.any { st.specOf(it)?.locked == true }) { st.message = ctx.getString(R.string.fx_locked_msg); return }
        val out = f(rects)
        st.edit(ctx.getString(label)) { doc -> refs.forEachIndexed { i, r -> FxEditor.setRect(doc, r, out[i]) }; true }
    }
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.fx_n_selected, n), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = onProps) { Icon(Icons.Default.Tune, stringResource(R.string.fx_props)) }
            IconButton(onClick = {
                val names = st.selectedSpecs().map { it.name }
                if (st.selectedSpecs().any { it.locked }) { st.message = ctx.getString(R.string.fx_locked_msg); return@IconButton }
                st.edit(ctx.getString(R.string.delete), after = { st.selection.clear() }) { doc ->
                    if (n == 1 && (st.specOf(st.selection.first())?.widgets?.size ?: 1) > 1) FxEditor.deleteWidget(doc, st.selection.first()) else FxEditor.delete(doc, names) > 0
                }
            }) { Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) }
            IconButton(onClick = {
                val names = st.selectedSpecs().map { it.name }
                st.edit(ctx.getString(R.string.fx_duplicate)) { doc -> names.forEach { FxEditor.duplicate(doc, it, 12f, -12f) }; true }
            }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.fx_duplicate)) }
            if (n == 1) {
                IconButton(onClick = { onDialog("copies") }) { Icon(Icons.Default.GridOn, stringResource(R.string.fx_copies)) }
                IconButton(onClick = { onDialog("pages") }) { Icon(Icons.Default.FileCopy, stringResource(R.string.fx_dup_pages)) }
                IconButton(onClick = { st.specOf(st.selection.first())?.let { FxPrefs.saveDefaults(ctx, it); st.message = ctx.getString(R.string.fx_defaults_saved) } }) {
                    Icon(Icons.Default.BookmarkAdd, stringResource(R.string.fx_set_default))
                }
            }
            IconButton(onClick = { onDialog("inherit") }) { Icon(Icons.Default.FormatPaint, stringResource(R.string.fx_inherit)) }
            Box {
                IconButton(onClick = { alignMenu = true }) { Icon(Icons.Default.AlignHorizontalLeft, stringResource(R.string.fx_align)) }
                DropdownMenu(alignMenu, { alignMenu = false }) {
                    if (n >= 2) {
                        listOf(FxLayout.Align.LEFT to R.string.fx_align_left, FxLayout.Align.RIGHT to R.string.fx_align_right, FxLayout.Align.TOP to R.string.fx_align_top,
                            FxLayout.Align.BOTTOM to R.string.fx_align_bottom, FxLayout.Align.CENTER_H to R.string.fx_align_ch, FxLayout.Align.CENTER_V to R.string.fx_align_cv).forEach { (m, l) ->
                            DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { alignMenu = false; layout(l) { FxLayout.align(it, m) } })
                        }
                        listOf(FxLayout.Size.WIDTH to R.string.fx_size_w, FxLayout.Size.HEIGHT to R.string.fx_size_h, FxLayout.Size.BOTH to R.string.fx_size_both).forEach { (m, l) ->
                            DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { alignMenu = false; layout(l) { FxLayout.matchSize(it, m) } })
                        }
                    }
                    if (n >= 3) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.fx_distribute_h)) }, onClick = { alignMenu = false; layout(R.string.fx_distribute_h) { FxLayout.distribute(it, true) } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.fx_distribute_v)) }, onClick = { alignMenu = false; layout(R.string.fx_distribute_v) { FxLayout.distribute(it, false) } })
                    }
                    val info = st.pages.getOrNull(st.page)
                    if (info != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.fx_center_page_h)) }, onClick = { alignMenu = false
                            layout(R.string.fx_center_page_h) { FxLayout.centerOnPage(it, info.cropW, info.cropH, true, false).map { r -> r.offset(info.cropLeft, 0f) } } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.fx_center_page_v)) }, onClick = { alignMenu = false
                            layout(R.string.fx_center_page_v) { FxLayout.centerOnPage(it, info.cropW, info.cropH, false, true).map { r -> r.offset(0f, info.cropBottom) } } })
                    }
                }
            }
            val anyLocked = st.selectedSpecs().any { it.locked }
            IconButton(onClick = {
                val names = st.selectedSpecs().map { it.name }
                st.edit(ctx.getString(if (anyLocked) R.string.fx_unlock else R.string.fx_lock)) { doc -> FxEditor.setLocked(doc, names, !anyLocked); true }
            }) { Icon(if (anyLocked) Icons.Default.LockOpen else Icons.Default.Lock, stringResource(if (anyLocked) R.string.fx_unlock else R.string.fx_lock)) }
        }
    }
}

// ================= small dialogs =================

@Composable
private fun CopiesDialog(onDismiss: () -> Unit, onOk: (Int, Int, Float, Float, Boolean) -> Unit) {
    var rows by remember { mutableStateOf("2") }; var cols by remember { mutableStateOf("1") }
    var dx by remember { mutableStateOf("0") }; var dy by remember { mutableStateOf("0") }; var same by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.fx_copies)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NumField(R.string.fx_copies_rows, rows, Modifier.weight(1f)) { rows = it }; NumField(R.string.fx_copies_cols, cols, Modifier.weight(1f)) { cols = it } }
                NumField(R.string.fx_copies_dx, dx) { dx = it }
                NumField(R.string.fx_copies_dy, dy) { dy = it }
                SwitchRow(R.string.fx_copies_same, same) { same = it }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(rows.toIntOrNull()?.coerceIn(1, 40) ?: 1, cols.toIntOrNull()?.coerceIn(1, 40) ?: 1, dx.toFloatOrNull() ?: 0f, dy.toFloatOrNull() ?: 0f, same) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun PagesDialog(pageCount: Int, onDismiss: () -> Unit, onOk: (List<Int>) -> Unit) {
    var mode by remember { mutableIntStateOf(0) }
    var range by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.fx_dup_pages)) },
        text = {
            Column {
                listOf(R.string.fx_pages_all, R.string.fx_pages_odd, R.string.fx_pages_even, R.string.fx_pages_range).forEachIndexed { i, l ->
                    Row(Modifier.fillMaxWidth().clickable { mode = i }, verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == i, { mode = i }); Text(stringResource(l)) }
                }
                if (mode == 3) PageRangeField(range) { range = it }
            }
        },
        confirmButton = { TextButton(onClick = {
            val all = (0 until pageCount).toList()
            onOk(when (mode) { 1 -> all.filter { it % 2 == 0 }; 2 -> all.filter { it % 2 == 1 }; 3 -> parseRange(range, pageCount); else -> all })
        }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** "1-3, 5" -> zero-based pages. */
internal fun parseRange(s: String, count: Int): List<Int> = s.split(',', ';', ' ').filter { it.isNotBlank() }.flatMap { part ->
    val p = part.trim().split('-')
    val a = p[0].trim().toIntOrNull() ?: return@flatMap emptyList()
    val b = p.getOrNull(1)?.trim()?.toIntOrNull() ?: a
    (minOf(a, b)..maxOf(a, b)).map { it - 1 }
}.filter { it in 0 until count }.distinct()

@Composable
private fun CalcOrderDialog(initial: List<String>, onDismiss: () -> Unit, onOk: (List<String>) -> Unit) {
    val l = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.fx_calc_order)) },
        text = {
            if (l.isEmpty()) Text(stringResource(R.string.fx_calc_order_empty))
            else Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                l.forEachIndexed { i, n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. $n", Modifier.weight(1f))
                        IconButton(onClick = { l.add(i - 1, l.removeAt(i)) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.fx_up)) }
                        IconButton(onClick = { l.add(i + 1, l.removeAt(i)) }, enabled = i < l.size - 1) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.fx_down)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(l.toList()) }, enabled = l.isNotEmpty()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun PickFieldDialog(title: Int, names: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) { items(names) { n -> Text(n, Modifier.fillMaxWidth().clickable { onPick(n) }.padding(vertical = 10.dp)) } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun TabsDialog(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.fx_tabs_menu)) },
        text = {
            Column {
                listOf("R" to R.string.fx_tabs_row, "C" to R.string.fx_tabs_col, "S" to R.string.fx_tabs_struct, null to R.string.fx_tabs_unspec, "M" to R.string.fx_tabs_manual).forEach { (m, l) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(m) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(current == m, { onPick(m) }); Text(stringResource(l)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun DetectDialog(st: DesignerState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var scanned by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<Pair<Int, FieldRecognition.Suggestion>>() }
    val all = st.suggestions.flatMap { (p, l) -> l.map { p to it } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(if (scanned) R.string.fx_detect_review else R.string.fx_detect)) },
        text = {
            if (!scanned) Column {
                TextButton(onClick = { st.recognize(listOf(st.page)) { scanned = true; chosen.clear(); chosen.addAll(st.suggestions[st.page].orEmpty().map { st.page to it }) } }) { Text(stringResource(R.string.fx_detect_page)) }
                TextButton(onClick = { st.recognize(null) { scanned = true; chosen.clear(); chosen.addAll(st.suggestions.flatMap { (p, l) -> l.map { p to it } }) } }) { Text(stringResource(R.string.fx_detect_all)) }
                if (st.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (all.isEmpty()) Text(stringResource(R.string.fx_detect_none))
            else Column {
                Text(stringResource(R.string.fx_detect_found, all.size), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(all) { ps ->
                        Row(Modifier.fillMaxWidth().clickable { if (ps in chosen) chosen.remove(ps) else chosen.add(ps) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(ps in chosen, null)
                            Text("${ps.second.name}  \u00B7  ${ctx.getString(if (ps.second.type == FxType.CHECKBOX) R.string.fb_type_checkbox else R.string.fb_type_text)}  \u00B7  ${ps.first + 1}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (scanned && all.isNotEmpty()) TextButton(onClick = {
                val pick = chosen.toList()
                onDismiss()
                st.edit(ctx.getString(R.string.fx_detect)) { doc ->
                    pick.groupBy { it.first }.forEach { (p, l) -> FieldRecognition.apply(doc, p, l.map { it.second }, null) }; pick.isNotEmpty()
                }
                st.suggestions = st.suggestions.mapValues { (p, l) -> l.filter { s -> pick.none { it.first == p && it.second == s } } }
            }, enabled = chosen.isNotEmpty()) { Text(stringResource(R.string.fx_create_selected)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

/** Fields panel: alphabetical or tab order; tapping a field selects it on its page. */
@Composable
private fun FieldListDialog(st: DesignerState, onDismiss: () -> Unit) {
    var alpha by remember { mutableStateOf(true) }
    val list = if (alpha) st.specs.sortedBy { it.name.lowercase() } else st.specs.sortedWith(compareBy({ it.page }, { -Math.round(it.rect.top / 6f) }, { it.rect.left }))
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.fx_fields)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(alpha, { alpha = true }, label = { Text(stringResource(R.string.fx_sort_alpha)) })
                    FilterChip(!alpha, { alpha = false }, label = { Text(stringResource(R.string.fx_sort_tab)) })
                }
                if (list.isEmpty()) Text(stringResource(R.string.fx_no_fields))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list) { s ->
                        Row(Modifier.fillMaxWidth().clickable {
                            st.mode = DesignMode.SELECT; st.page = s.page; st.selection.clear(); st.selection.add(FxRef(s.name, 0)); onDismiss()
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (s.locked) Icon(Icons.Default.Lock, null, Modifier.size(16.dp))
                            Text(s.name, Modifier.weight(1f).padding(start = 4.dp))
                            Text("${s.type.name.lowercase()} \u00B7 ${s.page + 1}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
