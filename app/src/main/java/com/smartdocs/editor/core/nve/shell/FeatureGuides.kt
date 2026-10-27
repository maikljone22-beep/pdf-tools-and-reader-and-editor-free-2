package com.smartdocs.editor.core.nve.shell

import com.smartdocs.editor.core.edit.Bidi
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File
import java.util.Calendar

/**
 * Nitro "Feature Guides": sample workflow PDFs generated on the device (Arabic + English), each one a small
 * hands-on exercise (editable text, real form fields, sample comments, numbered pages to organise).
 * The Arabic font is optional so the generator also runs in JVM tests (Latin only then).
 */
object FeatureGuides {
    enum class Guide(val fileName: String) { EDIT("Guide - Edit text and images.pdf"), FORM("Guide - Fill and sign.pdf"), COMMENT("Guide - Comment and review.pdf"), ORGANIZE("Guide - Organize pages.pdf") }

    private class Writer(val doc: PDDocument, val arabic: PDFont?) {
        val latin: PDFont = PDType1Font.HELVETICA
        val bold: PDFont = PDType1Font.HELVETICA_BOLD

        fun page(): Pair<PDPage, PDPageContentStream> {
            val p = PDPage(PDRectangle.A4); doc.addPage(p)
            return p to PDPageContentStream(doc, p)
        }

        /** Left-aligned Latin line. */
        fun en(cs: PDPageContentStream, text: String, x: Float, y: Float, size: Float, b: Boolean = false) {
            cs.beginText(); cs.setFont(if (b) bold else latin, size); cs.newLineAtOffset(x, y); cs.showText(text); cs.endText()
        }

        /** Right-aligned Arabic line ending at [right] (skipped without an Arabic font). */
        fun ar(cs: PDPageContentStream, text: String, right: Float, y: Float, size: Float) {
            val f = arabic ?: return
            val visual = Bidi.shapeVisual(text, true)
            val w = runCatching { f.getStringWidth(visual) / 1000f * size }.getOrDefault(0f)
            cs.beginText(); cs.setFont(f, size); cs.newLineAtOffset(right - w, y)
            runCatching { cs.showText(visual) }
            cs.endText()
        }

        fun header(cs: PDPageContentStream, en: String, ar: String) {
            cs.setNonStrokingColor(0.10f, 0.34f, 0.75f)
            cs.addRect(0f, 782f, 595f, 60f); cs.fill()
            cs.setNonStrokingColor(1f, 1f, 1f)
            en(cs, en, 40f, 806f, 20f, true)
            ar(cs, ar, 555f, 806f, 18f)
            cs.setNonStrokingColor(0f, 0f, 0f)
        }

        fun steps(cs: PDPageContentStream, top: Float, en: List<String>, ar: List<String>): Float {
            var y = top
            en.forEachIndexed { i, s -> this.en(cs, "${i + 1}. $s", 40f, y, 12f); y -= 20f }
            y -= 10f
            ar.forEachIndexed { i, s -> this.ar(cs, "${i + 1}. $s", 555f, y, 13f); y -= 22f }
            return y
        }
    }

    /** Write [guide] into [out]; returns the number of pages. [arabic] loads the Arabic font into the document. */
    fun write(guide: Guide, out: File, arabic: ((PDDocument) -> PDFont?)? = null): Int {
        PDDocument().use { doc ->
            val w = Writer(doc, arabic?.invoke(doc))
            doc.documentInformation.title = guide.fileName.removeSuffix(".pdf")
            doc.documentInformation.creator = "SmartDocs"
            doc.documentInformation.creationDate = Calendar.getInstance()
            when (guide) {
                Guide.EDIT -> edit(w)
                Guide.FORM -> form(w)
                Guide.COMMENT -> comment(w)
                Guide.ORGANIZE -> organize(w)
            }
            doc.save(out)
            return doc.numberOfPages
        }
    }

    private fun edit(w: Writer) {
        val (_, cs) = w.page()
        cs.use {
            w.header(it, "Edit text and images", "تحرير النصوص والصور")
            var y = w.steps(it, 740f,
                listOf("Open the Edit tab and tap 'Edit content'.", "Tap the paragraph below to rewrite it.", "Switch to Images to move, resize or replace the blue box.", "Undo any change with the arrow at the top."),
                listOf("افتح تبويب التحرير ثم اضغط تحرير المحتوى.", "اضغط على الفقرة التالية لإعادة كتابتها.", "انتقل إلى الصور لتحريك المربع أو تغيير حجمه أو استبداله.", "تراجع عن أي تغيير بسهم التراجع في الأعلى."))
            y -= 20f
            w.en(it, "This paragraph is real page text. Change it, make it bold or justify it.", 40f, y, 13f); y -= 18f
            w.en(it, "Every edit is one step in the undo history.", 40f, y, 13f); y -= 26f
            w.ar(it, "هذه الفقرة نص حقيقي في الصفحة. عدّلها أو اجعلها عريضة.", 555f, y, 14f); y -= 40f
            it.setNonStrokingColor(0.13f, 0.59f, 0.95f); it.addRect(40f, y - 120f, 200f, 120f); it.fill()
            it.setStrokingColor(0.9f, 0.3f, 0.2f); it.setLineWidth(3f); it.addRect(300f, y - 120f, 200f, 120f); it.stroke()
        }
    }

