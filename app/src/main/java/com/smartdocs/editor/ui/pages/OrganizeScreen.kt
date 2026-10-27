package com.smartdocs.editor.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.launch
import java.io.File

/** Page organizer: thumbnails grid with reorder / insert / delete / rotate / extract / share / copy-paste / undo / save. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun OrganizeScreen(nav: NavHostController, uri: String?) {
    val writeBackOrganize = com.smartdocs.editor.ui.common.rememberWriteBack()
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { OrganizeState(ctx.applicationContext, app.documents, scope) }
    DisposableEffect(Unit) { onDispose { state.dispose() } }

    var pendingPassword by remember { mutableStateOf("") }
    var insertDialog by remember { mutableStateOf<InsertKind?>(null) }
    var lockedInsert by remember { mutableStateOf<Pair<java.io.File, Int>?>(null) }   // share6 (fid)
    var exitDialog by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<File?>(null) }
    var saving by remember { mutableStateOf(false) }
    var snack by remember { mutableStateOf<String?>(null) }
    // thumbnail zoom (Foxit 17 / Nitro 4)
    var cellScale by remember { mutableFloatStateOf(1f) }

    var lastFile by remember { mutableStateOf<File?>(null) }
    fun openFile(f: File) {
        lastFile = f
        val key = Uri.fromFile(f).toString()
        state.open(f) { OrganizeState.bookmarks(app.db.bookmarks(), key) + (if (uri != null && uri != key) OrganizeState.bookmarks(app.db.bookmarks(), uri) else emptySet()) }
    }
    LaunchedEffect(uri) { if (uri != null && state.original == null) runCatching { File(Uri.parse(uri).path!!) }.getOrNull()?.let { openFile(it) } }
    val pick = rememberPdfPicker { it.firstOrNull()?.let(::openFile) }
    val pickInsert = rememberPdfPicker { files -> files.firstOrNull()?.let { f -> insertDialog = InsertKind.File(f) } }
    val pickImages = rememberImagePicker { files -> if (files.isNotEmpty()) insertDialog = InsertKind.Images(files) }
    // gaps6: capture pages with the document scanner, then choose where to insert them
    val scanPages = com.smartdocs.editor.ui.gaps6.rememberCameraPages { files -> if (files.isNotEmpty()) insertDialog = InsertKind.Images(files, fromCamera = true) }

    // Foxit 23a: drop PDFs or images dragged from another app (split screen / desktop mode) to insert them
    val activity = ctx as? android.app.Activity
    val dropTarget = remember(activity) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val de = event.toAndroidDragEvent()
                activity?.let { runCatching { androidx.core.app.ActivityCompat.requestDragAndDropPermissions(it, de) } }
                val clip = de.clipData ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                if (uris.isEmpty()) return false
                scope.launch {
                    val files = uris.mapNotNull { u -> runCatching { app.documents.localFile(u) }.getOrNull() }
                    val pdf = files.firstOrNull { it.extension.equals("pdf", true) }
                    val images = files.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif") }
                    insertDialog = when { pdf != null -> InsertKind.File(pdf); images.isNotEmpty() -> InsertKind.Images(images); else -> null }
                }
                return true
            }
        }
    }
    val noSel = stringResource(R.string.pg_no_selection)
    val cannotDeleteAll = stringResource(R.string.pg_cannot_delete_all)

    fun requireSelection(block: () -> Unit) { if (state.selected.isEmpty()) snack = noSel else block() }
    fun leave() { if (state.dirty) exitDialog = true else nav.popBackStack() }
    BackHandler(enabled = state.selectMode || state.dirty) { if (state.selectMode) { state.selectMode = false; state.selected = emptySet() } else leave() }

    val snackHost = remember { SnackbarHostState() }
    LaunchedEffect(snack) { snack?.let { snackHost.showSnackbar(it); snack = null } }
    LaunchedEffect(state.error) { state.error?.let { if (it != "password") { snackHost.showSnackbar(it); state.error = null } } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackHost) },
        topBar = {
            AppTopBar(
                title = if (state.selectMode) stringResource(R.string.pg_selected_count, state.selected.size) else stringResource(R.string.tool_organize),
                onBack = { if (state.selectMode) { state.selectMode = false; state.selected = emptySet() } else leave() },
            ) {
                if (state.original != null) {
                    IconButton(onClick = { state.undo() }, enabled = state.canUndo && !state.busy) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                    IconButton(onClick = {
                        if (state.selectMode && state.selected.size == state.pageCount) { state.selected = emptySet(); state.selectMode = false }
                        else { state.selected = state.visiblePages().toSet(); state.selectMode = true }
                    }) { Icon(Icons.Default.SelectAll, stringResource(R.string.select_all)) }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.Save, stringResource(R.string.save)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.pg_save_overwrite)) }, enabled = state.dirty, onClick = {
                                menu = false; saving = true
                                scope.launch { try { result = state.saveOverwrite(); result?.let { writeBackOrganize(it) } } catch (e: Exception) { snack = e.message } finally { saving = false } }
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pg_save_copy)) }, onClick = {
                                menu = false; saving = true
                                scope.launch { try { result = state.saveAsNew() } catch (e: Exception) { snack = e.message } finally { saving = false } }
                            })
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (state.original != null) OrganizeToolbar(
                state = state,
                onInsertBlank = { insertDialog = InsertKind.Blank },
                onInsertFile = pickInsert,
                onInsertImage = pickImages,
                onInsertCamera = scanPages,
                onDelete = { requireSelection { if (state.selected.size >= state.pageCount) snack = cannotDeleteAll else state.deleteSelected() } },
                onExtract = {
                    requireSelection { saving = true; scope.launch { try { result = state.extractSelected() } catch (e: Exception) { snack = e.message } finally { saving = false } } }
                },
                onShare = {
                    requireSelection {
                        saving = true
                        scope.launch {
                            try { state.selectedToTemp()?.let { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(it)), null)) } }
                            catch (e: Exception) { snack = e.message } finally { saving = false }
                        }
                    }
                },
                onPrint = {
                    // nav-batch: Foxit "Print Pages" from the thumbnails selection
                    requireSelection {
                        saving = true
                        scope.launch {
                            try { state.selectedToTemp()?.let { com.smartdocs.editor.core.util.PdfPrint.print(ctx, it, state.original?.nameWithoutExtension ?: "pages", state.selected.size) } }
                            catch (e: Exception) { snack = e.message } finally { saving = false }
                        }
                    }
                },
                onMessage = { snack = it },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (state.original == null) {
                Column(Modifier.padding(16.dp)) {
                    FilePickRow(null, stringResource(R.string.open_file), pick)
                    if (state.error == "password") PasswordPrompt(pendingPassword, { pendingPassword = it }) {
                        state.password = pendingPassword; state.error = null
                        lastFile?.let(::openFile)
                    }
                    Spacer(Modifier.height(24.dp))
                    EmptyState(Icons.Default.AutoAwesomeMotion, stringResource(R.string.page_reorder_hint))
                }
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PageFilter.entries.forEach { f ->
                        FilterChip(
                            selected = state.filter == f, onClick = { state.filter = f },
                            label = { Text(stringResource(when (f) { PageFilter.ALL -> R.string.pg_filter_all; PageFilter.ANNOTATED -> R.string.pg_filter_annotated; PageFilter.BOOKMARKED -> R.string.pg_filter_bookmarked })) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { cellScale = (cellScale / 1.25f).coerceAtLeast(0.6f) }, enabled = cellScale > 0.6f) { Icon(Icons.Default.ZoomOut, stringResource(R.string.pc_thumbs_smaller)) }
                    IconButton(onClick = { cellScale = (cellScale * 1.25f).coerceAtMost(2.5f) }, enabled = cellScale < 2.5f) { Icon(Icons.Default.ZoomIn, stringResource(R.string.pc_thumbs_larger)) }
                    Text(stringResource(R.string.page_count) + ": ${state.pageCount}", style = MaterialTheme.typography.labelMedium)
                    if (state.changes > 0) Text(" · " + stringResource(R.string.pg_changes_count, state.changes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                val pages = state.visiblePages()
                if (pages.isEmpty() && state.pageCount > 0) EmptyState(Icons.Default.FilterAltOff, stringResource(R.string.pg_no_pages_filter))
                else BoxWithConstraints(Modifier.fillMaxSize().dragAndDropTarget(
                    shouldStartDragAndDrop = { e -> e.mimeTypes().any { it == "application/pdf" || it.startsWith("image/") } }, target = dropTarget)) {
                    val tablet = maxWidth > 700.dp
                    PageGrid(
                        state = state, pages = pages, minCell = (if (tablet) 150.dp else 105.dp) * cellScale,
                        pageActions = listOf(
                            pageAct(R.string.pc_page_rotate) { p -> state.selected = setOf(p); state.rotateSelected(90) },
                            pageAct(R.string.pc_page_delete) { p -> if (state.pageCount <= 1) snack = cannotDeleteAll else { state.selected = setOf(p); state.deleteSelected() } },
                            pageAct(R.string.pc_page_print) { p ->
                                state.selected = setOf(p)
                                scope.launch { runCatching { state.selectedToTemp()?.let { com.smartdocs.editor.core.util.PrintHelper.printPdf(ctx, it) } }.onFailure { snack = it.message }; state.selected = emptySet() }
                            },
                            // redaction and OCR work on the current version of the document
                            pageAct(R.string.pc_page_redact) { _ -> state.work?.let { nav.navigate(com.smartdocs.editor.ui.Routes.tool("redact", Uri.fromFile(it).toString())) } },
                            pageAct(R.string.pc_page_ocr) { p -> state.work?.let { nav.navigate(com.smartdocs.editor.ui.Routes.reader(Uri.fromFile(it).toString(), p)) } },
                        ),
                        dragEnabled = state.filter == PageFilter.ALL && !state.busy,
                        onTap = { p -> if (state.selectMode) state.toggle(p) else { state.selectMode = true; state.selected = setOf(p) } },
                        onLongPress = { p -> state.selectMode = true; state.selected = state.selected + p },
                        onReorder = { from, to ->
                            val order = (0 until state.pageCount).toMutableList()
                            val moved = order.removeAt(from); order.add(to, moved)
                            state.reorder(order)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (state.busy && state.original != null) ProgressDialog(stringResource(R.string.pg_applying))
    if (saving) ProgressDialog(stringResource(R.string.processing))
    result?.let { ResultSheet(it, nav, onDismiss = { result = null }) }

    insertDialog?.let { kind -> InsertDialog(state, kind, onDismiss = { insertDialog = null }, onLockedFile = { f, at -> lockedInsert = f to at }) }
    // share6 (fid): inserting pages from a password-protected PDF
    lockedInsert?.let { (other, at) ->
        com.smartdocs.editor.ui.tools.FilePasswordDialog(other, onUnlocked = { pw -> lockedInsert = null; state.insertFrom(other, at, pw) }, onRemove = { lockedInsert = null })
    }

    if (exitDialog) AlertDialog(
        onDismissRequest = { exitDialog = false },
        title = { Text(stringResource(R.string.tool_organize)) },
        text = { Text(stringResource(R.string.pg_unsaved)) },
        confirmButton = { TextButton(onClick = { exitDialog = false; nav.popBackStack() }) { Text(stringResource(R.string.pg_discard)) } },
        dismissButton = { TextButton(onClick = { exitDialog = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PasswordPrompt(value: String, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(stringResource(R.string.password_enter), color = MaterialTheme.colorScheme.error)
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.password)) }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Button(onClick = onSubmit, Modifier.align(Alignment.End).padding(top = 8.dp)) { Text(stringResource(R.string.open)) }
    }
}

/** What the insert dialog is about to insert. */
sealed class InsertKind {
    data object Blank : InsertKind()
    data class File(val file: java.io.File) : InsertKind()
    data class Images(val files: List<java.io.File>, val fromCamera: Boolean = false) : InsertKind()
}

