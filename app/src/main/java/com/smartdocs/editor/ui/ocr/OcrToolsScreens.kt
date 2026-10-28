package com.smartdocs.editor.ui.ocr

import android.graphics.RectF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.ocr.OcrPage
import com.smartdocs.editor.core.ocr.OcrPipeline
import com.smartdocs.editor.core.ocr.OcrPrep
import com.smartdocs.editor.core.ocr.OcrReview
import com.smartdocs.editor.core.ocr.OcrTuning
import com.smartdocs.editor.core.ocr.SearchablePdfWriter
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.common.rememberToolRunner
import com.smartdocs.editor.ui.edit5.E5FileRow
import com.smartdocs.editor.ui.edit5.E5PageView
import com.smartdocs.editor.ui.edit5.PageStepper
import com.smartdocs.editor.ui.edit5.rememberE5Doc
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nitro "Zone Tool": OCR a selected area of a page. Drag a rectangle on the page, recognise it (languages and options
 * of the OCR sheet), copy the text, and/or add it to the page as searchable text.
 */
@Composable
fun OcrZoneScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    val clipboard = LocalClipboardManager.current
    var page by remember { mutableIntStateOf(0) }
    var zone by remember { mutableStateOf<RectF?>(null) }
    var langs by remember { mutableStateOf(OcrPrefs.languages(ctx).toSet()) }
    var tuning by remember { mutableStateOf(OcrTuning.load(ctx)) }
    var moreLangs by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<OcrPage?>(null) }      // words in page-image pixels
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var runJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }     // audit 5 OCR-5: the zone run can be cancelled
    var exported by remember { mutableStateOf<java.io.File?>(null) }              // audit 5 OCR-8: "Add to page" exports once
    val scope = rememberCoroutineScope()
    val processing = stringResource(R.string.processing)
    val noText = stringResource(R.string.nt_ocr_zone_empty)
    ToolScaffold(stringResource(R.string.nt_ocr_zone), nav, runner) {
        E5FileRow(doc)
        Text(stringResource(R.string.nt_ocr_zone_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            langs.forEach { l -> FilterChip(true, { if (langs.size > 1) langs = langs - l }, label = { Text(OcrEngine.label(l)) }) }
            AssistChip(onClick = { moreLangs = true }, label = { Text(stringResource(R.string.nt_ocr_more_langs)) }, leadingIcon = { Icon(Icons.Default.Language, null) })
        }
        OcrTuningSection(tuning) { tuning = it }
        val info = doc.infos.getOrNull(page)
        if (doc.work != null && info != null) {
            PageStepper(page, doc.pageCount) { page = it; zone = null; result = null; text = "" }
            E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth(), onRect = { r -> zone = r; result = null; text = "" }) { map ->
                zone?.let { z -> val r = map(z); drawRect(Color(0x332196F3), r.topLeft, r.size); drawRect(Color(0xFF2196F3), r.topLeft, r.size, style = Stroke(3f)) }
            }
            // audit 5 OCR-8: areas that need no drag (TalkBack / switch access): whole page, top / bottom half
            // (display points, origin bottom-left: top > bottom, as the dragged zone)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val W = info.displayW; val H = info.displayH
                listOf(
                    R.string.ttoc_zone_whole to RectF(0f, H, W, 0f),
                    R.string.ttoc_zone_top to RectF(0f, H, W, H / 2f),
                    R.string.ttoc_zone_bottom to RectF(0f, H / 2f, W, 0f),
                ).forEach { (label, r) ->
                    AssistChip(onClick = { zone = r; result = null; text = "" }, label = { Text(stringResource(label)) }, leadingIcon = { Icon(Icons.Default.CropFree, null) })
                }
            }
            if (zone != null) Text(stringResource(R.string.ttoc_zone_selected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val z = zone
            Button(onClick = {
                val f = doc.work ?: return@Button
                val pw = doc.password; val l = langs.joinToString("+"); val t = tuning; val pi = page
                busy = true; error = null
                runJob = scope.launchCatching(ctx, { error = it; busy = false }) {
                    val engine = OcrPipeline.engineFor(ctx, null, t)
                    try { OcrPipeline.cancelling(engine) {
                        engine.ensureLanguages(l)
                        if (!engine.init(l)) throw com.smartdocs.editor.core.ocr.OcrException(com.smartdocs.editor.core.ocr.OcrException.Kind.INIT)
                        val (crop, full) = withContext(Dispatchers.IO) {
                            val s = PdfSession.open(ctx, f, pw)
                            try {
                                val (w, h) = s.pageSize(pi)
                                // audit 5 OCR-4: the same render cap as the page OCR, so zone words join the stored page words
                                val k = OcrPipeline.renderScale(w, h)
                                val bmp = s.render(pi, (w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), annotations = false)
                                    .copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                s.invalidateCache()
                                // zone: display points (origin bottom-left) → image pixels (origin top-left)
                                val sx = bmp.width / info.displayW; val sy = bmp.height / info.displayH
                                val x0 = (z!!.left * sx).toInt().coerceIn(0, bmp.width - 1); val x1 = (z.right * sx).toInt().coerceIn(x0 + 1, bmp.width)
                                val y0 = ((info.displayH - z.top) * sy).toInt().coerceIn(0, bmp.height - 1); val y1 = ((info.displayH - z.bottom) * sy).toInt().coerceIn(y0 + 1, bmp.height)
                                val c = android.graphics.Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
                                // createBitmap may return the page bitmap itself (zone = whole page): recycle only a separate one
                                val r = Triple(c, x0 to y0, bmp.width to bmp.height); if (c !== bmp) bmp.recycle(); r
                            } finally { withContext(kotlinx.coroutines.NonCancellable) { s.closeAsync() } }
                        }.let { (c, o, wh) -> c to Triple(o.first, o.second, wh) }
                        // audit 5 OCR-2: filters off the main thread; OCR-8: the bitmaps are freed also on cancel
                        val pg = try {
                            val filtered = OcrPrep.filterAsync(crop, t)
                            try { engine.recognize(filtered, wantWords = true) } finally { if (filtered !== crop) filtered.recycle() }
                        } finally { crop.recycle() }
                        val (x0, y0, wh) = full
                        result = OcrReview.placeZone(pg, x0, y0, wh.first, wh.second)
                        text = pg.text.trim().ifEmpty { noText }
                    } } finally { OcrPipeline.closeQuietly(engine); busy = false; runJob = null }
                }
            }, enabled = z != null && !busy && langs.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.TextFields, null)
                Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.nt_ocr_zone_run))
            }
            if (busy) OutlinedButton(onClick = { runJob?.cancel() }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Close, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cancel))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (text.isNotEmpty()) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text(stringResource(R.string.nt_ocr_zone_text)) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.copy_text)) }
                }
                val r = result
                PrimaryButton(stringResource(R.string.nt_ocr_zone_add), enabled = r != null && r.words.isNotEmpty(), icon = Icons.Default.ManageSearch) {
                    val pg = r ?: return@PrimaryButton; val pi = page
                    runner.run(processing) {
                        doc.apply(app.documents.tempDir) { s, o, p -> SearchablePdfWriter(ctx, s, p).use { w -> w.addLayer(pi, pg); w.save(o) } }
                        // audit 5 OCR-8: the first "Add to page" makes the _ocr copy, later ones update that same copy
                        val prev = exported
                        val w = doc.work
                        if (prev != null && prev.exists() && w != null) { w.copyTo(prev, overwrite = true); com.smartdocs.editor.core.pdf.ToolOutputs.record(prev, doc.source) }
                        else doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "ocr").also { exported = it }
                    }
                }
            }
        }
    }
    if (moreLangs) OcrLanguagePicker(langs, onDismiss = { moreLangs = false }) { langs = it; moreLangs = false }
}

