package com.smartdocs.editor.core.tools5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification

/** Initial view of a portfolio (/Collection /View): details, tiles (layout), hidden (cover sheet). */
enum class PfView(val code: String) { DETAILS("D"), TILES("T"), COVER("H") }

/** One column of the portfolio schema. [type]: "S" text, "D" date, "N" number, or a file property F / Desc / ModDate / Size / CreationDate. */
data class PfField(val key: String, val name: String, val type: String, val order: Int = 0, val visible: Boolean = true)

/** One component file. [folder] = "" for the root, "A/B" for nested folders. */
data class PfItem(val key: String, val name: String, val description: String, val size: Long, val modified: Long?, val mime: String,
                  val folder: String, val values: Map<String, String>)

data class PfInfo(val isPortfolio: Boolean, val view: PfView, val initial: String?, val sortKey: String?, val ascending: Boolean,
                  val fields: List<PfField>, val folders: List<String>, val items: List<PfItem>)

/**
 * Foxit 7 portfolio editing on the COS level (PdfBox 2.0 has no collection API): view, initial file, sort, schema
 * fields, folders (/Folders with "<id>" name prefixes, PDF 1.7 ext. 3), add / remove / rename / describe / move parts
 * and custom field values (/CI). Pure over [PDDocument] — JVM-tested.
 */
object PortfolioEdit {
    private val COLLECTION = COSName.getPDFName("Collection")
    private val FOLDERS = COSName.getPDFName("Folders")
    private val CHILD = COSName.getPDFName("Child")
    private val SCHEMA = COSName.getPDFName("Schema")
    private val SORT = COSName.getPDFName("Sort")
    private val CI = COSName.getPDFName("CI")
    private val VIEW = COSName.getPDFName("View")
    private val ID = COSName.getPDFName("ID")
    private val prefix = Regex("^<(\\d+)>(.*)$", RegexOption.DOT_MATCHES_ALL)

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    fun collection(doc: PDDocument, create: Boolean = false): COSDictionary? {
        val cat = doc.documentCatalog.cosObject
        (deref(cat.getDictionaryObject(COLLECTION)) as? COSDictionary)?.let { return it }
        if (!create) return null
        return COSDictionary().also { it.setItem(COSName.TYPE, COLLECTION); it.setItem(VIEW, COSName.getPDFName("D")); cat.setItem(COLLECTION, it) }
    }

    // ------------------------------------------------------------------ folders

    /** Folder id → path ("" = root). */
    fun folderPaths(doc: PDDocument): Map<Int, String> {
        val root = collection(doc)?.let { deref(it.getDictionaryObject(FOLDERS)) as? COSDictionary } ?: return mapOf(0 to "")
        val out = HashMap<Int, String>()
        fun walk(f: COSDictionary, path: String, depth: Int) {
            if (depth > 30) return
            out[f.getInt(ID, 0)] = path
            var c = deref(f.getDictionaryObject(CHILD)) as? COSDictionary; var guard = 0
            while (c != null && guard++ < 10_000) {
                val name = c.getString(COSName.NAME) ?: ""
                walk(c, if (path.isEmpty()) name else "$path/$name", depth + 1)
                c = deref(c.getDictionaryObject(COSName.NEXT)) as? COSDictionary
            }
        }
        walk(root, "", 0)
        return out
    }

    /** Folder id of [path], creating the missing folders. */
    fun ensureFolder(doc: PDDocument, path: String): Int {
        val coll = collection(doc, true)!!
        val root = (deref(coll.getDictionaryObject(FOLDERS)) as? COSDictionary) ?: COSDictionary().also {
            it.setItem(COSName.TYPE, COSName.getPDFName("Folder")); it.setInt(ID, 0); it.setString(COSName.NAME, ""); coll.setItem(FOLDERS, it)
        }
        if (path.isBlank()) return 0
        var next = (folderPaths(doc).keys.maxOrNull() ?: 0) + 1
        var cur = root
        for (part in path.split('/').map { it.trim() }.filter { it.isNotEmpty() }) {
            var c = deref(cur.getDictionaryObject(CHILD)) as? COSDictionary; var last: COSDictionary? = null; var found: COSDictionary? = null
            while (c != null) { if (c.getString(COSName.NAME) == part) { found = c; break }; last = c; c = deref(c.getDictionaryObject(COSName.NEXT)) as? COSDictionary }
            cur = found ?: COSDictionary().also { f ->
                f.setItem(COSName.TYPE, COSName.getPDFName("Folder")); f.setInt(ID, next++); f.setString(COSName.NAME, part); f.setItem(COSName.PARENT, cur)
                if (last == null) cur.setItem(CHILD, f) else last.setItem(COSName.NEXT, f)
            }
        }
        return cur.getInt(ID, 0)
    }

    /** Name-tree key of [name] in folder [id] (root files have no prefix). Pure. */
    fun keyFor(id: Int, name: String) = if (id == 0) name else "<$id>$name"

