package com.smartdocs.editor.core.office

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/*
 * Shared OOXML plumbing: the ZIP package, relationship parts, pull-parser helpers and a tiny
 * lossless tag scanner used by the writers. Pure JVM + org.xmlpull (bundled with Android).
 */

internal object Ns {
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
}

/** One relationship from a *.rels part; [target] is already resolved to a zip path unless external. */
data class Rel(val id: String, val type: String, val target: String, val external: Boolean)

/** Read access to an OOXML ZIP package. */
class OoxmlPackage(val file: File) : Closeable {
    private val zip: ZipFile = try { ZipFile(file) } catch (e: Exception) { throw OfficeFormatException("not a zip package", e) }
    private val relCache = HashMap<String, Map<String, Rel>>()

    fun has(path: String): Boolean = zip.getEntry(path) != null

    fun bytes(path: String): ByteArray? = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

    fun entryNames(): List<String> = zip.entries().asSequence().map { it.name }.toList()

    /** A namespace-aware pull parser positioned before the root of [path], or null if the part is missing. */
    fun parser(path: String): XmlPullParser? = bytes(path)?.let { newParser(it) }

    /** Relationships of the part at [partPath] keyed by id. */
    fun rels(partPath: String): Map<String, Rel> = relCache.getOrPut(partPath) {
        val dir = partPath.substringBeforeLast('/', "")
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + partPath.substringAfterLast('/') + ".rels"
        val p = parser(relsPath) ?: return@getOrPut emptyMap()
        val out = LinkedHashMap<String, Rel>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.attr("Id") ?: continue
                val ext = p.attr("TargetMode") == "External"
                val raw = p.attr("Target") ?: ""
                out[id] = Rel(id, p.attr("Type") ?: "", if (ext) raw else resolvePath(dir, raw), ext)
            }
        }
        out
    }

    fun image(partPath: String, rId: String?): ImageData? {
        if (rId.isNullOrEmpty()) return null
        val rel = rels(partPath)[rId] ?: return null
        if (rel.external) return null
        val b = bytes(rel.target) ?: return null
        return ImageData(b, imageMime(rel.target, b))
    }

    override fun close() = zip.close()

    companion object {
        fun newParser(bytes: ByteArray): XmlPullParser {
            val f = XmlPullParserFactory.newInstance()
            f.isNamespaceAware = true
            val p = f.newPullParser()
            p.setInput(ByteArrayInputStream(bytes), null)
            return p
        }

        /** Resolves a relationship target relative to the source part directory. */
        fun resolvePath(baseDir: String, target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val parts = ArrayList<String>()
            if (baseDir.isNotEmpty()) parts.addAll(baseDir.split('/'))
            for (seg in target.split('/')) when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
            return parts.joinToString("/")
        }

        fun imageMime(name: String, b: ByteArray): String {
            if (b.size > 4) {
                val b0 = b[0].toInt() and 0xFF; val b1 = b[1].toInt() and 0xFF
                if (b0 == 0x89 && b1 == 0x50) return "image/png"
                if (b0 == 0xFF && b1 == 0xD8) return "image/jpeg"
                if (b0 == 0x47 && b1 == 0x49) return "image/gif"
                if (b0 == 0x42 && b1 == 0x4D) return "image/bmp"
                if (b0 == 0x52 && b1 == 0x49 && b.size > 11 && b[8] == 'W'.code.toByte()) return "image/webp"
            }
            return when (name.substringAfterLast('.').lowercase()) {
                "svg" -> "image/svg+xml"; "emf" -> "image/x-emf"; "wmf" -> "image/x-wmf"; "tif", "tiff" -> "image/tiff"
                else -> "application/octet-stream"
            }
        }

        /**
         * Copies [src] to [out] replacing the bytes of the parts in [replace] (path -> new bytes, null = drop
         * the entry). Every other entry is streamed unchanged, preserving order.
         */
        fun rewrite(src: File, out: OutputStream, replace: Map<String, ByteArray?>) {
            ZipFile(src).use { zip ->
                ZipOutputStream(out).use { zos ->
                    val buf = ByteArray(64 * 1024)
                    for (e in zip.entries()) {
                        if (replace.containsKey(e.name)) {
                            val nb = replace[e.name] ?: continue
                            zos.putNextEntry(ZipEntry(e.name)); zos.write(nb); zos.closeEntry()
                        } else {
                            zos.putNextEntry(ZipEntry(e.name).apply { if (e.time > 0) time = e.time })
                            zip.getInputStream(e).use { ins -> while (true) { val n = ins.read(buf); if (n <= 0) break; zos.write(buf, 0, n) } }
                            zos.closeEntry()
                        }
                    }
                }
            }
        }
    }
}

