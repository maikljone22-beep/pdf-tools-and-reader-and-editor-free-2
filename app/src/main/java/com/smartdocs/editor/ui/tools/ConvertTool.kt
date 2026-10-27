package com.smartdocs.editor.ui.tools

import android.print.WebPdfWriter
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.TextToPdf
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.rememberToolRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class ToPdfSource { TEXT, WEB }

/**
 * Tool "convert": text / web page → PDF, and PDF → Word / Excel / PowerPoint / HTML / TXT / images
 * (on-device, see core/convert and ConvertFromPdf.kt).
 */
@Composable
fun ConvertScreen(nav: NavHostController, uri: String?) {
    var tab by remember { mutableIntStateOf(if (uri != null) 1 else 0) }
    val runner = rememberToolRunner()
    val snackbar = remember { SnackbarHostState() }
    ToolScaffold(stringResource(R.string.tool_convert), nav, runner, snackbar = snackbar) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.tl_to_pdf)) })
            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.tl_from_pdf)) })
        }
        if (tab == 0) ToPdfPanel(runner) else ConvertFromPdfPanel(nav, uri, snackbar)
    }
}

@Composable
private fun ToPdfPanel(runner: com.smartdocs.editor.ui.common.ToolRunner) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    var source by remember { mutableStateOf(ToPdfSource.TEXT) }
    var txt by remember { mutableStateOf<File?>(null) }
    var url by remember { mutableStateOf("") }
    var fontSize by remember { mutableFloatStateOf(12f) }
    var attachSrc by remember { mutableStateOf(false) }   // nav-attach: "attach source file"
    val srcDesc = stringResource(R.string.nat_source_desc)
    val pickTxt = rememberTextFilePicker { txt = it }
    val processing = stringResource(R.string.processing)
    val loadingWeb = stringResource(R.string.tl_web_loading)

    FieldLabel(stringResource(R.string.tl_source))
    ChoiceChips(ToPdfSource.entries, source, { if (it == ToPdfSource.TEXT) stringResource(R.string.tl_from_text_file) else stringResource(R.string.tl_from_web) }) { source = it }
    when (source) {
        ToPdfSource.TEXT -> {
            FilePickRow(txt, stringResource(R.string.tl_pick_txt), pickTxt)
            com.smartdocs.editor.ui.common.LabeledSlider(stringResource(R.string.prop_font_size), fontSize, 9f..20f, { fontSize = it })
            Text(stringResource(R.string.tl_text_to_pdf_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            com.smartdocs.editor.ui.tools5.SwitchRow(stringResource(R.string.nat_attach_source), attachSrc, stringResource(R.string.nat_attach_source_sub)) { attachSrc = it }
            PrimaryButton(stringResource(R.string.tl_convert), enabled = txt != null, icon = Icons.Default.PictureAsPdf) {
                val src = txt ?: return@PrimaryButton
                val fs = fontSize; val attach = attachSrc
                runner.run(processing) {
                    val out = app.documents.uniqueFile(app.documents.docsDir, src.nameWithoutExtension)
                    TextToPdf.convert(ctx, src.readText(), out, fs, title = src.nameWithoutExtension)
                    if (attach) com.smartdocs.editor.core.pdf.AttachmentOps.attachSources(out, listOf(src), srcDesc)
                    out
                }
            }
        }
        ToPdfSource.WEB -> {
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.tl_url)) }, placeholder = { Text("https://…") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Text(stringResource(R.string.tl_web_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PrimaryButton(stringResource(R.string.tl_convert), enabled = url.isNotBlank(), icon = Icons.Default.Language) {
                var u = url.trim(); if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                val base = runCatching { android.net.Uri.parse(u).host }.getOrNull()?.ifBlank { null } ?: "webpage"
                runner.run(loadingWeb) {
                    val out = app.documents.uniqueFile(app.documents.docsDir, base)
                    withContext(Dispatchers.Main) { WebPdfWriter.convertUrl(ctx, u, out) }
                    if (!out.exists() || out.length() == 0L) throw IllegalStateException("empty")
                    out
                }
            }
        }
    }
}
