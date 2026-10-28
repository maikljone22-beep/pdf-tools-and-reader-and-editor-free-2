package com.smartdocs.editor.core.cloud

import org.json.JSONObject

/**
 * Pure parsers of the providers' change feeds and change notifications (fc-sync). No Android classes: every
 * response shape is unit-tested with JSON fixtures in ChangeFeedsTest.
 *
 * Delta feeds (one cheap request instead of re-listing every folder):
 *  - Google Drive `changes.list` (page tokens; `newStartPageToken` on the last page);
 *  - Microsoft Graph `/drive/root/delta` (`@odata.nextLink` pages, `@odata.deltaLink` on the last one);
 *  - Dropbox `files/list_folder/continue` (cursor + `has_more`);
 *  - Box `/events` (`next_stream_position`).
 * Notifications (a request the server holds open until something changes):
 *  - Dropbox `files/list_folder/longpoll` → `{changes, backoff}`;
 *  - Box `OPTIONS /events` → a realtime server URL, which answers `new_change` or `reconnect`.
 */
object ChangeFeeds {
    /**
     * One page of a delta feed. [changed]: the page reports at least one real change. [next]: token / URL of the next
     * page (null on the last page). [steady]: the cursor to keep once the feed is drained (only on the last page).
     */
    data class DeltaPage(val changed: Boolean, val next: String?, val steady: String?)

    private fun obj(json: String): JSONObject = JSONObject(json.ifBlank { "{}" })

    /** Drive `changes.list`: any entry counts. `newStartPageToken` ends the feed. */
    fun drive(json: String): DeltaPage {
        val o = obj(json)
        val changed = (o.optJSONArray("changes")?.length() ?: 0) > 0
        val fresh = o.optString("newStartPageToken").ifEmpty { null }
        return DeltaPage(changed, if (fresh != null) null else o.optString("nextPageToken").ifEmpty { null }, fresh)
    }

    /** Drive `changes/startPageToken`. */
    fun driveStartToken(json: String): String? = obj(json).optString("startPageToken").ifEmpty { null }

    /** Graph delta: the drive root itself is always reported; any other item is a change. */
    fun graph(json: String): DeltaPage {
        val o = obj(json)
        val arr = o.optJSONArray("value")
        var changed = false
        if (arr != null) for (i in 0 until arr.length()) if (!arr.getJSONObject(i).has("root")) { changed = true; break }
        val delta = o.optString("@odata.deltaLink").ifEmpty { null }
        return DeltaPage(changed, if (delta != null) null else o.optString("@odata.nextLink").ifEmpty { null }, delta)
    }

    /** Dropbox `list_folder/continue` (and `get_latest_cursor`): entries = changes; `has_more` = another page. */
    fun dropbox(json: String, previous: String): DeltaPage {
        val o = obj(json)
        val changed = (o.optJSONArray("entries")?.length() ?: 0) > 0
        val cursor = o.optString("cursor").ifEmpty { previous }
        return if (o.optBoolean("has_more")) DeltaPage(changed, cursor, null) else DeltaPage(changed, null, cursor)
    }

    /** Box `/events`: one page per call; [DeltaPage.next] is set while the page was full ([limit] events). */
    fun box(json: String, previous: String, limit: Int = 100): DeltaPage {
        val o = obj(json)
        val n = o.optInt("chunk_size")
        val pos = o.opt("next_stream_position")?.toString()?.ifEmpty { null } ?: previous
        return if (n >= limit) DeltaPage(n > 0, pos, null) else DeltaPage(n > 0, null, pos)
    }

    /** Box `/events?stream_position=now`. */
    fun boxNow(json: String): String? = obj(json).opt("next_stream_position")?.toString()?.ifEmpty { null }

    /** Answer of Dropbox `list_folder/longpoll`. [backoffSec] = how long to wait before the next long-poll (0 = none). */
    data class LongPoll(val changes: Boolean, val backoffSec: Int)

    fun dropboxLongPoll(json: String): LongPoll {
        val o = obj(json)
        return LongPoll(o.optBoolean("changes"), o.optInt("backoff", 0).coerceAtLeast(0))
    }

    /** Box realtime server from `OPTIONS /events`. [retryTimeoutSec] = how long the server may hold the request. */
    data class BoxRealtime(val url: String, val retryTimeoutSec: Int, val maxRetries: Int)

    fun boxRealtime(json: String): BoxRealtime? {
        val arr = obj(json).optJSONArray("entries") ?: return null
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("type") != "realtime_server") continue
            val url = e.optString("url").ifEmpty { return null }
            return BoxRealtime(url, e.optString("retry_timeout").toIntOrNull()?.coerceIn(30, 900) ?: 610,
                e.optString("max_retries").toIntOrNull()?.coerceIn(1, 50) ?: 10)
        }
        return null
    }

    enum class BoxMessage { NEW_CHANGE, RECONNECT, OTHER }

    fun boxMessage(json: String): BoxMessage = when (runCatching { obj(json).optString("message") }.getOrDefault("")) {
        "new_change" -> BoxMessage.NEW_CHANGE
        "reconnect" -> BoxMessage.RECONNECT
        else -> BoxMessage.OTHER
    }

    /** Realtime URL with the stream position Box wants appended. */
    fun boxSubscribeUrl(base: String, streamPosition: String): String =
        base + (if (base.contains('?')) "&" else "?") + "stream_position=" + streamPosition
}
