package com.smartdocs.editor.ui.templates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.templates.TemplateDefaults
import com.smartdocs.editor.core.templates.TemplateInput
import com.smartdocs.editor.core.templates.TemplateKind
import com.smartdocs.editor.core.templates.TemplateRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Renders template previews (first page) through PdfSession; gallery thumbnails are cached as PNG in cacheDir. */
object TemplateThumbs {
    private const val VERSION = 1
    private val lock = Mutex()
    private val memory = HashMap<String, Bitmap>()

    private fun dir(ctx: Context) = File(ctx.cacheDir, "tpl_thumbs").apply { mkdirs() }

    /** Render page [page] of a template built from [input] at [widthPx]. */
    suspend fun render(ctx: Context, kind: TemplateKind, input: TemplateInput, widthPx: Int, page: Int = 0): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            val pdf = File(ctx.cacheDir, "tpl_preview_${System.nanoTime()}.pdf")
            try {
                TemplateRenderer.render(ctx, kind, input, pdf)
                val session = PdfSession.open(ctx, pdf)
                try {
                    val idx = page.coerceIn(0, session.pageCount - 1)
                    val (w, h) = session.pageSize(idx)
                    val bmp = session.render(idx, widthPx, (widthPx * h / w).toInt().coerceAtLeast(1))
                    bmp.copy(Bitmap.Config.ARGB_8888, false)   // the session cache recycles its own bitmaps on close
                } finally { session.closeAsync() }
            } finally { pdf.delete() }
        }
    }

    /** Cached gallery thumbnail rendered from [TemplateDefaults.sample]. */
    suspend fun thumb(ctx: Context, kind: TemplateKind, widthPx: Int = 360): Bitmap? {
        val key = "${kind.id}_$VERSION"
        memory[key]?.let { return it }
        val file = File(dir(ctx), "$key.png")
        val cached = withContext(Dispatchers.IO) { if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null }
        if (cached != null) { memory[key] = cached; return cached }
        val bmp = runCatching { render(ctx, kind, TemplateDefaults.sample(kind), widthPx) }.getOrNull() ?: return null
        withContext(Dispatchers.IO) { runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
        memory[key] = bmp
        return bmp
    }
}

/** Gallery thumbnail with a placeholder while it is being generated. */
@Composable
fun TemplateThumb(kind: TemplateKind, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(kind) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(kind) { bmp = TemplateThumbs.thumb(ctx, kind) }
    val ratio = if (kind.landscapeDefault) 297f / 210f else 210f / 297f
    Box(modifier.fillMaxWidth().aspectRatio(ratio).background(Color.White), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(ratio), contentScale = ContentScale.Fit)
        else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.outline)
    }
}
