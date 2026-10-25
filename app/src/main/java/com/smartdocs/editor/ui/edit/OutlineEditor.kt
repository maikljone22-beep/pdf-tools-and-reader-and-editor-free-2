package com.smartdocs.editor.ui.edit

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit.OutlineModel
import com.smartdocs.editor.core.edit.OutlineRow
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

// ================= shared editor =================

/**
 * Tree editor of a document outline held as a flat pre-order list. Tap = select, drag the handle up/down to
 * reorder and sideways to indent / outdent, menu = rename / go to / move / indent / delete.
 */
@Composable
private fun OutlineEditorBody(
    rows: List<OutlineRow>, onRows: (List<OutlineRow>) -> Unit,
    currentPage: Int, pageCount: Int, onGoTo: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }        // index being renamed; -1 = new entry
    var confirmDelete by remember { mutableStateOf<Int?>(null) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val rowH = 52.dp
    val rowHPx = with(density) { rowH.toPx() }
    val indentPx = with(density) { 28.dp.toPx() }
    val listState = rememberLazyListState()
    val curRows by rememberUpdatedState(rows)
    val curOnRows by rememberUpdatedState(onRows)
    val curRtl by rememberUpdatedState(rtl)

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) EmptyState(Icons.Default.Toc, stringResource(R.string.ed_outline_empty), Modifier.align(Alignment.Center))
            else LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { _, r -> r.key }) { index, row ->
                    val idx by rememberUpdatedState(index)
                    var dragY by remember { mutableFloatStateOf(0f) }
                    var dragX by remember { mutableFloatStateOf(0f) }
                    val dragging = dragY != 0f || dragX != 0f
                    val isSel = selected == row.key
                    Row(
                        Modifier.fillMaxWidth().height(rowH)
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = dragY; translationX = dragX; shadowElevation = if (dragging) 8f else 0f }
                            .background(if (isSel || dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                            .clickable { selected = if (isSel) null else row.key }
                            .padding(start = (12 + row.level * 24).dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.DragIndicator, stringResource(R.string.ed_outline_drag),
                            Modifier.size(28.dp).pointerInput(row.key) {
                                detectDragGestures(
                                    onDragStart = { selected = row.key },
                                    onDragEnd = {
                                        val steps = (dragY / rowHPx).roundToInt()
                                        val levelDelta = ((if (curRtl) -dragX else dragX) / indentPx).roundToInt()
                                        dragY = 0f; dragX = 0f
                                        if (steps != 0 || levelDelta != 0) {
                                            val i = idx
                                            val list = curRows
                                            if (i in list.indices) {
                                                val (moved, _) = OutlineModel.move(list, i, i + steps, list[i].level + levelDelta)
                                                curOnRows(moved)
                                            }
                                        }
                                    },
                                    onDragCancel = { dragY = 0f; dragX = 0f },
                                ) { ch, d -> ch.consume(); dragY += d.y; dragX += d.x }
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(row.title.ifBlank { stringResource(R.string.ed_outline_untitled) }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = if (row.level == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { if (row.page >= 0) onGoTo(row.page) }, enabled = row.page >= 0) {
                            Text(if (row.page >= 0) stringResource(R.string.ed_outline_page, row.page + 1) else "—")
                        }
                        RowMenu(
                            onRename = { editing = idx },
                            onUp = { val (r, _) = OutlineModel.moveUp(rows, idx); onRows(r) },
                            onDown = { val (r, _) = OutlineModel.moveDown(rows, idx); onRows(r) },
                            onIndent = { onRows(OutlineModel.indent(rows, idx)) },
                            onOutdent = { onRows(OutlineModel.outdent(rows, idx)) },
                            onDelete = { confirmDelete = idx },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ed_outline_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { editing = -1 }) {
                    Icon(Icons.Default.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ed_outline_add))
                }
            }
        }
    }

    editing?.let { i ->
        val existing = rows.getOrNull(i)
        EntryDialog(
            title = stringResource(if (existing == null) R.string.ed_outline_add_title else R.string.ed_outline_edit_title),
            initialTitle = existing?.title ?: "", initialPage = (existing?.page?.takeIf { it >= 0 } ?: currentPage) + 1, pageCount = pageCount,
            onDismiss = { editing = null },
        ) { title, page ->
            editing = null
            if (existing == null) {
                val after = rows.indexOfFirst { it.key == selected }
                onRows(OutlineModel.insert(rows, after, title, page))
            } else onRows(OutlineModel.update(rows, i, title, page))
        }
    }
    confirmDelete?.let { i ->
        val sub = OutlineModel.subtreeEnd(rows, i) - i - 1
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.ed_outline_delete_title)) },
            text = { Text(if (sub > 0) stringResource(R.string.ed_outline_delete_children, sub) else stringResource(R.string.ed_outline_delete_msg)) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; onRows(OutlineModel.delete(rows, i)) }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RowMenu(onRename: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onIndent: () -> Unit, onOutdent: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.ed_outline_up)) }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) }, onClick = { open = false; onUp() })
            DropdownMenuItem(text = { Text(stringResource(R.string.ed_outline_down)) }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) }, onClick = { open = false; onDown() })
            DropdownMenuItem(text = { Text(stringResource(R.string.ed_outline_indent)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, null) }, onClick = { open = false; onIndent() })
            DropdownMenuItem(text = { Text(stringResource(R.string.ed_outline_outdent)) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, null) }, onClick = { open = false; onOutdent() })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { open = false; onDelete() })
        }
    }
}

