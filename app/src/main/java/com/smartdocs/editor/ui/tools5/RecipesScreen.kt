package com.smartdocs.editor.ui.tools5

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
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools.ToolScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun StepType.label(): String = stringResource(
    when (this) {
        StepType.OCR -> R.string.t5_st_ocr; StepType.OPTIMIZE -> R.string.t5_st_optimize; StepType.PDFA -> R.string.t5_st_pdfa
        StepType.WATERMARK -> R.string.t5_st_watermark; StepType.PAGE_NUMBERS -> R.string.t5_st_numbers; StepType.ROTATE -> R.string.t5_st_rotate
        StepType.REMOVE_METADATA -> R.string.t5_st_nometa; StepType.SET_INFO -> R.string.t5_st_info; StepType.FLATTEN -> R.string.t5_st_flatten
        StepType.AUTO_TAG -> R.string.t5_st_tag; StepType.PROTECT -> R.string.t5_st_protect
    },
)

/** Display name of a recipe (built-ins are translated). */
@Composable
fun Recipe.title(): String = if (!builtIn) name else stringResource(
    when (name) {
        "scan_archive" -> R.string.t5_rc_archive; "web" -> R.string.t5_rc_web; "ocr" -> R.string.t5_rc_ocr
        "number" -> R.string.t5_rc_number; "access" -> R.string.t5_rc_access; else -> R.string.t5_rc_share
    },
)

