package com.smartdocs.editor.ui.share5

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.collab.CollabStore
import com.smartdocs.editor.core.collab.ReviewRef
import com.smartdocs.editor.core.share5.ReviewConsent
import com.smartdocs.editor.core.share5.S5Client
import com.smartdocs.editor.core.share5.S5Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Privacy consent for reviews this device joined (SHR-4). Call once inside the review detail screen:
 * `com.smartdocs.editor.ui.share5.ReviewConsentGate(ref)`. Shows the notice the first time; also tells the share
 * server that this reviewer opened the review (reviewer status "opened").
 */
@Composable
fun ReviewConsentGate(ref: ReviewRef) {
    val ctx = LocalContext.current
    if (ref.isOwner) return
    var state by remember(ref.id) { mutableStateOf(ReviewConsent.state(ctx, ref.id)) }
    LaunchedEffect(ref.id, state) {
        val s = state ?: return@LaunchedEffect
        if (S5Config.enabled) withContext(Dispatchers.IO) {
            runCatching { S5Client.seen(ref.id, ref.code, CollabStore.participantId(ctx), ReviewConsent.author(ctx, ref.id, CollabStore.authorName(ctx)), s) }
        }
    }
    if (state == null) ConsentDialog(ref, cancellable = false) { state = ReviewConsent.state(ctx, ref.id) }
}

/** The consent / anonymity dialog (also opened from "my privacy"). */
@Composable
internal fun ConsentDialog(ref: ReviewRef, cancellable: Boolean = true, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = remember { ReviewConsent.state(ctx, ref.id) }
    var anonymous by remember { mutableStateOf(current?.anonymous ?: false) }
    fun save() {
        ReviewConsent.set(ctx, ref.id, true, anonymous)
        val st = ReviewConsent.state(ctx, ref.id)
        if (S5Config.enabled) scope.launch(Dispatchers.IO) {
            runCatching { S5Client.seen(ref.id, ref.code, CollabStore.participantId(ctx), ReviewConsent.author(ctx, ref.id, CollabStore.authorName(ctx)), st) }
        }
        onDone()
    }
    AlertDialog(
        onDismissRequest = { if (cancellable) onDone() },
        title = { Text(stringResource(R.string.sh5_consent_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sh5_consent_text, ref.title))
            SwitchRow(stringResource(R.string.sh5_consent_anon), stringResource(R.string.sh5_consent_anon_sub, ReviewConsent.pseudonym(CollabStore.participantId(ctx))), anonymous, true) { anonymous = it }
            Text(stringResource(R.string.sh5_consent_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } },
        confirmButton = { TextButton(onClick = { save() }) { Text(stringResource(R.string.sh5_consent_ok)) } },
        dismissButton = if (cancellable) ({ TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } }) else null,
    )
}
