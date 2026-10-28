package com.smartdocs.editor.ui.search

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.SignedGuard
import com.smartdocs.editor.core.redact.RedactApplier
import com.smartdocs.editor.core.redact.SearchRemove
import com.smartdocs.editor.core.search.Found
import com.smartdocs.editor.core.search.SearchMode
import com.smartdocs.editor.core.search.SearchOptions
import com.smartdocs.editor.core.search.SearchPrefs
import com.smartdocs.editor.core.search.SearchRequest
import com.smartdocs.editor.core.search.TextMatcher
import com.smartdocs.editor.core.search.TextSearch
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.launch
import java.io.File

/**
 * #17 Search & Remove Text (tool `search_remove`): a word list (typed or imported from a .txt file) is searched over
 * the document, the hits are listed (tick / untick, preview on the page), and the ticked hits are removed for real by
 * the redaction core ([RedactApplier] with `removeTextOnly`): the text leaves the page content, nothing covers it.
 * The result is always a new copy; a signed or certified file is never rewritten ([SignedGuard]: the copy is offered).
 */
@Composable
fun SearchRemoveScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = rememberToolRunner()
    val doc = rememberDocHolder(uri)
    var words by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(SearchOptions(norm = SearchPrefs.load(ctx))) }
    var range by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Found>>(emptyList()) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Found?>(null) }
    var result by remember { mutableStateOf<File?>(null) }
    var report by remember { mutableStateOf<String?>(null) }
    var guard by remember { mutableStateOf(SignedGuard.Decision.PROCEED) }
    val snack = remember { SnackbarHostState() }
    val pick = rememberPdfPicker { it.firstOrNull()?.let { f -> doc.open(f); found = emptyList(); checked = emptySet(); searched = false } }
    val importWords = com.smartdocs.editor.ui.security.rememberTextImport(arrayOf("text/plain", "text/*", "application/octet-stream")) { t -> words = SearchRemove.merge(words, t) }
    val list = SearchRemove.parseWords(words)

    // the signed / certified gate: removing text is a page-content change (never allowed in place on a signed file)
    LaunchedEffect(doc.file, doc.generation) {
        val f = doc.file
        guard = if (f == null || doc.session == null) SignedGuard.Decision.PROCEED
            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SignedGuard.decide(SignedGuard.inspect(f, doc.password), SignedGuard.Change.MODIFY, null) }
    }
    LaunchedEffect(runner.result) { runner.result?.let { result = it; runner.result = null } }
    LaunchedEffect(runner.error) { runner.error?.let { snack.showSnackbar(it); runner.error = null } }
    LaunchedEffect(doc.error) { doc.error?.let { snack.showSnackbar(it) } }

    fun doFind() {
        val s = doc.session ?: return
        if (list.isEmpty()) return
        scope.launch {
            searching = true
            try {
                val req = SearchRequest(options = options.copy(mode = SearchMode.PHRASE), words = list)
                val pages = PdfOps.parseRange(range, s.pageCount)
                found = if (pages.size == s.pageCount) TextSearch.search(s, req) else pages.flatMap { p -> TextSearch.search(s, req, p..p) }
                checked = found.map(SearchRemove::key).toSet()
                searched = true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) {
                found = emptyList(); searched = false
                snack.showSnackbar(searchErrorMessage(ctx, e))
            } finally { searching = false }
        }
    }

    val removing = stringResource(R.string.ett_sr_removing)
    val doneFmt = stringResource(R.string.ett_sr_done)
    val rasterFmt = stringResource(R.string.ett_sr_raster)
    val failedMsg = stringResource(R.string.ett_sr_failed)
    val wrongPw = stringResource(R.string.password_wrong)
    fun doRemove() {
        val src = doc.file ?: return
        val marks = SearchRemove.marks(found, checked)
        if (marks.isEmpty()) return
        val pw = doc.password
        runner.run(removing) {
            // always a new file: the original (signed or not) is never rewritten here
            val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension + "_removed")
            val r = try {
                RedactApplier.apply(ctx, src, out, pw, marks, RedactApplier.Options(removeTextOnly = true))
            } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { out.delete(); throw IllegalStateException(wrongPw, e) }
            catch (e: kotlinx.coroutines.CancellationException) { out.delete(); throw e }
            catch (e: Exception) { out.delete(); throw IllegalStateException(failedMsg, e) }
            report = doneFmt.format(r.glyphs, r.pages) +
                (if (r.rasterPages.isNotEmpty()) "\n" + rasterFmt.format(r.rasterPages.joinToString(", ") { (it + 1).toString() }) else "")
            runCatching { app.documents.touchRecent(out) }
            com.smartdocs.editor.core.pdf.ToolOutputs.record(out, src)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { AppTopBar(stringResource(R.string.ett_sr_title), onBack = { nav.popBackStack() }) },
        bottomBar = {
            if (found.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val sel = found.filter { SearchRemove.key(it) in checked }
                    Text(stringResource(R.string.ett_sr_hits, sel.size, sel.map { it.hit.page }.distinct().size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Button(onClick = { confirm = true }, enabled = sel.isNotEmpty() && !runner.running) {
                        Icon(Icons.Default.DeleteSweep, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ett_sr_remove, sel.size))
                    }
                }
            }
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val tablet = maxWidth > 700.dp
            val controls: @Composable (Modifier) -> Unit = { m ->
                Column(m.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    FilePickRow(doc.file, stringResource(R.string.open_file), pick)
                    if (guard != SignedGuard.Decision.PROCEED) Text(stringResource(R.string.ett_sr_signed), Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                        label = { Text(stringResource(R.string.ett_sr_words)) }, supportingText = { Text(stringResource(R.string.ett_sr_words_n, list.size)) })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { importWords() }) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ett_sr_import)) }
                        TextButton(onClick = { words = "" }, enabled = words.isNotEmpty()) { Text(stringResource(R.string.ett_sr_clear)) }
                    }
                    SearchOptionsPanel(options, { options = it }, modes = listOf(SearchMode.PHRASE), showStem = false)
                    PageRangeField(range) { range = it }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = ::doFind, enabled = doc.session != null && list.isNotEmpty() && !searching, modifier = Modifier.fillMaxWidth()) {
                        if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ett_sr_find))
                    }
                    Text(stringResource(R.string.ett_sr_note), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (searched && found.isEmpty() && !searching) Text(stringResource(R.string.ett_sr_none), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val bg = MaterialTheme.colorScheme.tertiaryContainer
            val hitsList: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ett_sr_hits, found.size, found.map { it.hit.page }.distinct().size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { checked = found.map(SearchRemove::key).toSet() }) { Text(stringResource(R.string.select_all)) }
                        TextButton(onClick = { checked = emptySet() }) { Text(stringResource(R.string.s5_select_none)) }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        SearchRemove.byPage(found).forEach { (page, hits) ->
                            item(key = "p$page") {
                                Text(stringResource(R.string.ett_sr_page, page + 1, hits.size), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            items(hits, key = { SearchRemove.key(it) }) { f ->
                                val id = SearchRemove.key(f)
                                val snippet = f.hit.snippet
                                val ranges = list.flatMap { w -> TextMatcher.find(snippet, w, options.copy(mode = SearchMode.PHRASE)) }
                                ListItem(
                                    modifier = Modifier.toggleable(value = id in checked, role = androidx.compose.ui.semantics.Role.Checkbox,
                                        onValueChange = { checked = if (id in checked) checked - id else checked + id }),
                                    leadingContent = { Checkbox(id in checked, null) },
                                    headlineContent = { Text(highlighted(snippet, ranges, bg), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
                                    trailingContent = { IconButton(onClick = { preview = f }) { Icon(Icons.Default.Visibility, stringResource(R.string.ett_sr_preview)) } },
                                )
                            }
                        }
                    }
                }
            }
            if (tablet) Row(Modifier.fillMaxSize()) { controls(Modifier.width(380.dp).fillMaxHeight()); hitsList(Modifier.weight(1f).fillMaxHeight()) }
            else Column(Modifier.fillMaxSize()) {
                controls(Modifier.fillMaxWidth().heightIn(max = if (found.isEmpty()) 2000.dp else 320.dp))
                if (found.isNotEmpty()) hitsList(Modifier.weight(1f))
            }
        }
    }
    preview?.let { f -> HitPreviewDialog(doc, f, found.filter { it.hit.page == f.hit.page && SearchRemove.key(it) in checked }) { preview = null } }
    if (confirm) {
        val n = found.count { SearchRemove.key(it) in checked }
        val text = stringResource(R.string.ett_sr_confirm, n) + if (guard != SignedGuard.Decision.PROCEED) "\n\n" + stringResource(R.string.ett_sr_signed) else ""
        AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.ett_sr_title)) },
            text = { Text(text, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { confirm = false; doRemove() }) { Text(stringResource(R.string.ett_sr_remove, n)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (runner.running) ProgressDialog(runner.message)
    result?.let { ResultSheet(it, nav, onDismiss = { result = null; report = null }, extraInfo = report) }
}

/** The page of hit [f] with the ticked hits of that page outlined ([f] in a stronger colour). */
@Composable
private fun HitPreviewDialog(doc: DocHolder, f: Found, ticked: List<Found>, onDismiss: () -> Unit) {
    var bmp by remember(f) { mutableStateOf<Bitmap?>(null) }
    var size by remember(f) { mutableStateOf<Pair<Float, Float>?>(null) }
    LaunchedEffect(f) {
        val s = doc.session ?: return@LaunchedEffect
        runCatching {
            val (w, h) = s.pageSize(f.hit.page)
            val k = 1000f / maxOf(w, h).coerceAtLeast(1f)
            size = w to h
            bmp = s.render(f.hit.page, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.ett_sr_page, f.hit.page + 1, ticked.size)) },
        text = {
            val b = bmp; val sz = size
            if (b == null || sz == null) Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else Box(Modifier.fillMaxWidth().aspectRatio(sz.first / sz.second)) {
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Canvas(Modifier.fillMaxSize()) {
                    val k = this.size.width / sz.first
                    // hit rects are in PDF points (y up, top > bottom) like the page PDFium rendered
                    fun mark(h: Found, strong: Boolean) = h.hit.rects.forEach { r ->
                        val l = minOf(r.left, r.right) * k; val t = (sz.second - maxOf(r.top, r.bottom)) * k
                        val w = kotlin.math.abs(r.right - r.left) * k; val hh = kotlin.math.abs(r.top - r.bottom) * k
                        drawRect(Color(if (strong) 0x66E53935 else 0x33E53935), Offset(l, t), Size(w, hh))
                        drawRect(Color(0xFFE53935), Offset(l, t), Size(w, hh), style = Stroke(if (strong) 3f else 1.5f))
                    }
                    ticked.forEach { mark(it, false) }
                    mark(f, true)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } })
}
