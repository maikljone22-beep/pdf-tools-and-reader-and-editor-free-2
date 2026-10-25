package com.smartdocs.editor.core.templates

import com.smartdocs.editor.core.pdf.FieldType
import com.smartdocs.editor.core.templates.TemplateRenderer.M

/** Paper-style templates: ruled, square grid, graph (mm), music staves, to-do list, weekly timetable. */
internal object TemplatePapers {

    private const val MM = 2.8346f

    private fun pages(input: TemplateInput) = input.pages.coerceIn(1, 50)

    /** Small header: title at the right, "التاريخ" line at the left. Returns the y under it. */
    private fun notebookHeader(cv: TplCanvas, title: String, accent: Int): Float {
        val y = cv.h - M - 6f
        if (title.isNotBlank()) cv.text(title, cv.w - M, y, 15f, Align.RIGHT, accent, bold = true)
        cv.text("التاريخ:", M + 120f, y, 10f, Align.RIGHT, 0x757575)
        cv.line(M, y - 3f, M + 70f, y - 3f, 0xBDBDBD, 0.6f)
        return y - 20f
    }

    fun lined(cv: TplCanvas, input: TemplateInput) {
        repeat(pages(input)) {
            cv.newPage(input.pageSize, input.landscape ?: false)
            val top = notebookHeader(cv, input.title, input.accent)
            val step = 8f * MM
            // margin line on the right for Arabic writing, light blue rules
            cv.line(cv.w - M - 28f, M - 10f, cv.w - M - 28f, top + 8f, 0xEF9A9A, 0.9f)
            var y = top - 8f
            while (y > M) { cv.line(M - 14f, y, cv.w - M + 14f, y, 0x90CAF9, 0.55f); y -= step }
        }
    }

    fun grid(cv: TplCanvas, input: TemplateInput) {
        repeat(pages(input)) {
            cv.newPage(input.pageSize, input.landscape ?: false)
            val top = if (input.title.isNotBlank()) notebookHeader(cv, input.title, input.accent) else cv.h - M + 10f
            val step = 5f * MM
            val left = M - 10f; val right = cv.w - M + 10f; val bottom = M - 10f
            val cols = ((right - left) / step).toInt(); val rows = ((top - bottom) / step).toInt()
            val gw = cols * step; val gh = rows * step
            val x0 = left + ((right - left) - gw) / 2f; val y0 = bottom
            for (i in 0..cols) cv.line(x0 + i * step, y0, x0 + i * step, y0 + gh, 0xB3C7E6, 0.45f)
            for (j in 0..rows) cv.line(x0, y0 + j * step, x0 + gw, y0 + j * step, 0xB3C7E6, 0.45f)
        }
    }

    fun graph(cv: TplCanvas, input: TemplateInput) {
        repeat(pages(input)) {
            cv.newPage(input.pageSize, input.landscape ?: false)
            val top = if (input.title.isNotBlank()) notebookHeader(cv, input.title, input.accent) else cv.h - M + 10f
            val unit = MM
            val left = M - 10f; val right = cv.w - M + 10f; val bottom = M - 10f
            val cols = (((right - left) / unit).toInt() / 10) * 10
            val rows = (((top - bottom) / unit).toInt() / 10) * 10
            val gw = cols * unit; val gh = rows * unit
            val x0 = left + ((right - left) - gw) / 2f; val y0 = bottom
            for (pass in 0..2) {   // draw fine lines first, then 5 mm, then 10 mm on top
                for (i in 0..cols) {
                    val major = i % 10 == 0; val mid = i % 5 == 0
                    if ((pass == 0 && !mid) || (pass == 1 && mid && !major) || (pass == 2 && major))
                        cv.line(x0 + i * unit, y0, x0 + i * unit, y0 + gh, if (major) 0xE57373 else if (mid) 0xEF9A9A else 0xFADBD8, if (major) 0.7f else if (mid) 0.45f else 0.25f)
                }
                for (j in 0..rows) {
                    val major = j % 10 == 0; val mid = j % 5 == 0
                    if ((pass == 0 && !mid) || (pass == 1 && mid && !major) || (pass == 2 && major))
                        cv.line(x0, y0 + j * unit, x0 + gw, y0 + j * unit, if (major) 0xE57373 else if (mid) 0xEF9A9A else 0xFADBD8, if (major) 0.7f else if (mid) 0.45f else 0.25f)
                }
            }
        }
    }

    fun music(cv: TplCanvas, input: TemplateInput) {
        repeat(pages(input)) { p ->
            cv.newPage(input.pageSize, input.landscape ?: false)
            var y = cv.h - M
            if (p == 0 && input.title.isNotBlank()) { cv.text(input.title, cv.w / 2f, y - 10f, 20f, Align.CENTER, 0x212121, bold = true); y -= 34f }
            if (p == 0 && input.subtitle.isNotBlank()) { cv.text(input.subtitle, cv.w - M, y, 11f, Align.RIGHT, 0x616161); y -= 18f }
            val gap = 7f; val block = 4 * gap + 34f
            y -= 16f
            while (y - 4 * gap > M - 4f) {
                for (l in 0..4) cv.line(M, y - l * gap, cv.w - M, y - l * gap, 0x424242, 0.55f)
                cv.line(M, y, M, y - 4 * gap, 0x424242, 0.9f)
                cv.line(cv.w - M, y, cv.w - M, y - 4 * gap, 0x424242, 0.9f)
                y -= block
            }
        }
    }

