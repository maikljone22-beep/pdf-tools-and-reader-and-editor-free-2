package com.smartdocs.editor.ui.reader.annot

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Spell check of comment text (Foxit 24.25) with the system spell checker service (SpellCheckerSession): the
 * misspelled words of the text with their suggestions. Null when no spell checker is available on the device.
 */
object SpellCheck {
    data class Miss(val start: Int, val length: Int, val word: String, val suggestions: List<String>)

    suspend fun check(context: Context, text: String): List<Miss>? {
        if (text.isBlank()) return emptyList()
        val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager ?: return null
        return withTimeoutOrNull(6000) {
            suspendCancellableCoroutine { cont ->
                var session: SpellCheckerSession? = null
                val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
                    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}
                    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
                        val out = ArrayList<Miss>()
                        results?.forEach { s ->
                            for (i in 0 until s.suggestionsCount) {
                                val info = s.getSuggestionsInfoAt(i)
                                val attrs = info.suggestionsAttributes
                                if (attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO == 0) continue
                                val st = s.getOffsetAt(i); val len = s.getLengthAt(i)
                                if (st < 0 || st + len > text.length) continue
                                out += Miss(st, len, text.substring(st, st + len), (0 until info.suggestionsCount).map { info.getSuggestionAt(it) })
                            }
                        }
                        runCatching { session?.close() }
                        if (cont.isActive) cont.resume(out)
                    }
                }
                val ss = runCatching { tsm.newSpellCheckerSession(null, null, listener, true) }.getOrNull()
                session = ss
                if (ss == null) { cont.resume(null); return@suspendCancellableCoroutine }
                ss.getSentenceSuggestions(arrayOf(TextInfo(text)), 5)
                cont.invokeOnCancellation { runCatching { ss.close() } }
            }
        }
    }
}

/** Row under a comment text field: "check spelling" and the misspelled words with replacement chips. */
@Composable
fun SpellCheckRow(text: String, onReplace: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var misses by remember { mutableStateOf<List<SpellCheck.Miss>?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val r = SpellCheck.check(ctx, text)
                    unavailable = r == null; misses = r; busy = false
                }
            }) { Text(stringResource(R.string.cx_spell_check)) }
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            if (unavailable) Text(stringResource(R.string.cx_spell_none), style = MaterialTheme.typography.bodySmall)
            else if (misses?.isEmpty() == true) Text(stringResource(R.string.cx_spell_ok), style = MaterialTheme.typography.bodySmall)
        }
        misses?.takeIf { it.isNotEmpty() }?.forEach { m ->
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(m.word, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                m.suggestions.take(5).forEach { sug ->
                    AssistChip(onClick = {
                        // replace this occurrence; offsets of the remaining misses shift, so they are checked again
                        if (m.start + m.length <= text.length && text.substring(m.start, m.start + m.length) == m.word)
                            onReplace(text.substring(0, m.start) + sug + text.substring(m.start + m.length))
                        misses = null
                    }, label = { Text(sug) })
                }
            }
        }
    }
}
