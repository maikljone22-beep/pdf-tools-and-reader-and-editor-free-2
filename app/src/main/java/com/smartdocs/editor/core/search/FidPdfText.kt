package com.smartdocs.editor.core.search

import com.tom_roush.pdfbox.contentstream.PDContentStream
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField

/**
 * fix/files-index: searchable text the page text does not give — signature fields (signer name, reason, location,
 * contact, the text drawn in the signature appearance) and optional content (layer names, and the text each layer
 * draws on a page). Text is taken from the content streams with each font's ToUnicode / encoding.
 */
object FidPdfText {
    private const val MAX_PAGE_TEXT = 200_000

    /** Walks a content stream (and the forms it draws) and reports each shown string with the layer it is in. */
    class TextWalk(private val sink: (layer: String?, text: String) -> Unit) {
        private var total = 0

        fun run(stream: PDContentStream, resources: PDResources?, layer: String?, depth: Int = 0) {
            if (depth > 12 || total > MAX_PAGE_TEXT) return
            val res = stream.resources ?: resources
            val stack = ArrayList<String?>()
            fun current(): String? = stack.lastOrNull { it != null } ?: layer
            var font: PDFont? = null
            val operands = ArrayList<Any>()
            val parser = PDFStreamParser(stream)
            var guard = 0
            while (guard++ < 2_000_000) {
                val t = runCatching { parser.parseNextToken() }.getOrNull() ?: break
                if (t !is Operator) { operands += t; continue }
                when (t.name) {
                    "Tf" -> font = (operands.firstOrNull() as? COSName)?.let { n -> runCatching { res?.getFont(n) }.getOrNull() }
                    "Tj", "'" -> (operands.lastOrNull() as? COSString)?.let { emit(current(), decode(font, it.bytes)) }
                    "\"" -> (operands.getOrNull(2) as? COSString)?.let { emit(current(), decode(font, it.bytes)) }
                    "TJ" -> (operands.firstOrNull() as? COSArray)?.let { arr ->
                        val sb = StringBuilder()
                        for (i in 0 until arr.size()) when (val o = arr.getObject(i)) {
                            is COSString -> sb.append(decode(font, o.bytes))
                            is COSNumber -> if (o.floatValue() < -200f && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                        }
                        emit(current(), sb.toString())
                    }
                    "Td", "TD", "Tm", "T*", "ET" -> emit(current(), " ")
                    "BMC" -> stack += null
                    "BDC" -> stack += if ((operands.firstOrNull() as? COSName)?.name == "OC") ocName(operands.getOrNull(1), res) else null
                    "EMC" -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    "Do" -> (operands.firstOrNull() as? COSName)?.let { n ->
                        val x = runCatching { res?.getXObject(n) }.getOrNull() as? PDFormXObject
                        if (x != null) run(x, res, ocOf(x.cosObject) ?: current(), depth + 1)
                    }
                }
                operands.clear()
            }
        }

        private fun emit(layer: String?, s: String) {
            if (s.isEmpty() || total > MAX_PAGE_TEXT) return
            total += s.length
            sink(layer, s)
        }
    }

    /** Glyph codes of [bytes] through the font's ToUnicode / encoding; Latin-1 bytes without a font. */
    fun decode(font: PDFont?, bytes: ByteArray): String {
        if (font == null) return String(bytes, Charsets.ISO_8859_1)
        return runCatching {
            val ins = java.io.ByteArrayInputStream(bytes)
            val sb = StringBuilder()
            var guard = 0
            while (ins.available() > 0 && guard++ < 100_000) { val code = font.readCode(ins); sb.append(font.toUnicode(code) ?: "") }
            sb.toString()
        }.getOrDefault("")
    }

    /** Layer name of a BDC /OC property: a named property list (OCG or OCMD) or an inline dictionary. */
    private fun ocName(prop: Any?, res: PDResources?): String? {
        val dict = when (prop) {
            is COSName -> runCatching { res?.getProperties(prop)?.cosObject }.getOrNull()
            is COSDictionary -> prop
            else -> null
        } ?: return ""
        return groupName(dict) ?: ""
    }

    /** The /OC entry of a form XObject. */
    private fun ocOf(d: COSDictionary): String? = (d.getDictionaryObject(COSName.getPDFName("OC")) as? COSDictionary)?.let { groupName(it) }

    /** OCG → its /Name; OCMD → the names of its /OCGs. */
    private fun groupName(d: COSDictionary): String? {
        d.getString(COSName.NAME)?.let { return it }
        return when (val g = d.getDictionaryObject(COSName.getPDFName("OCGs"))) {
            is COSDictionary -> g.getString(COSName.NAME)
            is COSArray -> (0 until g.size()).mapNotNull { (g.getObject(it) as? COSDictionary)?.getString(COSName.NAME) }.joinToString(", ").ifBlank { null }
            else -> null
        }
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** Layer names (document level) and, per page, the text each layer draws ("Layer: text"). Empty without layers. */
    fun layers(doc: PDDocument): List<DocPart> {
        val props = doc.documentCatalog.ocProperties ?: return emptyList()
        val out = ArrayList<DocPart>()
        val names = runCatching { props.groupNames.toList() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        if (names.isNotEmpty()) out += DocPart(PartKind.LAYER, -1, names.distinct().joinToString(" — "))
        doc.pages.forEachIndexed { pi, page ->
            val byLayer = LinkedHashMap<String, StringBuilder>()
            runCatching { TextWalk { layer, t -> if (!layer.isNullOrEmpty()) byLayer.getOrPut(layer) { StringBuilder() }.append(t) }.run(page, page.resources, null) }
            byLayer.forEach { (name, sb) -> clean(sb.toString()).takeIf { it.isNotEmpty() }?.let { out += DocPart(PartKind.LAYER, pi, "$name: $it") } }
        }
        return out
    }

    /** Text drawn by an appearance / form stream (all layers). */
    fun streamText(stream: PDContentStream, resources: PDResources? = null): String {
        val sb = StringBuilder()
        runCatching { TextWalk { _, t -> sb.append(t) }.run(stream, resources, null) }
        return clean(sb.toString())
    }

    /** Signature fields: field name, signer name, reason, location, contact info and the appearance text. */
    fun signatures(doc: PDDocument, pageOf: Map<COSDictionary, Int>): List<DocPart> {
        val form = doc.documentCatalog.getAcroForm(null) ?: return emptyList()
        val out = ArrayList<DocPart>()
        form.fieldTree.forEach { f ->
            if (f !is PDSignatureField) return@forEach
            val sig = runCatching { f.signature }.getOrNull()
            val texts = ArrayList<String>()
            f.partialName?.let { texts += it }
            if (sig != null) listOf(sig.name, sig.reason, sig.location, sig.contactInfo).forEach { v -> if (!v.isNullOrBlank()) texts += v }
            var page = -1
            f.widgets.forEach { w ->
                if (page < 0) page = w.page?.cosObject?.let { pageOf[it] } ?: -1
                runCatching { w.appearance?.normalAppearance?.appearanceStream }.getOrNull()?.let { ap -> streamText(ap).takeIf { it.isNotBlank() }?.let { texts += it } }
            }
            val t = texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" — ")
            if (t.isNotBlank() && (sig != null || texts.size > 1)) out += DocPart(PartKind.SIGNATURE, page, t)
        }
        return out
    }
}
