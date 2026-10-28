package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.formsx.FxEditor
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton

/**
 * 27.10 `field.buttonSetIcon(icon)`: where an Icon object of the runtime comes from. `buttonGetIcon()` of another
 * button gives its /MK /I icon (or its normal appearance), `this.getIcon(name)` a named icon of the document's
 * /Names /AP tree. [firstImage] finds the picture inside an icon, so the reader can show it like an imported image.
 */
object ScriptIcons {
    private val AP_TREE = COSName.getPDFName("AP")
    private const val MAX_DEPTH = 32

    /** The icon stream [src] points at in [doc]; null when the button / name has none. */
    fun find(doc: PDDocument, src: ScriptDocChanges.IconSrc): COSStream? = when {
        src.field.isNotBlank() -> fieldIcon(doc, src.field)
        src.named.isNotBlank() -> named(doc, src.named)
        else -> null
    }

    /** /MK /I of the first widget of push button [name], else its normal appearance stream. */
    fun fieldIcon(doc: PDDocument, name: String): COSStream? {
        val f = doc.documentCatalog.getAcroForm(null)?.getField(name) as? PDPushButton ?: return null
        val w = f.widgets.firstOrNull()?.cosObject ?: return null
        FxEditor.iconOf(w)?.let { return it }
        return (w.getDictionaryObject(COSName.AP) as? COSDictionary)?.getDictionaryObject(COSName.N) as? COSStream
    }

    /** A named icon of the catalog's /Names /AP name tree. */
    fun named(doc: PDDocument, name: String): COSStream? {
        val names = doc.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary ?: return null
        return lookup(names.getDictionaryObject(AP_TREE) as? COSDictionary, name, 0)
    }

    /** Name-tree lookup (leaf /Names pairs, then /Kids), cut at [MAX_DEPTH] against cycles. */
    internal fun lookup(node: COSDictionary?, name: String, depth: Int): COSStream? {
        if (node == null || depth > MAX_DEPTH) return null
        (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { arr ->
            var i = 0
            while (i + 1 < arr.size()) {
                if ((arr.getObject(i) as? COSString)?.string == name) return arr.getObject(i + 1) as? COSStream
                i += 2
            }
        }
        val kids = node.getDictionaryObject(COSName.KIDS) as? COSArray ?: return null
        for (k in 0 until kids.size()) lookup(kids.getObject(k) as? COSDictionary, name, depth + 1)?.let { return it }
        return null
    }

    /** The first image XObject drawn by [icon] (searching nested form XObjects a few levels deep), or null. */
    fun firstImage(icon: COSStream, depth: Int = 0): COSStream? {
        if (depth > 4) return null
        val res = icon.getDictionaryObject(COSName.RESOURCES) as? COSDictionary ?: return null
        val xo = res.getDictionaryObject(COSName.XOBJECT) as? COSDictionary ?: return null
        val forms = ArrayList<COSStream>()
        for (k in xo.keySet()) {
            val s = xo.getDictionaryObject(k) as? COSStream ?: continue
            when (s.getCOSName(COSName.SUBTYPE)) {
                COSName.IMAGE -> return s
                COSName.FORM -> forms += s
            }
        }
        for (f in forms) if (f !== icon) firstImage(f, depth + 1)?.let { return it }
        return null
    }
}
