package com.smartdocs.editor.ui.tools5

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ResultsList
import com.smartdocs.editor.ui.tools.ToolScaffold
import java.io.File

/** E-mail header labels for the EML reader, from string resources. */
@Composable
fun rememberEmlLabels(): Map<String, String> {
    val m = mapOf(
        "from" to stringResource(R.string.t5_eml_from), "to" to stringResource(R.string.t5_eml_to), "cc" to stringResource(R.string.t5_eml_cc),
        "date" to stringResource(R.string.t5_eml_date), "subject" to stringResource(R.string.t5_eml_subject), "attachments" to stringResource(R.string.t5_eml_attachments),
    )
    LaunchedEffect(m) { ToPdf.emlLabels = m }
    return m
}

/**
 * "Convert each" of Tools → To PDF / Share-to-PDF (convert audit A-H5): every file is tried, a failing one is reported
 * by name and its partial output removed, the others go on. Returns (outputs, "name: reason" lines).
 */
suspend fun convertEachToPdf(ctx: android.content.Context, docs: com.smartdocs.editor.core.files.DocumentRepository, list: List<File>, step: (Int, Int) -> Unit,
                             each: suspend (File, File) -> Unit = { _, _ -> }): Pair<List<File>, String?> {
    val outs = ArrayList<File>()
    val o = com.smartdocs.editor.core.convert6.Cv6Batch.each(list, onFailure = { _, _ -> }) { i, f ->
        step(i, list.size)
        val dst = docs.uniqueFile(docs.docsDir, f.nameWithoutExtension)
        try { ToPdf.convert(ctx, f, dst); each(f, dst); outs += dst } catch (e: Throwable) { dst.delete(); throw e }
    }
    step(list.size, list.size)
    return outs to com.smartdocs.editor.core.convert6.Cv6Batch.failureLines(o.failures) { com.smartdocs.editor.ui.convert6.Cv6ErrorText.of(ctx, it) }
}

