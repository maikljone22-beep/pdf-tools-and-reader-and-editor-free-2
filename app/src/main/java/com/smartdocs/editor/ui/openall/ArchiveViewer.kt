package com.smartdocs.editor.ui.openall

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.openall.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Message for an archive guard / error. */
fun archiveError(ctx: android.content.Context, e: Throwable?): String = when ((e as? ArchiveLimitException)?.reason) {
    "size" -> ctx.getString(R.string.oa_archive_limit_size, DocumentRepository.formatSize(ArcLimits().maxEntryBytes))
    "ratio" -> ctx.getString(R.string.oa_archive_limit_ratio)
    "entries" -> ctx.getString(R.string.oa_archive_limit_entries)
    "unsafe" -> ctx.getString(R.string.oa_archive_unsafe_entry)
    else -> if (e?.message?.contains("Invalid zip entry path", true) == true) ctx.getString(R.string.oa_why_zip_android) else e?.message ?: ""
}

/**
 * Archive browser (open-all): ZIP, 7z, TAR, TAR.GZ/BZ2/XZ, GZ/BZ2/XZ. Folders are browsed inside the archive; a tapped
 * entry is extracted alone (guarded against zip-slip and zip bombs) and opened in the in-app viewer. Nesting is
 * limited to [ArcLimits.maxNesting] levels.
 */
