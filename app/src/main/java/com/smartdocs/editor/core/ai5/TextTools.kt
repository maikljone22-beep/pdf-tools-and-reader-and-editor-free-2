package com.smartdocs.editor.core.ai5

import android.content.Context
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import kotlinx.coroutines.flow.Flow

/** Actions on selected text (reader selection menu → [com.smartdocs.editor.ui.ai5.TextToolsSheet]). */
enum class TextAction { REWRITE, GRAMMAR, EXPLAIN, SIMPLIFY, FORMAL, SHORTER, LONGER, SUMMARIZE, DEFINE, TRANSLATE }

object TextTools {
    /** Actions whose output replaces the selection (the others are explanations). */
    val REPLACING = setOf(TextAction.REWRITE, TextAction.GRAMMAR, TextAction.SIMPLIFY, TextAction.FORMAL, TextAction.SHORTER, TextAction.LONGER, TextAction.TRANSLATE)

    private const val ONLY = " أعد النص الناتج فقط دون مقدمة أو تعليق أو علامات اقتباس."

    fun prompt(a: TextAction, text: String, targetLang: String = "en", context: String? = null): String {
        val same = "واحتفظ بلغة النص الأصلية."
        val body = when (a) {
            TextAction.REWRITE -> "أعد صياغة النص التالي بأسلوب أوضح وأكثر سلاسة مع الحفاظ على المعنى. $same$ONLY"
            TextAction.GRAMMAR -> "صحّح الأخطاء الإملائية والنحوية وعلامات الترقيم في النص التالي دون تغيير أسلوبه أو معناه. $same$ONLY"
            TextAction.SIMPLIFY -> "بسّط النص التالي ليفهمه قارئ مبتدئ. $same$ONLY"
            TextAction.FORMAL -> "أعد كتابة النص التالي بأسلوب رسمي مهني. $same$ONLY"
            TextAction.SHORTER -> "اختصر النص التالي إلى نصف طوله تقريباً مع الإبقاء على الأفكار الأساسية. $same$ONLY"
            TextAction.LONGER -> "وسّع النص التالي بتوضيح الأفكار وإضافة تفاصيل مناسبة دون اختراع حقائق. $same$ONLY"
            TextAction.TRANSLATE -> "ترجم النص التالي إلى ${AiLang.name(targetLang)} ترجمة دقيقة تحافظ على التنسيق وفواصل الأسطر.$ONLY"
            TextAction.EXPLAIN -> "اشرح النص التالي بأسلوب مبسّط، ووضّح المصطلحات الصعبة، وأعط مثالاً إن أمكن. أجب بلغة النص."
            TextAction.SUMMARIZE -> "لخّص النص التالي في نقاط قصيرة. أجب بلغة النص."
            TextAction.DEFINE -> "عرّف المصطلح أو العبارة التالية تعريفاً دقيقاً مختصراً مع مثال. أجب بلغة النص."
        }
        return body + (context?.takeIf { it.isNotBlank() }?.let { "\n\nسياق من المستند (للفهم فقط):\n${DocIndex.clip(it, 2500)}" } ?: "") + "\n\nالنص:\n$text"
    }

    fun run(ctx: Context, client: AiClient, a: TextAction, text: String, targetLang: String = "en", context: String? = null): Flow<String> =
        AiCalls.stream(ctx, client, listOf(ChatMessage("system", AiCalls.SYSTEM), ChatMessage("user", prompt(a, text, targetLang, context))))

    /** Remove wrapping quotes / code fences the model sometimes adds around a rewrite. */
    fun clean(out: String): String {
        var s = out.trim()
        Regex("^```\\w*\\s*([\\s\\S]*?)```$").find(s)?.let { s = it.groupValues[1].trim() }
        val pairs = listOf('"' to '"', '«' to '»', '“' to '”')
        pairs.forEach { (a, b) -> if (s.length > 2 && s.first() == a && s.last() == b && s.count { it == a } == (if (a == b) 2 else 1)) s = s.substring(1, s.length - 1).trim() }
        return s
    }
}
