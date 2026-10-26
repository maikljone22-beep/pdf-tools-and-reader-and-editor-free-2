package com.smartdocs.editor.core.share5

import android.content.Context
import com.smartdocs.editor.core.collab.CollabClient
import com.smartdocs.editor.core.collab.CollabConfig
import com.smartdocs.editor.core.collab.CollabStore
import com.smartdocs.editor.core.collab.ReviewMeta
import com.smartdocs.editor.core.collab.ReviewRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Review tracker (SHR-2): every review this device created or joined, with live server state. */
object ReviewTracker {
    data class Item(
        val ref: ReviewRef, val meta: ReviewMeta?, val comments: Int, val othersComments: Int, val newComments: Int,
        val gone: Boolean = false, val error: String? = null,
    ) {
        val ended: Boolean get() = gone || meta?.expired == true || ref.closed
    }

    /** Loads meta + comments of all reviews in parallel. Never throws; per-review errors are in [Item.error]. */
    suspend fun load(c: Context): List<Item> = withContext(Dispatchers.IO) {
        val refs = CollabStore.load(c)
        if (!CollabConfig.enabled) return@withContext refs.map { Item(it, null, 0, 0, 0, error = "off") }
        val me = CollabStore.participantId(c)
        coroutineScope {
            refs.map { ref ->
                async {
                    try {
                        val meta = CollabClient.meta(ref.id, ref.code)
                        val all = CollabClient.comments(ref.id, ref.code).filter { !it.deleted }
                        val others = all.count { it.participant != me }
                        Item(ref, meta, all.size, others, (others - ReviewExtras.seenCount(c, ref.id)).coerceAtLeast(0))
                    } catch (e: Exception) {
                        Item(ref, null, 0, 0, 0, gone = CollabClient.isGone(e) || (e is com.smartdocs.editor.core.cloud.HttpException && e.code == 403), error = e.message)
                    }
                }
            }.awaitAll()
        }
    }

    /** Marks the current number of others' comments as seen (clears the badge). */
    fun markSeen(c: Context, item: Item) = ReviewExtras.setSeenCount(c, item.ref.id, item.othersComments)
}
