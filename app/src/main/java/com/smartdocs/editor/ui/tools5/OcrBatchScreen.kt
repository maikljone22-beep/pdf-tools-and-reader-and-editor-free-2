package com.smartdocs.editor.ui.tools5

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ocr.OcrEngine
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.ui.common.LabeledSlider
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ResultsList
import com.smartdocs.editor.ui.tools.ToolScaffold
import java.io.File

private val LANG_CHOICES = listOf("ara", "eng", "fra", "deu", "spa", "tur", "fas", "urd")

/**
 * OCR plus: many PDFs / images at once, image pre-clean, language auto-detect, review of low-confidence words
 * (single file), in-screen or in the background (recipe with one OCR step).
 */
@Composable
fun OcrBatchScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { ctx.getSharedPreferences("t5_ocr", android.content.Context.MODE_PRIVATE) }
    var files by remember { mutableStateOf(listOfNotNull(fileOf(uri))) }
    var auto by remember { mutableStateOf(prefs.getBoolean("auto", true)) }
    var langs by remember { mutableStateOf((prefs.getString("langs", "ara+eng") ?: "ara+eng").split('+').toSet()) }
    var clean by remember { mutableStateOf(prefs.getBoolean("clean", true)) }
    var review by remember { mutableStateOf(true) }
    var minConf by remember { mutableFloatStateOf(prefs.getFloat("conf", 65f)) }
    var background by remember { mutableStateOf(false) }
    var pendingReview by remember { mutableStateOf<OcrDoc?>(null) }
    var tuning by remember { mutableStateOf(com.smartdocs.editor.core.ocr.OcrTuning.load(ctx)) }
    var pendingFixed by remember { mutableStateOf<File?>(null) }
    var moreLangs by remember { mutableStateOf(false) }
    val job = rememberT5Job()
    val pick = rememberAnyFilePicker(true, arrayOf("application/pdf", "image/*")) { picked -> files = files + picked }
    val working = stringResource(R.string.t5_ocr_working)
    val nothing = stringResource(R.string.t5_ocr_nothing)
    val queued = stringResource(R.string.t5_batch_queued)
    fun plan() = OcrPlan(langs = if (auto) null else langs.sorted().joinToString("+").ifBlank { "ara+eng" }, preClean = clean, minConfidence = minConf, tuning = tuning)
    fun save() { prefs.edit().putBoolean("auto", auto).putString("langs", langs.joinToString("+")).putBoolean("clean", clean).putFloat("conf", minConf).apply(); com.smartdocs.editor.core.ocr.OcrTuning.save(ctx, tuning) }

    ToolScaffold(stringResource(R.string.t5_ocr_title), nav, snackbar = snackbar) {
        OutlinedButton(onClick = pick, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_add_files)) }
        if (files.isNotEmpty()) T5Card { FileListEditor(files, { files = it }) }
        T5Card(stringResource(R.string.t5_options), Icons.Default.Tune) {
            SwitchRow(stringResource(R.string.t5_lang_auto), auto, stringResource(R.string.t5_lang_auto_hint)) { auto = it }
            if (!auto) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (LANG_CHOICES + langs.filter { it !in LANG_CHOICES }).forEach { l -> FilterChip(l in langs, { langs = if (l in langs) langs - l else langs + l }, label = { Text(OcrEngine.label(l)) }) }
                AssistChip(onClick = { moreLangs = true }, label = { Text(stringResource(R.string.nt_ocr_more_langs)) }, leadingIcon = { Icon(Icons.Default.Language, null) })
            }
            SwitchRow(stringResource(R.string.t5_preclean), clean, stringResource(R.string.t5_preclean_hint)) { clean = it }
            SwitchRow(stringResource(R.string.t5_review), review, stringResource(R.string.t5_review_hint), enabled = files.size == 1 && !background) { review = it }
            if (review) LabeledSlider(stringResource(R.string.t5_min_conf), minConf, 30f..90f, { minConf = it })
            SwitchRow(stringResource(R.string.t5_run_background), background, stringResource(R.string.t5_run_background_hint)) { background = it }
        }
        com.smartdocs.editor.ui.ocr.OcrTuningSection(tuning) { tuning = it }
        PrimaryButton(stringResource(R.string.t5_ocr_run, files.size), enabled = files.isNotEmpty() && !job.running, icon = Icons.Default.TextFields) {
            save()
            val list = files.toList(); val p = plan()
            if (background) {
                val r = Recipe(RecipeJson.newId(), "OCR", listOf(Step(StepType.OCR, mapOf("langs" to (p.langs ?: "auto"), "clean" to p.preClean.toString()))), OutputOptions("{name}_ocr"))
                BatchJobs.enqueue(ctx, working, list.map { it to null }, r, false, app.documents.docsDir)
                job.info = queued
                nav.navigate(com.smartdocs.editor.ui.Routes.tool("batch"))
                return@PrimaryButton
            }
            val wantReview = review && list.size == 1
            job.launch(working) {
                val engine = com.smartdocs.editor.core.ocr.OcrPipeline.engineFor(ctx, null, p.tuning)
                try {
                    if (wantReview) {
                        val fixed = OcrRunner.prepare(ctx, engine, list[0], null, p)
                        val d = OcrRunner.recognize(ctx, engine, fixed ?: list[0], null, p) { a, b -> step(a, b) }
                        pendingFixed = fixed
                        if (d.pages.isEmpty()) { error = nothing; return@launch }
                        if (d.suspects.isEmpty()) {
                            val out = app.documents.uniqueFile(app.documents.docsDir, list[0].nameWithoutExtension + "_ocr")
                            result = OcrRunner.write(ctx, d, out)
                        } else pendingReview = d
                    } else {
                        val out = ArrayList<File>()
                        list.forEachIndexed { i, f ->
                            message = ctx.getString(R.string.t5_ocr_file, i + 1, list.size, f.name)
                            val dst = app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_ocr")
                            OcrRunner.ocrFile(ctx, engine, f, null, p, dst) { a, b -> step(a, b) }?.let { out += it }
                        }
                        if (out.isEmpty()) error = nothing else if (out.size == 1) result = out[0] else results = out
                    }
                } finally { engine.close() }
            }
        }
        Text(stringResource(R.string.t5_ocr_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (job.results.isNotEmpty()) ResultsList(job.results, nav, "application/pdf")
    }
    T5JobUi(job, nav, snackbar)
    pendingReview?.let { d ->
        OcrReviewDialog(d, onDone = { decisions ->
            pendingReview = null
            job.launch(working) {
                OcrRunner.applyReview(d, decisions)
                val out = app.documents.uniqueFile(app.documents.docsDir, (files.firstOrNull()?.nameWithoutExtension ?: d.src.nameWithoutExtension) + "_ocr")
                result = OcrRunner.write(ctx, d, out).also { com.smartdocs.editor.core.ocr.OcrPipeline.shrinkOutput(it, null, tuning) }
                pendingFixed?.delete(); pendingFixed = null
            }
        }, onCancel = { pendingReview = null; pendingFixed?.delete(); pendingFixed = null })
    }
    if (moreLangs) com.smartdocs.editor.ui.ocr.OcrLanguagePicker(langs, onDismiss = { moreLangs = false }) { langs = it; moreLangs = false }
}

