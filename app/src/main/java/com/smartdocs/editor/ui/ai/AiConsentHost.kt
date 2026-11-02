package com.smartdocs.editor.ui.ai

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiConsent

/** Audit C7: global host for the one-time "send content to AI?" question raised by [AiConsent.ensure]. */
@Composable
fun AiConsentHost() {
    DisposableEffect(Unit) { AiConsent.hosts++; onDispose { AiConsent.hosts-- } }
    val ask by AiConsent.pending.collectAsState()
    val a = ask ?: return
    AlertDialog(
        onDismissRequest = { AiConsent.answer(a, false) },
        title = { Text(stringResource(R.string.ai_consent_title)) },
        text = { Text(stringResource(R.string.ai_consent_body, a.provider)) },
        confirmButton = { TextButton(onClick = { AiConsent.answer(a, true) }) { Text(stringResource(R.string.ai_consent_allow)) } },
        dismissButton = { TextButton(onClick = { AiConsent.answer(a, false) }) { Text(stringResource(R.string.ai_consent_cancel)) } },
    )
}
