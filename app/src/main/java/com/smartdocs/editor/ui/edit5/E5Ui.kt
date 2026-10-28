package com.smartdocs.editor.ui.edit5

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit5.E5Font
import com.smartdocs.editor.core.edit5.PageBoxOps
import com.smartdocs.editor.core.edit5.PageInfo
import com.smartdocs.editor.core.edit5.PageSubset
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.rememberPdfPicker
import com.smartdocs.editor.ui.tools.ChoiceChips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Working copy of a document for the edit5 tools: every change is applied to a temp copy (undo keeps the previous
 * copies), the original is only replaced when the user saves from the result sheet.
 */
class E5Doc(initial: File?) {
    var source by mutableStateOf(initial); private set
    var work by mutableStateOf<File?>(null); private set
    // audit 5 E-13: the reader's password for the route file, so an open document is not asked for again
    var password by mutableStateOf(com.smartdocs.editor.core.pdf.SessionPasswords.get(initial))
    var needPassword by mutableStateOf(false)
    /** audit 5 E-13: the password prompt was cancelled; nothing opens until [retry]. */
    var cancelled by mutableStateOf(false); private set
    /** The last password typed was wrong (the prompt says so). */
    var wrongPassword by mutableStateOf(false); private set
    var infos by mutableStateOf<List<PageInfo>>(emptyList()); private set
    var version by mutableIntStateOf(0); private set
    var error by mutableStateOf<String?>(null)
    var changed by mutableStateOf(false); private set
    private val undo = ArrayList<File>()
    val canUndo get() = undo.isNotEmpty()
    val pageCount get() = infos.size

    fun pick(f: File) {
        dispose()
        source = f; password = com.smartdocs.editor.core.pdf.SessionPasswords.get(f); error = null; cancelled = false; wrongPassword = false
        infos = emptyList(); changed = false; version++
    }

    /** audit 5 E-13: the prompt was dismissed: no error line, the screen offers [retry]. */
    fun cancelPassword() { needPassword = false; cancelled = true; error = null }

    fun retry() { cancelled = false; error = null; needPassword = false; version++ }

    /** A password was typed in the prompt. */
    fun submitPassword(pw: String) { password = pw; error = null; cancelled = false; needPassword = false }

    /** audit 5 E-18: the working copy and the undo copies are temp files: removed when the screen goes away. */
    fun dispose() {
        runCatching { work?.delete() }; undo.forEach { runCatching { it.delete() } }
        undo.clear(); work = null
    }

    /** Open (or reopen after a password was typed): copy to a temp working file and read page geometry. */
    suspend fun open(tempDir: File) {
        val src = source ?: return
        error = null
        try {
            val w = work ?: File(tempDir, "e5_${System.nanoTime()}.pdf").also { withContext(Dispatchers.IO) { src.copyTo(it, overwrite = true) } }
            work = w
            try { infos = PageBoxOps.pageInfos(w, password) } catch (e: Exception) { work = null; w.delete(); throw e }
            needPassword = false; wrongPassword = false
        } catch (e: Exception) {
            if (com.smartdocs.editor.core.edit5.E5Locks.isPasswordError(e)) { wrongPassword = !password.isNullOrEmpty(); needPassword = true }
            else error = e.message ?: e.toString()
        }
    }

    /** Apply [op] (reads `src`, writes `out`) to the working copy. */
    suspend fun apply(tempDir: File, op: suspend (src: File, out: File, pw: String?) -> Unit) {
        val w = work ?: return
        val out = File(tempDir, "e5_${System.nanoTime()}.pdf")
        op(w, out, password)
        if (!out.exists() || out.length() == 0L) throw IllegalStateException("empty result")
        undo += w
        if (undo.size > 12) undo.removeAt(0).delete()
        work = out
        infos = PageBoxOps.pageInfos(out, password)
        changed = true; version++
    }

