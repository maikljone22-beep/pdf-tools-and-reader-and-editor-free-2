package com.smartdocs.editor.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.security.ActionFindings
import com.smartdocs.editor.core.security.ActionKind
import com.smartdocs.editor.core.security.ActionPlace
import com.smartdocs.editor.core.security.JsLevel
import com.smartdocs.editor.core.security.Perm
import com.smartdocs.editor.core.security.SecMethod
import com.smartdocs.editor.core.security.SecurityInfo

/** Text that can be selected and copied only when [allowed] (the document's copy permission). */
@Composable
fun CopyableIf(allowed: Boolean, content: @Composable () -> Unit) {
    if (allowed) SelectionContainer(content = content) else content()
}

/**
 * Under the reader's tab row: the Protected View banner ("Enable editing"), the action-inspector banner (scripts and
 * actions found on open, Allow / Keep blocked) and a note when the author's restrictions are in force.
 */
@Composable
fun ReaderSecurityBanners(state: ReaderState) {
    val g = state.guard
    // tab-view-core V-32: the Protected View banner collapses to a one-line chip (tap to expand again)
    var pvCollapsed by androidx.compose.runtime.saveable.rememberSaveable(state.uriString) { androidx.compose.runtime.mutableStateOf(false) }
    if (g.protectedView && pvCollapsed) Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = MaterialTheme.shapes.small, onClick = { pvCollapsed = false }) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Shield, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.np2_pv_title), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            TextButton(onClick = { g.enableEditing() }) { Text(stringResource(R.string.np2_pv_enable)) }
        }
    }
    else if (g.protectedView) BannerCard(Icons.Default.Shield, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) {
        Text(stringResource(R.string.np2_pv_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.np2_pv_body), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { pvCollapsed = true }) { Text(stringResource(R.string.tvc_collapse)) }
            Button(onClick = { g.enableEditing() }) { Text(stringResource(R.string.np2_pv_enable)) }
        }
    }
    val f = g.findings
    if (g.banner && f != null) BannerCard(Icons.Default.Warning, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) {
        Text(stringResource(R.string.np2_ai_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        findingLines(f).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        if (f.hasJavaScript) when {
            g.safeActive -> Hint(stringResource(R.string.np2_ai_safe_mode))
            g.jsLevel == JsLevel.OFF -> Hint(stringResource(R.string.np2_ai_js_off))
            g.jsLevel == JsLevel.TRUSTED -> Hint(stringResource(R.string.np2_ai_trusted_only))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { g.answer(false) }) { Text(stringResource(R.string.np2_ai_block)) }
            if (!f.hasJavaScript || g.canAllowScripts) Button(onClick = { g.answer(true) }) { Text(stringResource(R.string.np2_ai_allow)) }
            else TextButton(onClick = { g.dismissBanner() }) { Text(stringResource(R.string.ok)) }
        }
    }
    if (g.restricted && !g.protectedView) Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.np2_restricted_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Hint(t: String) = Text(t, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall)

@Composable
private fun BannerCard(icon: androidx.compose.ui.graphics.vector.ImageVector, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), color = bg, contentColor = fg, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), content = content)
        }
    }
}

@Composable
private fun findingLines(f: ActionFindings): List<String> {
    val out = ArrayList<String>()
    val auto = f.counts.filterKeys { it.place.automatic }.values.sum()
    if (f.hasOpenAction || auto > 0) out += stringResource(R.string.np2_ai_auto, auto)
    ActionKind.values().forEach { k ->
        val n = f.count(k)
        if (n > 0) out += stringResource(when (k) {
            ActionKind.JAVASCRIPT -> R.string.np2_ai_js
            ActionKind.LAUNCH -> R.string.np2_ai_launch
            ActionKind.URI -> R.string.np2_ai_uri
            ActionKind.SUBMIT_FORM -> R.string.np2_ai_submit
            ActionKind.IMPORT_DATA -> R.string.np2_ai_import
            ActionKind.REMOTE_GOTO -> R.string.np2_ai_remote
            ActionKind.MEDIA -> R.string.np2_ai_media
        }, n)
    }
    if (f.count(ActionPlace.DOCUMENT_SCRIPTS) > 0) out += stringResource(R.string.np2_ai_doc_scripts, f.count(ActionPlace.DOCUMENT_SCRIPTS))
    return out
}

/**
 * Security properties (row 1): method, algorithm and key length, handler version / revision, whether metadata is
 * encrypted, how the file was opened, and every permission with allowed / not allowed. [fallbackEncrypted]: shown
 * when the properties could not be read.
 */
@Composable
fun SecurityPropertiesSection(info: SecurityInfo?, fallbackEncrypted: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(stringResource(R.string.np2_sec_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (info == null) {
            Row2(stringResource(R.string.np2_sec_method), stringResource(if (fallbackEncrypted) R.string.np2_sec_password else R.string.np2_sec_none))
            return@Column
        }
        Row2(stringResource(R.string.np2_sec_method), stringResource(when (info.method) {
            SecMethod.NONE -> R.string.np2_sec_none; SecMethod.PASSWORD -> R.string.np2_sec_password
            SecMethod.CERTIFICATE -> R.string.np2_sec_certificate; SecMethod.OTHER -> R.string.np2_sec_other
        }) + if (info.method == SecMethod.OTHER) " (${info.filter})" else "")
        if (!info.encrypted) return@Column
        Row2(stringResource(R.string.np2_sec_algorithm), stringResource(R.string.np2_sec_algo_fmt, info.algorithm, info.keyLength))
        Row2(stringResource(R.string.np2_sec_revision), stringResource(R.string.np2_sec_revision_fmt, info.version, info.revision))
        Row2(stringResource(R.string.np2_sec_metadata), stringResource(if (info.encryptMetadata) R.string.yes else R.string.no))
        Text(stringResource(when {
            info.method == SecMethod.CERTIFICATE -> R.string.np2_sec_access_cert
            info.ownerAccess -> R.string.np2_sec_access_owner
            else -> R.string.np2_sec_access_user
        }), Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        info.allowed.forEach { (p, ok) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Block, null, Modifier.size(16.dp),
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(permLabel(p), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(if (ok) R.string.np2_sec_allowed else R.string.np2_sec_denied), style = MaterialTheme.typography.labelSmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun permLabel(p: Perm): String = stringResource(when (p) {
    Perm.PRINT -> R.string.s5_perm_print
    Perm.PRINT_HIGH -> R.string.s5_perm_print_high
    Perm.MODIFY -> R.string.s5_perm_modify
    Perm.COPY -> R.string.s5_perm_copy
    Perm.ACCESSIBILITY -> R.string.s5_perm_access
    Perm.ANNOTATE -> R.string.s5_perm_annotate
    Perm.FILL_FORMS -> R.string.s5_perm_forms
    Perm.ASSEMBLE -> R.string.s5_perm_assemble
})

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
