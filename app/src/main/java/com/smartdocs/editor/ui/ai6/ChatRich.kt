package com.smartdocs.editor.ui.ai6

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai5.CitedAnswer
import com.smartdocs.editor.core.ai5.Citation
import com.smartdocs.editor.core.ai6.WebResult
import com.smartdocs.editor.ui.ai5.AnswerText

/** A piece of an answer: prose (with citations) or a fenced code block (LaTeX, CSV, MathML…). */
sealed class RichPart {
    data class Prose(val text: String) : RichPart()
    data class Code(val lang: String, val code: String) : RichPart()
}

object RichText {
    private val FENCE = Regex("```([\\w+-]*)[ \\t]*\\n?([\\s\\S]*?)```")

    /** Split [text] at ``` fences; an unclosed fence (still streaming) stays prose. */
    fun split(text: String): List<RichPart> {
        val out = ArrayList<RichPart>()
        var last = 0
        FENCE.findAll(text).forEach { m ->
            if (m.range.first > last) text.substring(last, m.range.first).takeIf { it.isNotBlank() }?.let { out += RichPart.Prose(it.trim('\n')) }
            out += RichPart.Code(m.groupValues[1].lowercase(), m.groupValues[2].trimEnd())
            last = m.range.last + 1
        }
        if (last < text.length) text.substring(last).takeIf { it.isNotBlank() }?.let { out += RichPart.Prose(it.trim('\n')) }
        return out
    }
}

/** ai6 (AI-11): answer text where code / LaTeX blocks are shown in a monospace box with a copy button. */
@Composable
fun RichAnswer(answer: CitedAnswer, onCitation: (Citation) -> Unit) {
    val parts = remember(answer.text) { RichText.split(answer.text) }
    if (parts.none { it is RichPart.Code }) { AnswerText(answer, onCitation); return }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEach { p ->
            when (p) {
                is RichPart.Prose -> AnswerText(CitedAnswer(p.text, answer.citations, answer.followUps), onCitation)
                is RichPart.Code -> CodeBox(p.lang, p.code)
            }
        }
    }
}

@Composable
fun CodeBox(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lang.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, Modifier.size(30.dp)) {
                    Icon(Icons.Default.ContentCopy, stringResource(R.string.a5_copy), Modifier.size(15.dp))
                }
            }
            SelectionContainer {
                Text(code, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
            }
        }
    }
}

/** ai6 (AI-14): the web pages an answer used; tapping opens the page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebChips(results: List<WebResult>) {
    if (results.isEmpty()) return
    val ctx = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        results.forEachIndexed { i, r ->
            AssistChip(onClick = { if (r.url.isNotBlank()) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) } },
                label = { Text("[W${i + 1}] " + r.title.ifBlank { r.url }.take(28), style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                leadingIcon = { Icon(Icons.Default.Language, null, Modifier.size(14.dp)) })
        }
    }
}

/** ai6 (§35 feedback buttons): thumbs up / down for an answer. */
@Composable
fun FeedbackButtons(value: Int, onSet: (Int) -> Unit) {
    IconButton(onClick = { onSet(1) }, Modifier.size(32.dp)) {
        Icon(if (value == 1) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, stringResource(R.string.ai6h_good), Modifier.size(16.dp),
            tint = if (value == 1) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
    IconButton(onClick = { onSet(-1) }, Modifier.size(32.dp)) {
        Icon(if (value == -1) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, stringResource(R.string.ai6h_bad), Modifier.size(16.dp),
            tint = if (value == -1) MaterialTheme.colorScheme.error else LocalContentColor.current)
    }
}
