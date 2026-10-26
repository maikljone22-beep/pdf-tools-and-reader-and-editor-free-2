package com.smartdocs.editor.core.office5

/*
 * A small, tolerant, round-trip friendly XML DOM for OOXML parts (no org.xmlpull, so it runs in plain JVM
 * unit tests). Text and attribute values are kept in their escaped source form, comments / processing
 * instructions / CDATA are kept verbatim, and the prefix of every element name is kept as written, so
 * `XDom.parse(s).toXml() == s` for typical OOXML (single spaces between attributes).
 */

sealed class XNode {
    abstract fun write(sb: StringBuilder)
    abstract fun copy(): XNode
}

/** Character data in escaped form ("a &amp; b"). */
class XText(var raw: String) : XNode() {
    val text: String get() = XDom.unescape(raw)
    override fun write(sb: StringBuilder) { sb.append(raw) }
    override fun copy() = XText(raw)
}

/** Comment, processing instruction, CDATA section or DOCTYPE kept verbatim. */
class XRaw(val raw: String) : XNode() {
    override fun write(sb: StringBuilder) { sb.append(raw) }
    override fun copy() = XRaw(raw)
}

class XAttr(var name: String, var raw: String, val quote: Char = '"') {
    val value: String get() = XDom.unescape(raw)
    val local: String get() = name.substringAfter(':')
}

class XElem(var name: String) : XNode() {
    val attrs: MutableList<XAttr> = ArrayList(2)
    val kids: MutableList<XNode> = ArrayList(2)
    /** Written as `<x/>` when it has no children. */
    var selfClose: Boolean = true

    val local: String get() = name.substringAfter(':')
    val prefix: String get() = if (name.contains(':')) name.substringBefore(':') else ""

    // ---------------- attributes ----------------

    private fun findAttr(key: String): XAttr? {
        attrs.firstOrNull { it.name == key }?.let { return it }
        if (key.contains(':')) return null
        return attrs.firstOrNull { !it.name.startsWith("xmlns") && it.local == key }
    }

    /** Value of attribute [key]: exact qualified name first, else by local name (any prefix, never xmlns). */
    fun attr(key: String): String? = findAttr(key)?.value

    fun hasAttr(key: String) = findAttr(key) != null

    /** Sets attribute [qname] (matched like [attr]); appends it when missing. */
    fun set(qname: String, value: String): XElem {
        val a = findAttr(qname) ?: findAttr(qname.substringAfter(':'))?.takeIf { qname.contains(':') && it.name.contains(':') }
        if (a != null) a.raw = XDom.escapeAttr(value) else attrs.add(XAttr(qname, XDom.escapeAttr(value)))
        return this
    }

    fun removeAttr(key: String) { findAttr(key)?.let { attrs.remove(it) } }

    // ---------------- children ----------------

    fun elems(): List<XElem> = kids.filterIsInstance<XElem>()
    fun child(localName: String): XElem? = kids.firstOrNull { it is XElem && it.local == localName } as XElem?
    fun children(localName: String): List<XElem> = kids.filter { it is XElem && it.local == localName }.map { it as XElem }

    /** First descendant (depth first, document order) with [localName]. */
    fun find(localName: String): XElem? {
        for (k in kids) if (k is XElem) { if (k.local == localName) return k; k.find(localName)?.let { return it } }
        return null
    }

    fun findAll(localName: String, out: MutableList<XElem> = ArrayList()): MutableList<XElem> {
        for (k in kids) if (k is XElem) { if (k.local == localName) out.add(k); k.findAll(localName, out) }
        return out
    }

    fun add(n: XNode): XElem { kids.add(n); selfClose = false; return this }
    fun add(index: Int, n: XNode): XElem { kids.add(index.coerceIn(0, kids.size), n); selfClose = false; return this }
    fun remove(n: XNode): Boolean { val r = kids.remove(n); if (kids.isEmpty()) selfClose = true; return r }
    fun removeChildren(localName: String) { kids.removeAll { it is XElem && it.local == localName }; if (kids.isEmpty()) selfClose = true }
    fun indexOf(n: XNode): Int = kids.indexOf(n)

