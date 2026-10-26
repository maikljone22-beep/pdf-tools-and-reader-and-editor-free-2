package com.smartdocs.editor.core.pageflip

/**
 * Byte-bounded LRU (pure, unit-tested core of the page snapshot cache). [onEvict] is called for every entry that
 * leaves the cache (trim, replace, remove, clear) — the Android wrapper decides whether the value may be recycled.
 * Not thread-safe: callers synchronise.
 */
class BoundedLru<K, V>(
    maxBytes: Long,
    private val sizeOf: (V) -> Long,
    private val onEvict: (K, V) -> Unit = { _, _ -> },
) {
    var maxBytes: Long = maxBytes
        set(v) { field = v.coerceAtLeast(0); trim() }
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    var bytes: Long = 0; private set
    val size: Int get() = map.size
    val keys: Set<K> get() = map.keys.toSet()

    operator fun get(key: K): V? = map[key]
    fun contains(key: K): Boolean = map.containsKey(key)

    /** Insert / replace; the newest entry is always kept even when it alone exceeds the budget. */
    fun put(key: K, value: V) {
        map.remove(key)?.let { old -> bytes -= sizeOf(old); if (old !== value) onEvict(key, old) }
        map[key] = value
        bytes += sizeOf(value)
        trim(keep = key)
    }

    fun remove(key: K): V? = map.remove(key)?.also { bytes -= sizeOf(it); onEvict(key, it) }

    /** Drop entries for which [predicate] is true (e.g. pages far from the current one). */
    fun removeIf(predicate: (K) -> Boolean) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (predicate(e.key)) { it.remove(); bytes -= sizeOf(e.value); onEvict(e.key, e.value) }
        }
    }

    fun clear() {
        val all = map.entries.toList()
        map.clear(); bytes = 0
        all.forEach { onEvict(it.key, it.value) }
    }

    private fun trim(keep: K? = null) {
        val it = map.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            if (e.key == keep) continue
            it.remove(); bytes -= sizeOf(e.value); onEvict(e.key, e.value)
        }
    }
}
