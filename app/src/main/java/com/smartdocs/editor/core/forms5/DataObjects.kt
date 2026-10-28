package com.smartdocs.editor.core.forms5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument

/**
 * 8.84 `this.exportDataObject({ cName })`: the document's embedded files ("data objects") by name — the
 * /EmbeddedFiles name-tree key first, then the file name of the spec.
 */
object DataObjects {
    class Obj(val key: String, val fileName: String, val bytes: ByteArray)

    fun list(doc: PDDocument, maxBytes: Int = 50 * 1024 * 1024): List<Obj> {
        val out = ArrayList<Obj>()
        fun walk(node: COSDictionary, depth: Int) {
            if (depth > 20) return
            (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { arr ->
                var i = 0
                while (i + 1 < arr.size()) {
                    val key = (arr.getObject(i) as? COSString)?.string.orEmpty()
                    (arr.getObject(i + 1) as? COSDictionary)?.let { spec ->
                        val file = spec.getString(COSName.UF) ?: spec.getString(COSName.F) ?: key
                        val ef = spec.getDictionaryObject(COSName.EF) as? COSDictionary
                        val st = (ef?.getDictionaryObject(COSName.UF) ?: ef?.getDictionaryObject(COSName.F)) as? COSStream
                        val bytes = st?.let { s -> runCatching { s.createInputStream().use { it.readBytes() } }.getOrNull() }?.takeIf { it.size <= maxBytes }
                        if (bytes != null) out += Obj(key, file, bytes)
                    }
                    i += 2
                }
            }
            (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { k -> for (j in 0 until k.size()) (k.getObject(j) as? COSDictionary)?.let { walk(it, depth + 1) } }
        }
        runCatching {
            val names = doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary
            (names?.getDictionaryObject(COSName.EMBEDDED_FILES) as? COSDictionary)?.let { walk(it, 0) }
        }
        return out
    }

    fun find(doc: PDDocument, name: String): Obj? {
        val all = list(doc)
        return all.firstOrNull { it.key == name } ?: all.firstOrNull { it.fileName == name } ?: all.firstOrNull { it.fileName.equals(name, true) || it.key.equals(name, true) }
    }

    /** A file name safe for the temp folder. */
    fun safeName(s: String): String = s.substringAfterLast('/').substringAfterLast('\').replace(Regex("[^\p{L}\p{N}._ -]"), "_").ifBlank { "attachment" }
}
