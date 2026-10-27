package com.smartdocs.editor.ui.edit

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit.Affine
import com.smartdocs.editor.core.edit.ContentEdit
import com.smartdocs.editor.core.edit.EditTextStyle
import com.smartdocs.editor.core.edit.ImageEdit5
import com.smartdocs.editor.core.edit.ImageItem
import com.smartdocs.editor.core.edit.PageModel
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

// NVE-img: page-object tools of the content editor (Image mode): images, vector shapes and shadings. Every document
// change goes through EditController.runNve -> applyEdit -> ReaderState.applyInPlace = one step of the unified history.

/** A smart-alignment guide line in display space (vertical: x = [pos], else y = [pos]). */
data class Guide5(val vertical: Boolean, val pos: Float)

enum class ImgSheet5 { MORE, ROTATE, POSITION, CROP, LOOK, ADJUST, COLOR, RESAMPLE, PROPS, COMPRESS, PAGES, STROKE, EDITOR, DRAW_NEW }

/** Which page objects Image mode selects (Text switches to Text mode). */
enum class ObjFilter5 { ALL, IMAGE, SHAPE, SHADING }

/** What Copy put aside for Paste: a picture, a text block, or a vector shape (works across documents). */
sealed class ObjectClip5 {
    class Picture(val bitmap: Bitmap, val width: Float, val height: Float) : ObjectClip5()
    class Words(val text: String, val style: EditTextStyle, val width: Float) : ObjectClip5()
    internal class Vector(val clip: ImageEdit5.VectorClip) : ObjectClip5()
}

/** In-app clipboard of page objects (kept across pages and documents while the app runs). */
object ObjectClipboard5 { var clip by mutableStateOf<ObjectClip5?>(null) }

internal class ImageTools5(val c: EditController) {
    private val prefs = c.state.context.getSharedPreferences("nve_edit", Context.MODE_PRIVATE)
    var smart by mutableStateOf(prefs.getBoolean("smart_align", true)); private set
    var filter by mutableStateOf(ObjFilter5.entries.getOrElse(prefs.getInt("obj_filter", 0)) { ObjFilter5.ALL }); private set
    var multi by mutableStateOf(false)
    val selected = mutableStateListOf<ImageItem>()
    val guides = mutableStateListOf<Guide5>()
    var sheet by mutableStateOf<ImgSheet5?>(null)
    /** Page objects of the current page (images, shapes, shadings), loaded after every model reload. */
    var objects by mutableStateOf<List<ImageEdit5.Obj>>(emptyList())
    /** Marquee being dragged in multi-select (display space). */
    var marquee by mutableStateOf<RectF?>(null)
    private var raw: FloatArray? = null
    private var snapPt = 6f

    fun toggleSmart() { smart = !smart; prefs.edit().putBoolean("smart_align", smart).apply() }
    fun setFilter(f: ObjFilter5) { filter = f; prefs.edit().putInt("obj_filter", f.ordinal).apply(); c.selectImageNve(null); selected.clear() }

    fun toggleMulti() { multi = !multi; selected.clear(); marquee = null; if (multi) c.selectImageNve(null) }

    fun kindOf(op: Int): ImageEdit5.Kind? = objects.firstOrNull { it.op == op }?.kind
    /** The selection is a vector shape or a shading (not an image). */
    fun isVector(i: ImageItem?): Boolean = i != null && i.id < 0 && kindOf(i.op) != ImageEdit5.Kind.IMAGE

    private fun accepts(k: ImageEdit5.Kind) = when (filter) {
        ObjFilter5.ALL -> true
        ObjFilter5.IMAGE -> k == ImageEdit5.Kind.IMAGE
        ObjFilter5.SHAPE -> k == ImageEdit5.Kind.SHAPE
        ObjFilter5.SHADING -> k == ImageEdit5.Kind.SHADING
    }

    fun visibleObjects(): List<ImageEdit5.Obj> = objects.filter { accepts(it.kind) }