    /** Gets or creates the child [qname] at its schema position (see [setOrdered]). */
    fun getOrAdd(qname: String, order: List<String>): XElem {
        child(qname.substringAfter(':'))?.let { return it }
        return XElem(qname).also { setOrdered(it, order) }
    }

    /**
     * Inserts [e] replacing an existing child with the same local name, or before the first child that
     * comes later in the schema [order]. Children not listed in [order] keep their places.
     */
    fun setOrdered(e: XElem, order: List<String>) {
        val existing = kids.indexOfFirst { it is XElem && it.local == e.local }
        if (existing >= 0) { kids[existing] = e; return }
        val rank = order.indexOf(e.local)
        var at = kids.size
        if (rank >= 0) for ((i, k) in kids.withIndex()) {
            if (k is XElem) { val r = order.indexOf(k.local); if (r > rank) { at = i; break } }
        }
        add(at, e)
    }

    /** Concatenated unescaped text of all descendants. */
    fun textContent(): String = buildString { collectText(this@XElem, this) }

    private fun collectText(e: XElem, sb: StringBuilder) {
        for (k in e.kids) when (k) { is XText -> sb.append(k.text); is XElem -> collectText(k, sb); is XRaw -> if (k.raw.startsWith("<![CDATA[")) sb.append(k.raw.removePrefix("<![CDATA[").removeSuffix("]]>")) }
    }

    /** Replaces all children by one text node. */
    fun setText(s: String): XElem { kids.clear(); if (s.isNotEmpty()) add(XText(XDom.escapeText(s))) else selfClose = true; return this }

    override fun copy(): XElem {
        val e = XElem(name)
        attrs.forEach { e.attrs.add(XAttr(it.name, it.raw, it.quote)) }
        kids.forEach { e.kids.add(it.copy()) }
        e.selfClose = selfClose
        return e
    }

    override fun write(sb: StringBuilder) {
        sb.append('<').append(name)
        for (a in attrs) sb.append(' ').append(a.name).append('=').append(a.quote).append(a.raw).append(a.quote)
        if (kids.isEmpty() && selfClose) { sb.append("/>"); return }
        sb.append('>')
        for (k in kids) k.write(sb)
        sb.append("</").append(name).append('>')
    }

    fun toXml(): String = StringBuilder().also { write(it) }.toString()

    override fun toString(): String = toXml()
}

/** A parsed part: whatever precedes the root (XML declaration, comments) is kept. */
class XDoc(val prolog: MutableList<XNode>, var root: XElem, val epilog: MutableList<XNode> = ArrayList()) {
    fun toXml(): String {
        val sb = StringBuilder(4096)
        prolog.forEach { it.write(sb) }; root.write(sb); epilog.forEach { it.write(sb) }
        return sb.toString()
    }
    fun toBytes(): ByteArray = toXml().toByteArray(Charsets.UTF_8)

    /** Prefix bound to namespace [uri] on the root, or null. */
    fun prefixOf(uri: String): String? = root.attrs.firstOrNull { it.name.startsWith("xmlns:") && it.value == uri }?.name?.substringAfter(':')

    /** Declares xmlns:[prefix]=[uri] on the root when not declared yet; returns the bound prefix. */
    fun ensureNs(prefix: String, uri: String): String {
        prefixOf(uri)?.let { return it }
        root.attrs.add(XAttr("xmlns:$prefix", XDom.escapeAttr(uri)))
        return prefix
    }

    fun copy(): XDoc = XDoc(prolog.map { it.copy() }.toMutableList(), root.copy(), epilog.map { it.copy() }.toMutableList())
}

class XmlParseException(msg: String) : Exception(msg)

