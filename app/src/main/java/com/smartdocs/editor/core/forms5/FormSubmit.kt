package com.smartdocs.editor.core.forms5

import java.net.URLEncoder

/** What a push button does when tapped (from its /A action or a simple JavaScript action). */
sealed class ButtonAction {
    data class Submit(val url: String, val format: SubmitFormat, val includeNoValue: Boolean, val exclude: Boolean, val fields: List<String>, val get: Boolean) : ButtonAction()
    data class Reset(val fields: List<String>, val exclude: Boolean) : ButtonAction()
    data class OpenUri(val uri: String) : ButtonAction()
    data object Print : ButtonAction()
    /** An image button (Acrobat: `event.target.buttonImportIcon()`), filled from the gallery. */
    data object ImportImage : ButtonAction()
}

enum class SubmitFormat { FDF, HTML, XFDF, PDF }

/**
 * SubmitForm support (PDF 32000 12.7.6.2): flag decoding, the field subset and the request bodies
 * (FDF, HTML url-encoded, XFDF, whole PDF). Pure logic; the HTTP request itself is in FormSubmitter.
 */
object FormSubmit {

    const val FLAG_EXCLUDE = 1
    const val FLAG_INCLUDE_NO_VALUE = 2
    const val FLAG_EXPORT_HTML = 4
    const val FLAG_GET = 8
    const val FLAG_XFDF = 32
    /** Bit 8: FDF submissions also carry the document's comments (IncludeAnnotations). */
    const val FLAG_INCLUDE_ANNOTS = 128
    const val FLAG_PDF = 256
    /** Bit 10: date values are sent in the PDF date format (CanonicalFormat). */
    const val FLAG_CANONICAL = 512

    fun formatOf(flags: Int): SubmitFormat = when {
        flags and FLAG_PDF != 0 -> SubmitFormat.PDF
        flags and FLAG_XFDF != 0 -> SubmitFormat.XFDF
        flags and FLAG_EXPORT_HTML != 0 -> SubmitFormat.HTML
        else -> SubmitFormat.FDF
    }

    fun fromFlags(url: String, flags: Int, fields: List<String>): ButtonAction.Submit = ButtonAction.Submit(
        url = url, format = formatOf(flags), includeNoValue = flags and FLAG_INCLUDE_NO_VALUE != 0,
        exclude = flags and FLAG_EXCLUDE != 0, fields = fields, get = flags and FLAG_GET != 0 && formatOf(flags) == SubmitFormat.HTML,
    )

    private val jsSubmit = Regex("""submitForm\s*\(\s*(?:\{[^}]*?cURL\s*:\s*)?(["'])((?:\\.|(?!\1).)*)\1""")
    private val jsSubmitFormat = Regex("""cSubmitAs\s*:\s*["'](FDF|XFDF|HTML|PDF|XFD|XML)["']""", RegexOption.IGNORE_CASE)

    /** Button JavaScript reduced to an action: this.submitForm(...), this.resetForm(), print(), buttonImportIcon(), app.launchURL(...). */
    fun parseJs(js: String?): ButtonAction? {
        if (js.isNullOrBlank()) return null
        if (js.contains("buttonImportIcon")) return ButtonAction.ImportImage
        jsSubmit.find(js)?.let { m ->
            val fmt = when (jsSubmitFormat.find(js)?.groupValues?.get(1)?.uppercase()) {
                "XFDF", "XFD", "XML" -> SubmitFormat.XFDF; "HTML" -> SubmitFormat.HTML; "PDF" -> SubmitFormat.PDF
                else -> if (Regex("""bHTML\s*:\s*true""").containsMatchIn(js)) SubmitFormat.HTML else SubmitFormat.FDF
            }
            return ButtonAction.Submit(m.groupValues[2], fmt, includeNoValue = js.contains("bEmpty: true") || js.contains("bEmpty:true"), exclude = false, fields = emptyList(), get = false)
        }
        if (Regex("""resetForm\s*\(""").containsMatchIn(js)) return ButtonAction.Reset(emptyList(), exclude = false)
        Regex("""launchURL\s*\(\s*(["'])((?:\\.|(?!\1).)*)\1""").find(js)?.let { return ButtonAction.OpenUri(it.groupValues[2]) }
        if (Regex("""\bprint\s*\(""").containsMatchIn(js)) return ButtonAction.Print
        return null
    }

    /**
     * Values to send: every field, or only [ButtonAction.Submit.fields] (names or name prefixes), or all but
     * those when [ButtonAction.Submit.exclude]. Empty values are kept only with IncludeNoValueFields.
     */
    fun select(values: Map<String, String>, a: ButtonAction.Submit): Map<String, String> {
        fun listed(n: String) = a.fields.any { f -> n == f || n.startsWith("$f.") }
        return values.filter { (k, v) ->
            val inSet = a.fields.isEmpty() || (if (a.exclude) !listed(k) else listed(k))
            inSet && (a.includeNoValue || v.isNotEmpty())
        }
    }

