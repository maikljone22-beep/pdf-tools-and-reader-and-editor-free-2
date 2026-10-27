package com.smartdocs.editor.ui.openall

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.openall.FType
import com.smartdocs.editor.core.openall.FileSniffer
import com.smartdocs.editor.ui.office.OfficeDocKind
import com.smartdocs.editor.ui.office.OfficeRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Which screen shows a file (open-all central dispatch). */
enum class OpenScreen { READER, OFFICE, VIEWER }

/**
 * Open-all routing: every "open document" path (`Routes.reader`) comes through [OpenDispatch], which detects the
 * type by content and sends PDFs to the reader, editable Office files to the Office editors and everything else to
 * the universal in-app viewer ([UniversalViewer]).
 */
object OpenAll {
    const val ROUTE = "openall/{uri}?depth={depth}"
    fun route(uri: String, depth: Int = 0) = "openall/${Uri.encode(uri)}?depth=$depth"

    /** Plain-text files up to this size open in the text editor; bigger ones in the streamed viewer. */
    const val TEXT_EDITOR_MAX = 1L shl 20

    /** Where a local file of type [t] is shown. */
    fun screenFor(t: FType, f: File): OpenScreen = when (t) {
        FType.PDF -> OpenScreen.READER
        FType.DOCX, FType.XLSX, FType.PPTX -> if (OfficeRoutes.kindOf(f) in setOf(OfficeDocKind.WORD, OfficeDocKind.SHEET, OfficeDocKind.SLIDES)) OpenScreen.OFFICE else OpenScreen.VIEWER
        FType.CSV -> if (OfficeRoutes.kindOfName(f.name) == OfficeDocKind.CSV && f.length() <= 20L * 1024 * 1024) OpenScreen.OFFICE else OpenScreen.VIEWER
        FType.RTF, FType.ODT -> if (OfficeRoutes.kindOfName(f.name) == OfficeDocKind.IMPORT) OpenScreen.OFFICE else OpenScreen.VIEWER
        FType.TEXT -> if (f.extension.lowercase() in setOf("txt", "text") && f.length() <= TEXT_EDITOR_MAX) OpenScreen.OFFICE else OpenScreen.VIEWER
        else -> OpenScreen.VIEWER
    }

    /** Fast decision for local files that need no ZIP / OLE2 directory read (safe to call while composing). */
    fun quick(uri: String): Pair<OpenScreen, String>? {
        val u = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        if (u.scheme != "file" && u.scheme != null) return null
        val f = File(u.path ?: return null)
        if (!f.isFile) return OpenScreen.READER to uri   // the reader shows its "missing file" screen
        val head = FileSniffer.readHead(f, 1024)
        if (head.size >= 2 && ((head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) || (head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte()))) return null
        return screenFor(FileSniffer.detect(f.name, head), f) to Uri.fromFile(f).toString()
    }

    fun displayName(ctx: Context, u: Uri): String? = runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: u.lastPathSegment?.substringAfterLast('/')

    /** True for audio / video content that should be streamed from its source instead of copied. */
    fun isStreamedMedia(ctx: Context, u: Uri): Boolean {
        if (u.scheme != "content") return false
        val mime = runCatching { ctx.contentResolver.getType(u) }.getOrNull()
        val byMime = FileSniffer.byMime(mime)
        if (byMime.isMedia) return true
        return FileSniffer.byExtension(displayName(ctx, u).orEmpty()).isMedia
    }

    /** Off the main thread: imports content:// documents and decides the screen. */
    suspend fun resolve(ctx: Context, app: SmartDocsApp, uri: String): Pair<OpenScreen, String> = withContext(Dispatchers.IO) {
        val u = Uri.parse(uri)
        if (u.scheme == "content" && isStreamedMedia(ctx, u)) return@withContext OpenScreen.VIEWER to uri
        val f = if (u.scheme == "content") app.documents.localFile(u) else File(u.path ?: "")
        if (!f.isFile) return@withContext OpenScreen.READER to uri
        screenFor(FileSniffer.of(f), f) to Uri.fromFile(f).toString()
    }

    /** True for files the viewer must not add to Recents (archive entries, temporary copies). */
    fun isTemporary(ctx: Context, f: File): Boolean = f.absolutePath.startsWith(ctx.cacheDir.absolutePath)

    /** Folder of extracted archive entries / converted previews (cleared by the system with the cache). */
    fun workDir(ctx: Context, name: String): File = File(ctx.cacheDir, "openall/$name").apply { mkdirs() }

    // ---- shared actions

    fun share(ctx: Context, app: SmartDocsApp, f: File, t: FType) {
        runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(f), FileSniffer.mimeOf(t, f.extension.lowercase())), null)) }
            .onFailure { Toast.makeText(ctx, ctx.getString(R.string.o5_no_app), Toast.LENGTH_SHORT).show() }
    }

    fun openWith(ctx: Context, app: SmartDocsApp, f: File, t: FType) {
        val mime = FileSniffer.mimeOf(t, f.extension.lowercase()).let { if (it == "application/octet-stream") android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*" else it }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(app.documents.shareUri(f), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(i, null)) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.o5_no_app), Toast.LENGTH_SHORT).show() }
    }

    fun openWithUri(ctx: Context, u: Uri, mime: String) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(i, null)) }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.o5_no_app), Toast.LENGTH_SHORT).show() }
    }
}

/** The reader route's body: detects the type and shows the right screen. */
@Composable
fun OpenDispatch(nav: NavHostController, uri: String, page: Int) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val quick = remember(uri) { runCatching { OpenAll.quick(uri) }.getOrNull() }
    val target by produceState(initialValue = quick, uri) {
        if (value == null) value = runCatching { OpenAll.resolve(ctx.applicationContext, app, uri) }.getOrElse { OpenScreen.READER to uri }
    }
    when (target?.first) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        OpenScreen.READER -> com.smartdocs.editor.ui.reader.ReaderScreen(nav, uri = target!!.second, startPage = page)
        OpenScreen.OFFICE -> com.smartdocs.editor.ui.office.OfficeScreen(nav, target!!.second)
        OpenScreen.VIEWER -> UniversalViewer(nav, target!!.second, 0)
    }
}

/** `openall/{uri}?depth=` — archive entries and "open as …" go straight to the viewer. */
fun NavGraphBuilder.openAllRoutes(nav: NavHostController) {
    composable(
        OpenAll.ROUTE,
        arguments = listOf(navArgument("uri") { type = NavType.StringType }, navArgument("depth") { type = NavType.IntType; defaultValue = 0 }),
    ) { e -> UniversalViewer(nav, Uri.decode(e.arguments?.getString("uri") ?: ""), e.arguments?.getInt("depth") ?: 0) }
}
