package com.smartdocs.editor.core.annot

import android.graphics.RectF
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.pdf.ReviewStatus
import com.smartdocs.editor.core.pdf.SignedSave
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/** A reply in a comment thread. */
data class ThreadReply(val index: Int, val author: String, val text: String, val date: Long)

/** One stored comment with everything the comments panel shows (thread, review status, group). */
data class CommentItem(
    val page: Int,
    val index: Int,                 // index in the page's /Annots array
    val subtype: String,
    val author: String,
    val contents: String,
    val rect: RectF,                // PDF space, top = larger y
    val color: Int?,
    val date: Long,                 // /M, else /CreationDate (ms, 0 = unknown)
    val nm: String?,
    val groupParent: Int?,          // index of the group's primary annotation when /RT /Group
    val replies: List<ThreadReply>,
    val status: ReviewStatus,
    val statusAuthor: String,
    val statusDate: Long,
)

/** Reads comment threads and changes group / delete state of stored annotations. All writes go src → out. */
object CommentStore {

    private val IRT = COSName.getPDFName("IRT")
    private val RT = COSName.getPDFName("RT")
    private val STATE = COSName.getPDFName("State")
    private val STATE_MODEL = COSName.getPDFName("StateModel")
    private val CREATION = COSName.getPDFName("CreationDate")
    private val POPUP = COSName.getPDFName("Popup")

    private fun open(src: File, pw: String?): PDDocument = if (pw.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, pw)