    suspend fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        work?.delete(); work = prev
        infos = runCatching { PageBoxOps.pageInfos(prev, password) }.getOrDefault(infos)
        changed = undo.isNotEmpty(); version++
    }

    /** Copy of the working file in the documents folder (shown in the result sheet). */
    suspend fun export(docsDir: File, unique: (File, String) -> File, suffix: String): File = withContext(Dispatchers.IO) {
        val w = work ?: error("no document")
        val out = unique(docsDir, (source?.nameWithoutExtension ?: "document") + "_" + suffix)
        w.copyTo(out, overwrite = true)
        // audit 5 E-1: the result is this tool's document, transformed (the result sheet may offer "Save in original")
        com.smartdocs.editor.core.pdf.ToolOutputs.record(out, source)
    }
}

@Composable
fun rememberE5Doc(uri: String?): E5Doc {
    val doc = remember(uri) { E5Doc(uri?.let { u -> runCatching { File(Uri.parse(u).path!!) }.getOrNull()?.takeIf { it.exists() } }) }
    val app = LocalApp.current
    LaunchedEffect(doc.source, doc.needPassword, doc.password, doc.cancelled, doc.version) {
        if (doc.source != null && doc.work == null && !doc.needPassword && !doc.cancelled && doc.error == null) doc.open(app.documents.tempDir)
    }
    DisposableEffect(doc) { onDispose { doc.dispose() } }
    return doc
}

/** File row + password prompt + page count. */
@Composable
fun E5FileRow(doc: E5Doc) {
    val pick = rememberPdfPicker { it.firstOrNull()?.let { f -> doc.pick(f) } }
    FilePickRow(doc.source, stringResource(R.string.e5_document), pick)
    if (doc.pageCount > 0) Text(stringResource(R.string.e5_pages_n, doc.pageCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    doc.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    // audit 5 E-13: a cancelled prompt leaves no empty error line and can be opened again
    if (doc.cancelled && doc.work == null && doc.source != null) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tte5_password_cancelled), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { doc.retry() }) { Text(stringResource(R.string.tte5_retry)) }
    }
    if (doc.needPassword) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { doc.cancelPassword() },
            title = { Text(stringResource(R.string.password_enter)) },
            text = {
                Column {
                    if (doc.wrongPassword) Text(stringResource(R.string.e5_lock_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(pw, { pw = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = { Button(onClick = { doc.submitPassword(pw) }, enabled = pw.isNotEmpty()) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { doc.cancelPassword() }) { Text(stringResource(R.string.cancel)) } })
    }
}

/**
 * A rendered page of [file] with an overlay. Coordinates passed to callbacks and [overlay] are in display space
 * (points, origin bottom-left). [onRect] enables drawing a rectangle by dragging; [onTap] reports taps.
 */
@Composable
fun E5PageView(
    file: File?, password: String?, page: Int, info: PageInfo?, version: Int,
    modifier: Modifier = Modifier,
    onTap: ((Float, Float) -> Unit)? = null,
    onRect: ((RectF) -> Unit)? = null,
    overlay: DrawScope.(map: (RectF) -> Rect) -> Unit = {},
) {
    val ctx = LocalContext.current
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(file, page, version, password) {
        bmp = null
        val f = file ?: return@LaunchedEffect
        bmp = runCatching {
            val s = PdfSession.open(ctx, f, password)
            try { val (w, h) = s.pageSize(page); s.render(page, 900, (if (w > 0f) 900 * h / w else 1273f).toInt().coerceIn(1, 4500)) } finally { s.closeAsync() }
        }.getOrNull()
    }
    val dw = info?.displayW ?: 595f; val dh = info?.displayH ?: 842f
    val ratio = (dw / dh).let { if (it.isNaN() || dh <= 0f) 0.707f else it }.coerceIn(0.2f, 5f)   // NaN → aspectRatio() throws
    var drag by remember { mutableStateOf<Rect?>(null) }
    fun scale() = if (size.width > 0) size.width / dw else 1f
    fun toDisplay(o: Offset) = (o.x / scale()) to (dh - o.y / scale())
    val map: (RectF) -> Rect = { r ->
        val s = scale()
        Rect(minOf(r.left, r.right) * s, (dh - maxOf(r.top, r.bottom)) * s, maxOf(r.left, r.right) * s, (dh - minOf(r.top, r.bottom)) * s)
    }
    Box(modifier.aspectRatio(ratio).clip(RoundedCornerShape(8.dp)).background(Color.White).onSizeChanged { size = it }
        .pointerInput(onTap, onRect, dw, dh) {
            if (onRect != null) detectDragGestures(
                onDragStart = { o -> drag = Rect(o, o) },
                onDrag = { ch, _ -> drag = drag?.let { Rect(it.topLeft, ch.position) } },
                onDragEnd = {
                    drag?.let { r -> val a = toDisplay(r.topLeft); val b = toDisplay(r.bottomRight); onRect(RectF(minOf(a.first, b.first), maxOf(a.second, b.second), maxOf(a.first, b.first), minOf(a.second, b.second))) }
                    drag = null
                },
                onDragCancel = { drag = null })
        }
        .pointerInput(onTap, dw, dh) { if (onTap != null) detectTapGestures { o -> val (x, y) = toDisplay(o); onTap(x, y) } }) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            ?: CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp))
        Canvas(Modifier.fillMaxSize()) {
            overlay(map)
            drag?.let { r -> val n = Rect(minOf(r.left, r.right), minOf(r.top, r.bottom), maxOf(r.left, r.right), maxOf(r.top, r.bottom))
                drawRect(Color(0x332196F3), n.topLeft, n.size); drawRect(Color(0xFF2196F3), n.topLeft, n.size, style = Stroke(2f)) }
        }
    }
}

