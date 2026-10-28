package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.pdf.AttachmentOps
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * fix/links-bookmarks: following the actions the reader cannot hand to PDFium: GoToE (open an embedded file at a
 * page / named destination) and Sound / Rendition (extract the clip so a player can open it).
 */
object NavFollow {

    /** Extract embedded file [key] (its name-tree key, else its file name) of [src] into [dir]. Null when missing. */
    suspend fun embedded(src: File, password: String?, key: String, dir: File): File? = withContext(Dispatchers.IO) {
        val atts = AttachmentOps.list(src, password)
        val att = atts.firstOrNull { it.isDocumentLevel && it.key == key } ?: atts.firstOrNull { it.name == key } ?: return@withContext null
        AttachmentOps.extract(src, att, dir, password)
    }

    /** The page [a] leads to inside the extracted file [target] (its named destination when it has one). */
    suspend fun pageIn(target: File, a: NavAction.GoToE): Int =
        if (a.named.isNotBlank()) NamedDests.pageOf(target, a.named) ?: a.page else a.page

    private fun write(t: Triple<String, String, ByteArray>?, dir: File): Pair<File, String>? {
        val (name, mime, bytes) = t ?: return null
        dir.mkdirs()
        val f = File(dir, AttachmentOps.safeName(name))
        f.writeBytes(bytes)
        return f to mime
    }

    /** Clip of action [actionIndex] of link [annotIndex] on [page], written into [dir]: (file, mime) or null. */
    suspend fun linkMedia(src: File, password: String?, page: Int, annotIndex: Int, actionIndex: Int, dir: File): Pair<File, String>? = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            if (page !in 0 until doc.numberOfPages) return@use null
            val a = doc.getPage(page).annotations.getOrNull(annotIndex)?.cosObject?.getDictionaryObject(COSName.A) as? COSDictionary ?: return@use null
            write(NavActions.flatten(a).getOrNull(actionIndex)?.let { NavMedia.extract(it) }, dir)
        }
    }

    /** Clip of action [actionIndex] of outline item [itemIndex] (pre-order), written into [dir]: (file, mime) or null. */
    suspend fun outlineMedia(src: File, password: String?, itemIndex: Int, actionIndex: Int, dir: File): Pair<File, String>? = withContext(Dispatchers.IO) {
        E5Io.load(src, password).use { doc ->
            val a = OutlineStore.itemDict(doc, itemIndex)?.getDictionaryObject(COSName.A) as? COSDictionary ?: return@use null
            write(NavActions.flatten(a).getOrNull(actionIndex)?.let { NavMedia.extract(it) }, dir)
        }
    }
}
