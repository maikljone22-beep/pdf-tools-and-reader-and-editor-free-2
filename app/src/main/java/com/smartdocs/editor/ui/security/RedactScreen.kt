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
import kotlinx.coroutines.flow.collectLatest
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
    var styleMark by remember { mutableStateOf<RedactMark?>(null) }
    var outPrefix by remember { mutableStateOf(com.smartdocs.editor.core.redact.RedactPrefs.outPrefix(ctx)) }
    var outSuffix by remember { mutableStateOf(com.smartdocs.editor.core.redact.RedactPrefs.outSuffix(ctx)) }
    val me = LocalSettingsAuthor()
    var repeatMark by remember { mutableStateOf<RedactMark?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var showPii by remember { mutableStateOf(false) }      // smart PII detection (ui/ai5)
    // audit AN-10: metadata, bookmarks, attachments etc. also hold the redacted words, so sanitising is on by default
    var sanitize by remember { mutableStateOf(true) }
    var batchSkipped by remember { mutableStateOf<List<String>>(emptyList()) }
    var batchNoHits by remember { mutableStateOf<List<String>>(emptyList()) }
    /** audit 5b D7: the single mark "apply this mark" waits for confirmation (null = the confirm dialog is for all marks). */
    var confirmOne by remember { mutableStateOf<RedactMark?>(null) }
    var maxSecurity by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<File?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val pick = rememberPdfPicker { it.firstOrNull()?.let { f -> doc.file = f } }
    val batchPick = rememberPdfPicker(multiple = true) { files -> if (files.isNotEmpty()) runBatch(files, st, runner, app, ctx, sanitize, maxSecurity) { skipped, noHits -> batchSkipped = skipped; batchNoHits = noHits } }

    // T-09: the password the reader opened this document with
    LaunchedEffect(Unit) {
        val pw = com.smartdocs.editor.core.pdf.SessionPasswords.get(doc.file)
        if (pw != null && doc.password == null) { doc.password = pw; doc.askPassword = false }
    }
    var signed by remember { mutableStateOf(false) }
    val restoredFmt = stringResource(R.string.ttrd_draft_restored)
    val loadFailedMsg = stringResource(R.string.ttrd_marks_load_failed)
    LaunchedEffect(doc.generation) {
        val f = doc.file ?: return@LaunchedEffect
        if (doc.session == null) return@LaunchedEffect
        val pw = doc.password
        // audit 5 R-4: warn before applying (the output is a new file whose signature values are removed)
        signed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.smartdocs.editor.core.pdf.SignedSave.isSigned(f, pw) }
        val restored = st.load(f, pw); page = 0
        if (restored > 0) scope.launch { snack.showSnackbar(restoredFmt.format(restored)) }
        if (st.loadFailed) scope.launch { snack.showSnackbar(loadFailedMsg) }   // audit 5b L4
        // audit 5 R-L7: unsaved marks are kept as a draft (written shortly after each change)
        snapshotFlow { st.marks.toList() to st.excluded }.collectLatest { (list, ex) ->
            kotlinx.coroutines.delay(800)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { st.saveDraft(f, list, ex) } }
        }
    }
    LaunchedEffect(runner.result) { runner.result?.let { result = it; runner.result = null } }
    LaunchedEffect(runner.error) { runner.error?.let { snack.showSnackbar(it); runner.error = null } }
    LaunchedEffect(doc.error) { doc.error?.let { snack.showSnackbar(it) } }

    val markedMsg = stringResource(R.string.s5_marked_n)
    val noImageMsg = stringResource(R.string.nt_rd_no_image)
    val imageFailedMsg = stringResource(R.string.ttrd_image_failed)
    fun search() { val s = doc.session ?: return; scope.launch { runCatching { st.search(s) }.onFailure { snack.showSnackbar(it.message ?: it.toString()) } } }
    fun markChecked() { val n = st.markChecked(); scope.launch { snack.showSnackbar(markedMsg.format(n)) } }

    val applying = stringResource(R.string.pg_redact_applying)
    val reportFmt = stringResource(R.string.s5_report)
    val rasterFmt = stringResource(R.string.s5_report_raster)
    val sigFmt = stringResource(R.string.ttrd_sig_removed)
    val skipText = rememberSkipText()
    val failedMsg = stringResource(R.string.ttrd_failed)
    val wrongPwMsg = stringResource(R.string.ttrd_wrong_password)
    /** Applies [only] (all active marks when null); the other active marks stay pending in the result. */
    fun applyAll(only: RedactMark? = null) {
        val src = doc.file ?: return
        val marks = if (only != null) listOf(only) else st.activeMarks()
        val pending = if (only != null) st.activeMarks().filter { it.id != only.id } else emptyList()
        com.smartdocs.editor.core.redact.RedactPrefs.saveOutName(ctx, outPrefix, outSuffix)
        val base = outPrefix + src.nameWithoutExtension + outSuffix
        runner.run(applying) {
            val out = app.documents.uniqueFile(app.documents.docsDir, base.ifBlank { src.nameWithoutExtension + "_redacted" })
            val r = try {
                RedactApplier.apply(ctx, src, out, doc.password, marks, RedactApplier.Options(sanitize = sanitize, maxSecurity = maxSecurity, keepPending = pending, author = me))
            } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { throw IllegalStateException(wrongPwMsg, e) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { throw IllegalStateException(failedMsg, e) }
            report = reportFmt.format(r.marks, r.pages, r.glyphs, r.objects + r.images, r.annots) +
                (if (r.rasterPages.isNotEmpty()) "\n" + rasterFmt.format(r.rasterPages.joinToString(", ") { (it + 1).toString() }) else "") +
                (if (r.signaturesRemoved > 0) "\n" + sigFmt.format(r.signaturesRemoved) else "") +
                (skipText(r.sanitizeSkipped)?.let { "\n" + it } ?: "")
            runCatching { app.documents.touchRecent(out) }
            st.draftDone(src)
            com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
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
            st.draftDone(src)
            com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
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
                                onJump = { page = it; tab = RedactTab.MANUAL }, onEdit = { editMark = it }, onRepeat = { repeatMark = it }, onPii = { showPii = true },
                                onImageTap = { x, y -> val f = doc.file; if (f != null) scope.launch {
                                    // audit 5b L4: a page that could not be inspected is not "no image here"
                                    when (st.markImageAt(f, doc.password, page, x, y)) {
                                        ImageMark.NONE -> snack.showSnackbar(noImageMsg)
                                        ImageMark.FAILED -> snack.showSnackbar(imageFailedMsg)
                                        ImageMark.ADDED -> {}
                                    }
                                } })
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
    if (runner.results.isNotEmpty() || batchSkipped.isNotEmpty() || batchNoHits.isNotEmpty())
        BatchResults(runner.results, batchSkipped, batchNoHits, nav) { runner.results = emptyList(); batchSkipped = emptyList(); batchNoHits = emptyList() }
    if (styleAll) RedactStyleDialog(st.style, stringResource(R.string.s5_style_all), onDismiss = { styleAll = false }) { st.applyStyleToAll(it); styleAll = false }
    editMark?.let { m0 ->
        val m = st.marks.firstOrNull { it.id == m0.id } ?: m0
        MarkPropertiesDialog(m, me, onDismiss = { editMark = null }, onAppearance = { styleMark = m },
            onSave = { a, s, l -> st.setGeneral(m.id, a, s, l); editMark = null }, onReview = { state -> st.review(m.id, state, me) },
            onApplyOne = { editMark = null; confirmOne = m; confirm = true })
    }
    styleMark?.let { m -> RedactStyleDialog(m.style, stringResource(R.string.s5_properties), onDismiss = { styleMark = null }) { st.update((st.marks.firstOrNull { it.id == m.id } ?: m).copy(style = it)); styleMark = null } }
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
    // audit 5b D7: "apply this mark" also confirms, with its own sanitize / maximum-security choice
    if (confirm) AlertDialog(onDismissRequest = { confirm = false; confirmOne = null },
        title = { Text(if (confirmOne != null) stringResource(R.string.ttrd_apply_one_title) else stringResource(R.string.s5_apply_all, st.activeMarks().size)) },
        text = {
            Column {
                Text(stringResource(R.string.redact_warning))
                if (confirmOne != null && st.activeMarks().size > 1) Text(stringResource(R.string.ttrd_apply_one_pending), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                if (signed) Text(stringResource(R.string.ttrd_signed_warning), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                // audit 5 P-L: the whole row toggles, so TalkBack reads the label with the checkbox
                ToggleRow(stringResource(R.string.s5_also_sanitize), sanitize) { sanitize = it }
                ToggleRow(stringResource(R.string.s5_max_security), maxSecurity) { maxSecurity = it }
                Text(stringResource(R.string.nt_rd_file_name), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(outPrefix, { outPrefix = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.nt_rd_prefix)) })
                    OutlinedTextField(outSuffix, { outSuffix = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.nt_rd_suffix)) })
                }
                Text(outPrefix + (doc.file?.nameWithoutExtension ?: "") + outSuffix + ".pdf", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { val one = confirmOne; confirm = false; confirmOne = null; applyAll(one) }) { Text(stringResource(R.string.apply)) } },
        dismissButton = { TextButton(onClick = { confirm = false; confirmOne = null }) { Text(stringResource(R.string.cancel)) } })
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
    onImageTap: (Float, Float) -> Unit = { _, _ -> },
) {
    when (tab) {
        RedactTab.SEARCH -> RedactSearchPane(st, true, onSearch, onMark, onBatch, Modifier.fillMaxSize(), onPii = onPii)
        RedactTab.MANUAL -> {
            val boxes = st.marks.filter { it.page == page }.flatMap { m ->
                if (m.wholePage) listOf(ManualBox(m.id, RectF(), m.style.fill, m.style.outline, wholePage = true, locked = m.locked))
                else m.rects.mapNotNull { r -> st.displayBox(page, r)?.let { b -> ManualBox(m.id, RectF(b.l, b.t, b.r, b.b), m.style.fill, m.style.outline, locked = m.locked, single = m.rects.size == 1) } }
            }
            RedactManualView(s, page, onPage, boxes, onAdd = { r -> st.addManual(page, r) }, Modifier.fillMaxSize(),
                onMove = { id, dx, dy -> st.move(id, page, dx, dy) }, onResize = { id, r -> st.resize(id, page, r) }, onDoubleTap = onImageTap)
        }
        RedactTab.PAGES -> RedactPagesPane(st, s.pageCount, Modifier.fillMaxSize())
        RedactTab.REVIEW -> RedactReviewList(st, onJump, onEdit, onRepeat, Modifier.fillMaxSize())
    }
}

