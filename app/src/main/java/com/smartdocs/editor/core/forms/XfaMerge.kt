package com.smartdocs.editor.core.forms

import com.smartdocs.editor.core.forms.XfaTemplateParser.kids
import com.smartdocs.editor.core.forms.XfaTemplateParser.local
import org.w3c.dom.Element

/**
 * One instance of a template node after merging with the data (repeating subforms give several instances).
 * [dataPath] is the data binding path in the `XfaSupport` convention ("form1[0].row[2].qty[0]"), null for
 * unbound nodes. [value] is the raw data value of a field or of an exclusion group.
 */
class XInst(val node: XNode, val dataPath: String?, val som: String) {
    var value: String = ""
    var presence: String = node.presence
    var image: ByteArray? = null
    val children = ArrayList<XInst>()
    var parent: XInst? = null
    /** Set on the last instance of a repeating subform that can take another instance. */
    var repeat: XRepeatInfo? = null
    val hidden: Boolean get() = presence == "hidden" || presence == "inactive"
    val invisible: Boolean get() = presence == "invisible"
    val name: String get() = node.name
}

/** A repeating subform group: [count] instances under the data group [dataParent], [max] -1 = unlimited. */
data class XRepeatInfo(val key: String, val dataParent: String, val name: String, val count: Int, val max: Int) {
    val canAdd: Boolean get() = max < 0 || count < max
}

class XMerged(val root: XInst, val repeats: List<XRepeatInfo>)

/** Merges an [XTemplate] with the `xfa:data` element: instances, data paths, values (normal and dataRef binding). */
object XfaMerge {

    private data class Seg(val name: String, val index: Int)

    private fun path(segs: List<Seg>) = segs.joinToString(".") { "${it.name}[${it.index}]" }

    private fun Element.named(n: String): List<Element> = kids().filter { it.local() == n }

    private fun leafText(e: Element?): String? = e?.let { if (it.kids().isEmpty()) it.textContent.trim() else null }

    private fun refPath(ref: String, base: List<Seg>, root: List<Seg>): List<Seg>? {
        var r = ref.trim()
        val relative = r.startsWith("$.")
        r = r.removePrefix("\$record.").removePrefix("\$data.").removePrefix("$.").removePrefix("!")
        if (r.isBlank() || r.startsWith("$")) return null
        val segs = r.split('.').filter { it.isNotBlank() }.map { s ->
            val m = Regex("""^(.+?)\[(\d+|\*)]$""").find(s)
            if (m != null) Seg(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0) else Seg(s, 0)
        }
        return if (relative) base + segs else root.take(1) + segs
    }

    private fun locate(data: Element?, segs: List<Seg>): Element? {
        var cur = data ?: return null
        for (s in segs) cur = cur.named(s.name).getOrNull(s.index) ?: return null
        return cur
    }

    fun merge(t: XTemplate, data: Element?): XMerged {
        val repeats = ArrayList<XRepeatInfo>()
        val rootName = t.root.name.ifBlank { "form1" }
        val rootSegs = listOf(Seg(rootName, 0))
        val root = XInst(t.root, path(rootSegs), "$rootName[0]")
        val rootData = data?.named(rootName)?.firstOrNull()
        children(t.root, root, rootSegs, rootData, data, rootSegs, repeats)
        return XMerged(root, repeats)
    }

