package com.smartdocs.editor.core.office

import java.io.File

/**
 * Writes cell value edits back into an .xlsx. Strings become inline strings (`t="inlineStr"`),
 * numbers plain `n` cells, a value starting with '=' becomes a formula (recalculated on open),
 * an empty value clears the cell (keeping its style). Worksheet XML is spliced textually so all
 * other markup is preserved; the calculation chain is dropped and full recalculation requested.
 */
object XlsxWriter {

    /** [sheetPath] is [SheetModel.partPath]; [row]/[col] are 0-based. */
    data class CellEdit(val sheetPath: String, val row: Int, val col: Int, val value: String)

    fun save(src: File, dst: File, edits: List<CellEdit>) {
        val replace = HashMap<String, ByteArray?>()
        OoxmlPackage(src).use { pkg ->
            for ((path, list) in edits.groupBy { it.sheetPath }) {
                val xml = pkg.bytes(path)?.toString(Charsets.UTF_8)?.removePrefix("﻿") ?: throw OfficeFormatException("missing $path")
                replace[path] = applyEdits(xml, list).toByteArray(Charsets.UTF_8)
            }
            if (edits.isNotEmpty()) {
                val wbPath = pkg.rels("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target ?: "xl/workbook.xml"
                val chain = pkg.rels(wbPath).values.firstOrNull { it.type.endsWith("/calcChain") }
                if (chain != null && pkg.has(chain.target)) {
                    replace[chain.target] = null
                    val relsPath = wbPath.substringBeforeLast('/') + "/_rels/" + wbPath.substringAfterLast('/') + ".rels"
                    pkg.bytes(relsPath)?.toString(Charsets.UTF_8)?.let { r ->
                        replace[relsPath] = r.replace(Regex("""<Relationship\b[^>]*\bId="${Regex.escape(chain.id)}"[^>]*/>"""), "").toByteArray(Charsets.UTF_8)
                    }
                    pkg.bytes("[Content_Types].xml")?.toString(Charsets.UTF_8)?.let { ct ->
                        replace["[Content_Types].xml"] = ct.replace(Regex("""<Override\b[^>]*PartName="/${Regex.escape(chain.target)}"[^>]*/>"""), "").toByteArray(Charsets.UTF_8)
                    }
                }
                pkg.bytes(wbPath)?.toString(Charsets.UTF_8)?.let { wb -> replace[wbPath] = requestRecalc(wb).toByteArray(Charsets.UTF_8) }
            }
        }
        dst.outputStream().use { OoxmlPackage.rewrite(src, it, replace) }
    }

    /** Saves over [file] through a temp file next to it. */
    fun saveInPlace(file: File, edits: List<CellEdit>) = replaceFileSafely(file) { save(file, it, edits) }

    private fun requestRecalc(wb: String): String {
        val calc = Regex("""<([\w.-]+:)?calcPr\b([^>]*?)(/?)>""").find(wb)
        if (calc != null) {
            if (calc.value.contains("fullCalcOnLoad")) return wb
            val prefix = calc.groupValues[1]
            val rep = "<${prefix}calcPr${calc.groupValues[2]} fullCalcOnLoad=\"1\"${calc.groupValues[3]}>"
            return wb.replaceRange(calc.range, rep)
        }
        // calcPr follows sheets/functionGroups/externalReferences/definedNames in CT_Workbook
        val wbRoot = XmlScan.find(wb, "workbook") ?: return wb
        val prefix = wbRoot.qname.substringBefore(':', "").let { if (it.isEmpty() || it == wbRoot.qname) "" else "$it:" }
        val kids = XmlScan.children(wb, wbRoot)
        val after = kids.lastOrNull { it.local in setOf("sheets", "functionGroups", "externalReferences", "definedNames") } ?: return wb
        return wb.substring(0, after.end) + "<${prefix}calcPr fullCalcOnLoad=\"1\"/>" + wb.substring(after.end)
    }

    internal fun applyEdits(xml: String, edits: List<CellEdit>): String {
        var s = xml
        // apply one edit at a time on fresh offsets (edits are few and interactive)
        for (e in edits) s = applyOne(s, e)
        return s
    }

    private fun applyOne(xml: String, e: CellEdit): String {
        val sheetData = XmlScan.find(xml, "sheetData") ?: return xml
        val p = sheetData.qname.substringBefore(':', "").let { if (it.isEmpty()) "" else "$it:" }
        val ref = CellRef.name(e.row, e.col)
        if (sheetData.selfClosing) {
            val open = xml.substring(sheetData.start, sheetData.openEnd).removeSuffix("/>").trimEnd() + ">"
            val body = "<${p}row r=\"${e.row + 1}\">" + cellXml(p, ref, null, e.value) + "</${p}row>"
            return xml.substring(0, sheetData.start) + open + body + "</${sheetData.qname}>" + xml.substring(sheetData.end)
        }
        val rows = XmlScan.children(xml, sheetData).filter { it.local == "row" }
        var nextIdx = 0
        var target: XmlElem? = null; var insertAt = sheetData.innerRange(xml).last + 1
        for (r in rows) {
            val ri = XmlScan.attr(xml, r, "r")?.toIntOrNull()?.minus(1) ?: nextIdx
            nextIdx = ri + 1
            if (ri == e.row) { target = r; break }
            if (ri > e.row) { insertAt = r.start; break }
        }
        if (target == null) {
            val row = "<${p}row r=\"${e.row + 1}\">" + cellXml(p, ref, null, e.value) + "</${p}row>"
            return xml.substring(0, insertAt) + row + xml.substring(insertAt)
        }
        // inside the row
        val rowOpen = xml.substring(target.start, target.openEnd).replace(Regex("""\s+spans="[^"]*""""), "")
        if (target.selfClosing) {
            val open = rowOpen.removeSuffix("/>").trimEnd() + ">"
            return xml.substring(0, target.start) + open + cellXml(p, ref, null, e.value) + "</${target.qname}>" + xml.substring(target.end)
        }
        val cells = XmlScan.children(xml, target).filter { it.local == "c" }
        var nextCol = 0
        var cell: XmlElem? = null; var cellInsert = target.innerRange(xml).last + 1
        for (c in cells) {
            val ci = XmlScan.attr(xml, c, "r")?.let { CellRef.parse(it)?.second } ?: nextCol
            nextCol = ci + 1
            if (ci == e.col) { cell = c; break }
            if (ci > e.col) { cellInsert = c.start; break }
        }
        val prefix = xml.substring(0, target.start) + rowOpen
        val rowRest = target.openEnd
        return if (cell != null) {
            val style = XmlScan.attr(xml, cell, "s")
            prefix + xml.substring(rowRest, cell.start) + cellXml(p, ref, style, e.value) + xml.substring(cell.end)
        } else {
            prefix + xml.substring(rowRest, cellInsert) + cellXml(p, ref, null, e.value) + xml.substring(cellInsert)
        }
    }

    private val numberRx = Regex("""^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$""")

    internal fun cellXml(p: String, ref: String, style: String?, value: String): String {
        val s = style?.let { " s=\"$it\"" } ?: ""
        val v = value.trim()
        return when {
            value.isEmpty() -> "<${p}c r=\"$ref\"$s/>"
            v.startsWith("=") && v.length > 1 -> "<${p}c r=\"$ref\"$s><${p}f>${XmlScan.escape(v.substring(1))}</${p}f></${p}c>"
            // keep leading zeros ("007") and very long digit strings (IDs, phone numbers) as text
            numberRx.matches(v) && !(v.length > 1 && v.startsWith("0") && !v.startsWith("0.")) && v.length <= 15 ->
                "<${p}c r=\"$ref\"$s><${p}v>$v</${p}v></${p}c>"
            v.equals("TRUE", true) || v.equals("FALSE", true) ->
                "<${p}c r=\"$ref\"$s t=\"b\"><${p}v>${if (v.equals("TRUE", true)) 1 else 0}</${p}v></${p}c>"
            else -> "<${p}c r=\"$ref\"$s t=\"inlineStr\"><${p}is><${p}t xml:space=\"preserve\">${XmlScan.escape(value)}</${p}t></${p}is></${p}c>"
        }
    }
}
