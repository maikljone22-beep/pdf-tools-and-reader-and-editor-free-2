package com.smartdocs.editor.ui.tools

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.ResultSheet
import com.smartdocs.editor.ui.common.SectionTitle
import com.smartdocs.editor.ui.common.ToolRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Preselected input file from a `file://` URI passed to the tool route. */
@Composable
fun rememberInputFile(uri: String?): MutableState<File?> = remember(uri) {
    mutableStateOf(uri?.let { u -> runCatching { File(Uri.parse(u).path!!) }.getOrNull()?.takeIf { it.exists() } })
}

/** Base name for an output derived from [src]. */
fun outName(src: File?, suffix: String): String = (src?.nameWithoutExtension ?: "document") + "_" + suffix

/**
 * Common chrome for every tool screen: top bar with back, scrollable content column,
 * progress dialog while [runner] is working, result sheet on success and an error snackbar.
 */
@Composable
fun ToolScaffold(
    title: String,
    nav: NavHostController,
    runner: ToolRunner? = null,
    resultExtra: String? = null,
    scrollable: Boolean = true,
    snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = snackbar ?: remember { SnackbarHostState() }
    LaunchedEffect(runner?.error) {
        val e = runner?.error ?: return@LaunchedEffect
        host.showSnackbar(e)
        runner.error = null
    }
    Scaffold(
        topBar = { AppTopBar(title, onBack = { nav.popBackStack() }, actions = actions) },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        val base = Modifier.padding(pad).fillMaxSize()
        Column(
            (if (scrollable) base.verticalScroll(rememberScrollState()) else base).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
    if (runner != null) {
        if (runner.running) ProgressDialog(runner.message.ifBlank { stringResource(R.string.processing) })
        runner.result?.let { f -> ResultSheet(f, nav, onDismiss = { runner.result = null }, extraInfo = resultExtra) }
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().height(52.dp), enabled = enabled) {
        if (icon != null) { Icon(icon, null); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** Single-choice chips row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) },
                leadingIcon = if (o == selected) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null)
        }
    }
}

/** Colour swatches (RGB ints without alpha). */
@Composable
fun ColorChips(colors: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            val sel = c == selected
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or c))
                    .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onSelect(c) },
                contentAlignment = Alignment.Center,
            ) { if (sel) Icon(Icons.Default.Check, null, tint = if (isLight(c)) Color.Black else Color.White) }
        }
    }
}

private fun isLight(rgb: Int): Boolean {
    val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
    return (r * 299 + g * 587 + b * 114) / 1000 > 140
}

/** Numeric text field with digit-only filtering. */
@Composable
fun NumberField(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, maxLen: Int = 4) {
    OutlinedTextField(
        value, { onChange(it.filter { c -> c.isDigit() }.take(maxLen)) }, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
    )
}

/** Shows files produced by a multi-output tool with open / share actions. */
@Composable
fun ResultsList(files: List<File>, nav: NavHostController, mime: String) {
    if (files.isEmpty()) return
    val app = LocalApp.current
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(bottom = 8.dp)) {
            SectionTitle(stringResource(R.string.tl_results_count, files.size)) {
                TextButton(onClick = { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(files, mime), null)) }) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.tl_share_all))
                }
            }
            files.forEach { f ->
                Row(Modifier.fillMaxWidth().clickable { openResult(nav, ctx, app.documents, f) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (f.extension.lowercase() == "pdf") Icons.Default.PictureAsPdf else Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(DocumentRepository.formatSize(f.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(f), mime), null)) }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                }
            }
        }
    }
}

private fun openResult(nav: NavHostController, ctx: android.content.Context, docs: DocumentRepository, f: File) {
    if (f.extension.equals("pdf", true)) nav.navigate(Routes.reader(Uri.fromFile(f).toString()))
    else runCatching {
        val mime = when (f.extension.lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "txt" -> "text/plain"; "html" -> "text/html"; else -> "*/*" }
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(docs.shareUri(f), mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
    }
}

/** Picker for plain-text files; copies the chosen document into a temp file. */
@Composable
fun rememberTextFilePicker(onPicked: (File) -> Unit): () -> Unit {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val name = app.documents.displayName(uri)?.substringBeforeLast('.') ?: "text"
                    val t = app.documents.newTemp(name.replace(Regex("""[\\/:*?"<>|]"""), "_"), "txt")
                    ctx.contentResolver.openInputStream(uri)?.use { s -> t.outputStream().use { s.copyTo(it) } } ?: error("cannot open")
                    t
                }.getOrNull()
            }
            if (f != null) onPicked(f)
        }
    }
    return { launcher.launch(arrayOf("text/plain", "text/*")) }
}

/** Simple placeholder for ids without a screen yet. */
@Composable
fun ComingSoonScreen(nav: NavHostController, id: String) {
    ToolScaffold(id, nav) {
        com.smartdocs.editor.ui.common.EmptyState(Icons.Default.Construction, stringResource(R.string.tl_coming_soon))
    }
}
