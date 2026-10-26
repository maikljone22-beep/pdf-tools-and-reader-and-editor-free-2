package com.smartdocs.editor.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Tool "merge": combine several PDFs in a chosen order. */
@Composable
fun MergeScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val runner = rememberToolRunner()
    val preset = rememberInputFile(uri)
    var files by remember { mutableStateOf(listOfNotNull(preset.value)) }
    val counts = remember { mutableStateMapOf<String, Int>() }
    var name by remember { mutableStateOf("") }
    val pick = rememberPdfPicker(multiple = true) { files = files + it }
    val processing = stringResource(R.string.processing)
    // share6 (fid): password-protected inputs
    val passwords = remember { mutableStateMapOf<String, String>() }
    val locked = remember { mutableStateMapOf<String, Boolean>() }
    var asking by remember { mutableStateOf<File?>(null) }
    var protectOut by remember { mutableStateOf(false) }
    var protectPw by remember { mutableStateOf("") }

    LaunchedEffect(files, passwords.size) {
        files.forEach { f ->
            if (!locked.containsKey(f.absolutePath)) locked[f.absolutePath] = withContext(Dispatchers.IO) { com.smartdocs.editor.core.pdf.LockedPdf.needsPassword(f) }
            if (!counts.containsKey(f.absolutePath)) {
                val pw = passwords[f.absolutePath]
                if (locked[f.absolutePath] != true || pw != null) counts[f.absolutePath] = withContext(Dispatchers.IO) { com.smartdocs.editor.core.pdf.LockedPdf.pageCount(f, pw) } ?: 0
            }
        }
    }
    LaunchedEffect(files, passwords.size, locked.size) { asking = files.firstOrNull { locked[it.absolutePath] == true && passwords[it.absolutePath] == null } }
    asking?.let { f -> FilePasswordDialog(f, onUnlocked = { pw -> passwords[f.absolutePath] = pw; counts.remove(f.absolutePath); asking = null }, onRemove = { files = files - f; asking = null }) }
    val lockedLeft = files.any { locked[it.absolutePath] == true && passwords[it.absolutePath] == null }

    ToolScaffold(stringResource(R.string.tool_merge), nav, runner, actions = {
        IconButton(onClick = pick) { Icon(Icons.Default.Add, stringResource(R.string.add_files)) }
    }) {
        Text(stringResource(R.string.merge_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (files.isEmpty()) {
            EmptyState(Icons.Default.CallMerge, stringResource(R.string.merge_hint)) {
                Button(onClick = pick) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_files)) }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    files.forEachIndexed { i, f ->
                        FileOrderRow(
                            index = i, file = f, pages = counts[f.absolutePath],
                            canUp = i > 0, canDown = i < files.lastIndex,
                            onUp = { files = files.move(i, i - 1) }, onDown = { files = files.move(i, i + 1) },
                            onRemove = { files = files.toMutableList().also { it.removeAt(i) } },
                        )
                        if (i < files.lastIndex) HorizontalDivider()
                    }
                }
            }
            OutlinedButton(onClick = pick, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_files)) }
        }
        val total = files.sumOf { counts[it.absolutePath] ?: 0 }
        if (files.isNotEmpty()) Text(stringResource(R.string.tl_total_pages, total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.file_name)) }, singleLine = true)
        // share6 (fid): optional password for the merged file
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.fd_merge_protect), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(protectOut, { protectOut = it })
        }
        if (protectOut) OutlinedTextField(protectPw, { protectPw = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.fd_merge_protect_pw)) }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        else if (passwords.isNotEmpty()) Text(stringResource(R.string.fd_merge_unencrypted), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (lockedLeft) Text(stringResource(R.string.fd_merge_locked_left), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        PrimaryButton(stringResource(R.string.merge), enabled = files.size >= 2 && !lockedLeft && (!protectOut || protectPw.isNotEmpty()), icon = Icons.Default.CallMerge) {
            val list = files.toList()
            val base = name.ifBlank { outName(list.first(), "merged") }
            val pwMap = list.mapNotNull { f -> passwords[f.absolutePath]?.let { f to it } }.toMap()
            val outPw = protectPw.takeIf { protectOut && it.isNotEmpty() }
            runner.run(processing) {
                val out = app.documents.uniqueFile(app.documents.docsDir, base)
                if (outPw == null) com.smartdocs.editor.core.pdf.LockedPdf.mergeLocked(list, pwMap, out, app.documents.tempDir)
                else {
                    val tmp = app.documents.newTemp("merge")
                    try { com.smartdocs.editor.core.pdf.LockedPdf.mergeLocked(list, pwMap, tmp, app.documents.tempDir); PdfOps.protect(tmp, out, outPw, outPw) } finally { tmp.delete() }
                }
                out
            }
        }
    }
}

@Composable
private fun FileOrderRow(index: Int, file: File, pages: Int?, canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            val info = DocumentRepository.formatSize(file.length()) + (pages?.let { " · " + stringResource(R.string.tl_pages_n, it) } ?: "")
            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onUp, enabled = canUp) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.tl_move_up)) }
        IconButton(onClick = onDown, enabled = canDown) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.tl_move_down)) }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, stringResource(R.string.tl_remove), tint = MaterialTheme.colorScheme.error) }
    }
}

