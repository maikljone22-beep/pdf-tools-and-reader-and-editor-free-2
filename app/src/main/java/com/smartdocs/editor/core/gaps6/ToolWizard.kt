package com.smartdocs.editor.core.gaps6

import com.smartdocs.editor.core.search.ArabicNormalizer
import com.smartdocs.editor.core.search.NormOptions

/**
 * "What do you want to do?" — maps a free-text goal (Arabic or English) to the matching tools of the Tools tab.
 * Pure keyword matcher: the query and every keyword are folded with [ArabicNormalizer] (diacritics, alef/ya forms,
 * ta marbuta, digits) and light-stemmed, then each tool is scored:
 *  - a multi-word keyword found as a phrase in the query: +6
 *  - a query word equal to a keyword word (after stemming): +4
 *  - a query word that is a prefix of a keyword word or vice versa (>= 3 letters): +2
 *  - a query word contained in a keyword word (>= 4 letters): +1
 * Ties keep the Tools-tab order. Ids are the ids of `ui/tools/ToolsScreen.kt` (all of them).
 */
object ToolWizard {

    data class Hit(val id: String, val score: Int)

    private val OPTS = NormOptions(taMarbuta = true)

    /** Tool id → keywords (Arabic first, then English). Order = Tools tab order. */
    val KEYWORDS: LinkedHashMap<String, List<String>> = linkedMapOf(
        "scan" to listOf("مسح", "مسح ضوئي", "سكانر", "ماسح", "كاميرا", "تصوير مستند", "scan", "scanner", "camera"),
        "images_to_pdf" to listOf("صور إلى pdf", "تحويل الصور", "صورة", "صور", "images to pdf", "image", "photo", "jpg"),
        "blank" to listOf("صفحة فارغة", "مستند جديد", "فارغ", "إنشاء", "blank", "new document", "create"),
        "templates" to listOf("قالب", "قوالب", "نموذج جاهز", "من قالب", "template", "templates"),
        "ocr" to listOf("ocr", "التعرف على النص", "استخراج النص من الصورة", "نص من صورة", "تعرف ضوئي", "text recognition"),
        "convert" to listOf("تحويل", "تحويل إلى وورد", "وورد", "اكسل", "إكسل", "بوربوينت", "تحويل الملف", "convert", "word", "docx", "excel", "powerpoint"),
        "organize" to listOf("تنظيم", "ترتيب الصفحات", "إعادة ترتيب", "صفحات", "حذف صفحة", "organize", "reorder", "pages"),
        "merge" to listOf("دمج", "جمع الملفات", "ضم", "دمج ملفات", "merge", "combine", "join"),
        "split" to listOf("تقسيم", "فصل", "تجزئة", "split", "separate"),
        "extract" to listOf("استخراج صفحات", "استخراج", "قص صفحات", "extract pages", "extract"),
        "rotate" to listOf("تدوير", "لف الصفحة", "rotate", "rotation"),
        "compress" to listOf("ضغط", "تصغير الحجم", "تقليل الحجم", "حجم", "compress", "reduce size", "smaller"),
        "page_numbers" to listOf("ترقيم", "أرقام الصفحات", "ترقيم الصفحات", "page numbers", "numbering"),
        "watermark" to listOf("علامة مائية", "ختم مائي", "watermark"),
        "doc_info" to listOf("خصائص", "معلومات الملف", "بيانات وصفية", "properties", "info", "metadata"),
        "attachments" to listOf("مرفقات", "إرفاق ملف", "attachments", "attach"),
        "sign" to listOf("توقيع", "وقع", "إمضاء", "توقيع يدوي", "sign", "signature"),
        "digital_sign" to listOf("توقيع رقمي", "توقيع إلكتروني", "شهادة", "تحقق من التوقيع", "digital signature", "certificate", "verify"),
        "forms" to listOf("تعبئة النماذج", "نموذج", "استمارة", "تعبئة", "fill form", "forms"),
        "form_builder" to listOf("إنشاء نموذج", "حقول", "تصميم نموذج", "form builder", "fields"),
        "protect" to listOf("حماية", "كلمة مرور", "قفل", "تشفير", "protect", "password", "lock", "encrypt"),
        "cert_protect" to listOf("حماية بشهادة", "تشفير بشهادة", "certificate encryption"),
        "unlock" to listOf("إزالة كلمة المرور", "فك القفل", "فتح القفل", "إزالة الحماية", "unlock", "remove password"),
        "redact" to listOf("تنقيح", "حجب", "إخفاء نص", "طمس", "redact", "redaction", "black out"),
        "sanitize" to listOf("تنظيف المستند", "إزالة البيانات المخفية", "تعقيم", "sanitize", "clean"),
        "protect_advanced" to listOf("حماية متقدمة", "صلاحيات", "aes", "تشفير متقدم", "permissions", "advanced protection"),
        "flatten" to listOf("تسطيح", "دمج الطبقات", "تثبيت التعليقات", "flatten"),
        "pdf_to_images" to listOf("pdf إلى صور", "تحويل إلى صور", "تصدير صور", "pdf to images", "export images", "png"),
        "pdf_to_text" to listOf("pdf إلى نص", "تحويل إلى نص", "نص", "استخراج النص", "pdf to text", "txt"),
        "search_all" to listOf("بحث في كل الملفات", "بحث", "البحث في الملفات", "search", "find"),
        "search_highlight" to listOf("بحث وتظليل", "تظليل", "تمييز", "highlight"),
        "find_replace" to listOf("بحث واستبدال", "استبدال", "replace", "find and replace"),
        "search_remove" to listOf("بحث وإزالة", "إزالة كلمات", "حذف كلمات من المستند", "search and remove", "remove words"),
        "compare" to listOf("مقارنة", "قارن", "الفرق بين ملفين", "compare", "diff"),
        "scan_studio" to listOf("استوديو المسح", "مسح بطاقة", "بطاقة هوية", "إيصال", "سبورة", "scan studio", "id card", "receipt"),
        "to_pdf_plus" to listOf("تحويل إلى pdf", "أي ملف إلى pdf", "إلى pdf", "to pdf", "any to pdf"),
        "mail_merge" to listOf("دمج المراسلات", "رسائل جماعية", "mail merge"),
        "portfolio" to listOf("حافظة", "ملف مجمع", "portfolio"),
        "ocr_plus" to listOf("ocr دفعة", "تعرف ضوئي متقدم", "ocr متعدد", "batch ocr"),
        "export_plus" to listOf("تصدير", "تصدير إلى وورد", "تصدير متقدم", "rtf", "export"),
        "optimize" to listOf("تحسين", "تحسين الحجم", "عرض سريع على الويب", "ضغط متقدم", "optimize", "web view"),
        "pdfa" to listOf("أرشفة", "pdf/a", "pdfa", "archive"),
        "accessibility" to listOf("إمكانية الوصول", "وسوم", "قارئ الشاشة", "accessibility", "tags"),
        "recipes" to listOf("وصفات", "أتمتة", "خطوات تلقائية", "recipes", "automation"),
        "batch" to listOf("معالجة دفعة", "دفعة", "مهام مجدولة", "batch"),
        "ai" to listOf("ذكاء اصطناعي", "مساعد", "تلخيص", "ملخص", "ترجمة", "ترجم", "اسأل", "شرح", "ai", "summarize", "summary", "translate", "chat"),
        "read_aloud" to listOf("قراءة بصوت", "قراءة صوتية", "استماع", "نطق", "read aloud", "tts", "listen"),
        "podcast" to listOf("بودكاست", "ملخص صوتي", "podcast", "audio summary"),
        "ai_spaces" to listOf("مساحات", "عدة ملفات", "أسئلة على ملفات", "spaces", "multiple files"),
        "print" to listOf("طباعة", "اطبع", "print"),
        "batch_print" to listOf("طباعة دفعة", "طباعة عدة ملفات", "batch print", "print all"),
        "e5_hub" to listOf("تحرير", "تعديل النص", "روابط", "رأس وتذييل", "إشارات مرجعية", "edit", "header", "footer", "bookmarks", "links"),
    )

