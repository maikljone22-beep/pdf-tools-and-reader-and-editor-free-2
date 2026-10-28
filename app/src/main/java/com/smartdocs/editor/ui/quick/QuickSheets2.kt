package com.smartdocs.editor.ui.quick

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.files.DocumentRepository
import com.smartdocs.editor.core.pdf.DocMeta
import com.smartdocs.editor.core.pdf.PdfOps

// ======================= protect =======================

@Composable
fun ProtectSheet(c: QuickController, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var owner by remember { mutableStateOf("") }
    var print by remember { mutableStateOf(true) }
    var copy by remember { mutableStateOf(true) }
    var modify by remember { mutableStateOf(true) }
    var annotate by remember { mutableStateOf(true) }
    var forms by remember { mutableStateOf(true) }
    val mismatch = confirm.isNotEmpty() && confirm != pw
    // audit 5 P-5: an owner password equal to the open password would lift the restrictions for everyone who can open
    val ownerSame = owner.isNotEmpty() && owner == pw
    val vt = if (show) VisualTransformation.None else PasswordVisualTransformation()
    QuickSheet(stringResource(R.string.qk_pr_title), stringResource(R.string.qk_pr_btn), primaryEnabled = pw.isNotEmpty() && pw == confirm && !ownerSame,
        onPrimary = { c.protect(pw, owner, print, copy, modify, annotate, forms) }, onDismiss = onDismiss) {
        OutlinedTextField(pw, { pw = it }, label = { Text(stringResource(R.string.qk_pr_password)) }, singleLine = true, visualTransformation = vt,
            trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (show) R.string.pc_cd_hide else R.string.pc_cd_show)) } },
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(confirm, { confirm = it }, label = { Text(stringResource(R.string.qk_pr_confirm)) }, singleLine = true, visualTransformation = vt,
            isError = mismatch, supportingText = if (mismatch) ({ Text(stringResource(R.string.qk_pr_mismatch)) }) else null, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().clickable { options = !options }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.qk_pr_options), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(if (options) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (options) {
            OutlinedTextField(owner, { owner = it }, label = { Text(stringResource(R.string.qk_pr_owner)) }, singleLine = true, visualTransformation = vt,
                isError = ownerSame, supportingText = { Text(stringResource(if (ownerSame) R.string.tttl_pr_owner_same else R.string.tttl_pr_owner_hint)) }, modifier = Modifier.fillMaxWidth())
            CheckRow(stringResource(R.string.qk_pr_print), print) { print = it }
            CheckRow(stringResource(R.string.qk_pr_copy), copy) { copy = it }
            CheckRow(stringResource(R.string.qk_pr_modify), modify) { modify = it }
            CheckRow(stringResource(R.string.qk_pr_annot), annotate) { annotate = it }
            CheckRow(stringResource(R.string.qk_pr_forms), forms) { forms = it }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange); Text(label)
    }
}

// ======================= extract =======================

@Composable
fun ExtractSheet(c: QuickController, onDismiss: () -> Unit) {
    val state = c.state
    var current by remember { mutableStateOf(true) }
    var range by remember { mutableStateOf("${state.currentPage + 1}-${state.pageCount}") }
    val pages = if (current) listOf(state.currentPage) else PdfOps.parseRange(range, state.pageCount).takeIf { range.isNotBlank() } ?: emptyList()
    QuickSheet(stringResource(R.string.qk_ex_title), stringResource(R.string.qk_ex_btn), primaryEnabled = pages.isNotEmpty(),
        onPrimary = { c.extract(pages) }, onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth().selectable(current, role = Role.RadioButton) { current = true }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(current, { current = true }); Text(stringResource(R.string.qk_ex_current, state.currentPage + 1))
        }
        Row(Modifier.fillMaxWidth().selectable(!current, role = Role.RadioButton) { current = false }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(!current, { current = false }); Text(stringResource(R.string.qk_ex_range), Modifier.padding(end = 12.dp))
            OutlinedTextField(range, { range = it; current = false }, singleLine = true, placeholder = { Text(stringResource(R.string.qk_ex_range_hint)) },
                isError = !current && pages.isEmpty(), modifier = Modifier.weight(1f))
        }
        if (!current && pages.isNotEmpty()) Text(stringResource(R.string.qk_ex_count, pages.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ======================= properties =======================

@Composable
fun PropertiesSheet(c: QuickController, onDismiss: () -> Unit) {
    val state = c.state
    var meta by remember { mutableStateOf<DocMeta?>(null) }
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val m = runCatching { state.session?.meta() }.getOrNull() ?: return@LaunchedEffect
        meta = m; title = m.title; author = m.author; subject = m.subject; keywords = m.keywords
    }
    val m = meta
    val changed = m != null && (title != m.title || author != m.author || subject != m.subject || keywords != m.keywords)
    QuickSheet(stringResource(R.string.qk_props_title), stringResource(R.string.qk_save), primaryEnabled = changed,
        onPrimary = { c.setProperties(PdfOps.Info(title, author, subject, keywords, m?.creator.orEmpty(), m?.producer.orEmpty())) }, onDismiss = onDismiss) {
        OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.qk_props_title_f)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(author, { author = it }, label = { Text(stringResource(R.string.qk_props_author)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(subject, { subject = it }, label = { Text(stringResource(R.string.qk_props_subject)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(keywords, { keywords = it }, label = { Text(stringResource(R.string.qk_props_keywords)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        val f = state.file
        InfoRow(stringResource(R.string.qk_props_file), f?.name.orEmpty())
        InfoRow(stringResource(R.string.qk_props_size), DocumentRepository.formatSize(f?.length() ?: 0))
        InfoRow(stringResource(R.string.qk_props_pages), state.pageCount.toString())
        if (m != null) {
            if (m.creator.isNotBlank()) InfoRow(stringResource(R.string.qk_props_creator), m.creator)
            if (m.producer.isNotBlank()) InfoRow(stringResource(R.string.qk_props_producer), m.producer)
            if (m.creationDate.isNotBlank()) InfoRow(stringResource(R.string.qk_props_created), m.creationDate)
            if (m.modDate.isNotBlank()) InfoRow(stringResource(R.string.qk_props_modified), m.modDate)
        }
        com.smartdocs.editor.ui.reader.SecurityPropertiesSection(state.guard.info, state.password != null)   // nitro-protect2
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
