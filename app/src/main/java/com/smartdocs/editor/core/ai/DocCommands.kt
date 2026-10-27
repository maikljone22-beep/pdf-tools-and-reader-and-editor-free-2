package com.smartdocs.editor.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The document commands the AI agent may call (OpenAI "tools"). Matches the reference's 17 functions
 * (go-to, delete-page, extract-page, rotate-pages, share, insert-page-with-blank, duplicate-page, move-page,
 * change-bg-color, flatten-pages, redaction-pages, update-properties, convert-to-file, create-blank-pdf …)
 * and adds ours (search, compress, watermark, page numbers, protect, OCR, summary note, translation).
 *
 * [confirm] = a confirmation card is shown in chat before running ("هل تريد …؟" تنفيذ / إلغاء).
 */
enum class DocCmd(val id: String, val confirm: Boolean) {
    GO_TO_PAGE("go_to_page", false),
    SEARCH_TEXT("search_text", false),
    DELETE_PAGES("delete_pages", true),
    ROTATE_PAGES("rotate_pages", true),
    MOVE_PAGE("move_page", true),
    DUPLICATE_PAGE("duplicate_page", true),
    INSERT_BLANK_PAGE("insert_blank_page", true),
    EXTRACT_PAGES("extract_pages", true),
    SHARE_PAGES("share_pages", true),
    FLATTEN("flatten", true),
    REDACT_TEXT("redact_text", true),
    SET_PROPERTIES("set_properties", true),
    CONVERT("convert", true),
    COMPRESS("compress", true),
    ADD_WATERMARK("add_watermark", true),
    ADD_PAGE_NUMBERS("add_page_numbers", true),
    PROTECT("protect", true),
    OCR("ocr", true),
    CHANGE_PAGE_COLOR("change_page_color", true),
    SUMMARIZE_TO_NOTE("summarize_to_note", true),
    TRANSLATE_SELECTION("translate_selection", false),
    CREATE_BLANK_PDF("create_blank_pdf", true),
    // ai6 (B10 / AI-7): edit the document through chat
    HIGHLIGHT_TEXT("highlight_text", true),
    REDACT_PATTERN("redact_pattern", true),
    REPLACE_TEXT("replace_text", true),
    REWRITE_TEXT("rewrite_text", true);

    companion object {
        private val aliases = mapOf(
            "go-to" to GO_TO_PAGE, "goto" to GO_TO_PAGE, "delete-page" to DELETE_PAGES, "extract-page" to EXTRACT_PAGES,
            "rotate" to ROTATE_PAGES, "share" to SHARE_PAGES, "insert-page-with-blank" to INSERT_BLANK_PAGE,
            "duplicate-page" to DUPLICATE_PAGE, "move-page" to MOVE_PAGE, "change-bg-color" to CHANGE_PAGE_COLOR,
            "flatten-pages" to FLATTEN, "redaction-pages" to REDACT_TEXT, "update-properties" to SET_PROPERTIES,
            "convert-to-file" to CONVERT, "create-blank-pdf" to CREATE_BLANK_PDF, "search" to SEARCH_TEXT,
            "highlight" to HIGHLIGHT_TEXT, "replace" to REPLACE_TEXT, "rewrite" to REWRITE_TEXT, "rewrite_block" to REWRITE_TEXT, "redact" to REDACT_PATTERN,   // ai6
        )
        fun of(name: String): DocCmd? {
            val n = name.trim()
            return entries.firstOrNull { it.id == n } ?: aliases[n] ?: entries.firstOrNull { it.id == n.replace('-', '_').lowercase() }
        }
    }
}

/** One parsed call: the command and its raw JSON arguments, with typed, forgiving accessors. */
data class DocCommand(val cmd: DocCmd, val args: JsonObject) {