    /** A selectable item for object [o] (images keep the model's own item so every image action works). */
    fun itemOf(o: ImageEdit5.Obj): ImageItem =
        c.model?.images?.firstOrNull { it.op == o.op && o.kind == ImageEdit5.Kind.IMAGE }
            ?: ImageItem(-1 - o.op, o.op, null, false, RectF(o.box[0], o.box[3], o.box[2], o.box[1]), floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f), 0, 0)

    private fun hit(x: Float, y: Float): ImageEdit5.Obj? =
        visibleObjects().filter { x in it.box[0]..it.box[2] && y in it.box[1]..it.box[3] }
            .minByOrNull { (it.box[2] - it.box[0]) * (it.box[3] - it.box[1]) }

    /** A tap in Image mode. True when consumed (multi-select, shapes / shadings, filtered selection). */
    fun onTap(m: PageModel, x: Float, y: Float): Boolean {
        if (multi) {
            val o = hit(x, y) ?: return true
            val i = selected.indexOfFirst { it.op == o.op }
            if (i >= 0) selected.removeAt(i) else selected.add(itemOf(o))
            return true
        }
        if (filter == ObjFilter5.IMAGE || objects.isEmpty()) return false
        val o = hit(x, y)
        if (o == null) { if (c.image != null) { c.selectImageNve(null); return true }; return false }
        c.selectImageNve(itemOf(o))
        return true
    }

    fun selectAll() { selected.clear(); selected.addAll(visibleObjects().map { itemOf(it) }) }

    fun marqueeEnd() {
        val r = marquee ?: return
        marquee = null
        val l = minOf(r.left, r.right); val rr = maxOf(r.left, r.right); val b = minOf(r.top, r.bottom); val t = maxOf(r.top, r.bottom)
        visibleObjects().filter { it.box[0] >= l && it.box[2] <= rr && it.box[1] >= b && it.box[3] <= t }.forEach { o ->
            if (selected.none { it.op == o.op }) selected.add(itemOf(o))
        }
    }

    // ---------------- smart alignment ----------------

    fun dragStart(thresholdPt: Float) { raw = c.affine.copyOf(); snapPt = thresholdPt; guides.clear() }

    fun dragEnd() { raw = null; guides.clear() }

    private class Hit(val value: Float, val line: Float)

    private fun best(values: List<Float>, lines: List<Float>): Hit? {
        var b: Hit? = null
        for (v in values) for (l in lines) { val d = abs(l - v); if (d <= snapPt && (b == null || d < abs(b.line - b.value))) b = Hit(v, l) }
        return b
    }

    /** Candidate x and y lines: page edges, centre and margins, other objects and the text blocks. */
    private fun lines(m: PageModel, exclude: ImageItem?): Pair<List<Float>, List<Float>> {
        val xs = arrayListOf(0f, m.width / 2f, m.width, MARGIN, m.width - MARGIN)
        val ys = arrayListOf(0f, m.height / 2f, m.height, MARGIN, m.height - MARGIN)
        val rects = objects.filter { it.op != exclude?.op }.map { RectF(it.box[0], it.box[3], it.box[2], it.box[1]) } + m.blocks.map { it.rect }
        rects.forEach { r -> xs += listOf(r.left, (r.left + r.right) / 2f, r.right); ys += listOf(r.top, (r.top + r.bottom) / 2f, r.bottom) }
        return xs to ys
    }

    /** A drag step of the selected object: the finger moves the unsnapped placement; the shown one snaps. */
    fun drag(resize: Boolean, dx: Float, dy: Float) {
        c.setAffineNve(raw ?: c.affine.copyOf())
        if (resize) c.resizeImage(dx, dy) else c.moveImage(dx, dy)
        raw = c.affine.copyOf()
        guides.clear()
        val m = c.model ?: return
        if (!smart) return
        val r = c.previewRect() ?: return
        val (xs, ys) = lines(m, c.image)
        if (!resize) {
            val bx = best(listOf(r.left, r.centerX(), r.right), xs)
            val by = best(listOf(r.top, (r.top + r.bottom) / 2f, r.bottom), ys)
            val ddx = bx?.let { it.line - it.value } ?: 0f; val ddy = by?.let { it.line - it.value } ?: 0f
            if (ddx != 0f || ddy != 0f) c.moveImage(ddx, ddy)
            bx?.let { guides += Guide5(true, it.line) }; by?.let { guides += Guide5(false, it.line) }
        } else {
            val bx = best(listOf(r.right), xs) ?: return
            val w = r.width(); if (w < 1f) return
            val s = (bx.line - r.left) / w
            if (s <= 0.05f) return
            val mm = Affine.mul(Affine.mul(Affine.translate(-r.left, -r.top), Affine.scale(s, s)), Affine.translate(r.left, r.top))
            c.setAffineNve(Affine.values(Affine.mul(Affine.fromValues(c.affine), mm)))
            guides += Guide5(true, bx.line)
        }
    }

    // ---------------- pending transforms (applied with the bar's Apply) ----------------

    private fun around(r: RectF, m: com.tom_roush.pdfbox.util.Matrix): FloatArray {
        val cx = r.centerX(); val cy = (r.top + r.bottom) / 2f
        val t = Affine.mul(Affine.mul(Affine.translate(-cx, -cy), m), Affine.translate(cx, cy))
        return Affine.values(Affine.mul(Affine.fromValues(c.affine), t))
    }

    fun flip(horizontal: Boolean) {
        val r = c.previewRect() ?: return
        c.setAffineNve(around(r, if (horizontal) Affine.scale(-1f, 1f) else Affine.scale(1f, -1f)))
    }

    /** Rotate by [deg] degrees clockwise and shear by [shearX] / [shearY] degrees, around the centre. */
    fun rotate(deg: Float, shearX: Float = 0f, shearY: Float = 0f) {
        val r = c.previewRect() ?: return
        var m = Affine.identity()
        if (shearX != 0f || shearY != 0f) {
            val tx = Math.tan(Math.toRadians(shearX.toDouble())).toFloat(); val ty = Math.tan(Math.toRadians(shearY.toDouble())).toFloat()
            m = Affine.of(1f, ty, tx, 1f, 0f, 0f)
        }
        if (deg != 0f) m = Affine.mul(m, Affine.rotateAround(-deg, 0f, 0f))
        c.setAffineNve(around(r, m))
    }

    /** Centre the selected object on the page horizontally and / or vertically (pending, like a drag). */
    fun center(h: Boolean, v: Boolean) {
        val r = c.previewRect() ?: return
        val m = c.model ?: return
        val dx = if (h) m.width / 2f - r.centerX() else 0f
        val dy = if (v) m.height / 2f - (r.top + r.bottom) / 2f else 0f
        c.moveImage(dx, dy)
    }

    /** Place the bounding box at [x], [y] (from the page's top-left, points) with size [w] × [h], then rotate by [deg]. */
    fun place(x: Float, y: Float, w: Float, h: Float, deg: Float) {
        val r = c.previewRect() ?: return
        val m = c.model ?: return
        val rw = r.width(); val rh = r.top - r.bottom
        if (rw <= 0f || rh <= 0f || w <= 0f || h <= 0f) return
        val top = m.height - y
        var a = Affine.mul(Affine.mul(Affine.translate(-r.left, -r.top), Affine.scale(w / rw, h / rh)), Affine.translate(x, top))
        if (abs(deg) > 0.01f) a = Affine.mul(a, Affine.rotateAround(-deg, x + w / 2f, top - h / 2f))
        c.setAffineNve(Affine.values(Affine.mul(Affine.fromValues(c.affine), a)))
        c.applyTransform()
    }

    // ---------------- immediate edits ----------------

    private fun pw() = c.state.password

    fun change(ch: (op: Int) -> ImageEdit5.Change) {
        val i = c.image ?: return
        val p = c.page
        c.runNve(R.string.ed_saved) { src, out -> ImageEdit5.applyFile(src, out, pw(), p, listOf(ch(i.op))) }
    }

    fun delete() {
        val i = c.image ?: return
        val p = c.page
        c.runNve(R.string.ed_deleted) { src, out -> ImageEdit5.deleteFile(src, out, pw(), p, i.op) }
    }

    fun clearOuterClip() {
        val i = c.image ?: return
        val p = c.page
        c.runNve(R.string.ed_saved) { src, out -> ImageEdit5.clearOuterClipFile(src, out, pw(), p, i.op) }
    }

    fun replacePixels(make: (com.tom_roush.pdfbox.pdmodel.PDDocument) -> com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject) {
        val i = c.image ?: return
        val p = c.page
        c.runNve(R.string.ed_img_replaced) { src, out -> ImageEdit5.replacePixelsFile(src, out, pw(), p, i.op, make) }
    }

    fun replaceWithBitmap(bmp: Bitmap) = replacePixels { doc -> encode(doc, bmp) }

    suspend fun arrange(how: ImageEdit5.Arrange, onNothing: () -> Unit) {
        val i = c.image ?: return
        val f = c.state.file ?: return
        val p = c.page
        if (!ImageEdit5.canArrangeFile(f, pw(), p, i.op, how)) { onNothing(); return }
        c.runNve(R.string.ed_saved) { src, out -> ImageEdit5.arrangeFile(src, out, pw(), p, i.op, how) }
    }

    fun duplicate() {
        val i = c.image ?: return
        val p = c.page
        c.runNve(R.string.ed_added) { src, out -> ImageEdit5.duplicateFile(src, out, pw(), p, i.op, 12f, -12f) }
    }

    fun placeOnPages(targets: List<Int>) {
        val i = c.image ?: return
        val p = c.page
        c.runNve(R.string.ed_added) { src, out -> ImageEdit5.placeOnPagesFile(src, out, pw(), p, i.op, targets) }
    }

    /** The selected image as shown (its clip rectangle applied), for copy / external editing / the image editor. */
    suspend fun shownBitmap(): Bitmap? {
        val bmp = c.imageBitmap() ?: return null
        val i = c.image ?: return bmp
        val f = c.state.file ?: return bmp
        val look = runCatching { ImageEdit5.lookFile(f, pw(), c.page, i.op) }.getOrNull()
        val cr = look?.crop ?: return bmp
        return withContext(Dispatchers.Default) { Bitmaps5.crop(bmp, cr) }
    }

    /** Copy the selected object to the object clipboard (shapes as vectors, images as pictures). */
    suspend fun copy(): Boolean {
        val i = c.image ?: return false
        if (isVector(i)) {
            val f = c.state.file ?: return false
            val v = runCatching { ImageEdit5.copyVectorFile(f, pw(), c.page, i.op) }.getOrNull() ?: return false
            ObjectClipboard5.clip = ObjectClip5.Vector(v); return true
        }
        val r = c.previewRect() ?: return false
        val bmp = shownBitmap() ?: return false
        ObjectClipboard5.clip = ObjectClip5.Picture(bmp, r.width(), r.top - r.bottom)
        return true
    }

    /** Copy, then delete (one undo step: the delete). */
    suspend fun cut(): Boolean { if (!copy()) return false; if (isVector(c.image)) delete() else c.deleteImage(); return true }

    /** Paste the object clipboard at the tapped spot. */
    fun paste(ctx: Context) {
        val (x, y) = c.insertAt ?: return
        val m = c.model ?: return
        val p = c.page
        when (val clip = ObjectClipboard5.clip ?: return) {
            is ObjectClip5.Picture -> {
                val w = clip.width.coerceAtMost(m.width); val h = clip.height.coerceAtMost(m.height)
                val left = (x - w / 2).coerceIn(0f, (m.width - w).coerceAtLeast(0f))
                val bottom = (y - h / 2).coerceIn(0f, (m.height - h).coerceAtLeast(0f))
                c.runNve(R.string.ed_added) { src, out -> ContentEdit.addImage(src, out, pw(), p, clip.bitmap, RectF(left, bottom + h, left + w, bottom)) }
            }
            is ObjectClip5.Words -> {
                val right = (x + clip.width.coerceAtLeast(clip.style.size * 4)).coerceAtMost(m.width - MARGIN / 2)
                val box = RectF(x.coerceAtMost(right - clip.style.size * 2), y, right, y - clip.style.size * 1.3f)
                c.runNve(R.string.ed_added) { src, out -> ContentEdit.addText(ctx, src, out, pw(), p, clip.text, clip.style, box) }
            }
            is ObjectClip5.Vector -> {
                val b = clip.clip.box
                val dx = x - (b[0] + b[2]) / 2f; val dy = y - (b[1] + b[3]) / 2f
                c.runNve(R.string.ed_added) { src, out -> ImageEdit5.pasteVectorFile(src, out, pw(), p, clip.clip, dx, dy) }
            }
        }
    }

    // ---------------- multi-select: align / distribute / match size ----------------

    enum class Align { LEFT, HCENTER, RIGHT, TOP, VMIDDLE, BOTTOM, DIST_H, DIST_V, MATCH_W, MATCH_H }
    enum class Ref { SELECTION, PAGE, ANCHOR }

    /** Display affines per selected object for [how] relative to [ref]; null when the selection is too small. */
    fun plan(how: Align, ref: Ref): List<Pair<Int, FloatArray>>? {
        val items = selected.toList()
        val m = c.model ?: return null
        val need = when (how) { Align.DIST_H, Align.DIST_V -> 3; Align.MATCH_W, Align.MATCH_H -> 2; else -> if (ref == Ref.PAGE) 1 else 2 }
        if (items.size < need) return null
        val rs = items.map { it.rect }
        val box = when (ref) {
            Ref.PAGE -> RectF(0f, m.height, m.width, 0f)
            Ref.ANCHOR -> RectF(items.first().rect)
            Ref.SELECTION -> RectF(rs.minOf { it.left }, rs.maxOf { it.top }, rs.maxOf { it.right }, rs.minOf { it.bottom })
        }
        fun move(dx: Float, dy: Float) = floatArrayOf(1f, 0f, 0f, 1f, dx, dy)
        return when (how) {
            Align.LEFT -> items.map { it.op to move(box.left - it.rect.left, 0f) }
            Align.HCENTER -> items.map { it.op to move(box.centerX() - it.rect.centerX(), 0f) }
            Align.RIGHT -> items.map { it.op to move(box.right - it.rect.right, 0f) }
            Align.TOP -> items.map { it.op to move(0f, box.top - it.rect.top) }
            Align.VMIDDLE -> items.map { it.op to move(0f, (box.top + box.bottom) / 2f - (it.rect.top + it.rect.bottom) / 2f) }
            Align.BOTTOM -> items.map { it.op to move(0f, box.bottom - it.rect.bottom) }
            Align.DIST_H -> {
                val s = items.sortedBy { it.rect.centerX() }
                val gap = (s.last().rect.right - s.first().rect.left - s.sumOf { it.rect.width().toDouble() }.toFloat()) / (s.size - 1)
                var x = s.first().rect.left
                s.map { val dx = x - it.rect.left; x += it.rect.width() + gap; it.op to move(dx, 0f) }
            }
            Align.DIST_V -> {
                val s = items.sortedByDescending { it.rect.top }
                val gap = (s.first().rect.top - s.last().rect.bottom - s.sumOf { (it.rect.top - it.rect.bottom).toDouble() }.toFloat()) / (s.size - 1)
                var top = s.first().rect.top
                s.map { val dy = top - it.rect.top; top -= (it.rect.top - it.rect.bottom) + gap; it.op to move(0f, dy) }
            }
            Align.MATCH_W, Align.MATCH_H -> {
                val first = items.first().rect
                items.drop(1).map { it.op to Affine.values(Affine.mul(Affine.mul(Affine.translate(-it.rect.left, -it.rect.top),
                    if (how == Align.MATCH_W) Affine.scale(first.width() / it.rect.width().coerceAtLeast(0.01f), 1f)
                    else Affine.scale(1f, (first.top - first.bottom) / (it.rect.top - it.rect.bottom).coerceAtLeast(0.01f))),
                    Affine.translate(it.rect.left, it.rect.top))) }
            }
        }
    }

    fun align(how: Align, ref: Ref): Boolean {
        val plan = plan(how, ref) ?: return false
        val p = c.page
        c.runNve(R.string.ed_saved) { src, out -> ImageEdit5.applyFile(src, out, pw(), p, plan.map { (op, a) -> ImageEdit5.Change(op, display = a) }) }
        selected.clear()
        return true
    }

    companion object {
        const val MARGIN = 36f

        /** Encode [bmp] as a page image: lossless with transparency, JPEG otherwise. */
        fun encode(doc: com.tom_roush.pdfbox.pdmodel.PDDocument, bmp: Bitmap, quality: Float = 0.9f) =
            if (bmp.hasAlpha()) LosslessFactory.createFromImage(doc, bmp) else JPEGFactory.createFromImage(doc, bmp, quality)
    }
}