    private fun form(w: Writer) {
        val (pg, cs) = w.page()
        cs.use {
            w.header(it, "Fill and sign a form", "تعبئة النماذج وتوقيعها")
            w.steps(it, 740f,
                listOf("Tap a field to type in it.", "Tick the check box.", "Open Fill & Sign to add your signature on the line.", "Save: the values are written into the file."),
                listOf("اضغط على حقل للكتابة فيه.", "ضع علامة في مربع الاختيار.", "افتح تبويب التعبئة والتوقيع لإضافة توقيعك على السطر.", "احفظ: تكتب القيم داخل الملف."))
            w.en(it, "Name", 40f, 470f, 12f); w.ar(it, "الاسم", 555f, 470f, 12f)
            w.en(it, "I agree", 40f, 420f, 12f); w.ar(it, "أوافق", 555f, 420f, 12f)
            it.setStrokingColor(0f, 0f, 0f); it.setLineWidth(1f); it.moveTo(40f, 330f); it.lineTo(300f, 330f); it.stroke()
            w.en(it, "Signature", 40f, 316f, 10f)
        }
        val doc = w.doc
        val form = PDAcroForm(doc)
        doc.documentCatalog.acroForm = form
        val dr = PDResources(); dr.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA); form.defaultResources = dr
        form.defaultAppearance = "/Helv 12 Tf 0 g"
        val name = PDTextField(form).apply { partialName = "name"; defaultAppearance = "/Helv 12 Tf 0 g" }
        val nw: PDAnnotationWidget = name.widgets[0]
        nw.rectangle = PDRectangle(140f, 462f, 300f, 22f); nw.page = pg
        pg.annotations.add(nw)
        val box = PDCheckBox(form).apply { partialName = "agree" }
        val wd: PDAnnotationWidget = box.widgets[0]
        wd.rectangle = PDRectangle(140f, 414f, 16f, 16f); wd.page = pg
        pg.annotations.add(wd)
        form.fields = listOf(name, box)
        runCatching { form.refreshAppearances() }
    }

    private fun comment(w: Writer) {
        val (page, cs) = w.page()
        cs.use {
            w.header(it, "Comment and review", "التعليق والمراجعة")
            w.steps(it, 740f,
                listOf("Long-press a word and choose Highlight.", "Open the Comment tab for notes, shapes and stamps.", "Open the comments list to reply, filter or export.", "Mark a comment as accepted or rejected."),
                listOf("اضغط مطولًا على كلمة واختر تمييز.", "افتح تبويب التعليق للملاحظات والأشكال والأختام.", "افتح قائمة التعليقات للرد أو التصفية أو التصدير.", "علّم التعليق كمقبول أو مرفوض."))
            w.en(it, "This sentence already has a highlight and a sticky note.", 40f, 470f, 13f)
        }
        val hl = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT)
        hl.rectangle = PDRectangle(38f, 466f, 330f, 18f)
        hl.quadPoints = floatArrayOf(38f, 484f, 368f, 484f, 38f, 466f, 368f, 466f)
        hl.color = PDColor(floatArrayOf(1f, 0.92f, 0.23f), PDDeviceRGB.INSTANCE)
        hl.contents = "Sample highlight"; hl.titlePopup = "SmartDocs"
        page.annotations.add(hl)
        val note = PDAnnotationText()
        note.rectangle = PDRectangle(380f, 466f, 20f, 20f)
        note.contents = "Reply to this note / رُدّ على هذه الملاحظة"; note.titlePopup = "SmartDocs"
        note.color = PDColor(floatArrayOf(1f, 0.6f, 0f), PDDeviceRGB.INSTANCE)
        page.annotations.add(note)
    }

    private fun organize(w: Writer) {
        for (i in 1..4) {
            val (_, cs) = w.page()
            cs.use {
                w.header(it, "Organize pages - page $i", "تنظيم الصفحات - صفحة $i")
                if (i == 1) w.steps(it, 740f,
                    listOf("Open the Pages tab.", "Drag the thumbnails into the order 1, 2, 3, 4 (they are shuffled below).", "Rotate, delete, extract or insert pages."),
                    listOf("افتح تبويب الصفحات.", "اسحب المصغرات لترتيبها 1، 2، 3، 4.", "دوّر الصفحات أو احذفها أو استخرجها أو أدرج صفحات."))
                it.setNonStrokingColor(0.85f, 0.88f, 0.95f)
                it.addRect(197f, 200f, 200f, 260f); it.fill()
                it.setNonStrokingColor(0.10f, 0.34f, 0.75f)
                w.en(it, i.toString(), 270f, 290f, 110f, true)
                it.setNonStrokingColor(0f, 0f, 0f)
            }
        }
        // shuffle so there is something to organise: 3, 1, 4, 2
        val pages = w.doc.pages.toList()
        val order = listOf(2, 0, 3, 1)
        order.map { pages[it] }.let { list -> pages.forEach { w.doc.removePage(it) }; list.forEach { w.doc.addPage(it) } }
    }
}
