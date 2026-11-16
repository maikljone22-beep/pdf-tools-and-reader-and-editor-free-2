package com.smartdocs.editor.ui.edit5

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.*
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.ColorChips
import java.io.File

private enum class BulkPageScope { ALL, CURRENT, RANGE }

@Composable
fun BulkLinksSheet(file: File, password: String?, infos: List<PageInfo>, current: Int, initial: BulkLinkSpec,
                   onDismiss: () -> Unit, onApply: (BulkLinkSpec) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var spec by remember { mutableStateOf(initial) }
    var scope by remember { mutableStateOf(if (initial.allPages) BulkPageScope.ALL else BulkPageScope.RANGE) }
    var range by remember { mutableStateOf(initial.pages.sorted().joinToString(",") { (it + 1).toString() }) }
    var previewPage by remember { mutableIntStateOf(current.coerceIn(0, (infos.size - 1).coerceAtLeast(0))) }
    var links by remember { mutableStateOf<List<PageLinkInfo>>(emptyList()) }
    var previewMatches by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf(false) }
    LaunchedEffect(file, previewPage, password, spec.linkContains, spec.textContains) {
        previewMatches = false; previewError = false
        try {
            links = LinkOps.list(file, password, previewPage)
            val linkMatches = spec.linkContains.isBlank() || links.any { it.spec.uri.contains(spec.linkContains, true) || it.spec.contents.contains(spec.linkContains, true) }
            val textMatches = if (spec.textContains.isBlank()) true else {
                val session = com.smartdocs.editor.core.pdf.PdfSession.open(context, file, password)
                try { session.text(previewPage).contains(spec.textContains, true) } finally { session.closeAsync() }
            }
            previewMatches = linkMatches && textMatches
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { previewError = true }
    }
    val pages = when (scope) {
        BulkPageScope.ALL -> infos.indices.toSet()
        BulkPageScope.CURRENT -> setOf(previewPage)
        BulkPageScope.RANGE -> if (range.isNotBlank() && PdfOps.rangeSyntaxOk(range)) PdfOps.parseRange(range, infos.size).toSet() else emptySet()
    }
    val valid = pages.isNotEmpty() && when (spec.action) {
        BulkLinkAction.REPLACE_TARGET -> spec.target.isNotBlank()
        BulkLinkAction.REPLACE_WITH_TEXT -> spec.text.isNotBlank()
        BulkLinkAction.ADD -> spec.text.isNotBlank() || spec.target.isNotBlank()
    }
    CompositionLocalProvider(com.smartdocs.editor.ui.common.LocalBatchAction provides null) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { AppTopBar(stringResource(R.string.bulk_links_title), onBack = onDismiss) },
                bottomBar = { Button(onClick = { onApply(spec.copy(pages = pages, allPages = scope == BulkPageScope.ALL)) }, enabled = valid,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) { Text(stringResource(R.string.e5_run)) } }) { pad ->
                Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceChips(BulkLinkAction.entries, spec.action, { stringResource(when (it) {
                        BulkLinkAction.REPLACE_TARGET -> R.string.bulk_links_replace_target
                        BulkLinkAction.REPLACE_WITH_TEXT -> R.string.bulk_links_replace_text
                        BulkLinkAction.ADD -> R.string.bulk_links_add
                    }) }) { spec = spec.copy(action = it) }
                    Text(stringResource(R.string.bulk_links_target_hint), style = MaterialTheme.typography.bodySmall)
                    ChoiceChips(BulkPageScope.entries, scope, { stringResource(when (it) {
                        BulkPageScope.ALL -> R.string.nbm_all_pages; BulkPageScope.CURRENT -> R.string.nbm_current_page; BulkPageScope.RANGE -> R.string.nbm_page_range
                    }) }) { scope = it }
                    if (scope == BulkPageScope.RANGE) OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.nbm_page_range)) }, placeholder = { Text("1-3, 5") }, isError = pages.isEmpty(), singleLine = true)
                    OutlinedTextField(spec.linkContains, { spec = spec.copy(linkContains = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.bulk_links_filter_url)) }, singleLine = true)
                    OutlinedTextField(spec.textContains, { spec = spec.copy(textContains = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.bulk_links_filter_text)) }, singleLine = true)
                    if (spec.action != BulkLinkAction.REPLACE_WITH_TEXT) OutlinedTextField(spec.target, { spec = spec.copy(target = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.bulk_links_new_url)) }, singleLine = true)
                    if (spec.action != BulkLinkAction.REPLACE_TARGET) {
                        OutlinedTextField(spec.text, { spec = spec.copy(text = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.bulk_links_new_text)) }, singleLine = true)
                        FontChoice(spec.style.font) { spec = spec.copy(style = spec.style.copy(font = it)) }
                        Text(stringResource(R.string.bulk_links_size, spec.style.size.toInt()))
                        Slider(spec.style.size, { spec = spec.copy(style = spec.style.copy(size = it)) }, valueRange = 6f..96f)
                        ColorChips(listOf(0x000000, 0xFFFFFF, 0x1565C0, 0xC62828, 0x2E7D32, 0xEF6C00, 0x6A1B9A), spec.style.color) { spec = spec.copy(style = spec.style.copy(color = it)) }
                        Text(stringResource(R.string.bulk_links_opacity, (spec.opacity * 100).toInt()))
                        Slider(spec.opacity, { spec = spec.copy(opacity = it) }, valueRange = 0f..1f)
                        Row {
                            FilterChip(spec.style.bold, { spec = spec.copy(style = spec.style.copy(bold = !spec.style.bold)) }, label = { Text(stringResource(R.string.bulk_links_bold)) })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(spec.style.italic, { spec = spec.copy(style = spec.style.copy(italic = !spec.style.italic)) }, label = { Text(stringResource(R.string.bulk_links_italic)) })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(spec.style.underline, { spec = spec.copy(style = spec.style.copy(underline = !spec.style.underline)) }, label = { Text(stringResource(R.string.bulk_links_underline)) })
                        }
                        if (spec.action == BulkLinkAction.ADD) {
                            Row {
                                TextButton(onClick = { spec = spec.copy(x = 0.5f, y = 0.95f) }) { Text(stringResource(R.string.bulk_links_top)) }
                                TextButton(onClick = { spec = spec.copy(x = 0.5f, y = 0.05f) }) { Text(stringResource(R.string.bulk_links_bottom)) }
                            }
                            Text(stringResource(R.string.bulk_links_drag), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (previewError) Text(stringResource(R.string.e5_preview_failed), color = MaterialTheme.colorScheme.error)
                    PageStepper(previewPage, infos.size) { previewPage = it }
                    val info = infos.getOrNull(previewPage)
                    if (info != null) E5PageView(file, password, previewPage, info, 0, Modifier.fillMaxWidth(),
                        onTap = if (spec.action == BulkLinkAction.ADD) { x, y -> spec = spec.copy(x = (x / info.displayW).coerceIn(0f, 1f), y = (y / info.displayH).coerceIn(0f, 1f)) } else null,
                        onRect = if (spec.action == BulkLinkAction.ADD) { r -> spec = spec.copy(x = ((r.left + r.right) / 2 / info.displayW).coerceIn(0f, 1f), y = ((r.top + r.bottom) / 2 / info.displayH).coerceIn(0f, 1f)) } else null) { map ->
                        if (spec.action != BulkLinkAction.REPLACE_TARGET && previewPage in pages && previewMatches) {
                            val positions = if (spec.action == BulkLinkAction.ADD) listOf(RectF(info.displayW * spec.x, info.displayH * spec.y, info.displayW * spec.x, info.displayH * spec.y))
                            else links.map { info.rectToDisplay(it.spec.rect) }
                            for (position in positions) {
                                val point = map(position)
                                if (spec.action == BulkLinkAction.REPLACE_WITH_TEXT) drawRect(Color.White, point.topLeft, point.size)
                                drawIntoCanvas { canvas ->
                                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        color = spec.style.color or 0xFF000000.toInt(); alpha = (spec.opacity * 255).toInt()
                                        textSize = spec.style.size * size.width / info.displayW
                                        typeface = Typeface.create(Typeface.DEFAULT, (if (spec.style.bold) Typeface.BOLD else 0) or (if (spec.style.italic) Typeface.ITALIC else 0))
                                        isUnderlineText = spec.style.underline
                                        textAlign = if (spec.action == BulkLinkAction.ADD) Paint.Align.CENTER else Paint.Align.LEFT
                                    }
                                    canvas.nativeCanvas.drawText(spec.text.ifBlank { spec.target }, point.left, point.bottom, paint)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
