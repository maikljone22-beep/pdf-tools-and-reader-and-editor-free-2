package com.smartdocs.editor.ui.gaps6

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.RecentDoc
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.mimeFor
import com.smartdocs.editor.ui.home.ThumbBox
import com.smartdocs.editor.ui.home.kindOf
import com.smartdocs.editor.ui.home.rememberDocThumb
import com.smartdocs.editor.ui.home.rememberFileThumb
import java.io.File

/** Quick tools offered in the detail pane for a PDF. */
private val detailTools = listOf(
    Triple("organize", R.string.tool_organize, Icons.Default.AutoAwesomeMotion),
    Triple("compress", R.string.tool_compress, Icons.Default.Compress),
    Triple("sign", R.string.tool_sign, Icons.Default.Draw),
    Triple("ocr", R.string.tool_ocr, Icons.Default.TextFields),
    Triple("protect", R.string.tool_protect, Icons.Default.Lock),
    Triple("convert", R.string.tool_convert, Icons.Default.SwapHoriz),
)

/** Detail pane of Home on large / unfolded screens: the selected document, or the tool wizard when none. */
@Composable
fun HomeDetailPane(doc: RecentDoc?, nav: NavHostController, onTool: (String) -> Unit) {
    if (doc == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 16.dp)) {
            ToolWizardContent(onTool = onTool)
        }
        return
    }
    val ctx = LocalContext.current
    val app = LocalApp.current
    val file = remember(doc.uri) { Uri.parse(doc.uri).path?.let { File(it) } }
    DetailScaffold(
        thumb = { ThumbBox(rememberDocThumb(doc), 180.dp, 240.dp) },
        title = doc.name,
        meta = buildList {
            if (doc.pageCount > 0) add(stringResource(R.string.g6_pages_count, doc.pageCount))
            add(DocumentRepository.formatSize(doc.size))
            add(DocumentRepository.formatDate(doc.lastOpened))
        }.joinToString(" · "),
        onOpen = { nav.navigate(Routes.reader(doc.uri, doc.lastPage)) },
        onShare = file?.takeIf { it.exists() }?.let { f -> { runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(f)), null)) } } },
        tools = if (doc.name.endsWith(".pdf", true)) detailTools else emptyList(),
        onTool = { id -> nav.navigate(Routes.tool(id, doc.uri)) },
    )
}

/** Detail pane of the Files tab: the selected file / folder. */
@Composable
fun FileDetailPane(file: File?, nav: NavHostController, onOpenFolder: (File) -> Unit) {
    if (file == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.TouchApp, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.g6_pick_file_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        return
    }
    val ctx = LocalContext.current
    val app = LocalApp.current
    val uri = Uri.fromFile(file).toString()
    val isPdf = file.extension.equals("pdf", true)
    DetailScaffold(
        thumb = { ThumbBox(if (file.isDirectory) null else rememberFileThumb(file), 180.dp, if (file.isDirectory) 180.dp else 240.dp, kind = kindOf(file)) },
        title = file.name,
        meta = if (file.isDirectory) stringResource(R.string.g6_items, file.listFiles()?.size ?: 0)
            else "${file.extension.uppercase()} · ${DocumentRepository.formatSize(file.length())} · ${DocumentRepository.formatDate(file.lastModified())}",
        onOpen = { if (file.isDirectory) onOpenFolder(file) else nav.navigate(Routes.reader(uri)) },
        onShare = if (file.isFile) ({ runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(file), mimeFor(file.extension)), null)) } }) else null,
        tools = if (isPdf) detailTools else emptyList(),
        onTool = { id -> nav.navigate(Routes.tool(id, uri)) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailScaffold(
    thumb: @Composable () -> Unit, title: String, meta: String,
    onOpen: () -> Unit, onShare: (() -> Unit)?, tools: List<Triple<String, Int, ImageVector>>, onTool: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        thumb()
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 3)
        Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpen, shape = PillShape) { Icon(Icons.AutoMirrored.Filled.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.open)) }
            if (onShare != null) OutlinedButton(onClick = onShare, shape = PillShape) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.share)) }
        }
        if (tools.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tools.forEach { (id, label, icon) ->
                    AssistChip(onClick = { onTool(id) }, label = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) }, shape = PillShape)
                }
            }
        }
    }
}
