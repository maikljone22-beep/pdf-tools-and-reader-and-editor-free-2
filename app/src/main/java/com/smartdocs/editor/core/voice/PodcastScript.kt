package com.smartdocs.editor.core.voice

import com.smartdocs.editor.core.ai.AiClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** ai6v (B14): how long the generated podcast / audio summary should be. */
enum class PodcastLength { SHORT, MEDIUM, LONG }

/** ai6v (B14): user options for generating a script: focus areas (free text) and length. */
data class PodcastOptions(val focus: String = "", val length: PodcastLength = PodcastLength.MEDIUM)

/**
 * ai6v (B14): pure helpers for podcast scripts — prompt building from [PodcastOptions], transcript editing
 * (edit / swap speaker / add / delete / move lines), chapters, the editable script sidecar and duplicates.
 * Re-synthesis after an edit only pays for changed lines because [AiSpeech.synthesize] caches every piece by its
 * text + voice: rendering the edited script again reuses the unchanged lines' audio.
 */
object PodcastScript {

    fun podcastTurns(l: PodcastLength): String = when (l) { PodcastLength.SHORT -> "6 إلى 8"; PodcastLength.MEDIUM -> "12 إلى 18"; PodcastLength.LONG -> "24 إلى 32" }
    fun summaryWords(l: PodcastLength): String = when (l) { PodcastLength.SHORT -> "80 إلى 120"; PodcastLength.MEDIUM -> "150 إلى 220"; PodcastLength.LONG -> "300 إلى 400" }

    private fun focusLine(o: PodcastOptions): String =
        if (o.focus.isBlank()) "" else " ركّز بشكل خاص على: ${o.focus.trim().take(300)}."

    fun summaryPrompt(o: PodcastOptions): String =
        "اكتب ملخصاً صوتياً لهذا المستند بالعربية الفصحى في نحو ${summaryWords(o.length)} كلمة، بأسلوب مذيع يقدّم نشرة واضحة. " +
            "استخدم جملاً قصيرة سهلة النطق، وابدأ بجملة تعرّف بموضوع المستند، ثم أهم النقاط، ثم خلاصة." + focusLine(o) +
            " لا تستخدم عناوين ولا رموزاً ولا تعداداً نقطياً ولا Markdown، واكتب الأرقام بالكلمات إن أمكن."

    fun podcastPrompt(o: PodcastOptions): String =
        "اكتب نص حلقة بودكاست بالعربية الفصحى المبسّطة يتحاور فيها مقدّم البرنامج (host) مع ضيف خبير (guest) حول هذا المستند. " +
            "من ${podcastTurns(o.length)} مداخلة متناوبة، كل مداخلة جملة إلى ثلاث جمل قصيرة سهلة النطق. يبدأ المقدّم بالترحيب وتقديم الموضوع، " +
            "ويشرح الضيف الأفكار الرئيسية بأمثلة، ويطرح المقدّم أسئلة ذكية، ويختم المقدّم بخلاصة وشكر." + focusLine(o) +
            " قسّم الحلقة إلى فصول: ضع الحقل \"chapter\" (عنوان قصير) في أول مداخلة من كل فصل جديد فقط. " +
            "لا تخترع معلومات غير موجودة في المستند. أعد JSON فقط بهذا الشكل دون أي نص آخر: " +
            "[{\"speaker\": \"host\", \"chapter\": \"المقدمة\", \"text\": \"...\"}, {\"speaker\": \"guest\", \"text\": \"...\"}]"

    // ---- transcript editing (each returns a new list; indices out of range leave the list unchanged) ----
    fun editText(lines: List<ScriptLine>, i: Int, text: String): List<ScriptLine> =
        if (i !in lines.indices) lines else lines.toMutableList().also { it[i] = it[i].copy(text = text) }

    fun swapSpeaker(lines: List<ScriptLine>, i: Int): List<ScriptLine> =
        if (i !in lines.indices) lines else lines.toMutableList().also { it[i] = it[i].copy(speaker = 1 - it[i].speaker.coerceIn(0, 1)) }