/** Any-format → PDF: combine mixed files (bookmark per file), web page / HTML, or convert each file separately. */
@Composable
fun ToPdfPlusScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    rememberEmlLabels()
    var tab by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf(listOfNotNull(fileOf(uri))) }
    var bookmarks by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var html by remember { mutableStateOf("") }
    var background by remember { mutableStateOf(false) }
    var attachSrc by remember { mutableStateOf(false) }   // nav-attach: "attach source file"
    val srcDesc = stringResource(R.string.nat_source_desc)
    val job = rememberT5Job()
    val web = remember { WebOpts() }
    // fc-settings: saved defaults of this screen (bookmarks, attach source, background, web page layout)
    LaunchedEffect(Unit) { com.smartdocs.editor.core.convert6.Cv6Defaults.screen(ctx, TO_PDF_DEFAULTS)?.let { j ->
        bookmarks = j.optBoolean("bookmarks", bookmarks); attachSrc = j.optBoolean("attach", attachSrc); background = j.optBoolean("background", background)
        web.paper = j.optString("paper", web.paper); web.landscape = j.optBoolean("landscape", web.landscape); web.margin = j.optInt("margin", web.margin)
        web.timeoutSec = j.optDouble("timeout", web.timeoutSec.toDouble()).toFloat(); web.images = j.optBoolean("images", web.images)
        web.backgrounds = j.optBoolean("backgrounds", web.backgrounds); web.underline = j.optBoolean("underline", web.underline); web.noLinks = j.optBoolean("noLinks", web.noLinks)
    } }
    val savedMsg = stringResource(R.string.fcs_cv_saved)
    val pick = rememberAnyFilePicker(true, ToPdf.pickerMimes) { picked -> files = files + picked.filter { f -> files.none { it.absolutePath == f.absolutePath } } }
    // kinds by content, like the hub (convert audit A-M8): a .doc / .odt / .xls / .tiff is not "unsupported" here
    val unsupported by produceState(emptyList<File>(), files) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { files.filter { ToPdf.kindOf(it) == SourceKind.UNKNOWN } } }
    val combining = stringResource(R.string.t5_combining)
    val converting = stringResource(R.string.t5_converting)
    val queued = stringResource(R.string.t5_batch_queued)
    val defaultName = stringResource(R.string.t5_combined_name)

    ToolScaffold(stringResource(R.string.t5_topdf_title), nav, snackbar = snackbar) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.t5_tab_combine)) })
            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.t5_tab_each)) })
            Tab(tab == 2, { tab = 2 }, text = { Text(stringResource(R.string.t5_tab_web)) })
        }
        when (tab) {
            // Nitro 5 / Foxit 4: the combiner with sources, page ranges, titles, sort, headings and TOC
            0 -> {
                SwitchRow(stringResource(R.string.nat_attach_source), attachSrc, stringResource(R.string.nat_attach_source_sub)) { attachSrc = it }
                CombineTab(files, job, attachSrc, srcDesc)
            }
            1 -> {
                Text(stringResource(R.string.t5_topdf_formats), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = pick, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_add_files)) }
                if (files.isNotEmpty()) T5Card { FileListEditor(files, { files = it }) }
                if (unsupported.isNotEmpty()) Text(stringResource(R.string.t5_unsupported, unsupported.joinToString { it.name }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (tab == 0 || !background) SwitchRow(stringResource(R.string.nat_attach_source), attachSrc, stringResource(R.string.nat_attach_source_sub)) { attachSrc = it }
                if (tab == 0) {
                    SwitchRow(stringResource(R.string.t5_bookmark_per_file), bookmarks) { bookmarks = it }
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_file_name_optional)) })
                    PrimaryButton(stringResource(R.string.t5_combine), enabled = files.isNotEmpty() && unsupported.isEmpty() && !job.running, icon = Icons.Default.CallMerge) {
                        val list = files.toList(); val bm = bookmarks; val base = name.ifBlank { if (list.size == 1) list[0].nameWithoutExtension else defaultName }
                        val attach = attachSrc
                        job.launch(combining) {
                            val out = app.documents.uniqueFile(app.documents.docsDir, base)
                            ToPdf.combine(ctx, list.map { ToPdf.Item(it, it.nameWithoutExtension) }, out, app.documents.tempDir, bm) { d, t -> step(d, t) }
                            if (attach) com.smartdocs.editor.core.pdf.AttachmentOps.attachSources(out, list, srcDesc)
                            runCatching { app.documents.touchRecent(out) }
                            result = out
                        }
                    }
                } else {
                    SwitchRow(stringResource(R.string.t5_run_background), background, stringResource(R.string.t5_run_background_hint)) { background = it }
                    PrimaryButton(stringResource(R.string.t5_convert_each), enabled = files.isNotEmpty() && unsupported.isEmpty() && !job.running, icon = Icons.Default.PictureAsPdf) {
                        val list = files.toList()
                        if (background) {
                            BatchJobs.enqueue(ctx, converting, list.map { it to null }, null, true, app.documents.docsDir)
                            job.info = queued
                            nav.navigate(com.smartdocs.editor.ui.Routes.tool("batch"))
                        } else { val attach = attachSrc; job.launch(converting) {
                            val (out, failed) = convertEachToPdf(ctx, app.documents, list, { d, t -> step(d, t) }) { f, dst ->
                                if (attach) com.smartdocs.editor.core.pdf.AttachmentOps.attachSources(dst, listOf(f), srcDesc)
                            }
                            results = out
                            if (failed != null) { if (out.isEmpty()) error = failed else info = failed }
                        } }
                    }
                }
            }
            else -> {
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.t5_url)) }, placeholder = { Text("https://…") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                PrimaryButton(stringResource(R.string.t5_web_to_pdf), enabled = url.isNotBlank() && !job.running, icon = Icons.Default.Language) {
                    val u = url.trim()
                    val host = runCatching { android.net.Uri.parse(if (u.contains("://")) u else "https://$u").host }.getOrNull()?.ifBlank { null } ?: "webpage"
                    val setup = web.setup(); val nl = web.noLinks
                    job.launch(converting) {
                        val out = app.documents.uniqueFile(app.documents.docsDir, host)
                        ToPdf.url(ctx, u, out, setup, nl)
                        result = appendIfAsked(web, out) { app.documents.uniqueFile(app.documents.docsDir, it) }
                    }
                }
                WebOptionsCard(web)
                HorizontalDivider()
                OutlinedTextField(html, { html = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text(stringResource(R.string.t5_paste_html)) })
                PrimaryButton(stringResource(R.string.t5_html_to_pdf), enabled = html.isNotBlank() && !job.running, icon = Icons.Default.Code) {
                    val h = html
                    job.launch(converting) {
                        val out = app.documents.uniqueFile(app.documents.docsDir, "html_" + t5Today())
                        val page = if (h.contains("<html", true) || h.contains("<body", true)) h else HtmlBits.page("", h, HtmlBits.rtlDominant(h))
                        result = ToPdf.html(ctx, page, out, web.setup())
                    }
                }
            }
        }
        // fc-settings: "Save as default" for the options of all three tabs
        OutlinedButton(onClick = {
            com.smartdocs.editor.core.convert6.Cv6Defaults.saveScreen(ctx, TO_PDF_DEFAULTS, org.json.JSONObject()
                .put("bookmarks", bookmarks).put("attach", attachSrc).put("background", background)
                .put("paper", web.paper).put("landscape", web.landscape).put("margin", web.margin).put("timeout", web.timeoutSec.toDouble())
                .put("images", web.images).put("backgrounds", web.backgrounds).put("underline", web.underline).put("noLinks", web.noLinks))
            android.widget.Toast.makeText(ctx, savedMsg, android.widget.Toast.LENGTH_SHORT).show()
        }) { Icon(Icons.Default.BookmarkAdd, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.fcs_cv_save_default)) }
        if (job.results.isNotEmpty()) ResultsList(job.results, nav, "application/pdf")
        Text(stringResource(R.string.t5_print_service_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    T5JobUi(job, nav, snackbar)
}

/** fc-settings: [com.smartdocs.editor.core.convert6.Cv6Defaults] screen name of To PDF+. */
const val TO_PDF_DEFAULTS = "to_pdf_plus"

/** Target of "Share to PDF" from other apps (MainActivity puts the payload in [ShareImport.pending]). */
@Composable
fun ShareToPdfScreen(nav: NavHostController) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    rememberEmlLabels()
    // the payload survives opening a result and coming back, and a language switch (convert audit A-M13)
    val payload = rememberSaveable(saver = SharePayloadSaver) { mutableStateOf(ShareImport.pending.also { ShareImport.pending = null }) }.value
    var files by rememberSaveable(stateSaver = FileListSaver) { mutableStateOf(payload?.files.orEmpty()) }
    val job = rememberT5Job()
    val converting = stringResource(R.string.t5_converting)
    val title = payload?.subject?.ifBlank { null } ?: stringResource(R.string.t5_shared_name)

    ToolScaffold(stringResource(R.string.t5_share_title), nav, snackbar = snackbar) {
        if (payload == null || payload.isEmpty) {
            Text(stringResource(R.string.t5_share_empty))
            OutlinedButton(onClick = { nav.navigate(com.smartdocs.editor.ui.Routes.tool("to_pdf_plus")) }) { Text(stringResource(R.string.t5_topdf_title)) }
            return@ToolScaffold
        }
        if (files.isNotEmpty()) {
            T5Card(stringResource(R.string.t5_shared_files, files.size), Icons.Default.Inbox) { FileListEditor(files, { files = it }) }
            PrimaryButton(stringResource(if (files.size > 1) R.string.t5_combine else R.string.t5_convert_one), enabled = !job.running, icon = Icons.Default.PictureAsPdf) {
                val list = files.toList()
                job.launch(converting) {
                    val out = app.documents.uniqueFile(app.documents.docsDir, if (list.size == 1) list[0].nameWithoutExtension else title)
                    try { result = ToPdf.combine(ctx, list.map { ToPdf.Item(it, it.nameWithoutExtension) }, out, app.documents.tempDir, list.size > 1) { d, t -> step(d, t) } }
                    catch (e: Throwable) { out.delete(); throw e }
                }
            }
            if (files.size > 1) OutlinedButton(onClick = {
                val list = files.toList()
                job.launch(converting) {
                    val (out, failed) = convertEachToPdf(ctx, app.documents, list, { d, t -> step(d, t) })
                    results = out
                    if (failed != null) { if (out.isEmpty()) error = failed else info = failed }
                }
            }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.t5_convert_each)) }
        }
        payload.url?.let { u ->
            T5Card(stringResource(R.string.t5_shared_link), Icons.Default.Link) {
                Text(u, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                PrimaryButton(stringResource(R.string.t5_web_to_pdf), enabled = !job.running, icon = Icons.Default.Language) {
                    job.launch(converting) {
                        val host = runCatching { android.net.Uri.parse(u).host }.getOrNull() ?: "webpage"
                        result = ToPdf.url(ctx, u, app.documents.uniqueFile(app.documents.docsDir, host))
                    }
                }
            }
        }
        val text = payload.html ?: payload.text
        if (!text.isNullOrBlank()) {
            T5Card(stringResource(R.string.t5_shared_text), Icons.Default.Notes) {
                Text(text.take(400), style = MaterialTheme.typography.bodySmall, maxLines = 8)
                PrimaryButton(stringResource(R.string.t5_text_to_pdf), enabled = !job.running, icon = Icons.Default.TextSnippet) {
                    val isHtml = payload.html != null
                    job.launch(converting) {
                        // shared HTML is untrusted: sanitised and printed without scripts (convert audit A-M6)
                        val page = if (isHtml) HtmlBits.page(title, HtmlBits.sanitize(text), HtmlBits.rtlDominant(text)) else TextHtml.fromText(text, title).html
                        result = ToPdf.html(ctx, page, app.documents.uniqueFile(app.documents.docsDir, title))
                    }
                }
            }
        }
        if (job.results.isNotEmpty()) ResultsList(job.results, nav, "application/pdf")
        job.info?.takeIf { job.result == null }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    T5JobUi(job, nav, snackbar)
}

/** [SharePayload] as plain values for saved instance state (the copies stay in the cache folder of the share). */
private val SharePayloadSaver = androidx.compose.runtime.saveable.Saver<MutableState<SharePayload?>, List<String?>>(
    save = { s -> s.value?.let { p -> listOf(p.url, p.text, p.html, p.subject) + p.files.map { it.absolutePath } } ?: emptyList() },
    restore = { l -> mutableStateOf(if (l.size < 4) null else SharePayload(l.drop(4).filterNotNull().map(::File).filter { it.exists() }, l[0], l[1], l[2], l[3])) },
)

private val FileListSaver = androidx.compose.runtime.saveable.Saver<List<File>, List<String>>(
    save = { l -> l.map { it.absolutePath } }, restore = { l -> l.map(::File).filter { it.exists() } },
)
