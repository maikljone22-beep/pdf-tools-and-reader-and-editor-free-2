package com.smartdocs.editor.core.tools5

import android.content.Context
import com.smartdocs.editor.core.edit.Affine
import com.smartdocs.editor.core.edit.PageFrame
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.view5.Box
import com.smartdocs.editor.core.view5.CommentWriter5
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Look of compare markers (Nitro mark colours; Foxit colour, opacity, line thickness and "set as default").
 * [line] 0 = filled boxes, otherwise outlined boxes of that thickness (points).
 */
data class CompareStyle(
    val insert: Int = 0x1E88E5, val delete: Int = 0xE53935, val replace: Int = 0xFB8C00,
    val opacity: Float = 0.4f, val line: Float = 0f,
) {
    fun colorOf(t: ChangeType) = when (t) { ChangeType.INSERT -> insert; ChangeType.DELETE -> delete; else -> replace }

    fun toJson(): String = JSONObject().put("i", insert).put("d", delete).put("r", replace).put("o", opacity.toDouble()).put("l", line.toDouble()).toString()

    companion object {
        fun fromJson(s: String?): CompareStyle = runCatching {
            val o = JSONObject(s ?: return CompareStyle())
            CompareStyle(o.optInt("i", 0x1E88E5), o.optInt("d", 0xE53935), o.optInt("r", 0xFB8C00), o.optDouble("o", 0.4).toFloat(), o.optDouble("l", 0.0).toFloat())
        }.getOrDefault(CompareStyle())

        fun saved(c: Context) = fromJson(c.getSharedPreferences("compare5", Context.MODE_PRIVATE).getString("style", null))
        fun saveDefault(c: Context, s: CompareStyle) = c.getSharedPreferences("compare5", Context.MODE_PRIVATE).edit().putString("style", s.toJson()).apply()
    }
}

/** Compare outputs besides the report: an annotated copy of the new document and a visual-compare document. */
object CompareOutput {

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

