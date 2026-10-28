package com.smartdocs.editor.ui.nve.panes

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.nve.panes.AttachmentInfo
import com.smartdocs.editor.core.nve.panes.DestInfo
import com.smartdocs.editor.core.nve.panes.FieldInfo
import com.smartdocs.editor.core.nve.panes.PaneReaders
import com.smartdocs.editor.core.nve.panes.SigFieldInfo
import com.smartdocs.editor.core.nve.panes.StructNode
import com.smartdocs.editor.core.pdf.DigitalSignature
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.nve.NvePane
import com.smartdocs.editor.ui.nve.NveState
import com.smartdocs.editor.ui.reader.ReaderState
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Open [file] read-only (no re-protection: nothing is saved) and run [block] on the IO dispatcher. */
internal suspend fun <T> readDoc(file: File, password: String?, block: (PDDocument) -> T): T = withContext(Dispatchers.IO) {
    PdfOps.loadRaw(file, password).use(block)
}

/** Result of loading a pane's data: still loading, failed, or the value. */
private sealed interface Load<out T> {
    data object Busy : Load<Nothing>
    data class Fail(val msg: String) : Load<Nothing>
    data class Ok<T>(val v: T) : Load<T>
}

@Composable
private fun <T> rememberLoad(file: File?, password: String?, vararg keys: Any?, block: (PDDocument) -> T): Load<T> {
    val r by produceState<Load<T>>(Load.Busy, file, password, *keys) {
        value = if (file == null) Load.Fail("") else runCatching { readDoc(file, password, block) }.fold({ Load.Ok(it) }, { Load.Fail(it.message ?: it.toString()) })
    }
    return r
}

@Composable
private fun <T> LoadBox(l: Load<T>, content: @Composable (T) -> Unit) {
    when (l) {
        Load.Busy -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
        is Load.Fail -> Text(stringResource(R.string.np_failed, l.msg), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        is Load.Ok -> content(l.v)
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val paneIcons: List<Triple<NvePane, ImageVector, Int>> = listOf(
    Triple(NvePane.SIGNATURES, Icons.Default.VerifiedUser, R.string.np_pane_signatures),
    Triple(NvePane.TAGS, Icons.Default.AccountTree, R.string.np_pane_tags),
    Triple(NvePane.DESTINATIONS, Icons.Default.PinDrop, R.string.np_pane_destinations),
    Triple(NvePane.ARTICLES, Icons.AutoMirrored.Filled.Article, R.string.np_pane_articles),
)

/**
 * Reader panes of the nitro-view-edit work: attachments, signatures, tags (structure tree), form fields, named
 * destinations and articles. The header switches panes (NveState.pane). Everything but destination edits is read only.
 * [onOpenPdf] opens an attached PDF in the reader, [onOpenTool] opens a tool route id on this document,
 * [onShowRect] (articles) zooms to a rectangle in page display space.
 */
@Composable
fun NvePaneContent(
    state: ReaderState, pane: NvePane, onJump: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
    onOpenPdf: (File) -> Unit = {}, onOpenTool: (String) -> Unit = {}, onShowRect: (Int, FloatArray) -> Unit = { p, _ -> onJump(p) },
) {
    val n = NveState.of(state)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                paneIcons.forEach { (p, icon, label) ->
                    val sel = p == pane
                    IconButton(onClick = { n.pane = p }, colors = IconButtonDefaults.iconButtonColors(contentColor = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Icon(icon, stringResource(label))
                    }
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.np_close)) }
        }
        Text(stringResource(paneIcons.first { it.first == pane }.third), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            val f = state.file
            when (pane) {
                NvePane.ATTACHMENTS -> if (f != null) NveAttachmentsList(f, state.password, onJump, onOpenPdf, state.renderVersion)
                NvePane.SIGNATURES -> SignaturesPane(state, onJump, onOpenPdf)
                NvePane.TAGS -> TagsPane(state, onJump, onOpenTool)
                NvePane.FIELDS -> FieldsPane(state, onJump)
                NvePane.DESTINATIONS -> DestinationsPane(state, onJump)
                NvePane.ARTICLES -> com.smartdocs.editor.ui.nve.articles.NveArticlesPane(state, onShowRect)
            }
        }
    }
}

// ---------------------------------------------------------------- attachments

private fun safeName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").ifBlank { "attachment" }

/**
 * Read-only, reusable list of the attachments of [file] (document-level embedded files + file-attachment annotations):
 * open (attached PDFs through [onOpenPdf], other types with another app), save a copy, jump to an annotation's page.
 */
