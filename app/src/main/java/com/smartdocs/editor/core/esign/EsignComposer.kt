package com.smartdocs.editor.core.esign

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.security.MessageDigest

/**
 * Builds the completed PDF on the sender's device: every signer's values drawn into the pages (upright on rotated
 * pages, see [PageGeom]), then appended "certificate of completion" pages with the audit trail.
 * Text/date values are rendered as images (Android text layout handles Arabic shaping and bidi).
 */
object EsignComposer {
    /** Labels of the certificate (Arabic UI strings passed in by the caller so this stays resource-free). */
    data class CertText(
        val title: String, val document: String, val requestId: String, val sender: String, val created: String,
        val originalHash: String, val signers: String, val status: String, val ip: String, val device: String, val agent: String,
        val time: String, val audit: String, val verify: String, val verifyNote: String, val signed: String, val declined: String, val pending: String,
        val kinds: Map<String, String>,
    )

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return AuditFormat.hex(md.digest())
    }

    fun compose(src: File, out: File, req: EsignRequest, values: List<SubmittedValues>, labels: CertText, verifyUrl: String) {
        PDDocument.load(src).use { doc ->
            val byId = req.fields.associateBy { it.id }
            for (sv in values) for ((fid, v) in sv.values) {
                val f = byId[fid] ?: continue
                if (f.page !in 0 until doc.numberOfPages || v.isBlank()) continue
                val page = doc.getPage(f.page)
                val bmp = when (f.type) {
                    FieldType.SIGNATURE, FieldType.INITIALS -> decodeImage(v)
                    FieldType.DATE, FieldType.TEXT -> textBitmap(v, f.w / f.h)
                } ?: continue
                drawInto(doc, page, bmp, f)
                bmp.recycle()
            }
            certificatePages(req, values, labels, verifyUrl).forEach { bmp ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                val img = LosslessFactory.createFromImage(doc, bmp)
                PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height) }
                bmp.recycle()
            }
            val tmp = File(out.parentFile, ".${out.name}.part")
            doc.save(tmp)
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        }
    }

    private fun drawInto(doc: PDDocument, page: PDPage, bmp: Bitmap, f: EsignField) {
        val box = page.cropBox
        val geom = PageGeom(box.lowerLeftX, box.lowerLeftY, box.width, box.height, page.rotation)
        // keep the image aspect inside the field box (centred)
        val fieldAspect = (f.w * geom.displayW) / (f.h * geom.displayH)
        val imgAspect = bmp.width.toFloat() / bmp.height
        var fw = f.w; var fh = f.h; var fx = f.x; var fy = f.y
        if (imgAspect > fieldAspect) { val nh = f.h * fieldAspect / imgAspect; fy += (f.h - nh) / 2; fh = nh }
        else { val nw = f.w * imgAspect / fieldAspect; fx += (f.w - nw) / 2; fw = nw }
        val m = geom.uprightMatrix(fx, fy, fw, fh)
        val img = LosslessFactory.createFromImage(doc, bmp)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.drawImage(img, Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
        }
    }

    fun decodeImage(b64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(b64.substringAfter("base64,"), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /** Renders [text] dark blue on transparent, sized for a field of the given aspect. */
    fun textBitmap(text: String, aspect: Float): Bitmap {
        val h = 96; val w = (h * aspect.coerceIn(1f, 20f)).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16, 42, 110); textSize = h * 0.62f }
        while (p.measureText(text) > w * 0.98f && p.textSize > 12f) p.textSize -= 2f
        val c = Canvas(bmp)
        val tw = p.measureText(text)
        c.drawText(text, (w - tw) / 2f, h * 0.72f, p)
        return bmp
    }

    // ---------------------------------------------------------------- certificate

    private const val PW = 1240; private const val PH = 1754; private const val MARGIN = 90

    fun certificateText(req: EsignRequest, values: List<SubmittedValues>, t: CertText, verifyUrl: String): List<Pair<String, Int>> {
        // (text, style) with style 0 = body, 1 = heading, 2 = title, 3 = mono
        val out = ArrayList<Pair<String, Int>>()
        out += t.title to 2
        out += "${t.document}: ${req.title}" to 0
        out += "${t.requestId}: ${req.id}" to 0
        out += "${t.sender}: ${req.sender}" to 0
        out += "${t.created}: ${AuditFormat.utc(req.created)}" to 0
        out += t.originalHash to 1
        if (AuditFormat.isSha256(req.originalSha)) AuditFormat.grouped(req.originalSha).forEach { out += it to 3 } else out += req.originalSha to 3
        out += t.signers to 1
        val ev = values.associateBy { it.signer }
        req.signers.forEachIndexed { i, s ->
            val st = when (s.status) { SignerStatus.SIGNED -> t.signed; SignerStatus.DECLINED -> t.declined; else -> t.pending }
            out += "${i + 1}. ${s.name}${if (s.email.isNotBlank()) " <${s.email}>" else ""} — ${t.status}: $st" to 0
            val e = s.event ?: ev[i]?.event
            if (s.signedAt > 0) out += "   ${t.time}: ${AuditFormat.utc(s.signedAt)}" to 0
            if (e != null) {
                out += "   ${t.ip}: ${e.ip}   ${t.device}: ${e.device.ifBlank { e.channel }}" to 0
                if (e.userAgent.isNotBlank()) out += "   ${t.agent}: ${e.userAgent.take(160)}" to 0
            }
            if (s.declineReason.isNotBlank()) out += "   «${s.declineReason}»" to 0
        }
        if (req.audit.isNotEmpty()) {
            out += t.audit to 1
            req.audit.sortedBy { it.time }.forEach { a ->
                val kind = t.kinds[a.kind] ?: a.kind
                out += "${AuditFormat.utc(a.time)}  $kind  ${a.who}${if (a.ip.isNotBlank()) "  (${a.ip})" else ""}" to 0
            }
        }
        out += t.verify to 1
        out += verifyUrl to 3
        out += t.verifyNote to 0
        return out
    }

    fun certificatePages(req: EsignRequest, values: List<SubmittedValues>, t: CertText, verifyUrl: String): List<Bitmap> {
        val lines = certificateText(req, values, t, verifyUrl)
        val width = PW - 2 * MARGIN
        val layouts = lines.map { (s, style) ->
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (style == 1 || style == 2) Color.rgb(20, 50, 120) else Color.rgb(30, 30, 30)
                textSize = when (style) { 2 -> 46f; 1 -> 32f; 3 -> 26f; else -> 26f }
                typeface = when (style) { 3 -> Typeface.MONOSPACE; 1, 2 -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
            }
            val align = if (style == 3) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
            StaticLayout.Builder.obtain(s, 0, s.length, p, width).setAlignment(align).setLineSpacing(4f, 1f).build() to style
        }
        val pages = ArrayList<Bitmap>()
        var bmp: Bitmap? = null; var c: Canvas? = null; var y = 0f
        fun newPage() {
            bmp = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888).also { pages += it }
            c = Canvas(bmp!!).apply { drawColor(Color.WHITE) }
            val frame = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.rgb(20, 50, 120) }
            c!!.drawRect(40f, 40f, PW - 40f, PH - 40f, frame)
            y = MARGIN.toFloat()
        }
        newPage()
        for ((layout, style) in layouts) {
            val gap = if (style == 1 || style == 2) 26f else 8f
            if (y + gap + layout.height > PH - MARGIN) newPage()
            y += gap
            c!!.save(); c!!.translate(MARGIN.toFloat(), y); layout.draw(c!!); c!!.restore()
            y += layout.height
        }
        return pages
    }
}
