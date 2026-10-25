package com.smartdocs.editor.core.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/** Review status of a comment, stored the standard way: a hidden Text annotation with /State and /IRT. */
enum class ReviewStatus(val model: String, val state: String) {
    NONE("Review", "None"), ACCEPTED("Review", "Accepted"), REJECTED("Review", "Rejected"), CANCELLED("Review", "Cancelled"), COMPLETED("Review", "Completed"),
}

data class Reply(val page: Int, val index: Int, val author: String, val text: String, val date: String)

/**
 * Threaded replies and review states on existing annotations, using the PDF-standard In-Reply-To (/IRT)
 * mechanism so other viewers show them as threads.
 */
object AnnotReplies {

    private val IRT: COSName = COSName.getPDFName("IRT")

    private fun load(src: File, password: String?): PDDocument = if (password.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, password)

    private fun parentRef(an: PDAnnotation): COSDictionary? = an.cosObject.getDictionaryObject(IRT) as? COSDictionary

    /** Replies to the annotation at [page]/[index] (index in the page's annotation array). */
    suspend fun repliesOf(src: File, page: Int, index: Int, password: String? = null): List<Reply> = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val list = doc.getPage(page).annotations
            val target = list.getOrNull(index)?.cosObject ?: return@use emptyList()
            list.mapIndexedNotNull { i, an ->
                val p = parentRef(an) ?: return@mapIndexedNotNull null
                if (p !== target) return@mapIndexedNotNull null
                if (an.cosObject.getNameAsString(COSName.getPDFName("RT")) == "Group") return@mapIndexedNotNull null
                if (an.cosObject.containsKey(COSName.getPDFName("State"))) return@mapIndexedNotNull null
                Reply(page, i, (an as? PDAnnotationMarkup)?.titlePopup ?: "", an.contents ?: "", an.cosObject.getString(COSName.M) ?: "")
            }
        }
    }

    suspend fun addReply(src: File, page: Int, index: Int, author: String, text: String, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        // PdfOps.load re-protects encrypted files so PdfBox can save them
        PdfOps.load(src, password).use { doc ->
            val pg = doc.getPage(page); val list = pg.annotations
            val parent = list.getOrNull(index) ?: return@use
            val r = PDAnnotationText()
            r.contents = text; r.titlePopup = author
            r.setCreationDate(Calendar.getInstance()); r.setModifiedDate(Calendar.getInstance())
            r.rectangle = parent.rectangle
            r.cosObject.setItem(IRT, parent.cosObject)
            r.cosObject.setName(COSName.getPDFName("RT"), "R")
            r.isHidden = true
            list.add(r); pg.annotations = list
            doc.save(out)
        }
    }

    /** Current review status of an annotation = the newest state annotation replying to it. */
    suspend fun statusOf(src: File, page: Int, index: Int, password: String? = null): ReviewStatus = withContext(Dispatchers.IO) {
        load(src, password).use { doc ->
            val list = doc.getPage(page).annotations
            val target = list.getOrNull(index)?.cosObject ?: return@use ReviewStatus.NONE
            val states = list.filter { parentRef(it) === target && it.cosObject.containsKey(COSName.getPDFName("State")) }
            val last = states.lastOrNull()?.cosObject?.getString(COSName.getPDFName("State")) ?: return@use ReviewStatus.NONE
            ReviewStatus.entries.firstOrNull { it.state == last } ?: ReviewStatus.NONE
        }
    }

    suspend fun setStatus(src: File, page: Int, index: Int, status: ReviewStatus, author: String, out: File, password: String? = null) = withContext(Dispatchers.IO) {
        PdfOps.load(src, password).use { doc ->
            val pg = doc.getPage(page); val list = pg.annotations
            val parent = list.getOrNull(index) ?: return@use
            val s = PDAnnotationText()
            s.contents = status.state; s.titlePopup = author
            s.setCreationDate(Calendar.getInstance()); s.setModifiedDate(Calendar.getInstance())
            s.rectangle = parent.rectangle
            s.cosObject.setItem(IRT, parent.cosObject)
            s.cosObject.setString(COSName.getPDFName("State"), status.state)
            s.cosObject.setString(COSName.getPDFName("StateModel"), status.model)
            s.isHidden = true
            list.add(s); pg.annotations = list
            doc.save(out)
        }
    }
}
