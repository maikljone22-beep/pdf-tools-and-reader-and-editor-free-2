package com.smartdocs.editor.ui.cloud

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
import com.smartdocs.editor.R
import com.smartdocs.editor.core.cloud.CloudProvider
import com.smartdocs.editor.core.cloud.Dropbox
import com.smartdocs.editor.core.cloud.OAuthPkce
import com.smartdocs.editor.core.cloud.OneDrive
import com.smartdocs.editor.core.cloud.ProviderId
import com.smartdocs.editor.core.cloud.SharePoint
import com.smartdocs.editor.core.sync.SyncStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * fc-sync (Foxit §32): extra places of a cloud account, shown above the root listing of the cloud browser.
 *  - Dropbox: "My folder" / "Team space" when the account is in a team space (team folders become visible). The switch
 *    is refused while Dropbox sync pairs exist, because their folder paths are relative to the root.
 *  - OneDrive: "SharePoint libraries" (document libraries of followed sites). The first use asks to sign in again
 *    with the library permissions.
 */
@Composable
internal fun CloudScopeRow(provider: CloudProvider, atRoot: Boolean, onRootChanged: () -> Unit, onOpenFolder: (id: String, name: String) -> Unit) {
    if (!atRoot) return
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var askLibraries by remember { mutableStateOf(false) }
    val blocked = stringResource(R.string.fcy_team_blocked)
    when (provider) {
        is Dropbox -> {
            LaunchedEffect(provider) { withContext(Dispatchers.IO) { runCatching { provider.loadAccount() } }; version++ }
            val available = remember(version) { provider.teamSpaceAvailable }
            if (!available) return
            val on = remember(version) { provider.teamSpaceOn }
            fun switch(to: Boolean) {
                if (to == on) return
                if (SyncStore.load(ctx).any { it.provider == ProviderId.DROPBOX }) { Toast.makeText(ctx, blocked, Toast.LENGTH_LONG).show(); return }
                provider.setTeamSpace(to)
                SyncStore.setCursor(ctx, ProviderId.DROPBOX, null)   // the change cursor belongs to the old root
                version++
                onRootChanged()
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !on, onClick = { switch(false) }, label = { Text(stringResource(R.string.fcy_space_member)) },
                    leadingIcon = { Icon(Icons.Default.Person, null, Modifier.size(18.dp)) })
                FilterChip(selected = on, onClick = { switch(true) },
                    label = { Text(provider.teamName?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.fcy_space_team_named, it) } ?: stringResource(R.string.fcy_space_team)) },
                    leadingIcon = { Icon(Icons.Default.Groups, null, Modifier.size(18.dp)) })
            }
        }
        is OneDrive -> {
            val label = stringResource(R.string.fcy_sp_libraries)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                AssistChip(onClick = {
                    SharePoint.Scopes.load(ctx)
                    if (SharePoint.Scopes.librariesGranted) onOpenFolder(SharePoint.LIBRARIES, label) else askLibraries = true
                }, label = { Text(label) }, leadingIcon = { Icon(Icons.Default.Business, null, Modifier.size(18.dp)) })
            }
            if (askLibraries) AlertDialog(
                onDismissRequest = { askLibraries = false },
                icon = { Icon(Icons.Default.Business, null) },
                title = { Text(label) },
                text = { Text(stringResource(R.string.fcy_sp_consent)) },
                confirmButton = {
                    TextButton(onClick = {
                        askLibraries = false
                        SharePoint.Scopes.requestLibraries(ctx)
                        if (!OAuthPkce.start(ctx, ProviderId.ONEDRIVE)) Toast.makeText(ctx, R.string.fcy_not_configured, Toast.LENGTH_LONG).show()
                    }) { Text(stringResource(R.string.fcy_sign_in_again)) }
                },
                dismissButton = { TextButton(onClick = { askLibraries = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        else -> Unit
    }
}
