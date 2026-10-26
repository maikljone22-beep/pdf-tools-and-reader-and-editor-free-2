package com.smartdocs.editor.ui.connected

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.connected.EnterpriseProtection
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.esign.NoteCard
import java.io.File

/** Route "enterprise_protected?uri=": explains rights-managed files and hands them to an app that can open them. */
@Composable
fun EnterpriseProtectedScreen(nav: NavHostController, uri: String) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val file = remember(uri) { Uri.parse(uri).path?.let(::File)?.takeIf { it.exists() } }
    val info = remember(file) { file?.let { EnterpriseProtection.detect(it) } }
    val noViewer = stringResource(R.string.cn_ep_no_viewer)

    fun viewIntent(f: File): Intent {
        val mime = when (f.extension.lowercase()) {
            "pdf" -> "application/pdf"; "docx", "doc" -> "application/msword"; "xlsx", "xls" -> "application/vnd.ms-excel"
            "pptx", "ppt" -> "application/vnd.ms-powerpoint"; else -> "*/*"
        }
        return Intent(Intent.ACTION_VIEW).setDataAndType(app.documents.shareUri(f), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun openOfficial(f: File) {
        val pm = ctx.packageManager
        for (pkg in EnterpriseProtection.viewerPackages) {
            val i = viewIntent(f).setPackage(pkg)
            if (i.resolveActivity(pm) != null) { runCatching { ctx.startActivity(i) }.onSuccess { return } }
        }
        try { ctx.startActivity(Intent.createChooser(viewIntent(f), null)) } catch (e: ActivityNotFoundException) { Toast.makeText(ctx, noViewer, Toast.LENGTH_LONG).show() }
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.cn_ep_title), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Default.AdminPanelSettings, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.cn_ep_heading), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            file?.let { Text(it.name, style = MaterialTheme.typography.bodyMedium) }
            info?.labelName?.let { Text(stringResource(R.string.cn_ep_label, it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) }
            Text(stringResource(when (info?.kind) {
                EnterpriseProtection.Kind.OFFICE_IRM -> R.string.cn_ep_office
                EnterpriseProtection.Kind.OTHER_DRM -> R.string.cn_ep_other
                EnterpriseProtection.Kind.LABEL_ONLY -> R.string.cn_ep_label_only
                else -> R.string.cn_ep_body
            }), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            NoteCard(stringResource(R.string.cn_ep_why))
            if (file != null) {
                if (info?.kind != EnterpriseProtection.Kind.OTHER_DRM) Button(onClick = { openOfficial(file) }, shape = PillShape, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.cn_ep_open_official))
                }
                OutlinedButton(onClick = {
                    try { ctx.startActivity(Intent.createChooser(viewIntent(file), null)) } catch (e: ActivityNotFoundException) { Toast.makeText(ctx, noViewer, Toast.LENGTH_LONG).show() }
                }, shape = PillShape, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cn_ep_open_other)) }
                OutlinedButton(onClick = { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(file), "*/*"), null)) }, shape = PillShape, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.cn_share))
                }
            }
            if (info?.kind != EnterpriseProtection.Kind.OTHER_DRM) TextButton(onClick = {
                val id = EnterpriseProtection.VIEWER_STORE_ID
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))) } }
            }) { Text(stringResource(R.string.cn_ep_get_viewer)) }
        }
    }
}