    /** (folder id, display name) of a name-tree key. Pure. */
    fun splitKey(key: String): Pair<Int, String> = prefix.find(key)?.let { it.groupValues[1].toInt() to it.groupValues[2] } ?: (0 to key)

    // ------------------------------------------------------------------ read

    fun read(doc: PDDocument): PfInfo {
        val coll = collection(doc)
        val paths = folderPaths(doc)
        val fields = coll?.let { deref(it.getDictionaryObject(SCHEMA)) as? COSDictionary }?.let { s ->
            s.keySet().filter { it != COSName.TYPE }.mapNotNull { k ->
                val f = deref(s.getDictionaryObject(k)) as? COSDictionary ?: return@mapNotNull null
                PfField(k.name, f.getString(COSName.N) ?: k.name, f.getNameAsString(COSName.SUBTYPE) ?: "S", f.getInt(COSName.O, 0), f.getBoolean(COSName.V, true))
            }.sortedBy { it.order }
        }.orEmpty()
        val sort = coll?.let { deref(it.getDictionaryObject(SORT)) as? COSDictionary }
        val sortKey = sort?.let { s -> (deref(s.getDictionaryObject(COSName.S)) as? COSName)?.name ?: ((deref(s.getDictionaryObject(COSName.S)) as? COSArray)?.getName(0)) }
        val items = Embed.all(doc).map { (k, s) ->
            val (fid, nm) = splitKey(k)
            val ef = s.embeddedFile
            val ci = deref(s.cosObject.getDictionaryObject(CI)) as? COSDictionary
            val values = ci?.keySet()?.filter { it != COSName.TYPE }?.associate { key -> key.name to valueText(ci.getDictionaryObject(key)) }.orEmpty()
            PfItem(k, s.fileUnicode?.let { splitKey(it).second } ?: nm, s.fileDescription.orEmpty(), ef?.size?.toLong() ?: 0L,
                runCatching { ef?.modDate?.timeInMillis }.getOrNull(), ef?.subtype ?: Embed.mimeOf(nm), paths[fid] ?: "", values)
        }
        val view = when ((coll?.getDictionaryObject(VIEW) as? COSName)?.name) { "T" -> PfView.TILES; "H" -> PfView.COVER; else -> PfView.DETAILS }
        return PfInfo(coll != null, view, coll?.getString(COSName.D), sortKey, sort?.getBoolean(COSName.A, true) ?: true, fields,
            paths.values.filter { it.isNotEmpty() }.sorted(), items)
    }

    private fun valueText(b: COSBase?): String = when (val v = deref(b)) {
        is COSString -> v.string
        is COSInteger -> v.longValue().toString()
        is COSFloat -> v.floatValue().toString()
        is COSDictionary -> valueText(v.getDictionaryObject(COSName.D))
        is COSName -> v.name
        else -> ""
    }

    // ------------------------------------------------------------------ write

    private fun rewrite(doc: PDDocument, map: Map<String, PDComplexFileSpecification>) {
        val names = doc.documentCatalog.names ?: PDDocumentNameDictionary(doc.documentCatalog).also { doc.documentCatalog.names = it }
        names.embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { this.names = LinkedHashMap(map) }
    }

    fun setView(doc: PDDocument, view: PfView) { collection(doc, true)!!.setItem(VIEW, COSName.getPDFName(view.code)) }

    fun setInitial(doc: PDDocument, key: String?) { val c = collection(doc, true)!!; if (key == null) c.removeItem(COSName.D) else c.setString(COSName.D, key) }

    fun setSort(doc: PDDocument, field: String?, ascending: Boolean) {
        val c = collection(doc, true)!!
        if (field == null) { c.removeItem(SORT); return }
        c.setItem(SORT, COSDictionary().also { it.setItem(COSName.TYPE, COSName.getPDFName("CollectionSort")); it.setItem(COSName.S, COSName.getPDFName(field)); it.setBoolean(COSName.A, ascending) })
    }

    /** Replaces the schema (columns: name, type, order, shown). */
    fun setFields(doc: PDDocument, fields: List<PfField>) {
        val c = collection(doc, true)!!
        if (fields.isEmpty()) { c.removeItem(SCHEMA); return }
        val s = COSDictionary().also { it.setItem(COSName.TYPE, COSName.getPDFName("CollectionSchema")) }
        fields.forEachIndexed { i, f ->
            s.setItem(COSName.getPDFName(f.key), COSDictionary().also { d ->
                d.setItem(COSName.TYPE, COSName.getPDFName("CollectionField")); d.setItem(COSName.SUBTYPE, COSName.getPDFName(f.type))
                d.setString(COSName.N, f.name); d.setInt(COSName.O, i); d.setBoolean(COSName.V, f.visible)
            })
        }
        c.setItem(SCHEMA, s)
    }

