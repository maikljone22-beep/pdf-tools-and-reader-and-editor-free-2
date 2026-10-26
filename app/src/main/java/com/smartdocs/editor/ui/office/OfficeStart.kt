package com.smartdocs.editor.ui.office

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.TableChart
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

/**
 * Small launcher for the Office editors (open any Office/text document, new Word document, new workbook).
 * Meant for a Tools-tab entry; shows its own top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficeStartScreen(nav: NavHostController) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val open = rememberOfficeOpener(nav)
    val docName = stringResource(R.string.o5_new_doc_name)
    val bookName = stringResource(R.string.o5_new_sheet_name)
    val sheetName = stringResource(R.string.o5_sheet_base) + "1"
    fun create(ext: String, name: String) = scope.launch {
        val f = withContext(Dispatchers.IO) { runCatching { OfficeRoutes.createBlank(app, ext, name, sheetName = sheetName) }.getOrNull() }
        if (f != null) OfficeRoutes.open(nav, f)
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.o5_tool_open)) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.o5_back)) } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Entry(Icons.Default.FolderOpen, stringResource(R.string.o5_tool_open), stringResource(R.string.o5_tool_open_sub)) { open() }
            Entry(Icons.Default.Description, stringResource(R.string.o5_tool_new_doc), null) { create("docx", docName) }
            Entry(Icons.Default.TableChart, stringResource(R.string.o5_tool_new_sheet), null) { create("xlsx", bookName) }
        }
    }
}

@Composable
private fun Entry(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(title) }, supportingContent = sub?.let { { Text(it) } }, leadingContent = { Icon(icon, null) })
    }
}