    fun htmlBody(values: Map<String, String>): String =
        values.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun xfdf(values: Map<String, String>, fileName: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xfdf xmlns=\"http://ns.adobe.com/xfdf/\" xml:space=\"preserve\">\n")
        append("<f href=\"").append(xml(fileName)).append("\"/>\n<fields>\n")
        values.forEach { (k, v) -> append("<field name=\"").append(xml(k)).append("\"><value>").append(xml(v)).append("</value></field>\n") }
        append("</fields>\n</xfdf>\n")
    }

    /** PDF string literal with UTF-16BE (BOM) for non-ASCII text, escaped otherwise. */
    private fun pdfString(s: String): String {
        if (s.all { it.code in 32..126 }) return "(" + s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE)
        return "<" + bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) } + ">"
    }

    /** A minimal FDF file (flat /Fields array with fully qualified /T names). */
    fun fdf(values: Map<String, String>, fileName: String): ByteArray {
        val sb = StringBuilder("%FDF-1.2\n1 0 obj\n<< /FDF << /F ").append(pdfString(fileName)).append(" /Fields [\n")
        values.forEach { (k, v) -> sb.append("<< /T ").append(pdfString(k)).append(" /V ").append(pdfString(v)).append(" >>\n") }
        sb.append("] >> >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * CanonicalFormat: values of the date fields in [datePatterns] (field name -> Acrobat pattern) are sent as
     * PDF dates "D:YYYYMMDDHHmmSS" (PDF 32000 7.9.4); values that do not parse are sent unchanged.
     */
    fun canonicalDates(values: Map<String, String>, datePatterns: Map<String, String>): Map<String, String> =
        values.mapValues { (k, v) ->
            val p = datePatterns[k] ?: return@mapValues v
            if (v.isBlank() || v.startsWith("D:")) return@mapValues v
            val ms = FieldDates.parseUtc(v, p) ?: return@mapValues v
            val f = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            "D:" + f.format(java.util.Date(ms))
        }

    /**
     * FDF with the document's comments (IncludeAnnotations): the /Fields array as [fdf] plus an /Annots array of
     * already serialised annotation dictionaries ([annots], PDF syntax, each with its zero-based /Page).
     */
    fun fdfWithAnnots(values: Map<String, String>, fileName: String, annots: List<String>): ByteArray {
        if (annots.isEmpty()) return fdf(values, fileName)
        val sb = StringBuilder("%FDF-1.2
1 0 obj
<< /FDF << /F ").append(pdfString(fileName)).append(" /Fields [
")
        values.forEach { (k, v) -> sb.append("<< /T ").append(pdfString(k)).append(" /V ").append(pdfString(v)).append(" >>
") }
        sb.append("] /Annots [
")
        annots.forEach { sb.append(it).append('
') }
        sb.append("] >> >>
endobj
trailer
<< /Root 1 0 R >>
%%EOF
")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** One annotation as an FDF /Annots entry: subtype, page, rect, contents, author, colour. */
    fun fdfAnnot(subtype: String, page: Int, rect: FloatArray, contents: String, author: String, rgb: FloatArray?): String = buildString {
        append("<< /Type /Annot /Subtype /").append(subtype.filter { it.isLetterOrDigit() }.ifEmpty { "Text" })
        append(" /Page ").append(page)
        append(" /Rect [").append(rect.joinToString(" ") { String.format(java.util.Locale.US, "%.2f", it) }).append("]")
        if (contents.isNotEmpty()) append(" /Contents ").append(pdfString(contents))
        if (author.isNotEmpty()) append(" /T ").append(pdfString(author))
        if (rgb != null && rgb.size == 3) append(" /C [").append(rgb.joinToString(" ") { String.format(java.util.Locale.US, "%.3f", it) }).append("]")
        append(" >>")
    }

    fun mime(f: SubmitFormat) = when (f) {
        SubmitFormat.FDF -> "application/vnd.fdf"; SubmitFormat.HTML -> "application/x-www-form-urlencoded"
        SubmitFormat.XFDF -> "application/vnd.adobe.xfdf"; SubmitFormat.PDF -> "application/pdf"
    }

    fun isMail(url: String) = url.trim().startsWith("mailto:", ignoreCase = true)
    fun isHttp(url: String) = url.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }

    /** Absolute target: relative URLs (rare, relative to the document) cannot be resolved on device. */
    fun host(url: String): String = runCatching { java.net.URI(url.trim()).host }.getOrNull() ?: url.substringBefore('?').take(80)
}
