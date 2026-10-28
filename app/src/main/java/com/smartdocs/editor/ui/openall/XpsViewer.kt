package com.smartdocs.editor.ui.openall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.openall.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** An open XPS package: page part names and cached fonts / images. Thread-safe enough for one viewer. */
class XpsPackage(private val ctx: Context, f: File) : Closeable {
    private val zip = ZipFile(f)
    private val names: List<String> = zip.entries().asSequence().map { it.name }.toList()
    val pages: List<String> = XpsReader.pages({ entry(it) }, names)
    private val fonts = HashMap<String, Typeface?>()
    private val images = LruCache<String, Bitmap>(12)
    private val pageCache = LruCache<Int, XPage>(16)

    fun entry(p: String): ByteArray? {
        val e = zip.getEntry(p) ?: names.firstOrNull { it.equals(p, true) }?.let { zip.getEntry(it) } ?: return null
        if (e.size > 64L * 1024 * 1024) return null
        return zip.getInputStream(e).use { it.readBytes() }
    }

    @Synchronized fun page(i: Int): XPage = pageCache.get(i) ?: XpsReader.page(entry(pages[i]) ?: throw IllegalStateException("missing page"), pages[i]).also { pageCache.put(i, it) }

    @Synchronized fun font(uri: String?): Typeface? {
        uri ?: return null
        return fonts.getOrPut(uri) {
            runCatching {
                var b = entry(uri) ?: return@runCatching null
                if (uri.endsWith(".odttf", true)) b = XpsReader.deobfuscate(b, uri)
                val out = File(OpenAll.workDir(ctx, "xps_fonts"), Integer.toHexString(uri.hashCode()) + "_" + b.size + ".ttf")
                if (!out.exists()) out.writeBytes(b)
                Typeface.createFromFile(out)
            }.getOrNull()
        }
    }

    @Synchronized fun image(uri: String): Bitmap? = images.get(uri) ?: runCatching {
        val b = entry(uri) ?: return@runCatching null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 2048) s *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()?.also { images.put(uri, it) }

    override fun close() { runCatching { zip.close() } }
}

/** Draws XPS FixedPage elements on an Android canvas (screen bitmaps and vector PDF pages). */
object XpsRender {
    private fun path(g: XGeom): Path = Path().apply {
        fillType = if (g.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        for (c in g.cmds) when (c.op) {
            'M' -> moveTo(c.p[0], c.p[1]); 'L' -> lineTo(c.p[0], c.p[1])
            'C' -> cubicTo(c.p[0], c.p[1], c.p[2], c.p[3], c.p[4], c.p[5]); 'Q' -> quadTo(c.p[0], c.p[1], c.p[2], c.p[3])
            'Z' -> close()
        }
    }

    private fun argb(c: Long, alpha: Float): Int {
        val a = (((c ushr 24) and 0xFF) * alpha).toInt().coerceIn(0, 255)
        return (a shl 24) or (c and 0xFFFFFF).toInt()
    }

    private fun matrix(m: XMatrix) = android.graphics.Matrix().apply { setValues(floatArrayOf(m.a, m.c, m.e, m.b, m.d, m.f, 0f, 0f, 1f)) }

    private fun fill(c: Canvas, p: Path, b: XBrush, alpha: Float, pkg: XpsPackage, stroke: Float?) {
        when (b) {
            is XBrush.Solid -> c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb(b.argb, alpha)
                if (stroke != null) { style = Paint.Style.STROKE; strokeWidth = stroke; strokeJoin = Paint.Join.ROUND }
            })
            is XBrush.Image -> {
                val bmp = pkg.image(b.src) ?: return
                c.save(); c.clipPath(p)
                b.transform?.let { c.concat(matrix(it)) }
                val vp = b.viewport
                c.drawBitmap(bmp, null, RectF(vp[0], vp[1], vp[0] + vp[2], vp[1] + vp[3]), Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = (alpha * 255).toInt().coerceIn(0, 255) })
                c.restore()
            }
        }
    }

    private fun el(c: Canvas, e: XEl, alpha0: Float, pkg: XpsPackage) {
        val alpha = alpha0 * e.opacity
        if (alpha <= 0.001f) return
        c.save()
        e.transform?.let { c.concat(matrix(it)) }
        e.clip?.let { c.clipPath(path(it)) }
        when (e) {
            is XCanvas -> e.children.forEach { el(c, it, alpha, pkg) }
            is XPathEl -> {
                val p = path(e.geom)
                e.fill?.let { fill(c, p, it, alpha, pkg, null) }
                e.stroke?.let { fill(c, p, it, alpha, pkg, e.strokeWidth) }
            }
            is XGlyphs -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                    textSize = e.size
                    typeface = pkg.font(e.font) ?: Typeface.DEFAULT
                    isFakeBoldText = e.bold
                    if (e.italic) textSkewX = -0.25f
                    color = argb(((e.fill as? XBrush.Solid)?.argb) ?: 0xFF000000, alpha)
                }
                val adv = e.advances
                val simple = adv != null && !e.rtl && e.text.none { it.code in 0x0590..0x08FF || it.code >= 0x0900 && it.code < 0x0E80 }
                if (simple && adv != null) {
                    var x = e.x
                    e.text.forEachIndexed { i, ch ->
                        c.drawText(ch.toString(), x, e.y, paint)
                        val a = adv.getOrNull(i)
                        x += if (a == null || a.isNaN()) paint.measureText(ch.toString()) else a
                    }
                } else {
                    if (e.rtl) paint.textAlign = Paint.Align.RIGHT
                    c.drawText(e.text, e.x, e.y, paint)
                }
            }
        }
        c.restore()
    }

    /** Draws page [p] scaled by [scale] (page units are 1/96 inch). */
    fun draw(c: Canvas, p: XPage, scale: Float, pkg: XpsPackage, background: Boolean = true) {
        c.save(); c.scale(scale, scale)
        if (background) c.drawRect(0f, 0f, p.width, p.height, Paint().apply { color = android.graphics.Color.WHITE })
        el(c, p.root, 1f, pkg)
        c.restore()
    }

    suspend fun toPdf(ctx: Context, f: File, out: File): File {
        val pkg = withContext(Dispatchers.IO) { XpsPackage(ctx, f) }
        try {
            val pages = withContext(Dispatchers.IO) { pkg.pages.indices.map { pkg.page(it) } }
            return canvasToPdf(out, pages.map { it.width * 0.75f to it.height * 0.75f }) { c, i -> draw(c, pages[i], 0.75f, pkg, background = false) }
        } finally { pkg.close() }
    }
}

