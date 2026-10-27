package com.smartdocs.editor.ui.ai6

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.ai6.AiImage
import com.smartdocs.editor.core.ai6.AiMedia
import com.smartdocs.editor.core.ai6.Skill
import com.smartdocs.editor.core.ai6.SkillStore
import com.smartdocs.editor.core.ai6.Skills
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.ui.ai5.CitedChatState
import com.smartdocs.editor.ui.ai5.MicButton
import com.smartdocs.editor.ui.common.rememberImagePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Most images one question may carry (keeps the vision request small). */
const val MAX_CHAT_IMAGES = 4

/** Render page [page] (0-based) of [file] to a vision image (ai6 AI-10 "ask about this page / chart"). */
suspend fun pageSnapshot(ctx: android.content.Context, file: File, password: String?, page: Int): AiImage = withContext(Dispatchers.IO) {
    val s = PdfSession.open(ctx, file, password)
    try {
        val p = page.coerceIn(0, (s.pageCount - 1).coerceAtLeast(0))
        val (w, h) = s.pageSize(p)
        val width = AiMedia.MAX_IMAGE_SIDE
        val height = (width * (h / w.coerceAtLeast(1f))).toInt().coerceIn(1, AiMedia.MAX_IMAGE_SIDE * 2)
        AiMedia.fromBitmap(s.render(p, width, height, nightMode = false, annotations = true))
    } finally { s.closeAsync() }
}

