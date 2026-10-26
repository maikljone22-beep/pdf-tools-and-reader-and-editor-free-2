package com.smartdocs.editor.ui.esign

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartdocs.editor.LocalApp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.data.SavedSignature
import com.smartdocs.editor.core.esign.EsignClient
import com.smartdocs.editor.core.esign.EsignConfig
import com.smartdocs.editor.core.esign.EsignField
import com.smartdocs.editor.core.esign.EsignRef
import com.smartdocs.editor.core.esign.EsignStore
import com.smartdocs.editor.core.esign.FieldType
import com.smartdocs.editor.core.esign.SignerFlow
import com.smartdocs.editor.core.esign.SignerView
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.AppTopBar
import com.smartdocs.editor.ui.common.ProgressDialog
import com.smartdocs.editor.ui.signature.SignatureCreateSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Route "esign/sign?id=&t=": sign a request in the app (saved or new signature, text/date fields, consent). */
@Composable
fun EsignSignScreen(nav: NavHostController, id: String, token: String) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<SignerView?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<Int?>(null) }
    var consent by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<EsignField?>(null) }
    var textFor by remember { mutableStateOf<EsignField?>(null) }
    var declining by remember { mutableStateOf(false) }
    val values = remember { mutableStateMapOf<String, String>() }
    val loading = stringResource(R.string.cn_loading)

    LaunchedEffect(id, token) {
        if (!EsignConfig.enabled || id.isBlank()) return@LaunchedEffect
        busy = loading
        runCatching {
            withContext(Dispatchers.IO) {
                val v = EsignClient.signerView(id, token)
                val dest = File(ctx.filesDir, "esign/recv_$id.pdf").apply { parentFile?.mkdirs() }
                if (!dest.exists()) EsignClient.downloadForSigner(id, token, dest)
                EsignStore.upsert(ctx, EsignRef(id, v.request.title, false, token, EsignStore.received(ctx, id)?.created ?: System.currentTimeMillis(),
                    dest.absolutePath, if (v.canSign) "todo" else v.reason))
                v to dest
            }
        }.onSuccess { (v, f) ->
            view = v; file = f
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            v.request.fields.filter { it.type == FieldType.DATE }.forEach { values.putIfAbsent(it.id, today) }
        }.onFailure { error = if (EsignClient.isGone(it)) ctx.getString(R.string.cn_gone) else (it.message ?: it.toString()) }
        busy = null
    }

    val session = rememberPdfSession(file)
    Scaffold(
        topBar = { AppTopBar(view?.request?.title ?: stringResource(R.string.cn_sign_title), onBack = { nav.popBackStack() }) },
        bottomBar = {
            val v = view
            if (v != null && v.canSign && done == null) Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.clickable { consent = !consent }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(consent, { consent = it }); Text(stringResource(R.string.cn_consent), style = MaterialTheme.typography.bodySmall)
                    }
                    Row {
                        OutlinedButton(onClick = { declining = true }, shape = PillShape) { Text(stringResource(R.string.cn_decline)) }
                        Spacer(Modifier.weight(1f))
                        Button(enabled = consent, shape = PillShape, onClick = {
                            val missing = SignerFlow.missing(v.request.fields, v.me, values)
                            if (missing.isNotEmpty()) { error = ctx.getString(R.string.cn_missing_fields, missing.size); return@Button }
                            scope.launch {
                                busy = ctx.getString(R.string.cn_sending)
                                runCatching { withContext(Dispatchers.IO) { EsignClient.submit(id, token, values.toMap(), ShareHelpers.device()) } }
                                    .onSuccess { done = 1; EsignStore.update(ctx, id, false) { it.copy(status = "signed") } }
                                    .onFailure { error = if (EsignClient.isNotYourTurn(it)) ctx.getString(R.string.cn_reason_turn) else it.message }
                                busy = null
                            }
                        }) { Text(stringResource(R.string.cn_finish_sign)) }
                    }
                }
            }
        },
    ) { pad ->
        if (!EsignConfig.enabled) { NotConfigured(Modifier.padding(pad)); return@Scaffold }
        val v = view
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { item { NoteCard(it, Icons.Default.ErrorOutline, error = true) } }
            when {
                done == 1 -> item { NoteCard(stringResource(R.string.cn_signed_thanks), Icons.Default.TaskAlt) }
                done == 2 -> item { NoteCard(stringResource(R.string.cn_declined_done), Icons.Default.Block) }
                v != null -> {
                    item {
                        val me = v.request.signers.getOrNull(v.me)
                        Text(stringResource(R.string.cn_from, v.request.sender), style = MaterialTheme.typography.titleSmall)
                        if (v.request.message.isNotBlank()) Text(v.request.message, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                        NoteCard(if (v.canSign) stringResource(R.string.cn_sign_hint, me?.name ?: "") else reasonText(v.reason))
                    }
                    val s = session
                    if (s != null) items(s.pageCount) { p ->
                        SignPage(s, p, v.request.fields.filter { it.page == p && it.signer == v.me }, values, v.canSign) { f ->
                            when (f.type) { FieldType.SIGNATURE, FieldType.INITIALS -> pickFor = f; else -> textFor = f }
                        }
                    }
                }
            }
        }
    }

    busy?.let { ProgressDialog(it) }
    pickFor?.let { f -> SignaturePicker(initials = f.type == FieldType.INITIALS, onDismiss = { pickFor = null }) { b64 -> values[f.id] = b64; pickFor = null } }
    textFor?.let { f ->
        var t by remember(f.id) { mutableStateOf(values[f.id].orEmpty()) }
        AlertDialog(onDismissRequest = { textFor = null }, title = { Text(f.label.ifBlank { fieldLabel(f.type) }) },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { values[f.id] = t.trim(); textFor = null }) { Text(stringResource(R.string.cn_ok)) } },
            dismissButton = { TextButton(onClick = { textFor = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (declining) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { declining = false }, title = { Text(stringResource(R.string.cn_decline)) },
            text = { OutlinedTextField(reason, { reason = it }, label = { Text(stringResource(R.string.cn_decline_reason)) }) },
            confirmButton = { TextButton(onClick = {
                declining = false
                scope.launch {
                    busy = ctx.getString(R.string.cn_sending)
                    runCatching { withContext(Dispatchers.IO) { EsignClient.decline(id, token, reason.trim(), ShareHelpers.device()) } }
                        .onSuccess { done = 2; EsignStore.update(ctx, id, false) { it.copy(status = "declined") } }
                        .onFailure { error = it.message }
                    busy = null
                }
            }) { Text(stringResource(R.string.cn_decline)) } },
            dismissButton = { TextButton(onClick = { declining = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun reasonText(r: String) = stringResource(when (r) {
    "turn" -> R.string.cn_reason_turn; "signed" -> R.string.cn_reason_signed; "declined" -> R.string.cn_reason_declined
    "cancelled" -> R.string.cn_reason_cancelled; "expired" -> R.string.cn_reason_expired; else -> R.string.cn_gone
})

@Composable
private fun SignPage(session: com.smartdocs.editor.core.pdf.PdfSession, page: Int, fields: List<EsignField>, values: Map<String, String>, enabled: Boolean, onField: (EsignField) -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            var aspect by remember { mutableFloatStateOf(0.707f) }
            PageBitmap(session, page, wPx.toInt().coerceIn(300, 1600), onSize = { w, h -> aspect = w / h })
            val hPx = wPx / aspect
            fields.forEach { f ->
                val v = values[f.id]
                Box(Modifier.absoluteOffset(with(density) { (f.x * wPx).toDp() }, with(density) { (f.y * hPx).toDp() })
                    .size(with(density) { (f.w * wPx).toDp() }, with(density) { (f.h * hPx).toDp() })
                    .border(2.dp, if (v.isNullOrBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, RoundedCornerShape(4.dp))
                    .clickable(enabled = enabled) { onField(f) }, contentAlignment = Alignment.Center) {
                    when {
                        v.isNullOrBlank() -> Text(fieldLabel(f.type) + if (f.required) " *" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        f.type == FieldType.SIGNATURE || f.type == FieldType.INITIALS -> {
                            val img = remember(v) { runCatching { Base64.decode(v, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull() }
                            img?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                        }
                        else -> Text(v, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Saved signatures (or initials) of this device, plus "create new" through the existing signature sheet. */
@Composable
private fun SignaturePicker(initials: Boolean, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val app = LocalApp.current
    val sigs by app.db.signatures().all().collectAsState(initial = emptyList<SavedSignature>())
    var create by remember { mutableStateOf(false) }
    val list = sigs.filter { it.isInitials == initials && File(it.path).exists() }.ifEmpty { sigs.filter { File(it.path).exists() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initials) R.string.cn_pick_initials else R.string.cn_pick_signature)) },
        text = {
            Column {
                if (list.isEmpty()) Text(stringResource(R.string.cn_no_saved_sigs), style = MaterialTheme.typography.bodySmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { s ->
                        val bmp = remember(s.path) { runCatching { BitmapFactory.decodeFile(s.path) }.getOrNull() }
                        Surface(onClick = { onPicked(Base64.encodeToString(File(s.path).readBytes(), Base64.NO_WRAP)) }, shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(140.dp, 70.dp)) {
                            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(6.dp)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { create = true }) { Icon(Icons.Default.Draw, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.cn_new_signature)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (create) SignatureCreateSheet(onDismiss = { create = false }, onSaved = { create = false })
}