fun DrawScope.e5Box(r: Rect, color: Color, fill: Boolean = true) {
    if (fill) drawRect(color.copy(alpha = 0.18f), r.topLeft, Size(r.width, r.height))
    drawRect(color, r.topLeft, Size(r.width, r.height), style = Stroke(3f))
}

@Composable
fun PageStepper(page: Int, count: Int, onPage: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onPage((page - 1).coerceAtLeast(0)) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.pc_cd_prev_page)) }
        Text(stringResource(R.string.e5_page_of, page + 1, count), style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = { onPage((page + 1).coerceAtMost(count - 1)) }, enabled = page < count - 1) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.pc_cd_next_page)) }
    }
}

@Composable
fun RangeSubset(range: String, onRange: (String) -> Unit, subset: PageSubset, onSubset: (PageSubset) -> Unit) {
    OutlinedTextField(range, onRange, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.page_range)) }, placeholder = { Text(stringResource(R.string.e5_all_pages)) })
    ChoiceChips(PageSubset.values().toList(), subset, { stringResource(subsetLabel(it)) }, onSubset)
}

fun subsetLabel(s: PageSubset) = when (s) {
    PageSubset.ALL -> R.string.e5_sub_all; PageSubset.ODD -> R.string.e5_sub_odd; PageSubset.EVEN -> R.string.e5_sub_even
    PageSubset.PORTRAIT -> R.string.e5_sub_portrait; PageSubset.LANDSCAPE -> R.string.e5_sub_landscape
}

@Composable
fun FontChoice(font: E5Font, onFont: (E5Font) -> Unit) {
    val fonts = remember { E5Font.values().filter { it.available() } }
    ChoiceChips(fonts, font, { stringResource(fontLabel(it)) }, onFont)
}

fun fontLabel(f: E5Font) = when (f) {
    E5Font.NASKH -> R.string.e5_font_naskh; E5Font.NASKH_SYSTEM -> R.string.e5_font_naskh_sys; E5Font.KUFI -> R.string.e5_font_kufi
    E5Font.SANS_ARABIC -> R.string.e5_font_sans_ar; E5Font.NASKH_SERIF -> R.string.e5_font_serif; E5Font.MONO -> R.string.e5_font_mono; E5Font.ROBOTO -> R.string.e5_font_roboto
}

/** A titled group of options. */
@Composable
fun E5Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

/** Decimal field (points, factors). */
@Composable
fun FloatField(value: Float, label: String, modifier: Modifier = Modifier, onChange: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()) }
    OutlinedTextField(text, { t -> text = t; com.smartdocs.editor.core.edit5.Digits.toWestern(t).replace(',', '.').toFloatOrNull()?.let(onChange) }, modifier, singleLine = true,
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

/** Two-pane on tablets (options | preview), stacked on phones. */
@Composable
fun E5TwoPane(options: @Composable ColumnScope.() -> Unit, preview: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth > 700.dp) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { options() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { preview() }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { preview(); options() }
    }
}