/** Batch recipes: pick files, pick / edit a recipe, run it in WorkManager; import / export .sdrecipe. */
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
    var files by remember { mutableStateOf(listOfNotNull(fileOf(uri))) }
    val pick = rememberAnyFilePicker(true, ToPdf.pickerMimes) { picked -> files = files + picked }
    val imported = stringResource(R.string.t5_rc_imported)
    val badFile = stringResource(R.string.t5_rc_bad)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(u)?.use { String(it.readBytes()) }?.let { RecipeJson.fromJson(it) } }.getOrNull() }
            if (r == null || r.steps.isEmpty()) snackbar.showSnackbar(badFile) else { RecipeStore.save(ctx, r.copy(id = RecipeJson.newId())); version++; snackbar.showSnackbar(imported) }
        }
    }

    ToolScaffold(stringResource(R.string.t5_recipes_title), nav, snackbar = snackbar, actions = {
        IconButton(onClick = { nav.navigate(Routes.tool("batch")) }) { Icon(Icons.Default.Schedule, stringResource(R.string.t5_batch_jobs)) }
        IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileOpen, stringResource(R.string.t5_rc_import)) }
    }) {
        OutlinedButton(onClick = pick, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.t5_add_files)) }
        if (files.isNotEmpty()) T5Card(stringResource(R.string.t5_files_n, files.size)) { FileListEditor(files, { files = it }) }
        T5Card(stringResource(R.string.t5_rc_pick), Icons.Default.AutoMode) {
            recipes.forEach { r ->
                ListItem(
                    modifier = Modifier.clickable { selected = r },
                    leadingContent = { RadioButton(selected?.id == r.id, onClick = { selected = r }) },
                    headlineContent = { Text(r.title()) },
                    supportingContent = { Text(r.steps.filter { it.enabled }.map { it.type.label() }.joinToString(" → "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = if (r.builtIn) r.copy(id = RecipeJson.newId(), name = "", builtIn = false) else r }) { Icon(if (r.builtIn) Icons.Default.ContentCopy else Icons.Default.Edit, stringResource(if (r.builtIn) R.string.t5_rc_duplicate else R.string.t5_rc_edit)) }
                            if (!r.builtIn) IconButton(onClick = {
                                scope.launch {
                                    val f = runCatching { withContext(Dispatchers.IO) { app.documents.uniqueFile(app.documents.docsDir, TextNorm.safeFileName(r.name).ifBlank { "recipe" }, "sdrecipe").also { it.writeText(RecipeJson.toJson(r)) } } }.getOrNull() ?: return@launch
                                    shareFiles(ctx, app.documents, listOf(f), "application/json")
                                }
                            }) { Icon(Icons.Default.Share, stringResource(R.string.t5_rc_export)) }
                        }
                    },
                )
            }
            TextButton(onClick = { editing = Recipe(RecipeJson.newId(), "", listOf(Step(StepType.OCR))) }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.t5_rc_new)) }
        }
        val label = selected?.title().orEmpty()
        PrimaryButton(stringResource(R.string.t5_rc_run, files.size), enabled = files.isNotEmpty() && selected != null, icon = Icons.Default.PlayArrow) {
            val r = selected ?: return@PrimaryButton
            BatchJobs.enqueue(ctx, label, files.map { it to null }, r.copy(name = label), false, File(app.documents.docsDir, "Batch"))
            files = emptyList()
            nav.navigate(Routes.tool("batch"))
        }
        Text(stringResource(R.string.t5_rc_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    editing?.let { r ->
        RecipeEditor(r, onSave = { RecipeStore.save(ctx, it); version++; selected = it; editing = null }, onDelete = if (RecipeStore.user(ctx).any { it.id == r.id }) ({ RecipeStore.delete(ctx, r.id); version++; editing = null }) else null, onDismiss = { editing = null })
    }
}

/** Background jobs with live progress and the per-file log. */
@Composable
fun BatchScreen(nav: NavHostController) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val jobs = remember(version) { BatchJobs.all(ctx) }
    val infos by remember { WorkManager.getInstance(ctx).getWorkInfosByTagFlow(BatchJobs.TAG) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var open by remember { mutableStateOf<BatchJob?>(null) }
    LaunchedEffect(infos) { version++ }

    ToolScaffold(stringResource(R.string.t5_batch_jobs), nav) {
        if (jobs.isEmpty()) Text(stringResource(R.string.t5_batch_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        jobs.forEach { j ->
            val info = infos.firstOrNull { it.id.toString() == j.workId }
            val done = info?.progress?.getInt(BatchWorker.KEY_DONE, -1)?.takeIf { it >= 0 } ?: info?.outputData?.getInt(BatchWorker.KEY_DONE, 0) ?: 0
            val state = info?.state
            T5Card(j.title, Icons.Default.Schedule) {
                Text(DocumentRepository.formatDate(j.created) + " · " + stringResource(R.string.t5_files_n, j.files.size), style = MaterialTheme.typography.bodySmall)
                val (txt, color) = when (state) {
                    WorkInfo.State.RUNNING -> stringResource(R.string.t5_job_running) to T5Orange
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> stringResource(R.string.t5_job_queued) to T5Grey
                    WorkInfo.State.SUCCEEDED -> stringResource(R.string.t5_job_done) to T5Green
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> stringResource(R.string.t5_job_failed) to T5Red
                    null -> stringResource(R.string.t5_job_done) to T5Grey
                }
                StatusPill(txt, color)
                if (state == WorkInfo.State.RUNNING) LinearProgressIndicator(progress = { done.toFloat() / j.files.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { open = j }) { Text(stringResource(R.string.t5_job_log)) }
                    if (state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED) TextButton(onClick = { runCatching { WorkManager.getInstance(ctx).cancelWorkById(UUID.fromString(j.workId)) } }) { Text(stringResource(R.string.cancel)) }
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
                            supportingContent = { Text(if (e.ok) File(e.output ?: "").name + " · " + (e.millis / 1000) + " s" else e.message, maxLines = 2) },
                            modifier = Modifier.clickable(enabled = e.ok && e.output != null) { e.output?.let { p -> open = null; openPdf(nav, File(p)) } },
                        )
                    }
                }
            },
            confirmButton = {
                val outs = log.mapNotNull { it.output?.let(::File) }.filter { it.exists() }
                TextButton(onClick = { shareFiles(ctx, app.documents, outs, "application/pdf") }, enabled = outs.isNotEmpty()) { Text(stringResource(R.string.share)) }
            },
            dismissButton = { TextButton(onClick = { open = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

