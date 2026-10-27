package com.smartdocs.editor.ui.security

import android.graphics.RectF
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.redact.RedactApplier
import com.smartdocs.editor.core.redact.RedactMark
import com.smartdocs.editor.core.redact.RedactMarks
import com.smartdocs.editor.core.redact.SanitizeItem
import com.smartdocs.editor.core.search.TextSearch
import com.smartdocs.editor.ui.common.*
import com.smartdocs.editor.ui.search.rememberDocHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

private enum class RedactTab { SEARCH, MANUAL, PAGES, REVIEW }

/**
 * Redaction tool (mark → review → apply). Marks are `/Redact` annotations: they can be saved for review
 * («حفظ العلامات للمراجعة») and are loaded again when the file is opened here. «تطبيق الكل» removes the content
 * for real ([RedactApplier]), optionally with hidden information and/or as images (maximum security).
 */
@Composable
fun RedactScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberToolRunner()
    val doc = rememberDocHolder(uri)
    val st = remember { RedactState(ctx.applicationContext) }
    var tab by remember { mutableStateOf(RedactTab.SEARCH) }
    var page by remember { mutableIntStateOf(0) }
    var styleAll by remember { mutableStateOf(false) }
    var editMark by remember { mutableStateOf<RedactMark?>(null) }
    var repeatMark by remember { mutableStateOf<RedactMark?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showPii by remember { mutableStateOf(false) }      // smart PII detection (ui/ai5)
    // audit AN-10: metadata, bookmarks, attachments etc. also hold the redacted words, so sanitising is on by default
    var sanitize by remember { mutableStateOf(true) }
    var batchSkipped by remember { mutableStateOf<List<String>>(emptyList()) }
    var maxSecurity by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<File?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val pick = rememberPdfPicker { it.firstOrNull()?.let { f -> doc.file = f } }
    val batchPick = rememberPdfPicker(multiple = true) { files -> if (files.isNotEmpty()) runBatch(files, st, runner, app, ctx, sanitize, maxSecurity) { batchSkipped = it } }

    LaunchedEffect(doc.generation) {
        val f = doc.file ?: return@LaunchedEffect
        if (doc.session != null) { st.load(f, doc.password); page = 0 }
    }
    LaunchedEffect(runner.result) { runner.result?.let { result = it; runner.result = null } }
    LaunchedEffect(runner.error) { runner.error?.let { snack.showSnackbar(it); runner.error = null } }
    LaunchedEffect(doc.error) { doc.error?.let { snack.showSnackbar(it) } }

    val markedMsg = stringResource(R.string.s5_marked_n)
    fun search() { val s = doc.session ?: return; scope.launch { runCatching { st.search(s) }.onFailure { snack.showSnackbar(it.message ?: it.toString()) } } }
    fun markChecked() { val n = st.markChecked(); scope.launch { snack.showSnackbar(markedMsg.format(n)) } }

    val applying = stringResource(R.string.pg_redact_applying)
    val reportFmt = stringResource(R.string.s5_report)
    val rasterFmt = stringResource(R.string.s5_report_raster)
    fun applyAll() {
        val src = doc.file ?: return
        val marks = st.activeMarks()
        runner.run(applying) {
            val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + com.smartdocs.editor.core.data.NpfPrefs.redactSuffix.ifBlank { "_redacted" })   // nav-prefs: redaction file suffix
            val r = RedactApplier.apply(ctx, src, out, doc.password, marks, RedactApplier.Options(sanitize = sanitize, maxSecurity = maxSecurity))
            report = reportFmt.format(r.marks, r.pages, r.glyphs, r.objects + r.images, r.annots) +
                (if (r.rasterPages.isNotEmpty()) "\n" + rasterFmt.format(r.rasterPages.joinToString(", ") { (it + 1).toString() }) else "")
            runCatching { app.documents.touchRecent(out) }
            out
        }
    }
    val savingMsg = stringResource(R.string.s5_saving_marks)
    fun saveMarks() {
        val src = doc.file ?: return
        val marks = st.activeMarks()
        runner.run(savingMsg) {
            val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_marked")
            val author = runCatching { app.settings.settings.first().authorName }.getOrDefault("")
            RedactMarks.save(src, out, doc.password, marks, author)
            report = null
            runCatching { app.documents.touchRecent(out) }
            out
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            AppTopBar(stringResource(R.string.pg_redact_title), onBack = { nav.popBackStack() }) {
                if (doc.session != null) {
                    IconButton(onClick = { styleAll = true }) { Icon(Icons.Default.Palette, stringResource(R.string.s5_style_all)) }
                    IconButton(onClick = ::saveMarks, enabled = st.activeMarks().isNotEmpty() && !runner.running) { Icon(Icons.Default.BookmarkAdd, stringResource(R.string.s5_save_marks)) }
                    IconButton(onClick = { confirmClear = true }, enabled = st.marks.isNotEmpty()) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.pg_redact_clear)) }
                }
            }
        },
        bottomBar = {
            if (doc.session != null) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val active = st.activeMarks()
                    Text(stringResource(R.string.s5_marks_n, active.size) + " · " + stringResource(R.string.pg_redact_pages_affected, active.map { it.page }.distinct().size),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Button(onClick = { confirm = true }, enabled = active.isNotEmpty() && !runner.running) {
                        Icon(Icons.Default.Block, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.s5_apply_all, active.size))
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                FilePickRow(doc.file, stringResource(R.string.open_file), pick)
            }
            val s = doc.session
            if (s != null) BoxWithConstraints(Modifier.fillMaxSize()) {
                val tablet = maxWidth > 700.dp
                val tabs = if (tablet) listOf(RedactTab.SEARCH, RedactTab.MANUAL, RedactTab.PAGES) else RedactTab.values().toList()
                if (tablet && tab == RedactTab.REVIEW) tab = RedactTab.SEARCH
                Column(Modifier.fillMaxSize()) {
                    TabRow(tabs.indexOf(tab).coerceAtLeast(0)) {
                        tabs.forEach { t -> Tab(tab == t, { tab = t }, text = { Text(tabLabel(t), maxLines = 1) }) }
                    }
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            TabContent(tab, st, s, page, { page = it.coerceIn(0, (s.pageCount - 1).coerceAtLeast(0)) }, ::search, ::markChecked, { batchPick() },
                                onJump = { page = it; tab = RedactTab.MANUAL }, onEdit = { editMark = it }, onRepeat = { repeatMark = it }, onPii = { showPii = true })
                        }
                        if (tablet) {
                            VerticalDivider()
                            RedactReviewList(st, onJump = { page = it; tab = RedactTab.MANUAL }, onEdit = { editMark = it }, onRepeat = { repeatMark = it }, modifier = Modifier.width(320.dp).fillMaxHeight())
                        }
                    }
                }
            }
        }
    }

    if (runner.running) ProgressDialog(runner.message)
    result?.let { ResultSheet(it, nav, onDismiss = { result = null }, extraInfo = report) }
    if (runner.results.isNotEmpty() || batchSkipped.isNotEmpty()) BatchResults(runner.results, batchSkipped, nav) { runner.results = emptyList(); batchSkipped = emptyList() }
    if (styleAll) RedactStyleDialog(st.style, stringResource(R.string.s5_style_all), onDismiss = { styleAll = false }) { st.applyStyleToAll(it); styleAll = false }
    editMark?.let { m -> RedactStyleDialog(m.style, stringResource(R.string.s5_properties), onDismiss = { editMark = null }) { st.update(m.copy(style = it)); editMark = null } }
    repeatMark?.let { m -> PagePickDialog(stringResource(R.string.s5_repeat_pages), s5PageCount(doc.session), onDismiss = { repeatMark = null }) { st.repeatOnPages(m, it); repeatMark = null } }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(stringResource(R.string.pg_redact_clear)) },
        confirmButton = { TextButton(onClick = { st.clear(); confirmClear = false }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } })
    val piiMsg = stringResource(R.string.s5_pii_added)
    if (showPii) com.smartdocs.editor.ui.ai5.PiiScanSheet(doc.file, doc.password, doc.session, onApply = { hs ->
        showPii = false
        val n = st.addHits(hs)
        if (tab == RedactTab.SEARCH && doc.session != null) tab = RedactTab.REVIEW   // phone: the review list is its own tab
        scope.launch { snack.showSnackbar(piiMsg.format(n)) }
    }, onDismiss = { showPii = false })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.s5_apply_all, st.activeMarks().size)) },
        text = {
            Column {
                Text(stringResource(R.string.redact_warning))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(sanitize, { sanitize = it }); Text(stringResource(R.string.s5_also_sanitize), style = MaterialTheme.typography.bodyMedium) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(maxSecurity, { maxSecurity = it }); Text(stringResource(R.string.s5_max_security), style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = { confirm = false; applyAll() }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}

private fun s5PageCount(s: PdfSession?) = s?.pageCount ?: 1

@Composable
private fun tabLabel(t: RedactTab) = stringResource(when (t) {
    RedactTab.SEARCH -> R.string.redact_search
    RedactTab.MANUAL -> R.string.pg_redact_manual
    RedactTab.PAGES -> R.string.s5_tab_pages
    RedactTab.REVIEW -> R.string.s5_tab_review
})

@Composable
private fun TabContent(
    tab: RedactTab, st: RedactState, s: PdfSession, page: Int, onPage: (Int) -> Unit, onSearch: () -> Unit, onMark: () -> Unit, onBatch: () -> Unit,
    onJump: (Int) -> Unit, onEdit: (RedactMark) -> Unit, onRepeat: (RedactMark) -> Unit, onPii: () -> Unit,
) {
    when (tab) {
        RedactTab.SEARCH -> RedactSearchPane(st, true, onSearch, onMark, onBatch, Modifier.fillMaxSize(), onPii = onPii)
        RedactTab.MANUAL -> {
            val boxes = st.marks.filter { it.page == page }.flatMap { m ->
                if (m.wholePage) listOf(ManualBox(m.id, RectF(), m.style.fill, m.style.outline, wholePage = true))
                else m.rects.mapNotNull { r -> st.displayBox(page, r)?.let { b -> ManualBox(m.id, RectF(b.l, b.t, b.r, b.b), m.style.fill, m.style.outline) } }
            }
            RedactManualView(s, page, onPage, boxes, onAdd = { r -> st.addManual(page, r) }, Modifier.fillMaxSize())
        }
        RedactTab.PAGES -> RedactPagesPane(st, s.pageCount, Modifier.fillMaxSize())
        RedactTab.REVIEW -> RedactReviewList(st, onJump, onEdit, onRepeat, Modifier.fillMaxSize())
    }
}

/** Redacts several files with the current search inputs and style. */
private fun runBatch(files: List<File>, st: RedactState, runner: ToolRunner, app: com.smartdocs.editor.SmartDocsApp, ctx: android.content.Context, sanitize: Boolean, maxSecurity: Boolean, onSkipped: (List<String>) -> Unit) {
    val req = st.request()
    if (req.isEmpty) return
    val style = st.style
    runner.runMany(ctx.getString(R.string.pg_redact_applying)) {
        val outs = ArrayList<File>()
        // audit AN-10: protected / unreadable / failed files are reported, never silently left unredacted
        val skipped = ArrayList<String>()
        for ((i, f) in files.withIndex()) {
            runner.message = ctx.getString(R.string.pg_redact_applying) + " ${i + 1}/${files.size}"
            val session = runCatching { PdfSession.open(ctx, f, null) }.getOrNull()
            if (session == null) { skipped += f.name; continue }
            val found = try { TextSearch.search(session, req) } finally { session.closeAsync() }
            if (found.isEmpty()) continue
            val marks = found.filter { it.hit.rects.isNotEmpty() }.mapIndexed { k, h -> RedactMark("b$k-${System.nanoTime()}", h.hit.page, h.hit.rects.map { RectF(it) }, style, h.hit.snippet.trim()) }
            val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + com.smartdocs.editor.core.data.NpfPrefs.redactSuffix.ifBlank { "_redacted" })   // nav-prefs: redaction file suffix
            runCatching {
                RedactApplier.apply(ctx, f, out, null, marks, RedactApplier.Options(sanitize = sanitize, sanitizeItems = SanitizeItem.DEFAULTS, maxSecurity = maxSecurity))
                runCatching { app.documents.touchRecent(out) }
                outs += out
            }.onFailure { skipped += f.name }
        }
        onSkipped(skipped)
        outs
    }
}

@Composable
private fun BatchResults(files: List<File>, skipped: List<String>, nav: NavHostController, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.s5_batch_done, files.size)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                if (skipped.isNotEmpty()) item {
                    Text(stringResource(R.string.fx_redact_skipped, skipped.size) + "\n" + skipped.joinToString("\n"),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(files, key = { it.absolutePath }) { f ->
                    ListItem(headlineContent = { Text(f.name) }, trailingContent = {
                        IconButton(onClick = { onDismiss(); nav.navigate(com.smartdocs.editor.ui.Routes.reader(android.net.Uri.fromFile(f).toString())) }) { Icon(Icons.Default.OpenInNew, stringResource(R.string.open)) }
                    })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } })
}