    private fun children(sub: XSub, parent: XInst, segs: List<Seg>, group: Element?, data: Element?, rootSegs: List<Seg>, repeats: MutableList<XRepeatInfo>) {
        val counters = HashMap<String, Int>()
        for (c in sub.children) {
            when (c) {
                is XDraw -> parent.add(XInst(c, null, parent.som + "." + c.name.ifBlank { "#draw" }))
                is XFieldT -> {
                    val n = c.name
                    val idx = if (n.isBlank()) 0 else counters.getOrDefault(n, 0).also { counters[n] = it + 1 }
                    val bound = bindSegs(c.bind, n, idx, segs, rootSegs)
                    val inst = XInst(c, bound?.let { path(it) }, parent.som + "." + "$n[$idx]")
                    val v = bound?.let { leafText(locate(data, it)) }
                    inst.value = v ?: c.defaultValue
                    if (c.ui == XUi.IMAGE) inst.image = XfaTemplateParser.decodeImage(v) ?: c.image
                    parent.add(inst)
                }
                is XSub -> {
                    val n = c.name
                    val transparent = n.isBlank() || c.bind.match == "none" || c.isArea
                    if (transparent && !c.isExclGroup) {
                        val inst = XInst(c, null, parent.som + "." + n.ifBlank { "#subform" })
                        parent.add(inst)
                        children(c, inst, segs, group, data, rootSegs, repeats)
                        continue
                    }
                    if (c.isExclGroup) {
                        val idx = counters.getOrDefault(n, 0).also { counters[n] = it + 1 }
                        val bound = if (n.isBlank()) null else bindSegs(c.bind, n, idx, segs, rootSegs)
                        val inst = XInst(c, bound?.let { path(it) }, parent.som + "." + "$n[$idx]")
                        inst.value = bound?.let { leafText(locate(data, it)) } ?: c.children.filterIsInstance<XFieldT>().firstOrNull { it.defaultValue.isNotBlank() }
                            ?.let { it.saveItems.firstOrNull() ?: it.items.firstOrNull() }.orEmpty()
                        parent.add(inst)
                        // the options: unbound fields whose "on" value is their first item
                        for (o in c.children) when (o) {
                            is XFieldT -> parent.lastChild().add(XInst(o, null, inst.som + "." + o.name).also { it.value = o.saveItems.firstOrNull() ?: o.items.firstOrNull() ?: o.name })
                            is XDraw -> parent.lastChild().add(XInst(o, null, inst.som + "." + o.name))
                            else -> {}
                        }
                        continue
                    }
                    val start = counters.getOrDefault(n, 0)
                    val dataEls = group?.named(n).orEmpty()
                    val count = if (!c.occur.repeats) 1 else {
                        val have = (dataEls.size - start).coerceAtLeast(0)
                        val want = if (have > 0) have else maxOf(c.occur.initial, c.occur.min)
                        (if (c.occur.max >= 0) want.coerceAtMost(c.occur.max) else want).coerceAtLeast(c.occur.min.coerceAtLeast(0))
                    }
                    counters[n] = start + count
                    var last: XInst? = null
                    for (k in 0 until count) {
                        val i = start + k
                        val mySegs = when (c.bind.match) {
                            "global" -> rootSegs + Seg(n, i)
                            "dataRef" -> c.bind.ref?.let { refPath(it, segs, rootSegs) } ?: (segs + Seg(n, i))
                            else -> segs + Seg(n, i)
                        }
                        val inst = XInst(c, path(mySegs), parent.som + "." + "$n[$i]")
                        parent.add(inst)
                        children(c, inst, mySegs, locate(data, mySegs), data, rootSegs, repeats)
                        last = inst
                    }
                    if (c.occur.repeats && last != null) {
                        val info = XRepeatInfo(parent.som + "/" + n, path(segs), n, count + start, c.occur.max)
                        repeats += info
                        if (info.canAdd) last.repeat = info
                    }
                }
            }
        }
    }

    private fun bindSegs(b: XBind, n: String, idx: Int, segs: List<Seg>, rootSegs: List<Seg>): List<Seg>? = when (b.match) {
        "none" -> null
        "global" -> rootSegs + Seg(n, 0)
        "dataRef" -> b.ref?.let { refPath(it, segs, rootSegs) } ?: (segs + Seg(n, idx))
        else -> if (n.isBlank()) null else segs + Seg(n, idx)
    }

    private fun XInst.add(c: XInst) { c.parent = this; children += c }
    private fun XInst.lastChild(): XInst = children.last()

    /** Every instance of the tree, depth first. */
    fun walk(root: XInst): Sequence<XInst> = sequence {
        val stack = ArrayDeque<XInst>(); stack.addLast(root)
        while (stack.isNotEmpty()) { val n = stack.removeLast(); yield(n); for (i in n.children.indices.reversed()) stack.addLast(n.children[i]) }
    }
}