/** Pager over low-confidence words: crop, editable text, accept / not text. */
@Composable
fun OcrReviewDialog(doc: OcrDoc, onDone: (Map<Pair<Int, Int>, String?>) -> Unit, onCancel: () -> Unit) {
    val list = doc.suspects
    var i by remember { mutableIntStateOf(0) }
    val decisions = remember { mutableStateMapOf<Pair<Int, Int>, String?>() }
    val s = list[i.coerceIn(0, list.size - 1)]
    var text by remember(i) { mutableStateOf(decisions[s.page to s.index] ?: s.text) }
    fun commit() { if (text != s.text) decisions[s.page to s.index] = text }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.t5_review_title, i + 1, list.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.crop?.let { Image(it.asImageBitmap(), stringResource(R.string.t5_word_picture), Modifier.fillMaxWidth().heightIn(max = 120.dp), contentScale = ContentScale.Fit) }
                Text(stringResource(R.string.t5_review_meta, s.page + 1, s.confidence.toInt()), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.t5_word)) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { decisions[s.page to s.index] = null; if (i < list.size - 1) i++ else onDone(decisions.toMap()) }) { Text(stringResource(R.string.t5_not_text)) }
                    OutlinedButton(onClick = { commit(); if (i > 0) i-- }, enabled = i > 0) { Text(stringResource(R.string.t5_prev)) }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { commit(); onDone(decisions.toMap()) }) { Text(stringResource(R.string.t5_finish)) }
                TextButton(onClick = { commit(); if (i < list.size - 1) i++ else onDone(decisions.toMap()) }) { Text(stringResource(if (i < list.size - 1) R.string.t5_next else R.string.t5_finish)) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
