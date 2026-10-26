package com.smartdocs.editor.ui.esign

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.core.esign.RequestStatus
import com.smartdocs.editor.core.esign.SignerStatus
import com.smartdocs.editor.core.pdf.PdfSession
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.common.EmptyState
import java.io.File

/** Open PDF for previews; closed when the composable leaves. */
@Composable
fun rememberPdfSession(file: File?, password: String? = null): PdfSession? {
    val ctx = LocalContext.current
    var session by remember(file) { mutableStateOf<PdfSession?>(null) }
    LaunchedEffect(file, password) { session = file?.let { runCatching { PdfSession.open(ctx, it, password) }.getOrNull() } }
    DisposableEffect(session) { val s = session; onDispose { s?.close() } }
    return session
}

/** One rendered page at [widthPx]; the aspect ratio comes from the page size. */
@Composable
fun PageBitmap(session: PdfSession, page: Int, widthPx: Int, modifier: Modifier = Modifier, onSize: (Float, Float) -> Unit = { _, _ -> }) {
    var bmp by remember(session, page) { mutableStateOf<Bitmap?>(null) }
    var aspect by remember(session, page) { mutableFloatStateOf(0.707f) }
    LaunchedEffect(session, page, widthPx) {
        val (w, h) = runCatching { session.pageSize(page) }.getOrDefault(595f to 842f)
        aspect = w / h; onSize(w, h)
        bmp = runCatching { session.render(page, widthPx, (widthPx / aspect).toInt().coerceAtLeast(1)) }.getOrNull()
    }
    Box(modifier.fillMaxWidth().aspectRatio(aspect)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
    }
}

@Composable
fun SignerStatusChip(s: SignerStatus) {
    val (label, color) = when (s) {
        SignerStatus.WAITING -> stringResource(R.string.cn_st_waiting) to MaterialTheme.colorScheme.outline
        SignerStatus.PENDING -> stringResource(R.string.cn_st_pending) to MaterialTheme.colorScheme.tertiary
        SignerStatus.VIEWED -> stringResource(R.string.cn_st_viewed) to MaterialTheme.colorScheme.tertiary
        SignerStatus.SIGNED -> stringResource(R.string.cn_st_signed) to Color(0xFF15803D)
        SignerStatus.DECLINED -> stringResource(R.string.cn_st_declined) to MaterialTheme.colorScheme.error
    }
    Chip(label, color)
}

@Composable
fun RequestStatusChip(s: RequestStatus) {
    val (label, color) = when (s) {
        RequestStatus.DRAFT -> stringResource(R.string.cn_rs_draft) to MaterialTheme.colorScheme.outline
        RequestStatus.OUT -> stringResource(R.string.cn_rs_out) to MaterialTheme.colorScheme.tertiary
        RequestStatus.COMPLETED -> stringResource(R.string.cn_rs_completed) to Color(0xFF15803D)
        RequestStatus.FINALIZED -> stringResource(R.string.cn_rs_finalized) to Color(0xFF15803D)
        RequestStatus.DECLINED -> stringResource(R.string.cn_rs_declined) to MaterialTheme.colorScheme.error
        RequestStatus.CANCELLED -> stringResource(R.string.cn_rs_cancelled) to MaterialTheme.colorScheme.outline
    }
    Chip(label, color)
}

@Composable
fun Chip(label: String, color: Color) {
    Surface(shape = PillShape, color = color.copy(alpha = 0.14f)) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun NotConfigured(modifier: Modifier = Modifier) = EmptyState(Icons.Default.CloudOff, stringResource(R.string.cn_not_configured), modifier)

/** Rounded info box. */
@Composable
fun NoteCard(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Info, error: Boolean = false) {
    Surface(shape = RoundedCornerShape(16.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

object ShareHelpers {
    fun copy(ctx: Context, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", text))
        Toast.makeText(ctx, ctx.getString(R.string.cn_copied), Toast.LENGTH_SHORT).show()
    }

    fun shareText(ctx: Context, text: String, subject: String = "") {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, subject)
        ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun whatsapp(ctx: Context, text: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(text))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { shareText(ctx, text) }
    }

    fun mail(ctx: Context, to: String, subject: String, body: String) {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to))).putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { shareText(ctx, body, subject) }
    }

    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}"
}

/** Row of share buttons for a link. */
@Composable
fun LinkActions(link: String, mailTo: String = "", subject: String = "", message: String = link) {
    val ctx = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { ShareHelpers.copy(ctx, link) }) { Icon(Icons.Default.ContentCopy, stringResource(R.string.cn_copy)) }
        IconButton(onClick = { ShareHelpers.shareText(ctx, message, subject) }) { Icon(Icons.Default.Share, stringResource(R.string.cn_share)) }
        IconButton(onClick = { ShareHelpers.whatsapp(ctx, message) }) { Icon(Icons.Default.Chat, stringResource(R.string.cn_whatsapp)) }
        IconButton(onClick = { ShareHelpers.mail(ctx, mailTo, subject, message) }) { Icon(Icons.Default.Email, stringResource(R.string.cn_mail)) }
    }
}
