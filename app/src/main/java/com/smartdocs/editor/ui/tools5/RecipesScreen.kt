package com.smartdocs.editor.ui.tools5

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.tools5.*
import com.smartdocs.editor.core.util.PdfPrint
import com.smartdocs.editor.core.view5.PrintPipeline
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
fun StepType.label(): String = stringResource(
    when (this) {
        StepType.OCR -> R.string.t5_st_ocr; StepType.OPTIMIZE -> R.string.t5_st_optimize; StepType.PDFA -> R.string.t5_st_pdfa
        StepType.WATERMARK -> R.string.t5_st_watermark; StepType.PAGE_NUMBERS -> R.string.t5_st_numbers; StepType.ROTATE -> R.string.t5_st_rotate
        StepType.REMOVE_METADATA -> R.string.t5_st_nometa; StepType.SET_INFO -> R.string.t5_st_info; StepType.FLATTEN -> R.string.t5_st_flatten
        StepType.AUTO_TAG -> R.string.t5_st_tag; StepType.PROTECT -> R.string.t5_st_protect
        StepType.JAVASCRIPT -> R.string.nbt_st_js; StepType.DELETE_COMMENTS -> R.string.nbt_st_del_comments; StepType.HEADER_FOOTER -> R.string.nbt_st_hf
        StepType.BATES -> R.string.nbt_st_bates; StepType.REDACT -> R.string.nbt_st_redact; StepType.SANITIZE -> R.string.nbt_st_sanitize
        StepType.ACCESS_CHECK -> R.string.nbt_st_access_check; StepType.CONVERT_OFFICE -> R.string.nbt_st_convert; StepType.PRINT -> R.string.nbt_st_print
        StepType.NOTE -> R.string.nbt_st_note; StepType.RUN_RECIPE -> R.string.nbt_st_recipe
    },
)

/** Display name of a recipe (built-ins are translated). */
@Composable
fun Recipe.title(): String = if (!builtIn) name else stringResource(
    when (name) {
        "scan_archive" -> R.string.t5_rc_archive; "web" -> R.string.t5_rc_web; "ocr" -> R.string.t5_rc_ocr
        "number" -> R.string.t5_rc_number; "access" -> R.string.t5_rc_access; "distribute" -> R.string.nbt_rc_distribute
        "sensitive" -> R.string.nbt_rc_sensitive; "pdfua" -> R.string.nbt_rc_pdfua; else -> R.string.t5_rc_share
    },
)

/** Description of a built-in recipe, or the user's own. */
@Composable
private fun Recipe.about(): String = if (!builtIn) description else when (name) {
    "distribute" -> stringResource(R.string.nbt_rc_distribute_d); "sensitive" -> stringResource(R.string.nbt_rc_sensitive_d)
    "pdfua" -> stringResource(R.string.nbt_rc_pdfua_d); else -> ""
}

/** A recipe ready to start: the steps asked at run time were answered, the name conflicts are still to check. */
private data class PendingRun(val recipe: Recipe, val label: String)

/**
 * Batch recipes: pick files, a folder (with sub-folders) or the open tabs; pick / edit / copy / reorder a recipe; run it
 * in WorkManager (options marked "ask when run" are asked first, existing output names are asked about when the
 * recipe says so); import / export .sdrecipe.
 */