// ---------------- pull-parser helpers ----------------

/** Attribute by local name, ignoring the namespace/prefix (OOXML producers vary). */
internal fun XmlPullParser.attr(local: String): String? {
    for (i in 0 until attributeCount) {
        val n = getAttributeName(i)
        if (n == local || n.endsWith(":$local")) return getAttributeValue(i)
    }
    return null
}

/** Attribute in the relationships namespace (r:id, r:embed, r:link). */
internal fun XmlPullParser.rAttr(local: String): String? =
    getAttributeValue(Ns.R, local) ?: attr(local)

/** w:val style on/off attribute: absent or "1"/"true"/"on" means on. */
internal fun XmlPullParser.onOff(): Boolean = when (attr("val")?.lowercase()) { null, "1", "true", "on" -> true; else -> false }

/**
 * Calls [action] for each direct child element of the current START_TAG. The action may consume the
 * child completely (ending on its END_TAG) or not touch it (it is skipped). Ends on this element's END_TAG.
 */
internal inline fun XmlPullParser.forEachChild(action: (String) -> Unit) {
    val d = depth
    while (true) {
        val ev = next()
        if (ev == XmlPullParser.END_DOCUMENT) return
        if (ev == XmlPullParser.END_TAG && depth == d) return
        if (ev == XmlPullParser.START_TAG && depth == d + 1) {
            action(name)
            if (eventType == XmlPullParser.START_TAG && depth == d + 1) skipElement()
        }
    }
}

/** Skips the current element including its children; ends on its END_TAG. */
internal fun XmlPullParser.skipElement() {
    if (eventType != XmlPullParser.START_TAG) return
    val d = depth
    while (true) {
        val ev = next()
        if (ev == XmlPullParser.END_DOCUMENT) return
        if (ev == XmlPullParser.END_TAG && depth == d) return
    }
}

/** All text below the current element; ends on its END_TAG. */
internal fun XmlPullParser.readText(): String {
    if (eventType != XmlPullParser.START_TAG) return ""
    val d = depth
    val sb = StringBuilder()
    while (true) {
        val ev = next()
        if (ev == XmlPullParser.END_DOCUMENT) break
        if (ev == XmlPullParser.TEXT || ev == XmlPullParser.ENTITY_REF || ev == XmlPullParser.CDSECT) sb.append(text ?: "")
        if (ev == XmlPullParser.END_TAG && depth == d) break
    }
    return sb.toString()
}

/** Advances to the first START_TAG named [local] anywhere; false at end of document. */
internal fun XmlPullParser.seek(local: String): Boolean {
    while (true) {
        val ev = next()
        if (ev == XmlPullParser.END_DOCUMENT) return false
        if (ev == XmlPullParser.START_TAG && name == local) return true
    }
}

internal fun parseColor(v: String?): String? {
    if (v == null || v.equals("auto", true)) return null
    val h = v.removePrefix("#")
    return when (h.length) { 6 -> h.uppercase(); 8 -> h.substring(2).uppercase(); else -> null }
}

// ---------------- lossless tag scanner (writers) ----------------

/** One element found by [XmlScan]: [start]..[end) covers the whole element in the source text. */
internal data class XmlElem(val qname: String, val start: Int, val end: Int, val openEnd: Int, val selfClosing: Boolean) {
    val local: String get() = qname.substringAfter(':')
    /** Index of the start of the closing tag (== end for self-closing). */
    fun innerRange(src: String): IntRange = if (selfClosing) IntRange.EMPTY else openEnd until src.lastIndexOf('<', end - 1)
}

/**
 * Minimal XML tokenizer that keeps exact character offsets so writers can splice the original text
 * without re-serialising (namespaces, mc:Ignorable and unknown markup survive untouched).
 */
