package com.smartdocs.editor.ui.forms

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.FieldType
import com.smartdocs.editor.core.pdf.FormBuilder
import com.smartdocs.editor.core.pdf.NewField
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val fieldTypes = listOf(
    FieldType.TEXT to R.string.fb_type_text, FieldType.CHECKBOX to R.string.fb_type_checkbox, FieldType.RADIO to R.string.fb_type_radio,
    FieldType.COMBO to R.string.fb_type_combo, FieldType.LIST to R.string.fb_type_list, FieldType.SIGNATURE to R.string.fb_type_signature, FieldType.BUTTON to R.string.fb_type_button,
)

/** Draw fields on a page preview, set their properties, and write them into the PDF as real AcroForm fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormBuilderScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val runner = rememberToolRunner()
    var file by remember(uri) { mutableStateOf(uri?.let { runCatching { File(android.net.Uri.parse(it).path!!) }.getOrNull()?.takeIf { f -> f.exists() } }) }
    val pick = rememberPdfPicker { f -> file = f.firstOrNull() }
    var session by remember { mutableStateOf<PdfSession?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var type by remember { mutableStateOf(FieldType.TEXT) }
    val fields = remember { mutableStateListOf<NewField>() }
    var editing by remember { mutableStateOf<NewField?>(null) }
    var pendingRect by remember { mutableStateOf<RectF?>(null) }

    LaunchedEffect(file) {
        session?.close(); session = null
        file?.let { f -> session = runCatching { PdfSession.open(ctx, f) }.getOrNull(); page = 0; fields.clear() }
    }
    DisposableEffect(Unit) { onDispose { session?.close() } }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.fb_title), onBack = { nav.popBackStack() }, actions = {
        if (fields.isNotEmpty()) TextButton(onClick = {
            val f = file ?: return@TextButton
            runner.run(ctx.getString(R.string.processing)) {
                val out = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_form")
                FormBuilder.addFields(f, fields.toList(), out); out
            }
        }) { Text(stringResource(R.string.save)) }
    }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (file == null) {
                Column(Modifier.padding(16.dp)) { FilePickRow(null, stringResource(R.string.open_file), pick) }
                EmptyState(Icons.Default.Checklist, stringResource(R.string.fb_hint))
                return@Column
            }
            val s = session
            if (s == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fieldTypes.forEach { (t, label) -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(stringResource(label)) }) }
            }
            Text(stringResource(R.string.fb_draw_hint), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
                Text(stringResource(R.string.page_of, page + 1, s.pageCount), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { page = (page + 1).coerceAtMost(s.pageCount - 1) }, enabled = page < s.pageCount - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null) }
                Text("${fields.size}", style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Default.Checklist, null, Modifier.padding(start = 4.dp))
            }
            PageDrawSurface(s, page, fields.filter { it.page == page }, Modifier.weight(1f)) { r -> pendingRect = r }
            if (fields.any { it.page == page }) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fields.filter { it.page == page }.forEach { f ->
                        InputChip(selected = false, onClick = { editing = f }, label = { Text(f.name) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp).let { m -> m }) }, leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) })
                    }
                }
            }
        }
    }

    // property dialog for a new or existing field
    val target = pendingRect?.let { r -> NewField(type, defaultName(type, fields.size + 1), page, r) } ?: editing
    if (target != null) {
        FieldDialog(target, isNew = pendingRect != null,
            onDismiss = { pendingRect = null; editing = null },
            onDelete = { fields.remove(target); pendingRect = null; editing = null },
            onSave = { nf -> if (pendingRect != null) fields += nf else { val i = fields.indexOf(target); if (i >= 0) fields[i] = nf }; pendingRect = null; editing = null })
    }
    if (runner.running) ProgressDialog(runner.message)
    runner.result?.let { f -> ResultSheet(f, nav, onDismiss = { runner.result = null }) }
    runner.error?.let { e -> AlertDialog(onDismissRequest = { runner.error = null }, text = { Text(e) }, confirmButton = { TextButton(onClick = { runner.error = null }) { Text(stringResource(R.string.ok)) } }) }
}

private fun defaultName(t: FieldType, n: Int) = when (t) {
    FieldType.TEXT -> "text_$n"; FieldType.CHECKBOX -> "check_$n"; FieldType.RADIO -> "radio_$n"; FieldType.COMBO -> "combo_$n"
    FieldType.LIST -> "list_$n"; FieldType.SIGNATURE -> "signature_$n"; else -> "button_$n"
}

@Composable
private fun FieldDialog(initial: NewField, isNew: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (NewField) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var tooltip by remember { mutableStateOf(initial.tooltip) }
    var def by remember { mutableStateOf(initial.defaultValue) }
    var options by remember { mutableStateOf(initial.options.joinToString("\n")) }
    var required by remember { mutableStateOf(initial.required) }
    var multiline by remember { mutableStateOf(initial.multiline) }
    var maxLen by remember { mutableStateOf(if (initial.maxLength > 0) initial.maxLength.toString() else "") }
    val hasOptions = initial.type in setOf(FieldType.RADIO, FieldType.COMBO, FieldType.LIST)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(fieldTypes.first { it.first == initial.type }.second)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.fb_field_name)) }, singleLine = true)
                OutlinedTextField(tooltip, { tooltip = it }, label = { Text(stringResource(R.string.fb_tooltip)) }, singleLine = true)
                if (initial.type == FieldType.TEXT || initial.type == FieldType.BUTTON || hasOptions)
                    OutlinedTextField(def, { def = it }, label = { Text(stringResource(if (initial.type == FieldType.BUTTON) R.string.fb_caption else R.string.fb_default)) }, singleLine = true)
                if (hasOptions) OutlinedTextField(options, { options = it }, label = { Text(stringResource(R.string.fb_options)) }, minLines = 3)
                if (initial.type == FieldType.TEXT) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(multiline, { multiline = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.fb_multiline)) }
                    OutlinedTextField(maxLen, { maxLen = it.filter { c -> c.isDigit() }.take(4) }, label = { Text(stringResource(R.string.fb_max_len)) }, singleLine = true)
                }
                if (initial.type != FieldType.BUTTON && initial.type != FieldType.SIGNATURE)
                    Row(verticalAlignment = Alignment.CenterVertically) { Switch(required, { required = it }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.fb_required)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(name = name.trim().ifBlank { initial.name }, tooltip = tooltip.trim(), defaultValue = def.trim(), options = options.split('\n').map { it.trim() }.filter { it.isNotEmpty() }, required = required, multiline = multiline, maxLength = maxLen.toIntOrNull() ?: 0))
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun PageDrawSurface(session: PdfSession, page: Int, existing: List<NewField>, modifier: Modifier, onRect: (RectF) -> Unit) {
    var size by remember(page) { mutableStateOf(595f to 842f) }
    LaunchedEffect(page) { size = session.pageSize(page) }
    BoxWithConstraints(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
        val (pw, ph) = size
        val heightPx = (widthPx * ph / pw).toInt().coerceAtLeast(1)
        val scale = widthPx / pw
        val bmp by produceState<Bitmap?>(null, page, widthPx, size) { value = withContext(Dispatchers.IO) { runCatching { session.render(page, widthPx, heightPx) }.getOrNull() } }
        var a by remember { mutableStateOf<Offset?>(null) }
        var b by remember { mutableStateOf<Offset?>(null) }
        Box(Modifier.fillMaxWidth().aspectRatio(pw / ph).background(Color.White).pointerInput(page, scale) {
            detectDragGestures(
                onDragStart = { a = it; b = it },
                onDrag = { ch, _ -> ch.consume(); b = ch.position },
                onDragEnd = {
                    val p = a; val q = b
                    if (p != null && q != null) {
                        val l = minOf(p.x, q.x) / scale; val r = maxOf(p.x, q.x) / scale
                        val top = ph - minOf(p.y, q.y) / scale; val bottom = ph - maxOf(p.y, q.y) / scale
                        if (r - l > 6f && top - bottom > 6f) onRect(RectF(l.coerceIn(0f, pw), top.coerceIn(0f, ph), r.coerceIn(0f, pw), bottom.coerceIn(0f, ph)))
                    }
                    a = null; b = null
                },
                onDragCancel = { a = null; b = null },
            )
        }) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) } ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
            Canvas(Modifier.fillMaxSize()) {
                val s = this.size.width / pw
                existing.forEach { f ->
                    val r = f.rect
                    val tl = Offset(r.left * s, (ph - r.top) * s); val sz = Size((r.right - r.left) * s, (r.top - r.bottom) * s)
                    drawRect(Color(0xFF1565C0).copy(alpha = 0.18f), tl, sz); drawRect(Color(0xFF1565C0), tl, sz, style = Stroke(width = 3f))
                }
                val p = a; val q = b
                if (p != null && q != null) {
                    val tl = Offset(minOf(p.x, q.x), minOf(p.y, q.y)); val sz = Size(kotlin.math.abs(p.x - q.x), kotlin.math.abs(p.y - q.y))
                    drawRect(Color(0xFF00897B).copy(alpha = 0.25f), tl, sz); drawRect(Color(0xFF00897B), tl, sz, style = Stroke(width = 3f))
                }
            }
        }
    }
}
