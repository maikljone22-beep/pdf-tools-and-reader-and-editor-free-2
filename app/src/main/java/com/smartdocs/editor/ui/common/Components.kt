package com.smartdocs.editor.ui.common

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.core.theme.TileColors
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import android.widget.Toast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.ui.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

/** Tile colors that [ToolCard] uses for its icon; screens can provide a category color. */
val LocalToolTile = staticCompositionLocalOf<TileColors?> { null }

/** Rounded colored square holding an icon (category tiles, quick actions, settings rows). */
@Composable
fun IconTile(icon: ImageVector, colors: TileColors, modifier: Modifier = Modifier, size: Dp = 44.dp, iconSize: Dp = 24.dp, contentDescription: String? = null) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(colors.container),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, Modifier.size(iconSize), tint = colors.content) }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(104.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

/** Empty state with a full-color vector illustration from res/drawable (ds_empty_*). */
@Composable
fun EmptyIllustration(@DrawableRes art: Int, title: String, subtitle: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(art), null, Modifier.width(168.dp).height(134.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

@Composable
fun ToolCard(icon: ImageVector, title: String, subtitle: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tile = LocalToolTile.current ?: Brand.tiles.blue
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconTile(icon, tile, size = 48.dp, iconSize = 26.dp)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
fun SectionTitle(text: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 6.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun ProgressDialog(message: String, progress: Float? = null) {
    Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp) {
            Column(Modifier.padding(24.dp).widthIn(min = 260.dp, max = 360.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(message, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).animateContentSize())
                }
                Spacer(Modifier.height(20.dp))
                val trackMod = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                if (progress == null) LinearProgressIndicator(trackMod, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                else {
                    val p by animateFloatAsState(progress.coerceIn(0f, 1f), label = "progress")
                    LinearProgressIndicator(progress = { p }, modifier = trackMod, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                    Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End).padding(top = 6.dp))
                }
            }
        }
    }
}

/** State holder for long-running tool operations with a result file. */
class ToolRunner(private val scope: CoroutineScope) {
    var running by mutableStateOf(false); private set
    var message by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<File?>(null)
    var results by mutableStateOf<List<File>>(emptyList())

    fun run(msg: String, block: suspend () -> File?) {
        if (running) return
        running = true; message = msg; error = null
        scope.launch {
            // Throwable: OutOfMemoryError / NoClassDefFoundError from PdfBox on big or odd files must show an error, not kill the app
            try { result = withContext(Dispatchers.IO) { block() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { error = e.message ?: e.toString() } finally { running = false }
        }
    }

    fun runMany(msg: String, block: suspend () -> List<File>) {
        if (running) return
        running = true; message = msg; error = null
        scope.launch {
            try { results = withContext(Dispatchers.IO) { block() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { error = e.message ?: e.toString() } finally { running = false }
        }
    }
}

@Composable
fun rememberToolRunner(): ToolRunner { val scope = rememberCoroutineScope(); return remember { ToolRunner(scope) } }

/** Bottom sheet shown after a tool produced a file: open / share / save elsewhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultSheet(file: File, nav: NavHostController, onDismiss: () -> Unit, extraInfo: String? = null) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val writeBack = rememberWriteBack()
    // a PDF result of a tool opened on a document can replace that document (the default, one tap)
    val source = LocalToolSource.current?.takeIf { it.exists() && file.extension.equals("pdf", true) && it.absolutePath != file.absolutePath }
    // the "save as" document type and the share mime follow the file's extension (txt/docx/png/zip results exist)
    val mime = remember(file.extension) { mimeFor(file.extension) }
    val contract = remember(mime) { ActivityResultContracts.CreateDocument(mime) }
    // audit DI-30: report the outcome, and remove the empty document CreateDocument made when the copy failed
    val saver = rememberLauncherForActivityResult(contract) { uri ->
        if (uri != null) scope.launch {
            val r = runCatching { app.documents.exportTo(file, uri) }
            if (r.isSuccess) Toast.makeText(ctx, ctx.getString(R.string.fx_saved_copy), Toast.LENGTH_SHORT).show()
            else {
                withContext(Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, uri) } }
                Toast.makeText(ctx, ctx.getString(R.string.fx_save_failed, r.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
    var confirmReplace by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    if (confirmReplace && source != null) AlertDialog(
        onDismissRequest = { confirmReplace = false },
        title = { Text(stringResource(R.string.fx_replace_title)) },
        text = { Text(stringResource(R.string.fx_replace_text, source.name)) },
        confirmButton = {
            TextButton(enabled = !replacing, onClick = {
                replacing = true
                scope.launch {
                    // audit UX-02: the old version is kept in the trash; a failure is shown and the document stays as it was
                    val r = runCatching { app.documents.replaceOriginal(source, file) }
                    replacing = false; confirmReplace = false
                    if (r.isFailure) {
                        Toast.makeText(ctx, ctx.getString(R.string.fx_save_failed, signedOrMessage(ctx, r.exceptionOrNull())), Toast.LENGTH_LONG).show()   // tab-view-core E-01
                        return@launch
                    }
                    Toast.makeText(ctx, ctx.getString(R.string.fx_replaced_kept_in_trash), Toast.LENGTH_LONG).show()
                    writeBack(source)
                    onDismiss()
                    // back to the document, which reloads itself because its file changed
                    if (!nav.popBackStack(Routes.READER, inclusive = false)) nav.navigate(Routes.reader(Uri.fromFile(source).toString()))
                }
            }) { Text(stringResource(R.string.fx_replace_confirm)) }
        },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.cancel)) } },
    )
    val naturalBreak = com.smartdocs.editor.core.ads.rememberNaturalBreak()   // share6: interstitial at a natural break (not on Open/Share)
    ModalBottomSheet(onDismissRequest = { onDismiss(); naturalBreak() }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.success), style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(if (file.extension.equals("pdf", true)) Icons.Default.PictureAsPdf else Icons.Default.InsertDriveFile, Brand.tiles.rose, size = 40.dp, iconSize = 22.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(DocumentRepository.formatSize(file.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (extraInfo != null) Text(extraInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
            Spacer(Modifier.height(20.dp))
            val btnMod = Modifier.fillMaxWidth().height(52.dp)
            if (source != null) {
                // audit UX-02: the safe choice (new file) is the default; replacing the document needs a confirmation
                Button(onClick = { onDismiss(); nav.navigate(Routes.reader(Uri.fromFile(file).toString())) }, btnMod, shape = PillShape) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.wb_open_as_new)) }
                FilledTonalButton(onClick = { confirmReplace = true }, Modifier.padding(top = 8.dp).fillMaxWidth().height(52.dp), shape = PillShape) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.wb_save_in_original)) }
            } else {
                Button(onClick = { onDismiss(); nav.navigate(Routes.reader(Uri.fromFile(file).toString())) }, btnMod, shape = PillShape) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.open)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(file), mime), null)) } }, Modifier.weight(1f).height(48.dp), shape = PillShape) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.share)) }
                OutlinedButton(onClick = { saver.launch(file.name) }, Modifier.weight(1f).height(48.dp), shape = PillShape) { Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.save_as)) }
            }
            // fc-settings: banner under the result actions (a natural pause); AdBanner renders nothing when ads are off, without consent or with Pro
            com.smartdocs.editor.core.ads.AdBanner(Modifier.padding(top = 16.dp))
        }
    }
}

/** Mime type for a result file by extension. */
fun mimeFor(ext: String): String = when (ext.lowercase()) {
    "pdf" -> "application/pdf"
    "txt" -> "text/plain"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "html", "htm" -> "text/html"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "zip" -> "application/zip"
    else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "*/*"
}

/** A row that opens the system document picker and returns a local file. */
@Composable
fun rememberPdfPicker(multiple: Boolean = false, mimes: Array<String> = arrayOf("application/pdf"), onPicked: (List<File>) -> Unit): () -> Unit {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // keep read+write access so edits can be saved back into the picked document itself
    fun persist(u: Uri) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        .onFailure { runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } } }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { persist(uri); scope.launch { runCatching { app.documents.localFile(uri) }.getOrNull()?.let { onPicked(listOf(it)) } } }
    }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { uris.forEach { persist(it) }; scope.launch { onPicked(uris.mapNotNull { runCatching { app.documents.localFile(it) }.getOrNull() }) } }
    }
    return { if (multiple) multi.launch(mimes) else single.launch(mimes) }   // office5: general "open" buttons pass the Office types too
}

@Composable
fun rememberImagePicker(multiple: Boolean = true, onPicked: (List<File>) -> Unit): () -> Unit {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(if (multiple) 50 else 2)) { uris ->
        if (uris.isNotEmpty()) scope.launch(Dispatchers.IO) {
            val files = uris.mapIndexedNotNull { i, u ->
                runCatching {
                    val f = app.documents.newTemp("img_$i", "jpg")
                    ctx.contentResolver.openInputStream(u)?.use { s -> f.outputStream().use { s.copyTo(it) } }; f
                }.getOrNull()
            }
            withContext(Dispatchers.Main) { onPicked(files) }
        }
    }
    return { launcher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
fun FilePickRow(file: File?, label: String, onPick: () -> Unit) {
    Surface(
        onClick = onPick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (file == null) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Default.PictureAsPdf, Brand.tiles.rose, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(file?.name ?: stringResource(R.string.open_file), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronLeft, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PageRangeField(value: String, onChange: (String) -> Unit) {
    // Arabic-Indic digits and «،» are accepted; anything else is flagged before converting (convert audit B17 / A-M12)
    val bad = !com.smartdocs.editor.core.pdf.PdfOps.rangeSyntaxOk(value)
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.page_range)) }, placeholder = { Text(stringResource(R.string.pages_hint)) }, singleLine = true, shape = RoundedCornerShape(16.dp),
        isError = bad, supportingText = if (bad) ({ Text(stringResource(R.string.cv6_err_range)) }) else null)
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, format: (Float) -> String = { "%.0f".format(it) }) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Surface(shape = PillShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
            }
        }
        Slider(value, onChange, valueRange = range)
    }
}
