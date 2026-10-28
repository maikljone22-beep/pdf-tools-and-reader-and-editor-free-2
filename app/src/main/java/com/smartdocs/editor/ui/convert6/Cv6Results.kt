package com.smartdocs.editor.ui.convert6

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.mimeFor
import com.smartdocs.editor.ui.office.OfficeRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opens a result in the app (open-all: every type has an in-app viewer); the system viewer only if that fails. */
fun openResultFile(nav: NavHostController, ctx: Context, docs: DocumentRepository, f: File) {
    val uri = Uri.fromFile(f).toString()
    if (f.isFile && runCatching { nav.navigate(Routes.reader(uri)) }.isSuccess) return
    runCatching {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply { setDataAndType(docs.shareUri(f), mimeFor(f.extension)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, null))
    }
}

/** Common mime of several results (for one share intent). */
fun commonMime(files: List<File>): String {
    val m = files.map { mimeFor(it.extension) }.distinct()
    return when {
        m.size == 1 -> m[0]
        m.all { it.startsWith("image/") } -> "image/*"
        else -> "*/*"
    }
}

/** Results of a run that produced several files: open / share each, share all, pack all into one ZIP. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Cv6ResultsSheet(files: List<File>, info: String?, nav: NavHostController, onDismiss: () -> Unit, onZip: (File) -> Unit, onError: (String) -> Unit = {}) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var zipping by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.cv6_results, files.size), style = MaterialTheme.typography.titleLarge)
                    Text(DocumentRepository.formatSize(files.sumOf { it.length() }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!info.isNullOrBlank()) Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(files, key = { it.absolutePath }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { onDismiss(); openResultFile(nav, ctx, app.documents, f) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (f.extension.equals("pdf", true)) Icons.Default.PictureAsPdf else Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(DocumentRepository.formatSize(f.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(f), mimeFor(f.extension)), null)) } }) {
                            Icon(Icons.Default.Share, stringResource(R.string.share))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(files, commonMime(files)), null)) } }, Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cv6_share_all))
                }
                OutlinedButton(enabled = !zipping, onClick = {
                    zipping = true
                    scope.launch {
                        // a full disk or a file error is a message, not a crash; duplicate names are numbered (A-L9 / B27 / C30)
                        val z = app.documents.uniqueFile(app.documents.docsDir, files.first().nameWithoutExtension + "_all", "zip")
                        val zip = runCatching {
                            withContext(Dispatchers.IO) {
                                val names = com.smartdocs.editor.core.convert6.ImageNaming.dedupe(files.map { it.name })
                                ZipOutputStream(z.outputStream().buffered()).use { zo -> files.forEachIndexed { i, f -> zo.putNextEntry(ZipEntry(names[i])); f.inputStream().use { it.copyTo(zo) }; zo.closeEntry() } }
                                z
                            }
                        }
                        zipping = false
                        zip.onSuccess { onZip(it) }.onFailure { e -> z.delete(); onError(Cv6ErrorText.of(ctx, e)) }
                    }
                }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FolderZip, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.cv6_zip_all)) }
            }
        }
    }
}
