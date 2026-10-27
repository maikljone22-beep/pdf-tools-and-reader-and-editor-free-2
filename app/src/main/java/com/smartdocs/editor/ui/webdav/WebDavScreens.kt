package com.smartdocs.editor.ui.webdav

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.cloud.AuthRequiredException
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.webdav.DavClient
import com.smartdocs.editor.core.webdav.DavEntry
import com.smartdocs.editor.core.webdav.DavLink
import com.smartdocs.editor.core.webdav.DavLinks
import com.smartdocs.editor.core.webdav.DavLockedException
import com.smartdocs.editor.core.webdav.DavPlace
import com.smartdocs.editor.core.webdav.DavPlaces
import com.smartdocs.editor.core.webdav.DavXml
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.common.EmptyState
import com.smartdocs.editor.ui.common.IconTile
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.settings.GroupCard
import com.smartdocs.editor.ui.settings.GroupDivider
import com.smartdocs.editor.ui.settings.GroupRow
import com.smartdocs.editor.ui.tools5.rememberAnyFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Routes of the WebDAV places (nav-attach). Register with `webDavRoutes(nav)` inside the NavHost. */
object WebDavRoutes {
    const val BROWSE = "webdav/{id}"
    fun browse(id: String) = "webdav/${Uri.encode(id)}"
}

fun NavGraphBuilder.webDavRoutes(nav: NavHostController) {
    composable(WebDavRoutes.BROWSE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
        WebDavBrowserScreen(nav, e.arguments?.getString("id").orEmpty())
    }
}

/** Cloud tab card "WebDAV places" (Nitro "Add Place"): the saved places, edit / remove, and "Add place". */
@Composable
fun WebDavPlacesCard(nav: NavHostController) {
    val ctx = LocalContext.current
    val tiles = Brand.tiles
    var places by remember { mutableStateOf(DavPlaces.all(ctx)) }
    var editing by remember { mutableStateOf<DavPlace?>(null) }
    var adding by remember { mutableStateOf(false) }
    GroupCard(stringResource(R.string.nat_dav_places)) {
        places.forEach { p ->
            GroupRow(p.label, p.url, icon = Icons.Default.Dns, tile = tiles.teal, onClick = { nav.navigate(WebDavRoutes.browse(p.id)) },
                trailing = { IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, stringResource(R.string.nat_dav_edit)) } })
            GroupDivider()
        }
        GroupRow(stringResource(R.string.nat_dav_add), stringResource(R.string.nat_dav_add_sub), icon = Icons.Default.AddLink, tile = tiles.blue, onClick = { adding = true })
    }
    if (adding || editing != null) PlaceDialog(editing, onDismiss = { adding = false; editing = null }) { places = DavPlaces.all(ctx); adding = false; editing = null }
}