object XDom {
    fun parse(bytes: ByteArray): XDoc = parse(decode(bytes))

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val s = String(bytes, Charsets.UTF_8)
        return if (s.isNotEmpty() && s[0].code == 0xFEFF) s.substring(1) else s
    }

    fun parse(src: String): XDoc {
        val nodes = Parser(src).parseNodes(null)
        val rootIdx = nodes.indexOfFirst { it is XElem }
        if (rootIdx < 0) throw XmlParseException("no root element")
        return XDoc(nodes.subList(0, rootIdx).toMutableList(), nodes[rootIdx] as XElem, nodes.subList(rootIdx + 1, nodes.size).toMutableList())
    }

    /** Parses a fragment with one or more elements (prefixes need not be declared). */
    fun fragment(src: String): List<XNode> = Parser(src).parseNodes(null)

    fun elem(src: String): XElem = fragment(src).first { it is XElem } as XElem

    private class Parser(val s: String) {
        var i = 0

        fun parseNodes(closing: String?): MutableList<XNode> {
            val out = ArrayList<XNode>()
            while (i < s.length) {
                val lt = s.indexOf('<', i)
                if (lt < 0) { out.add(XText(s.substring(i))); i = s.length; break }
                if (lt > i) out.add(XText(s.substring(i, lt)))
                i = lt
                when {
                    s.startsWith("</", i) -> {
                        val gt = s.indexOf('>', i)
                        if (gt < 0) throw XmlParseException("unclosed end tag at $i")
                        val name = s.substring(i + 2, gt).trim()
                        i = gt + 1
                        if (closing == null) throw XmlParseException("unexpected </$name>")
                        if (name != closing) throw XmlParseException("expected </$closing> got </$name>")
                        return out
                    }
                    s.startsWith("<!--", i) -> out.add(raw("-->"))
                    s.startsWith("<![CDATA[", i) -> out.add(raw("]]>"))
                    s.startsWith("<?", i) -> out.add(raw("?>"))
                    s.startsWith("<!", i) -> out.add(raw(">"))
                    else -> out.add(element())
                }
            }
            if (closing != null) throw XmlParseException("missing </$closing>")
            return out
        }

        private fun raw(end: String): XRaw {
            val e = s.indexOf(end, i + 2)
            val stop = if (e < 0) s.length else e + end.length
            val r = XRaw(s.substring(i, stop)); i = stop; return r
        }

        private fun element(): XElem {
            i++ // '<'
            val ns = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
            val e = XElem(s.substring(ns, i))
            while (true) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) throw XmlParseException("unclosed tag <${e.name}>")
                val c = s[i]
                if (c == '/') { i = s.indexOf('>', i) + 1; if (i == 0) throw XmlParseException("bad tag"); e.selfClose = true; return e }
                if (c == '>') { i++; break }
                val an = i
                while (i < s.length && s[i] != '=' && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/') i++
                val aname = s.substring(an, i)
                while (i < s.length && s[i].isWhitespace()) i++
                if (i < s.length && s[i] == '=') {
                    i++
                    while (i < s.length && s[i].isWhitespace()) i++
                    val q = s[i]
                    if (q == '"' || q == '\'') {
                        val end = s.indexOf(q, i + 1)
                        if (end < 0) throw XmlParseException("unclosed attribute")
                        e.attrs.add(XAttr(aname, s.substring(i + 1, end), q)); i = end + 1
                    } else {
                        val vs = i
                        while (i < s.length && !s[i].isWhitespace() && s[i] != '>') i++
                        e.attrs.add(XAttr(aname, escapeAttr(s.substring(vs, i))))
                    }
                } else if (aname.isNotEmpty()) e.attrs.add(XAttr(aname, aname))
            }
            val kids = parseNodes(e.name)
            e.kids.addAll(kids)
            e.selfClose = false
            return e
        }
    }

    // ---------------- escaping ----------------

    fun escapeText(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            else -> if (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') {} else append(c)
        }
    }

    fun escapeAttr(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            '\n' -> append("&#10;"); '\r' -> append("&#13;"); '\t' -> append("&#9;")
            else -> if (c.code < 0x20) {} else append(c)
        }
    }

    private val entityRx = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")

    fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s
        return entityRx.replace(s) { m ->
            when (val g = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> runCatching { String(Character.toChars(if (g.startsWith("#x")) g.substring(2).toInt(16) else g.substring(1).toInt())) }.getOrDefault("")
            }
        }
    }
}
