package com.smartdocs.editor.ui.ai6

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai5.AiJson
import com.smartdocs.editor.core.ai5.Creation
import com.smartdocs.editor.core.ai5.StudyParse
import com.smartdocs.editor.core.ai6.ChatExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray

/** ai6 (AI-19): export a flashcards creation as an Anki import file (tab separated) or as CSV, then share it. */
@Composable
fun AnkiExportButton(c: Creation) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    fun export(csv: Boolean) {
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val cards = StudyParse.cardsFromJson(runCatching { AiJson.json.parseToJsonElement(c.data) as JsonArray }.getOrNull())
                    val base = c.title.ifBlank { "flashcards" }.take(40) + (if (csv) "" else "_anki")
                    val f = app.documents.uniqueFile(app.documents.docsDir, base, if (csv) "csv" else "txt")
                    f.writeText(if (csv) ChatExport.csv(cards) else ChatExport.anki(cards, c.title))
                    f
                }.getOrNull()
            }
            if (out == null) android.widget.Toast.makeText(ctx, ctx.getString(R.string.error_generic), android.widget.Toast.LENGTH_LONG).show()
            else runCatching { ctx.startActivity(Intent.createChooser(app.documents.shareIntent(listOf(out), if (csv) "text/csv" else "text/plain"), null)) }
        }
    }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.Style, stringResource(R.string.ai6h_anki)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.ai6h_anki)) }, onClick = { menu = false; export(false) })
            DropdownMenuItem(text = { Text(stringResource(R.string.ai6h_csv)) }, onClick = { menu = false; export(true) })
        }
    }
}
