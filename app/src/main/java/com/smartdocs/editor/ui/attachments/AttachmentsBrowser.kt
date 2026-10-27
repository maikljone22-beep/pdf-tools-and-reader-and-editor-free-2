package com.smartdocs.editor.ui.attachments

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.Attachment
import com.smartdocs.editor.core.pdf.AttachmentOps
import com.smartdocs.editor.core.pdf.AttachmentPolicy
import com.smartdocs.editor.core.pdf.SignedDocumentException
import com.smartdocs.editor.core.util.PdfPrint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** How the attachments are shown: compact list, list with every property, or tiles. */
enum class AttView { LIST, DETAIL, TILES }

/** Applies a change to the document: [op] writes the new version from `src` into `out` (never into `src`). */
typealias AttChange = suspend (label: String, op: suspend (src: File, out: File, pw: String?) -> Unit) -> Unit

private const val MAX_FOLDER_FILES = 200

/**
 * Attachments of one document (reader panel and the Attachments tool): list / detail / tile views, open in the
 * app that handles the type (ACTION_VIEW through the FileProvider, PDFs in our reader), save, share, print (PDF),
 * edit the description, properties (with created / modified dates), delete one, add files or a whole folder, and
 * the per-type attachment security rules. [items] null = loading. [source] gives the current file and password.
 */
