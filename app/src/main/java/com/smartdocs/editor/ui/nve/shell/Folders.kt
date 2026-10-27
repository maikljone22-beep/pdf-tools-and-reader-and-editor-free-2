package com.smartdocs.editor.ui.nve.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.RecentDoc
import com.smartdocs.editor.ui.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pinned folders, default save folder and the recent-list size (prefs "nve_shell"). */
object NveFolders {
    private fun p(ctx: Context) = NveShellPrefs.prefs(ctx)

    fun pinned(ctx: Context): List<Uri> = p(ctx).getString("pinned_folders", "")!!.split('\n').filter { it.isNotBlank() }.map(Uri::parse)
    fun pin(ctx: Context, tree: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        val l = (pinned(ctx).map { it.toString() } + tree.toString()).distinct().takeLast(12)
        p(ctx).edit().putString("pinned_folders", l.joinToString("\n")).apply(); NveShellPrefs.version.intValue++
    }
    fun unpin(ctx: Context, tree: Uri) {
        p(ctx).edit().putString("pinned_folders", pinned(ctx).filter { it != tree }.joinToString("\n")).apply(); NveShellPrefs.version.intValue++
    }

    /** Display name of a tree uri ("primary:Download/Work" -> "Work"). */
    fun label(tree: Uri): String = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()?.substringAfterLast(':')?.substringAfterLast('/')?.ifBlank { null } ?: tree.lastPathSegment ?: tree.toString()

    fun defaultSaveFolder(ctx: Context): Uri? = p(ctx).getString("save_folder", null)?.let(Uri::parse)
    fun setDefaultSaveFolder(ctx: Context, tree: Uri?) {
        tree?.let { runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        p(ctx).edit().putString("save_folder", tree?.toString()).apply(); NveShellPrefs.version.intValue++
    }

    /** Initial location for a CreateDocument / OpenDocument picker (DocumentsContract.EXTRA_INITIAL_URI). */
    fun initialUri(tree: Uri): Uri? = runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)) }.getOrNull()

    /** Copy [file] into the default save folder; returns the new document uri or null. */
    suspend fun saveCopy(ctx: Context, file: File, mime: String = "application/pdf"): Uri? = withContext(Dispatchers.IO) {
        val tree = defaultSaveFolder(ctx) ?: return@withContext null
        runCatching {
            val parent = initialUri(tree) ?: return@runCatching null
            val doc = DocumentsContract.createDocument(ctx.contentResolver, parent, mime, file.name) ?: return@runCatching null
            ctx.contentResolver.openOutputStream(doc)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            doc
        }.getOrNull()
    }

    /** Recent list size shown on Home (the database keeps 50). */
    fun recentSize(ctx: Context): Int = p(ctx).getInt("recent_size", 50)
    fun setRecentSize(ctx: Context, n: Int) { p(ctx).edit().putInt("recent_size", n.coerceIn(5, 50)).apply(); NveShellPrefs.version.intValue++ }
}

/** CreateDocument that starts in the default save folder (for "Save as"; hook for the reader). */
class NveCreateDocument(private val ctx: Context, mime: String = "application/pdf") : ActivityResultContracts.CreateDocument(mime) {
    override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).also { i ->
        NveFolders.defaultSaveFolder(ctx)?.let(NveFolders::initialUri)?.let { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
    }
}

/** Home: pinned folders row (tap = open a file from that folder; long list in a menu) + pin a new folder. */
@Composable
fun NveHomePinnedFolders(nav: NavHostController) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val v = NveShellPrefs.version.intValue
    val folders = remember(v) { NveFolders.pinned(ctx) }
    var menuFor by remember { mutableStateOf<Uri?>(null) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { t -> NveFolders.pin(ctx, t) } }
    val contract = remember { OpenDocumentAt() }
    val open = rememberLauncherForActivityResult(contract) { uri ->
        if (uri != null) scope.launch { runCatching { app.documents.localFile(uri) }.getOrNull()?.let { nav.navigate(Routes.reader(Uri.fromFile(it).toString())) } }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FolderSpecial, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.nl_pinned_folders), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            folders.forEach { f ->
                Box {
                    AssistChip(onClick = { contract.start = NveFolders.initialUri(f); runCatching { open.launch(arrayOf("application/pdf", "*/*")) } },
                        label = { Text(NveFolders.label(f)) }, leadingIcon = { Icon(Icons.Default.Folder, null) },
                        trailingIcon = { IconButton(onClick = { menuFor = f }, Modifier.size(20.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.more), Modifier.size(16.dp)) } })
                    DropdownMenu(menuFor == f, { menuFor = null }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.nl_unpin)) }, leadingIcon = { Icon(Icons.Default.PushPin, null) }, onClick = { menuFor = null; NveFolders.unpin(ctx, f) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.nl_set_save_folder)) }, leadingIcon = { Icon(Icons.Default.SaveAlt, null) }, onClick = { menuFor = null; NveFolders.setDefaultSaveFolder(ctx, f) })
                    }
                }
            }
            AssistChip(onClick = { runCatching { pickTree.launch(null) } }, label = { Text(stringResource(R.string.nl_pin_folder)) }, leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) })
        }
    }
}

/** OpenDocument starting in [start] (a pinned folder). */
private class OpenDocumentAt : ActivityResultContracts.OpenDocument() {
    var start: Uri? = null
    override fun createIntent(context: Context, input: Array<String>): Intent = super.createIntent(context, input).also { i -> start?.let { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) } }
}

/** Extra actions of the Home document sheet: copy path, open in a new window, save a copy to the default folder. */
@Composable
fun NveDocMenuExtras(d: RecentDoc, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val f = remember(d.uri) { Uri.parse(d.uri).path?.let(::File) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("path", f?.absolutePath ?: d.uri))
            Toast.makeText(ctx, ctx.getString(R.string.nl_path_copied), Toast.LENGTH_SHORT).show(); onDismiss()
        }, label = { Text(stringResource(R.string.nl_copy_path)) }, leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)) })
        if (f != null && f.exists()) {
            AssistChip(onClick = { NveWindows.openInNewWindow(ctx, f); onDismiss() }, label = { Text(stringResource(R.string.nl_new_window)) }, leadingIcon = { Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp)) })
            if (NveFolders.defaultSaveFolder(ctx) != null) AssistChip(onClick = {
                scope.launch { val ok = NveFolders.saveCopy(ctx, f) != null; Toast.makeText(ctx, ctx.getString(if (ok) R.string.nl_saved_to_folder else R.string.nl_save_failed), Toast.LENGTH_SHORT).show(); onDismiss() }
            }, label = { Text(stringResource(R.string.nl_save_to_folder)) }, leadingIcon = { Icon(Icons.Default.SaveAlt, null, Modifier.size(16.dp)) })
        }
    }
}
