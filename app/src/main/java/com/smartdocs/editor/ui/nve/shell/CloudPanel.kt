package com.smartdocs.editor.ui.nve.shell

import android.widget.Toast
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
import com.smartdocs.editor.R
import com.smartdocs.editor.core.cloud.Clouds
import com.smartdocs.editor.core.cloud.GoogleDrive
import com.smartdocs.editor.core.cloud.ProviderId
import com.smartdocs.editor.core.nve.shell.DriveConvert
import com.smartdocs.editor.ui.Routes
import com.smartdocs.editor.ui.account.AcctRoutes
import com.smartdocs.editor.ui.common.rememberPdfPicker
import kotlinx.coroutines.launch

/**
 * Start-page cloud panel (Nitro/Foxit start page "Cloud"): the connected accounts as shortcuts to their browsers, a
 * "connect" entry otherwise, and "Save to Google Drive as Google Docs / Sheets / Slides" when Drive is connected.
 */
@Composable
fun NveHomeCloudPanel(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val providers = remember { Clouds.all(ctx) }
    val connected = providers.filter { runCatching { it.isConnected() }.getOrDefault(false) }
    var busy by remember { mutableStateOf(false) }
    val drive = connected.firstOrNull { it.id == ProviderId.DRIVE }
    val pick = rememberPdfPicker(mimes = arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint", "text/csv", "text/plain")) { files ->
        val f = files.firstOrNull() ?: return@rememberPdfPicker
        val target = DriveConvert.targetFor(f.name)
        if (target == null) { Toast.makeText(ctx, ctx.getString(R.string.nl_drive_cannot), Toast.LENGTH_SHORT).show(); return@rememberPdfPicker }
        busy = true
        scope.launch {
            val r = runCatching { GoogleDrive.get(ctx).uploadConverted(f, null, DriveConvert.googleName(f.name), target) }
            busy = false
            Toast.makeText(ctx, if (r.isSuccess) ctx.getString(R.string.nl_drive_done) else ctx.getString(R.string.nl_drive_failed, r.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.nl_cloud_panel), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            connected.forEach { p -> AssistChip(onClick = { nav.navigate(AcctRoutes.cloudBrowser(p.id.key)) }, label = { Text(p.id.label) }, leadingIcon = { Icon(Icons.Default.CloudQueue, null) }) }
            if (connected.isEmpty()) AssistChip(onClick = { nav.navigate(Routes.CLOUD) }, label = { Text(stringResource(R.string.nl_cloud_connect)) }, leadingIcon = { Icon(Icons.Default.AddLink, null) })
            if (drive != null) AssistChip(onClick = { pick() }, enabled = !busy, label = { Text(stringResource(R.string.nl_drive_as_google)) }, leadingIcon = { Icon(Icons.Default.DriveFileMove, null) })
        }
    }
}