@Composable
fun AttachmentsBrowser(
    items: List<Attachment>?,
    source: () -> Pair<File, String?>?,
    change: AttChange,
    onOpenPdf: ((File) -> Unit)?,
    onJump: ((Int) -> Unit)? = null,
    scrollable: Boolean = false,
) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.applicationContext.getSharedPreferences("nav_att", Context.MODE_PRIVATE) }
    var view by remember { mutableStateOf(runCatching { AttView.valueOf(prefs.getString("view", AttView.LIST.name)!!) }.getOrDefault(AttView.LIST)) }
    var menuFor by remember { mutableStateOf<Attachment?>(null) }
    var describe by remember { mutableStateOf<Attachment?>(null) }
    var props by remember { mutableStateOf<Attachment?>(null) }
    var confirmDelete by remember { mutableStateOf<Attachment?>(null) }
    var ask by remember { mutableStateOf<Pair<Attachment, AttachmentPolicy.Decision>?>(null) }
    var security by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<Attachment?>(null) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    fun failure(e: Throwable) = toast(when ((e as? SignedDocumentException)?.reason) {
        SignedDocumentException.Reason.CERTIFIED_NO_CHANGES -> ctx.getString(R.string.signed_doc_certified_no_changes)
        SignedDocumentException.Reason.INCREMENTAL_FAILED -> ctx.getString(R.string.signed_doc_save_as_copy)
        null -> e.message ?: e.toString()
    })

    fun run(label: String, op: suspend (File, File, String?) -> Unit) {
        scope.launch {
            busy = true
            runCatching { change(label, op) }.onFailure { failure(it) }
            busy = false
        }
    }

    /** Extracts [a] into the private cache (for open / share / print). */
    suspend fun extractTemp(a: Attachment): File? {
        val (f, pw) = source() ?: return null
        val dir = File(ctx.cacheDir, "att_open/" + System.nanoTime()).apply { mkdirs() }
        return runCatching { AttachmentOps.extract(f, a, dir, pw) }.onFailure { failure(it) }.getOrNull()
    }

    fun mimeOf(a: Attachment): String = a.mime.takeIf { it.contains('/') && it != "application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(AttachmentPolicy.extOf(a.name)) ?: "*/*"

    fun openNow(a: Attachment) {
        scope.launch {
            val x = extractTemp(a) ?: return@launch
            if (AttachmentPolicy.extOf(a.name) == "pdf" && onOpenPdf != null) { onOpenPdf(x); return@launch }
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(app.documents.shareUri(x), mimeOf(a))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }.onFailure {
                // no app takes the exact type: let the user choose any app for the file
                runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(app.documents.shareUri(x), "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { toast(ctx.getString(R.string.nat_no_app)) }
            }
        }
    }

    fun open(a: Attachment) {
        when (val d = AttachmentPolicy.decide(ctx, a.name)) {
            AttachmentPolicy.Decision.OPEN_HERE, AttachmentPolicy.Decision.OPEN -> openNow(a)
            AttachmentPolicy.Decision.BLOCK -> toast(ctx.getString(R.string.nat_blocked_type, AttachmentPolicy.extOf(a.name).ifEmpty { "?" }))
            else -> ask = a to d
        }
    }

    fun saveToDocuments(a: Attachment) {
        scope.launch {
            val (f, pw) = source() ?: return@launch
            runCatching { AttachmentOps.extract(f, a, File(app.documents.docsDir, "Attachments"), pw) }
                .onSuccess { it?.let { out -> toast(ctx.getString(R.string.saved_to, out.absolutePath)) } }.onFailure { failure(it) }
        }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val a = pendingSave; pendingSave = null
        if (target != null && a != null) scope.launch {
            val x = extractTemp(a) ?: return@launch
            runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(target)?.use { o -> x.inputStream().use { it.copyTo(o) } } ?: error("cannot write") } }
                .onSuccess { toast(ctx.getString(R.string.success)) }.onFailure { failure(it) }
        }
    }

    fun share(a: Attachment) {
        scope.launch { extractTemp(a)?.let { x -> runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(x), mimeOf(a)), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
    }

    fun print(a: Attachment) {
        scope.launch { extractTemp(a)?.let { x -> runCatching { PdfPrint.print(ctx, x, a.name.substringBeforeLast('.')) }.onFailure { failure(it) } } }
    }

    // ---- add files / a folder ----
    fun addPicked(files: List<Pair<File, String>>) {
        if (files.isEmpty()) return
        run(ctx.getString(R.string.nat_hist_add)) { s, o, pw -> AttachmentOps.addAll(s, files, o, password = pw) }
    }
    suspend fun copyIn(u: Uri, name: String?, mime: String?): Pair<File, String>? = withContext(Dispatchers.IO) {
        val n = AttachmentOps.safeName(name ?: app.documents.displayName(u) ?: "attachment")
        val dir = File(app.documents.tempDir, "att_add_" + System.nanoTime()).apply { mkdirs() }
        val f = File(dir, n)
        runCatching { ctx.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: error("cannot open") }.getOrNull() ?: return@withContext null
        f to (mime ?: runCatching { ctx.contentResolver.getType(u) }.getOrNull() ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(AttachmentPolicy.extOf(n)) ?: "application/octet-stream")
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { busy = true; val list = uris.mapNotNull { copyIn(it, null, null) }; busy = false; addPicked(list) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            busy = true
            val list = withContext(Dispatchers.IO) {
                val out = ArrayList<Pair<Uri, Pair<String?, String?>>>()
                fun walk(d: DocumentFile, depth: Int) {
                    if (depth > 8) return
                    d.listFiles().forEach { c ->
                        if (out.size >= MAX_FOLDER_FILES) return
                        if (c.isDirectory) walk(c, depth + 1) else if (c.isFile) out += c.uri to (c.name to c.type)
                    }
                }
                DocumentFile.fromTreeUri(ctx, tree)?.let { walk(it, 0) }
                out
            }.mapNotNull { (u, nm) -> copyIn(u, nm.first, nm.second) }
            busy = false
            if (list.isEmpty()) toast(ctx.getString(R.string.nat_folder_empty)) else addPicked(list)
        }
    }

    val content: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, enabled = !busy) { Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nat_add_files)) }
            TextButton(onClick = { pickFolder.launch(null) }, enabled = !busy) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.nat_add_folder)) }
            Spacer(Modifier.weight(1f))
            val next = AttView.entries[(view.ordinal + 1) % AttView.entries.size]
            IconButton(onClick = { view = next; prefs.edit().putString("view", next.name).apply() }) {
                Icon(when (view) { AttView.LIST -> Icons.Default.ViewList; AttView.DETAIL -> Icons.Default.ViewAgenda; AttView.TILES -> Icons.Default.GridView }, stringResource(R.string.nat_view_mode))
            }
            IconButton(onClick = { security = true }) { Icon(Icons.Default.Security, stringResource(R.string.nat_security)) }
        }
        if (busy || items == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (items != null && items.isEmpty()) Text(stringResource(R.string.at_none), Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val list = items.orEmpty()
        when (view) {
            AttView.TILES -> {
                list.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { a -> AttTile(a, Modifier.weight(1f), onClick = { open(a) }, onLong = { menuFor = a }) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            else -> list.forEach { a -> AttRow(a, detail = view == AttView.DETAIL, onClick = { open(a) }, onMenu = { menuFor = a }) }
        }
    }
    if (scrollable) Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), content = content)
    else Column(Modifier.fillMaxWidth(), content = content)

    menuFor?.let { a ->
        AttActionsDialog(a, onDismiss = { menuFor = null }, onJump = onJump?.takeIf { !a.isDocumentLevel }) { act ->
            menuFor = null
            when (act) {
                AttAction.OPEN -> open(a)
                AttAction.SAVE -> saveToDocuments(a)
                AttAction.SAVE_AS -> { pendingSave = a; saveAs.launch(a.name) }
                AttAction.SHARE -> share(a)
                AttAction.PRINT -> print(a)
                AttAction.DESCRIBE -> describe = a
                AttAction.PROPERTIES -> props = a
                AttAction.DELETE -> confirmDelete = a
                AttAction.GO_TO_PAGE -> onJump?.invoke(a.page)
            }
        }
    }
    describe?.let { a ->
        var text by remember(a) { mutableStateOf(a.description) }
        AlertDialog(onDismissRequest = { describe = null }, title = { Text(stringResource(R.string.nat_edit_desc)) },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(a.name) }, minLines = 2) },
            confirmButton = { TextButton(onClick = { describe = null; val t = text.trim(); run(ctx.getString(R.string.nat_hist_desc)) { s, o, pw -> AttachmentOps.setDescription(s, a, t, o, pw) } }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { describe = null }) { Text(stringResource(R.string.cancel)) } })
    }
    confirmDelete?.let { a ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(stringResource(R.string.nat_delete_q)) }, text = { Text(a.name) },
            confirmButton = { TextButton(onClick = { confirmDelete = null; run(ctx.getString(R.string.nat_hist_delete)) { s, o, pw -> AttachmentOps.delete(s, a, o, pw) } }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
    props?.let { a -> AttPropertiesDialog(a) { props = null } }
    ask?.let { (a, d) ->
        val ext = AttachmentPolicy.extOf(a.name)
        AlertDialog(onDismissRequest = { ask = null },
            icon = { Icon(if (d == AttachmentPolicy.Decision.ASK_RISKY) Icons.Default.GppMaybe else Icons.Default.OpenInNew, null) },
            title = { Text(stringResource(R.string.nat_open_q, a.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (d == AttachmentPolicy.Decision.ASK_RISKY) R.string.nat_open_risky else R.string.nat_open_note))
                    if (ext.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { ask = null; AttachmentPolicy.setRule(ctx, ext, AttachmentPolicy.Rule.ALLOW); openNow(a) }, label = { Text(stringResource(R.string.nat_always, ext)) })
                        AssistChip(onClick = { ask = null; AttachmentPolicy.setRule(ctx, ext, AttachmentPolicy.Rule.NEVER) }, label = { Text(stringResource(R.string.nat_never, ext)) })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { ask = null; openNow(a) }) { Text(stringResource(R.string.nat_open_once)) } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (security) AttSecurityDialog { security = false }
}

private enum class AttAction { OPEN, SAVE, SAVE_AS, SHARE, PRINT, DESCRIBE, PROPERTIES, DELETE, GO_TO_PAGE }

@Composable
private fun AttActionsDialog(a: Attachment, onDismiss: () -> Unit, onJump: ((Int) -> Unit)?, onAction: (AttAction) -> Unit) {
    val isPdf = AttachmentPolicy.extOf(a.name) == "pdf" || a.mime == "application/pdf"
    val rows = buildList {
        add(Triple(AttAction.OPEN, Icons.Default.OpenInNew, R.string.nat_open))
        add(Triple(AttAction.SAVE, Icons.Default.Download, R.string.nat_save_docs))
        add(Triple(AttAction.SAVE_AS, Icons.Default.SaveAs, R.string.nat_save_as))
        add(Triple(AttAction.SHARE, Icons.Default.Share, R.string.share))
        if (isPdf) add(Triple(AttAction.PRINT, Icons.Default.Print, R.string.print))
        add(Triple(AttAction.DESCRIBE, Icons.Default.EditNote, R.string.nat_edit_desc))
        add(Triple(AttAction.PROPERTIES, Icons.Default.Info, R.string.nat_properties))
        if (onJump != null) add(Triple(AttAction.GO_TO_PAGE, Icons.Default.Pageview, R.string.nat_go_to_page))
        add(Triple(AttAction.DELETE, Icons.Default.Delete, R.string.delete))
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(a.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                rows.forEach { (act, icon, label) ->
                    ListItem(headlineContent = { Text(stringResource(label)) }, leadingContent = { Icon(icon, null) },
                        modifier = Modifier.combinedClickableCompat { onAction(act) }, colors = ListItemDefaults.colors(containerColor = AlertDialogDefaults.containerColor))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onLong: (() -> Unit)? = null, onClick: () -> Unit) = this.combinedClickable(onLongClick = onLong, onClick = onClick)

/** Material icon for an attachment type. */
fun attIcon(a: Attachment): ImageVector {
    val ext = AttachmentPolicy.extOf(a.name)
    val mime = a.mime
    return when {
        ext == "pdf" -> Icons.Default.PictureAsPdf
        mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "tif", "tiff") -> Icons.Default.Image
        mime.startsWith("audio/") || ext in setOf("mp3", "wav", "m4a", "ogg", "aac") -> Icons.Default.AudioFile
        mime.startsWith("video/") || ext in setOf("mp4", "mov", "mkv", "webm", "3gp") -> Icons.Default.VideoFile
        ext in setOf("xls", "xlsx", "ods", "csv") -> Icons.Default.TableChart
        ext in setOf("ppt", "pptx", "odp") -> Icons.Default.Slideshow
        ext in setOf("doc", "docx", "odt", "rtf", "txt", "md") -> Icons.Default.Description
        ext in setOf("zip", "rar", "7z", "gz", "tar") -> Icons.Default.FolderZip
        else -> Icons.Default.InsertDriveFile
    }
}

