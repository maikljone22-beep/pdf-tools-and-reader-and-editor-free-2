package com.smartdocs.editor.ui.pages

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.asImageBitmap
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
import com.smartdocs.editor.core.selmenu.SelAction
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
    // audit 5 G-06: back from a tool screen that got a copy of the current version (Redact): adopt its change, if any
    @Suppress("DEPRECATION") val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) state.takeBack() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var pendingPassword by remember { mutableStateOf("") }
    var insertDialog by remember { mutableStateOf<InsertKind?>(null) }
    var lockedInsert by remember { mutableStateOf<Pair<java.io.File, Int>?>(null) }   // share6 (fid)
    var exitDialog by remember { mutableStateOf(false) }
    // audit 5 G-02: Overwrite of a file opened without owner access replaces the author's owner password; warned once
    var ownerWarned by remember { mutableStateOf(false) }
    var ownerDialog by remember { mutableStateOf<Boolean?>(null) }   // pending overwrite; true = leave afterwards
    var result by remember { mutableStateOf<File?>(null) }
    var saving by remember { mutableStateOf(false) }
    var snack by remember { mutableStateOf<String?>(null) }
    var redactAsk by remember { mutableStateOf<Int?>(null) }
    val ocrHasText = stringResource(R.string.pa_ocr_has_text)
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
                    // audit 5 (PAGES, Low): the dropped files are copied off the main thread
                    val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { uris.mapNotNull { u -> runCatching { app.documents.localFile(u) }.getOrNull() } }
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
    // audit 5 G-03: the author's permissions, checked by the organizer itself
    val restrictedMsg = stringResource(R.string.np2_restricted_author)
    val saveFailedMsg = stringResource(R.string.ttpg_save_failed)
    val openSaveFirst = stringResource(R.string.ttpg_open_save_first)
    fun requirePermission(action: com.smartdocs.editor.core.security.DocAction, block: () -> Unit) { if (!state.allows(action)) snack = restrictedMsg else block() }

    /** Overwrite the original; [leave] = then close the organizer (exit dialog). */
    fun doOverwrite(leave: Boolean) {
        saving = true
        scope.launch {
            try {
                val saved = state.saveOverwrite()
                if (saved != null && leave) {
                    // the write-back to the origin must finish before the screen (and its scope) goes away
                    when (app.documents.writeBack(saved)) {
                        com.smartdocs.editor.core.files.DocumentRepository.WriteBack.Done -> { android.widget.Toast.makeText(ctx, ctx.getString(R.string.wb_saved_original), android.widget.Toast.LENGTH_SHORT).show(); nav.popBackStack() }
                        com.smartdocs.editor.core.files.DocumentRepository.WriteBack.NotNeeded -> nav.popBackStack()
                        else -> writeBackOrganize(saved)   // asks for access / offers a copy; the user leaves afterwards
                    }
                } else { result = saved; saved?.let { writeBackOrganize(it) } }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { snack = saveFailedMsg } finally { saving = false }
        }
    }
    fun overwrite(leave: Boolean) { if (state.ownerPasswordWillChange && !ownerWarned) ownerDialog = leave else doOverwrite(leave) }
    fun leave() { if (state.dirty) exitDialog = true else nav.popBackStack() }

    /** audit 5 G-14: open page [p] in the reader (the one under the organizer when there is one). */
    fun openPage(p: Int) {
        val o = state.original ?: return
        if (state.dirty) { snack = openSaveFirst; return }
        val prev = nav.previousBackStackEntry
        val fromReader = uri != null && runCatching { Uri.parse(uri).path == o.path }.getOrDefault(false)
        if (fromReader && prev != null && prev.destination.route == com.smartdocs.editor.ui.Routes.READER) {
            // the reader under us shows this file: it jumps to the page on return (key read by ReaderScreen)
            prev.savedStateHandle["ttpg_open_page"] = p
            nav.popBackStack()
        } else nav.navigate(com.smartdocs.editor.ui.Routes.reader(Uri.fromFile(o).toString(), p))
    }
    // fix/thumbs-panel: the thumbnail long-press menu (selection-menu registry, SelCtx.THUMB); a picked PDF is inserted
    // after the pages (false) or replaces them (true)
    var thumbMenu by remember { mutableStateOf<Int?>(null) }
    var thumbPick by remember { mutableStateOf<Pair<Boolean, List<Int>>?>(null) }
    var thumbLocked by remember { mutableStateOf<Pair<File, Pair<Boolean, List<Int>>>?>(null) }
    fun useThumbFile(f: File, t: Pair<Boolean, List<Int>>, pw: String?) {
        if (t.first) state.replacePages(t.second, f, pw) else state.insertFrom(f, t.second.max() + 1, pw)
    }
    val pickThumbFile = com.smartdocs.editor.ui.common.rememberAnyDocPicker { files ->   // FINAL_CHECK_2 #7: pages from any document
        val f = files.firstOrNull(); val t = thumbPick; thumbPick = null
        if (f != null && t != null) scope.launch {
            if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.smartdocs.editor.core.pdf.LockedPdf.needsPassword(f) }) thumbLocked = f to t
            else useThumbFile(f, t, null)
        }
    }
    fun runThumb(a: SelAction, pages: List<Int>, p: Int) {
        when (a) {
            SelAction.THUMB_OPEN -> openPage(p)
            SelAction.THUMB_SELECT -> { state.selectMode = true; state.selected = state.selected + p }
            SelAction.THUMB_DESELECT -> state.toggle(p)
            SelAction.THUMB_ROTATE_LEFT -> state.rotatePages(pages.toSet(), -90, keepSelection = true)
            SelAction.THUMB_ROTATE_RIGHT -> state.rotatePages(pages.toSet(), 90, keepSelection = true)
            SelAction.THUMB_DUPLICATE -> state.duplicatePages(pages)
            SelAction.THUMB_DELETE -> if (pages.size >= state.pageCount) snack = cannotDeleteAll else state.deletePages(pages.toSet(), keepSelection = true)
            SelAction.THUMB_EXTRACT -> requirePermission(com.smartdocs.editor.core.security.DocAction.COPY) {
                saving = true
                scope.launch { try { result = state.extractPages(pages) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { snack = state.message(e) } finally { saving = false } }
            }
            SelAction.THUMB_INSERT_BLANK -> state.insertBlank(pages.max() + 1, PdfOps.PageStyle.BLANK)
            SelAction.THUMB_INSERT_FILE -> { thumbPick = false to pages; pickThumbFile() }
            SelAction.THUMB_REPLACE -> { thumbPick = true to pages; pickThumbFile() }
            SelAction.THUMB_PRINT -> requirePermission(com.smartdocs.editor.core.security.DocAction.PRINT) {
                saving = true
                scope.launch { try { state.printPages(pages) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { snack = state.message(e) } finally { saving = false } }
            }
            else -> Unit
        }
    }
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
                    // audit 5 G-12: toggles on the visible (filtered) pages
                    IconButton(onClick = { state.toggleSelectAll() }) { Icon(Icons.Default.SelectAll, stringResource(R.string.select_all)) }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.Save, stringResource(R.string.save)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.pg_save_overwrite)) }, enabled = state.dirty && !state.busy, onClick = {
                                menu = false; overwrite(leave = false)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.pg_save_copy)) }, onClick = {
                                menu = false; saving = true
                                scope.launch { try { result = state.saveAsNew() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { snack = saveFailedMsg } finally { saving = false } }
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
                    // audit 5 G-03: extracting pages is copying content
                    requireSelection { requirePermission(com.smartdocs.editor.core.security.DocAction.COPY) {
                        saving = true; scope.launch { try { result = state.extractSelected() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { snack = state.message(e) } finally { saving = false } }
                    } }
                },
                onShare = {
                    requireSelection { requirePermission(com.smartdocs.editor.core.security.DocAction.COPY) {
                        saving = true
                        scope.launch {
                            try { state.selectedToTemp()?.let { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(it)), null)) } }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { snack = state.message(e) } finally { saving = false }
                        }
                    } }
                },
                onPrint = {
                    // nav-batch: Foxit "Print Pages" from the thumbnails selection
                    // audit 5 G-03 / G-09: PRINT permission, and the one shared print path (V5 pipeline)
                    requireSelection { requirePermission(com.smartdocs.editor.core.security.DocAction.PRINT) {
                        saving = true
                        scope.launch {
                            try { state.printPages(state.selected) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { snack = state.message(e) } finally { saving = false }
                        }
                    } }
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
                            // audit 5 G-14: open the page in the reader
                            pageAct(R.string.ttpg_open_page) { p -> openPage(p) },
                            // audit 5 G-08: the page's own action; the user's multi-selection is kept
                            pageAct(R.string.pc_page_rotate) { p -> state.rotatePages(setOf(p), 90, keepSelection = true) },
                            pageAct(R.string.pc_page_delete) { p -> if (state.pageCount <= 1) snack = cannotDeleteAll else state.deletePages(setOf(p), keepSelection = true) },
                            pageAct(R.string.pc_page_print) { p ->
                                requirePermission(com.smartdocs.editor.core.security.DocAction.PRINT) {
                                    saving = true
                                    scope.launch {
                                        try { state.printPages(listOf(p)) }
                                        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { snack = state.message(e) } finally { saving = false }
                                    }
                                }
                            },
                            // redaction acts on the tapped page of the working version (undo brings it back); audit 5 G-03: MODIFY
                            pageAct(R.string.pc_page_redact) { p -> requirePermission(com.smartdocs.editor.core.security.DocAction.MODIFY) { redactAsk = p } },
                            // audit 5 G-06: OCR of this page in place (one undoable organizer step), no second reader
                            pageAct(R.string.pc_page_ocr) { p -> requirePermission(com.smartdocs.editor.core.security.DocAction.MODIFY) { state.ocrPage(p) } },
                        ),
                        dragEnabled = state.filter == PageFilter.ALL && !state.busy,
                        onTap = { p -> if (state.selectMode) state.toggle(p) else { state.selectMode = true; state.selected = setOf(p) } },
                        // fix/thumbs-panel: long-press opens the thumbnail menu (Select is one of its items); long-press + drag still moves
                        onLongPress = { p -> if (!state.busy) thumbMenu = p },
                        menuPage = thumbMenu,
                        menu = { p ->
                            com.smartdocs.editor.ui.selmenu.ThumbSelMenu(
                                com.smartdocs.editor.ui.selmenu.organizeThumbEnv(state::allows, com.smartdocs.editor.LocalSettings.current.aiEnabled),
                                p, state.selected, state.pageCount, onDismiss = { thumbMenu = null }, onItem = { a, pages -> runThumb(a, pages, p) })
                        },
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

    if (state.busy && state.original != null) ProgressDialog(state.progress ?: stringResource(R.string.pg_applying))
    if (saving) ProgressDialog(stringResource(R.string.processing))
    result?.let { ResultSheet(it, nav, onDismiss = { result = null }) }

    insertDialog?.let { kind -> InsertDialog(state, kind, onDismiss = { insertDialog = null }, onLockedFile = { f, at -> lockedInsert = f to at }) }
    // share6 (fid): inserting pages from a password-protected PDF
    lockedInsert?.let { (other, at) ->
        com.smartdocs.editor.ui.tools.FilePasswordDialog(other, onUnlocked = { pw -> lockedInsert = null; state.insertFrom(other, at, pw) }, onRemove = { lockedInsert = null })
    }

    thumbLocked?.let { (other, t) ->
        com.smartdocs.editor.ui.tools.FilePasswordDialog(other, onUnlocked = { pw -> thumbLocked = null; useThumbFile(other, t, pw) }, onRemove = { thumbLocked = null })
    }

    // audit 5 (PAGES, Low): the exit dialog also offers Save (overwrite the original, then leave)
    redactAsk?.let { p ->
        AlertDialog(onDismissRequest = { redactAsk = null }, title = { Text(stringResource(R.string.pa_redact_page_q, p + 1)) },
            text = { Text(stringResource(R.string.pa_redact_page_sub)) },
            confirmButton = { TextButton(onClick = { redactAsk = null; state.redactPage(p) }) { Text(stringResource(R.string.pc_page_redact)) } },
            dismissButton = { TextButton(onClick = { redactAsk = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (exitDialog) AlertDialog(
        onDismissRequest = { exitDialog = false },
        title = { Text(stringResource(R.string.tool_organize)) },
        text = { Text(stringResource(R.string.ttpg_unsaved_save)) },
        confirmButton = { TextButton(onClick = { exitDialog = false; overwrite(leave = true) }, enabled = !state.busy) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { exitDialog = false; nav.popBackStack() }) { Text(stringResource(R.string.pg_discard)) }
                TextButton(onClick = { exitDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
    ownerDialog?.let { leaveAfter ->
        AlertDialog(
            onDismissRequest = { ownerDialog = null },
            title = { Text(stringResource(R.string.ttpg_owner_title)) },
            text = { Text(stringResource(R.string.ttpg_owner_warn)) },
            confirmButton = { TextButton(onClick = { ownerDialog = null; ownerWarned = true; doOverwrite(leaveAfter) }) { Text(stringResource(R.string.ttpg_owner_continue)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        ownerDialog = null; saving = true
                        scope.launch { try { result = state.saveAsNew() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { snack = saveFailedMsg } finally { saving = false } }
                    }) { Text(stringResource(R.string.pg_save_copy)) }
                    TextButton(onClick = { ownerDialog = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
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
    // FINAL_CHECK_2 Low: blank page size — like the neighbour, a preset, or custom width × height in mm / in / pt
    var sizeKind by remember { mutableIntStateOf(0) }   // 0 neighbour, 1 A4, 2 Letter, 3 custom
    var landscape by remember { mutableStateOf(false) }
    var unit by remember { mutableIntStateOf(0) }       // 0 mm, 1 in, 2 pt
    var wText by remember { mutableStateOf("210") }
    var hText by remember { mutableStateOf("297") }
    fun customSize(): com.tom_roush.pdfbox.pdmodel.common.PDRectangle? {
        val k = when (unit) { 0 -> 72f / 25.4f; 1 -> 72f; else -> 1f }
        val w = wText.replace(',', '.').toFloatOrNull()?.times(k) ?: return null
        val h = hText.replace(',', '.').toFloatOrNull()?.times(k) ?: return null
        if (w < 18f || h < 18f || w > 14400f || h > 14400f) return null
        return com.tom_roush.pdfbox.pdmodel.common.PDRectangle(w, h)
    }
    fun chosenSize(): com.tom_roush.pdfbox.pdmodel.common.PDRectangle? {
        val base = when (sizeKind) { 1 -> com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4; 2 -> com.tom_roush.pdfbox.pdmodel.common.PDRectangle.LETTER; 3 -> customSize(); else -> null } ?: return null
        return if (landscape == (base.width > base.height)) base else com.tom_roush.pdfbox.pdmodel.common.PDRectangle(base.height, base.width)
    }
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
                    Text(stringResource(R.string.pg_blank_size), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(R.string.pg_size_neighbour, R.string.t5_paper_a4, R.string.t5_paper_letter, R.string.pg_size_custom).forEachIndexed { i, l -> FilterChip(sizeKind == i, { sizeKind = i }, label = { Text(stringResource(l)) }) }
                    }
                    if (sizeKind != 0) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(!landscape, { landscape = false }, label = { Text(stringResource(R.string.pg_portrait)) })
                        FilterChip(landscape, { landscape = true }, label = { Text(stringResource(R.string.pg_landscape)) })
                    }
                    if (sizeKind == 3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(wText, { wText = it.take(8) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.pg_width)) }, isError = customSize() == null)
                            Text("×")
                            OutlinedTextField(hText, { hText = it.take(8) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.pg_height)) }, isError = customSize() == null)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("mm", "in", "pt").forEachIndexed { i, u -> FilterChip(unit == i, {
                                // convert the typed numbers to the new unit
                                val from = when (unit) { 0 -> 72f / 25.4f; 1 -> 72f; else -> 1f }; val to = when (i) { 0 -> 72f / 25.4f; 1 -> 72f; else -> 1f }
                                fun conv(t: String) = t.replace(',', '.').toFloatOrNull()?.let { v -> String.format(java.util.Locale.US, if (i == 1) "%.2f" else "%.0f", v * from / to) } ?: t
                                wText = conv(wText); hText = conv(hText); unit = i
                            }, label = { Text(u) }) }
                        }
                    }
                    Text(stringResource(R.string.page_style), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PdfOps.PageStyle.entries.forEach { s ->
                            FilterChip(style == s, { style = s }, label = { Text(stringResource(when (s) { PdfOps.PageStyle.BLANK -> R.string.style_blank; PdfOps.PageStyle.LINED -> R.string.style_lined; PdfOps.PageStyle.GRID -> R.string.style_grid; PdfOps.PageStyle.DOTS -> R.string.style_dots })) })
                        }
                    }
                }
                // FINAL_CHECK_2 Low: a preview of the file being inserted (its first pages)
                if (kind is InsertKind.File && !needsPw) {
                    val ctxP = androidx.compose.ui.platform.LocalContext.current
                    val prev by produceState<Pair<Int, List<android.graphics.Bitmap>>?>(null, kind.file) {
                        value = runCatching {
                            val s = com.smartdocs.editor.core.pdf.PdfSession.open(ctxP, kind.file)
                            try {
                                s.pageCount to (0 until minOf(s.pageCount, 8)).map { i -> val (w, h) = s.pageSize(i); s.render(i, 160, (160 * h / w).toInt().coerceIn(1, 480)) }
                            } finally { s.closeAsync() }
                        }.getOrNull()
                    }
                    Spacer(Modifier.height(12.dp))
                    prev?.let { (n, bmps) ->
                        Text(stringResource(R.string.pg_insert_preview, kind.file.name, n), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            bmps.forEachIndexed { i, b ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    androidx.compose.foundation.Image(b.asImageBitmap(), null, Modifier.height(110.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant))
                                    Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (kind is InsertKind.Images) Text(stringResource(R.string.pg_scan_pages, kind.files.size), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val at = when (position) { 0 -> ref ?: state.pageCount; 1 -> (ref ?: -1) + 1; else -> state.pageCount }
                when (kind) {
                    InsertKind.Blank -> state.insertBlank(at, style, chosenSize())
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
            com.smartdocs.editor.ui.nve.shell.NveOrganizeInsertMore(state, onMessage)   // NVE-shell: insert from web / clipboard / files / folder, insert into file
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
