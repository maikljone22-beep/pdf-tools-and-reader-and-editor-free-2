package com.smartdocs.editor.ui.office

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.office.OfficeKind
import com.smartdocs.editor.core.office.detectOfficeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the Office screen opens a file as. */
enum class OfficeDocKind { WORD, SHEET, CSV, SLIDES, TEXT, IMPORT, LEGACY, NONE }

/** Routing and file plumbing for the Office editors (route, MIME types, kind detection, imports). */
object OfficeRoutes {
    const val ROUTE = "office5/{uri}"
    fun route(uri: String) = "office5/${Uri.encode(uri)}"

    /** MIME types for "open document" pickers and the VIEW/SEND intent filters. */
    val MIMES = arrayOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.ms-word.document.macroEnabled.12", "application/vnd.ms-excel.sheet.macroEnabled.12",
        "application/vnd.ms-powerpoint.presentation.macroEnabled.12", "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "application/vnd.oasis.opendocument.text", "application/rtf", "text/rtf",
        "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
        "text/plain", "text/csv", "text/comma-separated-values", "text/tab-separated-values",
    )

    private val WORD = setOf("docx", "docm", "dotx", "dotm")
    private val SHEET = setOf("xlsx", "xlsm", "xltx", "xltm")
    private val SLIDES = setOf("pptx", "pptm", "potx", "ppsx", "ppsm")
    private val TEXT = setOf("txt", "text", "log", "md")
    private val CSV = setOf("csv", "tsv")
    private val IMPORT = setOf("odt", "rtf")
    private val LEGACY = setOf("doc", "dot", "xls", "xlt", "ppt", "pps", "pot")

    fun kindOfName(name: String): OfficeDocKind = when (name.substringAfterLast('.', "").lowercase()) {
        in WORD -> OfficeDocKind.WORD; in SHEET -> OfficeDocKind.SHEET; in SLIDES -> OfficeDocKind.SLIDES
        in CSV -> OfficeDocKind.CSV; in TEXT -> OfficeDocKind.TEXT; in IMPORT -> OfficeDocKind.IMPORT; in LEGACY -> OfficeDocKind.LEGACY
        else -> OfficeDocKind.NONE
    }

    /** Kind of a file by name, then by content (a ZIP's main part, an OLE2 header) when the name says nothing useful. */
    fun kindOf(f: File): OfficeDocKind {
        val byName = kindOfName(f.name)
        if (byName != OfficeDocKind.NONE && byName != OfficeDocKind.LEGACY) return byName
        return when (detectOfficeKind(f)) {
            OfficeKind.DOCX -> OfficeDocKind.WORD; OfficeKind.XLSX -> OfficeDocKind.SHEET; OfficeKind.PPTX -> OfficeDocKind.SLIDES
            OfficeKind.LEGACY_DOC, OfficeKind.LEGACY_XLS, OfficeKind.LEGACY_PPT -> OfficeDocKind.LEGACY
            else -> if (byName == OfficeDocKind.LEGACY) OfficeDocKind.LEGACY else OfficeDocKind.NONE
        }
    }

    /** True when the reader route should hand this uri to the Office screen. */
    fun isOffice(uri: String): Boolean {
        val path = runCatching { Uri.parse(uri).path }.getOrNull() ?: return false
        val name = path.substringAfterLast('/')
        if (kindOfName(name) != OfficeDocKind.NONE) return true
        if (!name.endsWith(".pdf", true)) return false
        // "report.docx.pdf" made by the old import naming, or any PDF-named Office file
        val f = File(path)
        return f.isFile && runCatching { f.inputStream().use { it.read() == 'P'.code } }.getOrDefault(false) && kindOf(f) != OfficeDocKind.NONE
    }

    fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "csv" -> "text/csv"; "txt", "md", "log" -> "text/plain"; "pdf" -> "application/pdf"
        "odt" -> "application/vnd.oasis.opendocument.text"; "rtf" -> "application/rtf"
        "doc" -> "application/msword"; "xls" -> "application/vnd.ms-excel"; "ppt" -> "application/vnd.ms-powerpoint"
        else -> "*/*"
    }

    /** The extension the content really has, for files named *.pdf by an older import. */
    fun realExt(f: File): String? = when (kindOf(f)) {
        OfficeDocKind.WORD -> "docx"; OfficeDocKind.SHEET -> "xlsx"; OfficeDocKind.SLIDES -> "pptx"
        else -> null
    }

    /**
     * Gives a working copy imported with a wrong ".pdf" name its real extension (keeping the link to the
     * original document for write-back). Returns the file to use.
     */
    fun repairName(app: SmartDocsApp, f: File): File {
        if (!f.name.endsWith(".pdf", true)) return f
        val ext = realExt(f) ?: return f
        val base = f.name.removeSuffix(".pdf").removeSuffix(".PDF").let { b -> if (b.substringAfterLast('.', "").equals(ext, true)) b.substringBeforeLast('.') else b }
        val target = app.documents.uniqueFile(f.parentFile ?: app.documents.docsDir, base, ext)
        if (!f.renameTo(target)) return f
        app.documents.originOf(f)?.let { app.documents.setOrigin(target, it) }
        return target
    }

    /**
     * Copies a picked/shared content uri into the documents folder keeping its real extension (unlike
     * DocumentRepository.localFile, which names copies *.pdf) and remembers it as the origin for write-back.
     */
    suspend fun importUri(ctx: Context, app: SmartDocsApp, uri: Uri): File = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext File(uri.path!!)
        val name = app.documents.displayName(uri) ?: "document"
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 } ?: "bin"
        val target = app.documents.uniqueFile(app.documents.docsDir, name.substringBeforeLast('.'), ext)
        val tmp = File(target.parentFile, ".${target.name}.import")
        ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } ?: throw IllegalStateException("cannot open $uri")
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        app.documents.setOrigin(target, uri)
        target
    }

    fun open(nav: NavHostController, file: File) = nav.navigate(route(Uri.fromFile(file).toString()))

    /** Creates an empty document ("docx" or "xlsx") in the documents folder (right-to-left by default). */
    fun createBlank(app: SmartDocsApp, ext: String, baseName: String, rtl: Boolean = true, sheetName: String = "Sheet1"): File {
        val f = app.documents.uniqueFile(app.documents.docsDir, baseName, ext)
        when (ext) {
            "docx" -> com.smartdocs.editor.core.office5.WordParts.blankDocx(listOf(""), rtl).saveTo(f)
            "xlsx" -> com.smartdocs.editor.core.office5.XlsxIO5.blankPackage(sheetName, rtl).saveTo(f)
            else -> throw IllegalArgumentException(ext)
        }
        return f
    }
}

/** Launches the system picker for any Office/text document and opens it in the Office screen. */
@Composable
fun rememberOfficeOpener(nav: NavHostController): () -> Unit {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .onFailure { runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            scope.launch { runCatching { OfficeRoutes.importUri(ctx, app, uri) }.getOrNull()?.let { OfficeRoutes.open(nav, it) } }
        }
    }
    return { launcher.launch(OfficeRoutes.MIMES) }
}
