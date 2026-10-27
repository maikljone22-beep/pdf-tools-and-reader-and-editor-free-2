package com.smartdocs.editor.core.voice

import android.content.Context
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** One spoken line: speaker 0 = host / narrator, 1 = guest. */
data class ScriptLine(val speaker: Int, val text: String, val chapter: String? = null)   // ai6v: chapter starts here

/** A line with its position in the final audio. */
data class TimedLine(val speaker: Int, val text: String, val startMs: Long, val endMs: Long, val chapter: String? = null)

data class SpokenAudio(val file: File, val lines: List<TimedLine>, val transcript: File)

/**
 * Audio summary ("قراءة الملخص") and two-voice podcast ("بودكاست ذكي"): writes the script with the chat model,
 * synthesizes each line with [AiSpeech] (host / guest voices), joins the pieces into one MP3 (or WAV) and keeps
 * the start / end time of every line for the transcript view.
 */
object PodcastGenerator {

    enum class Mode { SUMMARY, PODCAST }

    private const val SUMMARY_PROMPT =
        "اكتب ملخصاً صوتياً قصيراً لهذا المستند بالعربية الفصحى في نحو 150 إلى 220 كلمة، بأسلوب مذيع يقدّم نشرة واضحة. " +
            "استخدم جملاً قصيرة سهلة النطق، وابدأ بجملة تعرّف بموضوع المستند، ثم أهم النقاط، ثم خلاصة. " +
            "لا تستخدم عناوين ولا رموزاً ولا تعداداً نقطياً ولا Markdown، واكتب الأرقام بالكلمات إن أمكن."

    private const val PODCAST_PROMPT =
        "اكتب نص حلقة بودكاست قصيرة بالعربية الفصحى المبسّطة يتحاور فيها مقدّم البرنامج (host) مع ضيف خبير (guest) حول هذا المستند. " +
            "من 12 إلى 18 مداخلة متناوبة، كل مداخلة جملة إلى ثلاث جمل قصيرة سهلة النطق. يبدأ المقدّم بالترحيب وتقديم الموضوع، " +
            "ويشرح الضيف الأفكار الرئيسية بأمثلة، ويطرح المقدّم أسئلة ذكية، ويختم المقدّم بخلاصة وشكر. " +
            "لا تخترع معلومات غير موجودة في المستند. أعد JSON فقط بهذا الشكل دون أي نص آخر: " +
            "[{\"speaker\": \"host\", \"text\": \"...\"}, {\"speaker\": \"guest\", \"text\": \"...\"}]"

    /** Ask the model for the script. [docText] should already be trimmed (see [AiClient.trimDocument]). */
    suspend fun script(client: AiClient, docText: String, mode: Mode, docName: String, options: PodcastOptions? = null): List<ScriptLine> {
        // ai6v (B14): focus areas + length when the user set them
        val prompt = if (options == null) (if (mode == Mode.SUMMARY) SUMMARY_PROMPT else PODCAST_PROMPT)
            else if (mode == Mode.SUMMARY) PodcastScript.summaryPrompt(options) else PodcastScript.podcastPrompt(options)
        val msgs = listOf(
            ChatMessage("system", AiClient.SYSTEM_PROMPT),
            ChatMessage("system", "عنوان المستند: $docName\nنص المستند:\n$docText"),
            ChatMessage("user", prompt),
        )
        val answer = client.complete(msgs)
        val lines = if (mode == Mode.SUMMARY) summaryLines(answer) else podcastLines(answer)
        if (lines.isEmpty()) throw com.smartdocs.editor.core.ai.AiException("empty script")
        return lines
    }

    private fun cleanText(s: String): String = s.replace(Regex("[*#`_>]+"), "").replace(Regex("\\s+"), " ").trim()

    /** Summary → sentences grouped into lines of ~1–2 sentences (each gets its own highlight in the transcript). */
    fun summaryLines(answer: String): List<ScriptLine> =
        AiSpeech.split(cleanText(answer.replace(Regex("^\\s*[-•]\\s*", RegexOption.MULTILINE), "")), 220).map { ScriptLine(0, it) }