private enum class SplitMode { EVERY_PAGE, EVERY_N, RANGES, BOOKMARKS, SIZE }

/** Split helpers that are not in PdfOps: by top-level bookmarks and by maximum file size. */
internal object SplitOps {

    private fun safeName(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().trim('.').take(80).ifBlank { "part" }

    /** Top-level outline entries with their 0-based target page (entries without a resolvable page are skipped). */
    suspend fun topBookmarks(src: File, password: String? = null): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        PdfOps.load(src, password).use { doc ->
            val outline = doc.documentCatalog.documentOutline ?: return@use emptyList()
            outline.children().mapNotNull { item ->
                val page = runCatching { item.findDestinationPage(doc) }.getOrNull()
                    ?: runCatching { ((item.action as? PDActionGoTo)?.destination as? PDPageDestination)?.page }.getOrNull()
                val idx = page?.let { doc.pages.indexOf(it) } ?: -1
                if (idx >= 0) (item.title ?: "").trim() to idx else null
            }.sortedBy { it.second }
        }
    }

    /**
     * One file per top-level bookmark, named by the bookmark title, covering its first page up to the page
     * before the next bookmark. Pages before the first bookmark go to the first part.
     */
    suspend fun byBookmarks(src: File, outDir: File, password: String? = null): List<File> = withContext(Dispatchers.IO) {
        val marks = topBookmarks(src, password).distinctBy { it.second }
        if (marks.isEmpty()) throw IllegalStateException("no bookmarks")
        outDir.mkdirs()
        val files = ArrayList<File>()
        PdfOps.load(src, password).use { doc ->
            val n = doc.numberOfPages
            marks.forEachIndexed { i, (title, start) ->
                val from = if (i == 0) 0 else start
                val to = if (i + 1 < marks.size) marks[i + 1].second - 1 else n - 1
                if (to < from) return@forEachIndexed
                PDDocument().use { nd ->
                    for (p in from..to) nd.importPage(doc.getPage(p))
                    val f = uniqueIn(outDir, String.format(java.util.Locale.US, "%02d_%s", i + 1, safeName(title.ifBlank { "part_${i + 1}" })))
                    nd.save(f); files += f
                }
            }
        }
        files
    }

    private fun uniqueIn(dir: File, base: String): File {
        var f = File(dir, "$base.pdf"); var k = 2
        while (f.exists()) { f = File(dir, "${base}_$k.pdf"); k++ }
        return f
    }

    private fun sizeOf(doc: PDDocument, pages: List<Int>): Long = PDDocument().use { nd ->
        pages.forEach { nd.importPage(doc.getPage(it)) }
        val counter = object : java.io.OutputStream() {
            var n = 0L
            override fun write(b: Int) { n++ }
            override fun write(b: ByteArray, off: Int, len: Int) { n += len }
        }
        nd.save(counter); counter.n
    }

    /**
     * Split so that every part stays under [maxBytes] when possible: per-page sizes are measured once, pages are
     * grouped greedily, then each group is written and halved again if shared resources made it too large.
     * A single page larger than the limit becomes its own part.
     */
    suspend fun bySize(src: File, outDir: File, baseName: String, maxBytes: Long, password: String? = null): List<File> = withContext(Dispatchers.IO) {
        require(maxBytes > 10_000) { "limit too small" }
        outDir.mkdirs()
        val files = ArrayList<File>()
        PdfOps.load(src, password).use { doc ->
            val n = doc.numberOfPages
            val single = (0 until n).map { sizeOf(doc, listOf(it)) }
            val overhead = (single.minOrNull() ?: 0L) / 3   // rough per-file overhead counted once per group
            val groups = ArrayList<MutableList<Int>>()
            var cur = ArrayList<Int>(); var acc = 0L
            for (p in 0 until n) {
                val add = single[p] - overhead
                if (cur.isNotEmpty() && acc + add > maxBytes) { groups += cur; cur = ArrayList(); acc = overhead }
                if (cur.isEmpty()) acc = overhead
                cur += p; acc += add
            }
            if (cur.isNotEmpty()) groups += cur
            fun write(pages: List<Int>) {
                PDDocument().use { nd ->
                    pages.forEach { nd.importPage(doc.getPage(it)) }
                    val f = uniqueIn(outDir, "${baseName}_part${files.size + 1}")
                    nd.save(f)
                    if (f.length() > maxBytes && pages.size > 1) {
                        f.delete()
                        val half = pages.size / 2
                        write(pages.subList(0, half)); write(pages.subList(half, pages.size))
                    } else files += f
                }
            }
            groups.forEach { write(it) }
        }
        files
    }
}