    /** Insert a new line after [i] (−1 = at the start); the speaker alternates from the previous line. */
    fun insertAfter(lines: List<ScriptLine>, i: Int, text: String = ""): List<ScriptLine> {
        val at = (i + 1).coerceIn(0, lines.size)
        val speaker = lines.getOrNull(at - 1)?.let { 1 - it.speaker.coerceIn(0, 1) } ?: 0
        return lines.toMutableList().also { it.add(at, ScriptLine(speaker, text)) }
    }

    fun delete(lines: List<ScriptLine>, i: Int): List<ScriptLine> = if (i !in lines.indices) lines else lines.toMutableList().also { it.removeAt(i) }

    /** Move line [i] by [delta] (−1 up, +1 down). */
    fun move(lines: List<ScriptLine>, i: Int, delta: Int): List<ScriptLine> {
        val j = i + delta
        if (i !in lines.indices || j !in lines.indices) return lines
        return lines.toMutableList().also { val x = it.removeAt(i); it.add(j, x) }
    }

    /** Lines that will need new audio compared with [old] (same text + speaker → cached). */
    fun changedCount(old: List<ScriptLine>, new: List<ScriptLine>): Int {
        val have = old.map { it.speaker to it.text.trim() }.toHashSet()
        return new.count { (it.speaker to it.text.trim()) !in have }
    }

    /** Drop empty lines before rendering. */
    fun clean(lines: List<ScriptLine>): List<ScriptLine> = lines.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }

    /** Chapters: (title, start ms) from timed lines that begin a chapter. */
    fun chapters(lines: List<TimedLine>): List<Pair<String, Long>> = lines.mapNotNull { l -> l.chapter?.takeIf { it.isNotBlank() }?.let { it to l.startMs } }

    // ---- editable script sidecar (`<audio>.script.json`) ----
    fun toJson(lines: List<ScriptLine>): String = buildJsonArray {
        lines.forEach { l -> add(buildJsonObject {
            put("s", JsonPrimitive(l.speaker)); put("t", JsonPrimitive(l.text)); l.chapter?.let { put("c", JsonPrimitive(it)) }
        }) }
    }.toString()

    fun fromJson(s: String): List<ScriptLine> = runCatching {
        (AiClient.json.parseToJsonElement(s) as JsonArray).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            ScriptLine((o["s"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0, (o["t"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (o["c"] as? JsonPrimitive)?.contentOrNull)
        }
    }.getOrDefault(emptyList())

    fun scriptFile(audio: File): File = File(audio.parentFile, audio.nameWithoutExtension + ".script.json")

    fun saveScript(audio: File, lines: List<ScriptLine>) { runCatching { scriptFile(audio).writeText(toJson(lines)) } }

    /** The saved (possibly edited) script, else the rendered lines of the audio. */
    fun loadScript(audio: File, rendered: List<TimedLine>): List<ScriptLine> {
        val f = scriptFile(audio)
        val saved = if (f.exists()) fromJson(f.readText()) else emptyList()
        return saved.ifEmpty { rendered.map { ScriptLine(it.speaker, it.text, it.chapter) } }
    }

    /** First free "<base>_copyN.<ext>" name. */
    fun copyName(base: String, ext: String, exists: (String) -> Boolean): String {
        var n = 1
        while (exists("${base}_copy$n.$ext")) n++
        return "${base}_copy$n.$ext"
    }

    /** Duplicate an audio with its transcript / timings / script sidecars. Returns the new audio file. */
    fun duplicate(audio: File): File {
        val dir = audio.parentFile ?: throw IllegalStateException("no folder")
        val name = copyName(audio.nameWithoutExtension, audio.extension) { File(dir, it).exists() }
        val out = File(dir, name)
        audio.copyTo(out)
        val oldBase = audio.nameWithoutExtension; val newBase = out.nameWithoutExtension
        listOf(".txt", ".lines.json", ".script.json").forEach { suf ->
            val f = File(dir, oldBase + suf); if (f.exists()) runCatching { f.copyTo(File(dir, newBase + suf), overwrite = true) }
        }
        return out
    }
}
