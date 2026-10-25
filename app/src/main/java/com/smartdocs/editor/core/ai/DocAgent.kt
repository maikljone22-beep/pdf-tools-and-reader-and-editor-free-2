package com.smartdocs.editor.core.ai

import android.content.Context
import com.smartdocs.editor.R
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** What the model answered for one turn: free text and/or document commands to run. */
data class AgentReply(val text: String, val commands: List<DocCommand>)

/**
 * The document agent: sends the chat with the document context and the [DocTools] to an OpenAI-compatible
 * endpoint. Uses `tools` / `tool_calls`; when the provider rejects tools (HTTP 400/404/422 mentioning tools)
 * or answers with a JSON block anyway, falls back to parsing ```json {"function": …, "args": …}``` from the text.
 */
class DocAgent(private val client: AiClient) {

    /** Remembered per process: once a provider refused tools we use the JSON fallback directly. */
    companion object {
        private val noToolEndpoints = HashSet<String>()

        fun systemPrompt(pageCount: Int, currentPage: Int, hasSelection: Boolean, docName: String): String =
            "أنت وكيل ذكي داخل تطبيق لقراءة وتحرير ملفات PDF، وتتحدث العربية الفصحى. " +
                "المستند المفتوح: \"$docName\"، عدد صفحاته $pageCount، والصفحة الحالية ${currentPage + 1}. " +
                (if (hasSelection) "يوجد نص محدد في القارئ. " else "لا يوجد نص محدد. ") +
                "أرقام الصفحات تبدأ من 1، ويقبل المستخدم الأرقام العربية (٣) والهندية (3) وعبارات مثل \"من ٣ إلى ٥\" أو \"الصفحة الحالية\" أو \"الأخيرة\". " +
                "عندما يطلب المستخدم تعديل المستند أو التنقل فيه استدعِ الأداة المناسبة مباشرة بالمعاملات الصحيحة ولا تسأل عن التأكيد بنفسك؛ التطبيق يعرض بطاقة تأكيد للمستخدم. " +
                "لا تخترع أرقام صفحات غير موجودة. للأسئلة عن المحتوى أجب نصاً بالاعتماد على نص المستند واذكر رقم الصفحة. " +
                "لتلخيص صفحة في ملاحظة اكتب الملخص بنفسك في المعامل summary إن كان نص الصفحة متاحاً."

        /** Extract `{"function": name, "args": {...}}` (or an array of them) from free text. */
        fun parseJsonCommands(text: String): List<DocCommand> {
            val candidates = ArrayList<String>()
            Regex("```(?:json)?\\s*([\\s\\S]*?)```").findAll(text).forEach { candidates += it.groupValues[1].trim() }
            if (candidates.isEmpty()) {
                val a = text.indexOfFirst { it == '{' || it == '[' }
                val b = text.indexOfLast { it == '}' || it == ']' }
                if (a >= 0 && b > a) candidates += text.substring(a, b + 1)
            }
            val out = ArrayList<DocCommand>()
            candidates.forEach { c ->
                val el = runCatching { AiClient.json.parseToJsonElement(c) }.getOrNull() ?: return@forEach
                val items: List<JsonElement> = if (el is JsonArray) el else listOf(el)
                items.forEach { item -> (item as? JsonObject)?.let { o -> toCommand(o)?.let(out::add) } }
            }
            return out
        }

        private fun toCommand(o: JsonObject): DocCommand? {
            val name = (o["function"] as? JsonPrimitive)?.contentOrNull ?: (o["name"] as? JsonPrimitive)?.contentOrNull ?: (o["tool"] as? JsonPrimitive)?.contentOrNull ?: return null
            val cmd = DocCmd.of(name) ?: return null
            val args = (o["args"] as? JsonObject) ?: (o["arguments"] as? JsonObject) ?: (o["parameters"] as? JsonObject)
                ?: (o["arguments"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { AiClient.json.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?: JsonObject(o.filterKeys { it != "function" && it != "name" && it != "tool" })
            return DocCommand(cmd, args)
        }

        /** Remove JSON command blocks from a reply so only the prose is shown. */
        fun stripJson(text: String): String = text.replace(Regex("```(?:json)?\\s*[\\s\\S]*?```"), "").trim()
    }

    /**
     * One turn. [history] = previous user/assistant messages (plain text; executed commands are summarised as
     * assistant text, so no tool_call_id bookkeeping is needed across providers).
     */
    suspend fun turn(history: List<ChatMessage>, docContext: String?, pageCount: Int, currentPage: Int, hasSelection: Boolean, docName: String): AgentReply {
        val system = systemPrompt(pageCount, currentPage, hasSelection, docName)
        val msgs = buildList {
            add(ChatMessage("system", system))
            if (!docContext.isNullOrBlank()) add(ChatMessage("system", "نص المستند:\n$docContext"))
            history.forEach { add(it.copy(content = if (it.role == "user") DocCommand.normalizeDigits(it.content) else it.content)) }
        }
        val key = client.baseUrl + "|" + client.modelName
        if (key !in noToolEndpoints) {
            try {
                return withTools(msgs)
            } catch (e: AiException) {
                val m = e.message.orEmpty()
                val toolProblem = (m.contains("HTTP 400") || m.contains("HTTP 404") || m.contains("HTTP 422") || m.contains("HTTP 501")) &&
                    (m.contains("tool", true) || m.contains("function", true))
                if (!toolProblem) throw e
                noToolEndpoints += key
            }
        }
        return withJsonFallback(msgs)
    }

    private fun messagesJson(msgs: List<ChatMessage>): JsonArray = buildJsonArray {
        msgs.forEach { m -> add(buildJsonObject { put("role", m.role); put("content", m.content) }) }
    }

    private suspend fun withTools(msgs: List<ChatMessage>): AgentReply {
        val body = buildJsonObject {
            put("model", client.modelName)
            put("messages", messagesJson(msgs))
            put("tools", DocTools.all)
            put("tool_choice", "auto")
            put("temperature", 0.2)
            put("stream", false)
        }
        val obj = client.postJson("/chat/completions", body)
        val message = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: throw AiException("bad response")
        val content = (message["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val calls = (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull { tc ->
            val fn = (tc as? JsonObject)?.get("function") as? JsonObject ?: return@mapNotNull null
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val cmd = DocCmd.of(name) ?: return@mapNotNull null
            val args = when (val a = fn["arguments"]) {
                is JsonObject -> a
                is JsonPrimitive -> a.contentOrNull?.let { runCatching { AiClient.json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
                else -> JsonObject(emptyMap())
            }
            DocCommand(cmd, args)
        }
        if (calls.isNotEmpty()) return AgentReply(content.trim(), calls)
        // some models (e.g. Llama on Groq) write the call as JSON text instead of tool_calls
        val parsed = parseJsonCommands(content)
        return if (parsed.isNotEmpty()) AgentReply(stripJson(content), parsed) else AgentReply(content.trim(), emptyList())
    }

    private suspend fun withJsonFallback(msgs: List<ChatMessage>): AgentReply {
        val withInstr = listOf(msgs.first(), ChatMessage("system", DocTools.fallbackInstructions)) + msgs.drop(1)
        val text = client.complete(withInstr)
        val parsed = parseJsonCommands(text)
        return AgentReply(if (parsed.isEmpty()) text.trim() else stripJson(text), parsed)
    }
}

/** Builds the confirmation text and runs a [DocCommand] against [DocActions]. */
class DocCommandRunner(private val ctx: Context, private val actions: DocActions, private val client: AiClient) {

    private fun s(id: Int, vararg a: Any) = ctx.getString(id, *a)

    /** "هل تريد حذف الصفحات 3–5؟" — or null when the arguments are unusable (the reason is in [problem]). */
    fun confirmText(c: DocCommand): String {
        val n = actions.pageCount; val cur = actions.currentPage
        fun pg(key: String) = DocCommand.formatPages(c.pages(key, n, cur)).ifEmpty { "؟" }
        return when (c.cmd) {
            DocCmd.GO_TO_PAGE -> s(R.string.ai2_cf_goto, (c.page("page", n, cur) ?: cur) + 1)
            DocCmd.SEARCH_TEXT -> s(R.string.ai2_cf_search, c.str("query") ?: "")
            DocCmd.DELETE_PAGES -> s(R.string.ai2_cf_delete, pg("pages"))
            DocCmd.ROTATE_PAGES -> {
                val d = c.int("degrees") ?: 90
                s(if (d >= 0) R.string.ai2_cf_rotate_cw else R.string.ai2_cf_rotate_ccw, pg("pages"), kotlin.math.abs(d))
            }
            DocCmd.MOVE_PAGE -> s(R.string.ai2_cf_move, c.int("from") ?: 0, c.int("to") ?: 0)
            DocCmd.DUPLICATE_PAGE -> s(R.string.ai2_cf_duplicate, (c.page("page", n, cur) ?: cur) + 1)
            DocCmd.INSERT_BLANK_PAGE -> {
                val after = c.int("after") ?: (cur + 1)
                if (after <= 0) s(R.string.ai2_cf_blank_start) else s(R.string.ai2_cf_blank, after.coerceAtMost(n))
            }
            DocCmd.EXTRACT_PAGES -> s(R.string.ai2_cf_extract, pg(if (c.args.containsKey("range")) "range" else "pages"))
            DocCmd.SHARE_PAGES -> s(R.string.ai2_cf_share, pg("pages"))
            DocCmd.FLATTEN -> s(R.string.ai2_cf_flatten)
            DocCmd.REDACT_TEXT -> s(R.string.ai2_cf_redact, c.str("query") ?: "")
            DocCmd.SET_PROPERTIES -> {
                val parts = listOfNotNull(
                    c.str("title")?.let { s(R.string.ai2_prop_title) + ": " + it }, c.str("author")?.let { s(R.string.ai2_prop_author) + ": " + it },
                    c.str("subject")?.let { s(R.string.ai2_prop_subject) + ": " + it }, c.str("keywords")?.let { s(R.string.ai2_prop_keywords) + ": " + it },
                )
                s(R.string.ai2_cf_props, parts.joinToString("، "))
            }
            DocCmd.CONVERT -> s(R.string.ai2_cf_convert, (c.str("format") ?: "docx").uppercase())
            DocCmd.COMPRESS -> s(R.string.ai2_cf_compress)
            DocCmd.ADD_WATERMARK -> s(R.string.ai2_cf_watermark, c.str("text") ?: "")
            DocCmd.ADD_PAGE_NUMBERS -> s(R.string.ai2_cf_numbers)
            DocCmd.PROTECT -> s(R.string.ai2_cf_protect)
            DocCmd.OCR -> {
                val p = c.pages("pages", n, cur)
                s(if (c.str("mode") == "editable") R.string.ai2_cf_ocr_editable else R.string.ai2_cf_ocr, if (p.isEmpty()) s(R.string.ai2_all_pages) else DocCommand.formatPages(p))
            }
            DocCmd.CHANGE_PAGE_COLOR -> s(R.string.ai2_cf_color, c.str("color") ?: "")
            DocCmd.SUMMARIZE_TO_NOTE -> s(R.string.ai2_cf_note, (c.page("page", n, cur) ?: cur) + 1)
            DocCmd.TRANSLATE_SELECTION -> s(R.string.ai2_cf_translate, c.str("target_language") ?: s(R.string.ai2_lang_arabic))
            DocCmd.CREATE_BLANK_PDF -> s(R.string.ai2_cf_create, c.int("pages") ?: 1)
        }
    }

    /** Execute; throws [ActionError] with a user message on bad arguments. */
    suspend fun run(c: DocCommand): ActionResult {
        val n = actions.pageCount; val cur = actions.currentPage
        fun need(v: String?) = v ?: throw ActionError(s(R.string.ai2_err_args))
        fun pages(key: String) = c.pages(key, n, cur).ifEmpty { throw ActionError(s(R.string.ai2_err_page)) }
        fun page(key: String) = c.page(key, n, cur) ?: throw ActionError(s(R.string.ai2_err_page))
        return when (c.cmd) {
            DocCmd.GO_TO_PAGE -> actions.goToPage(page("page"))
            DocCmd.SEARCH_TEXT -> actions.searchText(need(c.str("query")))
            DocCmd.DELETE_PAGES -> actions.deletePages(pages("pages"))
            DocCmd.ROTATE_PAGES -> actions.rotatePages(pages("pages"), c.int("degrees") ?: 90)
            DocCmd.MOVE_PAGE -> actions.movePage(page("from"), (c.int("to") ?: throw ActionError(s(R.string.ai2_err_page))).minus(1).coerceIn(0, n - 1))
            DocCmd.DUPLICATE_PAGE -> actions.duplicatePage(page("page"))
            DocCmd.INSERT_BLANK_PAGE -> actions.insertBlankPage((c.int("after") ?: (cur + 1)).coerceIn(0, n) - 1)
            DocCmd.EXTRACT_PAGES -> actions.extractPages(pages(if (c.args.containsKey("range")) "range" else "pages"))
            DocCmd.SHARE_PAGES -> actions.sharePages(c.pages("pages", n, cur))
            DocCmd.FLATTEN -> actions.flatten()
            DocCmd.REDACT_TEXT -> actions.redactText(need(c.str("query")))
            DocCmd.SET_PROPERTIES -> actions.setProperties(c.str("title"), c.str("author"), c.str("subject"), c.str("keywords"))
            DocCmd.CONVERT -> actions.convert(c.str("format") ?: "docx")
            DocCmd.COMPRESS -> actions.compress(c.str("quality") ?: "medium")
            DocCmd.ADD_WATERMARK -> actions.addWatermark(need(c.str("text")))
            DocCmd.ADD_PAGE_NUMBERS -> actions.addPageNumbers(c.bool("arabic_digits") ?: false)
            DocCmd.PROTECT -> actions.protect(need(c.str("password")))
            DocCmd.OCR -> actions.ocr(c.pages("pages", n, cur).ifEmpty { null }, c.str("mode") == "editable")
            DocCmd.CHANGE_PAGE_COLOR -> actions.changePageColor(PdfDocActions.parseColor(need(c.str("color"))) ?: throw ActionError(s(R.string.ai2_err_color)))
            DocCmd.SUMMARIZE_TO_NOTE -> {
                val p = page("page")
                val summary = c.str("summary") ?: run {
                    val text = actions.pageText(p).ifBlank { throw ActionError(s(R.string.ai2_err_no_text)) }
                    client.complete(listOf(ChatMessage("system", AiClient.SYSTEM_PROMPT), ChatMessage("user", "لخّص نص هذه الصفحة في 3 إلى 5 جمل قصيرة بالعربية دون مقدمة:\n\n${text.take(6000)}"))).trim()
                }
                actions.summarizeToNote(p, summary)
            }
            DocCmd.TRANSLATE_SELECTION -> {
                val sel = actions.selectedText()?.takeIf { it.isNotBlank() } ?: throw ActionError(s(R.string.ai2_err_no_selection))
                val lang = c.str("target_language") ?: s(R.string.ai2_lang_arabic)
                val text = client.translate(sel.take(2000), lang).toList().joinToString("")
                ActionResult(text.trim())
            }
            DocCmd.CREATE_BLANK_PDF -> actions.createBlankPdf(c.int("pages") ?: 1)
        }
    }
}
