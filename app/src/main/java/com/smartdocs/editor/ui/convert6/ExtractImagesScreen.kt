package com.smartdocs.editor.ui.convert6

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert6.Extracted
import com.smartdocs.editor.core.convert6.FileKind6
import com.smartdocs.editor.core.convert6.ImageExtract6
import com.smartdocs.editor.core.convert6.Kind6
import com.smartdocs.editor.core.convert6.NameMode
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.PageRangeField
import com.smartdocs.editor.ui.tools.ChoiceChips
import com.smartdocs.editor.ui.tools.FieldLabel
import com.smartdocs.editor.ui.tools.PrimaryButton
import com.smartdocs.editor.ui.tools5.T5JobUi
import com.smartdocs.editor.ui.tools5.fileOf
import com.smartdocs.editor.ui.tools5.rememberAnyFilePicker
import com.smartdocs.editor.ui.tools5.rememberT5Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val EXTRACT_KINDS = setOf(Kind6.PDF, Kind6.DOCX, Kind6.PPTX, Kind6.XLSX, Kind6.EPUB, Kind6.ODT, Kind6.ODP, Kind6.ODS)

/**
 * Tool `extract_images` («استخراج كل الصور»): every embedded picture at its original resolution, with naming by page,
 * by resource name or by nearby caption, thumbnails with selection and rename, and ZIP / folder / share / gallery output.
 */
