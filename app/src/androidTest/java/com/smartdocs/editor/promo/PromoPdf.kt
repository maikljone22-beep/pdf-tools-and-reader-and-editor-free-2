package com.smartdocs.editor.promo

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.File

/**
 * A good-looking Arabic sample document for the promo footage (an annual report: cover, text pages, a chart, a table),
 * drawn with the platform's text engine so the Arabic is shaped correctly. Content written for the demo; no real data.
 */
object PromoPdf {
    private const val W = 595; private const val H = 842
    private val NAVY = Color.rgb(13, 71, 161); private val BLUE = Color.rgb(21, 101, 192)
    private val TEAL = Color.rgb(0, 150, 136); private val AMBER = Color.rgb(255, 179, 0); private val INK = Color.rgb(33, 37, 41)

    private fun text(c: Canvas, s: String, x: Float, y: Float, width: Int, size: Float, color: Int = INK, bold: Boolean = false,
                     align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, spacing: Float = 1.35f): Float {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
        val l = StaticLayout.Builder.obtain(s, 0, s.length, p, width).setAlignment(align).setTextDirection(TextDirectionHeuristics.RTL).setLineSpacing(0f, spacing).build()
        c.save(); c.translate(x, y); l.draw(c); c.restore()
        return y + l.height
    }

    private val PARAS = listOf(
        "شهد هذا العام نموًا واضحًا في جميع قطاعات العمل، وارتفعت الإيرادات بنسبة ثمانية عشر بالمئة مقارنة بالعام الماضي، مع توسّع قاعدة العملاء في ثلاث أسواق جديدة.",
        "ركّز الفريق على تحسين تجربة المستخدم وتقليص زمن الاستجابة، فانخفضت مدة معالجة الطلبات إلى أقل من يوم عمل واحد، وارتفع مؤشر رضا العملاء إلى أعلى مستوى منذ تأسيس الشركة.",
        "واصلت الشركة الاستثمار في التدريب والتطوير، وأُنجز أكثر من أربعين برنامجًا تدريبيًا شارك فيها معظم الموظفين، مما انعكس مباشرة على جودة العمل وسرعة الإنجاز.",
        "تتطلع الإدارة في العام القادم إلى مضاعفة الطاقة التشغيلية، وافتتاح مركز خدمة جديد، وإطلاق منتجات رقمية تسهّل على العملاء إنجاز معاملاتهم من أي مكان.",
    )

    fun write(out: File) {
        val doc = PdfDocument()
        var n = 0
        fun page(draw: (Canvas) -> Unit) { val pg = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++n).create()); draw(pg.canvas); doc.finishPage(pg) }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        // cover
        page { c ->
            fill.color = NAVY; c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), fill)
            fill.color = BLUE; c.drawCircle(W * 0.85f, H * 0.2f, 190f, fill)
            fill.color = TEAL; c.drawCircle(W * 0.1f, H * 0.82f, 130f, fill)
            fill.color = AMBER; c.drawRect(60f, 300f, 140f, 308f, fill)
            text(c, "التقرير السنوي", 60f, 330f, W - 120, 46f, Color.WHITE, bold = true)
            text(c, "٢٠٢٦", 60f, 400f, W - 120, 64f, AMBER, bold = true)
            text(c, "ملخّص الأداء والإنجازات وخطة العام القادم", 60f, 500f, W - 120, 18f, Color.rgb(200, 215, 240))
        }
        // text pages with a header band
        fun header(c: Canvas, title: String) {
            fill.color = NAVY; c.drawRect(0f, 0f, W.toFloat(), 86f, fill)
            fill.color = AMBER; c.drawRect(0f, 86f, W.toFloat(), 90f, fill)
            text(c, title, 50f, 28f, W - 100, 24f, Color.WHITE, bold = true)
            text(c, "$n", 50f, H - 46f, W - 100, 11f, Color.GRAY, align = Layout.Alignment.ALIGN_CENTER)
        }
        page { c ->
            header(c, "كلمة الإدارة")
            var y = 130f
            for (p in PARAS) { y = text(c, p, 50f, y, W - 100, 15.5f) + 18f }
            fill.color = Color.rgb(232, 240, 254); c.drawRoundRect(RectF(50f, y + 6f, W - 50f, y + 96f), 12f, 12f, fill)
            text(c, "«النجاح نتيجة عمل فريق كامل يؤمن بما يقدّمه.»", 70f, y + 34f, W - 140, 17f, NAVY, bold = true)
        }
        // chart page
        page { c ->
            header(c, "الإيرادات حسب الربع")
            val values = listOf(42f, 55f, 61f, 78f); val labels = listOf("الربع الأول", "الربع الثاني", "الربع الثالث", "الربع الرابع")
            val base = 520f; val barW = 78f; val gap = 40f; val left = 70f
            val axis = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
            for (i in 0..4) c.drawLine(50f, base - i * 80f, W - 50f, base - i * 80f, axis)
            values.forEachIndexed { i, v ->
                val x = W - left - (i + 1) * barW - i * gap
                fill.color = if (i == values.lastIndex) AMBER else BLUE
                c.drawRoundRect(RectF(x, base - v * 4f, x + barW, base), 8f, 8f, fill)
                text(c, labels[i], x - 16f, base + 12f, (barW + 32f).toInt(), 12f, INK, align = Layout.Alignment.ALIGN_CENTER)
                text(c, "${v.toInt()} مليون", x - 16f, base - v * 4f - 26f, (barW + 32f).toInt(), 12.5f, NAVY, bold = true, align = Layout.Alignment.ALIGN_CENTER)
            }
            text(c, PARAS[0], 50f, 600f, W - 100, 14.5f)
        }
        // table page
        page { c ->
            header(c, "مؤشرات الأداء الرئيسية")
            val rows = listOf(listOf("المؤشر", "٢٠٢٥", "٢٠٢٦", "التغيّر"), listOf("عدد العملاء", "١٢٬٤٠٠", "١٨٬٩٠٠", "+٥٢٪"), listOf("رضا العملاء", "٨٤٪", "٩٣٪", "+٩"),
                listOf("زمن المعالجة", "٣ أيام", "يوم واحد", "−٦٦٪"), listOf("الموظفون", "١٤٠", "١٨٥", "+٣٢٪"), listOf("الفروع", "٦", "٩", "+٣"))
            val top = 140f; val rowH = 54f; val colW = (W - 100) / 4f
            rows.forEachIndexed { r, row ->
                fill.color = when { r == 0 -> NAVY; r % 2 == 0 -> Color.rgb(240, 245, 252); else -> Color.WHITE }
                c.drawRect(50f, top + r * rowH, W - 50f, top + (r + 1) * rowH, fill)
                row.forEachIndexed { k, cell ->
                    val x = W - 50f - (k + 1) * colW
                    text(c, cell, x, top + r * rowH + 16f, colW.toInt(), 14f, if (r == 0) Color.WHITE else if (k == 3) TEAL else INK, bold = r == 0 || k == 0, align = Layout.Alignment.ALIGN_CENTER)
                }
            }
            text(c, PARAS[1], 50f, top + rows.size * rowH + 36f, W - 100, 14.5f)
        }
        for ((i, title) in listOf("التدريب والتطوير", "خطة العام القادم", "شكر وتقدير").withIndex()) page { c ->
            header(c, title)
            var y = 130f
            for (k in PARAS.indices) { y = text(c, PARAS[(k + i + 2) % PARAS.size], 50f, y, W - 100, 15.5f) + 18f }
        }
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