@Composable
private fun EntryDialog(title: String, initialTitle: String, initialPage: Int, pageCount: Int, onDismiss: () -> Unit, onOk: (String, Int) -> Unit) {
    var name by remember { mutableStateOf(initialTitle) }
    var page by remember { mutableStateOf(initialPage.toString()) }
    val pageNum = page.trim().toIntOrNull()
    val pageOk = pageNum != null && pageNum in 1..pageCount.coerceAtLeast(1)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ed_outline_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(page, { page = it.filter { ch -> ch.isDigit() }.take(6) }, label = { Text(stringResource(R.string.ed_outline_page_label, pageCount)) },
                    singleLine = true, isError = !pageOk, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onOk(name.trim(), (pageNum ?: 1) - 1) }, enabled = name.isNotBlank() && pageOk) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ================= sheet (reader panel) =================

/** Outline editor as a bottom sheet over the reader; saving replaces the open document in place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlineEditorSheet(state: ReaderState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<OutlineRow>?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.file, state.renderVersion) {
        if (dirty) return@LaunchedEffect
        val f = state.file ?: return@LaunchedEffect
        rows = runCatching { OutlineModel.load(f, state.password) }.onFailure { error = it.message }.getOrNull() ?: emptyList()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ed_outline_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (saving) CircularProgressIndicator(Modifier.size(24.dp))
                else Button(onClick = {
                    val r = rows ?: return@Button
                    saving = true
                    scope.launch {
                        try {
                            applyEdit(state) { src, out -> OutlineModel.save(src, out, state.password, r) }
                            dirty = false; error = null; onDismiss()
                        } catch (e: Throwable) { error = ctx.getString(R.string.ed_failed, e.message ?: "") } finally { saving = false }
                    }
                }, enabled = dirty) { Text(stringResource(R.string.save)) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            val r = rows
            if (r == null) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else OutlineEditorBody(r, { rows = it; dirty = true }, state.currentPage, state.pageCount,
                onGoTo = { p -> state.jumpTo(p); if (!dirty) onDismiss() }, Modifier.weight(1f))
        }
    }
}

// ================= full screen (route "outline") =================

/** Outline editor screen (tool route id "outline"); [uri] is the `file://` document to edit, or null to pick one. */
@Composable
fun OutlineEditorScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember(uri) { mutableStateOf(uri?.let { runCatching { File(Uri.parse(it).path!!) }.getOrNull()?.takeIf { f -> f.exists() } }) }
    var rows by remember { mutableStateOf<List<OutlineRow>?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var askDiscard by remember { mutableStateOf(false) }
    val pick = rememberPdfPicker { files -> files.firstOrNull()?.let { file = it; rows = null; dirty = false } }

    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        try {
            pageCount = PdfOps.pageCount(f)
            rows = OutlineModel.load(f, null)
        } catch (e: Throwable) { rows = emptyList(); message = ctx.getString(R.string.ed_load_failed, e.message ?: "") }
    }
    fun save() {
        val f = file ?: return
        val r = rows ?: return
        saving = true
        scope.launch {
            try {
                val tmp = File(f.parentFile, ".${f.nameWithoutExtension}.outline.pdf")
                OutlineModel.save(f, tmp, null, r)
                withContext(Dispatchers.IO) { if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() } }
                runCatching { app.documents.writeBack(f) }
                runCatching { app.documents.touchRecent(f, pageCount) }
                dirty = false
                message = ctx.getString(R.string.ed_outline_saved)
            } catch (e: Throwable) { message = ctx.getString(R.string.ed_failed, e.message ?: "") } finally { saving = false }
        }
    }
    val back: () -> Unit = { if (dirty) { askDiscard = true } else { nav.popBackStack() } }
    BackHandler(enabled = dirty) { askDiscard = true }

    Scaffold(topBar = {
        AppTopBar(stringResource(R.string.ed_outline_title), onBack = { back() }) {
            if (file != null) IconButton(onClick = { pick() }) { Icon(Icons.Default.FolderOpen, stringResource(R.string.ed_outline_pick)) }
            if (saving) CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp))
            else IconButton(onClick = { save() }, enabled = dirty) { Icon(Icons.Default.Save, stringResource(R.string.save)) }
        }
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            message?.let { m ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable { message = null }) {
                    Text(m, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            val f = file
            val r = rows
            when {
                f == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Default.Toc, stringResource(R.string.ed_outline_pick_hint)) { Button(onClick = { pick() }) { Text(stringResource(R.string.ed_outline_pick)) } }
                }
                r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    Text(f.name, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    OutlineEditorBody(r, { rows = it; dirty = true }, 0, pageCount,
                        onGoTo = { p -> if (dirty) message = ctx.getString(R.string.ed_outline_save_first) else nav.navigate(Routes.reader(Uri.fromFile(f).toString(), p)) },
                        Modifier.weight(1f))
                }
            }
        }
    }
    if (askDiscard) AlertDialog(
        onDismissRequest = { askDiscard = false },
        title = { Text(stringResource(R.string.ed_outline_unsaved)) },
        confirmButton = { TextButton(onClick = { askDiscard = false; save() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { askDiscard = false; dirty = false; nav.popBackStack() }) { Text(stringResource(R.string.ed_discard)) } },
    )
}