/** XPS / OXPS viewer (open-all): pages drawn from the FixedPage markup with their embedded fonts and images. */
@Composable
fun XpsViewer(nav: NavHostController, v: ViewFile) {
    val ctx = LocalContext.current
    val f = v.file!!
    val pkg by produceState<Result<XpsPackage>?>(null, f) { value = withContext(Dispatchers.IO) { runCatching { XpsPackage(ctx, f) } } }
    DisposableEffect(pkg) { onDispose { pkg?.getOrNull()?.close() } }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.toInt().coerceIn(320, 2000)
    val lock = remember { Mutex() }
    ViewerScaffold(nav, v, v.name, convert = { out -> ViewerConvert.toPdf(ctx, v, out) }) { pad ->
        val p = pkg?.getOrNull()
        Box(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
            when {
                pkg == null -> CenteredProgress()
                p == null || p.pages.isEmpty() -> ErrorPane(pkg?.exceptionOrNull()?.let { if (it is UnsafeXmlException) ctx.getString(R.string.oa_xml_unsafe) else it.message })
                else -> ZoomBox(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(8.dp)) {
                        itemsIndexed(p.pages) { i, _ ->
                            val bmp by produceState<Result<Bitmap>?>(null, i) {
                                value = withContext(Dispatchers.Default) {
                                    lock.withLock {
                                        runCatching {
                                            val page = p.page(i)
                                            val s = widthPx / page.width.coerceAtLeast(1f)
                                            val b = Bitmap.createBitmap(widthPx, (page.height * s).toInt().coerceIn(1, 6000), Bitmap.Config.ARGB_8888)
                                            XpsRender.draw(Canvas(b), page, s, p)
                                            b
                                        }
                                    }
                                }
                            }
                            val b = bmp?.getOrNull()
                            if (b != null) Image(b.asImageBitmap(), stringResource(R.string.oa_page_of, i + 1, p.pages.size), Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height), contentScale = ContentScale.FillWidth)
                            else Box(Modifier.fillMaxWidth().aspectRatio(0.77f).background(Color.White), contentAlignment = Alignment.Center) {
                                if (bmp == null) CircularProgressIndicator() else Text(bmp?.exceptionOrNull()?.message ?: "", color = Color.Red)
                            }
                        }
                    }
                }
            }
        }
    }
}
