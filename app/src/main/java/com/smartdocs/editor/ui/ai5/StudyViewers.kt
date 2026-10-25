package com.smartdocs.editor.ui.ai5

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai5.AiJson
import com.smartdocs.editor.core.ai5.Creation
import com.smartdocs.editor.core.ai5.Flashcard
import com.smartdocs.editor.core.ai5.Grade
import com.smartdocs.editor.core.ai5.MindNode
import com.smartdocs.editor.core.ai5.Srs
import com.smartdocs.editor.core.ai5.StudyKind
import com.smartdocs.editor.core.ai5.StudyParse
import kotlinx.serialization.json.JsonArray

/** Full-screen viewer of a saved creation. [onSave] persists changes (card SRS state). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreationViewer(c: Creation, filePath: String, onPage: (String, Int) -> Unit, onShare: (String) -> Unit, onExportPdf: (String) -> Unit, onSave: (Creation) -> Unit, onDismiss: () -> Unit) {
    val text = remember(c) { exportText(c) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                TopAppBar(title = { Text(studyLabel(c.kind)) }, navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.a5_close)) } },
                    actions = {
                        if (c.kind != StudyKind.MINDMAP) {
                            IconButton(onClick = { onShare(text) }) { Icon(Icons.Default.Share, stringResource(R.string.a5_share)) }
                            IconButton(onClick = { onExportPdf(text) }) { Icon(Icons.Default.Description, stringResource(R.string.a5_export_pdf)) }
                        }
                    })
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (c.kind) {
                        StudyKind.FLASHCARDS -> FlashcardsView(c, onSave)
                        StudyKind.QUIZ -> QuizView(c.data) { p -> onPage(filePath, p) }
                        StudyKind.GLOSSARY -> GlossaryView(c.data) { p -> onPage(filePath, p) }
                        StudyKind.GUIDE -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 760.dp)) {
                            SelectionContainer { Text(c.data.replace("**", ""), style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)) }
                        }
                        StudyKind.MINDMAP -> {
                            val root = remember(c) { StudyParse.mindMap(c.data) ?: MindNode(c.title) }
                            MindMapView(root, c.title)
                        }
                    }
                }
            }
        }
    }
}

/** Plain text of a creation for share / PDF. */
fun exportText(c: Creation): String = when (c.kind) {
    StudyKind.FLASHCARDS -> StudyParse.cardsFromJson(runCatching { AiJson.json.parseToJsonElement(c.data) as JsonArray }.getOrNull())
        .mapIndexed { i, f -> "${i + 1}. ${f.front}\n   ${f.back}" }.joinToString("\n\n")
    StudyKind.QUIZ -> StudyParse.quiz(c.data).mapIndexed { i, q ->
        "${i + 1}. ${q.question}\n" + q.options.mapIndexed { j, o -> "   ${if (j == q.answer) "✓" else "-"} $o" }.joinToString("\n") +
            (if (q.explanation.isNotBlank()) "\n   ${q.explanation}" else "")
    }.joinToString("\n\n")
    StudyKind.GLOSSARY -> StudyParse.glossary(c.data).joinToString("\n") { "${it.term}: ${it.definition}" + (it.page?.let { p -> " ($p)" } ?: "") }
    StudyKind.GUIDE -> c.data
    StudyKind.MINDMAP -> c.data
}

