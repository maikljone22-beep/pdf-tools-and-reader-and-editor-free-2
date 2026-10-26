package com.smartdocs.editor.core.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-file transfer queue of one sync pair: what is waiting, running, done or failed, with automatic retry and
 * exponential back-off. Pure state machine (org.json only), unit-tested in SyncQueueTest.
 *
 *   enqueue → PENDING ──start──► RUNNING ──succeed──► SYNCED
 *                ▲                   │
 *                └──retry / due──── FAILED (attempts+1, nextAt = now + backoff)
 *
 * A FAILED item is retried automatically by the next sync run once [QItem.nextAt] has passed, up to [maxAttempts];
 * after that only a manual [retry] (or a new change of the file, [enqueue]) brings it back.
 */
class SyncQueue(initial: Collection<QItem> = emptyList(), val maxAttempts: Int = 6) {
    enum class State { PENDING, RUNNING, SYNCED, FAILED }
    enum class Op { UPLOAD, DOWNLOAD, CONFLICT, DELETE_LOCAL, DELETE_REMOTE }

    data class QItem(
        val path: String, val op: Op, val state: State, val attempts: Int = 0, val nextAt: Long = 0L,
        val error: String? = null, val updated: Long = 0L, val bytes: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject().put("p", path).put("op", op.name).put("st", state.name).put("n", attempts).put("next", nextAt)
            .put("err", error ?: "").put("u", updated).put("b", bytes)

        companion object {
            fun from(o: JSONObject) = QItem(
                o.getString("p"), runCatching { Op.valueOf(o.getString("op")) }.getOrDefault(Op.UPLOAD),
                runCatching { State.valueOf(o.getString("st")) }.getOrDefault(State.PENDING), o.optInt("n"), o.optLong("next"),
                o.optString("err").ifEmpty { null }, o.optLong("u"), o.optLong("b"),
            )
        }
    }

    private val items = LinkedHashMap<String, QItem>().apply { initial.forEach { put(it.path, it) } }

    val all: List<QItem> get() = items.values.toList()
    operator fun get(path: String): QItem? = items[path]

    /** A (new) change of [path] needs [op]: a fresh PENDING entry (attempts reset unless the same op is still failing). */
    fun enqueue(path: String, op: Op, now: Long, bytes: Long = 0L): QItem {
        val prev = items[path]
        val keepAttempts = prev != null && prev.state == State.FAILED && prev.op == op
        if (prev != null && prev.state == State.RUNNING) return prev
        val item = QItem(path, op, State.PENDING, if (keepAttempts) prev!!.attempts else 0, if (keepAttempts) prev!!.nextAt else 0L,
            if (keepAttempts) prev!!.error else null, now, bytes)
        items[path] = item
        return item
    }

    /** True when [path] may be processed now (not a failed item still waiting for its back-off, unless [force]). */
    fun mayRun(path: String, now: Long, force: Boolean = false): Boolean {
        val it = items[path] ?: return true
        if (it.state != State.FAILED || force) return true
        return it.attempts < maxAttempts && it.nextAt <= now
    }

    fun start(path: String, now: Long): Boolean {
        val it = items[path] ?: return false
        if (it.state != State.PENDING && it.state != State.FAILED) return false
        items[path] = it.copy(state = State.RUNNING, updated = now)
        return true
    }

    fun succeed(path: String, now: Long) {
        val it = items[path] ?: return
        items[path] = it.copy(state = State.SYNCED, attempts = 0, nextAt = 0L, error = null, updated = now)
    }

    fun fail(path: String, error: String, now: Long) {
        val it = items[path] ?: return
        val n = it.attempts + 1
        items[path] = it.copy(state = State.FAILED, attempts = n, nextAt = now + backoff(n), error = error.take(300), updated = now)
    }

    /** Manual retry: back to PENDING immediately, attempts kept (so a retried item that fails again backs off further). */
    fun retry(path: String, now: Long): Boolean {
        val it = items[path] ?: return false
        if (it.state != State.FAILED) return false
        items[path] = it.copy(state = State.PENDING, nextAt = 0L, attempts = minOf(it.attempts, maxAttempts - 1), updated = now)
        return true
    }

    fun retryAll(now: Long): Int = items.keys.toList().count { retry(it, now) }

    /** Items the next run should handle first: PENDING ones and FAILED ones whose back-off has passed. */
    fun due(now: Long): List<QItem> = items.values.filter {
        it.state == State.PENDING || (it.state == State.FAILED && it.attempts < maxAttempts && it.nextAt <= now)
    }

    fun gaveUp(item: QItem) = item.state == State.FAILED && item.attempts >= maxAttempts

    /** A run died half-way (process killed): RUNNING items become PENDING again. */
    fun recoverInterrupted(now: Long) {
        items.entries.forEach { e -> if (e.value.state == State.RUNNING) e.setValue(e.value.copy(state = State.PENDING, updated = now)) }
    }

    /** Drops SYNCED entries older than [keepMs] and entries of files that no longer exist ([exists] false). */
    fun prune(now: Long, keepMs: Long = 3L * 24 * 3600_000, exists: (String) -> Boolean = { true }) {
        items.entries.removeAll { (p, it) -> (it.state == State.SYNCED && now - it.updated > keepMs) || (it.state != State.RUNNING && !exists(p)) }
    }

    fun remove(path: String) { items.remove(path) }

    fun counts(): Map<State, Int> = State.entries.associateWith { s -> items.values.count { it.state == s } }

    fun toJson(): JSONArray = JSONArray().apply { items.values.forEach { put(it.toJson()) } }

    companion object {
        const val BASE_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 6L * 3600_000

        /** 30 s, 1 min, 2 min, 4 min … capped at 6 h. */
        fun backoff(attempts: Int): Long {
            if (attempts <= 0) return 0L
            val shift = (attempts - 1).coerceAtMost(20)
            return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
        }

        fun fromJson(arr: JSONArray?, maxAttempts: Int = 6): SyncQueue =
            SyncQueue((0 until (arr?.length() ?: 0)).mapNotNull { i -> runCatching { QItem.from(arr!!.getJSONObject(i)) }.getOrNull() }, maxAttempts)

        fun opFor(a: SyncRules.Action): Op? = when (a) {
            SyncRules.Action.UPLOAD, SyncRules.Action.UPLOAD_NEW -> Op.UPLOAD
            SyncRules.Action.DOWNLOAD, SyncRules.Action.DOWNLOAD_NEW -> Op.DOWNLOAD
            SyncRules.Action.CONFLICT -> Op.CONFLICT
            SyncRules.Action.DELETE_LOCAL -> Op.DELETE_LOCAL
            SyncRules.Action.DELETE_REMOTE -> Op.DELETE_REMOTE
            SyncRules.Action.NONE, SyncRules.Action.RECORD -> null
        }
    }
}
