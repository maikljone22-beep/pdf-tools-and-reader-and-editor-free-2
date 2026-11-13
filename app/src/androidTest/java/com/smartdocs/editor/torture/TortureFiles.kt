package com.smartdocs.editor.torture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.smartdocs.editor.core.office5.OPackage
import com.smartdocs.editor.core.office5.WordParts
import com.smartdocs.editor.core.office5.XlsxIO5
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/**
 * Generators for the heavy files the app has to survive (the PDFs are in [TorturePdf]): a scan of hundreds of MB, a
 * 50-megapixel photo, Word files full of pictures, a big workbook, a big CSV. They are built on the device, so nothing
 * large is stored in git or pushed to the emulator, and every file is the same on every run.
 */
object TortureFiles {
    private const val MB = 1024L * 1024

    // ---------------------------------------------------------------------------------------------- images

    /**
     * A page as a scanner sees it: off-white paper with grain, about 55 lines of dark "words". The grain keeps the JPEG
     * from compressing to nothing, like a real scan.
     */
    private fun scannedPageJpeg(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val rnd = Random(42)
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) { val g = 236 + rnd.nextInt(16); row[x] = Color.rgb(g, g, g - 4) }
                bmp.setPixels(row, 0, width, 0, y, width, 1)
            }
            val c = Canvas(bmp)
            val p = Paint().apply { color = Color.rgb(28, 28, 32); strokeWidth = height / 190f }
            val left = width * 0.1f; val right = width * 0.9f
            for (line in 0 until 55) {
                val y = height * (0.08f + line * 0.0155f)
                var x = left
                while (x < right) { val w = width * (0.012f + rnd.nextFloat() * 0.05f); c.drawLine(x, y, minOf(x + w, right), y, p); x += w + width * 0.012f }
            }
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        } finally { bmp.recycle() }
    }

    /** A scanned PDF of about [targetMb]: A4 pages at 300 dpi (2480 x 3508 px), one full-page image each. Returns the page count. */
    fun scanPdf(out: File, targetMb: Int): Int = TorturePdf.scan(out, scannedPageJpeg(2480, 3508), 2480, 3508, targetMb * MB)

    /** Random-noise JPEG (does not compress, so it is as heavy as a detailed photo). */
    fun photoJpeg(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val rnd = Random(7)
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) row[x] = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
                bmp.setPixels(row, 0, width, 0, y, width, 1)
            }
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        } finally { bmp.recycle() }
    }

    /** A 50-megapixel (8165 x 6124) JPEG photo: a gradient with thousands of coloured lines, written straight to [out]. */
    fun jpeg50mp(out: File) {
        val w = 8165; val h = 6124
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            val p = Paint()
            p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(20, 60, 160), Color.rgb(250, 200, 40), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null; p.strokeWidth = 3f
            val rnd = Random(7)
            repeat(6000) { p.color = Color.argb(160, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)); c.drawLine(rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(), rnd.nextInt(w).toFloat(), rnd.nextInt(h).toFloat(), p) }
            out.outputStream().buffered().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        } finally { bmp.recycle() }
    }

    // ---------------------------------------------------------------------------------------------- Office

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun pictureParagraph(i: Int, rid: String): String =
        "<w:p><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"5486400\" cy=\"4114800\"/><wp:docPr id=\"$i\" name=\"Picture $i\"/>" +
            "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"$i\" name=\"image$i.jpeg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"5486400\" cy=\"4114800\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>" +
            "<w:p><w:r><w:t>Figure $i</w:t></w:r></w:p>"

    /**
     * A .docx of about [targetMb], made of 2000 x 1500 px photos with a caption each. Returns the picture count.
     * (While it is being built the pictures share one byte array; the saved file holds a full copy of each.)
     */
    fun docxWithPhotos(out: File, targetMb: Int): Int {
        val jpeg = photoJpeg(2000, 1500)
        val images = (targetMb * MB / jpeg.size + 1).toInt().coerceIn(2, 2000)
        val pkg = WordParts.blankDocx(listOf(""), rtl = false)
        pkg.ensureDefault("jpeg", "image/jpeg")
        val body = StringBuilder()
        for (i in 1..images) {
            val path = "word/media/image$i.jpeg"
            pkg.put(path, jpeg)
            body.append(pictureParagraph(i, pkg.addRel("word/document.xml", OPackage.REL_IMAGE, path)))
        }
        val doc = OPackage.DECL + "<w:document xmlns:w=\"$W\" xmlns:r=\"${OPackage.R_NS}\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"><w:body>" +
            body + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        pkg.put("word/document.xml", doc.toByteArray())
        pkg.saveTo(out)
        return images
    }

    /** A workbook with [rows] x 6 numeric cells (a ledger or data export). */
    fun xlsxRows(out: File, rows: Int) {
        val pkg = XlsxIO5.blankPackage("Data", rtl = false)
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        val cols = "ABCDEF"
        for (r in 1..rows) {
            sb.append("<row r=\"$r\">")
            for (c in 0 until 6) sb.append("<c r=\"${cols[c]}$r\"><v>${r * (c + 1) % 99991}.${(r + c) % 100}</v></c>")
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        pkg.put("xl/worksheets/sheet1.xml", sb.toString().toByteArray())
        pkg.saveTo(out)
    }

    /** A CSV of about [targetMb] (id, name, amount, date, note). */
    fun csv(out: File, targetMb: Int) {
        val limit = targetMb * MB
        BufferedOutputStream(out.outputStream(), 1 shl 16).use { os ->
            var n = 0L; var size = 0L
            val head = "id,name,amount,date,note\n".toByteArray(); os.write(head); size += head.size
            while (size < limit) {
                val line = "${++n},customer ${n % 5000},${n * 37 % 100000}.${n % 100},2026-${n % 12 + 1}-${n % 28 + 1},\"note, with a comma ${n % 777}\"\n".toByteArray()
                os.write(line); size += line.size
            }
        }
    }
}
