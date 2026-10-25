package com.smartdocs.editor.core.templates

import com.smartdocs.editor.core.pdf.FieldType
import com.smartdocs.editor.core.templates.TemplateRenderer.M

/** Document templates: official letter, invoice, certificate, meeting minutes, exam sheet. */
internal object TemplateDocs {

    private const val INK = 0x212121
    private const val MUTED = 0x616161

    // ---------------------------------------------------------------- خطاب رسمي

    fun letter(cv: TplCanvas, input: TemplateInput) {
        val f = input.withFields
        cv.newPage(input.pageSize, input.landscape ?: false)
        var y = TemplateRenderer.letterhead(cv, input)
        val right = cv.w - M; val mid = cv.w / 2f
        y -= 8f
        cv.labeledField("الرقم", "ref_no", right, mid + 30f, y, 11f, f)
        cv.labeledField("التاريخ", "date", mid - 10f, M, y, 11f, f, value = input.date)
        y -= 34f
        cv.labeledField("إلى", "recipient", right, M + 70f, y, 12f, f)
        cv.text("المحترم", M + 60f, y, 12f, Align.RIGHT, INK, bold = true)
        y -= 32f
        cv.labeledField("الموضوع", "subject", right, M, y, 12f, f)
        y -= 34f
        cv.text("السلام عليكم ورحمة الله وبركاته، وبعد:", right, y, 12f, Align.RIGHT, INK)
        y -= 14f
        val bodyBottom = M + 150f
        cv.roundRect(M, bodyBottom, cv.w - 2 * M, y - bodyBottom, 6f, stroke = 0xE0E0E0, lineWidth = 0.6f)
        if (f) cv.field(FieldType.TEXT, "body", M + 4f, bodyBottom + 4f, cv.w - 2 * M - 8f, y - bodyBottom - 8f, tooltip = "نص الخطاب", multiline = true)
        else { var ly = y - 24f; while (ly > bodyBottom + 10f) { cv.line(M + 10f, ly, right - 10f, ly, 0xEEEEEE, 0.5f); ly -= 24f } }
        var sy = bodyBottom - 22f
        cv.text("وتفضلوا بقبول فائق الاحترام والتقدير،", right, sy, 12f, Align.RIGHT, INK)
        sy -= 30f
        cv.labeledField("الاسم", "signer_name", M + 230f, M, sy, 11f, f)
        cv.labeledField("التوقيع", "signature_text", M + 230f, M, sy - 28f, 11f, false)
        if (f) cv.field(FieldType.SIGNATURE, "signature", M, sy - 34f, 160f, 26f, tooltip = "التوقيع")
        cv.circle(right - 50f, sy - 14f, 32f, stroke = 0xBDBDBD, lineWidth = 0.6f)
        cv.text("الختم", right - 50f, sy - 18f, 10f, Align.CENTER, 0xBDBDBD)
        TemplateRenderer.footer(cv, input.footer.ifBlank { input.subtitle }, input.accent)
    }

    // ---------------------------------------------------------------- فاتورة

