package com.smartdocs.editor.ui.ai5

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.ai5.CommentAi
import com.smartdocs.editor.core.ai5.DocTranslator
import com.smartdocs.editor.core.ai5.TranslateMode
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.ResultSheet
import com.smartdocs.editor.ui.common.rememberToolRunner
import java.io.File

@Composable
private fun ToolRow(icon: ImageVector, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Document tools of the hub: translate (keeps layout), PII scan, form fill, comment summary note, podcast, read aloud. */
@Composable
fun AiToolsPanel(nav: NavHostController, file: File?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings = LocalSettings.current
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    val runner = rememberToolRunner()
    var showResult by remember { mutableStateOf(false) }
    var translate by remember { mutableStateOf(false) }
    var pii by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf("en") }
    var mode by remember { mutableStateOf(TranslateMode.OVERLAY) }
    var pages by remember { mutableStateOf("") }
    val processing = stringResource(R.string.a5_working)
    val quota = stringResource(R.string.a5_err_quota)
    val uri = file?.let { Uri.fromFile(it).toString() }
    LaunchedEffect(runner.result) { if (runner.result != null) showResult = true }
    val ok = file != null

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolRow(Icons.Default.GTranslate, stringResource(R.string.a5_translate_doc), stringResource(R.string.a5_translate_doc_sub), ok) { translate = true }
        ToolRow(Icons.Default.PrivacyTip, stringResource(R.string.a5_pii_title), stringResource(R.string.a5_pii_sub), ok) { pii = true }
        ToolRow(Icons.Default.EditNote, stringResource(R.string.a5_form_fill), stringResource(R.string.a5_form_fill_sub), ok) { form = true }
        ToolRow(Icons.Default.Comment, stringResource(R.string.a5_comments_summary), stringResource(R.string.a5_comments_summary_sub), ok) {
            val f = file ?: return@ToolRow
            runner.run(processing) {
                try {
                    val s = CommentAi.summarize(ctx, client, f, null)
                    val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_comments")
                    CommentAi.addAsNote(ctx, f, null, s, ctx.getString(R.string.a5_ai_author), out)
                } catch (e: Exception) { throw if (AiCalls.isQuota(e)) IllegalStateException(quota) else e }
            }
        }
        ToolRow(Icons.Default.Toc, stringResource(R.string.a5_ai_bookmarks), stringResource(R.string.a5_ai_bookmarks_sub), ok) {
            val f = file ?: return@ToolRow
            runner.run(processing) {
                val idx = com.smartdocs.editor.core.ai5.DocIndexer.get(ctx, f)
                // headings are spread over the whole book: give the model the first line of every chunk + an even spread
                val heads = idx.chunks.joinToString("\n") { "[صفحة ${it.page + 1}] " + it.text.lineSequence().first().take(120) }.take(9000)
                val items = try { client.outline(heads + "\n\n" + com.smartdocs.editor.core.ai5.DocIndex.material(idx.spread(4000))) }
                    catch (e: Exception) { throw if (AiCalls.isQuota(e)) IllegalStateException(quota) else e }
                if (items.isEmpty()) throw IllegalStateException(ctx.getString(R.string.a5_err_parse))
                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_bookmarks")
                com.smartdocs.editor.core.pdf.PdfOps.setOutline(f, items.map { Triple(it.title, (it.page - 1).coerceIn(0, (idx.pageCount - 1).coerceAtLeast(0)), it.level) }, out)
                out
            }
        }
        ToolRow(Icons.Default.Headphones, stringResource(R.string.a5_podcast), stringResource(R.string.a5_podcast_sub), true) { nav.navigate(Routes.tool("podcast", uri)) }
        ToolRow(Icons.Default.RecordVoiceOver, stringResource(R.string.a5_read_aloud), stringResource(R.string.a5_read_aloud_sub), true) { nav.navigate(Routes.tool("read_aloud", uri)) }
    }

    if (translate && file != null) AlertDialog(onDismissRequest = { translate = false },
        title = { Text(stringResource(R.string.a5_translate_doc)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.a5_target_language)); LanguagePicker(lang, { lang = it }, allowAuto = false)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(mode == TranslateMode.OVERLAY, { mode = TranslateMode.OVERLAY }, label = { Text(stringResource(R.string.a5_tr_layout)) })
                    FilterChip(mode == TranslateMode.TEXT_ONLY, { mode = TranslateMode.TEXT_ONLY }, label = { Text(stringResource(R.string.a5_tr_text)) })
                }
                OutlinedTextField(pages, { pages = it }, label = { Text(stringResource(R.string.a5_pages_optional)) }, singleLine = true)
                Text(stringResource(R.string.a5_tr_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                translate = false
                val f = file
                runner.run(processing) {
                    val range = pages.takeIf { it.isNotBlank() }?.let { parsePages(it) }
                    val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_" + lang)
                    try { DocTranslator.translate(ctx, client, f, null, lang, mode, out, range) { a, b -> runner.message = "$processing ${a}/${b}" } }
                    catch (e: Exception) { throw if (AiCalls.isQuota(e)) IllegalStateException(quota) else e }
                }
            }) { Text(stringResource(R.string.a5_start)) }
        },
        dismissButton = { TextButton(onClick = { translate = false }) { Text(stringResource(R.string.a5_cancel)) } })

    if (pii && file != null) PiiScanSheet(file, null, onApply = { pii = false; nav.navigate(Routes.tool("redact", uri)) }, onDismiss = { pii = false })
    if (form && file != null) FormFillSheet(file, null, onFile = { runner.result = it }, onDismiss = { form = false })
    if (runner.running) ProgressDialog(runner.message)
    runner.error?.let { e -> AlertDialog(onDismissRequest = { runner.error = null }, text = { Text(e) }, confirmButton = { TextButton(onClick = { runner.error = null }) { Text(stringResource(R.string.a5_close)) } }) }
    if (showResult) runner.result?.let { ResultSheet(it, nav, onDismiss = { showResult = false; runner.result = null }) }
}

/** "1-3, 7" → 0-based page list (1-based input). */
fun parsePages(s: String): List<Int> = s.split(',', '،').flatMap { part ->
    val p = com.smartdocs.editor.core.ai5.AiNorm.digits(part).trim()
    val m = Regex("(\\d+)\\s*-\\s*(\\d+)").find(p)
    if (m != null) (m.groupValues[1].toInt()..m.groupValues[2].toInt()).map { it - 1 } else listOfNotNull(p.toIntOrNull()?.minus(1))
}.filter { it >= 0 }.distinct().sorted()