/** Add / edit a place: label, URL, user, password (kept encrypted), with a connection test. */
@Composable
private fun PlaceDialog(place: DavPlace?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(place?.label.orEmpty()) }
    var url by remember { mutableStateOf(place?.url.orEmpty()) }
    var user by remember { mutableStateOf(place?.user.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val clean = DavXml.cleanPlaceUrl(url)
    val pw: String? = if (place != null && password.isEmpty()) null else password

    fun test() {
        val u = clean ?: return
        scope.launch {
            testing = true; result = null
            result = runCatching {
                withContext(Dispatchers.IO) {
                    val c = DavClient(user.trim(), pw ?: place?.let { DavPlaces.password(it) }.orEmpty())
                    val caps = runCatching { c.options(u) }.getOrNull()
                    val n = c.list(u).size
                    ctx.getString(R.string.nat_dav_test_ok, n) + if (caps?.locking == true) "\n" + ctx.getString(R.string.nat_dav_test_locks) else ""
                }
            }.getOrElse { e -> if (e is AuthRequiredException) ctx.getString(R.string.nat_dav_auth_failed) else (e.message ?: e.toString()) }
            testing = false
        }
    }

    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (place == null) R.string.nat_dav_add else R.string.nat_dav_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nat_dav_label)) })
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nat_dav_url)) },
                    placeholder = { Text("https://cloud.example.com/remote.php/dav/files/me/") }, isError = url.isNotBlank() && clean == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                if (clean?.startsWith("http://") == true) Text(stringResource(R.string.nat_dav_http_warn), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nat_dav_user)) })
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.nat_dav_password)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { if (place != null && place.secret.isNotEmpty()) Text(stringResource(R.string.nat_dav_password_keep)) })
                Text(stringResource(R.string.nat_dav_secure_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { test() }, enabled = clean != null && !testing) { Text(stringResource(R.string.nat_dav_test)) }
                    if (testing) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    if (place != null) { Spacer(Modifier.weight(1f)); TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } }
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = clean != null, onClick = { DavPlaces.save(ctx, place?.id, label.trim(), clean!!, user.trim(), pw); onSaved() }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })

    if (confirmRemove && place != null) AlertDialog(onDismissRequest = { confirmRemove = false },
        title = { Text(stringResource(R.string.nat_dav_remove_q, place.label)) },
        confirmButton = { TextButton(onClick = { confirmRemove = false; DavPlaces.remove(ctx, place.id); onSaved() }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } })
}

/** Local working copy of [e]: <docs>/Cloud/webdav/<place>/<path below the place root>. */
private fun localFor(ctx: android.content.Context, place: DavPlace, e: DavEntry): File {
    val rel = DavXml.relativePath(place.url, e.url).split('/').filter { it.isNotBlank() && it != ".." }
        .map { it.replace(Regex("""[\\:*?"<>|]"""), "_") }
    return File(DavPlaces.cacheDir(ctx, place), rel.joinToString("/").ifEmpty { "file" })
}

