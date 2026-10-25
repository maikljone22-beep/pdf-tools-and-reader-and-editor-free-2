package com.smartdocs.editor.core.ai5

import android.content.Context
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai.ChatMessage
import java.io.File

/**
 * Material that covers the WHOLE book for one-shot generators (podcast / audio summary, slides): short documents
 * are returned as page-marked text; long ones are condensed segment by segment (≤ [SummaryGen.MAX_SEGMENTS]
 * requests, cached per content hash) into notes with page references, in reading order.
 */
object WholeBook {
    const val DIRECT_LIMIT = 12_000

    fun requestsNeeded(idx: DocIndex): Int = if (idx.totalChars <= DIRECT_LIMIT) 0 else SummaryGen.segments(idx).size

    suspend fun material(ctx: Context, client: AiClient, idx: DocIndex, progress: (Int, Int) -> Unit = { _, _ -> }): String {
        if (idx.totalChars <= DIRECT_LIMIT) return DocIndex.material(idx.chunks)
        val cache = File(File(ctx.filesDir, "ai5/book").apply { mkdirs() }, idx.key + ".txt")
        if (cache.exists() && cache.length() > 0) return cache.readText()
        val segs = SummaryGen.segments(idx)
        if (!AiCalls.fits(ctx, client, segs.size + 1)) throw AiQuotaException("need ${segs.size + 1}")
        val notes = segs.mapIndexed { i, seg ->
            progress(i, segs.size)
            val m = DocIndex.material(DocIndex(idx.key, idx.name, idx.path, idx.pageCount, seg).spread(SummaryGen.SEGMENT_CHARS))
            val first = seg.first().page + 1; val last = seg.last().page + 1
            "### الصفحات $first–$last\n" + AiCalls.complete(ctx, client, listOf(
                ChatMessage("system", AiCalls.SYSTEM),
                ChatMessage("user", "هذا الجزء ${i + 1} من ${segs.size} من كتاب \"${idx.name}\". اكتب ملاحظات مركزة تغطي كل أفكاره وأمثلته المهمة بالترتيب، " +
                    "مع (ص N) بعد كل ملاحظة. لا تكتب مقدمة.\n\n$m"),
            ))
        }
        progress(segs.size, segs.size)
        val text = notes.joinToString("\n\n")
        runCatching { cache.writeText(text) }
        return text
    }
}
