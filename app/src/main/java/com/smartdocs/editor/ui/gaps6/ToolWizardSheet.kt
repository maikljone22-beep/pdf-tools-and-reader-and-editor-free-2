package com.smartdocs.editor.ui.gaps6

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.gaps6.AiGate
import com.smartdocs.editor.core.gaps6.ToolWizard
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.IconTile
import com.smartdocs.editor.ui.tools.allTools
import com.smartdocs.editor.ui.tools.toolGroupTile

/** Home card that opens the wizard. */
@Composable
fun ToolWizardCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onOpen, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Default.Lightbulb, Brand.tiles.amber, size = 44.dp, iconSize = 24.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.g6_wizard_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(stringResource(R.string.g6_wizard_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f), maxLines = 2)
            }
            Icon(Icons.Default.ChevronLeft, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

/** The wizard content (used in a sheet on phones and in the detail pane on large screens). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolWizardContent(onTool: (String) -> Unit, modifier: Modifier = Modifier) {
    val settings = LocalSettings.current
    var query by rememberSaveable { mutableStateOf("") }
    val allowed = remember(settings.aiEnabled) { ToolWizard.KEYWORDS.keys.filter { AiGate.allowsTool(settings, it) }.toSet() }
    val hits = remember(query, allowed) { ToolWizard.rank(query, limit = 8, allowed = allowed) }
    val byId = remember { allTools.associateBy { it.id } }
    val goals = remember(allowed) { ToolWizard.SUGGESTED.filter { ToolWizard.rank(it, 1, allowed).isNotEmpty() } }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.g6_wizard_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 4.dp))
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.g6_wizard_hint)) }, singleLine = true, shape = PillShape,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            goals.forEach { g -> SuggestionChip(onClick = { query = g }, label = { Text(g) }, shape = PillShape) }
        }
        Spacer(Modifier.height(8.dp))
        when {
            query.isBlank() -> Text(stringResource(R.string.g6_wizard_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            hits.isEmpty() -> Text(stringResource(R.string.g6_wizard_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            else -> LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(hits, key = { it.id }) { h ->
                    val t = byId[h.id]
                    Row(
                        Modifier.fillMaxWidth().clickable { onTool(h.id) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (t != null) IconTile(t.icon, toolGroupTile(t.group), size = 40.dp, iconSize = 22.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(t?.let { stringResource(it.label) } ?: h.id, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (h == hits.first()) AssistChip(onClick = { onTool(h.id) }, label = { Text(stringResource(R.string.g6_wizard_best)) })
                        Icon(Icons.Default.ChevronLeft, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolWizardSheet(onDismiss: () -> Unit, onTool: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        ToolWizardContent(onTool = { id -> onDismiss(); onTool(id) }, Modifier.padding(bottom = 16.dp))
    }
}
