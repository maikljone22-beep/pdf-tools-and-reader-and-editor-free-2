package com.smartdocs.editor.ui.voice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.voice.PodcastLength
import com.smartdocs.editor.core.voice.PodcastScript
import com.smartdocs.editor.core.voice.ScriptLine
import com.smartdocs.editor.core.voice.TimedLine

/** ai6v (B14): focus areas + length for generating a podcast / audio summary. */
@Composable
fun PodcastOptionsRow(focus: String, onFocus: (String) -> Unit, length: PodcastLength, onLength: (PodcastLength) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(focus, onFocus, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ai6v_focus)) },
            placeholder = { Text(stringResource(R.string.ai6v_focus_hint)) }, maxLines = 3)
        Text(stringResource(R.string.ai6v_length), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PodcastLength.values().forEach { l ->
                FilterChip(selected = length == l, onClick = { onLength(l) }, label = {
                    Text(stringResource(when (l) { PodcastLength.SHORT -> R.string.ai6v_len_short; PodcastLength.MEDIUM -> R.string.ai6v_len_medium; PodcastLength.LONG -> R.string.ai6v_len_long }))
                })
            }
        }
    }
}

/** ai6v (B14): chapter list (tap = seek). Hidden when the script has no chapters. */
@Composable
fun ChapterRow(lines: List<TimedLine>, player: AudioPlayerState) {
    val ch = PodcastScript.chapters(lines)
    if (ch.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.ai6v_chapters), style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ch.size) { i ->
                val (title, ms) = ch[i]
                AssistChip(onClick = { player.seekTo(ms); if (!player.playing) player.play() }, label = { Text("${com.smartdocs.editor.core.voice.PodcastGenerator.fmt(ms)}  $title", maxLines = 1) })
            }
        }
    }
}

/**
 * ai6v (B14): editable transcript. Every line: text field, host/guest toggle, move up/down, delete, add after.
 * [onChange] gets the whole new list. Rendering again only synthesizes changed lines (per-line audio cache).
 */
@Composable
fun PodcastScriptEditor(lines: List<ScriptLine>, twoVoices: Boolean, onChange: (List<ScriptLine>) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = { onChange(PodcastScript.insertAfter(lines, -1)) }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ai6v_add_line))
            }
        }
        itemsIndexed(lines) { i, l ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (twoVoices) AssistChip(onClick = { onChange(PodcastScript.swapSpeaker(lines, i)) },
                            label = { Text(stringResource(if (l.speaker == 1) R.string.ai2_guest else R.string.ai2_host)) },
                            leadingIcon = { Icon(Icons.Default.SwapHoriz, stringResource(R.string.ai6v_swap), Modifier.size(18.dp)) })
                        l.chapter?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp), maxLines = 1) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onChange(PodcastScript.move(lines, i, -1)) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.ai6v_up)) }
                        IconButton(onClick = { onChange(PodcastScript.move(lines, i, 1)) }, enabled = i < lines.size - 1) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.ai6v_down)) }
                        IconButton(onClick = { onChange(PodcastScript.delete(lines, i)) }) { Icon(Icons.Default.Delete, stringResource(R.string.ai6v_delete_line)) }
                    }
                    OutlinedTextField(l.text, { t -> onChange(PodcastScript.editText(lines, i, t)) }, Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                    TextButton(onClick = { onChange(PodcastScript.insertAfter(lines, i)) }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.ai6v_add_line))
                    }
                }
            }
        }
    }
}