@Composable
private fun whereText(a: Attachment): String =
    if (a.isDocumentLevel) stringResource(R.string.nat_doc_level) else stringResource(R.string.nat_on_page, a.page + 1)

private fun fmtDate(ms: Long): String? = ms.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }

@Composable
private fun AttRow(a: Attachment, detail: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    val sub = buildList {
        add(DocumentRepository.formatSize(a.size)); add(whereText(a))
        if (detail) { fmtDate(a.modified)?.let { add(stringResource(R.string.nat_modified_x, it)) }; fmtDate(a.created)?.let { add(stringResource(R.string.nat_created_x, it)) } }
    }.joinToString(" · ")
    ListItem(
        modifier = Modifier.combinedClickableCompat(onLong = onMenu, onClick = onClick),
        leadingContent = { Icon(attIcon(a), null) },
        headlineContent = { Text(a.name, maxLines = if (detail) 3 else 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(sub, maxLines = if (detail) 3 else 1, overflow = TextOverflow.Ellipsis)
                if (a.description.isNotBlank() && a.description != a.name) Text(a.description, maxLines = if (detail) 6 else 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = { IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, stringResource(R.string.nat_actions)) } },
    )
}

@Composable
private fun AttTile(a: Attachment, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    Surface(modifier.combinedClickableCompat(onLong = onLong, onClick = onClick), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Icon(attIcon(a), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(6.dp))
            Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(DocumentRepository.formatSize(a.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onLong, Modifier.size(32.dp)) { Icon(Icons.Default.MoreHoriz, stringResource(R.string.nat_actions)) }
        }
    }
}

@Composable
private fun AttPropertiesDialog(a: Attachment, onDismiss: () -> Unit) {
    val rows = listOfNotNull(
        stringResource(R.string.nat_p_name) to a.name,
        stringResource(R.string.nat_p_desc) to a.description.ifBlank { "—" },
        stringResource(R.string.nat_p_size) to DocumentRepository.formatSize(a.size),
        stringResource(R.string.nat_p_type) to a.mime.ifBlank { "—" },
        stringResource(R.string.nat_p_location) to whereText(a),
        stringResource(R.string.nat_p_created) to (fmtDate(a.created) ?: "—"),
        stringResource(R.string.nat_p_modified) to (fmtDate(a.modified) ?: "—"),
    )
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.nat_properties)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (k, v) ->
                    Column {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } })
}

