package com.smartdocs.editor.ui.edit5

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.LinkOps
import com.smartdocs.editor.core.edit5.WebCapture
import com.smartdocs.editor.core.view5.PrintLayout
import com.smartdocs.editor.ui.tools.ChoiceChips
import kotlinx.coroutines.launch
import java.io.File

/** What the web links sheet does with the checked links. */
private enum class WebJob { DOWNLOAD, CONVERT, APPEND }

/**
 * fix/links-bookmarks (Foxit §15 "View Web Links" / "Append All Web Links"): the web links of the current page, all
 * pages or a range, with select all / clear and three jobs for the checked ones: download the target as is, save
 * each web page as a PDF, or append the converted pages to this document (bookmarks by domain, links re-pointed at
 * the appended pages). Every address goes through the Internet access gate first.
 * [onAppend] runs the append on the open document with (url → converted PDF) and the three options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebLinksSheet(work: File, password: String?, pageCount: Int, current: Int, onDismiss: () -> Unit,
                  onAppend: (List<Pair<String, File>>, Boolean, Boolean, Boolean) -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var allPages by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf("") }
    var links by remember { mutableStateOf<List<WebCapture.WebLink>?>(null) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bookmarks by remember { mutableStateOf(true) }
    var byDomain by remember { mutableStateOf(true) }
    var relink by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    val pages = remember(allPages, range, current, pageCount) {
        when {
            range.isNotBlank() -> PrintLayout.parseRange(range, pageCount).toSet()
            allPages -> (0 until pageCount).toSet()
            else -> setOf(current)
        }
    }
    LaunchedEffect(work, pages) {
        links = null
        val all = runCatching { LinkOps.list(work, password) }.getOrDefault(emptyList()).filter { it.page in pages }
        links = WebCapture.collect(all).also { l -> checked = l.map { it.url }.toSet() }
    }
    fun runJob(job: WebJob) {
        val urls = links.orEmpty().map { it.url }.filter { it in checked }
        if (urls.isEmpty()) return
        scope.launch {
            val done = ArrayList<Pair<String, File>>()
            val failed = ArrayList<String>()
            progress = 0 to urls.size
            for ((i, u) in urls.withIndex()) {
                progress = i to urls.size
                if (!com.smartdocs.editor.ui.security.DocSecurityGate.allowWeb(ctx, u)) { failed += u; continue }
                val r = runCatching {
                    when (job) {
                        WebJob.DOWNLOAD -> WebCapture.download(u, File(app.documents.docsDir, "Downloads"))
                        WebJob.CONVERT -> com.smartdocs.editor.core.tools5.ToPdf.url(ctx, u, app.documents.uniqueFile(app.documents.docsDir, WebCapture.fileName(u)))
                        WebJob.APPEND -> com.smartdocs.editor.core.tools5.ToPdf.url(ctx, u, File(app.documents.tempDir, "lbm_web_${System.nanoTime()}.pdf"))
                    }
                }
                r.onSuccess { done += u to it }.onFailure { failed += u }
            }
            progress = null
            if (job == WebJob.APPEND && done.isNotEmpty()) { onAppend(done, bookmarks, byDomain, relink); return@launch }
            result = ctx.getString(R.string.lbm_web_done, done.size, failed.size) +
                (if (job == WebJob.DOWNLOAD && done.isNotEmpty()) "\n" + done.first().second.parentFile?.absolutePath.orEmpty() else "")
        }
    }
    ModalBottomSheet(onDismissRequest = { if (progress == null) onDismiss() }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lbm_web_links), style = MaterialTheme.typography.titleLarge)
            ChoiceChips(listOf(false, true), allPages, { stringResource(if (it) R.string.nbm_all_pages else R.string.nbm_current_page) }) { allPages = it; range = "" }
            OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nbm_page_range)) }, placeholder = { Text("1-3, 5") })
            val l = links
            when {
                l == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                l.isEmpty() -> Text(stringResource(R.string.lbm_web_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.lbm_web_n, checked.size, l.size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { checked = l.map { it.url }.toSet() }) { Text(stringResource(R.string.nbm_select_all)) }
                        TextButton(onClick = { checked = emptySet() }) { Text(stringResource(R.string.nbm_clear)) }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                        items(l, key = { it.url }) { w ->
                            Row(Modifier.fillMaxWidth().clickable { checked = if (w.url in checked) checked - w.url else checked + w.url }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(w.url in checked, null)
                                Column(Modifier.weight(1f)) {
                                    Text(w.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource(R.string.lbm_web_pages, w.pages.joinToString(", ") { (it + 1).toString() }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.lbm_web_append_opts), style = MaterialTheme.typography.titleSmall)
                    SwitchRow(stringResource(R.string.lbm_web_bookmarks), bookmarks) { bookmarks = it }
                    if (bookmarks) SwitchRow(stringResource(R.string.lbm_web_by_domain), byDomain) { byDomain = it }
                    SwitchRow(stringResource(R.string.lbm_web_relink), relink) { relink = it }
                    val any = checked.isNotEmpty() && progress == null
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        OutlinedButton(onClick = { runJob(WebJob.DOWNLOAD) }, enabled = any) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.lbm_web_download)) }
                        OutlinedButton(onClick = { runJob(WebJob.CONVERT) }, enabled = any) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.lbm_web_convert)) }
                        Button(onClick = { runJob(WebJob.APPEND) }, enabled = any) { Icon(Icons.Default.PostAdd, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.lbm_web_append)) }
                    }
                }
            }
            progress?.let { (i, n) ->
                Text(stringResource(R.string.lbm_web_working, i + 1, n), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { i.toFloat() / n.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