@Composable
fun RecipesScreen(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var version by remember { mutableIntStateOf(0) }
    val recipes = remember(version) { RecipeStore.all(ctx) }
    var selected by remember { mutableStateOf<Recipe?>(null) }
    var editing by remember { mutableStateOf<Recipe?>(null) }
    var sources by remember { mutableStateOf(listOfNotNull(fileOf(uri)).map { SourceFile(it) }) }
    var pendingId by remember { mutableStateOf(BatchJobs.newId()) }
    var copying by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<PendingRun?>(null) }
    var conflicts by remember { mutableStateOf<Pair<PendingRun, List<String>>?>(null) }
    fun addFiles(more: List<SourceFile>) { sources = (sources + more).distinctBy { it.file.absolutePath } }
    val pick = rememberAnyFilePicker(true, ToPdf.pickerMimes) { picked -> addFiles(picked.map { SourceFile(it) }) }
    val folder = rememberFolderSource({ BatchJobs.inputsDir(ctx, pendingId) }, accept = { ToPdf.kindOf(it) != SourceKind.UNKNOWN },
        onBusy = { copying = it }) { addFiles(it) }
    val imported = stringResource(R.string.t5_rc_imported)
    val badFile = stringResource(R.string.t5_rc_bad)
    val noTabs = stringResource(R.string.nbt_no_open_tabs)
    val copyOf = stringResource(R.string.nbt_rc_copy_of)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(u)?.use { String(it.readBytes()) }?.let { RecipeJson.fromJson(it) } }.getOrNull() }
            if (r == null || r.steps.isEmpty()) snackbar.showSnackbar(badFile) else { RecipeStore.save(ctx, r.copy(id = RecipeJson.newId())); version++; snackbar.showSnackbar(imported) }
        }
    }
    // inputs copied in from a folder for a batch that was never started
    DisposableEffect(Unit) { onDispose { val id = pendingId; if (BatchJobs.load(ctx, id) == null) runCatching { BatchJobs.inputsDir(ctx, id).deleteRecursively() } } }

    fun enqueue(run: PendingRun, conflict: Conflict?) {
        BatchJobs.enqueueItems(ctx, pendingId, run.label, sources.map { BatchFile(it.file.absolutePath, null, it.name, it.folder) }, run.recipe.copy(name = run.label), false,
            File(app.documents.docsDir, "Batch"), conflict)
        pendingId = BatchJobs.newId()
        sources = emptyList()
        nav.navigate(Routes.tool("batch"))
    }

    /** Before starting: when the recipe asks about existing names, look for outputs that already exist. */
    fun checkAndRun(run: PendingRun) {
        val r = run.recipe
        if (r.output.conflict != Conflict.ASK) { enqueue(run, null); return }
        scope.launch {
            val taken = io {
                val now = Date()
                val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now); val t = SimpleDateFormat("HHmm", Locale.US).format(now)
                val ext = RecipeOps.outputExt(RecipeOps.expand(r) { RecipeStore.lookup(ctx, it) })
                val folderNames = r.output.folderUri.takeIf { it.isNotEmpty() && !r.output.sameFolder }?.let { SafFolders.names(ctx, SafFolders.root(Uri.parse(it))) }
                sources.mapIndexedNotNull { i, src ->
                    val name = OutputNaming.apply(r.output.pattern, File(src.name).nameWithoutExtension, run.label, i, d, t) + "." + ext
                    val exists = when {
                        r.output.sameFolder && src.folder.isNotEmpty() -> name in SafFolders.names(ctx, Uri.parse(src.folder))
                        r.output.sameFolder -> File(src.file.parentFile, name).exists()
                        folderNames != null -> name in folderNames
                        else -> File(File(app.documents.docsDir, "Batch"), name).exists()
                    }
                    name.takeIf { exists }
                }
            }
            if (taken.isEmpty()) enqueue(run, Conflict.RENAME) else conflicts = run to taken
        }
    }

    fun start(r: Recipe, label: String) {
        val run = PendingRun(r, label)
        val steps = RecipeOps.expand(r) { RecipeStore.lookup(ctx, it) }
        if (RecipeOps.asked(steps).isNotEmpty()) asking = run.copy(recipe = r.copy(steps = steps)) else checkAndRun(run)
    }

    ToolScaffold(stringResource(R.string.t5_recipes_title), nav, snackbar = snackbar, actions = {
        IconButton(onClick = { nav.navigate(Routes.tool("batch")) }) { Icon(Icons.Default.Schedule, stringResource(R.string.t5_batch_jobs)) }
        IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileOpen, stringResource(R.string.t5_rc_import)) }
    }) {
        OutlinedButton(onClick = pick, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_add_files)) }
        BatchSourceButtons(onFolder = folder, onOpenTabs = {
            val tabs = openTabFiles(ctx)
            if (tabs.isEmpty()) scope.launch { snackbar.showSnackbar(noTabs) } else addFiles(tabs.map { SourceFile(it) })
        })
        if (copying) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (sources.isNotEmpty()) T5Card(stringResource(R.string.t5_files_n, sources.size)) {
            val byFile = sources.associateBy { it.file }
            FileListEditor(sources.map { it.file }, { list -> sources = list.mapNotNull { byFile[it] } }, titles = sources.associate { it.file to it.name })
        }
        T5Card(stringResource(R.string.t5_rc_pick), Icons.Default.AutoMode) {
            val user = recipes.filter { !it.builtIn }
            recipes.forEach { r ->
                val ui = user.indexOfFirst { it.id == r.id }
                ListItem(
                    modifier = Modifier.clickable { selected = r },
                    leadingContent = { RadioButton(selected?.id == r.id, onClick = { selected = r }) },
                    headlineContent = { Text(r.title()) },
                    supportingContent = {
                        Column {
                            r.about().takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            Text(r.steps.filter { it.enabled }.map { it.type.label() }.joinToString(" → "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    trailingContent = {
                        Row {
                            if (!r.builtIn) IconButton(onClick = { editing = r }) { Icon(Icons.Default.Edit, stringResource(R.string.t5_rc_edit)) }
                            val copyName = if (r.builtIn) "" else String.format(copyOf, r.name)
                            IconButton(onClick = { editing = RecipeOps.clone(r, copyName) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.t5_rc_duplicate)) }
                            if (!r.builtIn) {
                                IconButton(onClick = { RecipeStore.move(ctx, r.id, -1); version++ }, enabled = ui > 0) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.t5_move_up)) }
                                IconButton(onClick = { RecipeStore.move(ctx, r.id, 1); version++ }, enabled = ui in 0 until user.size - 1) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.t5_move_down)) }
                                IconButton(onClick = {
                                    scope.launch {
                                        val f = runCatching { withContext(Dispatchers.IO) { app.documents.uniqueFile(app.documents.docsDir, TextNorm.safeFileName(r.name).ifBlank { "recipe" }, "sdrecipe").also { it.writeText(RecipeJson.toJson(r)) } } }.getOrNull() ?: return@launch
                                        shareFiles(ctx, app.documents, listOf(f), "application/json")
                                    }
                                }) { Icon(Icons.Default.Share, stringResource(R.string.t5_rc_export)) }
                            }
                        }
                    },
                )
            }
            TextButton(onClick = { editing = Recipe(RecipeJson.newId(), "", listOf(Step(StepType.OCR))) }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.t5_rc_new)) }
        }
        val label = selected?.title().orEmpty()
        PrimaryButton(stringResource(R.string.t5_rc_run, sources.size), enabled = sources.isNotEmpty() && selected != null && !copying, icon = Icons.Default.PlayArrow) {
            val r = selected ?: return@PrimaryButton
            start(r, label)
        }
        Text(stringResource(R.string.t5_rc_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrinterRecipeCard(recipes, version) { version++ }
    }
    editing?.let { r ->
        RecipeEditor(r, onSave = { RecipeStore.save(ctx, it); version++; selected = it; editing = null }, onDelete = if (RecipeStore.user(ctx).any { it.id == r.id }) ({ RecipeStore.delete(ctx, r.id); version++; editing = null }) else null, onDismiss = { editing = null })
    }
    asking?.let { run -> AskAtRunDialog(run.recipe, recipes, onRun = { steps -> asking = null; checkAndRun(run.copy(recipe = run.recipe.copy(steps = steps))) }, onDismiss = { asking = null }) }
    conflicts?.let { (run, names) ->
        AlertDialog(
            onDismissRequest = { conflicts = null },
            title = { Text(stringResource(R.string.nbt_conflict_found, names.size)) },
            text = { Text(names.take(12).joinToString("\n") + if (names.size > 12) "\n…" else "", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                Column {
                    TextButton(onClick = { conflicts = null; enqueue(run, Conflict.OVERWRITE) }) { Text(stringResource(R.string.nbt_conflict_overwrite)) }
                    TextButton(onClick = { conflicts = null; enqueue(run, Conflict.RENAME) }) { Text(stringResource(R.string.nbt_conflict_rename)) }
                    TextButton(onClick = { conflicts = null; enqueue(run, Conflict.SKIP) }) { Text(stringResource(R.string.nbt_conflict_skip)) }
                }
            },
            dismissButton = { TextButton(onClick = { conflicts = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Foxit PDF Printer settings: the recipe the "SmartDocs PDF" print service applies to every printed file. */
@Composable
private fun PrinterRecipeCard(recipes: List<Recipe>, version: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val current = remember(version) { RecipeStore.printerRecipe(ctx) }
    var open by remember { mutableStateOf(false) }
    T5Card(stringResource(R.string.nbt_printer_title), Icons.Default.Print) {
        Text(stringResource(R.string.nbt_printer_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) { Text(current?.title() ?: stringResource(R.string.nbt_printer_none), maxLines = 1) }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.nbt_printer_none)) }, onClick = { RecipeStore.setPrinterRecipe(ctx, null); open = false; onChanged() })
                recipes.forEach { r -> DropdownMenuItem(text = { Text(r.title()) }, onClick = { RecipeStore.setPrinterRecipe(ctx, r.id); open = false; onChanged() }) }
            }
        }
    }
}

/** Foxit "Prompt User": the options of the steps marked "ask when run" (and the recipe's instructions) before it starts. */
@Composable
private fun AskAtRunDialog(recipe: Recipe, recipes: List<Recipe>, onRun: (List<Step>) -> Unit, onDismiss: () -> Unit) {
    var steps by remember { mutableStateOf(recipe.steps) }
    val asked = remember(recipe) { RecipeOps.asked(recipe.steps) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(recipe.title()) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                asked.forEach { i ->
                    val s = steps[i]
                    if (s.type == StepType.NOTE) Text(s.p("text"), style = MaterialTheme.typography.bodyMedium)
                    else T5Card(s.type.label()) {
                        s.type.defaults.keys.forEach { k -> StepParam(s, k, recipes, recipe.id) { n -> steps = steps.toMutableList().also { it[i] = n } } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRun(steps) }) { Text(stringResource(R.string.nbt_run)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Background jobs with live progress, the per-file log, stop / resume and the print request of a recipe. */
@Composable
fun BatchScreen(nav: NavHostController) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val jobs = remember(version) { BatchJobs.all(ctx) }
    val infos by remember { WorkManager.getInstance(ctx).getWorkInfosByTagFlow(BatchJobs.TAG) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var open by remember { mutableStateOf<BatchJob?>(null) }
    var printing by remember { mutableStateOf(false) }
    LaunchedEffect(infos) { version++ }

    /** Send the outputs of [j] to the print framework (one job per file, or merged when the step says so). */
    fun printOutputs(j: BatchJob, merged: Boolean) {
        val outs = BatchJobs.readLog(ctx, j.id).mapNotNull { it.output?.let(::File) }.filter { it.exists() && it.extension.equals("pdf", true) }
        if (outs.isEmpty()) return
        printing = true
        scope.launch {
            runCatching {
                if (merged && outs.size > 1) {
                    val out = File(app.documents.tempDir, "batch_${j.id}.pdf")
                    PdfPrint.print(ctx, out, j.title, PrintPipeline.merge(outs, out))
                } else outs.forEach { PdfPrint.print(ctx, it, it.nameWithoutExtension) }
            }
            printing = false
        }
    }
    // a finished recipe with a print step prints once, when its job is first seen finished here
    LaunchedEffect(infos, jobs) {
        val prefs = ctx.getSharedPreferences("nbt_batch_printed", android.content.Context.MODE_PRIVATE)
        jobs.forEach { j ->
            val info = infos.firstOrNull { it.id.toString() == j.workId } ?: return@forEach
            val req = j.printRequest { RecipeStore.lookup(ctx, it) } ?: return@forEach
            if (info.state == WorkInfo.State.SUCCEEDED && !prefs.getBoolean(j.id, false)) {
                prefs.edit().putBoolean(j.id, true).apply()
                printOutputs(j, req.bool("merged"))
            }
        }
    }

    ToolScaffold(stringResource(R.string.t5_batch_jobs), nav) {
        if (jobs.isEmpty()) Text(stringResource(R.string.t5_batch_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        jobs.forEach { j ->
            val info = infos.firstOrNull { it.id.toString() == j.workId }
            val done = info?.progress?.getInt(BatchWorker.KEY_DONE, -1)?.takeIf { it >= 0 } ?: info?.outputData?.getInt(BatchWorker.KEY_DONE, 0) ?: 0
            val state = info?.state
            T5Card(j.title, Icons.Default.Schedule) {
                Text(DocumentRepository.formatDate(j.created) + " · " + stringResource(R.string.t5_files_n, j.items.size), style = MaterialTheme.typography.bodySmall)
                val (txt, color) = when (state) {
                    WorkInfo.State.RUNNING -> stringResource(R.string.t5_job_running) to T5Orange
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> stringResource(R.string.t5_job_queued) to T5Grey
                    WorkInfo.State.SUCCEEDED -> stringResource(R.string.t5_job_done) to T5Green
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> stringResource(R.string.t5_job_failed) to T5Red
                    null -> stringResource(R.string.t5_job_done) to T5Grey
                }
                StatusPill(txt, color)
                if (state == WorkInfo.State.RUNNING) LinearProgressIndicator(progress = { done.toFloat() / j.items.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { open = j }) { Text(stringResource(R.string.t5_job_log)) }
                    if (state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED) TextButton(onClick = { runCatching { WorkManager.getInstance(ctx).cancelWorkById(UUID.fromString(j.workId)) } }) { Text(stringResource(R.string.nbt_stop)) }
                    val stopped = state == WorkInfo.State.CANCELLED || state == WorkInfo.State.FAILED || state == null
                    if (stopped && BatchJobs.readLog(ctx, j.id).size < j.items.size) TextButton(onClick = { BatchJobs.resume(ctx, j.id); version++ }) { Text(stringResource(R.string.nbt_resume)) }
                    val req = remember(j.id) { j.printRequest { RecipeStore.lookup(ctx, it) } }
                    if (req != null && state == WorkInfo.State.SUCCEEDED) TextButton(onClick = { printOutputs(j, req.bool("merged")) }, enabled = !printing) { Text(stringResource(R.string.print)) }
                    TextButton(onClick = { BatchJobs.delete(ctx, j.id); version++ }) { Text(stringResource(R.string.t5_remove)) }
                }
            }
        }
    }
    open?.let { j ->
        val log = remember(j, infos) { BatchJobs.readLog(ctx, j.id) }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(stringResource(R.string.t5_job_log)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (log.isEmpty()) Text(stringResource(R.string.t5_job_log_empty))
                    log.forEach { e ->
                        ListItem(
                            leadingContent = { Icon(if (e.ok) Icons.Default.CheckCircle else Icons.Default.Error, stringResource(if (e.ok) R.string.t5_pf_ok else R.string.t5_pf_error), tint = if (e.ok) T5Green else T5Red) },
                            headlineContent = { Text(e.input, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(if (e.ok) listOf(File(e.output ?: "").name + " · " + (e.millis / 1000) + " s", e.message).filter { it.isNotBlank() }.joinToString("\n") else e.message, maxLines = 4)
                            },
                            modifier = Modifier.clickable(enabled = e.ok && e.output != null && e.output.endsWith(".pdf", true)) { e.output?.let { p -> open = null; openPdf(nav, File(p)) } },
                        )
                    }
                }
            },
            confirmButton = {
                val outs = log.mapNotNull { it.output?.let(::File) }.filter { it.exists() }
                TextButton(onClick = { shareFiles(ctx, app.documents, outs, if (outs.all { it.extension.equals("pdf", true) }) "application/pdf" else "*/*") }, enabled = outs.isNotEmpty()) { Text(stringResource(R.string.share)) }
            },
            dismissButton = { TextButton(onClick = { open = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}