@Composable
fun ArchiveViewer(nav: NavHostController, v: ViewFile) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = v.file!!
    val fmt = remember(f) { ArchiveReader.formatOf(f, v.type) }
    val all by produceState<Result<List<ArcEntry>>?>(null, f) {
        value = withContext(Dispatchers.IO) { runCatching { ArchiveReader.list(f, fmt ?: throw IllegalStateException("format")) } }
    }
    var folder by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val dir = remember(f) { OpenAll.workDir(ctx, "arc_" + Integer.toHexString((f.absolutePath + f.length() + f.lastModified()).hashCode())) }
    fun open(e: ArcEntry) {
        if (e.isDir) { folder = e.path; return }
        if (e.unsafe) { Toast.makeText(ctx, R.string.oa_archive_unsafe_entry, Toast.LENGTH_LONG).show(); return }
        val nested = FileSniffer.byExtension(e.name).let { it.isArchive || it.isComic }
        if (nested && v.depth + 1 >= ArcLimits().maxNesting) { Toast.makeText(ctx, R.string.oa_archive_nesting, Toast.LENGTH_LONG).show(); return }
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ArchiveReader.extract(f, fmt!!, e, dir) } }
            busy = false
            r.onSuccess { nav.navigate(OpenAll.route(Uri.fromFile(it).toString(), v.depth + 1)) }
                .onFailure { Toast.makeText(ctx, archiveError(ctx, it), Toast.LENGTH_LONG).show() }
        }
    }
    val list = all?.getOrNull()
    val atRoot = folder.isEmpty()
    val goUp: () -> Unit = { folder = folder.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } }
    val actions = buildList {
        if (list != null) add(ViewerAction(R.string.oa_archive_extract_all, Icons.Default.Unarchive) {
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val target = com.smartdocs.editor.core.files.FileCopy.unzipTarget(File(app.documents.docsDir, f.nameWithoutExtension + ".zip"))
                        target.mkdirs()
                        var n = 0
                        for (e in list) {
                            if (e.isDir || e.unsafe) continue
                            val safe = ArchiveReader.safePath(e.path) ?: continue
                            val out = File(target, safe)
                            if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) continue
                            out.parentFile?.mkdirs()
                            val tmp = ArchiveReader.extract(f, fmt!!, e, OpenAll.workDir(ctx, "extract_all"))
                            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
                            n++
                        }
                        target to n
                    }
                }
                busy = false
                r.onSuccess { (t, n) -> Toast.makeText(ctx, ctx.getString(R.string.g6_unzip_done, n, t.name), Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(ctx, archiveError(ctx, it), Toast.LENGTH_LONG).show() }
            }
        })
    }
    ViewerScaffold(nav, v, if (atRoot) v.name else folder.trimEnd('/').substringAfterLast('/'), actions = actions, onBack = if (atRoot) null else goUp) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                all == null -> CenteredProgress()
                list == null -> ErrorPane(archiveError(ctx, all?.exceptionOrNull()))
                else -> {
                    val kids = remember(list, folder) { ArchiveReader.children(list, folder) }
                    Text(stringResource(R.string.oa_archive_items, kids.size) + (if (!atRoot) "  ·  /" + folder else ""), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (!atRoot) item {
                            ListItem(headlineContent = { Text("..") }, leadingContent = { Icon(Icons.Default.Folder, null) }, modifier = Modifier.clickable { goUp() })
                        }
                        items(kids, key = { it.path + it.index }) { e ->
                            val t = if (e.isDir) null else FileSniffer.byExtension(e.name)
                            ListItem(
                                headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    if (e.unsafe) Text(stringResource(R.string.oa_archive_unsafe_entry), color = MaterialTheme.colorScheme.error)
                                    else if (!e.isDir && e.size >= 0) Text(DocumentRepository.formatSize(e.size) + if (e.modified > 0) "  ·  " + DocumentRepository.formatDate(e.modified) else "")
                                },
                                leadingContent = { Icon(if (e.isDir) Icons.Default.Folder else iconFor(t ?: FType.UNKNOWN), null, tint = if (e.unsafe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable { open(e) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (busy) com.smartdocs.editor.ui.common.ProgressDialog(stringResource(R.string.oa_archive_extracting))
}

/**
 * Comic book reader (open-all): CBZ (ZIP) and CB7 (7z) pages in natural order, one page at a time with zoom; pages are
 * extracted lazily; right-to-left (manga) reading can be switched on.
 */
@Composable
fun ComicViewer(nav: NavHostController, v: ViewFile) {
    val ctx = LocalContext.current
    val f = v.file!!
    val prefs = remember { ctx.getSharedPreferences("openall_comic", android.content.Context.MODE_PRIVATE) }
    val fmt = remember(f) { ArchiveReader.formatOf(f, v.type) }
    val pages by produceState<Result<List<ArcEntry>>?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ArchiveReader.list(f, fmt ?: throw IllegalStateException("format")).filter { !it.isDir && !it.unsafe && FileSniffer.isImageName(it.path) && !it.name.startsWith(".") }
                    .sortedWith { a, b -> NaturalOrder.compare(a.path, b.path) }
            }
        }
    }
    val key = remember(f) { f.absolutePath + ":" + f.length() }
    var page by remember { mutableIntStateOf(prefs.getInt("$key:p", 0)) }
    var rtl by remember { mutableStateOf(prefs.getBoolean("rtl", false)) }
    LaunchedEffect(page) { prefs.edit().putInt("$key:p", page).apply() }
    LaunchedEffect(rtl) { prefs.edit().putBoolean("rtl", rtl).apply() }
    val dir = remember(f) { OpenAll.workDir(ctx, "comic_" + Integer.toHexString(key.hashCode())) }
    val list = pages?.getOrNull()
    val n = list?.size ?: 0
    val cur = page.coerceIn(0, (n - 1).coerceAtLeast(0))
    val bmp by produceState<Result<Bitmap?>?>(null, list, cur) {
        value = null
        val e = list?.getOrNull(cur) ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { ImageLoad.decode(ctx, ArchiveReader.extract(f, fmt!!, e, dir), 2560) } }
        // prefetch the next page
        list.getOrNull(cur + 1)?.let { nx -> withContext(Dispatchers.IO) { runCatching { ArchiveReader.extract(f, fmt!!, nx, dir) } } }
    }
    ViewerScaffold(nav, v, v.name, actions = listOf(ViewerAction(R.string.oa_comic_rtl, Icons.Default.SwapHoriz) { rtl = !rtl }),
        convert = { out -> ViewerConvert.toPdf(ctx, v, out) },
        bottomBar = {
            if (n > 1) CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { page = (cur - 1).coerceAtLeast(0) }, enabled = cur > 0) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.oa_prev)) }
                        Slider(value = cur.toFloat(), onValueChange = { page = it.toInt() }, valueRange = 0f..(n - 1).toFloat(), modifier = Modifier.weight(1f))
                        Text("${cur + 1}/$n", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { page = (cur + 1).coerceAtMost(n - 1) }, enabled = cur < n - 1) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.oa_next)) }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            val b = bmp?.getOrNull()
            when {
                pages == null -> CenteredProgress()
                list == null -> ErrorPane(archiveError(ctx, pages?.exceptionOrNull()))
                list.isEmpty() -> ErrorPane(ctx.getString(R.string.oa_comic_empty))
                bmp == null -> CenteredProgress()
                b == null -> ErrorPane(archiveError(ctx, bmp?.exceptionOrNull()))
                else -> CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    ZoomBox(Modifier.fillMaxSize(), 0f, cur, onSwipe = { d -> page = (cur + d).coerceIn(0, n - 1) }) {
                        Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                }
            }
        }
    }
}
