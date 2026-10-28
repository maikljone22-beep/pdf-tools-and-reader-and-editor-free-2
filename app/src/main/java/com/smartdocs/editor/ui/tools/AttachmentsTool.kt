package com.smartdocs.editor.ui.tools

import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.Attachment
import com.smartdocs.editor.core.pdf.AttachmentOps
import com.smartdocs.editor.core.pdf.SignedSave
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.attachments.AttachmentsBrowser
import com.smartdocs.editor.ui.common.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tool "attachments": every embedded file of a PDF (open, save, share, print, describe, delete, add files / a
 * folder; see [AttachmentsBrowser]). A change is written to a new copy "<name>_att" (a signed file gets an
 * incremental update, so the copy keeps its signatures), which becomes the file shown.
 */
@Composable
fun AttachmentsTool(nav: NavHostController, uri: String?) {
    val app = LocalApp.current
    val file = rememberInputFile(uri)
    // audit 5 T-09: the reader's password for the document (kept for the "_att" copy, which keeps the protection)
    var pw by remember { mutableStateOf(com.smartdocs.editor.core.pdf.SessionPasswords.get(file.value)) }
    val pick = rememberPdfPicker { f -> file.value = f.firstOrNull(); pw = com.smartdocs.editor.core.pdf.SessionPasswords.get(file.value) }
    var items by remember { mutableStateOf<List<Attachment>?>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(file.value, reload) {
        val f = file.value ?: run { items = emptyList(); return@LaunchedEffect }
        items = null
        items = runCatching { AttachmentOps.list(f, pw) }.getOrDefault(emptyList())
    }

    ToolScaffold(stringResource(R.string.panel_attachments), nav) {
        FilePickRow(file.value, stringResource(R.string.open_file), pick)
        if (file.value != null) AttachmentsBrowser(
            items = items,
            source = { file.value?.let { it to pw } },
            change = { _, op ->
                val f = file.value ?: return@AttachmentsBrowser
                // the first change makes the "_att" copy; later ones replace it in place
                val inCopy = f.nameWithoutExtension.endsWith("_att") && f.parentFile == app.documents.docsDir
                val out = if (inCopy) File(f.parentFile, ".${f.nameWithoutExtension}.att.pdf") else app.documents.uniqueFile(app.documents.docsDir, f.nameWithoutExtension + "_att")
                op(f, out, pw)
                val result = if (inCopy) withContext(Dispatchers.IO) { if (!out.renameTo(f)) { out.copyTo(f, overwrite = true); out.delete() }; f } else out
                runCatching { app.documents.touchRecent(result) }
                if (result == f) reload++ else file.value = result
            },
            onOpenPdf = { pdf -> nav.navigate(Routes.reader(Uri.fromFile(pdf).toString())) },
        )
        file.value?.let { f ->
            val signed by produceState(false, f) { value = withContext(Dispatchers.IO) { SignedSave.isSigned(f) } }
            if (signed) Text(stringResource(R.string.nat_signed_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
