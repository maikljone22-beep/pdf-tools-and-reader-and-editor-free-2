package com.smartdocs.editor.ui.share5

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.collab.CollabConfig
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.share5.S5Config
import com.smartdocs.editor.core.share5.ShareBuilder
import com.smartdocs.editor.core.share5.ShareChoice
import com.smartdocs.editor.core.share5.ShareChoice.Format
import com.smartdocs.editor.core.share5.ShareChoice.Pages
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.FilePickRow
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Reader share sheet (SHR-5). [password] opens [file] when it is protected; [currentPage] is zero-based. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Share5Sheet(file: File, password: String?, currentPage: Int, nav: NavHostController, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.sh5_share_title), style = MaterialTheme.typography.titleLarge)
            Text(file.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            ShareOptionsContent(file, password, currentPage, nav, onDone = onDismiss)
        }
    }
}

/** Route "share5/share?uri=&page=": the same options as a screen (opened from the Files tab or a saved reader file). */
@Composable
fun ShareOptionsScreen(nav: NavHostController, uri: String?, page: Int) {
    var file by remember { mutableStateOf(uri?.let { Uri.parse(it).path }?.let(::File)) }
    val pick = rememberPdfPicker { file = it.firstOrNull() }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.sh5_share_title), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            FilePickRow(file, stringResource(R.string.sh5_pick_pdf), pick)
            Text(stringResource(R.string.sh5_save_first_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            file?.let { ShareOptionsContent(it, null, page, nav, onDone = {}) }
        }
    }
}

@Composable
private fun <T> RadioRow(selected: Boolean, label: String, sub: String? = null, value: T, onPick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) { onPick(value) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ShareOptionsContent(file: File, password: String?, currentPage: Int, nav: NavHostController, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var count by remember(file) { mutableIntStateOf(0) }
    var pagesChoice by remember { mutableStateOf(Pages.ALL) }
    var range by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(Format.AS_IS) }
    var busy by remember { mutableStateOf<Float?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        count = withContext(Dispatchers.IO) { runCatching { PdfOps.load(file, password).use { it.numberOfPages } }.getOrDefault(0) }
    }
    val badRange = stringResource(R.string.sh5_bad_range)
    val tooMany = stringResource(R.string.sh5_too_many_pages, ShareChoice.MAX_IMAGE_PAGES)

    fun run(email: Boolean) {
        val pages = ShareChoice.pages(pagesChoice, currentPage, range, count.coerceAtLeast(1))
        if (pages == null) { error = badRange; return }
        error = null
        scope.launch {
            working = true; busy = null
            runCatching { ShareBuilder.build(ctx, file, password, count, pages, format) { busy = it } }
                .onSuccess { r ->
                    if (email) S5Share.email(ctx, r.files, r.mime, emptyList(), file.nameWithoutExtension, "")
                    else S5Share.shareFiles(ctx, r.files, r.mime)
                    onDone()
                }
                .onFailure { error = if (it is ShareBuilder.TooManyPages) tooMany else (it.message ?: it.toString()) }
            working = false
        }
    }

    Text(stringResource(R.string.sh5_pages), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    RadioRow(pagesChoice == Pages.ALL, stringResource(R.string.sh5_pages_all) + if (count > 0) " ($count)" else "", value = Pages.ALL) { pagesChoice = it }
    RadioRow(pagesChoice == Pages.CURRENT, stringResource(R.string.sh5_pages_current, currentPage + 1), value = Pages.CURRENT) { pagesChoice = it }
    RadioRow(pagesChoice == Pages.RANGE, stringResource(R.string.sh5_pages_range), value = Pages.RANGE) { pagesChoice = it }
    if (pagesChoice == Pages.RANGE) OutlinedTextField(range, { range = it }, Modifier.fillMaxWidth().padding(start = 40.dp), singleLine = true,
        placeholder = { Text("1-3, 5") }, isError = error == badRange)
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.sh5_format), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    listOf(
        Format.AS_IS to (R.string.sh5_fmt_asis to R.string.sh5_fmt_asis_sub),
        Format.COMPRESSED to (R.string.sh5_fmt_compressed to R.string.sh5_fmt_compressed_sub),
        Format.NO_COMMENTS to (R.string.sh5_fmt_nocomments to R.string.sh5_fmt_nocomments_sub),
        Format.FLATTENED to (R.string.sh5_fmt_flat to R.string.sh5_fmt_flat_sub),
        Format.IMAGES to (R.string.sh5_fmt_images to R.string.sh5_fmt_images_sub),
        Format.COMMENTS_XFDF to (R.string.sh5_fmt_xfdf to R.string.sh5_fmt_xfdf_sub),
    ).forEach { (f, s) -> RadioRow(format == f, stringResource(s.first), stringResource(s.second), f) { format = it } }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { run(false) }, enabled = !working && count > 0, shape = PillShape, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sh5_share))
        }
        OutlinedButton(onClick = { run(true) }, enabled = !working && count > 0, shape = PillShape, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Email, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.sh5_email))
        }
    }
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Text(stringResource(R.string.sh5_more_ways), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
    val uri = Uri.fromFile(file).toString()
    val needSave = stringResource(R.string.sh5_protected_note)
    MoreRow(Icons.Default.Link, stringResource(R.string.sh5_view_new), stringResource(R.string.sh5_view_new_sub), S5Config.enabled) {
        if (password != null) Toast.makeText(ctx, needSave, Toast.LENGTH_LONG).show() else { onDone(); nav.navigate(Share5Routes.viewNew(uri)) }
    }
    MoreRow(Icons.Default.GroupAdd, stringResource(R.string.sh5_review_new), stringResource(R.string.sh5_review_new_sub), CollabConfig.enabled) {
        if (password != null) Toast.makeText(ctx, needSave, Toast.LENGTH_LONG).show() else { onDone(); nav.navigate(Share5Routes.reviewNew(uri)) }
    }
    MoreRow(Icons.Default.ForwardToInbox, stringResource(R.string.sh5_email_review), stringResource(R.string.sh5_email_review_sub), true) {
        onDone(); nav.navigate(Share5Routes.emailNew(uri))
    }
    if (working) ProgressDialog(stringResource(R.string.sh5_preparing), busy)
}

@Composable
private fun MoreRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(if (enabled) sub else stringResource(R.string.sh5_server_off_short)) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}