    fun invoice(cv: TplCanvas, input: TemplateInput) {
        val f = input.withFields
        val rows = (if (input.rows > 0) input.rows else TemplateKind.INVOICE.defaultRows).coerceIn(TemplateKind.INVOICE.rowsRange)
        val accent = input.accent
        val currency = input.currency.ifBlank { "ر.ي" }
        cv.newPage(input.pageSize, input.landscape ?: false)
        var y = TemplateRenderer.letterhead(cv, input)
        val right = cv.w - M
        cv.text(input.title.ifBlank { TemplateDefaults.title(TemplateKind.INVOICE) }, M, y - 18f, 24f, Align.LEFT, accent, bold = true)
        y -= 6f
        cv.labeledField("رقم الفاتورة", "invoice_no", right, right - 210f, y - 12f, 11f, f)
        cv.labeledField("التاريخ", "date", right, right - 210f, y - 38f, 11f, f, value = input.date)
        y -= 66f
        cv.roundRect(M, y - 52f, cv.w - 2 * M, 56f, 6f, stroke = null, fill = cv.tint(accent, 0.93f))
        cv.labeledField("اسم العميل", "customer", right - 10f, cv.w / 2f + 10f, y - 16f, 11f, f)
        cv.labeledField("الهاتف", "customer_phone", cv.w / 2f - 10f, M + 10f, y - 16f, 11f, f)
        cv.labeledField("العنوان", "customer_address", right - 10f, M + 10f, y - 42f, 11f, f)
        y -= 70f

        // table (RTL): #, description, qty, unit price, total
        val colNo = 28f; val colQty = 58f; val colPrice = 84f; val colTotal = 96f
        val colDesc = cv.w - 2 * M - colNo - colQty - colPrice - colTotal
        val xs = floatArrayOf(right, right - colNo, right - colNo - colDesc, right - colNo - colDesc - colQty, right - colNo - colDesc - colQty - colPrice, M)
        val headH = 24f; val rowH = 22f
        cv.rect(M, y - headH, cv.w - 2 * M, headH, stroke = null, fill = accent)
        val heads = listOf("#", "الوصف", "الكمية", "سعر الوحدة", "المجموع ($currency)")
        heads.forEachIndexed { i, t -> cv.text(t, (xs[i] + xs[i + 1]) / 2f, y - headH + 8f, 10f, Align.CENTER, 0xFFFFFF, bold = true) }
        y -= headH
        val numFmt = "AFNumber_Format(2, 0, 0, 0, \"\", true);"
        val numKey = "AFNumber_Keystroke(2, 0, 0, 0, \"\", true);"
        for (i in 1..rows) {
            val yb = y - rowH
            if (i % 2 == 0) cv.rect(M, yb, cv.w - 2 * M, rowH, stroke = null, fill = cv.tint(accent, 0.95f))
            cv.text("$i", (xs[0] + xs[1]) / 2f, yb + 7f, 10f, Align.CENTER, MUTED)
            if (f) {
                cv.field(FieldType.TEXT, "desc_$i", xs[2] + 3f, yb + 3f, colDesc - 6f, rowH - 6f, tooltip = "الوصف $i")
                cv.field(FieldType.TEXT, "qty_$i", xs[3] + 3f, yb + 3f, colQty - 6f, rowH - 6f, tooltip = "الكمية $i", script = FieldScript(format = "AFNumber_Format(0, 0, 0, 0, \"\", true);", keystroke = "AFNumber_Keystroke(0, 0, 0, 0, \"\", true);"))
                cv.field(FieldType.TEXT, "price_$i", xs[4] + 3f, yb + 3f, colPrice - 6f, rowH - 6f, tooltip = "سعر الوحدة $i", script = FieldScript(format = numFmt, keystroke = numKey))
                cv.field(FieldType.TEXT, "total_$i", xs[5] + 3f, yb + 3f, colTotal - 6f, rowH - 6f, tooltip = "المجموع $i",
                    script = FieldScript(calc = "AFSimple_Calculate(\"PRD\", new Array(\"qty_$i\", \"price_$i\"));", format = numFmt))
            }
            y = yb
        }
        // grid
        val tableTop = y + rows * rowH + headH
        for (i in 0..rows) cv.line(M, y + i * rowH, right, y + i * rowH, 0xCFD8DC, 0.5f)
        xs.forEach { x -> cv.line(x, y, x, tableTop, 0xCFD8DC, 0.5f) }

        // totals (left side)
        y -= 8f
        val labelRight = M + colTotal + colPrice + 20f
        fun totalRow(label: String, name: String, script: FieldScript?, strong: Boolean = false) {
            val yb = y - rowH
            if (strong) cv.rect(M, yb, colTotal + colPrice + 30f + 60f, rowH, stroke = null, fill = cv.tint(accent, 0.85f))
            cv.text(label, labelRight + 60f, yb + 7f, 10.5f, Align.RIGHT, if (strong) accent else INK, bold = true)
            cv.rect(M, yb + 2f, colTotal + 10f, rowH - 4f, stroke = 0xB0BEC5, lineWidth = 0.6f)
            if (f) cv.field(FieldType.TEXT, name, M + 2f, yb + 3f, colTotal + 6f, rowH - 6f, tooltip = label, script = script)
            y = yb - 2f
        }
        val totals = (1..rows).joinToString(", ") { "\"total_$it\"" }
        totalRow("المجموع الفرعي", "subtotal", FieldScript(calc = "AFSimple_Calculate(\"SUM\", new Array($totals));", format = numFmt))
        totalRow("نسبة الضريبة %", "tax_rate", FieldScript(format = "AFNumber_Format(2, 0, 0, 0, \"\", true);", keystroke = numKey))
        totalRow("الضريبة", "tax", FieldScript(
            calc = "/** BVCALC subtotal * tax_rate / 100 EVCALC **/ event.value = AFMakeNumber(getField(\"subtotal\").value) * AFMakeNumber(getField(\"tax_rate\").value) / 100;",
            format = numFmt))
        totalRow("الإجمالي ($currency)", "grand_total", FieldScript(calc = "AFSimple_Calculate(\"SUM\", new Array(\"subtotal\", \"tax\"));", format = numFmt), strong = true)

        // notes (right side, next to the totals)
        val notesTop = y + 4 * (rowH + 2f)
        val notesLeft = labelRight + 80f
        cv.text("ملاحظات:", right, notesTop - 14f, 10.5f, Align.RIGHT, INK, bold = true)
        cv.roundRect(notesLeft, y + 4f, right - notesLeft, notesTop - y - 26f, 5f, stroke = 0xE0E0E0, lineWidth = 0.6f)
        if (f) cv.field(FieldType.TEXT, "notes", notesLeft + 3f, y + 7f, right - notesLeft - 6f, notesTop - y - 32f, tooltip = "ملاحظات", multiline = true)
        cv.text("التوقيع / الختم:", right, M + 34f, 10.5f, Align.RIGHT, INK, bold = true)
        cv.line(right - 220f, M + 30f, right - 90f, M + 30f, 0xBDBDBD, 0.6f)
        TemplateRenderer.footer(cv, input.footer.ifBlank { "شكراً لتعاملكم معنا" }, accent)
    }