internal object XmlScan {
    /** Direct child elements of the element whose content spans [from, to). */
    fun children(src: String, from: Int, to: Int): List<XmlElem> {
        val out = ArrayList<XmlElem>()
        var i = from
        var depth = 0
        var curName = ""; var curStart = 0; var curOpenEnd = 0
        while (i < to) {
            val lt = src.indexOf('<', i)
            if (lt < 0 || lt >= to) break
            when {
                src.startsWith("<!--", lt) -> { i = src.indexOf("-->", lt).let { if (it < 0) to else it + 3 }; continue }
                src.startsWith("<![CDATA[", lt) -> { i = src.indexOf("]]>", lt).let { if (it < 0) to else it + 3 }; continue }
                src.startsWith("<?", lt) || src.startsWith("<!", lt) -> { i = src.indexOf('>', lt).let { if (it < 0) to else it + 1 }; continue }
            }
            val gt = tagEnd(src, lt)
            if (src[lt + 1] == '/') {
                depth--
                if (depth == 0) out.add(XmlElem(curName, curStart, gt + 1, curOpenEnd, false))
            } else {
                val self = src[gt - 1] == '/'
                val name = tagName(src, lt + 1)
                if (depth == 0) {
                    if (self) out.add(XmlElem(name, lt, gt + 1, gt + 1, true))
                    else { curName = name; curStart = lt; curOpenEnd = gt + 1 }
                }
                if (!self) depth++
            }
            i = gt + 1
        }
        return out
    }

    fun children(src: String, e: XmlElem): List<XmlElem> {
        if (e.selfClosing) return emptyList()
        val r = e.innerRange(src)
        return children(src, r.first, r.last + 1)
    }

    /** First element (any depth) whose local name is [local], searched in [from, to). */
    fun find(src: String, local: String, from: Int = 0, to: Int = src.length): XmlElem? {
        var kids = children(src, from, to)
        val queue = ArrayDeque(kids)
        while (queue.isNotEmpty()) {
            val e = queue.removeFirst()
            if (e.local == local) return e
            kids = children(src, e)
            queue.addAll(kids)
        }
        return null
    }

    private fun tagEnd(src: String, lt: Int): Int {
        var q: Char? = null
        var i = lt + 1
        while (i < src.length) {
            val c = src[i]
            if (q != null) { if (c == q) q = null } else if (c == '"' || c == '\'') q = c else if (c == '>') return i
            i++
        }
        return src.length - 1
    }

    private fun tagName(src: String, from: Int): String {
        var i = from
        while (i < src.length && !src[i].isWhitespace() && src[i] != '>' && src[i] != '/') i++
        return src.substring(from, i)
    }

    /** Value of attribute [local] (any prefix) inside the open tag of [e]. */
    fun attr(src: String, e: XmlElem, local: String): String? {
        val tag = src.substring(e.start, e.openEnd)
        val m = Regex("""(?:^|\s)(?:[\w.-]+:)?${Regex.escape(local)}\s*=\s*("([^"]*)"|'([^']*)')""").find(tag) ?: return null
        return unescape(m.groupValues[2].ifEmpty { m.groupValues[3] })
    }

    fun escape(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            else -> if (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') {} else append(c)
        }
    }

    fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(s) { m ->
            when (val g = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> if (g.startsWith("#x")) String(Character.toChars(g.substring(2).toInt(16))) else String(Character.toChars(g.substring(1).toInt()))
            }
        }
    }

    /** Namespace prefix bound to [uri] on the root element ("w" for WordprocessingML), default [fallback]. */
    fun prefixFor(src: String, uri: String, fallback: String): String {
        val m = Regex("""xmlns:([\w.-]+)\s*=\s*["']${Regex.escape(uri)}["']""").find(src) ?: return fallback
        return m.groupValues[1]
    }
}

/** True when [s] contains Arabic/Hebrew letters (used to default new paragraphs to RTL). */
fun hasRtlChars(s: String): Boolean = s.any { it in '֐'..'ࣿ' || it in 'יִ'..'﷿' || it in 'ﹰ'..'\uFEFF' }