/** Bitmap filters for page images (brightness / contrast, colour space, resample, crop). */
internal object Bitmaps5 {
    fun argb(src: Bitmap): Bitmap = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)

    /** [brightness] and [contrast] in -100..100. */
    fun adjust(src: Bitmap, brightness: Int, contrast: Int): Bitmap {
        val k = 1f + contrast / 100f
        val t = brightness * 2.55f + 128f * (1f - k)
        val cm = ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f))
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    private fun pixels(src: Bitmap): IntArray { val b = argb(src); val a = IntArray(b.width * b.height); b.getPixels(a, 0, b.width, 0, 0, b.width, b.height); return a }

    /** Pixels composited on white, luminance 0..255. */
    fun gray(src: Bitmap): ByteArray = pixels(src).let { px -> ByteArray(px.size) { lum(px[it]).toByte() } }

    fun blackWhite(src: Bitmap, threshold: Int): BooleanArray = pixels(src).let { px -> BooleanArray(px.size) { lum(px[it]) < threshold } }

    fun rgb(src: Bitmap): IntArray = pixels(src).map { onWhite(it) }.toIntArray()

    private fun onWhite(p: Int): Int {
        val a = (p ushr 24) and 0xFF
        fun ch(v: Int) = (v * a + 255 * (255 - a)) / 255
        return (ch((p shr 16) and 0xFF) shl 16) or (ch((p shr 8) and 0xFF) shl 8) or ch(p and 0xFF)
    }

    private fun lum(p: Int): Int { val q = onWhite(p); return (0.299f * ((q shr 16) and 0xFF) + 0.587f * ((q shr 8) and 0xFF) + 0.114f * (q and 0xFF)).toInt().coerceIn(0, 255) }

    /** Crop to the unit-square rect (x, y, w, h; y up from the bottom of the image). */
    fun crop(src: Bitmap, cr: FloatArray): Bitmap {
        val l = (cr[0] * src.width).toInt().coerceIn(0, src.width - 1)
        val t = ((1f - cr[1] - cr[3]) * src.height).toInt().coerceIn(0, src.height - 1)
        val w = (cr[2] * src.width).toInt().coerceIn(1, src.width - l)
        val h = (cr[3] * src.height).toInt().coerceIn(1, src.height - t)
        return Bitmap.createBitmap(src, l, t, w, h)
    }

    fun opaque(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.RGB_565)
        Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        return out
    }
}

// ======================================================================== UI

private fun mmOf(pt: Float) = pt * 25.4f / 72f
private fun ptOf(mm: Float) = mm * 72f / 25.4f
private fun fmt(v: Float) = if (abs(v - Math.round(v)) < 0.005f) Math.round(v).toString() else "%.2f".format(java.util.Locale.US, v)

@Composable
private fun Chip5(icon: ImageVector, label: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    if (selected) FilterChip(selected = true, onClick = onClick, enabled = enabled, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) }, modifier = Modifier.padding(horizontal = 3.dp))
    else AssistChip(onClick = onClick, enabled = enabled, label = { Text(label) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) }, modifier = Modifier.padding(horizontal = 3.dp))
}

/** Extra chips of the selected-image bar. */
@Composable
internal fun ImageTools5Chips(c: EditController) {
    val t = c.tools5
    Chip5(Icons.Default.FlipToBack, stringResource(R.string.ni_flip_h), !c.busy) { t.flip(true) }
    Chip5(Icons.Default.Flip, stringResource(R.string.ni_flip_v), !c.busy) { t.flip(false) }
    Chip5(Icons.Default.Crop, stringResource(R.string.ni_crop), !c.busy) { t.sheet = ImgSheet5.CROP }
    Chip5(Icons.Default.Brush, stringResource(R.string.ni_image_editor), !c.busy) { t.sheet = ImgSheet5.EDITOR }
    Chip5(Icons.Default.Tune, stringResource(R.string.ni_more), !c.busy) { t.sheet = ImgSheet5.MORE }
}

/** Object filter chips (All / Text / Image / Shape / Shading). */
@Composable
private fun FilterRow5(c: EditController) {
    val t = c.tools5
    Chip5(Icons.Default.TextFields, stringResource(R.string.ni_f_text)) { c.clearSelection(); c.mode = EditMode.TEXT }
    listOf(ObjFilter5.ALL to R.string.ni_f_all, ObjFilter5.IMAGE to R.string.ni_f_image, ObjFilter5.SHAPE to R.string.ni_f_shape, ObjFilter5.SHADING to R.string.ni_f_shading).forEach { (f, l) ->
        FilterChip(selected = t.filter == f, onClick = { t.setFilter(f) }, label = { Text(stringResource(l)) }, modifier = Modifier.padding(horizontal = 3.dp))
    }
}

