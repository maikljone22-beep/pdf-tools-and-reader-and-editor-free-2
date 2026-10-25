package com.smartdocs.editor.core.ai5

import android.content.Context
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import com.smartdocs.editor.core.data.FillProfileField
import com.smartdocs.editor.core.pdf.FieldType
import com.smartdocs.editor.core.pdf.FormField
import com.smartdocs.editor.core.pdf.FormOps
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/** A suggested value for one form field. [fromProfile] = matched locally from the fill profile (no AI needed). */
data class FillSuggestion(val field: FormField, val value: String, val fromProfile: Boolean)

/**
 * Form-fill suggestions: fields are first matched to the user's fill profile by name/label (on device), the rest
 * are sent in ONE request together with the profile and the start of the document so the model can propose
 * values (dates, totals, choices). Nothing is written until the user applies the suggestions ([apply]).
 */
object FormFillAi {
    private val SYN = mapOf(
        "name" to listOf("name", "الاسم", "اسم", "fullname", "full name"),
        "email" to listOf("email", "e-mail", "البريد", "ايميل"),
        "phone" to listOf("phone", "mobile", "tel", "الهاتف", "الجوال", "جوال", "هاتف"),
        "address" to listOf("address", "العنوان", "عنوان"),
        "city" to listOf("city", "المدينه", "مدينه"),
        "country" to listOf("country", "الدوله", "البلد"),
        "id" to listOf("id", "national", "الهويه", "الرقم الوطني"),
        "birth" to listOf("birth", "dob", "الميلاد"),
        "company" to listOf("company", "employer", "الشركه", "جهه العمل"),
    )

    fun fillable(fields: List<FormField>) = fields.filter { !it.readOnly && it.type in setOf(FieldType.TEXT, FieldType.COMBO, FieldType.LIST, FieldType.CHECKBOX, FieldType.RADIO) }

    /** Local match of a field to a profile entry by normalised key/label/synonyms. */
    fun matchProfile(field: FormField, profile: List<FillProfileField>): FillProfileField? {
        val fn = AiNorm.fold(field.label.ifBlank { field.name }).replace('_', ' ')
        profile.firstOrNull { p -> val pk = AiNorm.fold(p.label.ifBlank { p.key }); pk.isNotBlank() && (fn == pk || fn.contains(pk) || pk.contains(fn)) }?.let { return it }
        val group = SYN.entries.firstOrNull { (_, words) -> words.any { fn.contains(AiNorm.fold(it)) } }?.key ?: return null
        return profile.firstOrNull { p -> SYN[group]!!.any { AiNorm.fold(p.label + " " + p.key).contains(AiNorm.fold(it)) } }
    }

    suspend fun suggest(ctx: Context, client: AiClient, file: File, password: String?, profile: List<FillProfileField>, index: DocIndex?, useAi: Boolean = true): List<FillSuggestion> {
        val fields = fillable(FormOps.listFields(file, password))
        val out = ArrayList<FillSuggestion>()
        val rest = ArrayList<FormField>()
        fields.forEach { f ->
            val m = if (f.type == FieldType.TEXT) matchProfile(f, profile) else null
            if (m != null && m.value.isNotBlank()) out += FillSuggestion(f, m.value, true) else if (f.value.isBlank() || f.value == "false") rest += f
        }
        if (useAi && rest.isNotEmpty()) {
            val spec = buildJsonArray {
                rest.take(80).forEach { f -> add(buildJsonObject {
                    put("name", f.name); put("label", f.label); put("type", f.type.name.lowercase()); put("page", f.page + 1)
                    if (f.options.isNotEmpty()) put("options", buildJsonArray { f.options.forEach { add(JsonPrimitive(it)) } })
                }) }
            }
            val prof = profile.joinToString("\n") { "${it.label.ifBlank { it.key }}: ${it.value}" }
            val doc = index?.let { DocIndex.material(it.spread(5000)) }.orEmpty()
            val answer = AiCalls.complete(ctx, client, listOf(
                ChatMessage("system", AiCalls.SYSTEM),
                ChatMessage("user", "اقترح قيماً لحقول هذا النموذج اعتماداً على ملف المستخدم الشخصي ونص النموذج. لا تخترع بيانات شخصية غير موجودة في الملف؛ " +
                    "اترك الحقل غير المعروف خارج الإجابة. لمربعات الاختيار استخدم true أو false، وللقوائم اختر من options فقط. " +
                    "أعد JSON فقط: {\"اسم_الحقل\":\"القيمة\"}\n\nالحقول:\n$spec\n\nملف المستخدم:\n$prof\n\nنص النموذج:\n$doc"),
            ))
            val obj = AiJson.extract(answer, '{') as? JsonObject
            rest.forEach { f ->
                val v = (obj?.get(f.name) as? JsonPrimitive)?.contentOrNull?.trim() ?: return@forEach
                if (v.isBlank()) return@forEach
                if (f.options.isNotEmpty() && f.type != FieldType.CHECKBOX && v !in f.options) return@forEach
                out += FillSuggestion(f, v, false)
            }
        }
        return out
    }

    suspend fun apply(ctx: Context, src: File, password: String?, values: Map<String, String>, out: File): Int =
        FormOps.fillFields(src, values, out, password, ctx)
}