    /** A display box (points, top-left origin) of [page] as a user-space box. */
    private fun userBox(page: PDPage, l: Float, t: Float, r: Float, b: Float): Box {
        val f = PageFrame.of(page)
        val pts = listOf(l to t, r to t, l to b, r to b).map { (x, y) -> Affine.apply(f.toUser, x, f.height - y) }
        return Box(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first }, pts.maxOf { it.second })
    }

    /**
     * Copy of [new] with every change of [kinds] as a highlight annotation whose note says what changed, so the
     * changes show in the comments list of any viewer (Foxit "changes listed in the Comments panel"). Deleted text
     * is marked at the place it was removed; deleted / moved pages get a note on the next page.
     */
    suspend fun annotatedCopy(new: File, newPw: String?, r: CompareResult, style: CompareStyle, kinds: Set<ChangeKind>, labels: CompareLabels, out: File, author: String = "Compare"): File = withContext(Dispatchers.IO) {
        PdfOps.load(new, newPw).use { doc ->
            r.newMarks.boxes.forEach { (pi, marks) ->
                if (pi !in 0 until doc.numberOfPages) return@forEach
                val page = doc.getPage(pi)
                marks.filter { it.kind in kinds }.forEach { m ->
                    val b = m.box
                    val note = when (m.type) {
                        ChangeType.INSERT -> labels.inserted; ChangeType.DELETE -> labels.deleted; else -> labels.replaced
                    } + (if (m.note.isNotBlank()) ": " + m.note.take(300) else "") + " (" + kindLabel(m.kind, labels) + ")"
                    val ub = userBox(page, b.left, b.top, maxOf(b.right, b.left + 2f), maxOf(b.bottom, b.top + 2f))
                    runCatching { CommentWriter5.writeAreaHighlight(doc, page, ub, style.colorOf(m.type), style.opacity.coerceIn(0.15f, 1f), author, note) }
                }
            }
            if (ChangeKind.PAGE in kinds) r.pageChanges.forEach { pc ->
                val target = (pc.new ?: r.pairs.dropWhile { it.old != pc.old }.firstOrNull { it.new != null }?.new) ?: return@forEach
                if (target !in 0 until doc.numberOfPages) return@forEach
                val page = doc.getPage(target)
                val ub = userBox(page, 4f, 4f, 40f, 24f)
                val text = labels.pageChange(pc) + (if (pc.detail.isNotBlank()) " (${pc.detail})" else "")
                runCatching { CommentWriter5.writeAreaHighlight(doc, page, ub, style.replace, 0.5f, author, text) }
            }
            doc.save(out)
        }
        out
    }

    fun kindLabel(k: ChangeKind, l: CompareLabels) = when (k) {
        ChangeKind.TEXT -> l.kindText; ChangeKind.IMAGE -> l.kindImage; ChangeKind.ANNOTATION -> l.kindAnnot
        ChangeKind.FORMAT -> l.kindFormat; ChangeKind.PAGE -> l.kindPage; ChangeKind.TABLE -> l.kindTable
    }

    /**
     * Nitro "Visual Compare" output: one page per matched page pair, the pixel overlay (ink only in the old page in
     * the delete colour, only in the new one in the insert colour, in both in grey), plus inserted / deleted pages.
     */
    suspend fun visualDocument(ctx: Context, old: File, oldPw: String?, new: File, newPw: String?, pairs: List<PagePair>, style: CompareStyle,
                               labels: CompareLabels, out: File, maxPages: Int = 200): File = withContext(Dispatchers.IO) {
        PDDocument().use { doc ->
            val so = PdfSession.open(ctx, old, oldPw); val sn = PdfSession.open(ctx, new, newPw)
            try {
                pairs.take(maxPages).forEach { p ->
                    val s = if (p.new != null) sn else so
                    val idx = p.new ?: p.old ?: return@forEach
                    if (idx !in 0 until s.pageCount) return@forEach
                    val (pw, ph) = s.pageSize(idx)
                    val px = (1600f / maxOf(pw, ph)).coerceAtMost(3f)
                    val w = (pw * px).toInt().coerceAtLeast(1); val h = (ph * px).toInt().coerceAtLeast(1)
                    suspend fun render(ss: PdfSession, i: Int) = ss.render(i, w, h, annotations = true).copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { ss.invalidateCache() }
                    val a = p.old?.takeIf { it in 0 until so.pageCount }?.let { render(so, it) }
                    val b = p.new?.takeIf { it in 0 until sn.pageCount }?.let { render(sn, it) }
                    val shown = when {
                        a != null && b != null -> overlay(a, b, style)
                        else -> (a ?: b)!!
                    }
                    val page = PDPage(PDRectangle(pw, ph + 22f)); doc.addPage(page)
                    val img = JPEGFactory.createFromImage(doc, shown, 0.85f)
                    PDPageContentStream(doc, page).use { cs ->
                        cs.drawImage(img, 0f, 0f, pw, ph)
                        val head = when {
                            p.old == null -> labels.newOnly
                            p.new == null -> labels.oldOnly
                            else -> ""
                        } + "  " + labels.old + " " + (p.old?.plus(1)?.toString() ?: "–") + "  /  " + labels.new + " " + (p.new?.plus(1)?.toString() ?: "–")
                        cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 9f); cs.newLineAtOffset(6f, ph + 7f)
                        cs.showText(head.filter { it.code in 32..126 }); cs.endText()
                    }
                    shown.recycle(); a?.recycle(); b?.recycle()
                }
            } finally { so.closeAsync(); sn.closeAsync() }
            doc.documentInformation.title = labels.title
            doc.save(out)
        }
        out
    }

    /** [CompareEngine.overlay] with the marker colours of [style]. */
    fun overlay(a: android.graphics.Bitmap, b: android.graphics.Bitmap, style: CompareStyle): android.graphics.Bitmap {
        val w = minOf(a.width, b.width); val h = minOf(a.height, b.height)
        val pa = IntArray(w * h); val pb = IntArray(w * h)
        a.getPixels(pa, 0, a.width, 0, 0, w, h); b.getPixels(pb, 0, b.width, 0, 0, w, h)
        val out = IntArray(w * h)
        val del = 0xFF000000.toInt() or style.delete; val ins = 0xFF000000.toInt() or style.insert
        for (i in out.indices) {
            val da = PixelOps.lum(pa[i]) < 160; val db = PixelOps.lum(pb[i]) < 160
            out[i] = when { da && db -> 0xFF505050.toInt(); da -> del; db -> ins; else -> -1 }
        }
        return android.graphics.Bitmap.createBitmap(out, w, h, android.graphics.Bitmap.Config.ARGB_8888)
    }
}
