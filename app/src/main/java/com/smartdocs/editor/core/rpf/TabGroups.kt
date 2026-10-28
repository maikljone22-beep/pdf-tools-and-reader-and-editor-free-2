package com.smartdocs.editor.core.rpf

/**
 * reader-prefs (OLD SHL-5): a named group of document tabs. [uris] keeps the members in order; [collapsed] hides them
 * from the tab strip; [closed] means the tabs were closed and the group waits to be restored (its uris are kept).
 */
data class TabGroup(val id: String, val name: String, val uris: List<String>, val collapsed: Boolean = false, val closed: Boolean = false, val color: Int = 0)

/** Pure operations on the list of tab groups (unit-tested). A uri belongs to one group at most. */
object TabGroupOps {
    val COLORS = listOf(0x1E88E5, 0x43A047, 0xFB8C00, 0x8E24AA, 0xE53935, 0x00897B)

    fun newId(groups: List<TabGroup>): String {
        var n = groups.size + 1
        while (groups.any { it.id == "g$n" }) n++
        return "g$n"
    }

    /** A new group with [uris] (taken out of their old groups); empty groups that are not closed disappear. */
    fun create(groups: List<TabGroup>, name: String, uris: List<String>): List<TabGroup> {
        val id = newId(groups)
        val g = TabGroup(id, name.trim().ifEmpty { id }, uris.distinct(), color = COLORS[groups.size % COLORS.size])
        return prune(groups.map { it.copy(uris = it.uris - uris.toSet()) }) + g
    }

    /** Moves [uri] into group [id] (out of any other group). */
    fun assign(groups: List<TabGroup>, id: String, uri: String): List<TabGroup> =
        prune(groups.map { g -> if (g.id == id) g.copy(uris = (g.uris - uri) + uri) else g.copy(uris = g.uris - uri) })

    fun unassign(groups: List<TabGroup>, uri: String): List<TabGroup> = prune(groups.map { it.copy(uris = it.uris - uri) })

    fun rename(groups: List<TabGroup>, id: String, name: String): List<TabGroup> = groups.map { if (it.id == id && name.isNotBlank()) it.copy(name = name.trim()) else it }

    fun toggleCollapsed(groups: List<TabGroup>, id: String): List<TabGroup> = groups.map { if (it.id == id) it.copy(collapsed = !it.collapsed) else it }

    fun setClosed(groups: List<TabGroup>, id: String, closed: Boolean): List<TabGroup> = groups.map { if (it.id == id) it.copy(closed = closed, collapsed = false) else it }

    fun delete(groups: List<TabGroup>, id: String): List<TabGroup> = groups.filter { it.id != id }

    fun groupOf(groups: List<TabGroup>, uri: String): TabGroup? = groups.firstOrNull { !it.closed && uri in it.uris }

    /**
     * A tab closed outside a group action leaves its open group; a closed group keeps its members (they are restored
     * with it).
     */
    fun onTabClosed(groups: List<TabGroup>, uri: String): List<TabGroup> = prune(groups.map { if (it.closed) it else it.copy(uris = it.uris - uri) })

    /** Groups without members disappear unless they are closed (waiting to be restored). */
    fun prune(groups: List<TabGroup>): List<TabGroup> = groups.filter { it.closed || it.uris.isNotEmpty() }

    /**
     * Strip order: every open group (its members in group order, among [open]), then the tabs without a group. The
     * members of a collapsed group are left out, except [current] so the shown document always has its tab.
     */
    fun stripEntries(groups: List<TabGroup>, open: List<String>, current: String?): List<StripEntry> {
        val out = ArrayList<StripEntry>()
        val grouped = HashSet<String>()
        groups.filter { !it.closed }.forEach { g ->
            val members = g.uris.filter { it in open }
            if (members.isEmpty()) return@forEach
            out += StripEntry.Header(g, members.size)
            members.forEach { u -> grouped += u; if (!g.collapsed || u == current) out += StripEntry.Tab(u, g.id) }
        }
        open.filter { it !in grouped }.forEach { out += StripEntry.Tab(it, null) }
        return out
    }

    /** The tab [delta] steps away from [current] among the tabs shown in the strip (wrapping), or null. */
    fun neighbour(entries: List<StripEntry>, current: String?, delta: Int): String? {
        val tabs = entries.filterIsInstance<StripEntry.Tab>().map { it.uri }
        if (tabs.size < 2) return null
        val i = tabs.indexOf(current)
        if (i < 0) return tabs.first()
        return tabs[((i + delta) % tabs.size + tabs.size) % tabs.size]
    }
}

sealed class StripEntry {
    data class Header(val group: TabGroup, val count: Int) : StripEntry()
    data class Tab(val uri: String, val groupId: String?) : StripEntry()
}
