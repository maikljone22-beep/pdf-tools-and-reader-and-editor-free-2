package com.smartdocs.editor.core.forms5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.pdmodel.PDDocument

/**
 * SetOCGState (PDF 32000 12.6.4.12) applied to the default viewing configuration (/OCProperties /D /ON /OFF),
 * which is what the renderer honours: the reader writes the change into the file (one undoable step) and
 * redraws. With [preserveRB] false, turning on an OCG of a radio-button group (/RBGroups) turns the other
 * members of that group off.
 */
object OcgState {
    private val OCPROPERTIES = COSName.getPDFName("OCProperties")
    private val OCGS = COSName.getPDFName("OCGs")
    private val ON = COSName.getPDFName("ON")
    private val OFF = COSName.getPDFName("OFF")
    private val RBGROUPS = COSName.getPDFName("RBGroups")

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    private fun list(a: COSArray?): MutableList<COSDictionary> =
        if (a == null) ArrayList() else (0 until a.size()).mapNotNull { deref(a.getObject(it)) as? COSDictionary }.toMutableList()

    /** OCG names currently hidden in the default configuration. */
    fun hiddenNames(doc: PDDocument): Set<String> {
        val ocp = doc.documentCatalog.cosObject.getDictionaryObject(OCPROPERTIES) as? COSDictionary ?: return emptySet()
        val d = ocp.getDictionaryObject(COSName.D) as? COSDictionary ?: return emptySet()
        return list(d.getDictionaryObject(OFF) as? COSArray).mapNotNull { it.getString(COSName.NAME) }.toSet()
    }

    /** Apply [ops] ((ON|OFF|Toggle, OCG names) in order). Returns true when the configuration changed. */
    fun apply(doc: PDDocument, ops: List<Pair<String, List<String>>>, preserveRB: Boolean = true): Boolean {
        val ocp = doc.documentCatalog.cosObject.getDictionaryObject(OCPROPERTIES) as? COSDictionary ?: return false
        val all = list(ocp.getDictionaryObject(OCGS) as? COSArray)
        if (all.isEmpty()) return false
        val d = (ocp.getDictionaryObject(COSName.D) as? COSDictionary) ?: COSDictionary().also { ocp.setItem(COSName.D, it) }
        val off = list(d.getDictionaryObject(OFF) as? COSArray).toMutableSet()
        val before = off.toSet()
        val groups = (d.getDictionaryObject(RBGROUPS) as? COSArray)?.let { g -> (0 until g.size()).map { list(deref(g.getObject(it)) as? COSArray) } }.orEmpty()
        fun setOn(o: COSDictionary) {
            off.remove(o)
            if (!preserveRB) groups.filter { o in it }.forEach { g -> g.filter { it !== o }.forEach { off.add(it) } }
        }
        for ((state, names) in ops) {
            val targets = all.filter { (it.getString(COSName.NAME) ?: "") in names }
            for (o in targets) when (state) {
                "ON" -> setOn(o)
                "OFF" -> off.add(o)
                "Toggle" -> if (o in off) setOn(o) else off.add(o)
            }
        }
        if (off == before) return false
        val offArr = COSArray(); all.filter { it in off }.forEach { offArr.add(it) }
        val onArr = COSArray(); all.filter { it !in off }.forEach { onArr.add(it) }
        d.setItem(OFF, offArr); d.setItem(ON, onArr)
        return true
    }
}
