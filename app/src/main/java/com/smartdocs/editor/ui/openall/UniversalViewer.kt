package com.smartdocs.editor.ui.openall

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.openall.FType
import com.smartdocs.editor.core.openall.FileSniffer
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.ResultSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A file the viewer has: local copy (or null for streamed media), its type and the uri it came from. */
class ViewFile(val file: File?, val type: FType, val uri: Uri, val name: String, val depth: Int)

/** One extra menu entry a viewer adds to the shared top bar. */
class ViewerAction(val label: Int, val icon: ImageVector, val onClick: () -> Unit)

private sealed interface VLoad {
    data object Loading : VLoad
    class Ready(val v: ViewFile) : VLoad
    class Failed(val message: String?) : VLoad
}

/**
 * The universal in-app viewer (open-all): resolves the file (importing content:// documents, streaming media from
 * its source), detects its type by magic bytes and shows the matching viewer. Errors stay inside the app.
 */
@Composable
fun UniversalViewer(nav: NavHostController, uri: String, depth: Int) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    var state by remember(uri) { mutableStateOf<VLoad>(VLoad.Loading) }
    LaunchedEffect(uri) {
        state = withContext(Dispatchers.IO) {
            runCatching<VLoad> {
                val u = Uri.parse(uri)
                if (u.scheme == "content" && OpenAll.isStreamedMedia(ctx, u)) {
                    val name = OpenAll.displayName(ctx, u) ?: "media"
                    val t = FileSniffer.byMime(ctx.contentResolver.getType(u)).takeIf { it.isMedia } ?: FileSniffer.byExtension(name)
                    return@runCatching VLoad.Ready(ViewFile(null, t, u, name, depth))
                }
                val f = if (u.scheme == "content") app.documents.localFile(u) else File(u.path ?: "")
                if (!f.isFile) return@runCatching VLoad.Failed(ctx.getString(R.string.oa_missing))
                val t = FileSniffer.of(f)
                if (!OpenAll.isTemporary(ctx, f)) runCatching { app.documents.touchRecent(f) }
                VLoad.Ready(ViewFile(f, t, Uri.fromFile(f), f.name, depth))
            }.getOrElse { VLoad.Failed(it.message ?: it.toString()) }
        }
    }
    when (val s = state) {
        VLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is VLoad.Failed -> ViewerScaffold(nav, null, stringResource(R.string.oa_open_failed)) { ErrorPane(s.message) }
        is VLoad.Ready -> ViewerFor(nav, s.v)
    }
}

@Composable
private fun ViewerFor(nav: NavHostController, v: ViewFile) {
    val f = v.file
    if (f == null) { MediaViewer(nav, v); return }
    when (v.type) {
        FType.TEXT, FType.CODE, FType.CSV -> if (v.type == FType.CSV && f.length() <= 20L * 1024 * 1024) CsvViewer(nav, v) else TextViewer(nav, v)
        FType.MARKDOWN, FType.HTML, FType.MHT, FType.EML, FType.MSG, FType.FB2, FType.MOBI,
        FType.DOC, FType.RTF, FType.ODT, FType.XLS, FType.ODS, FType.PPT, FType.ODP, FType.DOCX, FType.XLSX, FType.PPTX -> WebDocViewer(nav, v)
        FType.EPUB -> EpubViewer(nav, v)
        FType.IMAGE, FType.TIFF, FType.HEIF, FType.AVIF, FType.RAW, FType.SVG -> ImageViewer(nav, v)
        FType.CBZ, FType.CB7 -> ComicViewer(nav, v)
        FType.ZIP, FType.SEVENZ, FType.TAR, FType.GZIP, FType.BZIP2, FType.XZ -> ArchiveViewer(nav, v)
        FType.AUDIO, FType.VIDEO -> MediaViewer(nav, v)
        FType.FONT, FType.WOFF -> FontViewer(nav, v)
        FType.DXF -> DxfViewer(nav, v)
        FType.XPS -> XpsViewer(nav, v)
        FType.VCF, FType.ICS -> CardsViewer(nav, v)
        FType.IWORK -> FileInfoScreen(nav, v, R.string.oa_why_iwork, preview = true)
        FType.DJVU -> FileInfoScreen(nav, v, R.string.oa_why_djvu)
        FType.CHM -> FileInfoScreen(nav, v, R.string.oa_why_chm)
        FType.RAR, FType.CBR -> FileInfoScreen(nav, v, R.string.oa_why_rar)
        FType.DWG -> FileInfoScreen(nav, v, R.string.oa_why_dwg)
        FType.WOFF2 -> FileInfoScreen(nav, v, R.string.oa_why_woff2)
        FType.PDF -> com.smartdocs.editor.ui.reader.ReaderScreen(nav, uri = v.uri.toString(), startPage = -1)
        FType.FDF, FType.UNKNOWN -> FileInfoScreen(nav, v, null)
    }
}

