package com.smartdocs.editor.ui.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DeviceFile
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.home.ThumbBox
import com.smartdocs.editor.ui.home.kindOf
import com.smartdocs.editor.ui.home.rememberFileThumb
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    count: Int, allPdf: Boolean,
    onClose: () -> Unit, onSelectAll: () -> Unit, onShare: () -> Unit, onZip: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit, onMerge: () -> Unit,
    onCopy: (() -> Unit)? = null,
    onConvert: (() -> Unit)? = null,
) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.ds_selected_count, count), style = MaterialTheme.typography.titleLarge) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer, navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer),
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, stringResource(R.string.select_all)) }
            IconButton(onClick = onShare) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
            IconButton(onClick = onZip) { Icon(Icons.Default.FolderZip, stringResource(R.string.ds_zip)) }
            IconButton(onClick = onMove) { Icon(Icons.Default.DriveFileMove, stringResource(R.string.move_to)) }
            if (onCopy != null) IconButton(onClick = onCopy) { Icon(Icons.Default.FileCopy, stringResource(R.string.g6_copy_to)) }
            if (allPdf) IconButton(onClick = onMerge) { Icon(Icons.Default.CallMerge, stringResource(R.string.merge)) }
            if (onConvert != null) IconButton(onClick = onConvert) { Icon(Icons.Default.SwapHoriz, stringResource(R.string.fcy_batch_convert)) }   // fc-sync 16b
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.delete)) }
        },
    )
}

