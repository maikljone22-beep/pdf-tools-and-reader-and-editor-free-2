package com.smartdocs.editor.core.tools5

import android.content.Context
import com.smartdocs.editor.core.office.OoxmlReader
import com.smartdocs.editor.core.pdf.FieldType
import com.smartdocs.editor.core.pdf.FormOps
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** A data table for mail merge: header + rows. */
class MergeTable(val header: List<String>, val rows: List<List<String>>)

/**
 * Mail merge: a fillable PDF (AcroForm) template + CSV / XLSX rows → one filled copy per row (named from a column)
 * or one merged PDF. Field ↔ column mapping is automatic (MergeMapping) and editable in the UI.
 */
object MailMerge {

    fun readTable(f: File): MergeTable {
        val rows: List<List<String>> = when (f.extension.lowercase()) {
            "xlsx" -> {
                val sheet = OoxmlReader.readXlsx(f, maxRows = 10_000, maxCols = 100).sheets.firstOrNull() ?: throw IllegalArgumentException("empty workbook")
                val maxCol = sheet.maxCol
                sheet.rows.sortedBy { it.index }.map { r -> (0..maxCol).map { c -> r.cells.firstOrNull { it.col == c }?.display.orEmpty() } }
            }
            else -> Csv.parse(ToPdf.readText(f))
        }.filter { r -> r.any { it.isNotBlank() } }
        if (rows.isEmpty()) throw IllegalArgumentException("empty table")
        val header = rows.first().mapIndexed { i, h -> h.trim().ifBlank { "col${i + 1}" } }
        return MergeTable(header, rows.drop(1))
    }

    /** Fillable fields of the template (buttons and signatures excluded). */
    suspend fun templateFields(template: File, password: String?): List<String> =
        FormOps.listFields(template, password).filter { it.type != FieldType.BUTTON && it.type != FieldType.SIGNATURE && !it.readOnly }.map { it.name }.distinct()

    /** Check-box values: "1/yes/true/نعم/x" → "true". */
    private fun normalizeValue(v: String, checkbox: Boolean): String {
        if (!checkbox) return v
        val t = TextNorm.arabicKey(v.trim())
        return if (t in setOf("1", "yes", "true", "y", "x", "on", "نعم", "✓")) "true" else "false"
    }

    /**
     * Run the merge. [mapping] field → column; [nameColumn] names each output; [merged] = one combined PDF.
     * [flatten] makes the copies non-editable. Returns the produced files.
     */
    suspend fun run(
        ctx: Context, template: File, password: String?, table: MergeTable, mapping: Map<String, String?>, outDir: File,
        baseName: String, nameColumn: String?, merged: Boolean, flatten: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<File> = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        val fields = FormOps.listFields(template, password).associateBy { it.name }
        val taken = HashSet<String>()
        val made = ArrayList<File>()
        val tmpDir = File(ctx.cacheDir, "t5_merge_" + System.currentTimeMillis()).apply { mkdirs() }
        try {
            table.rows.forEachIndexed { i, row ->
                coroutineContext.ensureActive()
                onProgress(i, table.rows.size)
                val values = MergeMapping.values(mapping, table.header, row).mapValues { (f, v) -> normalizeValue(v, fields[f]?.type == FieldType.CHECKBOX) }
                val name = MergeMapping.fileName(baseName, table.header, row, nameColumn, i, taken)
                val dst = File(if (merged) tmpDir else outDir, "$name.pdf")
                FormOps.fillFields(template, values, dst, password, context = ctx)
                if (flatten) {
                    val flat = File(tmpDir, "flat_$i.pdf")
                    com.smartdocs.editor.core.pdf.PdfOps.flattenForms(dst, flat)
                    flat.copyTo(dst, overwrite = true); flat.delete()
                }
                made += dst
            }
            onProgress(table.rows.size, table.rows.size)
            if (!merged) return@withContext made
            val out = File(outDir, TextNorm.safeFileName(baseName, 80).ifBlank { "merge" } + "_merged.pdf").let { f ->
                var g = f; var n = 2
                while (g.exists()) g = File(outDir, f.nameWithoutExtension + "_" + (n++) + ".pdf")
                g
            }
            val m = PDFMergerUtility()
            made.forEach { m.addSource(it) }
            m.destinationFileName = out.absolutePath
            m.mergeDocuments(null)
            listOf(out)
        } finally { tmpDir.deleteRecursively() }
    }
}