/** Launches [block], reporting a failure through [onError] (a string resource text, audit 5 OCR-8) instead of crashing. */
private fun kotlinx.coroutines.CoroutineScope.launchCatching(ctx: android.content.Context, onError: (String) -> Unit, block: suspend () -> Unit) = launch {
    try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { onError(ocrErrorText(ctx, e)) }
}

/**
 * Foxit "Find OCR Suspects" / "All Suspects" for a document OCRed earlier with this app: low-confidence words of the
 * page or the whole document, marked with red boxes on the page; correct, accept or drop them (one by one or all
 * at once in the list), then save — the changed pages get their searchable layer written again.
 */
@Composable
fun OcrReviewScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val doc = rememberE5Doc(uri)
    val runner = rememberToolRunner()
    var exported by remember { mutableStateOf<java.io.File?>(null) }              // audit 5 OCR-8: Save exports once
    var pages by remember { mutableStateOf<Map<Int, OcrPage>>(emptyMap()) }
    val edits = remember { mutableStateMapOf<Int, Map<Int, String?>>() }   // page → (word → new text / null = not text)
    var minConf by remember { mutableFloatStateOf(65f) }
    var wholeDoc by remember { mutableStateOf(true) }
    var page by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(doc.work, doc.version) {
        val w = doc.work ?: return@LaunchedEffect
        if (edits.isEmpty()) { pages = runCatching { OcrReview.load(w, doc.password) }.getOrDefault(emptyMap()); loaded = true }
    }
    val processing = stringResource(R.string.processing)
    fun current(pi: Int): OcrPage? = pages[pi]?.let { OcrReview.apply(it, edits[pi].orEmpty()) }
    fun setEdit(pi: Int, wi: Int, v: String?) { edits[pi] = edits[pi].orEmpty() + (wi to v) }
    ToolScaffold(stringResource(R.string.nt_ocr_review), nav, runner) {
        E5FileRow(doc)
        if (loaded && pages.isEmpty()) Text(stringResource(R.string.nt_ocr_review_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (pages.isNotEmpty()) {
            Text(stringResource(R.string.nt_ocr_review_conf, minConf.toInt()), style = MaterialTheme.typography.bodySmall)
            Slider(minConf, { minConf = it }, valueRange = 30f..95f)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(wholeDoc, { wholeDoc = true }, label = { Text(stringResource(R.string.nt_ocr_review_doc)) })
                FilterChip(!wholeDoc, { wholeDoc = false }, label = { Text(stringResource(R.string.nt_ocr_review_page)) })
            }
            val info = doc.infos.getOrNull(page)
            if (info != null && doc.work != null) {
                PageStepper(page, doc.pageCount) { page = it }
                val cur = pages[page]
                E5PageView(doc.work, doc.password, page, info, doc.version, Modifier.fillMaxWidth()) { map ->
                    val p = pages[page] ?: return@E5PageView
                    val e = edits[page].orEmpty()
                    val kx = info.displayW / p.imageWidth; val ky = info.displayH / p.imageHeight
                    OcrReview.suspects(p, minConf).filter { it !in e }.forEach { wi ->
                        val b = p.words[wi].box
                        val r = map(RectF(b.left * kx, info.displayH - b.top * ky, b.right * kx, info.displayH - b.bottom * ky))
                        drawRect(Color(0xFFE53935), r.topLeft, r.size, style = Stroke(3f))
                    }
                }
                if (cur == null) Text(stringResource(R.string.nt_ocr_review_page_none), style = MaterialTheme.typography.bodySmall)
            }
            // All suspects: every suspect of the page or document, editable at once
            val rows = (if (wholeDoc) pages.keys.sorted() else listOf(page)).flatMap { pi ->
                val p = pages[pi] ?: return@flatMap emptyList()
                OcrReview.suspects(p, minConf).map { wi -> Triple(pi, wi, p.words[wi]) }
            }
            Text(stringResource(R.string.nt_ocr_review_count, rows.count { (pi, wi, _) -> wi !in edits[pi].orEmpty() }, rows.size), style = MaterialTheme.typography.titleSmall)
            // audit 5 OCR-8: the list shows the first rows only; say so (the page view and "Accept all" still cover every suspect)
            if (rows.size > REVIEW_ROWS) Text(stringResource(R.string.ttoc_review_cut, REVIEW_ROWS, rows.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            rows.take(REVIEW_ROWS).forEach { (pi, wi, w) ->
                val done = wi in edits[pi].orEmpty()
                var v by remember(pi, wi) { mutableStateOf(edits[pi]?.get(wi) ?: w.text) }
                Row(Modifier.fillMaxWidth().clickable { page = pi }, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(v, { v = it }, Modifier.weight(1f), singleLine = true, enabled = !(done && edits[pi]?.get(wi) == null),
                        label = { Text(stringResource(R.string.nt_ocr_review_meta, pi + 1, w.confidence.toInt()), maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    IconButton(onClick = { setEdit(pi, wi, v) }) { Icon(Icons.Default.Check, stringResource(R.string.nt_ocr_review_accept), tint = if (done) Color(0xFF2E7D32) else LocalContentColor.current) }
                    IconButton(onClick = { setEdit(pi, wi, null) }) { Icon(Icons.Default.Block, stringResource(R.string.t5_not_text)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { rows.forEach { (pi, wi, w) -> if (wi !in edits[pi].orEmpty()) setEdit(pi, wi, w.text) } }, enabled = rows.isNotEmpty()) {
                    Text(stringResource(R.string.nt_ocr_review_accept_all))
                }
            }
            PrimaryButton(stringResource(R.string.save), enabled = edits.isNotEmpty() && doc.work != null, icon = Icons.Default.Save) {
                val changed = edits.keys.mapNotNull { pi -> current(pi)?.let { pi to it } }.toMap()
                runner.run(processing) {
                    doc.apply(app.documents.tempDir) { s, o, p -> OcrReview.rewrite(ctx, s, p, o, changed) }
                    edits.clear()
                    pages = OcrReview.load(doc.work!!, doc.password)
                    val prev = exported
                    val w = doc.work
                    if (prev != null && prev.exists() && w != null) { w.copyTo(prev, overwrite = true); com.smartdocs.editor.core.pdf.ToolOutputs.record(prev, doc.source) }
                    else doc.export(app.documents.docsDir, { d, b -> app.documents.uniqueFile(d, b) }, "reviewed").also { exported = it }
                }
            }
        }
    }
}

/** Rows of the suspect list shown at once (audit 5 OCR-8: the cut is said on screen). */
private const val REVIEW_ROWS = 400
