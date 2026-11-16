package com.smartdocs.editor.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.R
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.core.data.RecentDoc
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lazily rendered first-page thumbnails (~240px wide JPG) cached in cacheDir/thumbs.
 * The cache name includes size + mtime, so an edited document gets a fresh thumbnail.
 */
object Thumbnails {
    private const val WIDTH = 240
    private val gate = Semaphore(2)
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Files that could not be rendered (encrypted / broken) in this process: don't retry on every recomposition. */
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun dir(ctx: Context) = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
    private fun prefix(src: File) = Integer.toHexString(src.absolutePath.hashCode())
    fun fileFor(ctx: Context, src: File) = File(dir(ctx), "${prefix(src)}_${src.length()}_${src.lastModified()}.jpg")

    /** Cached thumbnail or null; never blocks. */
    fun cached(ctx: Context, src: File): File? = fileFor(ctx, src).takeIf { it.exists() }

    /** Render (if needed) and return the thumbnail file, or null for non-PDF / unreadable / encrypted files. */
    suspend fun render(ctx: Context, src: File): File? = withContext(Dispatchers.IO) {
        if (!src.isFile || !src.extension.equals("pdf", true)) return@withContext null
        val out = fileFor(ctx, src)
        if (out.exists()) return@withContext out
        if (out.absolutePath in failed) return@withContext null
        if (!inFlight.add(out.absolutePath)) return@withContext null
        try {
            gate.withPermit {
                val s = PdfSession.open(ctx.applicationContext, src)
                try {
                    if (s.pageCount < 1) return@withPermit null
                    val (w, h) = s.pageSize(0)
                    val hp = (WIDTH * (h / w.coerceAtLeast(1f))).toInt().coerceIn(1, WIDTH * 3)
                    val bmp = s.render(0, WIDTH, hp, nightMode = false, annotations = true)
                    val tmp = File(out.parentFile, out.name + ".tmp")
                    tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
                    // drop stale thumbnails of the same document
                    val p = prefix(src) + "_"
                    out.parentFile?.listFiles()?.forEach { if (it.name.startsWith(p) && it.name != out.name) it.delete() }
                    out
                } finally { runCatching { s.close() } }
            }
        } catch (_: Throwable) {
            failed += out.absolutePath
            null
        } finally {
            inFlight.remove(out.absolutePath)
        }
    }
}

/** Ensure a recent document has a thumbnail stored in the database (off the main thread). */
@Composable
fun rememberDocThumb(d: RecentDoc): String? {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val stored = d.thumbPath?.takeIf { it.endsWith(".jpg") && File(it).exists() }
    var path by remember(d.uri, stored) { mutableStateOf(stored) }
    LaunchedEffect(d.uri, d.size, stored) {
        val src = runCatching { File(Uri.parse(d.uri).path ?: "") }.getOrNull() ?: return@LaunchedEffect
        if (!src.exists()) return@LaunchedEffect
        val fresh = Thumbnails.fileFor(ctx, src)
        if (stored == fresh.absolutePath) return@LaunchedEffect
        // open-all: pictures, SVG and videos get a real thumbnail too
        val t = Thumbnails.render(ctx, src) ?: com.smartdocs.editor.ui.openall.OpenAllThumbs.render(ctx, src) ?: return@LaunchedEffect
        path = t.absolutePath
        runCatching { app.documents.setThumb(src, t) }
    }
    return path
}

/** Thumbnail for a plain local file (Files tab); PDFs render lazily, others show a type tile. */
@Composable
fun rememberFileThumb(f: File): String? {
    val ctx = LocalContext.current
    var path by remember(f.absolutePath, f.lastModified()) {
        mutableStateOf(if (f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")) f.absolutePath else Thumbnails.cached(ctx, f)?.absolutePath)
    }
    LaunchedEffect(f.absolutePath, f.lastModified()) {
        if (path == null && f.isFile && f.extension.equals("pdf", true)) path = Thumbnails.render(ctx, f)?.absolutePath
        // open-all: GIF / HEIC / TIFF / SVG / RAW pictures and video frames
        if (path == null && f.isFile) path = com.smartdocs.editor.ui.openall.OpenAllThumbs.render(ctx, f)?.absolutePath
    }
    return path
}

/** Page-like thumbnail frame: image when available, otherwise a colored type tile. */
@Composable
fun ThumbBox(path: String?, w: Dp, h: Dp, kind: String = "pdf", modifier: Modifier = Modifier, file: File? = null) {
    val ctx = LocalContext.current
    val revision = CustomThumbnails.version
    val custom = remember(file, revision) { file?.let { CustomThumbnails.choice(ctx, it) } }
    val shown = if (custom == null) path else custom.takeIf { it.isNotEmpty() }
    var editing by remember(file) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val tiles = Brand.tiles
    val tile = when (kind) { "folder" -> tiles.amber; "image", "video" -> tiles.purple; "pdf" -> tiles.rose; "sheet", "audio" -> tiles.green; "slides", "archive" -> tiles.amber; else -> tiles.blue }
    Box(
        modifier.then(if (w > 0.dp) Modifier.size(w, h) else Modifier).clip(shape)
            .background(if (shown != null) MaterialTheme.colorScheme.surfaceContainerLowest else tile.container)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null && File(shown).exists()) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(File(shown)).memoryCacheKey(shown).crossfade(true).build(),
                contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
        } else {
            val icon = when (kind) { "folder" -> Icons.Default.Folder; "image" -> Icons.Default.Image; "pdf" -> Icons.Default.PictureAsPdf; else -> com.smartdocs.editor.ui.openall.OpenAllThumbs.icon(kind) }
            Icon(icon, null, Modifier.size(if (w > 0.dp) (minOf(w.value, h.value) * 0.45f).dp else 36.dp), tint = tile.content)
        }
        if (file != null && file.isFile) IconButton(onClick = { editing = true }, modifier = Modifier.align(Alignment.BottomEnd).size(28.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))) {
            Icon(Icons.Default.Edit, stringResource(R.string.thumb_edit), Modifier.size(16.dp))
        }
    }
    if (editing && file != null) ThumbnailEditor(file) { editing = false }
}

fun kindOf(f: File): String = if (f.isDirectory) "folder" else kindOfName(f.name)

/** open-all: tile kind of a file name ("pdf", "image", "video", "audio", "sheet", "slides", "archive", "book", …). */
fun kindOfName(name: String): String = com.smartdocs.editor.ui.openall.OpenAllThumbs.kind(com.smartdocs.editor.core.openall.FileSniffer.byExtension(name))