@Composable
private fun InsertDialog(state: OrganizeState, kind: InsertKind, onDismiss: () -> Unit, onLockedFile: (java.io.File, Int) -> Unit = { _, _ -> }) {
    val ref = state.selected.minOrNull()
    // share6 (fid): a locked source file asks for its password before inserting
    val needsPw by produceState(false, kind) {
        value = (kind as? InsertKind.File)?.let { k -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.smartdocs.editor.core.pdf.LockedPdf.needsPassword(k.file) } } ?: false
    }
    var position by remember { mutableIntStateOf(if (ref == null) 2 else 1) } // 0 before, 1 after, 2 end
    var style by remember { mutableStateOf(PdfOps.PageStyle.BLANK) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(when (kind) { InsertKind.Blank -> R.string.page_insert_blank; is InsertKind.File -> R.string.page_insert_file; is InsertKind.Images -> if (kind.fromCamera) R.string.g6_insert_camera else R.string.page_insert_image })) },
        text = {
            Column {
                Text(stringResource(R.string.pg_insert_position), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ref != null) {
                        FilterChip(position == 0, { position = 0 }, label = { Text(stringResource(R.string.pg_insert_before) + " ${ref + 1}") })
                        FilterChip(position == 1, { position = 1 }, label = { Text(stringResource(R.string.pg_insert_after) + " ${ref + 1}") })
                    }
                    FilterChip(position == 2, { position = 2 }, label = { Text(stringResource(R.string.pg_at_end)) })
                }
                if (kind is InsertKind.Blank) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.page_style), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PdfOps.PageStyle.entries.forEach { s ->
                            FilterChip(style == s, { style = s }, label = { Text(stringResource(when (s) { PdfOps.PageStyle.BLANK -> R.string.style_blank; PdfOps.PageStyle.LINED -> R.string.style_lined; PdfOps.PageStyle.GRID -> R.string.style_grid; PdfOps.PageStyle.DOTS -> R.string.style_dots })) })
                        }
                    }
                }
                if (kind is InsertKind.Images) Text(stringResource(R.string.pg_scan_pages, kind.files.size), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val at = when (position) { 0 -> ref ?: state.pageCount; 1 -> (ref ?: -1) + 1; else -> state.pageCount }
                when (kind) {
                    InsertKind.Blank -> state.insertBlank(at, style)
                    is InsertKind.File -> if (needsPw) onLockedFile(kind.file, at) else state.insertFrom(kind.file, at)
                    is InsertKind.Images -> state.insertImages(kind.files, at)
                }
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Bottom action bar; scrolls horizontally on phones. */
@Composable
private fun OrganizeToolbar(
    state: OrganizeState,
    onInsertBlank: () -> Unit, onInsertFile: () -> Unit, onInsertImage: () -> Unit, onInsertCamera: () -> Unit,
    onDelete: () -> Unit, onExtract: () -> Unit, onShare: () -> Unit, onMessage: (String) -> Unit, onPrint: () -> Unit = {},
) {
    val noSel = stringResource(R.string.pg_no_selection)
    val single = state.selected.size == 1
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolbarButton(Icons.Default.NoteAdd, stringResource(R.string.page_insert_blank), onClick = onInsertBlank)
            ToolbarButton(Icons.Default.FileOpen, stringResource(R.string.page_insert_file), onClick = onInsertFile)
            ToolbarButton(Icons.Default.Image, stringResource(R.string.page_insert_image), onClick = onInsertImage)
            ToolbarButton(Icons.Default.PhotoCamera, stringResource(R.string.g6_insert_camera), onClick = onInsertCamera)
            VerticalDivider(Modifier.height(36.dp).padding(horizontal = 2.dp))
            ToolbarButton(Icons.Default.RotateLeft, stringResource(R.string.page_rotate_left)) { if (state.selected.isEmpty()) onMessage(noSel) else state.rotateSelected(-90) }
            ToolbarButton(Icons.Default.RotateRight, stringResource(R.string.page_rotate_right)) { if (state.selected.isEmpty()) onMessage(noSel) else state.rotateSelected(90) }
            ToolbarButton(Icons.Default.Delete, stringResource(R.string.page_delete), onClick = onDelete)
            VerticalDivider(Modifier.height(36.dp).padding(horizontal = 2.dp))
            ToolbarButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.pg_move_left), enabled = single) { state.moveSelected(-1) }
            ToolbarButton(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.pg_move_right), enabled = single) { state.moveSelected(1) }
            ToolbarButton(Icons.Default.ContentCopy, stringResource(R.string.pg_copy)) { if (state.selected.isEmpty()) onMessage(noSel) else state.copySelected() }
            ToolbarButton(Icons.Default.ContentPaste, stringResource(R.string.pg_paste), enabled = state.clipboard.isNotEmpty()) { state.paste(state.selected.maxOrNull() ?: -1) }
            VerticalDivider(Modifier.height(36.dp).padding(horizontal = 2.dp))
            ToolbarButton(Icons.Default.ContentCut, stringResource(R.string.page_extract), onClick = onExtract)
            ToolbarButton(Icons.Default.Share, stringResource(R.string.page_share), onClick = onShare)
            ToolbarButton(Icons.Default.Print, stringResource(R.string.print), onClick = onPrint)
        }
    }
}

@Composable
private fun ToolbarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).padding(vertical = 4.dp).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}

/** One entry of the per-thumbnail menu (typed so every action is a plain (Int) -> Unit). */
private fun pageAct(label: Int, act: (Int) -> Unit): Pair<Int, (Int) -> Unit> = label to act