    // ---------------------------------------------------------------- شهادة تقدير

    fun certificate(cv: TplCanvas, input: TemplateInput) {
        val f = input.withFields
        val accent = input.accent
        cv.newPage(input.pageSize, input.landscape ?: true)
        val gold = 0xC9A227
        // frame
        cv.rect(0f, 0f, cv.w, cv.h, stroke = null, fill = cv.tint(gold, 0.93f))
        cv.rect(18f, 18f, cv.w - 36f, cv.h - 36f, stroke = accent, lineWidth = 6f)
        cv.rect(30f, 30f, cv.w - 60f, cv.h - 60f, stroke = gold, lineWidth = 1.6f)
        cv.rect(36f, 36f, cv.w - 72f, cv.h - 72f, stroke = gold, lineWidth = 0.5f)
        listOf(30f to 30f, cv.w - 30f to 30f, 30f to cv.h - 30f, cv.w - 30f to cv.h - 30f).forEach { (x, y) ->
            cv.circle(x, y, 9f, stroke = gold, fill = accent, lineWidth = 1.5f)
            cv.circle(x, y, 3f, stroke = null, fill = gold)
        }
        var y = cv.h - 62f
        if (input.logo != null) { cv.image(input.logo, cv.w / 2f - 30f, y - 60f, 60f, 60f); y -= 70f }
        if (input.org.isNotBlank()) { cv.text(input.org, cv.w / 2f, y - 14f, 15f, Align.CENTER, MUTED, bold = true); y -= 26f }
        y -= 34f
        cv.text(input.title.ifBlank { TemplateDefaults.title(TemplateKind.CERTIFICATE) }, cv.w / 2f, y, 38f, Align.CENTER, accent, bold = true)
        cv.line(cv.w / 2f - 120f, y - 14f, cv.w / 2f + 120f, y - 14f, gold, 1.4f)
        y -= 50f
        cv.text("تُمنح هذه الشهادة إلى", cv.w / 2f, y, 16f, Align.CENTER, INK)
        y -= 46f
        if (input.subtitle.isNotBlank()) cv.text(input.subtitle, cv.w / 2f, y, 30f, Align.CENTER, 0x0D47A1, bold = true)
        else if (f) cv.field(FieldType.TEXT, "recipient", cv.w / 2f - 200f, y - 8f, 400f, 36f, tooltip = "اسم المكرَّم")
        cv.line(cv.w / 2f - 210f, y - 12f, cv.w / 2f + 210f, y - 12f, 0xBDBDBD, 0.6f, floatArrayOf(2f, 2f))
        y -= 40f
        y = cv.paragraph(input.body.ifBlank { TemplateDefaults.body(TemplateKind.CERTIFICATE) }, cv.w / 2f, y, 14f, cv.w - 260f, Align.CENTER, 0x37474F, 1.7f)
        // signatures
        val sy = 92f
        cv.line(cv.w - 260f, sy, cv.w - 110f, sy, 0x757575, 0.7f)
        cv.text("التوقيع", cv.w - 185f, sy - 16f, 12f, Align.CENTER, MUTED)
        cv.line(110f, sy, 260f, sy, 0x757575, 0.7f)
        cv.text("التاريخ", 185f, sy - 16f, 12f, Align.CENTER, MUTED)
        if (input.date.isNotBlank()) cv.text(input.date, 185f, sy + 6f, 12f, Align.CENTER, INK)
        else if (f) cv.field(FieldType.TEXT, "date", 110f, sy + 2f, 150f, 20f, tooltip = "التاريخ")
        if (f) cv.field(FieldType.SIGNATURE, "signature", cv.w - 260f, sy + 2f, 150f, 34f, tooltip = "التوقيع")
        cv.circle(cv.w / 2f, sy - 2f, 34f, stroke = gold, lineWidth = 1.2f)
        cv.circle(cv.w / 2f, sy - 2f, 29f, stroke = gold, lineWidth = 0.5f)
        cv.text("الختم", cv.w / 2f, sy - 6f, 11f, Align.CENTER, gold)
    }

