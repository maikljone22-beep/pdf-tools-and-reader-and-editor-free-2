package com.smartdocs.editor.ui.security

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.security.InternetAccess
import com.smartdocs.editor.core.security.LaunchPolicy
import com.smartdocs.editor.core.security.LaunchVerdict
import com.smartdocs.editor.core.security.NetDecision
import com.smartdocs.editor.core.security.SecurityPrefs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Asks before a document reaches the Internet or launches another file, following the document-security
 * settings ([SecurityPrefs]). Callers outside composition put a question in [pending]; [DocSecurityHost] (in the
 * activity's root composition) shows it and replies.
 */
object DocSecurityGate {
    sealed interface Ask {
        val reply: (Boolean) -> Unit
        data class Web(val url: String, val host: String?, override val reply: (Boolean) -> Unit) : Ask
        data class Launch(val name: String, override val reply: (Boolean) -> Unit) : Ask
    }

    var pending by mutableStateOf<Ask?>(null)
        private set

    private fun ask(a: Ask) {
        pending?.reply?.invoke(false)   // only one question at a time: an older one counts as cancelled
        pending = a
    }

    internal fun answer(a: Ask, ok: Boolean) {
        if (pending === a) pending = null
        a.reply(ok)
    }

    /** Runs [open] when the document may open [url]: allowed by the setting, a trusted site, or the user's answer. */
    fun web(ctx: Context, url: String, open: () -> Unit) {
        when (InternetAccess.decide(SecurityPrefs.netMode(ctx), SecurityPrefs.sites(ctx), url)) {
            NetDecision.ALLOW -> open()
            NetDecision.BLOCK -> Toast.makeText(ctx, ctx.getString(R.string.np_net_blocked, url.take(200)), Toast.LENGTH_LONG).show()
            NetDecision.ASK -> ask(Ask.Web(url, InternetAccess.hostOf(url)) { if (it) open() })
        }
    }

    /** Suspending form of [web] for code that continues after the decision (form submission). */
    suspend fun allowWeb(ctx: Context, url: String): Boolean = suspendCancellableCoroutine { c ->
        var done = false
        fun finish(v: Boolean) { if (!done) { done = true; if (c.isActive) c.resume(v) } }
        when (InternetAccess.decide(SecurityPrefs.netMode(ctx), SecurityPrefs.sites(ctx), url)) {
            NetDecision.ALLOW -> finish(true)
            NetDecision.BLOCK -> { Toast.makeText(ctx, ctx.getString(R.string.np_net_blocked, url.take(200)), Toast.LENGTH_LONG).show(); finish(false) }
            NetDecision.ASK -> {
                val a = Ask.Web(url, InternetAccess.hostOf(url)) { finish(it) }
                ask(a)
                c.invokeOnCancellation { if (pending === a) pending = null }
            }
        }
    }

    /**
     * Before a Launch / GoToR action opens [name]: blocked for executable-like files and when launch actions are
     * off (with a message), otherwise true only when the user confirmed the warning.
     */
    suspend fun confirmLaunch(ctx: Context, name: String): Boolean {
        when (LaunchPolicy.check(name, SecurityPrefs.blockLaunch(ctx))) {
            LaunchVerdict.BLOCKED_EXECUTABLE -> { Toast.makeText(ctx, ctx.getString(R.string.np_launch_exe_blocked, name), Toast.LENGTH_LONG).show(); return false }
            LaunchVerdict.BLOCKED_BY_SETTING -> { Toast.makeText(ctx, ctx.getString(R.string.np_launch_blocked, name), Toast.LENGTH_LONG).show(); return false }
            LaunchVerdict.ALLOWED_WITH_WARNING -> Unit
        }
        return suspendCancellableCoroutine { c ->
            var done = false
            val a = Ask.Launch(name) { v -> if (!done) { done = true; if (c.isActive) c.resume(v) } }
            ask(a)
            c.invokeOnCancellation { if (pending === a) pending = null }
        }
    }
}

/** Shows the gate's current question. Placed once in the activity's root composition. */
@Composable
fun DocSecurityHost() {
    val ctx = LocalContext.current
    when (val a = DocSecurityGate.pending) {
        null -> Unit
        is DocSecurityGate.Ask.Web -> AlertDialog(
            onDismissRequest = { DocSecurityGate.answer(a, false) },
            icon = { Icon(Icons.Default.Language, null) },
            title = { Text(stringResource(R.string.np_net_ask_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.np_net_ask_body))
                    Text(a.url, Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { DocSecurityGate.answer(a, true) }) { Text(stringResource(R.string.np_net_allow_once)) }
                    if (a.host != null) TextButton(onClick = { SecurityPrefs.addSite(ctx, a.host); DocSecurityGate.answer(a, true) }) {
                        Text(stringResource(R.string.np_net_always_allow, a.host), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { DocSecurityGate.answer(a, false) }) { Text(stringResource(R.string.np_net_block)) }
                }
            },
        )
        is DocSecurityGate.Ask.Launch -> AlertDialog(
            onDismissRequest = { DocSecurityGate.answer(a, false) },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.np_launch_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.np_launch_body))
                    Text(a.name, Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = { TextButton(onClick = { DocSecurityGate.answer(a, true) }) { Text(stringResource(R.string.np_launch_open)) } },
            dismissButton = { TextButton(onClick = { DocSecurityGate.answer(a, false) }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