/** Error text inside a viewer (never a crash). */
@Composable
fun ErrorPane(message: String?, title: Int = R.string.oa_open_failed) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.ErrorOutline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.oa_open_failed_text), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        message?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    }
}

@Composable
fun CenteredProgress() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

/**
 * Shared chrome of every viewer: back, title, and a menu with Share / Open with / Convert to PDF / File info plus the
 * viewer's own [actions]. [convert] = null hides "Convert to PDF"; otherwise it writes the PDF to the given file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScaffold(
    nav: NavHostController,
    v: ViewFile?,
    title: String,
    actions: List<ViewerAction> = emptyList(),
    topIcons: @Composable RowScope.() -> Unit = {},
    convert: (suspend (File) -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    onBack: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pdf by remember { mutableStateOf<File?>(null) }
    var info by remember { mutableStateOf(false) }
    val back: () -> Unit = onBack ?: { nav.popBackStack() }
    if (onBack != null) BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.oa_back)) } },
                actions = {
                    topIcons()
                    if (v != null) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.oa_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            actions.forEach { a -> DropdownMenuItem({ Text(stringResource(a.label)) }, { menu = false; a.onClick() }, leadingIcon = { Icon(a.icon, null) }) }
                            val f = v.file
                            if (convert != null && f != null) DropdownMenuItem({ Text(stringResource(R.string.oa_convert_pdf)) }, {
                                menu = false; busy = true
                                scope.launch {
                                    val base = if (OpenAll.isTemporary(ctx, f)) f.nameWithoutExtension.substringAfter('_') else f.nameWithoutExtension
                                    val out = app.documents.uniqueFile(app.documents.docsDir, base.ifBlank { "document" })
                                    val r = runCatching { convert(out); out }
                                    busy = false
                                    r.onSuccess { pdf = it }.onFailure { out.delete(); android.widget.Toast.makeText(ctx, ctx.getString(R.string.oa_convert_failed, it.message ?: it.javaClass.simpleName), android.widget.Toast.LENGTH_LONG).show() }
                                }
                            }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) })
                            if (f != null) {
                                DropdownMenuItem({ Text(stringResource(R.string.oa_share)) }, { menu = false; OpenAll.share(ctx, app, f, v.type) }, leadingIcon = { Icon(Icons.Default.Share, null) })
                                DropdownMenuItem({ Text(stringResource(R.string.oa_open_with)) }, { menu = false; OpenAll.openWith(ctx, app, f, v.type) }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) })
                            } else DropdownMenuItem({ Text(stringResource(R.string.oa_open_with)) }, { menu = false; OpenAll.openWithUri(ctx, v.uri, ctx.contentResolver.getType(v.uri) ?: "*/*") }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) })
                            DropdownMenuItem({ Text(stringResource(R.string.oa_file_info)) }, { menu = false; info = true }, leadingIcon = { Icon(Icons.Default.Info, null) })
                        }
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { pad -> content(pad) }
    if (busy) ProgressDialog(stringResource(R.string.oa_converting))
    pdf?.let { ResultSheet(it, nav, onDismiss = { pdf = null }) }
    if (info && v != null) FileInfoDialog(v) { info = false }
}
