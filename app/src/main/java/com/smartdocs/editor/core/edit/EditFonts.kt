package com.smartdocs.editor.core.edit

import android.content.Context
import android.graphics.Typeface
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.io.InputStream

/**
 * NVE-text: font faces offered by the edit-mode font picker (E7): the bundled Arabic fonts (assets, res/font) and the
 * device's system fonts (/system/fonts, TrueType / OpenType files). Ids: "asset:<path>", "res:<name>", "file:<path>".
 * Faces are embedded with PDType0Font (subset or full, see [FontEmbed]); CFF-flavoured OpenType files cannot be
 * embedded by PDFBox and fall back to a standard font (the writer reports it).
 */
object EditFonts {
    data class Face(val id: String, val name: String, val arabic: Boolean, val bundled: Boolean)

    private const val NASKH = "asset:fonts/NotoNaskhArabic-Regular.ttf"
    @Volatile private var cache: List<Face>? = null

    private fun bundled(): List<Face> = listOf(
        Face(NASKH, "Noto Naskh Arabic", true, true),
        Face("res:noto_sans_arabic_regular", "Noto Sans Arabic", true, true),
        Face("res:noto_sans_arabic_medium", "Noto Sans Arabic Medium", true, true),
        Face("res:noto_sans_arabic_bold", "Noto Sans Arabic Bold", true, true),
    )

    private fun prettyName(file: String): String =
        file.substringBeforeLast('.').replace('-', ' ').replace('_', ' ')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2").replace(Regex(" +"), " ").trim()

    private fun isArabicName(n: String) = listOf("arabic", "naskh", "kufi", "nastaliq", "urdu").any { n.lowercase().contains(it) }

    /** Bundled faces, then system faces sorted by name ([includeSystem] false: bundled only). */
    fun list(context: Context, includeSystem: Boolean = true): List<Face> {
        val sys = cache ?: runCatching {
            File("/system/fonts").listFiles()?.filter { f -> f.isFile && (f.name.endsWith(".ttf", true) || f.name.endsWith(".otf", true)) }
                ?.map { f -> Face("file:" + f.absolutePath, prettyName(f.name), isArabicName(f.name), false) }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
        }.getOrDefault(emptyList()).also { cache = it }
        return bundled() + if (includeSystem) sys else emptyList()
    }

    fun nameOf(context: Context, id: String?): String? = id?.let { i -> list(context).firstOrNull { it.id == i }?.name ?: prettyName(i.substringAfterLast('/').substringAfter(':')) }

    fun open(context: Context, id: String): InputStream? = runCatching {
        when {
            id.startsWith("asset:") -> context.assets.open(id.removePrefix("asset:"))
            id.startsWith("res:") -> {
                val rid = context.resources.getIdentifier(id.removePrefix("res:"), "font", context.packageName)
                if (rid == 0) null else context.resources.openRawResource(rid)
            }
            id.startsWith("file:") -> File(id.removePrefix("file:")).inputStream()
            else -> null
        }
    }.getOrNull()

    /** The face [id] embedded in [doc] (null when it cannot be read or embedded). */
    fun load(context: Context, doc: PDDocument, id: String, embed: FontEmbed): PDFont? = runCatching {
        open(context, id)?.use { PDType0Font.load(doc, it, embed != FontEmbed.FULL) }
    }.getOrNull()

    /** Arabic fallback font (Noto Naskh Arabic), embedded as a subset unless [embed] is FULL. */
    fun arabic(context: Context, doc: PDDocument, embed: FontEmbed): PDFont = runCatching {
        context.assets.open("fonts/NotoNaskhArabic-Regular.ttf").use { PDType0Font.load(doc, it, embed != FontEmbed.FULL) }
    }.getOrElse { PDType1Font.HELVETICA }

    /** Typeface for the on-screen preview of [id] (null: use the family). */
    fun typeface(context: Context, id: String?): Typeface? = id?.let { i ->
        runCatching {
            when {
                i.startsWith("asset:") -> Typeface.createFromAsset(context.assets, i.removePrefix("asset:"))
                i.startsWith("res:") -> context.resources.getIdentifier(i.removePrefix("res:"), "font", context.packageName).takeIf { it != 0 }
                    ?.let { androidx.core.content.res.ResourcesCompat.getFont(context, it) }
                i.startsWith("file:") -> Typeface.createFromFile(i.removePrefix("file:"))
                else -> null
            }
        }.getOrNull()
    }
}
