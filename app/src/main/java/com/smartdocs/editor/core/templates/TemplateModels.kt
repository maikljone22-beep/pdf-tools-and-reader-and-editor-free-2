package com.smartdocs.editor.core.templates

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle

/** On-device document templates (rendered with PdfBox, no server). */
enum class TemplateKind(val id: String, val landscapeDefault: Boolean = false, val hasFields: Boolean = false) {
    LINED("lined"),
    GRID("grid"),
    GRAPH("graph"),
    MUSIC("music"),
    LETTER("letter", hasFields = true),
    INVOICE("invoice", hasFields = true),
    CERTIFICATE("certificate", landscapeDefault = true, hasFields = true),
    MINUTES("minutes", hasFields = true),
    TODO("todo", hasFields = true),
    TIMETABLE("timetable", landscapeDefault = true, hasFields = true),
    EXAM("exam", hasFields = true);

    /** Which inputs the editor shows for this template. */
    val inputs: Set<TemplateField>
        get() = when (this) {
            LINED, GRID, GRAPH -> setOf(TemplateField.TITLE, TemplateField.PAGES)
            MUSIC -> setOf(TemplateField.TITLE, TemplateField.SUBTITLE, TemplateField.PAGES)
            LETTER -> setOf(TemplateField.ORG, TemplateField.SUBTITLE, TemplateField.LOGO, TemplateField.DATE, TemplateField.FOOTER, TemplateField.ACCENT)
            INVOICE -> setOf(TemplateField.ORG, TemplateField.SUBTITLE, TemplateField.LOGO, TemplateField.DATE, TemplateField.ROWS, TemplateField.CURRENCY, TemplateField.ACCENT)
            CERTIFICATE -> setOf(TemplateField.TITLE, TemplateField.ORG, TemplateField.SUBTITLE, TemplateField.BODY, TemplateField.LOGO, TemplateField.DATE, TemplateField.ACCENT)
            MINUTES -> setOf(TemplateField.TITLE, TemplateField.ORG, TemplateField.LOGO, TemplateField.DATE, TemplateField.ROWS, TemplateField.ACCENT)
            TODO -> setOf(TemplateField.TITLE, TemplateField.ROWS, TemplateField.PAGES, TemplateField.ACCENT)
            TIMETABLE -> setOf(TemplateField.TITLE, TemplateField.ORG, TemplateField.SUBTITLE, TemplateField.ROWS, TemplateField.LOGO, TemplateField.ACCENT)
            EXAM -> setOf(TemplateField.TITLE, TemplateField.ORG, TemplateField.SUBTITLE, TemplateField.LOGO, TemplateField.DATE, TemplateField.ROWS, TemplateField.BODY, TemplateField.PAGES)
        }

    /** Default row count (invoice lines, decisions, tasks, periods, questions). */
    val defaultRows: Int
        get() = when (this) { INVOICE -> 8; MINUTES -> 5; TODO -> 18; TIMETABLE -> 7; EXAM -> 4; else -> 0 }

    val rowsRange: IntRange
        get() = when (this) { INVOICE -> 1..20; MINUTES -> 1..10; TODO -> 5..30; TIMETABLE -> 4..9; EXAM -> 1..10; else -> 0..0 }

    companion object { fun byId(id: String?) = entries.firstOrNull { it.id == id } }
}

enum class TemplateField { TITLE, ORG, SUBTITLE, BODY, LOGO, DATE, FOOTER, PAGES, ROWS, CURRENCY, ACCENT }

/**
 * User input for a template. Empty strings fall back to each template's Arabic defaults
 * (see [TemplateDefaults]), so a template can always be created with no input at all.
 */
data class TemplateInput(
    val title: String = "",
    val org: String = "",
    val subtitle: String = "",
    val body: String = "",
    val date: String = "",
    val footer: String = "",
    val currency: String = "",
    val logo: Bitmap? = null,
    val pages: Int = 1,
    val rows: Int = 0,
    val accent: Int = 0x1565C0,
    val landscape: Boolean? = null,
    val pageSize: PDRectangle = PDRectangle.A4,
    /** Add AcroForm fields (fillable template). */
    val withFields: Boolean = true,
)

/** Arabic defaults used when the user leaves an input empty. */
object TemplateDefaults {
    fun title(k: TemplateKind): String = when (k) {
        TemplateKind.LINED -> ""
        TemplateKind.GRID -> ""
        TemplateKind.GRAPH -> ""
        TemplateKind.MUSIC -> ""
        TemplateKind.LETTER -> "خطاب رسمي"
        TemplateKind.INVOICE -> "فاتورة"
        TemplateKind.CERTIFICATE -> "شهادة تقدير"
        TemplateKind.MINUTES -> "محضر اجتماع"
        TemplateKind.TODO -> "قائمة المهام"
        TemplateKind.TIMETABLE -> "الجدول الدراسي الأسبوعي"
        TemplateKind.EXAM -> "اختبار"
    }

    fun body(k: TemplateKind): String = when (k) {
        TemplateKind.CERTIFICATE -> "تقديراً لجهوده المتميزة وتفانيه في العمل، متمنين له دوام التوفيق والنجاح"
        TemplateKind.EXAM -> "اقرأ الأسئلة بعناية قبل الإجابة. اكتب إجابتك بخط واضح. الزمن المحدد للاختبار ساعتان."
        else -> ""
    }

    /** Sample input used for gallery thumbnails. */
    fun sample(k: TemplateKind) = TemplateInput(
        title = title(k),
        org = when (k) { TemplateKind.EXAM, TemplateKind.TIMETABLE -> "مدرسة النجاح الأهلية"; else -> "مؤسسة النور التعليمية" },
        subtitle = when (k) {
            TemplateKind.CERTIFICATE -> "أحمد محمد علي"
            TemplateKind.EXAM -> "مادة الكيمياء — الصف الثالث الثانوي"
            TemplateKind.TIMETABLE -> "الصف الأول الثانوي — شعبة أ"
            TemplateKind.LETTER, TemplateKind.INVOICE -> "صنعاء — الجمهورية اليمنية"
            else -> ""
        },
        date = "2026/10/24",
    )
}