/** Image mode with nothing selected: object filter, multi-select and smart alignment, paste hint. */
@Composable
internal fun ImageIdleBar5(c: EditController) {
    val t = c.tools5
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) { FilterRow5(c) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip5(Icons.Default.SelectAll, stringResource(R.string.ni_select_multi), !c.busy, t.multi) { t.toggleMulti() }
                Chip5(Icons.Default.Straighten, stringResource(R.string.ni_smart_align), true, t.smart) { t.toggleSmart() }
                if (ObjectClipboard5.clip != null) Text(stringResource(R.string.ni_paste_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

/** Bar of a selected vector shape or shading. */
@Composable
internal fun ShapeActionBar5(c: EditController) {
    val t = c.tools5
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val shading = t.kindOf(c.image?.op ?: -1) == ImageEdit5.Kind.SHADING
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(stringResource(if (shading) R.string.ni_f_shading else R.string.ni_f_shape) + if (c.transformPending) " · " + stringResource(R.string.ed_img_pending) else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!shading) Chip5(Icons.Default.FormatColorFill, stringResource(R.string.ni_stroke_fill), !c.busy) { t.sheet = ImgSheet5.STROKE }
                Chip5(Icons.Default.Opacity, stringResource(R.string.ni_look), !c.busy) { t.sheet = ImgSheet5.LOOK }
                Chip5(Icons.Default.Crop, stringResource(R.string.ni_crop), !c.busy) { t.sheet = ImgSheet5.CROP }
                Chip5(Icons.Default.FlipToBack, stringResource(R.string.ni_flip_h), !c.busy) { t.flip(true) }
                Chip5(Icons.Default.Flip, stringResource(R.string.ni_flip_v), !c.busy) { t.flip(false) }
                if (!shading) Chip5(Icons.Default.ContentCut, stringResource(R.string.ni_cut), !c.busy) {
                    scope.launch { if (!t.cut()) c.message = ctx.getString(R.string.ed_failed, "") }
                }
                Chip5(Icons.Default.Tune, stringResource(R.string.ni_more), !c.busy) { t.sheet = ImgSheet5.MORE }
                Chip5(Icons.Default.Delete, stringResource(R.string.delete), !c.busy) { t.delete() }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ed_img_drag_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (c.transformPending) TextButton(onClick = { c.resetTransform() }, enabled = !c.busy) { Text(stringResource(R.string.ed_reset)) }
                Button(onClick = { c.applyTransform() }, enabled = !c.busy && c.transformPending) { Text(stringResource(R.string.ed_apply)) }
            }
        }
    }
}

/** Align / distribute / match size bar shown in multi-select. */
@Composable
internal fun MultiAlignBar5(c: EditController) {
    val t = c.tools5
    val ctx = LocalContext.current
    var ref by remember { mutableStateOf(ImageTools5.Ref.SELECTION) }
    fun go(a: ImageTools5.Align) {
        if (!t.align(a, ref)) c.message = ctx.getString(if (a == ImageTools5.Align.DIST_H || a == ImageTools5.Align.DIST_V) R.string.ni_need_three else R.string.ni_need_two)
    }
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ni_multi_count, t.selected.size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { t.selectAll() }) { Text(stringResource(R.string.ni_select_all)) }
                TextButton(onClick = { t.toggleMulti() }) { Text(stringResource(R.string.done)) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ni_align_ref), style = MaterialTheme.typography.bodySmall)
                listOf(ImageTools5.Ref.SELECTION to R.string.ni_ref_selection, ImageTools5.Ref.PAGE to R.string.ni_align_to_page, ImageTools5.Ref.ANCHOR to R.string.ni_ref_anchor).forEach { (r, l) ->
                    FilterChip(selected = ref == r, onClick = { ref = r }, label = { Text(stringResource(l)) }, modifier = Modifier.padding(horizontal = 3.dp))
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val en = !c.busy
                Chip5(Icons.Default.AlignHorizontalLeft, stringResource(R.string.ni_align_left), en) { go(ImageTools5.Align.LEFT) }
                Chip5(Icons.Default.AlignHorizontalCenter, stringResource(R.string.ni_align_hcenter), en) { go(ImageTools5.Align.HCENTER) }
                Chip5(Icons.Default.AlignHorizontalRight, stringResource(R.string.ni_align_right), en) { go(ImageTools5.Align.RIGHT) }
                Chip5(Icons.Default.AlignVerticalTop, stringResource(R.string.ni_align_top), en) { go(ImageTools5.Align.TOP) }
                Chip5(Icons.Default.AlignVerticalCenter, stringResource(R.string.ni_align_vmiddle), en) { go(ImageTools5.Align.VMIDDLE) }
                Chip5(Icons.Default.AlignVerticalBottom, stringResource(R.string.ni_align_bottom), en) { go(ImageTools5.Align.BOTTOM) }
                Chip5(Icons.Default.ViewColumn, stringResource(R.string.ni_distribute_h), en) { go(ImageTools5.Align.DIST_H) }
                Chip5(Icons.Default.TableRows, stringResource(R.string.ni_distribute_v), en) { go(ImageTools5.Align.DIST_V) }
                Chip5(Icons.Default.SettingsEthernet, stringResource(R.string.ni_match_w), en) { go(ImageTools5.Align.MATCH_W) }
                Chip5(Icons.Default.Height, stringResource(R.string.ni_match_h), en) { go(ImageTools5.Align.MATCH_H) }
            }
            Text(stringResource(R.string.ni_marquee_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}

/** Insert bar extras: image from the system clipboard, from the camera, a new drawing, paste a copied page object. */
@Composable
internal fun InsertExtras5(c: EditController) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) { PdfOps.decodeScaled(f, 2400) }
            if (bmp != null) c.insertImage(bmp) else c.message = ctx.getString(R.string.ed_img_unreadable)
        }
    }
    fun launchCamera() {
        val f = app.documents.newTemp("edit_cam", "jpg"); cameraFile = f
        runCatching { take.launch(app.documents.shareUri(f)) }.onFailure { c.message = ctx.getString(R.string.ed_failed, it.message ?: "") }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) launchCamera() }
    IconButton(onClick = {
        scope.launch {
            val f = withContext(Dispatchers.IO) { clipboardImage(ctx, app.documents.newTemp("clip_img", "img")) }
            val bmp = f?.let { withContext(Dispatchers.IO) { PdfOps.decodeScaled(it, 2400) } }
            if (bmp != null) c.insertImage(bmp) else c.message = ctx.getString(R.string.ni_no_clip_image)
        }
    }, enabled = !c.busy) { Icon(Icons.Default.ContentPaste, stringResource(R.string.ni_paste_clip)) }
    IconButton(onClick = {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else perm.launch(Manifest.permission.CAMERA)
    }, enabled = !c.busy) { Icon(Icons.Default.PhotoCamera, stringResource(R.string.ni_camera)) }
    IconButton(onClick = { c.tools5.sheet = ImgSheet5.DRAW_NEW }, enabled = !c.busy) { Icon(Icons.Default.Brush, stringResource(R.string.ni_draw_new)) }
    if (ObjectClipboard5.clip != null) IconButton(onClick = { c.tools5.paste(ctx.applicationContext) }, enabled = !c.busy) {
        Icon(Icons.Default.ContentPasteGo, stringResource(R.string.ni_paste))
    }
}

/** Copy the first image of the system clipboard into [to]; null when there is none. */
private fun clipboardImage(ctx: Context, to: File): File? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    for (i in 0 until clip.itemCount) {
        val uri = clip.getItemAt(i).uri ?: continue
        val type = runCatching { ctx.contentResolver.getType(uri) }.getOrNull() ?: clip.description.getMimeType(0)
        if (type?.startsWith("image/") != true) continue
        val ok = runCatching { ctx.contentResolver.openInputStream(uri)?.use { s -> to.outputStream().use { s.copyTo(it) } } != null }.getOrDefault(false)
        if (ok) return to
    }
    return null
}

/** Text bar: copy the edited block (text + style) as a page object, and its text to the system clipboard. */
@Composable
internal fun CopyTextObject5(c: EditController) {
    val ctx = LocalContext.current
    val b = c.block ?: return
    if (c.unreadable || c.text.isBlank()) return
    IconButton(onClick = {
        ObjectClipboard5.clip = ObjectClip5.Words(c.text, c.style, b.rect.width())
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("text", c.text))
        c.message = ctx.getString(R.string.ni_copied)
    }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.ni_copy)) }
}