    // ---------------------------------------------------------------- محضر اجتماع

    fun minutes(cv: TplCanvas, input: TemplateInput) {
        val f = input.withFields
        val accent = input.accent
        val rows = (if (input.rows > 0) input.rows else TemplateKind.MINUTES.defaultRows).coerceIn(TemplateKind.MINUTES.rowsRange)
        cv.newPage(input.pageSize, input.landscape ?: false)
        var y = TemplateRenderer.letterhead(cv, input)
        val right = cv.w - M; val mid = cv.w / 2f
        cv.text(input.title.ifBlank { TemplateDefaults.title(TemplateKind.MINUTES) }, mid, y - 10f, 20f, Align.CENTER, accent, bold = true)
        y -= 40f
        cv.labeledField("التاريخ", "date", right, mid + 10f, y, 11f, f, value = input.date)
        cv.labeledField("الوقت", "time", mid - 10f, M, y, 11f, f)
        y -= 26f
        cv.labeledField("المكان", "place", right, mid + 10f, y, 11f, f)
        cv.labeledField("رئيس الاجتماع", "chair", mid - 10f, M, y, 11f, f)
        y -= 26f
        cv.labeledField("الحاضرون", "attendees", right, M, y, 11f, f)
        y -= 26f
        cv.labeledField("المعتذرون", "absent", right, M, y, 11f, f)
        y -= 26f
        // agenda
        y = TemplateRenderer.sectionTitle(cv, "جدول الأعمال", y, accent)
        val agendaH = 70f
        cv.roundRect(M, y - agendaH, cv.w - 2 * M, agendaH, 5f, stroke = 0xE0E0E0, lineWidth = 0.6f)
        if (f) cv.field(FieldType.TEXT, "agenda", M + 3f, y - agendaH + 3f, cv.w - 2 * M - 6f, agendaH - 6f, tooltip = "جدول الأعمال", multiline = true)
        y -= agendaH + 18f
        // decisions table sized from the bottom up, discussion takes the rest
        val rowH = 22f
        val signH = 56f
        val decisionsH = 16f + 22f + rows * rowH
        val discussionH = (y - 14f - decisionsH - signH - M - 12f).coerceAtLeast(60f)
        y = TemplateRenderer.sectionTitle(cv, "المناقشات", y, accent)
        cv.roundRect(M, y - discussionH, cv.w - 2 * M, discussionH, 5f, stroke = 0xE0E0E0, lineWidth = 0.6f)
        if (f) cv.field(FieldType.TEXT, "discussion", M + 3f, y - discussionH + 3f, cv.w - 2 * M - 6f, discussionH - 6f, tooltip = "المناقشات", multiline = true)
        y -= discussionH + 18f
        y = TemplateRenderer.sectionTitle(cv, "القرارات والتوصيات", y, accent)
        val colNo = 26f; val colWho = 120f; val colWhen = 90f
        val colWhat = cv.w - 2 * M - colNo - colWho - colWhen
        val xs = floatArrayOf(right, right - colNo, right - colNo - colWhat, right - colNo - colWhat - colWho, M)
        cv.rect(M, y - 22f, cv.w - 2 * M, 22f, stroke = null, fill = cv.tint(accent, 0.8f))
        listOf("#", "القرار / التوصية", "المسؤول", "موعد التنفيذ").forEachIndexed { i, t -> cv.text(t, (xs[i] + xs[i + 1]) / 2f, y - 15f, 10f, Align.CENTER, INK, bold = true) }
        y -= 22f
        for (i in 1..rows) {
            val yb = y - rowH
            cv.text("$i", (xs[0] + xs[1]) / 2f, yb + 7f, 10f, Align.CENTER, MUTED)
            if (f) {
                cv.field(FieldType.TEXT, "decision_$i", xs[2] + 3f, yb + 3f, colWhat - 6f, rowH - 6f, tooltip = "القرار $i")
                cv.field(FieldType.TEXT, "owner_$i", xs[3] + 3f, yb + 3f, colWho - 6f, rowH - 6f, tooltip = "المسؤول $i")
                cv.field(FieldType.TEXT, "due_$i", xs[4] + 3f, yb + 3f, colWhen - 6f, rowH - 6f, tooltip = "الموعد $i")
            }
            cv.line(M, yb, right, yb, 0xCFD8DC, 0.5f)
            y = yb
        }
        val tableTop = y + rows * rowH + 22f
        xs.forEach { x -> cv.line(x, y, x, tableTop, 0xCFD8DC, 0.5f) }
        // signatures
        val sy = M + 22f
        cv.text("مقرر الاجتماع", right - 60f, sy + 20f, 11f, Align.CENTER, INK, bold = true)
        cv.line(right - 130f, sy, right, sy, 0xBDBDBD, 0.6f)
        cv.text("رئيس الاجتماع", M + 60f, sy + 20f, 11f, Align.CENTER, INK, bold = true)
        cv.line(M, sy, M + 130f, sy, 0xBDBDBD, 0.6f)
        if (f) {
            cv.field(FieldType.SIGNATURE, "sign_secretary", right - 130f, sy + 1f, 130f, 16f, tooltip = "مقرر الاجتماع")
            cv.field(FieldType.SIGNATURE, "sign_chair", M, sy + 1f, 130f, 16f, tooltip = "رئيس الاجتماع")
        }
    }

