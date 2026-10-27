package com.smartdocs.editor.ui.ai6

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalSettings
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai.AiClient
import com.smartdocs.editor.core.ai5.DocTranslator
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.ai5.LanguagePicker
import com.smartdocs.editor.ui.common.AppTopBar
import kotlinx.coroutines.CancellationException
import java.io.File

/** Hand-off of a finished translation (original path → translated PDF) to the side-by-side view. */
object SideBySideArgs {
    val translated = HashMap<String, File>()
}

private enum class SbsShow { BOTH, ORIGINAL, TRANSLATION }

/**
 * ai6 (B12): route "ai_side_by_side". The original page on one side and its translation on the other, paged
 * together. With a translated PDF (from the whole-document translation) both pages are shown; otherwise each page
 * is translated on demand as text (cached), and picture-only pages are read and translated by a vision model.
 * Wide screens: side by side; phones: stacked, with a switch to show one side only.
 */
@Composable
fun SideBySideScreen(nav: NavHostController, uri: String?) {
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val client = remember(settings.aiEndpoint, settings.aiModel, settings.aiApiKey) { AiClient(settings) }
    val file = remember(uri) { uri?.takeIf { it.isNotBlank() }?.let { runCatching { File(Uri.parse(it).path!!) }.getOrNull() }?.takeIf { it.exists() } }
    val translatedFile = remember(file) { file?.let { SideBySideArgs.translated[it.absolutePath] }?.takeIf { it.exists() } }
    var src by remember { mutableStateOf<PdfSession?>(null) }
    var dst by remember { mutableStateOf<PdfSession?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(0) }
    var lang by remember { mutableStateOf(if (settings.language == "ar") "en" else "ar") }
    var guidance by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(SbsShow.BOTH) }
    val cache = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        if (file == null) return@LaunchedEffect
        runCatching { src = PdfSession.open(ctx, file) }.onFailure { openError = it.message }
        translatedFile?.let { t -> runCatching { dst = PdfSession.open(ctx, t) } }
    }
    DisposableEffect(Unit) { onDispose { runCatching { src?.close() }; runCatching { dst?.close() } } }

    val s = src
    val count = s?.pageCount ?: 0
    val key = "$page|$lang|$guidance"
    // on-demand text translation of the current page (no translated PDF)
    var request by remember { mutableStateOf(0) }
    LaunchedEffect(request) {
        val session = s ?: return@LaunchedEffect
        if (request == 0 || dst != null || cache.containsKey(key)) { busy = false; return@LaunchedEffect }
        busy = true; error = null
        try {
            val p = page
            val blocks = DocTranslator.blocks(p, session.text(p), session.charBoxes(p), 0)
            val text = if (blocks.isNotEmpty()) {
                val tr = DocTranslator.translateBlocks(ctx, client, blocks, lang, guidance)
                blocks.joinToString("\n\n") { tr[it.id] ?: it.text }
            } else {
                val bmp = DocTranslator.renderPage(ctx, file!!, null, p)
                try { DocTranslator.translateImage(client, bmp, lang, guidance) } finally { bmp.recycle() }
            }
            cache[key] = text
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = tError(ctx, e) } finally { busy = false }
    }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.ai6t_side_by_side), onBack = { nav.popBackStack() }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (file == null || openError != null) { Text(openError ?: stringResource(R.string.ai6t_no_file), Modifier.padding(16.dp)); return@Column }
            // controls
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(enabled = page > 0, onClick = { page-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.ai6t_prev)) }
                Text("${page + 1} / $count", style = MaterialTheme.typography.labelLarge)
                IconButton(enabled = page < count - 1, onClick = { page++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.ai6t_next)) }
                Spacer(Modifier.weight(1f))
                if (dst == null) LanguagePicker(lang, { lang = it }, allowAuto = false)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(show == SbsShow.BOTH, { show = SbsShow.BOTH }, label = { Text(stringResource(R.string.ai6t_both)) })
                FilterChip(show == SbsShow.ORIGINAL, { show = SbsShow.ORIGINAL }, label = { Text(stringResource(R.string.ai6t_original)) })
                FilterChip(show == SbsShow.TRANSLATION, { show = SbsShow.TRANSLATION }, label = { Text(stringResource(R.string.ai6t_translation)) })
            }
            if (dst == null) OutlinedTextField(guidance, { guidance = it }, label = { Text(stringResource(R.string.ai6t_guidance)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
                val wide = maxWidth > 600.dp
                val original: @Composable (Modifier) -> Unit = { m -> PagePane(s, page, m) }
                val translation: @Composable (Modifier) -> Unit = { m ->
                    val d = dst
                    if (d != null) PagePane(d, page.coerceAtMost(d.pageCount - 1), m)
                    else Box(m.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        val text = cache[key]
                        when {
                            text != null -> SelectionContainer { Text(text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) }
                            busy -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            else -> Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Button(onClick = { request++ }) { Text(stringResource(R.string.ai6t_translate_page)) }
                            }
                        }
                    }
                }
                when (show) {
                    SbsShow.ORIGINAL -> original(Modifier.fillMaxSize())
                    SbsShow.TRANSLATION -> translation(Modifier.fillMaxSize())
                    SbsShow.BOTH -> if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        original(Modifier.weight(1f).fillMaxHeight()); translation(Modifier.weight(1f).fillMaxHeight())
                    } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        original(Modifier.weight(1f).fillMaxWidth()); translation(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
    // keep the on-demand translation following the page when one was already asked for
    LaunchedEffect(page, lang) { if (request > 0 && dst == null && !cache.containsKey(key)) request++ }
}

@Composable
private fun PagePane(session: PdfSession?, page: Int, modifier: Modifier) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    BoxWithConstraints(modifier.background(Color.White)) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceAtLeast(100)
        LaunchedEffect(session, page, wPx) {
            val s = session ?: return@LaunchedEffect
            if (page !in 0 until s.pageCount) return@LaunchedEffect
            bmp = runCatching {
                val (pw, ph) = s.pageSize(page)
                s.render(page, wPx, (wPx * ph / pw.coerceAtLeast(1f)).toInt().coerceAtLeast(1))
            }.getOrNull()
        }
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alignment = Alignment.TopCenter) }
            ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}
