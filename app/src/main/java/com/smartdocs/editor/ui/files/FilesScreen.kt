package com.smartdocs.editor.ui.files

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DeviceFile
import com.smartdocs.editor.core.files.ZipUtil
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class Sort { NAME, TIME, SIZE }

private const val PREFS_FILES = "files"
private const val PREF_TREE = "tree_uri"
private const val PREF_GRID = "grid"
private const val PREF_TAB = "tab"

/**
 * PDFs inside a user-chosen SAF tree (recursive, bounded). MediaStore.Files only returns this app's own
 * PDFs on API 33+ (READ_MEDIA_IMAGES does not cover documents), so this is the way to list other PDFs.
 */
private suspend fun scanTree(ctx: android.content.Context, tree: Uri, maxFiles: Int = 2000, maxDepth: Int = 8): List<DeviceFile> = withContext(Dispatchers.IO) {
    val out = ArrayList<DeviceFile>()
    val root = runCatching { DocumentFile.fromTreeUri(ctx, tree) }.getOrNull() ?: return@withContext out
    fun walk(dir: DocumentFile, depth: Int) {
        if (depth > maxDepth || out.size >= maxFiles) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (c in children) {
            if (out.size >= maxFiles) return
            if (c.isDirectory) walk(c, depth + 1)
            else if (c.type == "application/pdf" || com.smartdocs.editor.core.openall.FileSniffer.byExtension(c.name.orEmpty()) != com.smartdocs.editor.core.openall.FType.UNKNOWN) {   // open-all: every known type
                out += DeviceFile(c.uri, c.name ?: "document.pdf", c.length(), c.lastModified(), c.uri.toString())
            }
        }
    }
    walk(root, 0)
    out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(nav: NavHostController) {
    val app = LocalApp.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.applicationContext.getSharedPreferences(PREFS_FILES, 0) }
    var tab by rememberSaveable { mutableIntStateOf(prefs.getInt(PREF_TAB, 0)) }
    var query by rememberSaveable { mutableStateOf("") }
    var grid by remember { mutableStateOf(prefs.getBoolean(PREF_GRID, false)) }
    var sort by remember { mutableStateOf(Sort.TIME) }
    var sortMenu by remember { mutableStateOf(false) }
    var media by remember { mutableStateOf<List<DeviceFile>>(emptyList()) }
    var treeFiles by remember { mutableStateOf<List<DeviceFile>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf(app.documents.docsDir) }
    var folderFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selecting = selected.isNotEmpty()
    var newFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var infoTarget by remember { mutableStateOf<File?>(null) }
    var deleteTargets by remember { mutableStateOf<List<File>?>(null) }
    var permanentTargets by remember { mutableStateOf<List<File>?>(null) }
    var moveTargets by remember { mutableStateOf<List<File>?>(null) }
    // gaps6: copy to folder, unzip, list-detail on large / unfolded screens
    var copyTargets by remember { mutableStateOf<List<File>?>(null) }
    var detailFile by remember { mutableStateOf<File?>(null) }
    val fold = com.smartdocs.editor.ui.gaps6.rememberFoldState()
    val snackbar = remember { SnackbarHostState() }
    val pick = rememberPdfPicker(mimes = arrayOf("*/*")) { f -> f.firstOrNull()?.let { nav.navigate(Routes.reader(Uri.fromFile(it).toString())) } }   // open-all: any file type (opened in-app by content)

    fun refreshFolder() { folderFiles = app.documents.listFolder(folder); selected = selected.filter { p -> folderFiles.any { it.absolutePath == p } }.toSet() }
    LaunchedEffect(folder) { refreshFolder() }

    var treeUri by remember { mutableStateOf(prefs.getString(PREF_TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }) }

    suspend fun doScan() {
        scanning = true
        media = runCatching { app.documents.scanDevicePdfs() }.getOrDefault(emptyList())
        treeFiles = treeUri?.let { runCatching { scanTree(ctx.applicationContext, it) }.getOrDefault(emptyList()) } ?: emptyList()
        scanning = false
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        scope.launch { doScan() }
    }
    fun scanDevice() {
        // READ_EXTERNAL_STORAGE is only meaningful below API 33; above that MediaStore.Files needs no permission
        // for our own files and no runtime permission grants access to other apps' PDFs (use the folder picker).
        if (Build.VERSION.SDK_INT < 33 && ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }.onFailure { scope.launch { doScan() } }
        } else scope.launch { doScan() }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            prefs.edit().putString(PREF_TREE, uri.toString()).apply()
            treeUri = uri
            scope.launch { doScan() }
        }
    }
    LaunchedEffect(tab) {
        prefs.edit().putInt(PREF_TAB, tab).apply()
        if (tab != 0) selected = emptySet()
        if (tab != 0 && media.isEmpty() && treeFiles.isEmpty()) scanDevice()
    }
    BackHandler(enabled = selecting) { selected = emptySet() }
    BackHandler(enabled = !selecting && tab == 0 && folder != app.documents.docsDir) { folder = folder.parentFile ?: app.documents.docsDir }

    fun openFile(f: File) { if (f.isDirectory) folder = f else if (fold.twoPane) detailFile = f else nav.navigate(Routes.reader(Uri.fromFile(f).toString())) }
    fun toggle(f: File) { selected = if (f.absolutePath in selected) selected - f.absolutePath else selected + f.absolutePath }
    val selectedFiles = folderFiles.filter { it.absolutePath in selected }
    fun share(files: List<File>) {
        val fs = files.filter { it.isFile }; if (fs.isEmpty()) return
        val mime = fs.map { mimeFor(it.extension) }.distinct().singleOrNull() ?: "*/*"
        runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(fs, mime), null)) }
    }
    val zipDoneFmt = stringResource(R.string.ds_zip_done)
    fun zip(files: List<File>) {
        if (files.isEmpty()) return
        scope.launch {
            val out = app.documents.uniqueFile(folder, if (files.size == 1) files[0].nameWithoutExtension else "archive", "zip")
            val ok = runCatching { ZipUtil.zip(files, out) }.isSuccess
            selected = emptySet(); refreshFolder()
            if (ok) {
                val r = snackbar.showSnackbar(zipDoneFmt.format(out.name), actionLabel = ctx.getString(R.string.share), duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed) share(listOf(out))
            }
        }
    }

    val unzipDoneFmt = stringResource(R.string.g6_unzip_done)
    val unzipFailed = stringResource(R.string.g6_unzip_failed)
    val openLabel = stringResource(R.string.open)
    fun unzip(f: File) {
        scope.launch {
            val target = com.smartdocs.editor.core.files.FileCopy.unzipTarget(f)
            val extracted = runCatching { ZipUtil.unzip(f, target) }.getOrNull()
            refreshFolder()
            if (extracted == null) { target.deleteRecursively(); snackbar.showSnackbar(unzipFailed); return@launch }
            val r = snackbar.showSnackbar(unzipDoneFmt.format(extracted.size, target.name), actionLabel = openLabel, duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) folder = target
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                AnimatedContent(selecting, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "topbar") { sel ->
                    if (sel) SelectionTopBar(
                        count = selected.size,
                        allPdf = selectedFiles.size >= 2 && selectedFiles.all { it.isFile && it.extension.equals("pdf", true) },
                        onClose = { selected = emptySet() },
                        onSelectAll = { selected = if (selected.size == folderFiles.size) emptySet() else folderFiles.map { it.absolutePath }.toSet() },
                        onShare = { share(selectedFiles) },
                        onZip = { zip(selectedFiles) },
                        onMove = { moveTargets = selectedFiles },
                        onCopy = { copyTargets = selectedFiles },
                        onDelete = { deleteTargets = selectedFiles },
                        onMerge = { val first = selectedFiles.first(); selected = emptySet(); nav.navigate(Routes.tool("merge", Uri.fromFile(first).toString())) },
                    ) else TopAppBar(
                        title = { Text(stringResource(R.string.nav_files), style = MaterialTheme.typography.headlineSmall) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                        actions = {
                            IconButton(onClick = { grid = !grid; prefs.edit().putBoolean(PREF_GRID, grid).apply() }) {
                                Icon(if (grid) Icons.Default.ViewList else Icons.Default.GridView, stringResource(if (grid) R.string.view_list else R.string.view_grid))
                            }
                            Box {
                                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Default.Sort, stringResource(R.string.sort_by)) }
                                DropdownMenu(sortMenu, { sortMenu = false }, shape = MaterialTheme.shapes.medium) {
                                    listOf(Sort.NAME to R.string.sort_name, Sort.TIME to R.string.sort_time, Sort.SIZE to R.string.sort_size).forEach { (s, l) ->
                                        DropdownMenuItem(text = { Text(stringResource(l)) }, onClick = { sort = s; sortMenu = false },
                                            trailingIcon = { if (sort == s) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) })
                                    }
                                }
                            }
                            IconButton(onClick = pick) { Icon(Icons.Default.FolderOpen, stringResource(R.string.open_file)) }
                        },
                    )
                }
                TextField(
                    query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text(stringResource(R.string.search)) }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } },
                    singleLine = true, shape = PillShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val labels = listOf(R.string.ds_tab_device, R.string.all_pdfs, R.string.ds_tab_folders)
                    labels.forEachIndexed { i, l ->
                        SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, labels.size)) {
                            Text(stringResource(l), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (scanning && tab != 0) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = {
            if (tab == 0 && !selecting) ExtendedFloatingActionButton(
                onClick = { newFolder = true }, icon = { Icon(Icons.Default.CreateNewFolder, null) }, text = { Text(stringResource(R.string.new_folder)) },
            )
        },
    ) { pad ->
        val filesBody: @Composable (Modifier) -> Unit = { bodyModifier ->
        Crossfade(tab, bodyModifier.fillMaxSize(), label = "filesTab") { t ->
            when (t) {
                0 -> Column(Modifier.fillMaxSize()) {
                    Breadcrumb(folder, app.documents.docsDir) { folder = it }
                    val list = folderFiles.filter { query.isBlank() || it.name.contains(query, true) }.let { l ->
                        when (sort) { Sort.NAME -> l.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })); Sort.TIME -> l.sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified() })); Sort.SIZE -> l.sortedWith(compareBy({ !it.isDirectory }, { -it.length() })) }
                    }
                    if (list.isEmpty()) EmptyIllustration(
                        if (query.isBlank()) R.drawable.ds_empty_folder else R.drawable.ds_empty_search,
                        stringResource(if (query.isBlank()) R.string.no_files else R.string.ds_no_results),
                        if (query.isBlank()) stringResource(R.string.ds_folder_empty_sub) else null,
                        action = { if (query.isBlank()) Button(onClick = pick, shape = PillShape) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.open_file)) } },
                    )
                    else if (grid) LazyVerticalGrid(GridCells.Adaptive(132.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list, key = { it.absolutePath }) { f ->
                            FileGridItem(f, f.absolutePath in selected, selecting, Modifier.animateItem(),
                                onOpen = { if (selecting) toggle(f) else openFile(f) }, onLongPress = { toggle(f) })
                        }
                    } else LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                        items(list, key = { it.absolutePath }) { f ->
                            FileRow(
                                f, f.absolutePath in selected, selecting, Modifier.animateItem(),
                                onOpen = { if (selecting) toggle(f) else openFile(f) }, onLongPress = { toggle(f) },
                                onInfo = { infoTarget = f }, onRename = { renameTarget = f }, onDelete = { deleteTargets = listOf(f) },
                                onShare = { if (f.extension.equals("pdf", true)) nav.navigate(com.smartdocs.editor.ui.share5.Share5Routes.share(Uri.fromFile(f).toString())) else share(listOf(f)) },   // share6: single-PDF share sheet
                                onZip = { zip(listOf(f)) }, onMove = { moveTargets = listOf(f) }, onSelect = { toggle(f) },
                                onCopy = { copyTargets = listOf(f) }, onUnzip = { unzip(f) },
                            )
                        }
                    }
                }
                1 -> {
                    val seen = HashSet<String>()
                    val all = (treeFiles + media).filter { seen.add(it.uri.toString()) }
                    DeviceList(all, query, sort, grid, scanning, nav,
                        empty = {
                            EmptyIllustration(R.drawable.ds_empty_docs, stringResource(R.string.no_files), if (Build.VERSION.SDK_INT >= 33) stringResource(R.string.fl_folder_hint) else null,
                                action = { Button(onClick = { scanDevice() }, shape = PillShape) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.search)) } })
                        })
                }
                else -> Column(Modifier.fillMaxSize()) {
                    val tu = treeUri
                    // folder picker row: the only way to list PDFs outside this app on API 33+
                    if (tu != null) FolderChip(
                        stringResource(R.string.fl_chosen_folder, tu.lastPathSegment?.substringAfterLast(':')?.ifBlank { tu.lastPathSegment } ?: ""),
                        onChange = { runCatching { treePicker.launch(treeUri) } },
                        onForget = { prefs.edit().remove(PREF_TREE).apply(); treeUri = null; treeFiles = emptyList(); scope.launch { doScan() } },
                    )
                    if (tu == null) EmptyIllustration(
                        R.drawable.ds_empty_folder, stringResource(R.string.ds_no_folder_title), stringResource(R.string.fl_folder_hint),
                        action = { Button(onClick = { runCatching { treePicker.launch(null) } }, shape = PillShape) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.fl_pick_folder)) } },
                    ) else DeviceList(treeFiles, query, sort, grid, scanning, nav,
                        empty = { EmptyIllustration(R.drawable.ds_empty_folder, stringResource(R.string.no_files)) })
                }
            }
        }
        }
        if (fold.twoPane && tab == 0) com.smartdocs.editor.ui.gaps6.TwoPane(fold, Modifier.padding(pad),
            first = { filesBody(Modifier) },
            second = { com.smartdocs.editor.ui.gaps6.FileDetailPane(detailFile?.takeIf { it.exists() }, nav) { dir -> folder = dir; detailFile = null } })
        else filesBody(Modifier.padding(pad))
    }

    // ---- dialogs ----
    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newFolder = false }, title = { Text(stringResource(R.string.new_folder)) }, icon = { Icon(Icons.Default.CreateNewFolder, null) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, shape = MaterialTheme.shapes.medium) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) File(folder, name.trim()).mkdirs(); refreshFolder(); newFolder = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text(stringResource(R.string.cancel)) } })
    }
    renameTarget?.let { f ->
        var name by remember { mutableStateOf(if (f.isDirectory) f.name else f.nameWithoutExtension) }
        var renameError by remember { mutableStateOf<String?>(null) }
        val existsFmt = stringResource(R.string.fx_rename_exists)
        val invalidMsg = stringResource(R.string.fx_rename_invalid)
        val failedMsg = stringResource(R.string.fx_rename_failed)
        AlertDialog(onDismissRequest = { renameTarget = null }, title = { Text(stringResource(R.string.rename)) }, icon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
            text = {
                OutlinedTextField(name, { name = it; renameError = null }, singleLine = true, shape = MaterialTheme.shapes.medium,
                    isError = renameError != null, supportingText = renameError?.let { msg -> { Text(msg) } })
            },
            confirmButton = {
                TextButton(onClick = {
                    // audit DI-04: never replace another file; tell the user why a rename did not happen
                    // audit DI-22: recents, tags, bookmarks, origin and index entry follow the new name
                    scope.launch {
                        when (val r = app.documents.renameDocument(f, name)) {
                            is com.smartdocs.editor.core.files.SafeRename.Result.Ok -> { refreshFolder(); renameTarget = null }
                            is com.smartdocs.editor.core.files.SafeRename.Result.Exists -> renameError = existsFmt.format(r.other.name)
                            com.smartdocs.editor.core.files.SafeRename.Result.Blank, com.smartdocs.editor.core.files.SafeRename.Result.InvalidName -> renameError = invalidMsg
                            com.smartdocs.editor.core.files.SafeRename.Result.Failed -> renameError = failedMsg
                        }
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) } })
    }
    infoTarget?.let { f -> FileInfoDialog(f) { infoTarget = null } }
    deleteTargets?.let { list ->
        AlertDialog(onDismissRequest = { deleteTargets = null }, icon = { Icon(Icons.Default.DeleteOutline, null) },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.confirm_delete) + "\n" + list.joinToString("، ") { it.name }.take(300)) },
            confirmButton = {
                TextButton(onClick = {
                    // audit DI-06: a file that cannot go to the trash is never deleted silently — ask first
                    deleteTargets = null
                    scope.launch {
                        val failed = list.filter { f -> !app.documents.deleteDocument(f, toTrash = true) { com.smartdocs.editor.ui.view5.Trash5.moveToTrash(ctx, it) } }
                        selected = emptySet(); refreshFolder()
                        if (failed.isNotEmpty()) permanentTargets = failed
                    }
                },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTargets = null }) { Text(stringResource(R.string.cancel)) } })
    }
    permanentTargets?.let { list ->
        AlertDialog(onDismissRequest = { permanentTargets = null }, icon = { Icon(Icons.Default.DeleteForever, null) },
            title = { Text(stringResource(R.string.fx_trash_failed_title)) },
            text = { Text(stringResource(R.string.fx_trash_failed_text) + "\n" + list.joinToString("، ") { it.name }.take(300)) },
            confirmButton = {
                TextButton(onClick = { permanentTargets = null; scope.launch { list.forEach { f -> app.documents.deleteDocument(f, toTrash = false) { it.deleteRecursively() } }; refreshFolder() } },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.fx_delete_permanently)) }
            },
            dismissButton = { TextButton(onClick = { permanentTargets = null }) { Text(stringResource(R.string.cancel)) } })
    }
    moveTargets?.let { list ->
        MoveDialog(app.documents.docsDir, list, onDismiss = { moveTargets = null }) { dest ->
            moveTargets = null; selected = emptySet()
            // audit DI-22: the repository moves the file and everything keyed by its path
            scope.launch { list.forEach { f -> app.documents.moveDocument(f, dest) }; refreshFolder() }
        }
    }
    val copyDoneFmt = stringResource(R.string.g6_copy_done)
    copyTargets?.let { list ->
        MoveDialog(app.documents.docsDir, list, onDismiss = { copyTargets = null }, title = stringResource(R.string.g6_copy_to)) { dest ->
            copyTargets = null; selected = emptySet()
            scope.launch {
                val r = withContext(Dispatchers.IO) { com.smartdocs.editor.core.files.FileCopy.copyInto(list, dest) }
                refreshFolder()
                snackbar.showSnackbar(copyDoneFmt.format(r.copied.size, if (dest == app.documents.docsDir) ctx.getString(R.string.ds_root) else dest.name))
            }
        }
    }
}