/** Path chips from the documents root to the current folder (tap to jump). */
@Composable
internal fun Breadcrumb(folder: File, root: File, onGo: (File) -> Unit) {
    val chain = remember(folder) { generateSequence(folder) { f -> if (f == root) null else f.parentFile }.toList().reversed() }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        items(chain, key = { it.absolutePath }) { f ->
            val last = f == folder
            Surface(onClick = { onGo(f) }, shape = PillShape, color = if (last) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (f == root) { Icon(Icons.Default.Home, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(if (f == root) stringResource(R.string.ds_root) else f.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
            if (!last) Icon(Icons.Default.ChevronLeft, null, Modifier.size(16.dp).padding(start = 4.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SelectBadge(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(24.dp).clip(CircleShape)
            .background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .border(2.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (checked) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary) }
}

private fun meta(f: File, itemsFmt: String): String =
    if (f.isDirectory) itemsFmt.format(f.listFiles()?.size ?: 0)
    else "${f.extension.uppercase()} · ${DocumentRepository.formatSize(f.length())} · ${DocumentRepository.formatDate(f.lastModified())}"

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileRow(
    f: File, checked: Boolean, selecting: Boolean, modifier: Modifier,
    onOpen: () -> Unit, onLongPress: () -> Unit,
    onInfo: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit, onZip: () -> Unit, onMove: () -> Unit, onSelect: () -> Unit,
    onCopy: (() -> Unit)? = null, onUnzip: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val thumb = if (f.isDirectory) null else rememberFileThumb(f)
    val itemsFmt = stringResource(R.string.ds_items_count)
    Row(
        modifier.fillMaxWidth()
            .background(if (checked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ThumbBox(thumb, 46.dp, if (f.isDirectory) 46.dp else 60.dp, kind = kindOf(f))
            androidx.compose.animation.AnimatedVisibility(visible = selecting, modifier = Modifier.align(Alignment.BottomEnd), enter = scaleIn(), exit = scaleOut()) { SelectBadge(checked, Modifier.offset(4.dp, 4.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta(f, itemsFmt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (!selecting) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
            DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.medium) {
                MenuItem(Icons.Default.CheckCircleOutline, stringResource(R.string.ds_select)) { menu = false; onSelect() }
                MenuItem(Icons.Default.Info, stringResource(R.string.file_info)) { menu = false; onInfo() }
                MenuItem(Icons.Default.DriveFileRenameOutline, stringResource(R.string.rename)) { menu = false; onRename() }
                if (!f.isDirectory) MenuItem(Icons.Default.Share, stringResource(R.string.share)) { menu = false; onShare() }
                MenuItem(Icons.Default.FolderZip, stringResource(R.string.ds_zip)) { menu = false; onZip() }
                if (onUnzip != null && f.isFile && f.extension.equals("zip", true)) MenuItem(Icons.Default.Unarchive, stringResource(R.string.g6_unzip)) { menu = false; onUnzip() }
                MenuItem(Icons.Default.DriveFileMove, stringResource(R.string.move_to)) { menu = false; onMove() }
                if (onCopy != null) MenuItem(Icons.Default.FileCopy, stringResource(R.string.g6_copy_to)) { menu = false; onCopy() }
                MenuItem(Icons.Default.DeleteOutline, stringResource(R.string.delete)) { menu = false; onDelete() }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(text) }, onClick = onClick, leadingIcon = { Icon(icon, null) })

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileGridItem(f: File, checked: Boolean, selecting: Boolean, modifier: Modifier, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val thumb = if (f.isDirectory) null else rememberFileThumb(f)
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress).padding(8.dp),
    ) {
        Box {
            ThumbBox(thumb, 0.dp, 0.dp, kind = kindOf(f), modifier = Modifier.fillMaxWidth().aspectRatio(if (f.isDirectory) 1.2f else 0.78f))
            androidx.compose.animation.AnimatedVisibility(visible = selecting, modifier = Modifier.align(Alignment.TopStart).padding(6.dp), enter = scaleIn(), exit = scaleOut()) { SelectBadge(checked) }
        }
        Spacer(Modifier.height(6.dp))
        Text(f.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
        Text(if (f.isDirectory) stringResource(R.string.ds_items_count).format(f.listFiles()?.size ?: 0) else DocumentRepository.formatSize(f.length()),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** MediaStore / SAF results (content URIs). */
@Composable
internal fun DeviceList(files: List<DeviceFile>, query: String, sort: Sort, grid: Boolean, scanning: Boolean, nav: NavHostController, empty: @Composable () -> Unit) {
    val list = files.filter { query.isBlank() || it.name.contains(query, true) }.let { l ->
        when (sort) { Sort.NAME -> l.sortedBy { it.name.lowercase() }; Sort.TIME -> l.sortedByDescending { it.modified }; Sort.SIZE -> l.sortedByDescending { it.size } }
    }
    if (list.isEmpty()) { if (!scanning) empty(); return }
    if (grid) LazyVerticalGrid(GridCells.Adaptive(132.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.uri.toString() }) { d ->
            val thumb = deviceThumb(d)
            Column(Modifier.animateItem().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable { nav.navigate(Routes.reader(d.uri.toString())) }.padding(8.dp)) {
                ThumbBox(thumb, 0.dp, 0.dp, kind = com.smartdocs.editor.ui.home.kindOfName(d.name), modifier = Modifier.fillMaxWidth().aspectRatio(0.78f))
                Spacer(Modifier.height(6.dp))
                Text(d.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
                Text(DocumentRepository.formatSize(d.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { it.uri.toString() }) { d ->
            val thumb = deviceThumb(d)
            Row(
                Modifier.animateItem().fillMaxWidth().clickable { nav.navigate(Routes.reader(d.uri.toString())) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThumbBox(thumb, 46.dp, 60.dp, kind = com.smartdocs.editor.ui.home.kindOfName(d.name))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${DocumentRepository.formatSize(d.size)} · ${DocumentRepository.formatDate(d.modified)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** Thumbnails only for files we can open directly (own folder / readable path); content URIs show a tile. */
@Composable
private fun deviceThumb(d: DeviceFile): String? {
    val f = remember(d.uri) {
        when {
            d.uri.scheme == "file" -> File(d.uri.path ?: "")
            d.path.startsWith("/") -> File(d.path).takeIf { it.canRead() }
            else -> null
        }
    }
    return if (f != null && f.exists()) rememberFileThumb(f) else null
}

@Composable
internal fun FolderChip(label: String, onChange: () -> Unit, onForget: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onChange) { Text(stringResource(R.string.fl_pick_folder)) }
            TextButton(onClick = onForget) { Text(stringResource(R.string.fl_forget_folder)) }
        }
    }
}

@Composable
internal fun FileInfoDialog(f: File, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Info, null) }, title = { Text(stringResource(R.string.file_info)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoLine(stringResource(R.string.file_name), f.name)
            InfoLine(stringResource(R.string.file_path), f.absolutePath)
            if (!f.isDirectory) InfoLine(stringResource(R.string.file_size), DocumentRepository.formatSize(f.length()))
            InfoLine(stringResource(R.string.file_modified), DocumentRepository.formatDate(f.lastModified()))
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } })
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Destination picker: every folder under the documents root (excluding the moved folders themselves). */
@Composable
internal fun MoveDialog(root: File, moving: List<File>, onDismiss: () -> Unit, title: String? = null, onMove: (File) -> Unit) {
    val folders = remember(root, moving) {
        val excluded = moving.filter { it.isDirectory }.map { it.absolutePath }
        root.walkTopDown().maxDepth(6).filter { it.isDirectory }
            .filter { d -> excluded.none { d.absolutePath == it || d.absolutePath.startsWith("$it/") } }
            .take(300).toList()
    }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(if (title != null) Icons.Default.FileCopy else Icons.Default.DriveFileMove, null) }, title = { Text(title ?: stringResource(R.string.move_to)) }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(folders, key = { it.absolutePath }) { d ->
                val depth = d.absolutePath.removePrefix(root.absolutePath).count { it == '/' }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onMove(d) }.padding(start = (8 + depth * 16).dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (d == root) Icons.Default.Home else Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(if (d == root) stringResource(R.string.ds_root) else d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

