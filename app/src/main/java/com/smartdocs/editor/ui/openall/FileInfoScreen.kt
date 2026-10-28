package com.smartdocs.editor.ui.openall

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.openall.FType
import com.smartdocs.editor.core.openall.FileSniffer
import com.smartdocs.editor.core.openall.HexDump
import com.smartdocs.editor.core.openall.TextDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** Human-readable name of a detected type. */
fun typeLabel(t: FType, ext: String): String = when (t) {
    FType.UNKNOWN -> if (ext.isNotEmpty()) ext.uppercase() else "?"
    FType.IMAGE -> if (ext.isNotEmpty()) ext.uppercase() else "Image"
    FType.AUDIO, FType.VIDEO, FType.CODE, FType.TEXT, FType.FONT -> (if (ext.isNotEmpty()) ext.uppercase() + " · " else "") + t.name.lowercase().replaceFirstChar { it.uppercase() }
    FType.SEVENZ -> "7z"
    else -> t.name
}

@Composable
private fun InfoRows(v: ViewFile) {
    val f = v.file
    val rows = listOfNotNull(
        R.string.oa_info_name to v.name,
        f?.let { R.string.oa_info_size to DocumentRepository.formatSize(it.length()) },
        R.string.oa_info_type to typeLabel(v.type, (f?.extension ?: v.name.substringAfterLast('.', "")).lowercase()),
        R.string.oa_info_mime to FileSniffer.mimeOf(v.type, (f?.extension ?: "").lowercase()),
        f?.let { R.string.oa_info_modified to DocumentRepository.formatDate(it.lastModified()) },
    )
    rows.forEach { (k, value) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(stringResource(k), Modifier.width(120.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun FileInfoDialog(v: ViewFile, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.oa_file_info)) },
        text = { Column { InfoRows(v) } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.oa_close)) } })
}

/**
 * "File info" for unknown types and the formats that cannot be shown (DjVu, CHM, RAR, DWG, WOFF2, iWork):
 * details, the reason, an embedded preview when the file has one, hex + text preview, Open with / Share / Convert.
 */
@Composable
fun FileInfoScreen(nav: NavHostController, v: ViewFile, reason: Int?, preview: Boolean = false) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val f = v.file ?: return
    var asText by remember { mutableStateOf(false) }
    if (asText) { TextViewer(nav, ViewFile(f, FType.TEXT, v.uri, v.name, v.depth)); return }
    val head = remember(f) { FileSniffer.readHead(f, 1024) }
    val previewImg by produceState<android.graphics.Bitmap?>(null, f) {
        if (preview) value = withContext(Dispatchers.IO) {
            runCatching {
                ZipFile(f).use { z ->
                    val e = listOf("preview.jpg", "QuickLook/Thumbnail.jpg", "preview-web.jpg", "preview-micro.jpg").firstNotNullOfOrNull { z.getEntry(it) }
                        ?: return@use null
                    if (e.size > 20L * 1024 * 1024) return@use null
                    z.getInputStream(e).use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()
        }
    }
    val quickLookPdf = remember(f) { if (preview) runCatching { ZipFile(f).use { it.getEntry("QuickLook/Preview.pdf") != null } }.getOrDefault(false) else false }
    ViewerScaffold(nav, v, v.name, convert = if (v.type == FType.UNKNOWN || reason != null) null else { out -> ViewerConvert.toPdf(ctx, v, out) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(v.type), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(if (reason == null) R.string.oa_info_unknown else R.string.oa_info_cannot_show), style = MaterialTheme.typography.titleMedium)
            }
            reason?.let {
                Spacer(Modifier.height(10.dp))
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(it), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            previewImg?.let { b ->
                Spacer(Modifier.height(12.dp))
                Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit)
            }
            if (quickLookPdf) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = {
                    runCatching {
                        val out = File(OpenAll.workDir(ctx, "quicklook"), f.nameWithoutExtension + "_preview.pdf")
                        ZipFile(f).use { z -> z.getInputStream(z.getEntry("QuickLook/Preview.pdf")).use { i -> out.outputStream().use { i.copyTo(it) } } }
                        nav.navigate(OpenAll.route(android.net.Uri.fromFile(out).toString(), v.depth + 1))
                    }
                }) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.oa_open_preview)) }
            }
            Spacer(Modifier.height(12.dp))
            InfoRows(v)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { OpenAll.openWith(ctx, app, f, v.type) }) { Text(stringResource(R.string.oa_open_with)) }
                OutlinedButton(onClick = { OpenAll.share(ctx, app, f, v.type) }) { Text(stringResource(R.string.oa_share)) }
            }
            if (TextDecode.looksText(head) && head.isNotEmpty()) TextButton(onClick = { asText = true }) { Text(stringResource(R.string.oa_open_as_text)) }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.oa_info_hex), style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(HexDump.dump(head, 512), Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            if (TextDecode.looksText(head) && head.isNotEmpty()) {
                Text(stringResource(R.string.oa_info_text), style = MaterialTheme.typography.labelLarge)
                Text(TextDecode.decode(head).text.take(1500), Modifier.padding(vertical = 6.dp), fontSize = 13.sp, textAlign = TextAlign.Start)
            }
        }
    }
}