    fun str(key: String): String? = (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    fun int(key: String): Int? {
        val p = args[key] as? JsonPrimitive ?: return null
        return p.intOrNull ?: p.contentOrNull?.let { normalizeDigits(it).filter { c -> c.isDigit() || c == '-' }.toIntOrNull() }
    }

    fun bool(key: String): Boolean? = (args[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.lowercase()?.let { s -> s == "true" || s == "نعم" } }

    /** 1-based page argument → 0-based index ("current"/"الحالية" → [current]). */
    fun page(key: String, count: Int, current: Int): Int? {
        val s = str(key)?.lowercase()
        if (s != null && (s.contains("current") || s.contains("الحالي"))) return current
        if (s != null && (s.contains("last") || s.contains("الأخير") || s.contains("الاخير"))) return (count - 1).coerceAtLeast(0)
        val n = int(key) ?: return null
        return (n - 1).takeIf { it in 0 until count }
    }

    /** Pages argument (array of numbers, "3-5, 8", "٣ إلى ٥", "all", "current") → sorted 0-based indexes. */
    fun pages(key: String, count: Int, current: Int): List<Int> = parsePages(args[key], count, current)

    companion object {
        fun parsePages(el: JsonElement?, count: Int, current: Int): List<Int> {
            val out = sortedSetOf<Int>()
            fun addSpec(raw: String) {
                val s = normalizeDigits(raw).lowercase().trim()
                if (s.isEmpty()) return
                if (s in setOf("all", "الكل", "كل الصفحات", "جميع الصفحات", "*") || s.contains("all pages") || s.contains("كل")) { out += 0 until count; return }
                if (s.contains("current") || s.contains("الحالي")) { out += current; return }
                if (s.contains("last") || s.contains("الأخير") || s.contains("الاخير")) { if (count > 0) out += count - 1; return }
                val unified = s.replace("إلى", "-").replace("الى", "-").replace(" to ", "-").replace('–', '-').replace('—', '-')
                    .replace(Regex("""\s*-\s*"""), "-")
                unified.split(',', '،', ';', '؛', ' ', 'و').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                    val m = Regex("""^(\d+)-(\d+)$""").find(part)
                    if (m != null) {
                        val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
                        for (i in minOf(a, b)..maxOf(a, b)) if (i in 1..count) out += i - 1
                    } else part.filter { it.isDigit() }.toIntOrNull()?.let { if (it in 1..count) out += it - 1 }
                }
            }
            when (el) {
                null -> {}
                is JsonArray -> el.forEach { e -> (e as? JsonPrimitive)?.contentOrNull?.let(::addSpec) }
                is JsonPrimitive -> el.contentOrNull?.let(::addSpec)
                else -> {}
            }
            return out.toList()
        }

        /** Arabic-Indic and Persian digits → ASCII. */
        fun normalizeDigits(s: String): String {
            val sb = StringBuilder(s.length)
            s.forEach { c ->
                sb.append(when (c) { in '٠'..'٩' -> '0' + (c - '٠'); in '۰'..'۹' -> '0' + (c - '۰'); else -> c })
            }
            return sb.toString()
        }

        /** 0-based indexes → "3–5، 8" (1-based, compact ranges). */
        fun formatPages(pages: List<Int>): String {
            if (pages.isEmpty()) return ""
            val sorted = pages.distinct().sorted()
            val parts = ArrayList<String>()
            var start = sorted[0]; var prev = start
            fun flush() { parts += if (start == prev) "${start + 1}" else "${start + 1}–${prev + 1}" }
            sorted.drop(1).forEach { p -> if (p == prev + 1) prev = p else { flush(); start = p; prev = p } }
            flush()
            return parts.joinToString("، ")
        }
    }
}

/** JSON schemas of the tools (OpenAI function-calling format) with Arabic descriptions. */
object DocTools {

