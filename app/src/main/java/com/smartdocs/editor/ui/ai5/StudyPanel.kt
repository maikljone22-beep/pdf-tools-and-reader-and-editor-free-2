package com.smartdocs.editor.ui.ai5

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai5.AiCalls
import com.smartdocs.editor.core.ai5.AiStore
import com.smartdocs.editor.core.ai5.Creation
import com.smartdocs.editor.core.ai5.StudyGen
import com.smartdocs.editor.core.ai5.StudyKind
import com.smartdocs.editor.core.ai5.StudyParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun studyLabel(k: StudyKind): String = stringResource(when (k) {
    StudyKind.FLASHCARDS -> R.string.a5_flashcards; StudyKind.QUIZ -> R.string.a5_quiz; StudyKind.GUIDE -> R.string.a5_study_guide
    StudyKind.GLOSSARY -> R.string.a5_glossary; StudyKind.MINDMAP -> R.string.a5_mindmap
})

private fun icon(k: StudyKind): ImageVector = when (k) {
    StudyKind.FLASHCARDS -> Icons.Default.Style; StudyKind.QUIZ -> Icons.Default.Quiz; StudyKind.GUIDE -> Icons.Default.MenuBook
    StudyKind.GLOSSARY -> Icons.Default.Translate; StudyKind.MINDMAP -> Icons.Default.AccountTree
}

/**
 * Study tools: generate flashcards, a quiz, a study guide, a glossary or a mind map from the whole document(s);
 * creations are saved per document / Space and reopened from the list.
 */
@Composable
fun StudyPanel(docs: DocSet, client: AiClient, onPage: (filePath: String, page: Int) -> Unit, onShare: (String) -> Unit, onExportPdf: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AiStore(ctx) }
    var saved by remember(docs.scope) { mutableStateOf(store.creations(docs.scope)) }
    var busy by remember { mutableStateOf<StudyKind?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<Creation?>(null) }
    var count by remember { mutableStateOf(12f) }
    var language by remember { mutableStateOf("") }
    var focus by remember { mutableStateOf("") }
    LaunchedEffect(docs.scope) { saved = store.creations(docs.scope) }

    fun generate(k: StudyKind) {
        busy = k; error = null
        scope.launch {
            try {
                val raw = StudyGen.generate(ctx, client, docs.indexes, k, count.toInt(), language, focus)
                val data = withContext(Dispatchers.Default) {
                    when (k) {
                        StudyKind.FLASHCARDS -> StudyParse.flashcards(raw).takeIf { it.isNotEmpty() }?.let { StudyParse.cardsToJson(it).toString() }
                        StudyKind.QUIZ -> raw.takeIf { StudyParse.quiz(it).isNotEmpty() }
                        StudyKind.GLOSSARY -> raw.takeIf { StudyParse.glossary(it).isNotEmpty() }
                        StudyKind.MINDMAP -> StudyParse.mindMap(raw)?.let { StudyParse.mindToJson(it).toString() }
                        StudyKind.GUIDE -> raw.trim().takeIf { it.isNotEmpty() }
                    }
                } ?: throw IllegalStateException(ctx.getString(R.string.a5_err_parse))
                val title = (if (focus.isNotBlank()) "$focus — " else "") + docs.title
                val c = Creation(AiStore.newId(), docs.scope, k, title, System.currentTimeMillis(), data)
                store.saveCreation(c)
                saved = store.creations(docs.scope)
                open = c
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error = if (AiCalls.isQuota(e)) ctx.getString(R.string.a5_err_quota) else ctx.getString(R.string.a5_err_failed, e.message ?: "")
            } finally { busy = null }
        }
    }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.a5_study_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(focus, { focus = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.a5_focus)) })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.a5_language), style = MaterialTheme.typography.labelLarge)
                    LanguagePicker(language, { language = it })
                }
                Text(stringResource(R.string.a5_items_count, count.toInt()), style = MaterialTheme.typography.labelLarge)
                Slider(count, { count = it }, valueRange = 5f..30f, steps = 24)
            }
        }
        items(StudyKind.entries.toList()) { k ->
            OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = docs.ready && busy == null) { generate(k) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon(k), null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                    Text(studyLabel(k), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (busy == k) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        if (saved.isNotEmpty()) item { Text(stringResource(R.string.a5_saved_creations), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
        items(saved, key = { it.id }) { c ->
            ListItem(
                leadingContent = { Icon(icon(c.kind), null) },
                headlineContent = { Text(studyLabel(c.kind)) },
                supportingContent = { Text(c.title, maxLines = 1) },
                trailingContent = { IconButton(onClick = { store.deleteCreation(c.id); saved = store.creations(docs.scope) }) { Icon(Icons.Default.Delete, stringResource(R.string.a5_delete)) } },
                modifier = Modifier.clickable { open = c },
            )
        }
    }
    open?.let { c ->
        CreationViewer(c, docs.indexes.firstOrNull()?.path.orEmpty(), onPage = { p, pg -> open = null; onPage(p, pg) }, onShare = onShare, onExportPdf = onExportPdf,
            onSave = { updated -> store.saveCreation(updated); saved = store.creations(docs.scope) }, onDismiss = { open = null })
    }
}