/** Route "webdav/{id}": browse a WebDAV place, open (PDFs in the reader, saved back on save), upload, folders, locks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavBrowserScreen(nav: NavHostController, placeId: String) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val tiles = Brand.tiles
    val place = remember(placeId) { DavPlaces.get(ctx, placeId) }
    if (place == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val client = remember(place) { DavClient(place) }
    var folder by remember { mutableStateOf(place.url) }
    var items by remember { mutableStateOf<List<DavEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var authNeeded by remember { mutableStateOf(false) }
    var canLock by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<String, Float?>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var linksVersion by remember { mutableIntStateOf(0) }
    var newFolder by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<DavEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<DavEntry?>(null) }
    var overwrite by remember { mutableStateOf<File?>(null) }
    val busyOpen = stringResource(R.string.ac_downloading)
    val busyUpload = stringResource(R.string.ac_uploading)

    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    suspend fun <T> guard(block: suspend () -> T): T? = try { error = null; withContext(Dispatchers.IO) { block() } }
        catch (e: AuthRequiredException) { authNeeded = true; null }
        catch (e: DavLockedException) { toast(ctx.getString(R.string.nat_dav_locked_other)); null }
        catch (e: Exception) { error = e.message ?: e.toString(); null }

    LaunchedEffect(Unit) {
        canLock = withContext(Dispatchers.IO) { runCatching { client.options(place.url).locking }.getOrDefault(false) }
        withContext(Dispatchers.IO) { DavLinks.resumePending(ctx) }
    }
    LaunchedEffect(folder, reload) {
        loading = true
        items = guard { client.list(folder) } ?: items.takeIf { error == null }.orEmpty()
        loading = false
    }
    LaunchedEffect(Unit) {
        DavLinks.events.collect { e ->
            linksVersion++
            if (e.error == null) { toast(ctx.getString(R.string.ac_uploaded)); reload++ } else toast(ctx.getString(R.string.ac_upload_failed, e.error))
        }
    }

    fun mimeOf(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "*/*"

    fun open(e: DavEntry, checkOut: Boolean = false) {
        if (e.isFolder) { folder = e.url; return }
        scope.launch {
            progress = busyOpen to 0f
            val local = localFor(ctx, place, e)
            val ok = guard {
                val link = DavLinks.get(ctx, local)
                when (DavLinks.openAction(local.exists(), link, e.etag)) {
                    DavLinks.OpenAction.DOWNLOAD -> {
                        val etag = client.download(e.url, local) { d, t -> if (t > 0) progress = busyOpen to d.toFloat() / t } ?: e.etag
                        DavLinks.set(ctx, local, DavLink(place.id, e.url, etag, canLock = canLock, lock = link?.lock))
                    }
                    DavLinks.OpenAction.REUSE -> Unit
                    DavLinks.OpenAction.KEEP_LOCAL -> withContext(Dispatchers.Main) { toast(ctx.getString(R.string.fix7_opened_local)) }
                }
                if (checkOut) DavLinks.checkOut(ctx, local)
                true
            }
            progress = null
            linksVersion++
            if (ok == true) {
                if (local.isFile) nav.navigate(Routes.reader(Uri.fromFile(local).toString()))   // open-all: any type opens in-app
                else runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(app.documents.shareUri(local), e.contentType ?: mimeOf(e.name))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { toast(ctx.getString(R.string.nat_no_app)) }
            }
        }
    }

    fun uploadFile(f: File, replace: Boolean) {
        scope.launch {
            progress = busyUpload to null
            val target = DavXml.childUrl(folder, f.name)
            val done = guard { client.put(target, f, mimeOf(f.name), mustNotExist = !replace); true }
            progress = null
            if (done == true) { toast(ctx.getString(R.string.ac_uploaded)); reload++ }
        }
    }
    val upload = rememberAnyFilePicker(false, arrayOf("*/*")) { files ->
        val f = files.firstOrNull() ?: return@rememberAnyFilePicker
        if (items.any { !it.isFolder && it.name == f.name }) overwrite = f else uploadFile(f, replace = false)
    }

    val crumbs = DavXml.crumbs(place.url, folder, place.label)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = { if (crumbs.size > 1) folder = crumbs[crumbs.size - 2].second else nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(crumbs.last().first, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.ac_refresh)) }
                    IconButton(onClick = { newFolder = true }) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.new_folder)) }
                },
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = upload, icon = { Icon(Icons.Default.Upload, null) }, text = { Text(stringResource(R.string.ac_upload)) }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (crumbs.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(crumbs) { _, (name, u) -> AssistChip(onClick = { folder = u }, label = { Text(name, maxLines = 1) }) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                authNeeded -> EmptyState(Icons.Default.Lock, stringResource(R.string.nat_dav_auth_failed)) {
                    Button(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.nat_dav_edit)) }
                }
                error != null && items.isEmpty() -> EmptyState(Icons.Default.CloudOff, error ?: "") { OutlinedButton(onClick = { reload++ }) { Text(stringResource(R.string.ac_retry)) } }
                !loading && items.isEmpty() -> EmptyState(Icons.Default.FolderOpen, stringResource(R.string.ac_folder_empty))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp)) {
                    items(items, key = { it.url }) { e ->
                        val link = remember(e.url, linksVersion) { DavLinks.forUrl(ctx, e.url)?.second }
                        ListItem(
                            modifier = Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp)).clickable { open(e) },
                            leadingContent = {
                                when {
                                    e.isFolder -> IconTile(Icons.Default.Folder, tiles.amber, size = 40.dp, iconSize = 22.dp)
                                    e.name.endsWith(".pdf", true) -> IconTile(Icons.Default.PictureAsPdf, tiles.rose, size = 40.dp, iconSize = 22.dp)
                                    else -> IconTile(Icons.Default.InsertDriveFile, tiles.blue, size = 40.dp, iconSize = 22.dp)
                                }
                            },
                            headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                val parts = listOfNotNull(
                                    e.size.takeIf { !e.isFolder && it > 0 }?.let { DocumentRepository.formatSize(it) },
                                    e.modified.takeIf { it > 0 }?.let { DocumentRepository.formatDate(it) },
                                    when {
                                        link?.lock != null -> stringResource(R.string.nat_dav_checked_out)
                                        e.lockedBy != null -> stringResource(R.string.nat_dav_locked_other)
                                        else -> null
                                    },
                                    if (link?.dirty == true) stringResource(R.string.nat_dav_pending) else null,
                                )
                                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            },
                            trailingContent = { if (!e.isFolder) IconButton(onClick = { menuFor = e }) { Icon(Icons.Default.MoreVert, stringResource(R.string.nat_actions)) } else Icon(Icons.Default.ChevronLeft, null) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        )
                    }
                }
            }
        }
    }

    progress?.let { (msg, p) -> ProgressDialog(msg, p) }

    menuFor?.let { e ->
        val pair = remember(e.url, linksVersion) { DavLinks.forUrl(ctx, e.url) }
        val link = pair?.second
        fun act(block: suspend () -> Unit) { menuFor = null; scope.launch { progress = busyUpload to null; guard { block() }; progress = null; linksVersion++; reload++ } }
        AlertDialog(onDismissRequest = { menuFor = null }, title = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    MenuRow(Icons.Default.OpenInNew, stringResource(R.string.nat_open)) { menuFor = null; open(e) }
                    if (canLock && link?.lock == null && e.lockedBy == null) MenuRow(Icons.Default.Lock, stringResource(R.string.nat_dav_checkout)) { menuFor = null; open(e, checkOut = true) }
                    if (link?.lock != null && pair != null) MenuRow(Icons.Default.LockOpen, stringResource(R.string.nat_dav_checkin)) { act { DavLinks.checkIn(ctx, pair.first) } }
                    if (link?.dirty == true && pair != null) MenuRow(Icons.Default.CloudUpload, stringResource(R.string.nat_dav_retry_upload) + (link.error?.let { "\n$it" } ?: "")) {
                        act { DavLinks.upload(ctx, pair.first) }
                    }
                    MenuRow(Icons.Default.Download, stringResource(R.string.nat_dav_download)) {
                        act {
                            val dest = app.documents.uniqueFile(app.documents.docsDir, e.name.substringBeforeLast('.'), e.name.substringAfterLast('.', "pdf"))
                            client.download(e.url, dest)
                            withContext(Dispatchers.Main) { toast(ctx.getString(R.string.saved_to, dest.absolutePath)) }
                        }
                    }
                    MenuRow(Icons.Default.Delete, stringResource(R.string.delete)) { menuFor = null; confirmDelete = e }
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text(stringResource(R.string.close)) } })
    }

    confirmDelete?.let { e ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, title = { Text(stringResource(R.string.nat_dav_delete_q, e.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        val pair = DavLinks.forUrl(ctx, e.url)
                        if (guard { client.delete(e.url, pair?.second?.lock) } != null) { pair?.let { DavLinks.remove(ctx, it.first) }; reload++ }
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }

    overwrite?.let { f ->
        AlertDialog(onDismissRequest = { overwrite = null }, title = { Text(stringResource(R.string.nat_dav_exists_q, f.name)) },
            confirmButton = { TextButton(onClick = { overwrite = null; uploadFile(f, replace = true) }) { Text(stringResource(R.string.nat_dav_overwrite)) } },
            dismissButton = { TextButton(onClick = { overwrite = null }) { Text(stringResource(R.string.cancel)) } })
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newFolder = false }, title = { Text(stringResource(R.string.new_folder)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, shape = RoundedCornerShape(16.dp)) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && !name.contains('/'), onClick = {
                    newFolder = false
                    scope.launch { guard { client.mkcol(DavXml.childUrl(folder, name.trim(), folder = true)) }?.let { reload++ } }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) =
    ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) }, modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = AlertDialogDefaults.containerColor))
