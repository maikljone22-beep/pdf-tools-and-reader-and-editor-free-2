package com.smartdocs.editor.core.edit5

import android.content.Context
import com.smartdocs.editor.core.edit.Affine
import com.smartdocs.editor.core.edit.PageFrame
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionRemoteGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What a click on a watermark does (Nitro "Action on click"). */
enum class ClickAction { NONE, PAGE, URL, FILE }

/** Shared helpers for the Nitro additions to watermark, header/footer and Bates. */
object DecorExtras {
    private val K_OWNER = COSName.getPDFName(Marked.KEY)

    /** The page frame, or with [ignoreRotation] the unrotated crop box (Nitro "ignore page rotation"). */
    fun frameOf(page: PDPage, ignoreRotation: Boolean): PageFrame {
        if (!ignoreRotation) return PageFrame.of(page)
        val cb = page.cropBox
        return PageFrame(cb.width, cb.height, 0, Affine.translate(-cb.lowerLeftX, -cb.lowerLeftY), Affine.translate(cb.lowerLeftX, cb.lowerLeftY))
    }

    /** Pages chosen by a range, a subset and "from page X every N pages". */
    fun targets(doc: PDDocument, range: String, subset: PageSubset, everyStart: Int, everyN: Int): List<Int> =
        PageSet.every(PageSet.filter(PageSet.parse(range, doc.numberOfPages), subset) { E5Io.isLandscape(doc.getPage(it)) }, everyStart, everyN)

    /**
     * Greedy word wrap of [text] to [maxWidth] (measured by [width]); single words wider than the limit stay whole.
     * Explicit line breaks are kept.
     */
    fun wrap(text: String, maxWidth: Float, width: (String) -> Float): List<String> {
        if (maxWidth <= 0f) return text.split('\n')
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = ""
            for (word in para.split(' ').filter { it.isNotEmpty() }) {
                val probe = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && width(probe) > maxWidth) { out += line; line = word } else line = probe
            }
            out += line
        }
        return out
    }

    /**
     * Imports page [pageIndex] of the PDF [file] into [doc] as a form XObject (watermark / header image "PDF page"
     * source). The objects are copied into [doc], so the source can be closed afterwards.
     */
    fun importPage(doc: PDDocument, file: File, pageIndex: Int, password: String? = null): PDFormXObject =
        E5Io.load(file, password).use { src -> LayerUtility(doc).importPageAsForm(src, pageIndex.coerceIn(0, src.numberOfPages - 1)) }

    /** Draws [form] scaled into a [w]×[h] box whose lower-left corner is at ([x], [y]). */
    fun drawForm(cs: PDPageContentStream, form: PDFormXObject, x: Float, y: Float, w: Float, h: Float) {
        val bb = form.bBox
        if (bb.width <= 0f || bb.height <= 0f) return
        cs.saveGraphicsState()
        cs.transform(Affine.mul(Affine.translate(-bb.lowerLeftX, -bb.lowerLeftY), Affine.mul(Affine.scale(w / bb.width, h / bb.height), Affine.translate(x, y))))
        cs.drawForm(form)
        cs.restoreGraphicsState()
    }

    /**
     * Enlarges the page so a header (visible [top] points) or footer (visible [bottom] points) fits outside the
     * content (Nitro Bates "increase page size"). The edge of the user-space box that shows as the visible top or
     * bottom moves outwards in both the media and crop box.
     */
    fun grow(page: PDPage, top: Float, bottom: Float) {
        if (top <= 0f && bottom <= 0f) return
        val f = PageFrame.of(page)
        val cb = page.cropBox
        var llx = cb.lowerLeftX; var lly = cb.lowerLeftY; var urx = cb.upperRightX; var ury = cb.upperRightY
        for ((amount, vy) in listOf(top to f.height, bottom to 0f)) {
            if (amount <= 0f) continue
            val (ux, uy) = Affine.apply(f.toUser, f.width / 2f, vy)
            val d = listOf(kotlin.math.abs(ux - llx), kotlin.math.abs(ux - urx), kotlin.math.abs(uy - lly), kotlin.math.abs(uy - ury))
            when (d.indices.minByOrNull { d[it] }) {
                0 -> llx -= amount; 1 -> urx += amount; 2 -> lly -= amount; else -> ury += amount
            }
        }
        val box = PDRectangle(llx, lly, urx - llx, ury - lly)
        val mb = page.mediaBox
        page.mediaBox = PDRectangle(minOf(mb.lowerLeftX, llx), minOf(mb.lowerLeftY, lly),
            maxOf(mb.upperRightX, urx) - minOf(mb.lowerLeftX, llx), maxOf(mb.upperRightY, ury) - minOf(mb.lowerLeftY, lly))
        page.cropBox = box
    }

    /**
     * Adds a link annotation over a placed watermark: a [bw]×[bh] box centred on display point ([cx], [cy]) and
     * turned by [rotation]°. Tagged with /SDE5 [kind] so [removeLinks] finds it again.
     */
    fun addLink(doc: PDDocument, page: PDPage, frame: PageFrame, kind: String, cx: Float, cy: Float, bw: Float, bh: Float, rotation: Float,
                action: ClickAction, target: String, targetPage: Int) {
        if (action == ClickAction.NONE) return
        val m = Affine.mul(Affine.rotateAround(rotation, 0f, 0f), Affine.mul(Affine.translate(cx, cy), frame.toUser))
        val pts = listOf(-bw / 2f to -bh / 2f, bw / 2f to -bh / 2f, bw / 2f to bh / 2f, -bw / 2f to bh / 2f).map { (x, y) -> Affine.apply(m, x, y) }
        val link = PDAnnotationLink()
        link.rectangle = PDRectangle(pts.minOf { it.first }, pts.minOf { it.second }, pts.maxOf { it.first } - pts.minOf { it.first }, pts.maxOf { it.second } - pts.minOf { it.second })
        // the turned box itself (lower-left, lower-right, upper-left, upper-right is the order viewers expect)
        link.cosObject.setItem(COSName.getPDFName("QuadPoints"), COSArray().apply {
            listOf(pts[3], pts[2], pts[0], pts[1]).forEach { (x, y) -> add(com.tom_roush.pdfbox.cos.COSFloat(x)); add(com.tom_roush.pdfbox.cos.COSFloat(y)) }
        })
        link.borderStyle = PDBorderStyleDictionary().apply { width = 0f }
        link.action = when (action) {
            ClickAction.PAGE -> PDActionGoTo().apply {
                destination = PDPageFitDestination().apply { setPage(doc.getPage((targetPage - 1).coerceIn(0, doc.numberOfPages - 1))) }
            }
            ClickAction.URL -> PDActionURI().apply { uri = target.trim().let { if ("://" in it || it.startsWith("mailto:")) it else "https://$it" } }
            ClickAction.FILE -> PDActionRemoteGoTo().apply {
                file = com.tom_roush.pdfbox.pdmodel.common.filespecification.PDSimpleFileSpecification().also { it.file = target.trim() }
                cosObject.setItem(COSName.D, COSArray().apply { add(com.tom_roush.pdfbox.cos.COSInteger.get((targetPage - 1).coerceAtLeast(0).toLong())); add(COSName.getPDFName("Fit")) })
            }
            ClickAction.NONE -> null
        }
        link.cosObject.setItem(K_OWNER, COSString(kind))
        page.annotations = page.annotations.also { it.add(link) }
    }

    /** Removes the links [addLink] made for [kind]. Returns how many. */
    fun removeLinks(doc: PDDocument, kind: String): Int {
        var n = 0
        doc.pages.forEach { p ->
            val list = runCatching { p.annotations }.getOrNull() ?: return@forEach
            val keep = list.filterNot { (it.cosObject.getDictionaryObject(K_OWNER) as? COSString)?.string == kind }
            if (keep.size != list.size) { n += list.size - keep.size; p.annotations = keep }
        }
        return n
    }
}