    /** Adds [files] into [folder]; returns their keys. */
    fun add(doc: PDDocument, files: List<Attachment>, folder: String = "", descriptions: Map<String, String> = emptyMap()): List<String> {
        collection(doc, true)
        val id = ensureFolder(doc, folder)
        val before = Embed.all(doc).keys
        Embed.addFiles(doc, files.map { Attachment(keyFor(id, it.name), it.mime, it.bytes) }, descriptions.mapKeys { (k, _) -> keyFor(id, k) })
        return (Embed.all(doc).keys - before).toList()
    }

    fun remove(doc: PDDocument, keys: Collection<String>): Int {
        val map = LinkedHashMap(Embed.all(doc)); var n = 0
        keys.forEach { if (map.remove(it) != null) n++ }
        rewrite(doc, map)
        collection(doc)?.let { c -> if (c.getString(COSName.D) in keys) c.removeItem(COSName.D) }
        return n
    }

    /** Renames part [key] (same folder); returns the new key. A name already taken gets "_2" … */
    fun rename(doc: PDDocument, key: String, newName: String): String {
        val map = LinkedHashMap(Embed.all(doc))
        val s = map[key] ?: return key
        val (fid, _) = splitKey(key)
        var nk = keyFor(fid, newName.ifBlank { return key }); var n = 2
        val stem = newName.substringBeforeLast('.'); val ext = newName.substringAfterLast('.', "")
        while (nk != key && map.containsKey(nk)) nk = keyFor(fid, stem + "_" + (n++) + if (ext.isNotEmpty()) ".$ext" else "")
        s.file = splitKey(nk).second; s.fileUnicode = splitKey(nk).second
        val out = LinkedHashMap<String, PDComplexFileSpecification>()
        map.forEach { (k, v) -> out[if (k == key) nk else k] = v }
        rewrite(doc, out)
        collection(doc)?.let { c -> if (c.getString(COSName.D) == key) c.setString(COSName.D, nk) }
        return nk
    }

    fun setDescription(doc: PDDocument, key: String, description: String) {
        val s = Embed.all(doc)[key] ?: return
        s.fileDescription = description.ifBlank { null }
    }

    /** Value of a custom field (type from the schema: N number, D date as text, else text). */
    fun setValue(doc: PDDocument, key: String, field: String, value: String) {
        val s = Embed.all(doc)[key] ?: return
        val ci = (deref(s.cosObject.getDictionaryObject(CI)) as? COSDictionary) ?: COSDictionary().also {
            it.setItem(COSName.TYPE, COSName.getPDFName("CollectionItem")); s.cosObject.setItem(CI, it)
        }
        val type = read(doc).fields.firstOrNull { it.key == field }?.type
        if (value.isBlank()) ci.removeItem(COSName.getPDFName(field))
        else if (type == "N" && value.toDoubleOrNull() != null) ci.setItem(COSName.getPDFName(field), if (value.contains('.')) COSFloat(value.toFloat()) else COSInteger.get(value.toLong()))
        else ci.setString(COSName.getPDFName(field), value)
    }

    /** Moves part [key] into [folder]; returns the new key. */
    fun move(doc: PDDocument, key: String, folder: String): String {
        val id = ensureFolder(doc, folder)
        val map = LinkedHashMap(Embed.all(doc)); val s = map[key] ?: return key
        var nk = keyFor(id, splitKey(key).second); var n = 2
        val nm = splitKey(key).second; val stem = nm.substringBeforeLast('.'); val ext = nm.substringAfterLast('.', "")
        while (nk != key && map.containsKey(nk)) nk = keyFor(id, stem + "_" + (n++) + if (ext.isNotEmpty()) ".$ext" else "")
        val out = LinkedHashMap<String, PDComplexFileSpecification>(); map.forEach { (k, v) -> out[if (k == key) nk else k] = v }
        rewrite(doc, out)
        collection(doc)?.let { c -> if (c.getString(COSName.D) == key) c.setString(COSName.D, nk) }
        return nk
    }

    /** Items sorted by [field] (F / Desc / Size / ModDate / custom), as a portfolio viewer lists them. Pure. */
    fun sorted(items: List<PfItem>, field: String?, ascending: Boolean): List<PfItem> {
        val c: Comparator<PfItem> = when (field) {
            null, "F", "FileName" -> Comparator { a, b -> NaturalOrder.compare(a.name, b.name) }
            "Desc" -> compareBy { it.description }
            "Size" -> compareBy { it.size }
            "ModDate" -> compareBy { it.modified ?: 0L }
            else -> Comparator { a, b ->
                val x = a.values[field].orEmpty(); val y = b.values[field].orEmpty()
                val nx = x.toDoubleOrNull(); val ny = y.toDoubleOrNull()
                if (nx != null && ny != null) nx.compareTo(ny) else NaturalOrder.compare(x, y)
            }
        }
        return items.sortedWith(if (ascending) c else c.reversed())
    }
}