    private fun fn(name: String, description: String, required: List<String> = emptyList(), props: Map<String, Pair<String, String>> = emptyMap(), enums: Map<String, List<String>> = emptyMap()): JsonObject =
        buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", description)
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        props.forEach { (k, v) ->
                            putJsonObject(k) {
                                put("type", v.first); put("description", v.second)
                                enums[k]?.let { list -> putJsonArray("enum") { list.forEach { add(JsonPrimitive(it)) } } }
                            }
                        }
                    }
                    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }

    private const val PAGES = "أرقام الصفحات تبدأ من 1، مثل \"3-5, 8\" أو \"all\" أو \"current\""

    val all: JsonArray by lazy {
        buildJsonArray {
            add(fn("go_to_page", "الانتقال إلى صفحة في المستند المفتوح.", listOf("page"), mapOf("page" to ("integer" to "رقم الصفحة يبدأ من 1"))))
            add(fn("search_text", "البحث عن نص في المستند وإظهار النتائج.", listOf("query"), mapOf("query" to ("string" to "النص المطلوب"))))
            add(fn("delete_pages", "حذف صفحات من المستند.", listOf("pages"), mapOf("pages" to ("string" to PAGES))))
            add(fn("rotate_pages", "تدوير صفحات بزاوية من مضاعفات 90 (موجبة = مع عقارب الساعة).", listOf("pages", "degrees"),
                mapOf("pages" to ("string" to PAGES), "degrees" to ("integer" to "90 أو 180 أو 270 أو -90"))))
            add(fn("move_page", "نقل صفحة إلى موضع جديد.", listOf("from", "to"), mapOf("from" to ("integer" to "رقم الصفحة الحالي"), "to" to ("integer" to "الموضع الجديد"))))
            add(fn("duplicate_page", "تكرار صفحة (تُدرج النسخة بعدها مباشرة).", listOf("page"), mapOf("page" to ("integer" to "رقم الصفحة"))))
            add(fn("insert_blank_page", "إدراج صفحة فارغة بعد صفحة معيّنة (0 = في البداية).", listOf("after"), mapOf("after" to ("integer" to "رقم الصفحة التي تُدرج بعدها"))))
            add(fn("extract_pages", "استخراج صفحات إلى ملف PDF جديد دون تغيير المستند.", listOf("range"), mapOf("range" to ("string" to PAGES))))
            add(fn("share_pages", "مشاركة صفحات من المستند كملف PDF.", listOf("pages"), mapOf("pages" to ("string" to PAGES))))
            add(fn("flatten", "تسطيح التعليقات التوضيحية والنماذج لتصبح جزءاً من الصفحات."))
            add(fn("redact_text", "تنقيح (حجب نهائي) كل مواضع نص معيّن في المستند.", listOf("query"), mapOf("query" to ("string" to "النص المطلوب حجبه"))))
            add(fn("set_properties", "تعديل خصائص المستند. أرسل الحقول المطلوب تغييرها فقط.", emptyList(),
                mapOf("title" to ("string" to "العنوان"), "author" to ("string" to "المؤلف"), "subject" to ("string" to "الموضوع"), "keywords" to ("string" to "الكلمات المفتاحية"))))
            add(fn("convert", "تحويل المستند إلى صيغة أخرى (ملف جديد).", listOf("format"), mapOf("format" to ("string" to "الصيغة")), mapOf("format" to listOf("docx", "txt", "jpeg", "png"))))
            add(fn("compress", "ضغط المستند لتقليل حجمه.", emptyList(), mapOf("quality" to ("string" to "مستوى الضغط")), mapOf("quality" to listOf("low", "medium", "high"))))
            add(fn("add_watermark", "إضافة علامة مائية نصية على كل الصفحات.", listOf("text"), mapOf("text" to ("string" to "نص العلامة المائية"))))
            add(fn("add_page_numbers", "إضافة أرقام الصفحات أسفل كل صفحة.", emptyList(), mapOf("arabic_digits" to ("boolean" to "استخدام الأرقام العربية ٠١٢"))))
            add(fn("protect", "حماية المستند بكلمة مرور.", listOf("password"), mapOf("password" to ("string" to "كلمة المرور"))))
            add(fn("ocr", "التعرف الضوئي على النصوص في صفحات ممسوحة لجعلها قابلة للبحث أو التحرير.", emptyList(),
                mapOf("pages" to ("string" to PAGES), "mode" to ("string" to "searchable = طبقة نص مخفية، editable = نص قابل للتحرير")), mapOf("mode" to listOf("searchable", "editable"))))
            add(fn("change_page_color", "تغيير لون خلفية الصفحات في العرض فقط (لا يغيّر الملف).", listOf("color"),
                mapOf("color" to ("string" to "اسم اللون أو #RRGGBB")), mapOf("color" to listOf("white", "sepia", "green", "gray", "night"))))
            add(fn("summarize_to_note", "تلخيص صفحة وإضافة الملخص كتعليق (ملاحظة) على تلك الصفحة.", listOf("page"),
                mapOf("page" to ("integer" to "رقم الصفحة"), "summary" to ("string" to "الملخص بالعربية (اختياري، يُولَّد تلقائياً إن تُرك)"))))
            add(fn("translate_selection", "ترجمة النص المحدد حالياً في القارئ.", emptyList(), mapOf("target_language" to ("string" to "اللغة الهدف، الافتراضي العربية"))))
            add(fn("create_blank_pdf", "إنشاء ملف PDF فارغ جديد.", emptyList(), mapOf("pages" to ("integer" to "عدد الصفحات، الافتراضي 1"))))
            // ai6 (B10 / AI-7): edit through chat
            add(fn("highlight_text", "تظليل كل مواضع نص أو نمط (تعبير نمطي) في المستند بلون، مثل: ظلّل كل التواريخ بالأصفر.", listOf("query"),
                mapOf("query" to ("string" to "النص المطلوب أو التعبير النمطي"), "regex" to ("boolean" to "true إن كان query تعبيراً نمطياً"),
                    "color" to ("string" to "اللون"), "pages" to ("string" to PAGES)),
                mapOf("color" to listOf("yellow", "green", "blue", "pink", "red", "orange", "purple"))))
            add(fn("redact_pattern", "تنقيح (حجب نهائي) كل البيانات من نوع معيّن: بريد، هاتف، بطاقة، آيبان، هوية، جواز، تاريخ، رابط، أو تعبير نمطي مخصص.", listOf("kind"),
                mapOf("kind" to ("string" to "نوع البيانات"), "regex" to ("string" to "تعبير نمطي عند kind=custom"), "pages" to ("string" to PAGES)),
                mapOf("kind" to listOf("email", "phone", "card", "iban", "national_id", "passport", "date", "url", "ip", "all", "custom"))))
            add(fn("replace_text", "استبدال كل مواضع نص بنص آخر داخل محتوى الصفحات (تحرير حقيقي)، مثل: استبدل اسم العميل بـ Brightfield.", listOf("find", "replace"),
                mapOf("find" to ("string" to "النص الموجود"), "replace" to ("string" to "النص الجديد"), "match_case" to ("boolean" to "مطابقة حالة الأحرف"))))
            add(fn("rewrite_text", "إعادة صياغة فقرة أو النص المحدد في المستند واستبداله (مثلاً: أعد كتابة هذه الفقرة بأسلوب رسمي). اترك original فارغاً لاستعمال النص المحدد.", listOf("instruction"),
                mapOf("original" to ("string" to "النص الأصلي كما يظهر في المستند (اختياري)"), "instruction" to ("string" to "طريقة إعادة الصياغة"),
                    "new_text" to ("string" to "النص الجديد إن كتبته بنفسك (اختياري)"))))
        }
    }

    /** Text listing for providers without tool support (the model answers with a JSON block instead). */
    val fallbackInstructions: String by lazy {
        val lines = all.joinToString("\n") { el ->
            val f = el.jsonObject["function"]!!.jsonObject
            val params = f["parameters"]!!.jsonObject["properties"]!!.jsonObject.keys.joinToString(", ")
            "- ${(f["name"] as JsonPrimitive).content}($params): ${(f["description"] as JsonPrimitive).content}"
        }
        "يمكنك تنفيذ أوامر على المستند. إذا طلب المستخدم تنفيذ أمر، فأجب بكتلة JSON فقط بهذا الشكل دون أي نص آخر:\n" +
            "```json\n{\"function\": \"delete_pages\", \"args\": {\"pages\": \"3-5\"}}\n```\n" +
            "ولعدة أوامر استخدم مصفوفة من هذه الكائنات. الأوامر المتاحة:\n$lines\n" +
            "إذا كان الطلب سؤالاً عادياً فأجب نصاً عادياً دون JSON."
    }
}
