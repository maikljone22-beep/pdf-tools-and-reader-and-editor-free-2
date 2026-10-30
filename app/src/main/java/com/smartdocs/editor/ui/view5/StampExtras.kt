package com.smartdocs.editor.ui.view5

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.pdf.StampRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stamp extras of section 7 / Foxit 24.4x-24.6x kept outside the Room table (no schema change): per custom
 * stamp name, category, opacity and "white as transparent"; favourites and recently used stamps (by key);
 * dynamic template style (colour, border, font) and prompted values; shape templates; stamps from a PDF page
 * or the clipboard.
 */
object StampMeta {
    private const val PREFS = "stamp_meta"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [locked] / [set]: a stamp of an imported shared (enterprise) stamp set; locked ones cannot be edited (24.58 / §41). */
    data class Custom(val name: String = "", val category: String = "", val opacity: Float = 1f, val whiteTransparent: Boolean = false,
                      val locked: Boolean = false, val set: String = "")

    fun custom(ctx: Context, id: Long): Custom {
        val raw = p(ctx).getString("c_$id", null) ?: return Custom()
        val f = raw.split('\u001F')
        return Custom(f.getOrElse(0) { "" }, f.getOrElse(1) { "" }, f.getOrNull(2)?.toFloatOrNull() ?: 1f, f.getOrNull(3) == "1",
            f.getOrNull(4) == "1", f.getOrElse(5) { "" })
    }

    fun saveCustom(ctx: Context, id: Long, c: Custom) {
        p(ctx).edit().putString("c_$id", listOf(c.name, c.category, c.opacity.toString(), if (c.whiteTransparent) "1" else "0",
            if (c.locked) "1" else "0", c.set).joinToString("\u001F")).apply()
    }

    fun forget(ctx: Context, id: Long) { p(ctx).edit().remove("c_$id").apply() }

    fun favourites(ctx: Context): Set<String> = p(ctx).getStringSet("favs", emptySet())?.toSet() ?: emptySet()
    fun toggleFavourite(ctx: Context, key: String) {
        val now = favourites(ctx).toMutableSet(); if (!now.add(key)) now.remove(key)
        p(ctx).edit().putStringSet("favs", now).apply()
    }

    fun recents(ctx: Context): List<String> = p(ctx).getString("recent", null)?.split('\u001E')?.filter { it.isNotBlank() } ?: emptyList()
    fun pushRecent(ctx: Context, key: String) {
        val l = (listOf(key) + recents(ctx).filter { it != key }).take(12)
        p(ctx).edit().putString("recent", l.joinToString("\u001E")).apply()
    }

    /** Dynamic template style: colour, border ordinal, font family index. */
    data class TplStyle(val color: Int = StampRenderer.BLUE, val border: Int = 0, val font: Int = 0)

    fun tplStyle(ctx: Context, tpl: String): TplStyle {
        val raw = p(ctx).getString("t_" + tpl.hashCode(), null) ?: return TplStyle()
        val f = raw.split('|')
        return TplStyle(f.getOrNull(0)?.toIntOrNull() ?: StampRenderer.BLUE, f.getOrNull(1)?.toIntOrNull() ?: 0, f.getOrNull(2)?.toIntOrNull() ?: 0)
    }

    fun saveTplStyle(ctx: Context, tpl: String, s: TplStyle) { p(ctx).edit().putString("t_" + tpl.hashCode(), "${s.color}|${s.border}|${s.font}").apply() }

    val FONTS = listOf(R.string.cx_font_sans, R.string.cx_font_serif, R.string.cx_font_mono, R.string.cx_font_cursive)
    fun typeface(i: Int): Typeface = when (i) { 1 -> Typeface.SERIF; 2 -> Typeface.MONOSPACE; 3 -> Typeface.create("cursive", Typeface.NORMAL); else -> Typeface.DEFAULT }

    /** `{ask:Label}` tokens of a template, asked when the stamp is placed (Foxit "custom values"). */
    fun askTokens(tpl: String): List<String> = Regex("""\{ask:([^}]+)\}""").findAll(tpl).map { it.groupValues[1] }.distinct().toList()
    fun fillAsk(tpl: String, values: Map<String, String>): String = Regex("""\{ask:([^}]+)\}""").replace(tpl) { m -> values[m.groupValues[1]] ?: "" }
}

