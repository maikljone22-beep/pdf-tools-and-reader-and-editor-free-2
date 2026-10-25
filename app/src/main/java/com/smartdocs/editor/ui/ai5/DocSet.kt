package com.smartdocs.editor.ui.ai5

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.smartdocs.editor.core.ai5.AiStore
import com.smartdocs.editor.core.ai5.DocIndex
import com.smartdocs.editor.core.ai5.DocIndexer
import java.io.File

/**
 * The documents a chat / summary / study session works on: one file, several files, or a Space. Indexes are
 * built in the background (whole document, cached on disk).
 */
class DocSet(val files: List<File>, val spaceId: String?, val persona: String, private val pageText: (suspend (Int) -> String)? = null,
             private val pageCount: Int? = null, private val password: String? = null) {
    var indexes by mutableStateOf<List<DocIndex>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var progress by mutableStateOf(0f); private set
    var error by mutableStateOf<String?>(null); private set

    val ready: Boolean get() = indexes.isNotEmpty() && !loading

    /** Storage scope for chats / creations: the Space, or the content key of the single document. */
    val scope: String get() = spaceId?.let { AiStore.spaceScope(it) } ?: indexes.firstOrNull()?.key ?: files.firstOrNull()?.absolutePath.orEmpty()

    val title: String get() = files.singleOrNull()?.name ?: files.joinToString("، ") { it.nameWithoutExtension }

    suspend fun load(ctx: Context) {
        if (files.isEmpty()) return
        loading = true; error = null; progress = 0f
        try {
            val list = ArrayList<DocIndex>()
            files.forEachIndexed { fi, f ->
                runCatching {
                    if (files.size == 1 && pageText != null && pageCount != null)
                        DocIndexer.get(ctx, f, password, pageText, pageCount) { i, n -> progress = (i + 1f) / n.coerceAtLeast(1) }
                    else DocIndexer.get(ctx, f, password) { i, n -> progress = (fi + (i + 1f) / n.coerceAtLeast(1)) / files.size }
                }.onSuccess { list += it }.onFailure { error = it.message ?: it.javaClass.simpleName }
            }
            indexes = list
        } finally { loading = false }
    }
}

@Composable
fun rememberDocSet(ctx: Context, files: List<File>, spaceId: String? = null, persona: String = "", pageText: (suspend (Int) -> String)? = null,
                   pageCount: Int? = null, password: String? = null): DocSet {
    val key = files.joinToString("|") { it.absolutePath } + "#" + spaceId
    val set = remember(key) { DocSet(files, spaceId, persona, pageText, pageCount, password) }
    LaunchedEffect(set) { set.load(ctx.applicationContext) }
    return set
}
