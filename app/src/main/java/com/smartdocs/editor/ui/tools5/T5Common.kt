package com.smartdocs.editor.ui.tools5

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.ui.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Long-running job with a progress fraction, cancellation and a result (one or many files).
 *
 * Convert audit B01 / C13 / A-H8: only one job runs at a time — a cancelled run that is still unwinding (a library call
 * that cannot be interrupted) is awaited before the next one starts, so two heavy conversions never run side by side;
 * whatever a cancelled run still produces is discarded; errors are shown as localised text ([errorText]), never as raw
 * English messages or class names.
 */
class T5Job(private val scope: CoroutineScope, private val errorText: (Throwable) -> String = { it.message ?: "" }) {
    var running by mutableStateOf(false); private set
    /** A cancelled run is still finishing (the next one waits for it). */
    var finishing by mutableStateOf(false); private set
    var message by mutableStateOf("")
    var progress by mutableStateOf<Float?>(null)
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<File?>(null)
    var results by mutableStateOf<List<File>>(emptyList())
    var info by mutableStateOf<String?>(null)
    private var job: Job? = null
    private var generation = 0

    fun step(done: Int, total: Int) { progress = if (total > 0) done.toFloat() / total else null }

    fun launch(msg: String, block: suspend T5Job.() -> Unit) {
        if (running) return
        running = true; message = msg; progress = null; error = null; info = null
        val gen = ++generation
        val previous = job
        job = scope.launch {
            try {
                previous?.join()   // one conversion at a time: a cancelled one finishes (and cleans up) first
                if (gen != generation) return@launch
                block()
            }
            catch (e: kotlinx.coroutines.CancellationException) { }
            catch (e: Throwable) {   // OutOfMemoryError, NoClassDefFoundError, StackOverflowError… must not kill the app
                android.util.Log.w("T5Job", "job failed", e)
                if (gen == generation) error = runCatching { errorText(e) }.getOrNull()?.ifBlank { null } ?: e.javaClass.simpleName
            }
            finally { if (gen == generation) running = false }
        }
    }

    /** Cancels the work and closes the progress dialog at once, even if the work only notices later
     *  (non-cancellable library calls); anything the cancelled run still produces is discarded. */
    fun cancel() {
        val j = job ?: return
        val g = ++generation
        j.cancel()
        running = false; progress = null
        if (j.isActive) finishing = true
        j.invokeOnCompletion { scope.launch { if (generation == g) { result = null; results = emptyList(); info = null }; finishing = false } }   // a newer run's results stay
    }
}

/** A job whose errors are shown in the app language ([com.smartdocs.editor.ui.convert6.Cv6ErrorText]). */
@Composable
fun rememberT5Job(): T5Job {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current.applicationContext
    return remember { T5Job(scope) { e -> com.smartdocs.editor.ui.convert6.Cv6ErrorText.of(ctx, e) } }
}

/** Progress dialog with percent and a cancel button, result sheet / list and error snackbar for a [T5Job]. */
@Composable
fun T5JobUi(job: T5Job, nav: NavHostController, snackbar: SnackbarHostState, mime: String = "application/pdf") {
    LaunchedEffect(job.error) { job.error?.let { snackbar.showSnackbar(it); job.error = null } }
    if (job.running) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(job.message.ifBlank { stringResource(R.string.processing) }) },
            text = {
                Column {
                    val p = job.progress
                    if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                        LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.End).padding(top = 6.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { job.cancel() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    job.result?.let { f -> com.smartdocs.editor.ui.common.ResultSheet(f, nav, onDismiss = { job.result = null }, extraInfo = job.info) }
}

/** Picker for any document type; copies each pick into the app's documents folder. */
@Composable
fun rememberAnyFilePicker(multiple: Boolean, mimes: Array<String>, onPicked: (List<File>) -> Unit): () -> Unit {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { runCatching { app.documents.localFile(uri) }.getOrNull()?.let { onPicked(listOf(it)) } }
    }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { onPicked(uris.mapNotNull { runCatching { app.documents.localFile(it) }.getOrNull() }) }
    }
    return { if (multiple) multi.launch(mimes) else single.launch(mimes) }
}

fun t5Today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

fun fileOf(uri: String?): File? = uri?.let { runCatching { File(Uri.parse(it).path!!) }.getOrNull()?.takeIf { f -> f.exists() } }

fun openPdf(nav: NavHostController, f: File) = nav.navigate(Routes.reader(Uri.fromFile(f).toString()))

fun shareFiles(ctx: Context, docs: DocumentRepository, files: List<File>, mime: String) {
    runCatching { ctx.startActivity(Intent.createChooser(docs.shareIntent(files, mime), null)) }
}

/** Titled card section. */
@Composable
fun T5Card(title: String? = null, icon: ImageVector? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)) }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/** A switch with a label (and optional explanation) that is one accessible control. */
@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

/** List of picked files with move up / down / remove (used by the combiner, batch and portfolio screens). */
@Composable
fun FileListEditor(files: List<File>, onChange: (List<File>) -> Unit, titles: Map<File, String> = emptyMap()) {
    val moveUp = stringResource(R.string.t5_move_up); val moveDown = stringResource(R.string.t5_move_down); val remove = stringResource(R.string.t5_remove)
    Column {
        files.forEachIndexed { i, f ->
            ListItem(
                leadingContent = { Icon(if (f.extension.equals("pdf", true)) Icons.Default.PictureAsPdf else Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(titles[f] ?: f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(DocumentRepository.formatSize(f.length()), style = MaterialTheme.typography.bodySmall) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { onChange(files.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, moveUp) }
                        IconButton(onClick = { onChange(files.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < files.size - 1) { Icon(Icons.Default.KeyboardArrowDown, moveDown) }
                        IconButton(onClick = { onChange(files - f) }) { Icon(Icons.Default.Close, remove) }
                    }
                },
            )
        }
    }
}

/** Coloured status pill with a spoken label. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50), modifier = modifier.semantics { contentDescription = text }) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

val T5Green = Color(0xFF2E7D32)
val T5Red = Color(0xFFC62828)
val T5Orange = Color(0xFFEF6C00)
val T5Grey = Color(0xFF757575)

/** Run [block] on IO and return to the caller's context. */
suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