    /** Parse the podcast JSON; falls back to "المضيف: …" / "الضيف: …" lines. */
    fun podcastLines(answer: String): List<ScriptLine> {
        val a = answer.indexOf('['); val b = answer.lastIndexOf(']')
        if (a >= 0 && b > a) {
            val arr = runCatching { AiClient.json.parseToJsonElement(answer.substring(a, b + 1)) as? JsonArray }.getOrNull()
            val parsed = arr?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val sp = (o["speaker"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
                val text = cleanText((o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                val chapter = (o["chapter"] as? JsonPrimitive)?.contentOrNull?.let { cleanText(it) }?.takeIf { it.isNotEmpty() }
                if (text.isEmpty()) null else ScriptLine(if (sp.contains("guest") || sp.contains("ضيف")) 1 else 0, text, chapter)
            }.orEmpty()
            if (parsed.isNotEmpty()) return parsed
        }
        val out = ArrayList<ScriptLine>()
        answer.lines().forEach { raw ->
            val l = cleanText(raw); if (l.isEmpty()) return@forEach
            val m = Regex("^(المضيف|المقدم|المقدّم|مقدم البرنامج|host|الضيف|guest)\\s*[:：]\\s*(.+)$", RegexOption.IGNORE_CASE).find(l)
            if (m != null) {
                val who = m.groupValues[1].lowercase()
                out += ScriptLine(if (who.contains("ضيف") || who.contains("guest")) 1 else 0, m.groupValues[2].trim())
            } else if (out.isNotEmpty()) {
                out[out.size - 1] = out.last().copy(text = out.last().text + " " + l)
            }
        }
        return out
    }

    /**
     * Synthesize every line (host → [hostVoice], guest → [guestVoice]) and join them into [outFile] (extension is
     * corrected to the provider's format). [onProgress] gets (lines done, total).
     */
    suspend fun render(ctx: Context, lines: List<ScriptLine>, hostVoice: String, guestVoice: String, outFile: File, title: String,
                       onProgress: (Int, Int) -> Unit = { _, _ -> }): SpokenAudio = withContext(Dispatchers.IO) {
        val instructionsHost = "تحدّث بالعربية الفصحى بنبرة مذيع ودود وواضح."
        val instructionsGuest = "تحدّث بالعربية الفصحى بنبرة خبير هادئ وواثق."
        val pieces = ArrayList<File>()
        val timed = ArrayList<TimedLine>()
        var t = 0L
        lines.forEachIndexed { i, line ->
            val voice = if (line.speaker == 1) guestVoice else hostVoice
            val f = AiSpeech.synthesizeLong(ctx, line.text, voice, 1f, if (line.speaker == 1) instructionsGuest else instructionsHost)
            val d = AudioConcat.durationMs(f).coerceAtLeast(1L)
            timed += TimedLine(line.speaker, line.text, t, t + d, line.chapter)
            t += d
            pieces += f
            onProgress(i + 1, lines.size)
        }
        val ext = pieces.firstOrNull()?.extension ?: "mp3"
        val out = if (outFile.extension.equals(ext, true)) outFile else File(outFile.parentFile, outFile.nameWithoutExtension + "." + ext)
        AudioConcat.join(pieces, out)
        val transcript = File(out.parentFile, out.nameWithoutExtension + ".txt")
        transcript.writeText(buildString {
            append(title).append("\n\n")
            timed.forEach { l -> l.chapter?.let { append("
## ").append(it).append('
') }; append("[").append(fmt(l.startMs)).append("] ").append(if (l.speaker == 1) "الضيف: " else if (lines.any { it.speaker == 1 }) "المضيف: " else "").append(l.text).append('\n') }
        })
        saveSidecar(out, timed)
        PodcastScript.saveScript(out, lines)   // ai6v: the editable script next to the audio
        SpokenAudio(out, timed, transcript)
    }

    /** Timings are kept next to the audio (`.lines.json`) so the transcript works when the file is reopened. */
    private fun saveSidecar(audio: File, lines: List<TimedLine>) {
        val arr = kotlinx.serialization.json.buildJsonArray {
            lines.forEach { l -> add(kotlinx.serialization.json.buildJsonObject {
                put("s", JsonPrimitive(l.speaker)); put("t", JsonPrimitive(l.text)); put("a", JsonPrimitive(l.startMs)); put("b", JsonPrimitive(l.endMs))
                l.chapter?.let { put("c", JsonPrimitive(it)) }
            }) }
        }
        runCatching { File(audio.parentFile, audio.nameWithoutExtension + ".lines.json").writeText(arr.toString()) }
    }

    /** Load a previously generated audio + transcript (null when absent). */
    fun loadExisting(audio: File): SpokenAudio? {
        if (!audio.exists() || audio.length() == 0L) return null
        val side = File(audio.parentFile, audio.nameWithoutExtension + ".lines.json")
        val lines = runCatching {
            (AiClient.json.parseToJsonElement(side.readText()) as JsonArray).mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                TimedLine(
                    (o["s"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0, (o["t"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    (o["a"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L, (o["b"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
                    (o["c"] as? JsonPrimitive)?.contentOrNull,
                )
            }
        }.getOrDefault(emptyList())
        return SpokenAudio(audio, lines, File(audio.parentFile, audio.nameWithoutExtension + ".txt"))
    }

    /** Output file for a document and mode (an existing WAV variant is preferred when present). */
    fun outputFor(ctx: Context, docName: String, mode: Mode): File {
        val base = docName.substringBeforeLast('.').ifBlank { "document" } + if (mode == Mode.SUMMARY) "_summary" else "_podcast"
        val wav = File(audioDir(ctx), "$base.wav")
        return if (wav.exists()) wav else File(audioDir(ctx), "$base.mp3")
    }

    fun fmt(ms: Long): String { val s = ms / 1000; return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60) }

    /** Folder for generated audio (next to the documents folder, not inside it). */
    fun audioDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Audio").apply { mkdirs() }
}