    fun todo(cv: TplCanvas, input: TemplateInput) {
        val rows = (if (input.rows > 0) input.rows else TemplateKind.TODO.defaultRows).coerceIn(TemplateKind.TODO.rowsRange)
        val accent = input.accent
        repeat(pages(input)) { p ->
            cv.newPage(input.pageSize, input.landscape ?: false)
            val title = input.title.ifBlank { TemplateDefaults.title(TemplateKind.TODO) }
            var y = cv.h - M - 8f
            cv.roundRect(M, y - 34f, cv.w - 2 * M, 44f, 10f, stroke = null, fill = cv.tint(accent, 0.88f))
            cv.text(title, cv.w - M - 14f, y - 18f, 18f, Align.RIGHT, accent, bold = true)
            cv.labeledField("التاريخ", "todo_date_${p + 1}", M + 150f, M + 12f, y - 18f, 10f, withField = input.withFields)
            y -= 58f
            // column headings
            cv.text("تم", cv.w - M - 4f, y, 10f, Align.RIGHT, 0x757575, bold = true)
            cv.text("المهمة", cv.w - M - 44f, y, 10f, Align.RIGHT, 0x757575, bold = true)
            cv.text("الأولوية (1-3)", M + 70f, y, 10f, Align.RIGHT, 0x757575, bold = true)
            y -= 10f
            val rowH = ((y - M) / rows).coerceAtMost(34f)
            for (i in 0 until rows) {
                val top = y - i * rowH; val base = top - rowH
                val box = minOf(14f, rowH - 8f)
                val cx = cv.w - M - box - 4f; val cy = base + (rowH - box) / 2f
                cv.roundRect(cx, cy, box, box, 3f, stroke = accent, lineWidth = 1f)
                if (input.withFields) cv.field(FieldType.CHECKBOX, "done_${p + 1}_${i + 1}", cx, cy, box, box, tooltip = "تم")
                cv.line(M, base + 2f, cv.w - M - box - 14f, base + 2f, 0xCFD8DC, 0.6f)
                if (input.withFields) {
                    cv.field(FieldType.TEXT, "task_${p + 1}_${i + 1}", M + 80f, base + 3f, cv.w - 2 * M - box - 100f, rowH - 6f, tooltip = "المهمة")
                    cv.field(FieldType.COMBO, "prio_${p + 1}_${i + 1}", M, base + 3f, 70f, rowH - 6f, tooltip = "الأولوية", options = listOf("1", "2", "3"))
                }
            }
        }
    }

    fun timetable(cv: TplCanvas, input: TemplateInput) {
        val periods = (if (input.rows > 0) input.rows else TemplateKind.TIMETABLE.defaultRows).coerceIn(TemplateKind.TIMETABLE.rowsRange)
        val accent = input.accent
        cv.newPage(input.pageSize, input.landscape ?: true)
        val top = cv.h - M + 8f
        // compact header: logo + org at the right, title centred, subtitle (class) under the title
        var textRight = cv.w - M
        if (input.logo != null) { cv.image(input.logo, cv.w - M - 44f, top - 44f, 44f, 44f); textRight -= 54f }
        if (input.org.isNotBlank()) cv.text(input.org, textRight, top - 18f, 13f, Align.RIGHT, 0x424242, bold = true)
        cv.text(input.title.ifBlank { TemplateDefaults.title(TemplateKind.TIMETABLE) }, cv.w / 2f, top - 22f, 20f, Align.CENTER, accent, bold = true)
        if (input.subtitle.isNotBlank()) cv.text(input.subtitle, cv.w / 2f, top - 42f, 12f, Align.CENTER, 0x616161)
        val gridTop = top - 58f
        val gridBottom = M - 6f
        val dayW = 78f
        val days = TemplateRenderer.days
        val headH = 26f
        val rowH = (gridTop - gridBottom - headH) / days.size
        val colW = (cv.w - 2 * M - dayW) / periods
        val right = cv.w - M
        // header row
        cv.rect(M, gridTop - headH, cv.w - 2 * M, headH, stroke = null, fill = accent)
        cv.text("اليوم / الحصة", right - dayW / 2f, gridTop - headH + 9f, 10f, Align.CENTER, 0xFFFFFF, bold = true)
        for (p in 0 until periods) {
            val xr = right - dayW - p * colW
            cv.text("الحصة ${p + 1}", xr - colW / 2f, gridTop - headH + 9f, 10.5f, Align.CENTER, 0xFFFFFF, bold = true)
        }
        days.forEachIndexed { d, day ->
            val yTop = gridTop - headH - d * rowH
            val yb = yTop - rowH
            if (d % 2 == 1) cv.rect(M, yb, cv.w - 2 * M - dayW, rowH, stroke = null, fill = cv.tint(accent, 0.94f))
            cv.rect(right - dayW, yb, dayW, rowH, stroke = null, fill = cv.tint(accent, 0.82f))
            cv.text(day, right - dayW / 2f, yb + rowH / 2f - 4f, 12f, Align.CENTER, 0x263238, bold = true)
            for (p in 0 until periods) {
                val xr = right - dayW - p * colW
                if (input.withFields) cv.field(FieldType.TEXT, "tt_${d + 1}_${p + 1}", xr - colW + 3f, yb + 3f, colW - 6f, rowH - 6f, tooltip = "$day - ${p + 1}", multiline = true)
            }
        }
        // grid lines
        for (d in 0..days.size) { val y = gridTop - headH - d * rowH; cv.line(M, y, right, y, 0x90A4AE, 0.7f) }
        cv.line(M, gridTop, right, gridTop, 0x90A4AE, 0.7f)
        cv.line(right, gridTop, right, gridBottom, 0x90A4AE, 0.7f)
        cv.line(right - dayW, gridTop, right - dayW, gridBottom, 0x90A4AE, 0.7f)
        for (p in 1..periods) { val x = right - dayW - p * colW; cv.line(x, gridTop, x, gridBottom, 0x90A4AE, 0.7f) }
    }
}
