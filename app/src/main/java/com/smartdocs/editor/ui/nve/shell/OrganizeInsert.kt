package com.smartdocs.editor.ui.nve.shell

import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.nve.shell.PageInsert
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.pages.OrganizeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Organize "More insert" (Foxit/Nitro Insert menu): from a web page, from the clipboard, from several files, from a
 * folder (every PDF in it), and "Insert into file" (the selected pages, or all, copied into another PDF). The new
 * pages land after the selection (or at the end) as one undoable organize step.
 */
@Composable
fun NveOrganizeInsertMore(state: OrganizeState, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var intoTarget by remember { mutableStateOf<File?>(null) }
    fun at(): Int = state.selected.maxOrNull()?.let { it + 1 } ?: state.pageCount
    fun fail(e: Throwable) { onMessage(ctx.getString(R.string.nl_insert_failed, state.message(e))) }
    /** audit 5 G-13: the insert is awaited; a busy organizer is reported instead of dropping the pages silently. */
    suspend fun insertAwait(f: File) {
        when (state.insertFromAwait(f, at())) {
            com.smartdocs.editor.ui.pages.OpResult.BUSY -> onMessage(ctx.getString(R.string.ttpg_busy))
            else -> Unit                                  // DONE, or FAILED (the organizer shows its error)
        }
    }
    fun insertFiles(files: List<File>) {
        if (files.isEmpty()) { onMessage(ctx.getString(R.string.nl_insert_none)); return }
        if (state.busy) { onMessage(ctx.getString(R.string.ttpg_busy)); return }
        busy = true
        scope.launch {
            val tmp = app.documents.newTemp("insert_many")
            try {
                runCatching {
                    withContext(Dispatchers.IO) { PageInsert.merge(files, tmp) }
                    insertAwait(tmp)
                }.onFailure(::fail)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { tmp.delete() } }   // audit 5 G-13: no leftover temp
                busy = false
            }
        }
    }
    val multi = rememberPdfPicker(multiple = true) { files -> insertFiles(files) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            busy = true
            val files = withContext(Dispatchers.IO) {
                runCatching {
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val list = ArrayList<Pair<String, Uri>>()
                    ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                        while (c.moveToNext()) if (c.getString(2) == "application/pdf" || c.getString(1).endsWith(".pdf", true)) list += c.getString(1) to DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    }
                    list.sortedBy { it.first.lowercase() }.mapNotNull { (_, u) -> runCatching { app.documents.localFile(u) }.getOrNull() }
                }.getOrDefault(emptyList())
            }
            busy = false
            insertFiles(files)
        }
    }
    val pickInto = rememberPdfPicker { files -> intoTarget = files.firstOrNull() }

    Column(Modifier.width(72.dp).padding(vertical = 4.dp).clickable(enabled = !busy) { menu = true }, horizontalAlignment = Alignment.CenterHorizontally) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Default.PostAdd, null)
        Text(stringResource(R.string.nl_insert_more), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.nl_insert_web)) }, leadingIcon = { Icon(Icons.Default.Language, null) }, onClick = { menu = false; urlDialog = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.nl_insert_clipboard)) }, leadingIcon = { Icon(Icons.Default.ContentPaste, null) }, onClick = {
                menu = false; busy = true
                scope.launch {
                    val f = runCatching { NvePaste.fromClipboard(ctx, app) }.getOrNull()
                    // the clipboard PDF is a document of its own in docsDir ("Clipboard"): kept
                    if (f == null) onMessage(ctx.getString(R.string.nl_paste_empty)) else runCatching { insertAwait(f) }.onFailure(::fail)
                    busy = false
                }
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.nl_insert_files)) }, leadingIcon = { Icon(Icons.Default.FileCopy, null) }, onClick = { menu = false; multi() })
            DropdownMenuItem(text = { Text(stringResource(R.string.nl_insert_folder)) }, leadingIcon = { Icon(Icons.Default.Folder, null) }, onClick = { menu = false; runCatching { folder.launch(null) } })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.nl_insert_into)) }, leadingIcon = { Icon(Icons.Default.MoveToInbox, null) }, onClick = {
                menu = false
                // audit 5 G-03: copying this document's pages into another file needs the COPY permission
                if (!state.allows(com.smartdocs.editor.core.security.DocAction.COPY)) onMessage(ctx.getString(R.string.np2_restricted_author)) else pickInto()
            })
        }
    }
    if (urlDialog) {
        var url by remember { mutableStateOf("https://") }
        AlertDialog(onDismissRequest = { urlDialog = false }, title = { Text(stringResource(R.string.nl_insert_web)) },
            text = { OutlinedTextField(url, { url = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = url.length > 8, onClick = {
                    urlDialog = false; busy = true
                    scope.launch {
                        val tmp = app.documents.newTemp("web")
                        try {
                            runCatching { com.smartdocs.editor.core.tools5.ToPdf.url(ctx, url, tmp); insertAwait(tmp) }.onFailure(::fail)
                        } finally {
                            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { tmp.delete() } }   // audit 5 G-13
                            busy = false
                        }
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { urlDialog = false }) { Text(stringResource(R.string.cancel)) } })
    }
    intoTarget?.let { target -> InsertIntoDialog(target, onDismiss = { intoTarget = null }) { position ->
        intoTarget = null; busy = true
        scope.launch {
            var pagesTmp: File? = null
            runCatching {
                val pages = ((if (state.selected.isEmpty()) null else state.selectedToTemp()) ?: state.work?.let { w -> app.documents.newTemp("all_pages").also { o -> com.smartdocs.editor.core.pdf.PdfOps.keepPages(w, (0 until state.pageCount).toList(), o, state.password) } }
                    ?: error(ctx.getString(R.string.nl_insert_none))).also { pagesTmp = it }
                val out = app.documents.uniqueFile(app.documents.docsDir, target.nameWithoutExtension + " (+" + ctx.getString(R.string.nl_pages) + ")")
                withContext(Dispatchers.IO) { PageInsert.into(target, pages, position, out) }
                runCatching { app.documents.touchRecent(out) }
                out
            }.onSuccess { Toast.makeText(ctx, ctx.getString(R.string.nl_inserted_into, it.name), Toast.LENGTH_LONG).show() }.onFailure(::fail)
            pagesTmp?.let { t -> withContext(Dispatchers.IO) { runCatching { t.delete() } } }   // audit 5 G-13: no leftover temp
            busy = false
        }
    } }
}

@Composable
private fun InsertIntoDialog(target: File, onDismiss: () -> Unit, onOk: (Int) -> Unit) {
    var where by remember { mutableIntStateOf(2) }   // 0 start, 1 before page, 2 end
    var page by remember { mutableStateOf("1") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.nl_insert_into)) },
        text = {
            Column {
                Text(target.name, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilterChip(where == 0, { where = 0 }, label = { Text(stringResource(R.string.nl_at_start)) })
                    FilterChip(where == 1, { where = 1 }, label = { Text(stringResource(R.string.nl_before_page)) })
                    FilterChip(where == 2, { where = 2 }, label = { Text(stringResource(R.string.pg_at_end)) })
                }
                if (where == 1) OutlinedTextField(page, { page = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text(stringResource(R.string.nl_page_number)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(stringResource(R.string.nl_insert_into_hint), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onOk(when (where) { 0 -> 0; 1 -> ((page.toIntOrNull() ?: 1) - 1).coerceAtLeast(0); else -> -1 }) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
