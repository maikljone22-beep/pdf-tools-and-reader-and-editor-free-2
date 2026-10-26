package com.smartdocs.editor.ui.cloud

import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.cloud.Dropbox
import com.smartdocs.editor.core.cloud.OAuthPkce
import com.smartdocs.editor.core.cloud.OneDrive
import com.smartdocs.editor.core.cloud.ProviderId
import com.smartdocs.editor.ui.account.AcctRoutes
import kotlinx.coroutines.flow.MutableStateFlow

/** Bumped after a cloud sign-in completes so screens re-read connection state. */
object CloudAuthEvents { val version = MutableStateFlow(0) }

/**
 * `smartdocs://` deep links:
 *   smartdocs://oauth/<provider>?code=…&state=…   OAuth redirect (OneDrive / Dropbox)
 *   smartdocs://review/<id>?c=<code>              open / join a shared review
 * MainActivity puts the Uri into [pending]; [DeepLinkHandler] (inside the NavHost's composition) consumes it.
 */
object DeepLinks {
    const val SCHEME = "smartdocs"
    val pending = MutableStateFlow<Uri?>(null)
    fun isOurs(uri: Uri?) = uri?.scheme == SCHEME
}

@Composable
fun DeepLinkHandler(nav: NavHostController) {
    val ctx = LocalContext.current
    val uri by DeepLinks.pending.collectAsState()
    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        DeepLinks.pending.value = null
        when (u.host) {
            "oauth" -> runCatching {
                val id = OAuthPkce.complete(ctx, u)
                when (id) {
                    ProviderId.ONEDRIVE -> runCatching { OneDrive.get(ctx).loadAccount() }
                    ProviderId.DROPBOX -> runCatching { Dropbox.get(ctx).loadAccount() }
                    ProviderId.BOX -> runCatching { com.smartdocs.editor.core.cloud.Box.get(ctx).loadAccount() }
                    ProviderId.DRIVE -> Unit
                }
                CloudAuthEvents.version.value++
                nav.navigate(AcctRoutes.cloudBrowser(id.key))
            }.onFailure { Toast.makeText(ctx, ctx.getString(R.string.ac_auth_failed) + ": " + (it.message ?: ""), Toast.LENGTH_LONG).show() }
            // share6 (conn): signature requests and connected documents
            "esign", "doc" -> com.smartdocs.editor.ui.connected.ConnDeepLinks.handle(nav, u)
            "review" -> {
                val id = u.pathSegments.firstOrNull()
                if (id != null) nav.navigate(AcctRoutes.review(id, u.getQueryParameter("c")))
            }
        }
    }
}
