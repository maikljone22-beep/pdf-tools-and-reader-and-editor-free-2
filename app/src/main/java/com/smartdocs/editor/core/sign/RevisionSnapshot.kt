package com.smartdocs.editor.core.sign

import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Builds [RevisionDiff] snapshots with PdfBox and lists the changes made to a document since its last signed
 * revision (the bytes covered by the last signature's ByteRange) — or, for an unsigned document saved with
 * incremental updates, since the previous revision.
 */
object RevisionSnapshot {

    enum class Base { LAST_SIGNATURE, PREVIOUS_REVISION, NONE }

    data class Report(val base: Base, val revisions: Int, val signatures: Int, val changes: List<RevisionDiff.Change>)

    fun snapshot(doc: PDDocument): RevisionDiff.DocSnap {
        val pages = doc.pages.map { p ->
            val mb = p.mediaBox
            val annots = runCatching { p.annotations }.getOrDefault(emptyList()).map { a ->
                val r = a.rectangle
                val key = if (r == null) "?" else "${r.lowerLeftX.roundToInt()},${r.lowerLeftY.roundToInt()},${r.upperRightX.roundToInt()},${r.upperRightY.roundToInt()}" +
                    "|" + (a.contents ?: "").hashCode()
                RevisionDiff.AnnotSnap(a.subtype ?: "?", key)
            }
            RevisionDiff.PageSnap(p.rotation, "${mb.width.roundToInt()}x${mb.height.roundToInt()}", annots)
        }
        val fields = LinkedHashMap<String, RevisionDiff.FieldSnap>()
        runCatching {
            doc.documentCatalog.acroForm?.fieldTree?.forEach { f ->
                val name = f.fullyQualifiedName ?: return@forEach
                fields[name] = RevisionDiff.FieldSnap(f.fieldType ?: "", runCatching { f.valueAsString }.getOrNull().orEmpty())
            }
        }
        return RevisionDiff.DocSnap(pages, fields)
    }

    private fun load(bytes: ByteArray, password: String?): PDDocument =
        if (password.isNullOrEmpty()) PDDocument.load(bytes) else PDDocument.load(bytes, password)

    suspend fun changes(file: File, password: String? = null): Report = withContext(Dispatchers.IO) {
        val bytes = file.readBytes()
        val ends = RevisionDiff.revisionEnds(bytes)
        load(bytes, password).use { cur ->
            val sigs = cur.signatureDictionaries
            val lastSignedEnd = sigs.mapNotNull { s -> s.byteRange?.takeIf { it.size == 4 }?.let { it[2] + it[3] } }.maxOrNull()
            val (base, end) = when {
                lastSignedEnd != null && lastSignedEnd < bytes.size -> Base.LAST_SIGNATURE to lastSignedEnd
                lastSignedEnd != null -> return@withContext Report(Base.LAST_SIGNATURE, ends.size, sigs.size, emptyList())
                ends.size >= 2 -> Base.PREVIOUS_REVISION to ends[ends.size - 2]
                else -> return@withContext Report(Base.NONE, ends.size, 0, emptyList())
            }
            val old = runCatching { load(bytes.copyOf(end), password).use { snapshot(it) } }.getOrNull()
                ?: return@withContext Report(Base.NONE, ends.size, sigs.size, emptyList())
            Report(base, ends.size, sigs.size, RevisionDiff.diff(old, snapshot(cur)))
        }
    }
}
