package com.smartdocs.editor.ui.office

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.office.OoxmlReader
import com.smartdocs.editor.core.office.TextContent
import com.smartdocs.editor.core.office5.PlainImport
import com.smartdocs.editor.core.office5.WordDoc
import java.io.File

/** Plain text (.txt/.md/.log): edited as one field, saved back with its original encoding and BOM. */
class TextState(override val file: File) : OfficeHost {
    private val content: TextContent = OoxmlReader.readText(file)
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var lastSnap = content.text
    var value by mutableStateOf(TextFieldValue(content.text))
    private var savedText = content.text
    override var tick by mutableIntStateOf(0); private set
    override val ext: String = file.extension.lowercase().ifEmpty { "txt" }
    override val modified: Boolean get() = value.text != savedText
    override val canUndo: Boolean get() = undoStack.isNotEmpty() || value.text != lastSnap
    override val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun onChange(v: TextFieldValue) {
        if (v.text != value.text) {
            // a new undo step after a pause in typing: words separated by whitespace
            if (v.text.length - lastSnap.length !in -1..1 || v.text.lastOrNull()?.isWhitespace() == true) { undoStack.addLast(lastSnap); lastSnap = v.text; redoStack.clear() }
            tick++
        }
        value = v
    }

    override fun undo() {
        if (value.text != lastSnap) { redoStack.addLast(value.text); value = TextFieldValue(lastSnap, TextRange(lastSnap.length)); tick++; return }
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(value.text); lastSnap = s; value = TextFieldValue(s, TextRange(s.length)); tick++
    }

    override fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(value.text); lastSnap = s; value = TextFieldValue(s, TextRange(s.length)); tick++
    }

    override fun saveTo(target: File) {
        OoxmlReader.writeText(target, value.text, content)
        if (target.absolutePath == file.absolutePath) { savedText = value.text; tick++ }
    }
}

@Composable
fun TextEditor(s: TextState) {
    var find by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var idx by remember { mutableIntStateOf(0) }
    val hits = remember(q, s.value.text) { if (q.isEmpty()) emptyList() else Regex(Regex.escape(q), RegexOption.IGNORE_CASE).findAll(s.value.text).map { it.range }.toList() }
    Column(Modifier.fillMaxSize()) {
        RibbonRow {
            RibbonButton(Icons.Default.Search, R.string.o5_find) { find = !find }
            Text(stringResource(R.string.o5_text_stats, s.value.text.split(Regex("\\s+")).count { it.isNotBlank() }, s.value.text.length), Modifier.padding(horizontal = 8.dp), fontSize = 12.sp)
        }
        if (find) FindBar(q, { q = it; idx = 0 }, null, null, hits.size, idx.coerceAtMost((hits.size - 1).coerceAtLeast(0)),
            onPrev = { if (hits.isNotEmpty()) { idx = (idx - 1 + hits.size) % hits.size; s.onChange(s.value.copy(selection = TextRange(hits[idx].first, hits[idx].last + 1))) } },
            onNext = { if (hits.isNotEmpty()) { idx = (idx + 1) % hits.size; s.onChange(s.value.copy(selection = TextRange(hits[idx].first, hits[idx].last + 1))) } },
            onReplaceOne = null, onReplaceAll = null, onClose = { find = false })
        BasicTextField(
            s.value, { s.onChange(it) },
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(12.dp),
            textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, fontFamily = if (s.ext == "log") FontFamily.Monospace else FontFamily.Default, textDirection = TextDirection.Content),
        )
    }
}

/** OpenDocument text / RTF: shown read-only; "Edit as Word" makes an editable .docx copy. */
class ImportState(override val file: File, val doc: PlainImport.Doc) : OfficeHost {
    override val ext: String = file.extension.lowercase()
    override val tick: Int = 0
    override val modified = false
    override val canUndo = false
    override val canRedo = false
    override fun undo() {}
    override fun redo() {}

    override fun saveTo(target: File) {
        if (target.extension.equals("docx", true)) toDocx(target) else file.copyTo(target, overwrite = true)
    }

    fun toDocx(target: File): File {
        val rtl = doc.paragraphs.any { com.smartdocs.editor.core.office.hasRtlChars(it) }
        val d = WordDoc.blank(doc.paragraphs.ifEmpty { listOf("") }, rtl)
        // headings keep their role
        val heads = doc.headings
        if (heads.isNotEmpty()) d.change { d.blocks().forEachIndexed { i, b -> if (i in heads && b.local == "p") d.applyStyle(b, com.smartdocs.editor.core.office5.WordParts.StyleKind.HEADING2) } }
        d.save(target)
        return target
    }
}

@Composable
fun ImportView(s: ImportState) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.o5_import_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().background(Color(0xFFE9EBEE)).padding(8.dp)) {
                itemsIndexed(s.doc.paragraphs) { i, p ->
                    Text(p, Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 18.dp, vertical = 3.dp),
                        style = TextStyle(color = Color(0xFF111111), fontSize = if (i in s.doc.headings) 19.sp else 15.sp, fontWeight = if (i in s.doc.headings) FontWeight.Bold else FontWeight.Normal, textDirection = TextDirection.Content))
                }
            }
        }
    }
}
