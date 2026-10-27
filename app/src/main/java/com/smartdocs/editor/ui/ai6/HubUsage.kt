package com.smartdocs.editor.ui.ai6

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.ai5.Citation
import com.smartdocs.editor.core.ai6.AiMedia
import com.smartdocs.editor.core.ai6.AiUsage

/**
 * ai6 (AI-18): today's usage. Through the proxy: the server counters (`GET /usage`) when it reports them, else the
 * local per-device counter. With an own key / endpoint: no daily limit from the app.
 */
@Composable
fun AiUsageLine(client: AiClient, detailed: Boolean = false) {
    val ctx = LocalContext.current
    var server by remember(client) { mutableStateOf<AiUsage?>(null) }
    LaunchedEffect(client) { if (client.viaProxy) server = AiMedia.serverUsage(client) }
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        if (detailed) Text(stringResource(R.string.ai6_usage_title), style = MaterialTheme.typography.titleSmall)
        when {
            !client.viaProxy -> Text(stringResource(R.string.ai6_usage_unlimited), style = style, color = color)
            server != null -> {
                val u = server!!
                Text(stringResource(R.string.ai6_usage_chat, u.chatUsed, u.chatLimit) + "  ·  " + stringResource(R.string.ai6_usage_server), style = style, color = color)
                if (detailed) {
                    if (u.ttsLimit > 0) Text(stringResource(R.string.ai6_usage_tts, u.ttsUsed, u.ttsLimit), style = style, color = color)
                    if (u.imagesLimit > 0) Text(stringResource(R.string.ai6_usage_images, u.imagesUsed, u.imagesLimit), style = style, color = color)
                }
            }
            else -> {
                Text(stringResource(R.string.ai6_usage_chat, AiCalls.usedToday(ctx), AiCalls.PROXY_DAILY_LIMIT) + "  ·  " + stringResource(R.string.ai6_usage_local), style = style, color = color)
            }
        }
    }
}

/** ai6 (B9): the passage of a web / text source cited by an answer. */
@Composable
fun TextSourceDialog(c: Citation, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(c.fileName.ifBlank { stringResource(R.string.ai6h_source) }) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.a5_page_n, c.page + 1), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                SelectionContainer { Text(c.quote.ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.a5_close)) } })
}
