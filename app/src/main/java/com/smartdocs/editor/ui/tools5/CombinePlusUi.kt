package com.smartdocs.editor.ui.tools5

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tools5.CombineEntry
import com.smartdocs.editor.core.tools5.CombineOpts
import com.smartdocs.editor.core.tools5.CombinePlus
import com.smartdocs.editor.core.tools5.FileSort
import com.smartdocs.editor.core.tools5.SourceKind
import com.smartdocs.editor.core.tools5.ToPdf
import com.smartdocs.editor.ui.reader.view.ReaderTabs
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.PrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Combine tab (Nitro 5 / Foxit 4): sources from files, a folder, open tabs, a web page, the clipboard (text or image)
 * and the camera; per file a bookmark title and a page range; sort; bookmarks of the files, headings → bookmarks,
 * TOC page.
 */
@Composable
fun CombineTab(initial: List<File>, job: T5Job, attachSrc: Boolean, srcDesc: String) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries = remember { mutableStateListOf<CombineEntry>().apply { addAll(initial.map { CombineEntry(it, it.nameWithoutExtension) }) } }
    fun add(fs: List<File>, title: (File) -> String = { it.nameWithoutExtension }) {
        fs.filter { f -> entries.none { it.file.absolutePath == f.absolutePath } }.forEach { entries += CombineEntry(it, title(it)) }
    }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var perFile by remember { mutableStateOf(true) }
    var keepBm by remember { mutableStateOf(true) }
    var headings by remember { mutableStateOf(false) }
    var toc by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf<FileSort?>(null) }
    var desc by remember { mutableStateOf(false) }
    val pick = rememberAnyFilePicker(true, ToPdf.pickerMimes) { add(it) }
    val folderEmpty = stringResource(R.string.pc_folder_empty)
    val clipEmpty = stringResource(R.string.pc_clipboard_empty)
    val clipName = stringResource(R.string.pc_clipboard)
    val scanName = stringResource(R.string.pc_scan_name)
    val tocTitle = stringResource(R.string.pc_toc_title)
    val combining = stringResource(R.string.t5_combining)
    val defaultName = stringResource(R.string.t5_combined_name)
    // errors in the app language (convert audit A-L15)
    fun work(block: suspend () -> Unit) { busy = true; scope.launch { runCatching { block() }.onFailure { note = com.smartdocs.editor.ui.convert6.Cv6ErrorText.of(ctx, it) }; busy = false } }
    var askPw by remember { mutableStateOf<Int?>(null) }   // entry whose PDF needs a password

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) work {
            val uris = withContext(Dispatchers.IO) {
                val out = ArrayList<Uri>()
                fun walk(d: DocumentFile, depth: Int) {
                    if (depth > 6) return
                    d.listFiles().sortedBy { it.name.orEmpty() }.forEach { c ->
                        if (out.size >= 200) return
                        if (c.isDirectory) walk(c, depth + 1) else if (c.isFile && ToPdf.kindOf(c.name.orEmpty()) != SourceKind.UNKNOWN) out += c.uri
                    }
                }
                DocumentFile.fromTreeUri(ctx, tree)?.let { walk(it, 0) }
                out
            }
            val files = uris.mapNotNull { runCatching { app.documents.localFile(it) }.getOrNull() }
            if (files.isEmpty()) note = folderEmpty else add(files)
        }
    }
    val camera = com.smartdocs.editor.ui.gaps6.rememberCameraPages { imgs ->
        if (imgs.isNotEmpty()) work {
            val out = app.documents.newTemp("scan")
            com.smartdocs.editor.core.pdf.PdfOps.imagesToPdf(imgs, out, fitA4 = true)
            add(listOf(out)) { scanName }
        }
    }

    Text(stringResource(R.string.t5_topdf_formats), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // ---- sources
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = pick, Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.t5_add_files)) }
        OutlinedButton(onClick = { pickFolder.launch(null) }, Modifier.weight(1f), enabled = !busy) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.pc_add_folder)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            val item = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            val u = item?.uri; val text = item?.coerceToText(ctx)?.toString().orEmpty()
            when {
                u != null -> work { add(listOf(app.documents.localFile(u))) { clipName } }
                text.isNotBlank() -> work {
                    val f = File(app.documents.tempDir, "clipboard_${System.currentTimeMillis()}.txt")
                    withContext(Dispatchers.IO) { f.writeText(text) }
                    add(listOf(f)) { clipName }
                }
                else -> note = clipEmpty
            }
        }, Modifier.weight(1f), enabled = !busy) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.pc_add_clipboard)) }
        OutlinedButton(onClick = camera, Modifier.weight(1f), enabled = !busy) { Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.pc_add_scan)) }
    }
    val tabs = ReaderTabs.tabs.mapNotNull { t -> runCatching { File(Uri.parse(t.uri).path!!) }.getOrNull()?.takeIf { it.exists() } }.filter { f -> entries.none { it.file == f } }
    if (tabs.isNotEmpty()) {
        Text(stringResource(R.string.pc_from_tabs), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.take(3).forEach { f -> AssistChip(onClick = { add(listOf(f)) }, label = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.weight(1f, fill = false)) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(url, { url = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.pc_web_page)) })
        OutlinedButton(enabled = url.isNotBlank() && !busy && !job.running, onClick = {
            val u = url.trim()
            // a web page can take a minute: run as a job with progress and Cancel (convert audit A-L15)
            job.launch(combining) {
                val host = runCatching { Uri.parse(if (u.contains("://")) u else "https://$u").host }.getOrNull()?.ifBlank { null } ?: "webpage"
                val f = File(app.documents.tempDir, "${host}_${System.currentTimeMillis()}.pdf")
                try { ToPdf.url(ctx, u, f) } catch (e: Throwable) { f.delete(); throw e }
                add(listOf(f)) { host }; url = ""
            }
        }) { Text(stringResource(R.string.pc_add)) }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    // ---- list
    if (entries.size > 1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pc_sort), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            ChoiceChips(FileSort.values().toList(), sort, { stringResource(when (it) { FileSort.NAME -> R.string.pc_sort_name; FileSort.TIME -> R.string.pc_sort_time; else -> R.string.pc_sort_size }) }) { s0 ->
                val s = s0 ?: return@ChoiceChips
                desc = if (sort == s) !desc else false; sort = s
                val byFile = entries.associateBy { it.file }
                val order = CombinePlus.sortFiles(entries.map { it.file }, s, desc)
                entries.clear(); entries.addAll(order.map { byFile.getValue(it) })
            }
        }
    }
    entries.forEachIndexed { i, e ->
        T5Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (e.file.extension.equals("pdf", true)) Icons.Default.PictureAsPdf else Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                Text("${i + 1}. ${e.file.name}", Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { entries.add(i - 1, entries.removeAt(i)) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.t5_move_up)) }
                IconButton(onClick = { entries.add(i + 1, entries.removeAt(i)) }, enabled = i < entries.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.t5_move_down)) }
                IconButton(onClick = { entries.removeAt(i) }) { Icon(Icons.Default.Close, stringResource(R.string.t5_remove)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (perFile) OutlinedTextField(e.title, { entries[i] = e.copy(title = it) }, Modifier.weight(1.4f), singleLine = true, label = { Text(stringResource(R.string.pc_bookmark_title)) })
                if (!e.attach) OutlinedTextField(e.pages, { entries[i] = e.copy(pages = it) }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.page_range)) }, placeholder = { Text(stringResource(R.string.e5_all_pages)) },
                    isError = !com.smartdocs.editor.core.pdf.PdfOps.rangeSyntaxOk(e.pages))
            }
            // PDF Binder: a non-PDF file can travel as an attachment instead of being converted
            if (ToPdf.kindOf(e.file.name) != SourceKind.PDF) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(e.attach, { entries[i] = e.copy(attach = it) })
                Text(stringResource(R.string.pc_attach_instead), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    val unsupported = entries.filter { !it.attach && ToPdf.kindOf(it.file.name) == SourceKind.UNKNOWN && ToPdf.kindOf(it.file) == SourceKind.UNKNOWN }
    val badRange = entries.any { !it.attach && !com.smartdocs.editor.core.pdf.PdfOps.rangeSyntaxOk(it.pages) }
    if (unsupported.isNotEmpty()) Text(stringResource(R.string.t5_unsupported, unsupported.joinToString { it.file.name }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

    // ---- options
    SwitchRow(stringResource(R.string.t5_bookmark_per_file), perFile) { perFile = it }
    SwitchRow(stringResource(R.string.pc_keep_file_bookmarks), keepBm) { keepBm = it }
    SwitchRow(stringResource(R.string.pc_headings_bookmarks), headings, stringResource(R.string.pc_headings_bookmarks_sub)) { headings = it }
    SwitchRow(stringResource(R.string.pc_toc_page), toc) { toc = it }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_file_name_optional)) })
    PrimaryButton(stringResource(R.string.t5_combine), enabled = entries.isNotEmpty() && unsupported.isEmpty() && !badRange && !job.running && !busy, icon = Icons.Default.CallMerge) {
        val list = entries.toList()
        val o = CombineOpts(perFile, keepBm, headings, toc, tocTitle)
        val base = name.ifBlank { if (list.size == 1) list[0].file.nameWithoutExtension else defaultName }
        val attach = attachSrc
        job.launch(combining) {
            val out = app.documents.uniqueFile(app.documents.docsDir, base)
            val signedNames = ArrayList<String>()
            try {
                CombinePlus.combine(ctx, list, out, app.documents.tempDir, o, { d, t -> step(d, t) }) { f -> signedNames += f.name }
            } catch (e: com.smartdocs.editor.core.convert6.Cv6NeedPassword) {
                // ask that file's password, then combine again (convert audit A-M12: protected PDFs just failed)
                out.delete(); askPw = list.indexOfFirst { it.file == e.file }.takeIf { it >= 0 }; error = com.smartdocs.editor.ui.convert6.Cv6ErrorText.of(ctx, e); return@launch
            } catch (e: Throwable) { out.delete(); throw e }
            if (attach) com.smartdocs.editor.core.pdf.AttachmentOps.attachSources(out, list.map { it.file }, srcDesc)
            runCatching { app.documents.touchRecent(out) }
            // signed inputs lose their signatures in the combined copy: say so (convert audit A-L14)
            info = signedNames.joinToString("\n") { ctx.getString(R.string.cvf_info_signed, it) }.ifBlank { null }
            result = out
        }
    }
    askPw?.let { i ->
        val e = entries.getOrNull(i)
        if (e == null) askPw = null else {
            var pw by remember(i) { mutableStateOf("") }
            AlertDialog(onDismissRequest = { askPw = null },
                title = { Text(stringResource(R.string.cvf_pw_for, e.file.name)) },
                text = { OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text(stringResource(R.string.cv_password)) }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()) },
                confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { entries[i] = e.copy(password = pw); askPw = null }) { Text(stringResource(R.string.ok)) } },
                dismissButton = { TextButton(onClick = { askPw = null }) { Text(stringResource(R.string.cancel)) } })
        }
    }

    // ---- PDF Binder projects: save this list and its options, reopen later
    var binders by remember { mutableStateOf(emptyList<Pair<File, com.smartdocs.editor.core.tools5.BinderProject>>()) }
    LaunchedEffect(Unit) { binders = withContext(Dispatchers.IO) { runCatching { com.smartdocs.editor.core.tools5.BinderStore.list(ctx) }.getOrDefault(emptyList()) } }
    val binderSaved = stringResource(R.string.pc_binder_saved)
    T5Card(stringResource(R.string.pc_binders), Icons.Default.Inventory2) {
        OutlinedButton(enabled = entries.isNotEmpty() && !busy, onClick = {
            val p = com.smartdocs.editor.core.tools5.BinderProject(name.ifBlank { defaultName }, CombineOpts(perFile, keepBm, headings, toc, tocTitle), entries.toList())
            work {
                withContext(Dispatchers.IO) { com.smartdocs.editor.core.tools5.BinderStore.save(ctx, p) }
                binders = withContext(Dispatchers.IO) { com.smartdocs.editor.core.tools5.BinderStore.list(ctx) }
                note = binderSaved
            }
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.pc_binder_save)) }
        binders.take(8).forEach { (dir, b) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pc_binder_row, b.name, b.entries.size), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    entries.clear(); entries.addAll(b.entries.filter { it.file.isFile })
                    perFile = b.opts.bookmarkPerFile; keepBm = b.opts.keepBookmarks; headings = b.opts.headings; toc = b.opts.toc; name = b.name
                }) { Text(stringResource(R.string.pc_saved_scan_open)) }
                IconButton(onClick = { com.smartdocs.editor.core.tools5.BinderStore.delete(dir); binders = binders.filter { it.first != dir } }) { Icon(Icons.Default.Delete, stringResource(R.string.pc_preset_delete, b.name)) }
            }
        }
    }
}