/** Sheets and dialogs of the object tools, the object list loader and the "this document has layers" prompt. */
@Composable
internal fun ImageTools5Host(c: EditController) {
    val t = c.tools5
    LayersPrompt5(c)
    // page objects for the selection (shapes and shadings are not in the text model)
    LaunchedEffect(c.model) {
        val f = c.state.file ?: return@LaunchedEffect
        val m = c.model ?: run { t.objects = emptyList(); return@LaunchedEffect }
        t.objects = runCatching { ImageEdit5.objectsFile(f, c.state.password, m.page) }.getOrDefault(emptyList())
        t.selected.retainAll { s -> t.objects.any { it.op == s.op } }
    }
    LaunchedEffect(c.mode) { if (c.mode != EditMode.IMAGE && t.multi) t.toggleMulti() }
    // a finished edit clears the selection: close what belonged to it
    LaunchedEffect(c.image) { if (c.image == null && t.sheet != null && t.sheet != ImgSheet5.DRAW_NEW) t.sheet = null }
    when (t.sheet) {
        null -> {}
        ImgSheet5.MORE -> MoreSheet5(c)
        ImgSheet5.ROTATE -> RotateDialog5(c)
        ImgSheet5.POSITION -> PositionDialog5(c)
        ImgSheet5.CROP -> CropDialog5(c)
        ImgSheet5.LOOK -> LookDialog5(c)
        ImgSheet5.ADJUST -> AdjustDialog5(c)
        ImgSheet5.COLOR -> ColorDialog5(c)
        ImgSheet5.RESAMPLE -> ResampleDialog5(c)
        ImgSheet5.PROPS -> PropsDialog5(c)
        ImgSheet5.COMPRESS -> CompressDialog5(c)
        ImgSheet5.PAGES -> PagesDialog5(c)
        ImgSheet5.STROKE -> StrokeDialog5(c)
        ImgSheet5.EDITOR, ImgSheet5.DRAW_NEW -> ImageEditorHost5(c, blank = t.sheet == ImgSheet5.DRAW_NEW)
    }
}

