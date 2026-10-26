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
    WATERMARK(mapOf("text" to "", "opacity" to "0.3", "size" to "48")),
    PAGE_NUMBERS(mapOf("start" to "1", "arabicDigits" to "false")),
    ROTATE(mapOf("degrees" to "90", "pages" to "")),
    REMOVE_METADATA(emptyMap()),
    SET_INFO(mapOf("title" to "", "author" to "", "subject" to "", "keywords" to "")),
    FLATTEN(mapOf("forms" to "true")),
    AUTO_TAG(mapOf("lang" to "ar")),
    PROTECT(mapOf("password" to "")),
    ;

    companion object { fun of(name: String?): StepType? = entries.firstOrNull { it.name == name } }
}

data class Step(val type: StepType, val params: Map<String, String> = type.defaults, val enabled: Boolean = true) {
    fun p(key: String): String = params[key] ?: type.defaults[key].orEmpty()
    fun int(key: String, def: Int): Int = p(key).trim().toIntOrNull() ?: def
    fun float(key: String, def: Float): Float = p(key).trim().toFloatOrNull() ?: def
    fun bool(key: String): Boolean = p(key).trim().equals("true", true)
}

/** Output naming: tokens {name} {recipe} {date} {time} {n}. [sameFolder] writes next to the input. */
data class OutputOptions(val pattern: String = "{name}_{recipe}", val sameFolder: Boolean = false, val overwrite: Boolean = false)

data class Recipe(
    val id: String,
    val name: String,
    val steps: List<Step>,
    val output: OutputOptions = OutputOptions(),
    val builtIn: Boolean = false,
)

object RecipeJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }
    const val VERSION = 1

    fun toJsonObject(r: Recipe): JsonObject = buildJsonObject {
        put("v", VERSION)
        put("id", r.id)
        put("name", r.name)
        put("builtIn", r.builtIn)
        put("output", buildJsonObject { put("pattern", r.output.pattern); put("sameFolder", r.output.sameFolder); put("overwrite", r.output.overwrite) })
        put("steps", buildJsonArray {
            r.steps.forEach { s ->
                add(buildJsonObject {
                    put("type", s.type.name)
                    put("enabled", s.enabled)
                    put("params", buildJsonObject { s.params.toSortedMap().forEach { (k, v) -> put(k, v) } })
                })
            }
        })
    }

    fun toJson(r: Recipe): String = toJsonObject(r).toString()

    fun fromJsonObject(o: JsonObject): Recipe {
        val out = o["output"]?.jsonObject
        val steps = o["steps"]?.jsonArray.orEmpty().mapNotNull { e ->
            val so = e as? JsonObject ?: return@mapNotNull null
            val type = StepType.of(so["type"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null   // unknown steps from newer versions are skipped
            val params = so["params"]?.jsonObject?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: type.defaults
            Step(type, params, so["enabled"]?.jsonPrimitive?.booleanOrNull ?: true)
        }
        return Recipe(
            id = o["id"]?.jsonPrimitive?.contentOrNull ?: newId(),
            name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            steps = steps,
            output = OutputOptions(
                pattern = out?.get("pattern")?.jsonPrimitive?.contentOrNull ?: "{name}_{recipe}",
                sameFolder = out?.get("sameFolder")?.jsonPrimitive?.booleanOrNull ?: false,
                overwrite = out?.get("overwrite")?.jsonPrimitive?.booleanOrNull ?: false,
            ),
            builtIn = o["builtIn"]?.jsonPrimitive?.booleanOrNull ?: false,
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
        Recipe("builtin_access", "access", listOf(Step(StepType.OCR), Step(StepType.AUTO_TAG)), OutputOptions("{name}_accessible"), true),
        Recipe("builtin_share", "share", listOf(Step(StepType.FLATTEN), Step(StepType.REMOVE_METADATA), Step(StepType.OPTIMIZE)), OutputOptions("{name}_share"), true),
    )
}