    /** "D:YYYYMMDDHHmmSS+HH'mm'" → epoch ms (0 when unparsable). */
    fun parseDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        val d = s.removePrefix("D:")
        val m = Regex("""^(\d{4})(\d{2})?(\d{2})?(\d{2})?(\d{2})?(\d{2})?([Z+\-])?(\d{2})?'?(\d{2})?""").find(d) ?: return 0L
        val g = m.groupValues
        return runCatching {
            val tz = when (g[7]) {
                "+", "-" -> TimeZone.getTimeZone("GMT${g[7]}${g[8].ifEmpty { "00" }}:${g[9].ifEmpty { "00" }}")
                else -> TimeZone.getTimeZone("UTC")
            }
            Calendar.getInstance(tz).apply {
                clear()
                set(g[1].toInt(), (g[2].ifEmpty { "01" }.toInt() - 1), g[3].ifEmpty { "01" }.toInt(),
                    g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt())
            }.timeInMillis
        }.getOrDefault(0L)
    }

    private fun colorOf(cos: COSDictionary): Int? {
        val arr = cos.getDictionaryObject(COSName.C) as? COSArray ?: return null
        val v = FloatArray(arr.size()) { (arr.getObject(it) as? COSNumber)?.floatValue() ?: 0f }
        fun b(f: Float) = (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return when (v.size) {
            1 -> b(v[0]).let { (it shl 16) or (it shl 8) or it }
            3 -> (b(v[0]) shl 16) or (b(v[1]) shl 8) or b(v[2])
            4 -> { val k = 1f - v[3]; (b((1f - v[0]) * k) shl 16) or (b((1f - v[1]) * k) shl 8) or b((1f - v[2]) * k) }
            else -> null
        }
    }

    private fun dateOf(cos: COSDictionary): Long = parseDate(cos.getString(COSName.M)).takeIf { it > 0 } ?: parseDate(cos.getString(CREATION))

    /** Every visible comment of the document with its replies, latest review status and group parent. */
    suspend fun load(src: File, pw: String? = null): List<CommentItem> = withContext(Dispatchers.IO) {
        open(src, pw).use { doc ->
            val out = ArrayList<CommentItem>()
            doc.pages.forEachIndexed { pi, page ->
                val list = runCatching { page.annotations }.getOrNull() ?: return@forEachIndexed
                val indexOf = java.util.IdentityHashMap<COSDictionary, Int>()
                list.forEachIndexed { i, an -> indexOf[an.cosObject] = i }
                val replies = HashMap<Int, MutableList<ThreadReply>>()
                val states = HashMap<Int, Triple<ReviewStatus, String, Long>>()
                list.forEachIndexed { i, an ->
                    val cos = an.cosObject
                    val parent = (cos.getDictionaryObject(IRT) as? COSDictionary)?.let { indexOf[it] } ?: return@forEachIndexed
                    if (cos.getNameAsString(RT) == "Group") return@forEachIndexed
                    val author = cos.getString(COSName.T) ?: ""
                    if (cos.containsKey(STATE)) {
                        val st = cos.getString(STATE) ?: cos.getNameAsString(STATE) ?: return@forEachIndexed
                        val model = cos.getString(STATE_MODEL) ?: cos.getNameAsString(STATE_MODEL) ?: "Review"
                        if (model != "Review") return@forEachIndexed
                        val rs = ReviewStatus.entries.firstOrNull { it.state.equals(st, true) } ?: return@forEachIndexed
                        val d = dateOf(cos)
                        val prev = states[parent]
                        if (prev == null || d >= prev.third) states[parent] = Triple(rs, author, d)
                    } else replies.getOrPut(parent) { ArrayList() } += ThreadReply(i, author, an.contents ?: "", dateOf(cos))
                }
                list.forEachIndexed { i, an ->
                    runCatching {
                        val sub = an.subtype ?: return@runCatching
                        if (sub == "Link" || sub == "Popup" || sub == "Widget") return@runCatching
                        if (Annot2Writer.isReplyOrState(an)) return@runCatching
                        val cos = an.cosObject
                        val r = an.rectangle ?: return@runCatching
                        val gp = if (cos.getNameAsString(RT) == "Group") (cos.getDictionaryObject(IRT) as? COSDictionary)?.let { indexOf[it] } else null
                        val st = states[i]
                        out += CommentItem(pi, i, sub, cos.getString(COSName.T) ?: "", an.contents ?: "",
                            RectF(r.lowerLeftX, r.upperRightY, r.upperRightX, r.lowerLeftY), colorOf(cos), dateOf(cos), an.annotationName, gp,
                            replies[i]?.sortedBy { it.date } ?: emptyList(), st?.first ?: ReviewStatus.NONE, st?.second ?: "", st?.third ?: 0L)
                    }
                }
            }
            out
        }
    }

    /** Indices (same page) that move together with [index]: its group parent and every member. */
    fun groupMembers(items: List<CommentItem>, page: Int, index: Int): List<Int> {
        val onPage = items.filter { it.page == page }
        val me = onPage.firstOrNull { it.index == index } ?: return listOf(index)
        val root = me.groupParent ?: index
        val members = onPage.filter { it.index == root || it.groupParent == root }.map { it.index }
        return if (members.size <= 1) listOf(index) else members
    }

    /** Group annotations [indices] of [page]: the first becomes the primary, the rest get /IRT + /RT /Group. */
    suspend fun group(src: File, out: File, pw: String?, page: Int, indices: List<Int>) = withContext(Dispatchers.IO) {
        PdfOps.loadRaw(src, pw).use { doc ->
            val list = doc.getPage(page).annotations
            val members = indices.distinct().mapNotNull { list.getOrNull(it) }
            if (members.size >= 2) {
                // a member that is itself a group parent keeps its children, which re-attach to the new primary
                val primary = members.first().cosObject
                val memberSet = members.map { it.cosObject }.toSet()
                list.forEach { an ->
                    val cos = an.cosObject
                    val parent = cos.getDictionaryObject(IRT) as? COSDictionary
                    if (cos !== primary && (cos in memberSet || (parent != null && parent in memberSet && cos.getNameAsString(RT) == "Group"))) {
                        cos.setItem(IRT, primary); cos.setName(RT, "Group")
                    }
                }
            }
            SignedSave.saveEdited(doc, out, pw, listOf(page))
        }
    }

    /** Remove group links of [indices] of [page] (and of the members of groups they lead). */
    suspend fun ungroup(src: File, out: File, pw: String?, page: Int, indices: List<Int>) = withContext(Dispatchers.IO) {
        PdfOps.loadRaw(src, pw).use { doc ->
            val list = doc.getPage(page).annotations
            val sel = indices.mapNotNull { list.getOrNull(it)?.cosObject }.toSet()
            list.forEach { an ->
                val cos = an.cosObject
                if (cos.getNameAsString(RT) != "Group") return@forEach
                val parent = cos.getDictionaryObject(IRT) as? COSDictionary
                if (cos in sel || parent in sel) { cos.removeItem(IRT); cos.removeItem(RT) }
            }
            SignedSave.saveEdited(doc, out, pw, listOf(page))
        }
    }

    /** Delete annotations ([page] → indices) together with their replies, states and popups. */
    suspend fun deleteMany(src: File, out: File, pw: String?, refs: Map<Int, List<Int>>) = withContext(Dispatchers.IO) {
        PdfOps.loadRaw(src, pw).use { doc ->
            refs.forEach { (pi, idx) ->
                if (pi !in 0 until doc.numberOfPages) return@forEach
                val page = doc.getPage(pi)
                val list = page.annotations
                val doomed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<COSDictionary, Boolean>())
                idx.mapNotNullTo(doomed) { list.getOrNull(it)?.cosObject }
                // children: replies / states / popups of deleted comments (group members stay, unlinked)
                list.forEach { an ->
                    val cos = an.cosObject
                    val parent = (cos.getDictionaryObject(IRT) as? COSDictionary) ?: (cos.getDictionaryObject(COSName.PARENT) as? COSDictionary)
                    if (parent != null && parent in doomed) {
                        if (cos.getNameAsString(RT) == "Group") { cos.removeItem(IRT); cos.removeItem(RT) } else doomed += cos
                    }
                    (cos.getDictionaryObject(POPUP) as? COSDictionary)?.let { if (cos in doomed) doomed += it }
                }
                val kept = list.filter { it.cosObject !in doomed }
                page.annotations = kept
            }
            SignedSave.saveEdited(doc, out, pw, refs.keys)
        }
    }

    /** Add a reply (Text annotation /IRT parent /RT /R, hidden). */
    suspend fun addReply(src: File, out: File, pw: String?, page: Int, index: Int, author: String, text: String) = withContext(Dispatchers.IO) {
        PdfOps.loadRaw(src, pw).use { doc ->
            val pg = doc.getPage(page); val list = pg.annotations
            val parent = list.getOrNull(index)
            if (parent != null) {
                val r = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText()
                r.contents = text; r.titlePopup = author
                r.setCreationDate(Calendar.getInstance()); r.setModifiedDate(Calendar.getInstance())
                r.rectangle = parent.rectangle
                r.cosObject.setItem(IRT, parent.cosObject)
                r.cosObject.setName(RT, "R")
                r.annotationName = "sd-r-${System.nanoTime()}"
                r.isHidden = true
                list.add(r); pg.annotations = list
            }
            SignedSave.saveEdited(doc, out, pw, listOf(page))
        }
    }

    /** Set the review status (a hidden state Text annotation, /StateModel Review). */
    suspend fun setStatus(src: File, out: File, pw: String?, page: Int, index: Int, status: ReviewStatus, author: String) = withContext(Dispatchers.IO) {
        PdfOps.loadRaw(src, pw).use { doc ->
            val pg = doc.getPage(page); val list = pg.annotations
            val parent = list.getOrNull(index)
            if (parent != null) {
                val s = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText()
                s.contents = status.state; s.titlePopup = author
                s.setCreationDate(Calendar.getInstance()); s.setModifiedDate(Calendar.getInstance())
                s.rectangle = parent.rectangle
                s.cosObject.setItem(IRT, parent.cosObject)
                s.cosObject.setString(STATE, status.state)
                s.cosObject.setString(STATE_MODEL, status.model)
                s.annotationName = "sd-s-${System.nanoTime()}"
                s.isHidden = true
                list.add(s); pg.annotations = list
            }
            SignedSave.saveEdited(doc, out, pw, listOf(page))
        }
    }

    /** Delete one reply (by its index in the page array). */
    suspend fun deleteReply(src: File, out: File, pw: String?, page: Int, index: Int) = deleteMany(src, out, pw, mapOf(page to listOf(index)))

    internal fun isMarkup(an: PDAnnotation) = an is PDAnnotationMarkup
}