    /** Suggested goals shown as chips (Arabic UI). */
    val SUGGESTED = listOf("دمج", "ضغط", "توقيع", "تحويل إلى وورد", "مسح", "OCR", "حماية", "تنقيح", "ترجمة", "تلخيص")

    /** Tools that need the AI assistant (hidden when AI is switched off in Settings). */
    val AI_TOOLS = setOf("ai", "podcast", "ai_spaces")

    private class Kw(val phrase: String, val words: List<String>)

    private val index: Map<String, List<Kw>> by lazy {
        KEYWORDS.mapValues { (_, kws) -> kws.map { k -> val f = fold(k); Kw(f, words(f)) } }
    }

    fun fold(s: String): String = ArabicNormalizer.fold(s.trim(), OPTS).text

    private fun words(folded: String): List<String> =
        folded.split(' ', '/', '-', '،', ',').filter { it.isNotBlank() }.map { ArabicNormalizer.lightStem(it) }

    /** Ranked matches (score > 0), best first, at most [limit]; ids not in [allowed] are skipped when given. */
    fun rank(query: String, limit: Int = 8, allowed: Set<String>? = null): List<Hit> {
        val fq = fold(query)
        if (fq.isBlank()) return emptyList()
        val qWords = words(fq).filter { it.isNotEmpty() && it !in STOP }
        val order = KEYWORDS.keys.toList()
        return index.mapNotNull { (id, kws) ->
            if (allowed != null && id !in allowed) return@mapNotNull null
            var score = 0
            for (k in kws) {
                if (k.words.size > 1 && fq.contains(k.phrase)) score += 6
            }
            for (q in qWords) {
                var best = 0
                for (k in kws) for (w in k.words) {
                    val s = when {
                        q == w -> 4
                        q.length >= 3 && w.length >= 3 && (w.startsWith(q) || q.startsWith(w)) -> 2
                        q.length >= 4 && w.contains(q) -> 1
                        else -> 0
                    }
                    if (s > best) best = s
                }
                score += best
            }
            if (score > 0) Hit(id, score) else null
        }.sortedWith(compareByDescending<Hit> { it.score }.thenBy { order.indexOf(it.id) }).take(limit)
    }

    /** Common filler words ignored in a goal ("I want to …", "a file", "to"). */
    private val STOP: Set<String> by lazy {
        listOf("اريد", "أريد", "ابغى", "عايز", "كيف", "ملف", "ملفات", "مستند", "إلى", "الى", "على", "من", "في", "pdf", "the", "a", "to", "my", "want", "file", "i")
            .map { ArabicNormalizer.lightStem(fold(it)) }.toSet()
    }
}
