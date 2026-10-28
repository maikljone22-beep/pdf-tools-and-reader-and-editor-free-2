package com.smartdocs.editor.ui.ai6

import android.content.Context
import android.graphics.RectF
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.R
import com.smartdocs.editor.core.edit.ContentEdit
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.ui.edit.applyEdit
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * aor (AI-8 / §35): hand-over of an AI image from the image screen to the reader that has [Request.doc] open. The
 * reader places it centred on the page as page content through [applyEdit], so it is one step of the reader's
 * unified undo history (and the signed-file gate of [ReaderState.applyInPlace] applies).
 */
object AiImageInsert {
    data class Request(val doc: String, val page: Int, val image: String)

    var pending by mutableStateOf<Request?>(null)

    /** Called by the reader: inserts the pending image when it is for this document; no-op otherwise. */
    suspend fun consume(ctx: Context, state: ReaderState) {
        val r = pending ?: return
        val f = state.file ?: return
        if (state.session == null) return
        if (runCatching { File(r.doc).canonicalPath != f.canonicalPath }.getOrDefault(true)) return
        pending = null
        val page = r.page.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
        try {
            val bmp = withContext(Dispatchers.IO) { PdfOps.decodeScaled(File(r.image), 2400) } ?: throw IllegalStateException("image")
            val (pw, ph) = state.pageSize(page)
            val p = ImagePlacement.centred(bmp.width, bmp.height, pw, ph)
            val done = applyEdit(state, label = { ctx.getString(R.string.aor_img_history, page + 1) }) { src, out ->
                ContentEdit.addImage(src, out, state.password, page, bmp, RectF(p[0], p[1] + p[3], p[0] + p[2], p[1]))
            }
            if (done != null) { state.jumpTo(page); Toast.makeText(ctx, ctx.getString(R.string.aor_img_inserted, page + 1), Toast.LENGTH_SHORT).show() }
            else Toast.makeText(ctx, ctx.getString(R.string.aor_img_not_inserted), Toast.LENGTH_LONG).show()
        } catch (e: CancellationException) { throw e
        } catch (e: Throwable) {
            Toast.makeText(ctx, ctx.getString(R.string.aor_img_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }
}