@Composable
fun ExtractImagesScreen(nav: NavHostController, uri: String?) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var file by remember(uri) { mutableStateOf(fileOf(uri)?.takeIf { FileKind6.of(it) in EXTRACT_KINDS }) }
    var range by remember { mutableStateOf("") }
    var minSide by remember { mutableIntStateOf(32) }
    var mode by remember { mutableStateOf(NameMode.PAGE_INDEX) }
    var items by remember { mutableStateOf<List<Extracted>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var overrides by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var password by remember { mutableStateOf("") }
    val job = rememberT5Job()
    val pick = rememberAnyFilePicker(false, FileKind6.mimes(EXTRACT_KINDS)) { fs -> fs.firstOrNull()?.let { file = it; items = emptyList() } }
    val docName = file?.nameWithoutExtension ?: "images"
    val names = remember(items, mode, overrides, docName) { ImageExtract6.names(items, mode, docName, overrides) }
    fun chosen(): List<Pair<File, String>> = items.indices.filter { it in selected }.map { items[it].file to names[it] }

    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { t ->
        if (t != null) scope.launch {
            val n = Cv6Save.toTree(ctx, t, chosen())
            snackbar.showSnackbar(ctx.getString(R.string.cv6_ex_saved_n, n))
        }
    }

    fun extract() {
        val f = file ?: return
        val r = range; val m = minSide; val pw = password.ifBlank { null }
        job.launch(ctx.getString(R.string.cv6_ex_running)) {
            val work = File(app.documents.tempDir, "cv6_extract").also { it.deleteRecursively(); it.mkdirs() }
            val kind = FileKind6.of(f)
            val list = if (kind == Kind6.PDF) {
                val pages = if (r.isBlank()) emptyList() else PdfOps.parseRange(r, PdfOps.pageCount(f, pw))
                ImageExtract6.fromPdf(f, pw, pages, m, work) { d, t -> step(d, t) }
            } else ImageExtract6.fromPackage(f, kind, m, work)
            items = list; selected = list.indices.toSet(); overrides = emptyMap()
            if (list.isEmpty()) error = ctx.getString(R.string.cv6_ex_none)
        }
    }

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.cv6_t_extract_images), onBack = { nav.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (items.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val any = selected.isNotEmpty()
                    Button(enabled = any, onClick = {
                        job.launch(ctx.getString(R.string.processing)) {
                            result = Cv6Save.zip(chosen(), app.documents.uniqueFile(app.documents.docsDir, docName + "_images", "zip"))
                        }
                    }) { Icon(Icons.Default.FolderZip, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cv6_ex_zip)) }
                    OutlinedButton(enabled = any, onClick = { runCatching { tree.launch(null) } }) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cv6_ex_folder)) }
                    OutlinedButton(enabled = any, onClick = {
                        scope.launch {
                            val files = Cv6Save.named(chosen(), File(app.documents.tempDir, "cv6_share"))
                            runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(files, "image/*"), null)) }
                        }
                    }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.share)) }
                    OutlinedButton(enabled = any, onClick = {
                        scope.launch {
                            val list = chosen().filter { Cv6Save.galleryOk(it.first.extension) }
                            val n = Cv6Save.toGallery(ctx, list)
                            snackbar.showSnackbar(ctx.getString(R.string.cv6_ex_gallery_n, n))
                        }
                    }) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cv6_ex_gallery)) }
                }
            }
        },
    ) { pad ->
        LazyVerticalGrid(
            GridCells.Adaptive(140.dp), Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "opts") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilePickRow(file, stringResource(R.string.tl_input_file), pick)
                    if (file?.let { FileKind6.of(it) } == Kind6.PDF) {
                        PageRangeField(range) { range = it }
                        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.cv_password)) }, singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), shape = RoundedCornerShape(16.dp))
                    }
                    FieldLabel(stringResource(R.string.cv6_ex_min))
                    ChoiceChips(listOf(0, 32, 64, 128), minSide, { if (it == 0) stringResource(R.string.cv6_ex_min_all) else stringResource(R.string.cv6_ex_min_px, it) }) { minSide = it }
                    FieldLabel(stringResource(R.string.cv6_ex_naming))
                    ChoiceChips(NameMode.entries, mode, { when (it) { NameMode.PAGE_INDEX -> stringResource(R.string.cv6_ex_name_page); NameMode.RESOURCE -> stringResource(R.string.cv6_ex_name_res); NameMode.CAPTION -> stringResource(R.string.cv6_ex_name_caption) } }) { mode = it }
                    Text(stringResource(R.string.cv6_ex_example, if (names.isNotEmpty()) names.first() else ImageExtract6.names(listOf(Extracted(File("x.png"), 3, 1, "Im7", ctx.getString(R.string.cv6_ex_sample_caption), 0, 0, "png")), mode, docName).first()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryButton(stringResource(R.string.cv6_ex_extract), enabled = file != null, icon = Icons.Default.Collections) { extract() }
                    if (items.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.cv6_ex_found, items.size, selected.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selected = items.indices.toSet() }) { Text(stringResource(R.string.cv6_ex_all)) }
                        TextButton(onClick = { selected = emptySet() }) { Text(stringResource(R.string.cv6_ex_none_sel)) }
                    }
                }
            }
            itemsIndexed(items, key = { i, it -> it.file.absolutePath + i }) { i, it ->
                ImageCell(it, names.getOrElse(i) { "" }, i in selected, onToggle = { selected = if (i in selected) selected - i else selected + i }, onRename = { renaming = i })
            }
        }
    }
    renaming?.let { i ->
        var text by remember(i) { mutableStateOf(names.getOrElse(i) { "" }.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.cv6_ex_rename)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { overrides = overrides + (i to text); renaming = null }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    T5JobUi(job, nav, snackbar, mime = "application/zip")
}

@Composable
private fun ImageCell(e: Extracted, name: String, checked: Boolean, onToggle: () -> Unit, onRename: () -> Unit) {
    val thumb by produceState<android.graphics.Bitmap?>(null, e.file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(e.file.absolutePath, o)
                var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 320) s *= 2
                BitmapFactory.decodeFile(e.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull()
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (checked) 2.dp else 1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                val b = thumb
                if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Icon(Icons.Default.Image, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Checkbox(checked, { onToggle() }, Modifier.align(Alignment.TopEnd))
                Text(e.ext.uppercase(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp))
            }
            Column(Modifier.clickable(onClick = onRename).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Edit, stringResource(R.string.cv6_ex_rename), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text((if (e.width > 0) "${e.width}×${e.height} · " else "") + DocumentRepository.formatSize(e.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