/** Tool "split": one file per page, per N pages or per custom range. */
@Composable
fun SplitScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val runner = rememberToolRunner()
    var file by rememberInputFile(uri)
    var pages by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(SplitMode.EVERY_PAGE) }
    var n by remember { mutableStateOf("2") }
    var ranges by remember { mutableStateOf("") }
    var maxMb by remember { mutableStateOf("5") }
    var bookmarks by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    val processing = stringResource(R.string.processing)
    val noBookmarks = stringResource(R.string.ps_split_no_bookmarks)
    val badSize = stringResource(R.string.ps_split_size_invalid)

    LaunchedEffect(file) { pages = file?.let { f -> runCatching { PdfOps.pageCount(f) }.getOrDefault(0) } ?: 0 }
    LaunchedEffect(file, mode) {
        bookmarks = null
        val f = file
        if (mode == SplitMode.BOOKMARKS && f != null) bookmarks = runCatching { SplitOps.topBookmarks(f) }.getOrDefault(emptyList())
    }

    ToolScaffold(stringResource(R.string.tool_split), nav, runner) {
        FilePickRow(file, stringResource(R.string.tl_input_file), pick)
        if (file != null && pages > 0) Text(stringResource(R.string.tl_pages_n, pages), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FieldLabel(stringResource(R.string.tl_split_mode))
        ChoiceChips(SplitMode.entries, mode, {
            when (it) {
                SplitMode.EVERY_PAGE -> stringResource(R.string.tl_split_every_page); SplitMode.EVERY_N -> stringResource(R.string.tl_split_every_n)
                SplitMode.RANGES -> stringResource(R.string.tl_split_by_range); SplitMode.BOOKMARKS -> stringResource(R.string.ps_split_by_bookmarks)
                SplitMode.SIZE -> stringResource(R.string.ps_split_by_size)
            }
        }) { mode = it }
        when (mode) {
            SplitMode.EVERY_N -> NumberField(n, stringResource(R.string.tl_split_n), { n = it }, Modifier.fillMaxWidth(), maxLen = 3)
            SplitMode.RANGES -> OutlinedTextField(ranges, { ranges = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.page_range)) }, placeholder = { Text(stringResource(R.string.tl_split_ranges_hint)) })
            SplitMode.BOOKMARKS -> BookmarkPreview(bookmarks, pages)
            SplitMode.SIZE -> {
                OutlinedTextField(
                    maxMb, { v -> maxMb = v.filter { c -> c.isDigit() || c == '.' }.take(6) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.ps_split_max_mb)) }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    supportingText = { file?.let { Text(stringResource(R.string.ps_split_size_hint, DocumentRepository.formatSize(it.length()))) } },
                )
            }
            else -> Unit
        }
        PrimaryButton(stringResource(R.string.tl_split), enabled = file != null, icon = Icons.Default.CallSplit) {
            val src = file ?: return@PrimaryButton
            val m = mode; val chunk = (n.toIntOrNull() ?: 1).coerceAtLeast(1); val spec = ranges; val total = pages
            val limit = ((maxMb.toDoubleOrNull() ?: 0.0) * 1024 * 1024).toLong()
            if (m == SplitMode.SIZE && limit < 50_000) { runner.error = badSize; return@PrimaryButton }
            if (m == SplitMode.BOOKMARKS && bookmarks?.isEmpty() == true) { runner.error = noBookmarks; return@PrimaryButton }
            runner.runMany(processing) {
                val dir = File(app.documents.docsDir, src.nameWithoutExtension + "_split").apply { mkdirs() }
                val base = src.nameWithoutExtension
                when (m) {
                    SplitMode.BOOKMARKS -> try { SplitOps.byBookmarks(src, dir) } catch (e: IllegalStateException) { throw IllegalStateException(noBookmarks) }
                    SplitMode.SIZE -> SplitOps.bySize(src, dir, base, limit)
                    SplitMode.EVERY_PAGE -> PdfOps.split(src, dir, base, 1)
                    SplitMode.EVERY_N -> PdfOps.split(src, dir, base, chunk)
                    SplitMode.RANGES -> {
                        val count = if (total > 0) total else PdfOps.pageCount(src)
                        val parts = spec.replace('؛', ';').split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                        parts.mapIndexedNotNull { i, part ->
                            val idx = PdfOps.parseRange(part, count)
                            if (idx.isEmpty()) null else app.documents.uniqueFile(dir, "${base}_${i + 1}").also { PdfOps.keepPages(src, idx, it) }
                        }
                    }
                }
            }
        }
        ResultsList(runner.results, nav, "application/pdf")
    }
}

/** Top-level bookmarks that will become files, with their page ranges. */
@Composable
private fun BookmarkPreview(marks: List<Pair<String, Int>>?, pageCount: Int) {
    when {
        marks == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        marks.isEmpty() -> Text(stringResource(R.string.ps_split_no_bookmarks), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        else -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.ps_split_bookmarks_found, marks.size), style = MaterialTheme.typography.titleSmall)
                val distinct = marks.distinctBy { it.second }
                distinct.take(30).forEachIndexed { i, (title, start) ->
                    val from = if (i == 0) 1 else start + 1
                    val to = if (i + 1 < distinct.size) distinct[i + 1].second else pageCount
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bookmark, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(title.ifBlank { "—" }, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        Text(if (to > from) "$from–$to" else "$from", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (distinct.size > 30) Text("…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