/** The raster image editor (ui/nve/imgedit) on the selected image, or on a blank canvas for "Draw new image". */
@Composable
private fun ImageEditorHost5(c: EditController, blank: Boolean) {
    val t = c.tools5
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var ready by remember { mutableStateOf(blank) }
    LaunchedEffect(blank) { if (!blank) { src = t.shownBitmap(); ready = true } }
    if (!ready) return
    if (!blank && src == null) { LaunchedEffect(Unit) { t.sheet = null }; return }
    val ar = src?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
    Dialog(onDismissRequest = { t.sheet = null }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            com.smartdocs.editor.ui.nve.imgedit.NveImageEditor(src, onDone = { out ->
                t.sheet = null
                when {
                    blank -> c.insertImage(out)
                    ar != null && abs(out.width.toFloat() / out.height.coerceAtLeast(1) - ar) < 0.01f * ar -> t.replaceWithBitmap(out)
                    else -> c.replaceImage(out)
                }
            }, onCancel = { t.sheet = null })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet5(c: EditController) {
    val t = c.tools5
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val vector = t.isVector(c.image)
    val shading = t.kindOf(c.image?.op ?: -1) == ImageEdit5.Kind.SHADING
    var external by remember { mutableStateOf<Triple<File, Long, Float>?>(null) }   // file, stamp, aspect
    val editLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val (f, stamp, ar) = external ?: return@rememberLauncherForActivityResult
        external = null
        scope.launch {
            val back: File? = withContext(Dispatchers.IO) {
                val u = res.data?.data
                if (u != null && u != app.documents.shareUri(f)) runCatching {
                    val g = app.documents.newTemp("edited_img", "img")
                    ctx.contentResolver.openInputStream(u)?.use { s -> g.outputStream().use { s.copyTo(it) } }; g
                }.getOrNull()
                else if (f.lastModified() != stamp) f else null
            }
            val bmp = back?.let { withContext(Dispatchers.IO) { PdfOps.decodeScaled(it, 4000) } }
            when {
                bmp == null -> c.message = ctx.getString(R.string.ni_external_unchanged)
                c.image == null -> c.message = ctx.getString(R.string.ed_stale)
                // same proportions: exact placement (and clip) kept; otherwise fitted into the old frame
                abs(bmp.width.toFloat() / bmp.height.coerceAtLeast(1) - ar) < 0.01f * ar -> t.replaceWithBitmap(bmp)
                else -> c.replaceImage(bmp)
            }
        }
    }
    fun close() { t.sheet = null }
    ModalBottomSheet(onDismissRequest = { close() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.ni_more), style = MaterialTheme.typography.titleLarge)
            Section5(stringResource(R.string.ni_sec_transform))
            FlowRow5 {
                Chip5(Icons.Default.Rotate90DegreesCw, stringResource(R.string.ni_rotate_180)) { t.rotate(180f); close() }
                Chip5(Icons.Default.Rotate90DegreesCcw, stringResource(R.string.ni_rotate_angle)) { t.sheet = ImgSheet5.ROTATE }
                Chip5(Icons.Default.FlipToBack, stringResource(R.string.ni_flip_h)) { t.flip(true); close() }
                Chip5(Icons.Default.Flip, stringResource(R.string.ni_flip_v)) { t.flip(false); close() }
                Chip5(Icons.Default.OpenWith, stringResource(R.string.ni_position)) { t.sheet = ImgSheet5.POSITION }
                Chip5(Icons.Default.Crop, stringResource(R.string.ni_crop)) { t.sheet = ImgSheet5.CROP }
                Chip5(Icons.Default.AlignHorizontalCenter, stringResource(R.string.ni_center_h)) { t.center(true, false); close() }
                Chip5(Icons.Default.AlignVerticalCenter, stringResource(R.string.ni_center_v)) { t.center(false, true); close() }
                Chip5(Icons.Default.CenterFocusStrong, stringResource(R.string.ni_center_both)) { t.center(true, true); close() }
            }
            Text(stringResource(R.string.ni_pending_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Section5(stringResource(R.string.ni_sec_look))
            FlowRow5 {
                Chip5(Icons.Default.Opacity, stringResource(R.string.ni_look)) { t.sheet = ImgSheet5.LOOK }
                if (vector && !shading) Chip5(Icons.Default.FormatColorFill, stringResource(R.string.ni_stroke_fill)) { t.sheet = ImgSheet5.STROKE }
                if (!vector) {
                    Chip5(Icons.Default.Brush, stringResource(R.string.ni_image_editor)) { t.sheet = ImgSheet5.EDITOR }
                    Chip5(Icons.Default.Brightness6, stringResource(R.string.ni_adjust)) { t.sheet = ImgSheet5.ADJUST }
                    Chip5(Icons.Default.Palette, stringResource(R.string.ni_color)) { t.sheet = ImgSheet5.COLOR }
                    Chip5(Icons.Default.PhotoSizeSelectSmall, stringResource(R.string.ni_resample)) { t.sheet = ImgSheet5.RESAMPLE }
                    Chip5(Icons.Default.Compress, stringResource(R.string.ni_compress)) { t.sheet = ImgSheet5.COMPRESS }
                }
            }
            Section5(stringResource(R.string.ni_arrange))
            FlowRow5 {
                val nothing = { c.message = ctx.getString(R.string.ni_already) }
                Chip5(Icons.Default.FlipToFront, stringResource(R.string.ni_front)) { close(); scope.launch { t.arrange(ImageEdit5.Arrange.FRONT, nothing) } }
                Chip5(Icons.Default.ArrowUpward, stringResource(R.string.ni_forward)) { close(); scope.launch { t.arrange(ImageEdit5.Arrange.FORWARD, nothing) } }
                Chip5(Icons.Default.ArrowDownward, stringResource(R.string.ni_backward)) { close(); scope.launch { t.arrange(ImageEdit5.Arrange.BACKWARD, nothing) } }
                Chip5(Icons.Default.FlipToBack, stringResource(R.string.ni_back)) { close(); scope.launch { t.arrange(ImageEdit5.Arrange.BACK, nothing) } }
            }
            Section5(stringResource(R.string.ni_sec_object))
            FlowRow5 {
                if (!shading) {
                    Chip5(Icons.Default.ContentCopy, stringResource(R.string.ni_copy)) {
                        close(); scope.launch { c.message = ctx.getString(if (t.copy()) R.string.ni_copied else R.string.ed_img_export_failed) }
                    }
                    Chip5(Icons.Default.ContentCut, stringResource(R.string.ni_cut)) {
                        close(); scope.launch { if (!t.cut()) c.message = ctx.getString(R.string.ed_img_export_failed) }
                    }
                }
                Chip5(Icons.Default.LibraryAdd, stringResource(R.string.ni_duplicate)) { close(); t.duplicate() }
                Chip5(Icons.Default.CopyAll, stringResource(R.string.ni_place_pages)) { t.sheet = ImgSheet5.PAGES }
                if (!vector) {
                    Chip5(Icons.Default.Edit, stringResource(R.string.ni_edit_external)) {
                        scope.launch {
                            val bmp = t.shownBitmap() ?: run { c.message = ctx.getString(R.string.ed_img_export_failed); return@launch }
                            val f = withContext(Dispatchers.IO) { app.documents.newTemp("edit_img", "png").also { o -> o.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
                            val uri: Uri = app.documents.shareUri(f)
                            val intent = Intent(Intent.ACTION_EDIT).setDataAndType(uri, "image/png")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                            intent.clipData = ClipData.newRawUri("image", uri)
                            external = Triple(f, f.lastModified(), bmp.width.toFloat() / bmp.height.coerceAtLeast(1))
                            runCatching { editLauncher.launch(Intent.createChooser(intent, ctx.getString(R.string.ni_edit_external))) }
                                .onFailure { external = null; c.message = ctx.getString(R.string.ni_no_editor) }
                        }
                    }
                    Chip5(Icons.Default.Info, stringResource(R.string.ni_props)) { t.sheet = ImgSheet5.PROPS }
                }
                Chip5(Icons.Default.Delete, stringResource(R.string.delete)) { close(); if (vector) t.delete() else c.deleteImage() }
            }
            Section5(stringResource(R.string.ni_sec_options))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(t.smart, { t.toggleSmart() }); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ni_smart_align))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section5(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow5(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun NumField5(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() || it == '.' || it == '-' || it == ',' }.replace(',', '.')) },
        label = { Text(label) }, singleLine = true, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
private fun Dialog5(title: String, onOk: () -> Unit, okEnabled: Boolean = true, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) },
        confirmButton = { TextButton(onClick = onOk, enabled = okEnabled) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun RotateDialog5(c: EditController) {
    val t = c.tools5
    var deg by remember { mutableStateOf("15") }
    var slider by remember { mutableFloatStateOf(15f) }
    var snap by remember { mutableStateOf(true) }
    var sx by remember { mutableStateOf("0") }
    var sy by remember { mutableStateOf("0") }
    val ok = deg.toFloatOrNull() != null && (sx.toFloatOrNull() ?: 99f).let { abs(it) < 80f } && (sy.toFloatOrNull() ?: 99f).let { abs(it) < 80f }
    Dialog5(stringResource(R.string.ni_rotate_angle), okEnabled = ok,
        onOk = { t.rotate(deg.toFloat(), sx.toFloat(), sy.toFloat()); t.sheet = null }, onDismiss = { t.sheet = null }) {
        NumField5(stringResource(R.string.ni_angle_deg), deg, { deg = it; it.toFloatOrNull()?.let { v -> slider = v.coerceIn(-180f, 180f) } }, Modifier.fillMaxWidth())
        Slider(slider, { v -> val s = if (snap) Math.round(v / 15f) * 15f else Math.round(v).toFloat(); slider = s; deg = fmt(s) }, valueRange = -180f..180f)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(snap, { snap = it }); Text(stringResource(R.string.ni_snap15)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField5(stringResource(R.string.ni_shear_x), sx, { sx = it }, Modifier.weight(1f))
            NumField5(stringResource(R.string.ni_shear_y), sy, { sy = it }, Modifier.weight(1f))
        }
        Text(stringResource(R.string.ni_pending_note), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PositionDialog5(c: EditController) {
    val t = c.tools5
    val r = c.previewRect() ?: run { LaunchedEffect(Unit) { t.sheet = null }; return }
    val pageH = c.model?.height ?: return
    var mm by remember { mutableStateOf(false) }
    fun show(v: Float) = fmt(if (mm) mmOf(v) else v)
    fun read(s: String) = s.toFloatOrNull()?.let { if (mm) ptOf(it) else it }
    var x by remember(mm) { mutableStateOf(show(r.left)) }
    var y by remember(mm) { mutableStateOf(show(pageH - r.top)) }
    var w by remember(mm) { mutableStateOf(show(r.width())) }
    var h by remember(mm) { mutableStateOf(show(r.top - r.bottom)) }
    var scaleX by remember { mutableStateOf("100") }
    var scaleY by remember { mutableStateOf("100") }
    var lock by remember { mutableStateOf(true) }
    var rot by remember { mutableStateOf("0") }
    val rw = r.width(); val rh = (r.top - r.bottom).coerceAtLeast(0.01f)
    val ar = rw / rh
    val ok = read(x) != null && read(y) != null && (read(w) ?: 0f) > 0f && (read(h) ?: 0f) > 0f && rot.toFloatOrNull() != null
    Dialog5(stringResource(R.string.ni_position), okEnabled = ok, onDismiss = { t.sheet = null }, onOk = {
        t.place(read(x)!!, read(y)!!, read(w)!!, read(h)!!, rot.toFloat()); t.sheet = null
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(!mm, { mm = false }, label = { Text(stringResource(R.string.ni_unit_pt)) })
            Spacer(Modifier.width(6.dp))
            FilterChip(mm, { mm = true }, label = { Text(stringResource(R.string.ni_unit_mm)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField5(stringResource(R.string.ni_x), x, { x = it }, Modifier.weight(1f))
            NumField5(stringResource(R.string.ni_y), y, { y = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField5(stringResource(R.string.ni_w), w, { v -> w = v; read(v)?.let { nw -> scaleX = fmt(nw / rw * 100f); if (lock) { h = show(nw / ar); scaleY = scaleX } } }, Modifier.weight(1f))
            NumField5(stringResource(R.string.ni_h), h, { v -> h = v; read(v)?.let { nh -> scaleY = fmt(nh / rh * 100f); if (lock) { w = show(nh * ar); scaleX = scaleY } } }, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(lock, { lock = it }); Text(stringResource(R.string.ni_lock)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField5(stringResource(R.string.ni_scale_x), scaleX, { v ->
                scaleX = v
                v.toFloatOrNull()?.takeIf { it > 0f }?.let { s -> w = show(rw * s / 100f); if (lock) { scaleY = v; h = show(rh * s / 100f) } }
            }, Modifier.weight(1f))
            NumField5(stringResource(R.string.ni_scale_y), scaleY, { v ->
                scaleY = v
                v.toFloatOrNull()?.takeIf { it > 0f }?.let { s -> h = show(rh * s / 100f); if (lock) { scaleX = v; w = show(rw * s / 100f) } }
            }, Modifier.weight(1f))
        }
        NumField5(stringResource(R.string.ni_rotation), rot, { rot = it }, Modifier.fillMaxWidth())
        Text(stringResource(R.string.ni_position_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CropDialog5(c: EditController) {
    val t = c.tools5
    val i = c.image ?: run { LaunchedEffect(Unit) { t.sheet = null }; return }
    val f = c.state.file ?: return
    val look by produceState<ImageEdit5.Look?>(null, i.op) { value = runCatching { ImageEdit5.lookFile(f, c.state.password, c.page, i.op) }.getOrNull() ?: ImageEdit5.Look(null, 1f, "Normal") }
    val cur = look ?: run { Dialog5(stringResource(R.string.ni_crop), {}, false, { t.sheet = null }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }; return }
    val cr = cur.crop ?: floatArrayOf(0f, 0f, 1f, 1f)
    fun pct(v: Float) = fmt(v * 100f)
    var bySize by remember { mutableStateOf(false) }
    var ellipse by remember { mutableStateOf(cur.ellipse) }
    var l by remember { mutableStateOf(pct(cr[0])) }
    var b by remember { mutableStateOf(pct(cr[1])) }
    var r by remember { mutableStateOf(pct(1f - cr[0] - cr[2])) }
    var tp by remember { mutableStateOf(pct(1f - cr[1] - cr[3])) }
    // full (unclipped) displayed size of the object, for the width × height mode
    val shownW = i.rect.width(); val shownH = i.rect.top - i.rect.bottom
    val fullW = shownW / cr[2]; val fullH = shownH / cr[3]
    var w by remember { mutableStateOf(fmt(shownW)) }
    var h by remember { mutableStateOf(fmt(shownH)) }
    fun edges(): FloatArray? {
        val vl = l.toFloatOrNull()?.div(100f) ?: return null; val vb = b.toFloatOrNull()?.div(100f) ?: return null
        val vr = r.toFloatOrNull()?.div(100f) ?: return null; val vt = tp.toFloatOrNull()?.div(100f) ?: return null
        val cw = 1f - vl - vr; val ch = 1f - vb - vt
        if (vl < 0f || vb < 0f || vr < 0f || vt < 0f || cw < 0.01f || ch < 0.01f) return null
        return floatArrayOf(vl, vb, cw, ch)
    }
    fun sized(): FloatArray? {
        if (fullW <= 0f || fullH <= 0f) return null
        val vl = l.toFloatOrNull()?.div(100f) ?: return null; val vt = tp.toFloatOrNull()?.div(100f) ?: return null
        val cw = ((w.toFloatOrNull() ?: return null) / fullW).coerceAtMost(1f - vl); val ch = ((h.toFloatOrNull() ?: return null) / fullH).coerceAtMost(1f - vt)
        if (vl < 0f || vt < 0f || cw < 0.01f || ch < 0.01f) return null
        return floatArrayOf(vl, 1f - vt - ch, cw, ch)
    }
    val result = if (bySize) sized() else edges()
    Dialog5(stringResource(R.string.ni_crop), okEnabled = result != null, onDismiss = { t.sheet = null }, onOk = {
        val v = result!!
        val full = !ellipse && v[0] < 0.0005f && v[1] < 0.0005f && v[2] > 0.999f && v[3] > 0.999f
        t.change { op -> ImageEdit5.Change(op, crop = if (full) null else v, resetCrop = full, ellipse = ellipse) }; t.sheet = null
    }) {
        Row {
            FilterChip(!ellipse, { ellipse = false }, label = { Text(stringResource(R.string.ni_clip_rect)) })
            Spacer(Modifier.width(6.dp))
            FilterChip(ellipse, { ellipse = true }, label = { Text(stringResource(R.string.ni_clip_ellipse)) })
        }
        Row {
            FilterChip(!bySize, { bySize = false }, label = { Text(stringResource(R.string.ni_crop_edges)) })
            Spacer(Modifier.width(6.dp))
            FilterChip(bySize, { bySize = true }, label = { Text(stringResource(R.string.ni_crop_size)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField5(stringResource(R.string.ni_crop_left), l, { l = it }, Modifier.weight(1f))
            NumField5(stringResource(R.string.ni_crop_top), tp, { tp = it }, Modifier.weight(1f))
        }
        if (!bySize) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField5(stringResource(R.string.ni_crop_right), r, { r = it }, Modifier.weight(1f))
            NumField5(stringResource(R.string.ni_crop_bottom), b, { b = it }, Modifier.weight(1f))
        } else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField5(stringResource(R.string.ni_w) + " (pt)", w, { w = it }, Modifier.weight(1f))
            NumField5(stringResource(R.string.ni_h) + " (pt)", h, { h = it }, Modifier.weight(1f))
        }
        Text(stringResource(R.string.ni_crop_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (cur.crop != null) TextButton(onClick = { t.change { op -> ImageEdit5.Change(op, resetCrop = true) }; t.sheet = null }) { Text(stringResource(R.string.ni_crop_reset)) }
        if (cur.outerClip) TextButton(onClick = { t.clearOuterClip(); t.sheet = null }) { Text(stringResource(R.string.ni_clip_clear_doc)) }
    }
}

@Composable
private fun LookDialog5(c: EditController) {
    val t = c.tools5
    val i = c.image ?: run { LaunchedEffect(Unit) { t.sheet = null }; return }
    val f = c.state.file ?: return
    val look by produceState<ImageEdit5.Look?>(null, i.op) { value = runCatching { ImageEdit5.lookFile(f, c.state.password, c.page, i.op) }.getOrNull() ?: ImageEdit5.Look(null, 1f, "Normal") }
    val cur = look ?: return
    var a by remember(cur) { mutableFloatStateOf(cur.opacity) }
    var bm by remember(cur) { mutableStateOf(cur.blend) }
    var menu by remember { mutableStateOf(false) }
    Dialog5(stringResource(R.string.ni_look), onDismiss = { t.sheet = null }, onOk = {
        t.change { op -> ImageEdit5.Change(op, opacity = a, blend = bm) }; t.sheet = null
    }) {
        Text(stringResource(R.string.ni_opacity, Math.round(a * 100)))
        Slider(a, { a = it }, valueRange = 0.05f..1f)
        Text(stringResource(R.string.ni_blend))
        Box {
            OutlinedButton(onClick = { menu = true }) { Text(bm) }
            DropdownMenu(menu, { menu = false }) {
                ImageEdit5.BLEND_MODES.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { bm = m; menu = false }) }
            }
        }
    }
}

private val SHAPE_COLORS = listOf(-1, 0x000000, 0x616161, 0xFFFFFF, 0xD32F2F, 0xF57C00, 0xFBC02D, 0x388E3C, 0x1976D2, 0x7B1FA2)

/** Shape fill / stroke colour and line width. */
@Composable
private fun StrokeDialog5(c: EditController) {
    val t = c.tools5
    if (c.image == null) { LaunchedEffect(Unit) { t.sheet = null }; return }
    var fill by remember { mutableIntStateOf(-1) }       // -1: keep
    var stroke by remember { mutableIntStateOf(-1) }
    var width by remember { mutableStateOf("") }
    @Composable
    fun palette(sel: Int, onPick: (Int) -> Unit) = Row(Modifier.horizontalScroll(rememberScrollState())) {
        SHAPE_COLORS.forEach { col ->
            Box(Modifier.padding(3.dp).size(28.dp).clip(CircleShape)
                .background(if (col < 0) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF000000 or col.toLong()))
                .border(if (sel == col) 3.dp else 1.dp, if (sel == col) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                .clickable { onPick(col) }, contentAlignment = Alignment.Center) { if (col < 0) Text("=", style = MaterialTheme.typography.labelSmall) }
        }
    }
    Dialog5(stringResource(R.string.ni_stroke_fill), okEnabled = fill >= 0 || stroke >= 0 || width.toFloatOrNull() != null, onDismiss = { t.sheet = null }, onOk = {
        val w = width.toFloatOrNull()?.takeIf { it >= 0f }
        t.change { op -> ImageEdit5.Change(op, fill = fill.takeIf { it >= 0 }, stroke = stroke.takeIf { it >= 0 }, lineWidth = w) }; t.sheet = null
    }) {
        Text(stringResource(R.string.ni_fill)); palette(fill) { fill = it }
        Text(stringResource(R.string.ni_stroke)); palette(stroke) { stroke = it }
        NumField5(stringResource(R.string.ni_line_width), width, { width = it }, Modifier.fillMaxWidth())
        Text(stringResource(R.string.ni_keep_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AdjustDialog5(c: EditController) {
    val t = c.tools5
    if (c.image == null) { LaunchedEffect(Unit) { t.sheet = null }; return }
    val scope = rememberCoroutineScope()
    val src by produceState<Bitmap?>(null, c.image) { value = c.imageBitmap() }
    val thumb = remember(src) { src?.let { s -> val k = (360f / maxOf(s.width, s.height)).coerceAtMost(1f); Bitmap.createScaledBitmap(s, (s.width * k).toInt().coerceAtLeast(1), (s.height * k).toInt().coerceAtLeast(1), true) } }
    var br by remember { mutableFloatStateOf(0f) }
    var ct by remember { mutableFloatStateOf(0f) }
    val preview by produceState<Bitmap?>(null, thumb, br.toInt(), ct.toInt()) { value = thumb?.let { withContext(Dispatchers.Default) { Bitmaps5.adjust(it, br.toInt(), ct.toInt()) } } }
    Dialog5(stringResource(R.string.ni_adjust), okEnabled = src != null && (br.toInt() != 0 || ct.toInt() != 0), onDismiss = { t.sheet = null }, onOk = {
        val s = src ?: return@Dialog5
        t.sheet = null
        scope.launch { t.replaceWithBitmap(withContext(Dispatchers.Default) { Bitmaps5.adjust(s, br.toInt(), ct.toInt()) }) }
    }) {
        preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Fit) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.ni_brightness, br.toInt())); Slider(br, { br = it }, valueRange = -100f..100f)
        Text(stringResource(R.string.ni_contrast, ct.toInt())); Slider(ct, { ct = it }, valueRange = -100f..100f)
    }
}

@Composable
private fun ColorDialog5(c: EditController) {
    val t = c.tools5
    if (c.image == null) { LaunchedEffect(Unit) { t.sheet = null }; return }
    val scope = rememberCoroutineScope()
    var kind by remember { mutableIntStateOf(1) }       // 0 RGB, 1 gray, 2 b&w, 3 CMYK
    var th by remember { mutableFloatStateOf(128f) }
    Dialog5(stringResource(R.string.ni_color), onDismiss = { t.sheet = null }, onOk = {
        t.sheet = null
        scope.launch {
            val bmp = c.imageBitmap() ?: return@launch
            val w = bmp.width; val h = bmp.height; val k = kind; val thr = th.toInt()
            val data: Any = withContext(Dispatchers.Default) {
                when (k) { 0 -> Bitmaps5.opaque(bmp); 1 -> Bitmaps5.gray(bmp); 2 -> Bitmaps5.blackWhite(bmp, thr); else -> Bitmaps5.rgb(bmp) }
            }
            t.replacePixels { doc ->
                when (k) {
                    0 -> JPEGFactory.createFromImage(doc, data as Bitmap, 0.9f)
                    1 -> ImageEdit5.grayImage(doc, w, h, data as ByteArray)
                    2 -> ImageEdit5.bwImage(doc, w, h, data as BooleanArray)
                    else -> ImageEdit5.cmykImage(doc, w, h, data as IntArray)
                }
            }
        }
    }) {
        listOf(R.string.ni_cs_rgb, R.string.ni_cs_gray, R.string.ni_cs_bw, R.string.ni_cs_cmyk).forEachIndexed { idx, l ->
            Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(kind == idx, { kind = idx }); Text(stringResource(l)) }
        }
        if (kind == 2) { Text(stringResource(R.string.ni_threshold, th.toInt())); Slider(th, { th = it }, valueRange = 16f..240f) }
        Text(stringResource(R.string.ni_color_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResampleDialog5(c: EditController) {
    val t = c.tools5
    val i = c.image ?: run { LaunchedEffect(Unit) { t.sheet = null }; return }
    val f = c.state.file ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by produceState<ImageEdit5.ImageInfo?>(null, i.op) { value = runCatching { ImageEdit5.infoFile(f, c.state.password, c.page, i.op) }.getOrNull() }
    var dpi by remember { mutableStateOf("150") }
    val inf = info
    Dialog5(stringResource(R.string.ni_resample), okEnabled = inf != null && (dpi.toFloatOrNull() ?: 0f) >= 10f, onDismiss = { t.sheet = null }, onOk = {
        val d = dpi.toFloat(); val n = inf!!
        t.sheet = null
        // the image's pixels per point stay proportional: scale to the chosen resolution of its placement
        val scale = d / maxOf(n.ppiX, n.ppiY).coerceAtLeast(1f)
        if (scale >= 0.999f) { c.message = ctx.getString(R.string.ni_resample_higher); return@Dialog5 }
        scope.launch {
            val bmp = c.imageBitmap() ?: return@launch
            val nw = (bmp.width * scale).toInt().coerceAtLeast(1); val nh = (bmp.height * scale).toInt().coerceAtLeast(1)
            t.replaceWithBitmap(withContext(Dispatchers.Default) { Bitmap.createScaledBitmap(bmp, nw, nh, true) })
        }
    }) {
        inf?.let { Text(stringResource(R.string.ni_resample_now, Math.round(maxOf(it.ppiX, it.ppiY)), it.pixelW, it.pixelH)) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("300", "150", "96", "72").forEach { v -> FilterChip(dpi == v, { dpi = v }, label = { Text(v) }) }
        }
        NumField5(stringResource(R.string.ni_dpi_custom), dpi, { dpi = it }, Modifier.fillMaxWidth())
    }
}

/** Re-encode the selected image as JPEG with a chosen quality (same pixels, same placement). */
@Composable
private fun CompressDialog5(c: EditController) {
    val t = c.tools5
    if (c.image == null) { LaunchedEffect(Unit) { t.sheet = null }; return }
    val scope = rememberCoroutineScope()
    var q by remember { mutableFloatStateOf(70f) }
    Dialog5(stringResource(R.string.ni_compress), onDismiss = { t.sheet = null }, onOk = {
        t.sheet = null
        scope.launch {
            val bmp = c.imageBitmap() ?: return@launch
            val opaque = withContext(Dispatchers.Default) { Bitmaps5.opaque(bmp) }
            val quality = q / 100f
            t.replacePixels { doc -> JPEGFactory.createFromImage(doc, opaque, quality) }
        }
    }) {
        Text(stringResource(R.string.ni_jpeg_quality, q.toInt())); Slider(q, { q = it }, valueRange = 10f..100f)
        Text(stringResource(R.string.ni_compress_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Place the selected object on other pages at the same spot (page range). */
@Composable
private fun PagesDialog5(c: EditController) {
    val t = c.tools5
    if (c.image == null) { LaunchedEffect(Unit) { t.sheet = null }; return }
    val n = c.state.pageCount
    var spec by remember { mutableStateOf("1-$n") }
    val pages = remember(spec, n) { PdfOps.parseRange(spec, n).filter { it != c.page } }
    Dialog5(stringResource(R.string.ni_place_pages), okEnabled = spec.isNotBlank() && pages.isNotEmpty(), onDismiss = { t.sheet = null }, onOk = {
        t.placeOnPages(pages); t.sheet = null
    }) {
        OutlinedTextField(spec, { spec = it }, label = { Text(stringResource(R.string.ni_page_range)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.ni_place_pages_note, pages.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PropsDialog5(c: EditController) {
    val t = c.tools5
    val i = c.image ?: run { LaunchedEffect(Unit) { t.sheet = null }; return }
    val f = c.state.file ?: return
    val info by produceState<ImageEdit5.ImageInfo?>(null, i.op) { value = runCatching { ImageEdit5.infoFile(f, c.state.password, c.page, i.op) }.getOrNull() }
    val yes = stringResource(R.string.ni_yes); val no = stringResource(R.string.ni_no)
    val r = c.previewRect()
    val pageH = c.model?.height ?: 0f
    AlertDialog(onDismissRequest = { t.sheet = null }, title = { Text(stringResource(R.string.ni_props)) },
        confirmButton = { TextButton(onClick = { t.sheet = ImgSheet5.POSITION }) { Text(stringResource(R.string.ni_position)) } },
        dismissButton = { TextButton(onClick = { t.sheet = null }) { Text(stringResource(R.string.ok)) } },
        text = {
            val n = info
            if (n == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PropRow5(R.string.ni_p_kind, stringResource(if (n.inline) R.string.ni_p_inline else R.string.ni_p_xobject) + (n.name?.let { " /$it" } ?: ""))
                if (r != null) PropRow5(R.string.ni_p_position, "X ${fmt(r.left)} · Y ${fmt(pageH - r.top)} pt")
                PropRow5(R.string.ni_p_pixels, "${n.pixelW} × ${n.pixelH}")
                PropRow5(R.string.ni_p_placed, "${fmt(n.placedW)} × ${fmt(n.placedH)} pt (${fmt(mmOf(n.placedW))} × ${fmt(mmOf(n.placedH))} mm)")
                PropRow5(R.string.ni_p_res, "${Math.round(n.ppiX)} × ${Math.round(n.ppiY)} ppi")
                PropRow5(R.string.ni_p_cs, n.colorSpace)
                PropRow5(R.string.ni_p_bpc, n.bitsPerComponent.toString())
                PropRow5(R.string.ni_p_filter, n.filters.joinToString().ifBlank { "-" })
                PropRow5(R.string.ni_p_smask, if (n.softMask) yes else no)
                PropRow5(R.string.ni_p_crop, if (n.cropped) yes else no)
                PropRow5(R.string.ni_p_opacity, "${Math.round(n.opacity * 100)}% · ${n.blend}")
            }
        })
}

@Composable
private fun PropRow5(k: Int, v: String) {
    Row { Text(stringResource(k), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text(v, Modifier.weight(1.3f)) }
}

/** On entering the editor: a document with optional content (layers) gets a one-time prompt to merge them. */
@Composable
private fun LayersPrompt5(c: EditController) {
    val state = c.state
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var asked by remember(state.file) { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(state.file) {
        val f = state.file ?: return@LaunchedEffect
        if (!asked && ImageEdit5.hasLayersFile(f, state.password)) show = true
        asked = true
    }
    if (!show) return
    AlertDialog(onDismissRequest = { show = false },
        icon = { Icon(Icons.Default.Layers, null) },
        title = { Text(stringResource(R.string.ni_layers_title)) },
        text = { Text(stringResource(R.string.ni_layers_msg)) },
        confirmButton = { TextButton(onClick = {
            show = false
            scope.launch {
                runCatching { state.applyInPlace(label = { ctx.getString(R.string.ni_layers_merged) }) { src, out, pw -> ImageEdit5.mergeLayersFile(src, out, pw) } }
                    .onSuccess { if (it != null) c.message = ctx.getString(R.string.ni_layers_merged) }
                    .onFailure { c.message = ctx.getString(R.string.ed_failed, it.message ?: "") }
            }
        }) { Text(stringResource(R.string.ni_layers_merge)) } },
        dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.ni_layers_continue)) } })
}
