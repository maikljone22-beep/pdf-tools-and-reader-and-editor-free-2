package com.smartdocs.editor.ui.tools5

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ResultsList
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Text input dialog used by the portfolio actions (rename, description, folder, field value). */
@Composable
private fun AskText(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, Modifier.fillMaxWidth(), singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(v) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private sealed class PfAsk {
    data class Rename(val item: PfItem) : PfAsk()
    data class Describe(val item: PfItem) : PfAsk()
    data class Move(val item: PfItem) : PfAsk()
    data class Value(val item: PfItem, val field: PfField) : PfAsk()
    object NewFolder : PfAsk()
    object NewField : PfAsk()
}

/**
 * Foxit 7 portfolio editor: views (details / tiles / cover sheet), sort, folders, columns (schema fields), search across
 * the parts, a preview pane, and per part open / share / rename / describe / move / initial / field values / delete /
 * convert to PDF / reduce size; add files or a folder, regenerate the cover table of contents. Edits go to a working
 * copy; "Save" writes a new file (the result sheet can replace the original).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioEditor(nav: NavHostController, source: File, onClose: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val job = rememberT5Job()
    var work by remember(source) { mutableStateOf<File?>(null) }
    var info by remember { mutableStateOf<PfInfo?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf<String?>(null) }          // null = all folders
    var tiles by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<PfItem?>(null) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PfHit>?>(null) }
    var ask by remember { mutableStateOf<PfAsk?>(null) }
    var dirty by remember { mutableStateOf(false) }
    val folderLabel = stringResource(R.string.pc_pf_folder)
    val saving = stringResource(R.string.processing)
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(source) {
        val w = File(app.documents.tempDir, "pf_${System.nanoTime()}.pdf")
        runCatching { withContext(Dispatchers.IO) { source.copyTo(w, true) }; work = w }.onFailure { error = it.message }
    }
    LaunchedEffect(work, version) { val w = work ?: return@LaunchedEffect; info = runCatching { PortfolioOps.info(w, null) }.onFailure { error = it.message }.getOrNull() }

    /** Apply a change to the working copy. */
    fun change(block: suspend (File) -> Unit) {
        val w = work ?: return
        scope.launch {
            runCatching { block(w) }.onSuccess { dirty = true; version++ }.onFailure { error = it.message ?: it.toString() }
        }
    }
    fun cos(block: (com.tom_roush.pdfbox.pdmodel.PDDocument) -> Unit) = change { w -> PortfolioOps.edit(w, null, w, block) }

    val addFiles = rememberAnyFilePicker(true, arrayOf("*/*")) { fs ->
        val f = folder.orEmpty()
        change { w -> withContext(Dispatchers.IO) { val atts = fs.map { Attachment(it.name, Embed.mimeOf(it.name), it.readBytes()) }; PortfolioOps.edit(w, null, w) { PortfolioEdit.add(it, atts, f) } } }
    }
    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) change { w ->
            // the picked folder (and its sub-folders, 4 levels) becomes portfolio folders
            val found = withContext(Dispatchers.IO) {
                val out = ArrayList<Pair<String, Uri>>()
                fun walk(d: DocumentFile, path: String, depth: Int) {
                    if (depth > 4) return
                    d.listFiles().forEach { c -> if (out.size < 300) { if (c.isDirectory) walk(c, "$path/${c.name}", depth + 1) else if (c.isFile) out += path to c.uri } }
                }
                DocumentFile.fromTreeUri(ctx, tree)?.let { walk(it, it.name ?: "folder", 0) }
                out
            }
            val files = found.mapNotNull { (p, u) -> runCatching { p to app.documents.localFile(u) }.getOrNull() }
            withContext(Dispatchers.IO) { PortfolioOps.edit(w, null, w) { d -> files.groupBy({ it.first }, { it.second }).forEach { (p, fs) -> PortfolioEdit.add(d, fs.map { Attachment(it.name, Embed.mimeOf(it.name), it.readBytes()) }, p) } } }
        }
    }

    suspend fun extract(item: PfItem): File? {
        val w = work ?: return null
        return Portfolio.extract(w, item.key, File(app.documents.tempDir, "pf_parts_${source.nameWithoutExtension}"))
    }

    ToolScaffold(stringResource(R.string.t5_portfolio_title), nav, snackbar = snackbar, actions = {
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.pc_pf_close)) }
    }) {
        Text(source.name, style = MaterialTheme.typography.titleMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val inf = info
        if (inf == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@ToolScaffold }

        // ---- view, sort, columns
        T5Card(stringResource(R.string.pc_pf_view), Icons.Default.ViewModule) {
            Text(stringResource(R.string.pc_pf_default_view), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(PfView.entries, inf.view, { stringResource(when (it) { PfView.DETAILS -> R.string.pc_pf_details; PfView.TILES -> R.string.pc_pf_tiles; PfView.COVER -> R.string.pc_pf_cover }) }) { v -> cos { PortfolioEdit.setView(it, v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!tiles, { tiles = false }, label = { Text(stringResource(R.string.pc_pf_details)) }, leadingIcon = { Icon(Icons.Default.ViewList, null) })
                Spacer(Modifier.width(6.dp))
                FilterChip(tiles, { tiles = true }, label = { Text(stringResource(R.string.pc_pf_tiles)) }, leadingIcon = { Icon(Icons.Default.GridView, null) })
                Spacer(Modifier.width(6.dp))
                FilterChip(preview, { preview = !preview }, label = { Text(stringResource(R.string.pc_pf_preview)) })
            }
            val sortKeys = listOf("F", "Desc", "Size", "ModDate") + inf.fields.filter { it.type in setOf("S", "N", "D") }.map { it.key }
            Text(stringResource(R.string.pc_sort), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(sortKeys, inf.sortKey ?: "F", { k -> when (k) { "F" -> stringResource(R.string.pc_sort_name); "Desc" -> stringResource(R.string.pc_pf_description); "Size" -> stringResource(R.string.pc_sort_size); "ModDate" -> stringResource(R.string.pc_sort_time); else -> inf.fields.first { it.key == k }.name } }) { k ->
                val asc = if (k == (inf.sortKey ?: "F")) !inf.ascending else true
                cos { PortfolioEdit.setSort(it, k, asc) }
            }
            // columns (schema fields)
            Text(stringResource(R.string.pc_pf_columns), style = MaterialTheme.typography.labelLarge)
            inf.fields.forEachIndexed { i, f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.visible, { v -> cos { PortfolioEdit.setFields(it, inf.fields.map { x -> if (x.key == f.key) x.copy(visible = v) else x }) } })
                    Text("${f.name} (${f.type})", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { cos { PortfolioEdit.setFields(it, inf.fields.toMutableList().apply { add(i - 1, removeAt(i)) }) } }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.t5_move_up)) }
                    IconButton(onClick = { cos { PortfolioEdit.setFields(it, inf.fields.filter { x -> x.key != f.key }) } }) { Icon(Icons.Default.Delete, stringResource(R.string.t5_remove)) }
                }
            }
            TextButton(onClick = { ask = PfAsk.NewField }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.pc_pf_add_field)) }
        }

        // ---- search across the parts
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.pc_pf_search)) }, leadingIcon = { Icon(Icons.Default.Search, null) })
            OutlinedButton(enabled = query.isNotBlank() && work != null, onClick = { val w = work!!; val q = query; scope.launch { hits = runCatching { PortfolioOps.search(w, null, q) }.getOrDefault(emptyList()) } }) { Text(stringResource(R.string.pc_pf_find)) }
        }
        hits?.let { hs ->
            T5Card(stringResource(R.string.pc_pf_hits, hs.size), Icons.Default.Search) {
                hs.take(50).forEach { h ->
                    Column(Modifier.fillMaxWidth().clickable {
                        val item = inf.items.firstOrNull { it.key == h.key } ?: return@clickable
                        scope.launch { extract(item)?.let { nav.navigate(Routes.reader(Uri.fromFile(it).toString(), h.page)) } }
                    }.padding(vertical = 4.dp)) {
                        Text(stringResource(R.string.pc_pf_hit_page, h.name, h.page + 1), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(h.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = { hits = null }) { Text(stringResource(R.string.cancel)) }
            }
        }

        // ---- folders + items
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(folder == null, { folder = null }, label = { Text(stringResource(R.string.pc_pf_all)) })
            FilterChip(folder == "", { folder = "" }, label = { Text(stringResource(R.string.pc_pf_root)) })
            inf.folders.forEach { p -> FilterChip(folder == p, { folder = p }, label = { Text(p) }, leadingIcon = { Icon(Icons.Default.Folder, null) }) }
            AssistChip(onClick = { ask = PfAsk.NewFolder }, label = { Text(stringResource(R.string.pc_pf_new_folder)) }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) })
        }
        val shown = PortfolioEdit.sorted(inf.items.filter { folder == null || it.folder == folder }, inf.sortKey, inf.ascending)
        if (shown.isEmpty()) Text(stringResource(R.string.pc_pf_empty), style = MaterialTheme.typography.bodySmall)
        val visibleFields = inf.fields.filter { it.visible && it.type in setOf("S", "N", "D") }
        @Composable fun ItemMenu(item: PfItem, onDismiss: () -> Unit) {
            val pdf = item.name.endsWith(".pdf", true) || item.mime == "application/pdf"
            DropdownMenuItem(text = { Text(stringResource(R.string.t5_open)) }, onClick = { onDismiss(); scope.launch {
                if (!pdf && !com.smartdocs.editor.ui.security.DocSecurityGate.confirmAttachment(ctx, item.name)) return@launch
                extract(item)?.let { f -> if (pdf) openPdf(nav, f) else shareFiles(ctx, app.documents, listOf(f), item.mime) } } })
            DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = { onDismiss(); scope.launch { extract(item)?.let { shareFiles(ctx, app.documents, listOf(it), item.mime) } } })
            DropdownMenuItem(text = { Text(stringResource(R.string.pc_pf_rename)) }, onClick = { onDismiss(); ask = PfAsk.Rename(item) })
            DropdownMenuItem(text = { Text(stringResource(R.string.pc_pf_edit_description)) }, onClick = { onDismiss(); ask = PfAsk.Describe(item) })
            DropdownMenuItem(text = { Text(stringResource(R.string.pc_pf_move)) }, onClick = { onDismiss(); ask = PfAsk.Move(item) })
            DropdownMenuItem(text = { Text(stringResource(R.string.pc_pf_initial)) }, onClick = { onDismiss(); cos { PortfolioEdit.setInitial(it, item.key) } })
            inf.fields.filter { it.type in setOf("S", "N", "D") }.forEach { f ->
                DropdownMenuItem(text = { Text(stringResource(R.string.pc_pf_set_value, f.name)) }, onClick = { onDismiss(); ask = PfAsk.Value(item, f) })
            }
            if (pdf) DropdownMenuItem(text = { Text(stringResource(R.string.pc_pf_compress)) }, onClick = { onDismiss(); change { w -> PortfolioOps.compressParts(w, null, listOf(item.key), w, app.documents.tempDir) } })
            else if (ToPdf.kindOf(item.name) != SourceKind.UNKNOWN) DropdownMenuItem(text = { Text(stringResource(R.string.pc_pf_convert)) }, onClick = { onDismiss(); change { w -> PortfolioOps.convertPart(ctx, w, null, item.key, w, app.documents.tempDir) } })
            DropdownMenuItem(text = { Text(stringResource(R.string.pc_pf_delete), color = MaterialTheme.colorScheme.error) }, onClick = { onDismiss(); cos { PortfolioEdit.remove(it, listOf(item.key)) }; if (selected?.key == item.key) selected = null })
        }
        @Composable fun ItemRow(item: PfItem) {
            var menu by remember(item.key) { mutableStateOf(false) }
            val details = buildList {
                add(DocumentRepository.formatSize(item.size))
                item.modified?.let { add(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it))) }
                if (item.description.isNotBlank()) add(item.description)
                visibleFields.forEach { f -> item.values[f.key]?.takeIf { it.isNotBlank() }?.let { add("${f.name}: $it") } }
            }.joinToString(" · ")
            ListItem(
                modifier = Modifier.clickable { selected = item },
                leadingContent = { Icon(if (item.name.endsWith(".pdf", true)) Icons.Default.PictureAsPdf else Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text((if (inf.initial == item.key) "★ " else "") + item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(details, maxLines = if (tiles) 1 else 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
                trailingContent = { Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.pc_pf_actions, item.name)) }
                    DropdownMenu(menu, { menu = false }) { ItemMenu(item) { menu = false } } } },
            )
        }
        if (tiles) shown.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { Card(Modifier.weight(1f)) { ItemRow(it) } }; if (row.size == 1) Spacer(Modifier.weight(1f)) }
        } else T5Card { shown.forEach { ItemRow(it) } }

        // ---- preview pane
        val sel = selected?.let { s -> inf.items.firstOrNull { it.key == s.key } }
        if (preview && sel != null) {
            val bmp by produceState<Bitmap?>(null, sel.key, version) {
                value = runCatching {
                    val f = extract(sel) ?: return@runCatching null
                    when {
                        f.extension.equals("pdf", true) -> { val s = com.smartdocs.editor.core.pdf.PdfSession.open(ctx, f); try { val (w, h) = s.pageSize(0); s.render(0, 600, (600 * h / w).toInt().coerceAtLeast(1)) } finally { s.closeAsync() } }
                        ToPdf.kindOf(f.name) == SourceKind.IMAGE -> withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) }
                        else -> null
                    }
                }.getOrNull()
            }
            T5Card(stringResource(R.string.pc_pf_preview) + ": " + sel.name, Icons.Default.Visibility) {
                bmp?.let { Image(it.asImageBitmap(), sel.name, Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit) }
                    ?: Text(stringResource(R.string.pc_pf_no_preview), style = MaterialTheme.typography.bodySmall)
            }
        }

        // ---- add / cover / save
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = addFiles, Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.t5_add_files)) }
            OutlinedButton(onClick = { addFolder.launch(null) }, Modifier.weight(1f)) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.pc_add_folder)) }
        }
        OutlinedButton(onClick = { change { w -> PortfolioOps.regenerateCover(ctx, w, null, w, source.nameWithoutExtension, folderLabel) } }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.ListAlt, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.pc_pf_regen_cover))
        }
        PrimaryButton(stringResource(R.string.pc_pf_save), enabled = dirty && !job.running, icon = Icons.Default.Save) {
            val w = work ?: return@PrimaryButton
            job.launch(saving) {
                val out = app.documents.uniqueFile(app.documents.docsDir, source.nameWithoutExtension)
                withContext(Dispatchers.IO) { w.copyTo(out, true) }
                result = out; dirty = false
            }
        }
        if (job.results.isNotEmpty()) ResultsList(job.results, nav, "application/pdf")
    }
    T5JobUi(job, nav, snackbar)

    when (val a = ask) {
        is PfAsk.Rename -> AskText(stringResource(R.string.pc_pf_rename), a.item.name, { v -> ask = null; cos { PortfolioEdit.rename(it, a.item.key, v.trim()) } }) { ask = null }
        is PfAsk.Describe -> AskText(stringResource(R.string.pc_pf_edit_description), a.item.description, { v -> ask = null; cos { PortfolioEdit.setDescription(it, a.item.key, v) } }) { ask = null }
        is PfAsk.Move -> AskText(stringResource(R.string.pc_pf_move_to), a.item.folder, { v -> ask = null; cos { PortfolioEdit.move(it, a.item.key, v.trim().trim('/')) } }) { ask = null }
        is PfAsk.Value -> AskText(a.field.name, a.item.values[a.field.key].orEmpty(), { v -> ask = null; cos { PortfolioEdit.setValue(it, a.item.key, a.field.key, v) } }) { ask = null }
        PfAsk.NewFolder -> AskText(stringResource(R.string.pc_pf_new_folder), folder.orEmpty(), { v -> ask = null; val p = v.trim().trim('/'); if (p.isNotEmpty()) { cos { PortfolioEdit.ensureFolder(it, p) }; folder = p } }) { ask = null }
        PfAsk.NewField -> AskText(stringResource(R.string.pc_pf_add_field), "", { v ->
            ask = null
            val name = v.trim()
            if (name.isNotEmpty()) cos { d ->
                val cur = PortfolioEdit.read(d).fields
                val key = "f" + (name.filter { it.isLetterOrDigit() && it.code < 128 }.ifEmpty { "field" }) + cur.size
                // a field whose name ends in "#" is a number column
                PortfolioEdit.setFields(d, (if (cur.isEmpty()) listOf(PfField("FileName", ctx.getString(R.string.pc_sort_name), "F"), PfField("Desc", ctx.getString(R.string.pc_pf_description), "Desc"),
                    PfField("Size", ctx.getString(R.string.pc_sort_size), "Size"), PfField("ModDate", ctx.getString(R.string.pc_sort_time), "ModDate")) else cur) +
                    PfField(key, name.removeSuffix("#").trim(), if (name.endsWith("#")) "N" else "S"))
            }
        }) { ask = null }
        null -> {}
    }
}