/**
 * Redacts several files with the current search inputs, page range and style. audit 5b L5: the page range applies to
 * every file, files without a match are reported, and the Compose state is set on the main thread.
 */
private fun runBatch(files: List<File>, st: RedactState, runner: ToolRunner, app: com.smartdocs.editor.SmartDocsApp, ctx: android.content.Context, sanitize: Boolean, maxSecurity: Boolean,
                     onReport: (skipped: List<String>, noHits: List<String>) -> Unit) {
    val req = st.request()
    if (req.isEmpty) return
    val style = st.style
    val range = st.pageRange
    val applying = ctx.getString(R.string.pg_redact_applying)
    runner.runMany(applying) {
        val outs = ArrayList<File>()
        // audit AN-10: protected / unreadable / failed files are reported, never silently left unredacted
        val skipped = ArrayList<String>()
        val noHits = ArrayList<String>()
        for ((i, f) in files.withIndex()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { runner.message = "$applying ${i + 1}/${files.size}" }
            val session = runCatching { PdfSession.open(ctx, f, null) }.getOrNull()
            if (session == null) { skipped += f.name; continue }
            val found = try { runCatching { st.searchPages(session, req, range) }.getOrNull() } finally { session.closeAsync() }
            if (found == null) { skipped += f.name; continue }
            val marks = found.filter { it.hit.rects.isNotEmpty() }.mapIndexed { k, h -> RedactMark("b$k-${System.nanoTime()}", h.hit.page, h.hit.rects.map { RectF(it) }, style, h.hit.snippet.trim()) }
            if (marks.isEmpty()) { noHits += f.name; continue }
            val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_redacted")
            runCatching {
                RedactApplier.apply(ctx, f, out, null, marks, RedactApplier.Options(sanitize = sanitize, sanitizeItems = SanitizeItem.DEFAULTS, maxSecurity = maxSecurity))
                runCatching { app.documents.touchRecent(out) }
                outs += out
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; skipped += f.name }
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onReport(skipped, noHits) }
        outs
    }
}

@Composable
private fun BatchResults(files: List<File>, skipped: List<String>, noHits: List<String>, nav: NavHostController, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.s5_batch_done, files.size)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                if (skipped.isNotEmpty()) item {
                    Text(stringResource(R.string.fx_redact_skipped, skipped.size) + "\n" + skipped.joinToString("\n"),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (noHits.isNotEmpty()) item {
                    Text(stringResource(R.string.ttrd_batch_no_hits, noHits.size) + "\n" + noHits.joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
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

/** The author name from the settings (marks, review history). */
@Composable
private fun LocalSettingsAuthor(): String = com.smartdocs.editor.LocalSettings.current.authorName
