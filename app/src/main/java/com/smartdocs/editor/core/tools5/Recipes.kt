package com.smartdocs.editor.core.tools5

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/*
 * Batch "recipes": saved step sequences run on many files (see RecipeRunner / BatchWorker).
 * Model + JSON (de)serialisation + output naming are plain Kotlin and unit-tested.
 */

/** Step types. The [params] keys each step understands are listed in [StepType.defaults]. */
enum class StepType(val defaults: Map<String, String>) {
    OCR(mapOf("langs" to "auto", "clean" to "true")),
    OPTIMIZE(mapOf("dpi" to "150", "quality" to "70", "strip" to "true")),
    PDFA(mapOf("rasterizeMissingFonts" to "true")),
    WATERMARK(mapOf("text" to "", "opacity" to "0.3", "size" to "48", "profile" to "")),
    PAGE_NUMBERS(mapOf("start" to "1", "arabicDigits" to "false")),
    ROTATE(mapOf("degrees" to "90", "pages" to "")),
    REMOVE_METADATA(emptyMap()),
    SET_INFO(mapOf("title" to "", "author" to "", "subject" to "", "keywords" to "", "baseUrl" to "", "custom" to "")),
    FLATTEN(mapOf("forms" to "true")),
    AUTO_TAG(mapOf("lang" to "ar", "pdfua" to "false")),
    /** FINAL_CHECK_2 #21: alt text for tagged figures without one — "ai" (suggestion from the page / vision hook), "text" (one fixed text), "decorative". */
    ALT_TEXT(mapOf("mode" to "ai", "fallback" to "")),
    PROTECT(mapOf("password" to "")),
    // nav-batch: Nitro batch commands / Foxit Action Wizard commands
    JAVASCRIPT(mapOf("code" to "")),
    DELETE_COMMENTS(emptyMap()),
    HEADER_FOOTER(mapOf("profile" to "", "header" to "", "footer" to "{page}", "size" to "10")),
    BATES(mapOf("prefix" to "", "suffix" to "", "digits" to "6", "start" to "1", "position" to "BOTTOM_RIGHT")),
    REDACT(mapOf("terms" to "", "patterns" to "", "regex" to "", "sanitize" to "true")),
    SANITIZE(mapOf("items" to "")),
    /** [report]: FINAL_CHECK_2 Low — the accessibility / PDF-UA report attached to the output as HTML. */
    ACCESS_CHECK(mapOf("report" to "false")),
    /** FINAL_CHECK_2 Low (B #43): a check-only preflight; [onFail]: "continue" (note), "stop" (the file fails), "skip" (the file is left out). */
    PREFLIGHT(mapOf("profile" to "a2b", "onFail" to "stop")),
    /** FINAL_CHECK_2 Low (§21): a divider / panel title in the recipe editor; does nothing when run. */
    DIVIDER(mapOf("text" to "")),
    CONVERT_OFFICE(mapOf("format" to "docx")),
    PRINT(mapOf("merged" to "false")),
    NOTE(mapOf("text" to "")),
    RUN_RECIPE(mapOf("recipe" to "")),
    ;

    /** Steps that never change the file (an instruction, the print request, the accessibility report). */
    val passive: Boolean get() = this == NOTE || this == PRINT || this == ACCESS_CHECK || this == PREFLIGHT || this == DIVIDER

    companion object { fun of(name: String?): StepType? = entries.firstOrNull { it.name == name } }
}

/** [ask]: the step's options are shown again when the recipe is started (Foxit "Prompt User"). */
data class Step(val type: StepType, val params: Map<String, String> = type.defaults, val enabled: Boolean = true, val ask: Boolean = false) {
    fun p(key: String): String = params[key] ?: type.defaults[key].orEmpty()
    fun int(key: String, def: Int): Int = p(key).trim().toIntOrNull() ?: def
    fun float(key: String, def: Float): Float = p(key).trim().toFloatOrNull() ?: def
    fun bool(key: String): Boolean = p(key).trim().equals("true", true)
}

/** What happens when the output name is taken: a numbered name, replace it, skip the file, or ask before the run. */
enum class Conflict {
    RENAME, OVERWRITE, SKIP, ASK;
    companion object { fun of(s: String?): Conflict? = entries.firstOrNull { it.name == s } }
}

/**
 * Output naming: tokens {name} {recipe} {date} {time} {n}. [sameFolder] writes next to the input. [folderUri] is a
 * custom destination folder (SAF tree; [folderName] for display). [conflict] settles names that are taken
 * ([overwrite] is the older yes/no switch and seeds it).
 */
data class OutputOptions(
    val pattern: String = "{name}_{recipe}", val sameFolder: Boolean = false, val overwrite: Boolean = false,
    val conflict: Conflict = if (overwrite) Conflict.OVERWRITE else Conflict.RENAME,
    val folderUri: String = "", val folderName: String = "",
)

data class Recipe(
    val id: String,
    val name: String,
    val steps: List<Step>,
    val output: OutputOptions = OutputOptions(),
    val builtIn: Boolean = false,
    val description: String = "",
)

object RecipeJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }
    const val VERSION = 1

    fun toJsonObject(r: Recipe): JsonObject = buildJsonObject {
        put("v", VERSION)
        put("id", r.id)
        put("name", r.name)
        put("builtIn", r.builtIn)
        if (r.description.isNotEmpty()) put("description", r.description)
        put("output", buildJsonObject {
            put("pattern", r.output.pattern); put("sameFolder", r.output.sameFolder); put("overwrite", r.output.overwrite); put("conflict", r.output.conflict.name)
            if (r.output.folderUri.isNotEmpty()) { put("folderUri", r.output.folderUri); put("folderName", r.output.folderName) }
        })
        put("steps", buildJsonArray {
            r.steps.forEach { s ->
                add(buildJsonObject {
                    put("type", s.type.name)
                    put("enabled", s.enabled)
                    if (s.ask) put("ask", true)
                    put("params", buildJsonObject { s.params.toSortedMap().forEach { (k, v) -> put(k, v) } })
                })
            }
        })
    }

    fun toJson(r: Recipe): String = toJsonObject(r).toString()

    fun fromJsonObject(o: JsonObject): Recipe {
        val out = o["output"]?.jsonObject
        fun outStr(k: String) = out?.get(k)?.jsonPrimitive?.contentOrNull
        val steps = o["steps"]?.jsonArray.orEmpty().mapNotNull { e ->
            val so = e as? JsonObject ?: return@mapNotNull null
            val type = StepType.of(so["type"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null   // unknown steps from newer versions are skipped
            val params = so["params"]?.jsonObject?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: type.defaults
            Step(type, params, so["enabled"]?.jsonPrimitive?.booleanOrNull ?: true, so["ask"]?.jsonPrimitive?.booleanOrNull ?: false)
        }
        val overwrite = out?.get("overwrite")?.jsonPrimitive?.booleanOrNull ?: false
        return Recipe(
            id = o["id"]?.jsonPrimitive?.contentOrNull ?: newId(),
            name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            steps = steps,
            output = OutputOptions(
                pattern = outStr("pattern") ?: "{name}_{recipe}",
                sameFolder = out?.get("sameFolder")?.jsonPrimitive?.booleanOrNull ?: false,
                overwrite = overwrite,
                conflict = Conflict.of(outStr("conflict")) ?: if (overwrite) Conflict.OVERWRITE else Conflict.RENAME,
                folderUri = outStr("folderUri").orEmpty(),
                folderName = outStr("folderName").orEmpty(),
            ),
            builtIn = o["builtIn"]?.jsonPrimitive?.booleanOrNull ?: false,
            description = o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    fun fromJson(s: String): Recipe = fromJsonObject(json.parseToJsonElement(s).jsonObject)

    fun listToJson(list: List<Recipe>): String = JsonArray(list.map { toJsonObject(it) }).toString()

    fun listFromJson(s: String): List<Recipe> = runCatching {
        json.parseToJsonElement(s).jsonArray.mapNotNull { e -> runCatching { fromJsonObject(e.jsonObject) }.getOrNull() }
    }.getOrDefault(emptyList())

    private var counter = 0
    fun newId(): String = "r" + System.currentTimeMillis().toString(36) + (counter++).toString(36)
}

object OutputNaming {
    /** Expand the pattern; the result is a safe file base name (no extension). */
    fun apply(pattern: String, name: String, recipe: String, index: Int, date: String, time: String): String {
        val s = pattern.ifBlank { "{name}" }
            .replace("{name}", name).replace("{recipe}", recipe).replace("{date}", date).replace("{time}", time)
            .replace("{n}", (index + 1).toString().padStart(3, '0'))
        return TextNorm.safeFileName(s, 120).ifBlank { name.ifBlank { "file" } }
    }
}

/** Recipes shipped with the app (names are resolved through string resources by id in the UI). */
object BuiltInRecipes {
    val all: List<Recipe> = listOf(
        Recipe("builtin_scan_archive", "scan_archive", listOf(Step(StepType.OCR), Step(StepType.OPTIMIZE), Step(StepType.PDFA)), OutputOptions("{name}_archive"), true),
        Recipe("builtin_web", "web", listOf(Step(StepType.REMOVE_METADATA), Step(StepType.OPTIMIZE, mapOf("dpi" to "110", "quality" to "60", "strip" to "true"))), OutputOptions("{name}_web"), true),
        Recipe("builtin_ocr", "ocr", listOf(Step(StepType.OCR)), OutputOptions("{name}_ocr"), true),
        Recipe("builtin_number", "number", listOf(Step(StepType.PAGE_NUMBERS)), OutputOptions("{name}_numbered"), true),
        Recipe("builtin_access", "access", listOf(Step(StepType.OCR), Step(StepType.SET_INFO, ask = true), Step(StepType.AUTO_TAG, ask = true), Step(StepType.ALT_TEXT, ask = true), Step(StepType.ACCESS_CHECK)), OutputOptions("{name}_accessible"), true),
        Recipe("builtin_share", "share", listOf(Step(StepType.FLATTEN), Step(StepType.REMOVE_METADATA), Step(StepType.OPTIMIZE)), OutputOptions("{name}_share"), true),
        // nav-batch: the other Foxit Action Wizard built-ins
        Recipe("builtin_distribute", "distribute", listOf(
            Step(StepType.SANITIZE), Step(StepType.WATERMARK, ask = true), Step(StepType.HEADER_FOOTER, ask = true), Step(StepType.OPTIMIZE),
        ), OutputOptions("{name}_final"), true),
        Recipe("builtin_sensitive", "sensitive", listOf(
            Step(StepType.REDACT, mapOf("terms" to "", "patterns" to "EMAIL,PHONE_INTL,CARD,IBAN", "regex" to "", "sanitize" to "true"), ask = true),
            Step(StepType.SANITIZE), Step(StepType.OPTIMIZE), Step(StepType.PROTECT, ask = true),
        ), OutputOptions("{name}_redacted"), true),
        Recipe("builtin_pdfua", "pdfua", listOf(
            Step(StepType.OCR), Step(StepType.SET_INFO, ask = true), Step(StepType.AUTO_TAG, mapOf("lang" to "ar", "pdfua" to "true"), ask = true), Step(StepType.ALT_TEXT, ask = true), Step(StepType.ACCESS_CHECK),
        ), OutputOptions("{name}_pdfua"), true),
    )
}

/** Pure list / step helpers of the recipe screens and the runner (unit-tested). */
object RecipeOps {

    /** A user copy of [r] (built-in or not) with a new id and [name]. */
    fun clone(r: Recipe, name: String): Recipe = r.copy(id = RecipeJson.newId(), name = name, builtIn = false)

    /** [list] with the recipe [id] moved by [delta] places (clamped); an unknown id leaves it unchanged. */
    fun move(list: List<Recipe>, id: String, delta: Int): List<Recipe> {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return list
        val j = (i + delta).coerceIn(0, list.size - 1)
        if (i == j) return list
        return list.toMutableList().apply { add(j, removeAt(i)) }
    }

    /**
     * The enabled steps of [r], each "run recipe" step replaced by the enabled steps of the recipe it names
     * ([lookup] by id or name). Cycles and nesting deeper than [maxDepth] are cut (the step is dropped).
     */
    fun expand(r: Recipe, maxDepth: Int = 4, lookup: (String) -> Recipe?): List<Step> {
        fun walk(steps: List<Step>, path: Set<String>, depth: Int): List<Step> = steps.filter { it.enabled }.flatMap { s ->
            if (s.type != StepType.RUN_RECIPE) listOf(s)
            else {
                val sub = lookup(s.p("recipe").trim())
                if (sub == null || sub.id in path || depth >= maxDepth) emptyList() else walk(sub.steps, path + sub.id, depth + 1)
            }
        }
        return walk(r.steps, setOf(r.id), 0)
    }

    /** Indexes of the steps shown before a run: those marked "ask" and instructions with text. */
    fun asked(steps: List<Step>): List<Int> = steps.indices.filter { i ->
        val s = steps[i]
        s.enabled && (s.ask && !s.type.passive || s.type == StepType.NOTE && s.p("text").isNotBlank())
    }

    /** "key=value" lines (or ";"-separated) → ordered map; blank keys are ignored, a key without "=" maps to "". */
    fun parsePairs(s: String): Map<String, String> = LinkedHashMap<String, String>().also { m ->
        s.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val k = line.substringBefore('=').trim()
            if (k.isNotEmpty()) m[k] = if ('=' in line) line.substringAfter('=').trim() else ""
        }
    }

    /** Comma / Arabic comma / line separated list, trimmed, blanks dropped. */
    fun list(s: String): List<String> = s.split(',', '\n', '،').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Output name for [base].[ext] in a folder where [exists] tells the taken names: [Conflict.RENAME] numbers it
     * ("_2", "_3"…), OVERWRITE keeps it, SKIP returns null when it is taken ([Conflict.ASK] is settled before a run
     * and behaves like RENAME here).
     */
    fun resolveName(base: String, ext: String, conflict: Conflict, exists: (String) -> Boolean): String? {
        val first = "$base.$ext"
        if (!exists(first)) return first
        return when (conflict) {
            Conflict.OVERWRITE -> first
            Conflict.SKIP -> null
            Conflict.RENAME, Conflict.ASK -> {
                var n = 2
                var name: String
                do { name = "${base}_${n++}.$ext" } while (exists(name))
                name
            }
        }
    }

    /** Output extension of a step list: the format of the last CONVERT_OFFICE step, else pdf. */
    fun outputExt(steps: List<Step>): String =
        steps.lastOrNull { it.enabled && it.type == StepType.CONVERT_OFFICE }?.p("format")?.trim()?.lowercase()?.ifBlank { null } ?: "pdf"
}