@Composable
fun NveAttachmentsList(file: File, password: String?, onJump: ((Int) -> Unit)?, onOpenPdf: (File) -> Unit, reloadKey: Any? = null) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val load = rememberLoad(file, password, reloadKey, file.lastModified()) { PaneReaders.attachments(it) }
    var pendingSave by remember { mutableStateOf<AttachmentInfo?>(null) }
    suspend fun bytes(a: AttachmentInfo): ByteArray? = runCatching { readDoc(file, password) { PaneReaders.attachmentBytes(it, a) } }.getOrNull()
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val a = pendingSave; pendingSave = null
        if (uri != null && a != null) scope.launch {
            val r = runCatching {
                val b = bytes(a) ?: error("empty")
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "w")?.use { it.write(b) } ?: error("no stream") }
            }
            Toast.makeText(ctx, if (r.isSuccess) ctx.getString(R.string.np_saved) else ctx.getString(R.string.np_save_failed, r.exceptionOrNull()?.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }
    fun open(a: AttachmentInfo) = scope.launch {
        val b = bytes(a) ?: run { Toast.makeText(ctx, ctx.getString(R.string.np_open_failed), Toast.LENGTH_SHORT).show(); return@launch }
        val dir = File(app.documents.tempDir, "att_${System.currentTimeMillis()}").apply { mkdirs() }
        val out = File(dir, safeName(a.name))
        withContext(Dispatchers.IO) { out.writeBytes(b) }
        val ext = out.extension.lowercase()
        if (ext == "pdf" || a.subtype.equals("application/pdf", true)) { onOpenPdf(out); return@launch }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: a.subtype.takeIf { it.contains('/') } ?: "*/*"
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(app.documents.shareUri(out), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(Intent.createChooser(i, a.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(ctx, ctx.getString(R.string.np_open_failed), Toast.LENGTH_SHORT).show() }
    }
    LoadBox(load) { list ->
        if (list.isEmpty()) Empty(stringResource(R.string.np_no_attachments))
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(list, key = { it.key }) { a ->
                ListItem(
                    headlineContent = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val parts = listOfNotNull(
                            a.page?.let { stringResource(R.string.np_page_n, it + 1) } ?: stringResource(R.string.np_doc_level),
                            a.size?.let { DocumentRepository.formatSize(it) },
                            a.modified?.let { DocumentRepository.formatDate(it) },
                            a.description.takeIf { it.isNotBlank() && it != a.name },
                        )
                        Text(parts.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { Icon(if (a.name.endsWith(".pdf", true)) Icons.Default.PictureAsPdf else Icons.Default.InsertDriveFile, null) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { open(a) }) { Icon(Icons.Default.OpenInNew, stringResource(R.string.np_open)) }
                            IconButton(onClick = { pendingSave = a; saver.launch(a.name) }) { Icon(Icons.Default.SaveAlt, stringResource(R.string.np_save)) }
                        }
                    },
                    modifier = Modifier.clickable { val p = a.page; if (p != null && onJump != null) onJump(p) else open(a) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- signatures

@Composable
private fun SignaturesPane(state: ReaderState, onJump: (Int) -> Unit, onOpenPdf: (File) -> Unit) {
    val f = state.file
    val load = rememberLoad(f, state.password, state.renderVersion) { d -> PaneReaders.signatureFields(d) to PaneReaders.certificationLevel(d) }
    val scope = rememberCoroutineScope()
    var results by remember(f, state.renderVersion) { mutableStateOf<List<DigitalSignature.VerifyResult>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    LoadBox(load) { (sigs, level) ->
        Column(Modifier.fillMaxWidth()) {
            level?.let { Text(stringResource(R.string.np_certified, it), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (sigs.isEmpty()) { Empty(stringResource(R.string.np_no_signatures)); return@Column }
            if (sigs.any { it.signed }) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(enabled = !busy && f != null, onClick = {
                    busy = true; err = null
                    scope.launch {
                        // existing verifier of the Digital-sign screen (pdf-3c builds deeper validation)
                        runCatching { DigitalSignature.verify(f!!, revocation = false) }.onSuccess { results = it }.onFailure { err = it.message ?: it.toString() }
                        busy = false
                    }
                }) { Icon(Icons.Default.Verified, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(if (busy) R.string.np_verifying else R.string.np_verify)) }
            }
            err?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val signed = sigs.filter { it.signed }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(sigs, key = { it.name }) { s ->
                    // results are matched by field name (main's VerifyResult.fieldName), by order as a fallback
                    val r = results?.let { rs -> rs.firstOrNull { it.fieldName == s.name } ?: rs.getOrNull(signed.indexOf(s)) }
                    SigRow(s, r, onJump, onSignedVersion = r?.takeIf { it.signedLength > 0 }?.let { v -> {
                        scope.launch {
                            val out = File(state.app.documents.tempDir, (f?.nameWithoutExtension ?: "signed") + "_rev" + v.revision + ".pdf")
                            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.smartdocs.editor.core.sign.SigRevisions.extract(f!!, v.signedLength, out) } }
                                .onSuccess { onOpenPdf(out) }.onFailure { err = it.message }
                        }
                    } })
                }
            }
        }
    }
}

@Composable
private fun SigRow(s: SigFieldInfo, r: DigitalSignature.VerifyResult?, onJump: (Int) -> Unit, onSignedVersion: (() -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(if (s.signed) stringResource(R.string.np_signed_by, s.signer.ifBlank { r?.name ?: s.name }) else s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                if (!s.signed) Text(stringResource(R.string.np_unsigned))
                val meta = listOfNotNull(s.page?.let { stringResource(R.string.np_page_n, it + 1) }, s.date?.let { DocumentRepository.formatDate(it) }, s.subFilter.takeIf { it.isNotBlank() })
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "))
                if (s.reason.isNotBlank()) Text(stringResource(R.string.np_reason, s.reason))
                if (s.location.isNotBlank()) Text(stringResource(R.string.np_location, s.location))
                if (r != null) {
                    val ok = r.signatureValid && r.error == null
                    Text(stringResource(if (ok) R.string.np_valid else R.string.np_invalid), color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(if (r.coversWholeDocument) R.string.np_covers_all else R.string.np_not_all), style = MaterialTheme.typography.bodySmall)
                    if (r.certSubject.isNotBlank()) Text(stringResource(R.string.np_cert, r.certSubject), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    r.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (r.revisionCount > 0) Text("${r.revision} / ${r.revisionCount}", style = MaterialTheme.typography.bodySmall)
                    if (onSignedVersion != null) TextButton(onClick = onSignedVersion) { Text(stringResource(R.string.np_signed_version)) }
                }
            }
        },
        leadingContent = {
            val ok = r?.let { it.signatureValid && it.error == null }
            Icon(when { !s.signed -> Icons.Default.Draw; ok == true -> Icons.Default.Verified; ok == false -> Icons.Default.GppBad; else -> Icons.Default.VerifiedUser }, null,
                tint = when (ok) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.onSurfaceVariant })
        },
        modifier = Modifier.clickable(enabled = s.page != null) { s.page?.let(onJump) },
    )
}

// ---------------------------------------------------------------- tags

@Composable
private fun TagsPane(state: ReaderState, onJump: (Int) -> Unit, onOpenTool: (String) -> Unit) {
    val load = rememberLoad(state.file, state.password, state.renderVersion) { PaneReaders.structTree(it) }
    LoadBox(load) { tree ->
        if (tree == null) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.np_not_tagged), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { onOpenTool("accessibility") }) { Icon(Icons.Default.Accessibility, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.np_autotag)) }
            }
        } else {
            // flattened expandable tree: expanded node paths are remembered
            val expanded = remember(tree) { mutableStateListOf<String>().apply { tree.indices.forEach { add("$it") } } }
            val rows = remember(tree, expanded.toList()) {
                val out = ArrayList<Triple<String, Int, StructNode>>()
                fun add(list: List<StructNode>, prefix: String, depth: Int) {
                    list.forEachIndexed { i, nd ->
                        val id = if (prefix.isEmpty()) "$i" else "$prefix.$i"
                        out += Triple(id, depth, nd)
                        if (id in expanded) add(nd.children, id, depth + 1)
                    }
                }
                add(tree, "", 0); out
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                items(rows, key = { it.first }) { (id, depth, nd) ->
                    Row(Modifier.fillMaxWidth().clickable { nd.page?.let(onJump) }.padding(start = (8 + depth * 14).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (nd.children.isNotEmpty()) IconButton(onClick = { if (id in expanded) expanded.remove(id) else expanded.add(id) }, Modifier.size(32.dp)) {
                            Icon(if (id in expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null)
                        } else Spacer(Modifier.width(32.dp))
                        Column(Modifier.weight(1f)) {
                            Text("<${nd.type}>" + (nd.title.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (nd.alt.isNotBlank()) Text(stringResource(R.string.np_alt, nd.alt), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (nd.actual.isNotBlank()) Text(nd.actual, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        nd.page?.let { Text((it + 1).toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- fields

@Composable
private fun FieldsPane(state: ReaderState, onJump: (Int) -> Unit) {
    val load = rememberLoad(state.file, state.password, state.renderVersion) { PaneReaders.fields(it) }
    LoadBox(load) { list ->
        if (list.isEmpty()) Empty(stringResource(R.string.np_no_fields))
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            items(list) { f: FieldInfo ->
                ListItem(
                    headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val flags = listOfNotNull(f.type, f.page?.let { stringResource(R.string.np_page_n, it + 1) },
                            if (f.required) stringResource(R.string.np_required) else null, if (f.readOnly) stringResource(R.string.np_readonly) else null)
                        Column {
                            if (f.value.isNotBlank()) Text(f.value, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(flags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    leadingContent = {
                        Icon(when (f.type) { "checkbox" -> Icons.Default.CheckBox; "radio" -> Icons.Default.RadioButtonChecked; "choice" -> Icons.Default.List
                            "signature" -> Icons.Default.Draw; "button" -> Icons.Default.SmartButton; else -> Icons.Default.TextFields }, null)
                    },
                    modifier = Modifier.clickable(enabled = f.page != null) { f.page?.let(onJump) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- named destinations

@Composable
private fun DestinationsPane(state: ReaderState, onJump: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val load = rememberLoad(state.file, state.password, state.renderVersion) { PaneReaders.namedDests(it) }
    var byPage by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Pair<DestInfo?, String>?>(null) }      // (null = new, name)
    var errMsg by remember { mutableStateOf<String?>(null) }
    fun change(label: Int, op: (PDDocument) -> Boolean) {
        scope.launch {
            runCatching {
                state.applyInPlace(label = { ctx.getString(label) }) { src, out, pw ->
                    withContext(Dispatchers.IO) {
                        PdfOps.load(src, pw).use { d -> if (!op(d)) error(ctx.getString(R.string.np_dest_exists)); d.save(out) }
                    }
                }
            }.onFailure { errMsg = it.message ?: it.toString() }
        }
    }
    LoadBox(load) { list0 ->
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!byPage, { byPage = false }, label = { Text(stringResource(R.string.np_sort_name)) })
                FilterChip(byPage, { byPage = true }, label = { Text(stringResource(R.string.np_sort_page)) })
                Spacer(Modifier.weight(1f))
                FilledTonalButton(onClick = { dialog = null to "" }) { Icon(Icons.Default.AddLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.np_add_dest)) }
            }
            errMsg?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            val list = if (byPage) list0.sortedWith(compareBy({ it.page ?: Int.MAX_VALUE }, { it.name })) else list0.sortedBy { it.name.lowercase() }
            if (list.isEmpty()) Empty(stringResource(R.string.np_no_dests))
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(list, key = { it.source + ":" + it.name }) { d ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(listOfNotNull(d.page?.let { stringResource(R.string.np_page_n, it + 1) }, d.kind.takeIf { it.isNotBlank() }).joinToString(" · ")) },
                        leadingContent = { Icon(Icons.Default.Place, null) },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.pc_cd_more)) }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.np_go)) }, enabled = d.page != null, onClick = { menu = false; d.page?.let(onJump) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.np_rename)) }, onClick = { menu = false; dialog = d to d.name })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.np_delete)) }, onClick = { menu = false; change(R.string.np_hi_dest_delete) { PaneReaders.deleteDest(it, d.name) } })
                                }
                            }
                        },
                        modifier = Modifier.clickable(enabled = d.page != null) { d.page?.let(onJump) },
                    )
                }
            }
        }
    }
    dialog?.let { (target, initial) ->
        var name by remember(target) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(if (target == null) R.string.np_add_dest else R.string.np_rename)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.np_dest_name)) }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val nm = name.trim(); dialog = null; errMsg = null
                    if (target == null) { val p = state.currentPage; change(R.string.np_hi_dest_add) { PaneReaders.addDest(it, nm, p) } }
                    else change(R.string.np_hi_dest_rename) { PaneReaders.renameDest(it, target.name, nm) }
                }) { Text(stringResource(R.string.np_ok)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.np_cancel)) } },
        )
    }
}
