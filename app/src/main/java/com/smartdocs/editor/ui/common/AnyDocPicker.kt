package com.smartdocs.editor.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert6.FileKind6
import com.smartdocs.editor.core.convert6.Kind6
import com.smartdocs.editor.core.convert6.ToPdf6
import kotlinx.coroutines.launch
import java.io.File

/**
 * FINAL_CHECK_2 #7: a page source that is any document (Word, Excel, PowerPoint, pictures, text, e-book …), not only a
 * PDF. Non-PDF files are converted with the one to-PDF engine ([ToPdf6.convert]) into the temp folder before
 * [onPicked] gets them; a file that cannot be converted is left out with a message.
 */
@Composable
fun rememberAnyDocPicker(multiple: Boolean = false, onPicked: (List<File>) -> Unit): () -> Unit {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return rememberPdfPicker(multiple = multiple, mimes = arrayOf("*/*")) { files ->
        scope.launch {
            val out = files.mapNotNull { f ->
                runCatching { asPdf(ctx, f, app.documents.tempDir) }
                    .onFailure { Toast.makeText(ctx, ctx.getString(R.string.cv9_not_convertible, f.name), Toast.LENGTH_LONG).show() }
                    .getOrNull()
            }
            if (out.isNotEmpty()) onPicked(out)
        }
    }
}

/** [f] itself when it is a PDF, else a PDF made from it in [tempDir]. */
suspend fun asPdf(ctx: Context, f: File, tempDir: File): File {
    if (FileKind6.of(f) == Kind6.PDF) return f
    val out = File(tempDir, f.nameWithoutExtension + "_" + System.nanoTime() + ".pdf")
    try { return ToPdf6.convert(ctx, f, out) } catch (e: Throwable) { out.delete(); throw e }
}