/** Attachment security: the per-type rules (always open / never open) and adding one. */
@Composable
fun AttSecurityDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var rules by remember { mutableStateOf(AttachmentPolicy.rules(ctx)) }
    var input by remember { mutableStateOf("") }
    fun set(ext: String, r: AttachmentPolicy.Rule?) { AttachmentPolicy.setRule(ctx, ext, r); rules = AttachmentPolicy.rules(ctx) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.nat_security)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.nat_security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rules.isEmpty()) Text(stringResource(R.string.nat_no_rules), style = MaterialTheme.typography.bodyMedium)
                rules.entries.sortedBy { it.key }.forEach { (ext, r) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(".$ext", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(if (r == AttachmentPolicy.Rule.ALLOW) R.string.nat_rule_allow else R.string.nat_rule_never), style = MaterialTheme.typography.labelMedium,
                            color = if (r == AttachmentPolicy.Rule.ALLOW) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        IconButton(onClick = { set(ext, null) }) { Icon(Icons.Default.Close, stringResource(R.string.delete)) }
                    }
                }
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nat_type_hint)) },
                    isError = input.isNotBlank() && AttachmentPolicy.normalize(input) == null)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val ok = AttachmentPolicy.normalize(input) != null
                    OutlinedButton(onClick = { set(input, AttachmentPolicy.Rule.ALLOW); input = "" }, enabled = ok) { Text(stringResource(R.string.nat_rule_allow)) }
                    OutlinedButton(onClick = { set(input, AttachmentPolicy.Rule.NEVER); input = "" }, enabled = ok) { Text(stringResource(R.string.nat_rule_never)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } })
}
