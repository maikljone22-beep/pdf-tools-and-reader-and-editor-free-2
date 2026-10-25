package com.smartdocs.editor.ui.templates

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.R
import com.smartdocs.editor.core.templates.TemplateKind
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.SectionTitle

/**
 * Route "templates": on-device template gallery (live thumbnails) → editor with a few inputs and a live
 * preview → create the PDF. `TemplatesScreen(nav)`.
 */
@Composable
fun TemplatesScreen(nav: NavHostController) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val kind = TemplateKind.byId(selected)
    if (kind == null) TemplateGallery(nav) { selected = it.id }
    else {
        BackHandler { selected = null }
        TemplateEditor(nav, kind, onBack = { selected = null })
    }
}

@Composable
fun templateName(k: TemplateKind): String = stringResource(when (k) {
    TemplateKind.LINED -> R.string.ps_tpl_lined
    TemplateKind.GRID -> R.string.ps_tpl_grid
    TemplateKind.GRAPH -> R.string.ps_tpl_graph
    TemplateKind.MUSIC -> R.string.ps_tpl_music
    TemplateKind.LETTER -> R.string.ps_tpl_letter
    TemplateKind.INVOICE -> R.string.ps_tpl_invoice
    TemplateKind.CERTIFICATE -> R.string.ps_tpl_certificate
    TemplateKind.MINUTES -> R.string.ps_tpl_minutes
    TemplateKind.TODO -> R.string.ps_tpl_todo
    TemplateKind.TIMETABLE -> R.string.ps_tpl_timetable
    TemplateKind.EXAM -> R.string.ps_tpl_exam
})

@Composable
fun templateDescription(k: TemplateKind): String = stringResource(when (k) {
    TemplateKind.LINED -> R.string.ps_tpl_lined_desc
    TemplateKind.GRID -> R.string.ps_tpl_grid_desc
    TemplateKind.GRAPH -> R.string.ps_tpl_graph_desc
    TemplateKind.MUSIC -> R.string.ps_tpl_music_desc
    TemplateKind.LETTER -> R.string.ps_tpl_letter_desc
    TemplateKind.INVOICE -> R.string.ps_tpl_invoice_desc
    TemplateKind.CERTIFICATE -> R.string.ps_tpl_certificate_desc
    TemplateKind.MINUTES -> R.string.ps_tpl_minutes_desc
    TemplateKind.TODO -> R.string.ps_tpl_todo_desc
    TemplateKind.TIMETABLE -> R.string.ps_tpl_timetable_desc
    TemplateKind.EXAM -> R.string.ps_tpl_exam_desc
})

private val paperKinds = listOf(TemplateKind.LINED, TemplateKind.GRID, TemplateKind.GRAPH, TemplateKind.MUSIC)
private val docKinds = listOf(TemplateKind.EXAM, TemplateKind.TIMETABLE, TemplateKind.CERTIFICATE, TemplateKind.LETTER, TemplateKind.INVOICE, TemplateKind.MINUTES, TemplateKind.TODO)

@Composable
private fun TemplateGallery(nav: NavHostController, onPick: (TemplateKind) -> Unit) {
    Scaffold(topBar = { AppTopBar(stringResource(R.string.ps_templates_title), onBack = { nav.popBackStack() }) }) { pad ->
        LazyVerticalGrid(
            GridCells.Adaptive(150.dp), Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.ps_templates_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.ps_templates_documents)) }
            items(docKinds, key = { it.id }) { k -> TemplateCard(k) { onPick(k) } }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.ps_templates_papers)) }
            items(paperKinds, key = { it.id }) { k -> TemplateCard(k) { onPick(k) } }
        }
    }
}

@Composable
private fun TemplateCard(k: TemplateKind, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(14.dp)) {
        Column {
            TemplateThumb(k, Modifier.clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)))
            Column(Modifier.padding(10.dp)) {
                Text(templateName(k), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(templateDescription(k), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (k.hasFields) Text(stringResource(R.string.ps_templates_fillable), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