    // ---------------------------------------------------------------- ورقة امتحان

    fun exam(cv: TplCanvas, input: TemplateInput) {
        val f = input.withFields
        val accent = input.accent
        val questions = (if (input.rows > 0) input.rows else TemplateKind.EXAM.defaultRows).coerceIn(TemplateKind.EXAM.rowsRange)
        cv.newPage(input.pageSize, input.landscape ?: false)
        val right = cv.w - M; val mid = cv.w / 2f
        // header box: right = organisation, centre = logo, left = exam title / subject / time
        val boxTop = cv.h - M + 6f; val boxH = 84f
        cv.roundRect(M, boxTop - boxH, cv.w - 2 * M, boxH, 8f, stroke = accent, lineWidth = 1.2f)
        val orgLines = input.org.ifBlank { "اسم المدرسة" }.split('\n')
        orgLines.take(3).forEachIndexed { i, l -> cv.text(l, right - 12f, boxTop - 22f - i * 18f, 12f, Align.RIGHT, INK, bold = i == 0) }
        if (input.logo != null) cv.image(input.logo, mid - 30f, boxTop - boxH + 12f, 60f, 60f)
        cv.text(input.title.ifBlank { TemplateDefaults.title(TemplateKind.EXAM) }, M + 12f, boxTop - 22f, 14f, Align.LEFT, accent, bold = true)
        if (input.subtitle.isNotBlank()) cv.text(input.subtitle, M + 12f, boxTop - 42f, 11f, Align.LEFT, INK)
        if (input.date.isNotBlank()) cv.text("التاريخ: ${input.date}", M + 12f, boxTop - 62f, 10.5f, Align.LEFT, MUTED)
        var y = boxTop - boxH - 18f
        // student box + score box
        val scoreW = 92f
        cv.roundRect(M + scoreW + 10f, y - 62f, cv.w - 2 * M - scoreW - 10f, 66f, 6f, stroke = 0xB0BEC5, lineWidth = 0.8f)
        cv.labeledField("اسم الطالب", "student_name", right - 10f, M + scoreW + 20f, y - 16f, 11f, f)
        val colMid = (right + M + scoreW) / 2f
        cv.labeledField("الصف", "class", right - 10f, colMid + 10f, y - 44f, 11f, f)
        cv.labeledField("الشعبة", "section", colMid - 4f, colMid - 80f, y - 44f, 11f, f)
        cv.labeledField("رقم الجلوس", "seat_no", colMid - 90f, M + scoreW + 20f, y - 44f, 11f, f)
        cv.roundRect(M, y - 62f, scoreW, 66f, 6f, stroke = accent, fill = cv.tint(accent, 0.92f), lineWidth = 1f)
        cv.text("الدرجة", M + scoreW / 2f, y - 14f, 11f, Align.CENTER, accent, bold = true)
        cv.line(M + 10f, y - 36f, M + scoreW - 10f, y - 36f, accent, 0.6f)
        if (f) {
            cv.field(FieldType.TEXT, "score", M + 8f, y - 34f, scoreW - 16f, 16f, tooltip = "الدرجة")
            cv.field(FieldType.TEXT, "score_max", M + 8f, y - 58f, scoreW - 16f, 16f, tooltip = "الدرجة الكاملة")
        }
        y -= 80f
        // instructions
        val instructions = input.body.ifBlank { TemplateDefaults.body(TemplateKind.EXAM) }
        val lines = cv.wrap(instructions, 10.5f, cv.w - 2 * M - 24f)
        val instH = 26f + lines.size * 17f
        cv.roundRect(M, y - instH, cv.w - 2 * M, instH, 6f, stroke = null, fill = cv.tint(accent, 0.94f))
        cv.text("التعليمات:", right - 10f, y - 16f, 11f, Align.RIGHT, accent, bold = true)
        var iy = y - 33f
        lines.forEach { l -> cv.text(l, right - 14f, iy, 10.5f, Align.RIGHT, 0x37474F); iy -= 17f }
        y -= instH + 16f

        // questions with ruled answer lines, flowing over the requested number of pages
        val totalPages = input.pages.coerceIn(1, 20)
        var page = 1
        val lineStep = 22f
        val firstPageSpace = y - M
        val perQuestion = ((firstPageSpace + (totalPages - 1) * (cv.h - 2 * M)) / questions).coerceAtLeast(80f)
        for (q in 0 until questions) {
            if (y - 60f < M) {
                cv.newPage(input.pageSize, input.landscape ?: false); page++
                y = cv.h - M
                cv.text("${input.subtitle.ifBlank { input.title }}  —  ${page}", mid, M - 20f, 9f, Align.CENTER, 0x9E9E9E)
            }
            val label = "السؤال ${TemplateRenderer.ordinals.getOrElse(q) { "${q + 1}" }}:"
            cv.rect(right - 4f, y - 4f, 4f, 16f, stroke = null, fill = accent)
            cv.text(label, right - 10f, y, 12.5f, Align.RIGHT, accent, bold = true)
            if (f) cv.field(FieldType.TEXT, "q${q + 1}_mark", M, y - 4f, 46f, 16f, tooltip = "درجة السؤال ${q + 1}")
            cv.text("الدرجة", M + 54f, y, 9f, Align.LEFT, MUTED)
            var ly = y - lineStep
            val end = maxOf(y - perQuestion + 16f, M)
            while (ly > end && ly > M) { cv.line(M, ly, right, ly, 0xCFD8DC, 0.5f, floatArrayOf(1f, 2f)); ly -= lineStep }
            y = ly - 10f
        }
        cv.text("انتهت الأسئلة — مع تمنياتنا لكم بالتوفيق والنجاح", mid, M - 6f, 10f, Align.CENTER, MUTED)
    }
}