/** A named, saved set of settings (watermark or header/footer profile). [json] is the spec's own JSON. */
data class E5Profile(val name: String, val json: String)

/**
 * Pure edits of a profile list (Nitro watermark / header & footer profiles: new, rename, copy, delete,
 * import / export) and its file format.
 */
object E5ProfileList {
    const val FORMAT = "smartdocs-e5-profiles"

    private fun unique(list: List<E5Profile>, name: String): String {
        val base = name.trim().ifEmpty { "Profile" }
        var n = base; var i = 2
        while (list.any { it.name == n }) n = "$base ($i)".also { i++ }
        return n
    }

    /** Saves [json] as [name]; an existing profile of that name is replaced (the "save over" case). */
    fun put(list: List<E5Profile>, name: String, json: String): List<E5Profile> {
        val n = name.trim().ifEmpty { return list }
        return if (list.any { it.name == n }) list.map { if (it.name == n) it.copy(json = json) else it } else list + E5Profile(n, json)
    }

    fun rename(list: List<E5Profile>, old: String, new: String): List<E5Profile> {
        val t = new.trim()
        if (t.isEmpty() || list.any { it.name == t }) return list
        return list.map { if (it.name == old) it.copy(name = t) else it }
    }

    /** "Copy / paste": a duplicate named "<name> (2)". */
    fun duplicate(list: List<E5Profile>, name: String): List<E5Profile> {
        val p = list.firstOrNull { it.name == name } ?: return list
        return list + p.copy(name = unique(list, p.name))
    }

    fun delete(list: List<E5Profile>, name: String) = list.filterNot { it.name == name }

    fun export(kind: String, list: List<E5Profile>): String = JSONObject().put("format", FORMAT).put("kind", kind)
        .put("profiles", JSONArray().apply { list.forEach { put(JSONObject().put("name", it.name).put("spec", JSONObject(it.json))) } }).toString(2)

    /** Profiles of [kind] read from an export; names already in [list] get a numbered copy. Throws on another kind. */
    fun import(kind: String, list: List<E5Profile>, text: String): List<E5Profile> {
        val o = JSONObject(text)
        require(o.optString("format") == FORMAT && o.optString("kind") == kind) { "not a $kind profile file" }
        val a = o.getJSONArray("profiles")
        var out = list
        for (i in 0 until a.length()) {
            val p = a.getJSONObject(i)
            out = out + E5Profile(unique(out, p.getString("name")), p.getJSONObject("spec").toString())
        }
        return out
    }
}

/** Stored profiles per kind ("wm", "bg", "sec", "hf"). */
object E5Profiles {
    private const val FILE = "e5_profiles"

    fun list(c: Context, kind: String): List<E5Profile> = runCatching {
        val a = JSONArray(c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(kind, null) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> E5Profile(o.getString("name"), o.getString("json")) } }
    }.getOrDefault(emptyList())

    fun save(c: Context, kind: String, list: List<E5Profile>) {
        val a = JSONArray().apply { list.forEach { put(JSONObject().put("name", it.name).put("json", it.json)) } }
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(kind, a.toString()).apply()
    }
}
