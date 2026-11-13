package com.smartdocs.editor.ui.office

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Html
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TableView
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One "create new" entry: the file type, its icon, its label, and the base name of the new file. */
private class NewKind(val ext: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: Int, val name: Int)

private val NEW_KINDS = listOf(
    NewKind("docx", Icons.Default.Description, R.string.o5_tool_new_doc, R.string.o5_new_doc_name),
    NewKind("xlsx", Icons.Default.TableChart, R.string.o5_tool_new_sheet, R.string.o5_new_sheet_name),
    NewKind("pptx", Icons.Default.Slideshow, R.string.o5_tool_new_slides, R.string.o5_new_slides_name),
    NewKind("txt", Icons.Default.TextSnippet, R.string.o5_tool_new_txt, R.string.o5_new_txt_name),
    NewKind("md", Icons.Default.TextSnippet, R.string.o5_tool_new_md, R.string.o5_new_md_name),
    NewKind("html", Icons.Default.Html, R.string.o5_tool_new_html, R.string.o5_new_html_name),
    NewKind("csv", Icons.Default.TableView, R.string.o5_tool_new_csv, R.string.o5_new_csv_name),
    NewKind("json", Icons.Default.DataObject, R.string.o5_tool_new_json, R.string.o5_new_data_name),
    NewKind("xml", Icons.Default.Code, R.string.o5_tool_new_xml, R.string.o5_new_data_name),
)

/**
 * Launcher for the Office editors: open any Office/text document, or create a new Word document, workbook,
 * presentation, text, Markdown, HTML, CSV, JSON or XML file. Meant for a Tools entry; shows its own top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficeStartScreen(nav: NavHostController) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val open = rememberOfficeOpener(nav)
    val sheetName = stringResource(R.string.o5_sheet_base) + "1"
    val failed = stringResource(R.string.o5_open_failed)
    var busy by remember { mutableStateOf(false) }
    fun create(ext: String, name: String) {
        if (busy) return
        busy = true
        scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { OfficeRoutes.createBlank(app, ext, name, sheetName = sheetName) }.getOrNull() }
            busy = false
            if (f != null) OfficeRoutes.open(nav, f) else Toast.makeText(ctx, failed, Toast.LENGTH_SHORT).show()
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.o5_tool_open)) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.o5_back)) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Entry(Icons.Default.FolderOpen, stringResource(R.string.o5_tool_open), stringResource(R.string.o5_tool_open_sub)) { open() }
            Text(stringResource(R.string.o5_tool_create), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            NEW_KINDS.forEach { k ->
                val name = stringResource(k.name)
                Entry(k.icon, stringResource(k.title), null) { create(k.ext, name) }
            }
        }
    }
}

@Composable
private fun Entry(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(title) }, supportingContent = sub?.let { { Text(it) } }, leadingContent = { Icon(icon, null) })
    }
}