object StampProcess {
    /** Apply opacity and turn (near) white pixels transparent. */
    fun apply(src: Bitmap, opacity: Float, whiteTransparent: Boolean): Bitmap {
        if (opacity >= 0.999f && !whiteTransparent) return src
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        val bmp = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val k = opacity.coerceIn(0.05f, 1f)
        for (i in px.indices) {
            val c = px[i]
            var a = c ushr 24
            if (whiteTransparent) {
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                val mn = minOf(r, g, b)
                if (mn > 235) a = 0 else if (mn > 200) a = (a * (235 - mn) / 35)
            }
            a = (a * k).toInt().coerceIn(0, 255)
            px[i] = (a shl 24) or (c and 0xFFFFFF)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }
}

/** Standard shape stamps (24.47): outline shapes in several colours. */
enum class StampShape(val label: Int) {
    ARROW(R.string.cx_shape_arrow), CIRCLE(R.string.cx_shape_circle), DIAMOND(R.string.cx_shape_diamond), ELLIPSE(R.string.cx_shape_ellipse),
    OCTAGON(R.string.cx_shape_octagon), RECTANGLE(R.string.cx_shape_rectangle), SQUARE(R.string.cx_shape_square), TRIANGLE(R.string.cx_shape_triangle);

    fun render(color: Int, widthPx: Int = 400): Bitmap {
        val wide = this == ARROW || this == ELLIPSE || this == RECTANGLE
        val w = widthPx; val h = if (wide) (widthPx * 0.6f).toInt() else widthPx
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sw = w * 0.04f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = sw; strokeJoin = Paint.Join.ROUND }
        val l = sw; val t = sw; val r = w - sw; val b = h - sw
        val cx = w / 2f; val cy = h / 2f
        val path = Path()
        when (this) {
            CIRCLE, ELLIPSE -> { c.drawOval(l, t, r, b, p); return bmp }
            RECTANGLE, SQUARE -> { c.drawRect(l, t, r, b, p); return bmp }
            DIAMOND -> { path.moveTo(cx, t); path.lineTo(r, cy); path.lineTo(cx, b); path.lineTo(l, cy); path.close() }
            TRIANGLE -> { path.moveTo(cx, t); path.lineTo(r, b); path.lineTo(l, b); path.close() }
            OCTAGON -> {
                for (i in 0 until 8) {
                    val a = Math.PI / 8 + i * Math.PI / 4
                    val x = cx + (r - cx) * Math.cos(a).toFloat(); val y = cy + (b - cy) * Math.sin(a).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            ARROW -> {
                val sh = (b - t) * 0.25f
                path.moveTo(l, cy - sh); path.lineTo(r - (b - t) * 0.5f, cy - sh); path.lineTo(r - (b - t) * 0.5f, t)
                path.lineTo(r, cy); path.lineTo(r - (b - t) * 0.5f, b); path.lineTo(r - (b - t) * 0.5f, cy + sh); path.lineTo(l, cy + sh); path.close()
            }
        }
        c.drawPath(path, p)
        return bmp
    }
}

object StampSources {
    /** Render [page] (0-based) of [file] as a stamp bitmap, white made transparent (7.38 / 24.51). */
    suspend fun fromPdfPage(ctx: Context, file: File, page: Int, password: String? = null, widthPx: Int = 900): Bitmap = withContext(Dispatchers.IO) {
        PdfSession.open(ctx, file, password).use { s ->
            val pg = page.coerceIn(0, s.pageCount - 1)
            val (pw, ph) = s.pageSize(pg)
            val h = (widthPx * ph / pw).toInt().coerceAtLeast(1)
            val bmp = s.render(pg, widthPx, h, false, true).copy(Bitmap.Config.ARGB_8888, true)
            StampProcess.apply(trim(bmp), 1f, true)
        }
    }

    /** Crop white margins so the stamp is only the page content. */
    fun trim(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        fun ink(i: Int): Boolean { val c = px[i]; return (c ushr 24) > 16 && minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) < 235 }
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) if (ink(y * w + x)) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
        if (r < l || b < t) return src
        return Bitmap.createBitmap(src, l, t, r - l + 1, b - t + 1)
    }

    /** An image on the system clipboard (24.55), or null. */
    suspend fun fromClipboard(ctx: Context): Bitmap? = withContext(Dispatchers.IO) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@withContext null
        val clip = cm.primaryClip ?: return@withContext null
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val bmp = runCatching { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (bmp != null) return@withContext bmp
        }
        null
    }
}
