package com.smartdocs.editor.ui.reader

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.DocMeta

@Composable
fun PasswordDialog(wrong: Boolean, onOk: (String) -> Unit, onCancel: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text(stringResource(R.string.password)) },
        text = {
            Column {
                Text(stringResource(R.string.password_enter))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pw, { pw = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = wrong,
                    supportingText = if (wrong) ({ Text(stringResource(R.string.password_wrong)) }) else null, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onOk(pw) }, enabled = pw.isNotEmpty()) { Text(stringResource(R.string.open)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun DiscardDialog(onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Default.Save, null) },
        title = { Text(stringResource(R.string.rd_unsaved_title)) },
        text = { Text(stringResource(R.string.rd_unsaved_text)) },
        confirmButton = { Button(onClick = onSave) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row { TextButton(onClick = onDiscard) { Text(stringResource(R.string.rd_discard)) }; TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } }
        },
    )
}

@Composable
fun GoToPageDialog(current: Int, count: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableStateOf((current + 1).toString()) }
    val n = v.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_page)) },
        text = {
            OutlinedTextField(v, { v = it.filter { c -> c.isDigit() }.take(6) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.page_of, current + 1, count)) }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { Button(onClick = { n?.let { onGo(it - 1) } }, enabled = n != null && n in 1..count) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Text entry for note / free text / link url prompts raised by the page canvas. */
@Composable
fun TextPromptDialog(state: ReaderState, prompt: TextPrompt, onDismiss: () -> Unit) {
    var text by remember(prompt) { mutableStateOf("") }
    val isLink = prompt.tool == ReaderTool.LINK
    val title = when (prompt.tool) {
        ReaderTool.NOTE -> R.string.annot_note; ReaderTool.LINK -> R.string.rd_link_url
        ReaderTool.FS_TEXT -> R.string.fill_sign_text; else -> R.string.annot_text
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            // NVE: live spell check (underlines) + spell-check button; Arabic-Indic digits option applied on OK
            val nveIssues = if (isLink) emptyList() else com.smartdocs.editor.ui.nve.rememberSpellIssues(text)
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = if (isLink) 1 else 3, singleLine = isLink,
                placeholder = { Text(if (isLink) "https://" else "") },
                visualTransformation = com.smartdocs.editor.ui.nve.spellTransformation(nveIssues),
                trailingIcon = if (isLink) null else ({ com.smartdocs.editor.ui.nve.SpellCheckButton(text, { text = it }) }),
                keyboardOptions = KeyboardOptions(keyboardType = if (isLink) KeyboardType.Uri else KeyboardType.Text))
        },
        confirmButton = {
            Button(enabled = text.isNotBlank(), onClick = {
                if (!isLink) text = com.smartdocs.editor.ui.nve.NveDigits.apply(state.context, text)   // NVE: digit shapes setting
                val rtl = text.any { it in '؀'..'ۿ' }
                val pageW = state.pageSizes[prompt.page]?.first ?: 595f
                when (prompt.tool) {
                    ReaderTool.NOTE -> state.addAnnot(state.newAnnot(AnnotKind.NOTE, prompt.page, rects = listOf(RectF(prompt.x, prompt.y, prompt.x + 20f, prompt.y - 20f)), text = text))
                    ReaderTool.LINK -> prompt.rect?.let { r ->
                        val url = if (text.contains("://")) text.trim() else "https://" + text.trim()
                        state.addAnnot(state.newAnnot(AnnotKind.NOTE, prompt.page, rects = listOf(r), text = url, color = 0x2196F3))
                    }
                    else -> state.addAnnot(state.newAnnot(AnnotKind.FREETEXT, prompt.page, rects = listOf(textRectAt(prompt.x, prompt.y, text, state.fontSize, pageW, rtl)), text = text))
                }
                onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The "View settings" sheet (see ui/reader/view/ViewSheet.kt). [tablet] is kept for the existing call site. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ViewModeSheet(state: ReaderState, tablet: Boolean, onDismiss: () -> Unit) {
    com.smartdocs.editor.ui.reader.view.ViewSettingsSheet(state, onDismiss)
}

/** Page colour: the reference's 8 presets (each with its matching text colour) plus the night switch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageColorSheet(state: ReaderState, onPersist: (Long) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.page_color), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            com.smartdocs.editor.ui.reader.view.PresetRow(state) { c -> state.nightMode = false; state.pageColor = c; onPersist(c) }
            Spacer(Modifier.height(12.dp))
            ListItem(headlineContent = { Text(stringResource(R.string.night_mode)) }, trailingContent = { Switch(state.nightMode, { state.nightMode = it }) })
        }
    }
}

/** Auto flip: 20–100 s in 10 s steps (reference range), remembered between sessions. */
@Composable
fun AutoFlipDialog(state: ReaderState, onDismiss: () -> Unit) {
    val vx = state.vx
    var sec by remember { mutableFloatStateOf((if (state.autoFlipSeconds > 0) state.autoFlipSeconds else vx.autoFlipInterval).toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Timer, null) },
        title = { Text(stringResource(R.string.auto_flip)) },
        text = {
            Column {
                Text(stringResource(R.string.vw_flip_every, sec.toInt()))
                Slider(sec, { sec = com.smartdocs.editor.ui.reader.view.ViewExtras.snapFlip(it.toInt()).toFloat() },
                    valueRange = com.smartdocs.editor.ui.reader.view.ViewExtras.FLIP_MIN.toFloat()..com.smartdocs.editor.ui.reader.view.ViewExtras.FLIP_MAX.toFloat(), steps = 7)
            }
        },
        confirmButton = { Button(onClick = { vx.updateAutoFlipInterval(sec.toInt()); state.autoFlipSeconds = vx.autoFlipInterval; onDismiss() }) { Text(stringResource(R.string.rd_start)) } },
        dismissButton = {
            Row {
                if (state.autoFlipSeconds > 0) TextButton(onClick = { state.autoFlipSeconds = 0; onDismiss() }) { Text(stringResource(R.string.tts_stop)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
fun DocInfoContent(state: ReaderState) {
    val meta by produceState<DocMeta?>(null, state.session) { value = runCatching { state.session?.meta() }.getOrNull() }
    val f = state.file
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Text(stringResource(R.string.tool_doc_info), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        InfoRow(stringResource(R.string.file_name), f?.name ?: "")
        InfoRow(stringResource(R.string.file_size), f?.let { DocumentRepository.formatSize(it.length()) } ?: "")
        InfoRow(stringResource(R.string.file_pages), state.pageCount.toString())
        InfoRow(stringResource(R.string.file_modified), f?.let { DocumentRepository.formatDate(it.lastModified()) } ?: "")
        InfoRow(stringResource(R.string.file_path), f?.absolutePath ?: "")
        meta?.let { m ->
            InfoRow(stringResource(R.string.info_title), m.title); InfoRow(stringResource(R.string.info_author), m.author)
            InfoRow(stringResource(R.string.info_subject), m.subject); InfoRow(stringResource(R.string.info_keywords), m.keywords)
            InfoRow(stringResource(R.string.info_creator), m.creator); InfoRow(stringResource(R.string.info_producer), m.producer)
            InfoRow(stringResource(R.string.info_created), m.creationDate); InfoRow(stringResource(R.string.info_modified), m.modDate)
        }
        InfoRow(stringResource(R.string.info_encrypted), stringResource(if (state.password != null) R.string.yes else R.string.no))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    if (value.isBlank()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Reflow: the page text in a scrollable column. Font scale ×1.25 / ×0.8 within 1–8 (reference values),
 * colours follow the page-colour preset (background + matching text colour) or night mode.
 */
@Composable
fun ReflowView(state: ReaderState, modifier: Modifier = Modifier) {
    val page = state.currentPage
    val vx = state.vx
    val text by produceState("", page, state.session) { value = state.pageText(page) }
    val rtl = text.any { it in '\u0600'..'\u06FF' }
    val fontSp = com.smartdocs.editor.ui.reader.view.ViewExtras.REFLOW_BASE_SP * vx.reflowScale
    val bg = if (state.nightMode) Color(0xFF121212) else Color(state.pageColor or 0xFF000000L)
    val fg = if (state.nightMode) Color(0xFFE0E0E0) else Color(com.smartdocs.editor.ui.reader.view.pageTextColor(state.pageColor) or 0xFF000000L)
    Column(modifier.fillMaxSize().background(bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vx.reflowSmaller() }, enabled = vx.reflowScale > com.smartdocs.editor.ui.reader.view.ViewExtras.REFLOW_MIN + 0.001f) {
                Icon(Icons.Default.TextDecrease, stringResource(R.string.vw_font_smaller))
            }
            Text("×%.2f".format(java.util.Locale.US, vx.reflowScale), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { vx.reflowLarger() }, enabled = vx.reflowScale < com.smartdocs.editor.ui.reader.view.ViewExtras.REFLOW_MAX - 0.001f) {
                Icon(Icons.Default.TextIncrease, stringResource(R.string.vw_font_larger))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { if (page > 0) state.jumpTo(page - 1) }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.previous)) }
            Text(stringResource(R.string.page_of, page + 1, state.pageCount), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { if (page < state.pageCount - 1) state.jumpTo(page + 1) }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.next)) }
            IconButton(onClick = { state.reflow = false }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
        }
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                text.ifBlank { stringResource(R.string.ocr_no_text) },
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                fontSize = fontSp.sp, lineHeight = (fontSp * 1.6f).sp,
                textAlign = if (rtl) TextAlign.Right else TextAlign.Left,
                color = fg,
            )
        }
    }
}
