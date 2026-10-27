package com.smartdocs.editor.ui.tools5

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val pick = rememberAnyFilePicker(true, ToPdf.pickerMimes) { picked -> files = files + picked.filter { f -> files.none { it.absolutePath == f.absolutePath } } }
    val unsupported = files.filter { ToPdf.kindOf(it.name) == SourceKind.UNKNOWN }
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
                            val out = ArrayList<File>()
                            list.forEachIndexed { i, f ->
                                step(i, list.size)
                                val dst = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension)
                                ToPdf.convert(ctx, f, dst); out += dst
                                if (attach) com.smartdocs.editor.core.pdf.AttachmentOps.attachSources(dst, listOf(f), srcDesc)
                            }
                            results = out
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
        if (job.results.isNotEmpty()) ResultsList(job.results, nav, "application/pdf")
        Text(stringResource(R.string.t5_print_service_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    T5JobUi(job, nav, snackbar)
}

/** Target of "Share to PDF" from other apps (MainActivity puts the payload in [ShareImport.pending]). */
@Composable
fun ShareToPdfScreen(nav: NavHostController) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    rememberEmlLabels()
    val payload = remember { ShareImport.pending.also { ShareImport.pending = null } }
    var files by remember { mutableStateOf(payload?.files.orEmpty()) }
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
                    val out = app.documents.uniqueFile(app.documents.docsDir, if (list.size == 1) list[0].nameWithoutExtension.substringAfter('_').substringAfter('_') else title)
                    result = ToPdf.combine(ctx, list.map { ToPdf.Item(it, it.nameWithoutExtension) }, out, app.documents.tempDir, list.size > 1) { d, t -> step(d, t) }
                }
            }
            if (files.size > 1) OutlinedButton(onClick = {
                val list = files.toList()
                job.launch(converting) {
                    val out = ArrayList<File>()
                    list.forEachIndexed { i, f -> step(i, list.size); val d = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension); ToPdf.convert(ctx, f, d); out += d }
                    results = out
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
                        val page = if (isHtml) HtmlBits.page(title, text, HtmlBits.rtlDominant(text)) else TextHtml.fromText(text, title).html
                        result = ToPdf.html(ctx, page, app.documents.uniqueFile(app.documents.docsDir, title))
                    }
                }
            }
        }
        if (job.results.isNotEmpty()) ResultsList(job.results, nav, "application/pdf")
    }
    T5JobUi(job, nav, snackbar)
}