/**
 * ai6 chat input for the hub and the reader assistant: dictation, voice chat, attachments (more PDFs — AI-4;
 * gallery / camera images and page snapshots — AI-10), skill chips and `/skill` commands (AI-6, AI-11) and the
 * live web search toggle (AI-14).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubChatInput(
    chat: CitedChatState, enabled: Boolean, onVoiceChat: (() -> Unit)?,
    onAddFiles: (() -> Unit)? = null, onManageSkills: (() -> Unit)? = null,
    snapshotFile: File? = null, snapshotPassword: String? = null, snapshotPages: Int = 0, currentPage: Int = 0,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val images = remember { mutableStateListOf<AiImage>() }
    var skill by remember { mutableStateOf<Skill?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    var snapshotAsk by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var skills by remember { mutableStateOf(SkillStore(ctx).enabled()) }
    // reload after the skills screen (the back stack entry resumes)
    @Suppress("DEPRECATION") val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) skills = SkillStore(ctx).enabled() }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun addImage(img: AiImage) { if (images.size < MAX_CHAT_IMAGES) images += img }
    val pickImages = rememberImagePicker(multiple = true) { files ->
        scope.launch { working = true; try { files.take(MAX_CHAT_IMAGES).forEach { f -> runCatching { withContext(Dispatchers.IO) { AiMedia.shrink(f.readBytes()) } }.getOrNull()?.let(::addImage) } } finally { working = false } }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp -> if (bmp != null) addImage(AiMedia.fromBitmap(bmp)) }

    fun send() {
        val (cmdSkill, rest) = if (skill == null) Skills.parseCommand(text, skills) else null to text.trim()
        val use = skill ?: cmdSkill
        if (use?.wantsImage == true && images.isEmpty()) { pickImages(); return }
        if (rest.isBlank() && images.isEmpty() && use == null) return
        chat.send(rest, images.toList(), use)
        text = ""; images.clear(); skill = null
    }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 4.dp)) {
        // skills + web toggle
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
            item {
                FilterChip(selected = chat.webSearch, onClick = { chat.webSearch = !chat.webSearch }, enabled = enabled,
                    label = { Text(stringResource(R.string.ai6h_web)) }, leadingIcon = { Icon(Icons.Default.Language, null, Modifier.size(16.dp)) })
            }
            items(skills, key = { it.id }) { s ->
                FilterChip(selected = skill?.id == s.id, onClick = { skill = if (skill?.id == s.id) null else s }, enabled = enabled,
                    label = { Text("/" + s.id + " · " + s.name, maxLines = 1) })
            }
            if (onManageSkills != null) item {
                AssistChip(onClick = onManageSkills, label = { Text(stringResource(R.string.ai6h_skills)) }, leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)) })
            }
        }
        if (images.isNotEmpty() || working) LazyRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(images.size) { i ->
                val bmp = remember(images[i]) { runCatching { BitmapFactory.decodeByteArray(images[i].bytes, 0, images[i].bytes.size)?.asImageBitmap() }.getOrNull() }
                Box {
                    if (bmp != null) Image(bmp, null, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    else Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                    IconButton(onClick = { images.removeAt(i) }, Modifier.size(22.dp).align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(11.dp))) {
                        Icon(Icons.Default.Close, stringResource(R.string.ai6h_remove), Modifier.size(14.dp))
                    }
                }
            }
            if (working) item { CircularProgressIndicator(Modifier.size(24.dp)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { attachMenu = true }, enabled = enabled && !chat.busy) { Icon(Icons.Default.AttachFile, stringResource(R.string.ai6h_attach)) }
                DropdownMenu(attachMenu, { attachMenu = false }) {
                    if (onAddFiles != null) DropdownMenuItem(text = { Text(stringResource(R.string.ai6h_add_pdfs)) }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                        onClick = { attachMenu = false; onAddFiles() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai6h_add_images)) }, leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, null) },
                        onClick = { attachMenu = false; pickImages() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai6h_camera)) }, leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                        onClick = { attachMenu = false; runCatching { camera.launch(null) } })
                    if (snapshotFile != null) DropdownMenuItem(text = { Text(stringResource(R.string.ai6h_page_snapshot)) }, leadingIcon = { Icon(Icons.Default.Screenshot, null) },
                        onClick = { attachMenu = false; snapshotAsk = true })
                }
            }
            MicButton(enabled = enabled && !chat.busy, onText = { t -> text = if (text.isBlank()) t else "$text $t" })
            if (onVoiceChat != null) IconButton(onClick = onVoiceChat, enabled = enabled && !chat.busy) { Icon(Icons.Default.RecordVoiceOver, stringResource(R.string.a5_voice_chat)) }
            OutlinedTextField(text, { text = it }, Modifier.weight(1f),
                placeholder = { Text(stringResource(if (skill != null) R.string.ai6h_ask_skill else R.string.a5_ask_doc)) },
                enabled = enabled, maxLines = 4, shape = RoundedCornerShape(24.dp))
            Spacer(Modifier.width(6.dp))
            if (chat.busy) FilledIconButton(onClick = { chat.stop() }) { Icon(Icons.Default.Stop, stringResource(R.string.a5_stop)) }
            else FilledIconButton(onClick = { send() }, enabled = enabled && !working && (text.isNotBlank() || images.isNotEmpty() || skill != null)) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.a5_send))
            }
        }
    }

    if (snapshotAsk && snapshotFile != null) {
        var pageText by remember { mutableStateOf((currentPage + 1).toString()) }
        AlertDialog(onDismissRequest = { snapshotAsk = false },
            title = { Text(stringResource(R.string.ai6h_page_snapshot)) },
            text = {
                OutlinedTextField(pageText, { v -> pageText = v.filter { it.isDigit() }.take(5) }, singleLine = true,
                    label = { Text(if (snapshotPages > 0) stringResource(R.string.ai6h_page_of, snapshotPages) else stringResource(R.string.ai6h_page)) })
            },
            confirmButton = {
                TextButton(onClick = {
                    snapshotAsk = false
                    val p = (pageText.toIntOrNull() ?: 1) - 1
                    scope.launch {
                        working = true
                        try { runCatching { pageSnapshot(ctx.applicationContext, snapshotFile, snapshotPassword, p) }.getOrNull()?.let(::addImage) } finally { working = false }
                    }
                }) { Text(stringResource(R.string.ai6h_add)) }
            },
            dismissButton = { TextButton(onClick = { snapshotAsk = false }) { Text(stringResource(R.string.a5_cancel)) } })
    }
}