/** Web page → PDF options (Foxit web-page conversion settings). */
class WebOpts {
    var paper by mutableStateOf("A4")
    var landscape by mutableStateOf(false)
    var margin by mutableStateOf(500)
    var timeoutSec by mutableStateOf(30f)
    var images by mutableStateOf(true)
    var backgrounds by mutableStateOf(false)
    var underline by mutableStateOf(false)
    var noLinks by mutableStateOf(false)
    var appendTo by mutableStateOf<File?>(null)

    fun setup(): android.print.WebPdfWriter.Setup {
        val media = when (paper) {
            "Letter" -> android.print.PrintAttributes.MediaSize.NA_LETTER; "Legal" -> android.print.PrintAttributes.MediaSize.NA_LEGAL
            "A3" -> android.print.PrintAttributes.MediaSize.ISO_A3; "A5" -> android.print.PrintAttributes.MediaSize.ISO_A5
            else -> android.print.PrintAttributes.MediaSize.ISO_A4
        }
        return android.print.WebPdfWriter.Setup(media, landscape, margin, (timeoutSec * 1000).toLong(), css = com.smartdocs.editor.core.tools5.WebCss.build(images, backgrounds, underline))
    }
}

@Composable
fun WebOptionsCard(o: WebOpts) {
    val pickAppend = com.smartdocs.editor.ui.common.rememberPdfPicker { o.appendTo = it.firstOrNull() }
    T5Card(stringResource(R.string.pc_web_layout), Icons.Default.Tune) {
        ChoiceChips(listOf("A4", "Letter", "Legal", "A3", "A5"), o.paper, { it }) { o.paper = it }
        SwitchRow(stringResource(R.string.e5_landscape), o.landscape) { o.landscape = it }
        ChoiceChips(listOf(0, 250, 500, 1000), o.margin, { stringResource(when (it) { 0 -> R.string.pc_margin_none; 250 -> R.string.pc_margin_narrow; 500 -> R.string.pc_margin_normal; else -> R.string.pc_margin_wide }) }) { o.margin = it }
        Text(stringResource(R.string.pc_web_timeout, o.timeoutSec.toInt()), style = MaterialTheme.typography.bodySmall)
        Slider(o.timeoutSec, { o.timeoutSec = it }, valueRange = 5f..120f)
        SwitchRow(stringResource(R.string.pc_web_images), o.images) { o.images = it }
        SwitchRow(stringResource(R.string.pc_web_backgrounds), o.backgrounds) { o.backgrounds = it }
        SwitchRow(stringResource(R.string.pc_web_underline), o.underline) { o.underline = it }
        SwitchRow(stringResource(R.string.pc_web_no_links), o.noLinks) { o.noLinks = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.appendTo?.let { stringResource(R.string.pc_append_to, it.name) } ?: stringResource(R.string.pc_append_none), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (o.appendTo != null) IconButton(onClick = { o.appendTo = null }) { Icon(Icons.Default.Close, stringResource(R.string.t5_remove)) }
            TextButton(onClick = pickAppend) { Text(stringResource(R.string.pc_append_pick)) }
        }
    }
}

/** After a web conversion: append [page] to the end of [o].appendTo when one was chosen (a new file next to it). */
suspend fun appendIfAsked(o: WebOpts, page: File, out: (String) -> File): File {
    val base = o.appendTo ?: return page
    val dst = out(base.nameWithoutExtension + "_web")
    com.smartdocs.editor.core.pdf.PdfOps.merge(listOf(base, page), dst)
    return dst
}