@Composable
private fun FlashcardsView(c: Creation, onSave: (Creation) -> Unit) {
    var cards by remember(c) { mutableStateOf(StudyParse.cardsFromJson(runCatching { AiJson.json.parseToJsonElement(c.data) as JsonArray }.getOrNull())) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var reviewAll by remember { mutableStateOf(false) }
    val queue = remember(cards, now, reviewAll) { if (reviewAll) cards.indices.toList() else Srs.queue(cards, now) }
    var pos by remember(queue) { mutableStateOf(0) }
    var flipped by remember(pos, queue) { mutableStateOf(false) }
    val rot by animateFloatAsState(if (flipped) 180f else 0f, tween(350), label = "flip")
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val due = Srs.queue(cards, now).size
        Text(stringResource(R.string.a5_cards_status, cards.size, due), style = MaterialTheme.typography.labelLarge)
        if (queue.isEmpty() || pos >= queue.size) {
            Spacer(Modifier.height(40.dp))
            Icon(Icons.Default.CheckCircle, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.a5_cards_done), textAlign = TextAlign.Center)
            OutlinedButton(onClick = { reviewAll = true; now = System.currentTimeMillis() }) { Text(stringResource(R.string.a5_review_all)) }
            return@Column
        }
        val card: Flashcard = cards[queue[pos]]
        Text("${pos + 1} / ${queue.size}", style = MaterialTheme.typography.bodySmall)
        ElevatedCard(Modifier.fillMaxWidth().widthIn(max = 640.dp).heightIn(min = 260.dp).graphicsLayer { rotationY = rot; cameraDistance = 12 * density }
            .clickable { flipped = !flipped }) {
            Box(Modifier.fillMaxWidth().heightIn(min = 260.dp).padding(24.dp).graphicsLayer { rotationY = if (rot > 90f) 180f else 0f }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (rot > 90f) card.back else card.front, textAlign = TextAlign.Center,
                        style = if (rot > 90f) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge)
                    if (rot > 90f) card.page?.let { Text(stringResource(R.string.a5_page_n, it), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        Text(stringResource(if (flipped) R.string.a5_rate_card else R.string.a5_tap_to_flip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (flipped) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Grade.AGAIN to R.string.a5_again, Grade.HARD to R.string.a5_hard, Grade.GOOD to R.string.a5_good, Grade.EASY to R.string.a5_easy).forEach { (g, l) ->
                FilledTonalButton(onClick = {
                    val i = queue[pos]
                    val list = cards.toMutableList(); list[i] = card.copy(srs = Srs.review(card.srs, g, System.currentTimeMillis()))
                    onSave(c.copy(data = StudyParse.cardsToJson(list).toString()))
                    if (pos + 1 < queue.size) { cards = list; pos += 1 } else { cards = list; now = System.currentTimeMillis(); reviewAll = false }
                }) { Text(stringResource(l)) }
            }
        }
    }
}

@Composable
private fun QuizView(data: String, onPage: (Int) -> Unit) {
    val qs = remember(data) { StudyParse.quiz(data) }
    val picked = remember(data) { mutableStateMapOf<Int, Int>() }
    var submitted by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (submitted) item {
            val score = qs.indices.count { picked[it] == qs[it].answer }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.a5_quiz_score, score, qs.size), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { picked.clear(); submitted = false }) { Text(stringResource(R.string.a5_retry)) }
                }
            }
        }
        itemsIndexed(qs) { i, q ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${i + 1}. ${q.question}", style = MaterialTheme.typography.titleSmall)
                    q.options.forEachIndexed { j, o ->
                        val chosen = picked[i] == j
                        val color = when {
                            submitted && j == q.answer -> MaterialTheme.colorScheme.primary
                            submitted && chosen -> MaterialTheme.colorScheme.error
                            chosen -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                        OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = !submitted) { picked[i] = j }, border = BorderStroke(if (chosen || (submitted && j == q.answer)) 2.dp else 1.dp, color)) {
                            Text(o, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (submitted) {
                        if (q.explanation.isNotBlank()) Text(q.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        q.page?.let { p -> TextButton(onClick = { onPage((p - 1).coerceAtLeast(0)) }) { Text(stringResource(R.string.a5_go_page, p)) } }
                    }
                }
            }
        }
        item {
            if (!submitted) Button(onClick = { submitted = true }, enabled = picked.size == qs.size && qs.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.a5_submit_quiz))
            }
        }
    }
}

@Composable
private fun GlossaryView(data: String, onPage: (Int) -> Unit) {
    val terms = remember(data) { StudyParse.glossary(data) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(terms) { _, t ->
            ListItem(headlineContent = { Text(t.term) }, supportingContent = { Text(t.definition) },
                trailingContent = { t.page?.let { p -> TextButton(onClick = { onPage((p - 1).coerceAtLeast(0)) }) { Text(stringResource(R.string.a5_page_n, p)) } } })
            HorizontalDivider()
        }
    }
}
