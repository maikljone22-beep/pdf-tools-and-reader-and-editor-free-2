package com.smartdocs.editor.ui.share5

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.theme.PillShape
import com.smartdocs.editor.ui.cloud.QrImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object S5Share {
    fun shareFiles(ctx: Context, files: List<File>, mime: String, subject: String? = null, text: String? = null) {
        val app = ctx.applicationContext as SmartDocsApp
        val i = app.documents.shareIntent(files, mime).apply {
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }; text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }
        runCatching { ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Mail with attachments: ACTION_SEND(_MULTIPLE) limited to mail apps through a mailto selector. */
    fun email(ctx: Context, files: List<File>, mime: String, to: List<String>, subject: String, body: String, cc: List<String> = emptyList()) {
        val app = ctx.applicationContext as SmartDocsApp
        val uris = ArrayList(files.map { app.documents.shareUri(it) })
        val i = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_EMAIL, to.toTypedArray()); putExtra(Intent.EXTRA_SUBJECT, subject); putExtra(Intent.EXTRA_TEXT, body)
            if (cc.isNotEmpty()) putExtra(Intent.EXTRA_CC, cc.toTypedArray())   // fc-sync
            if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) else if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { shareFiles(ctx, files, mime, subject, body) }   // no mail app understands the selector
    }

    fun mailText(ctx: Context, to: List<String>, subject: String, body: String) {
        val u = Uri.parse("mailto:" + to.joinToString(",") { Uri.encode(it) } + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body))
        runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { shareText(ctx, body) }
    }

    fun shareText(ctx: Context, text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun whatsapp(ctx: Context, text: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/?text=" + Uri.encode(text))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { shareText(ctx, text) }
    }

    fun copy(ctx: Context, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", text))
        Toast.makeText(ctx, ctx.getString(R.string.sh5_copied), Toast.LENGTH_SHORT).show()
    }

    fun openUrl(ctx: Context, url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    fun date(ms: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(ms))
    fun day(ms: Long): String = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(ms))

    fun emails(text: String): List<String> = text.split(',', ';', ' ', '\n', '،').map { it.trim() }.filter { it.contains('@') && it.length > 3 }
}

/** Row of share actions for an invitation / link: share, e-mail, WhatsApp, copy, QR. */
@Composable
internal fun InviteActions(text: String, link: String, subject: String, to: List<String> = emptyList()) {
    val ctx = LocalContext.current
    var qr by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ActionIcon(Icons.Default.Share, stringResource(R.string.sh5_share)) { S5Share.shareText(ctx, text) }
        ActionIcon(Icons.Default.Email, stringResource(R.string.sh5_email)) { S5Share.mailText(ctx, to, subject, text) }
        ActionIcon(Icons.Default.Chat, stringResource(R.string.sh5_whatsapp)) { S5Share.whatsapp(ctx, text) }
        ActionIcon(Icons.Default.ContentCopy, stringResource(R.string.sh5_copy_link)) { S5Share.copy(ctx, link) }
        ActionIcon(Icons.Default.QrCode2, stringResource(R.string.sh5_qr)) { qr = true }
    }
    if (qr) AlertDialog(
        onDismissRequest = { qr = false },
        confirmButton = { TextButton(onClick = { qr = false }) { Text(stringResource(R.string.ok)) } },
        text = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White) { QrImage(link, Modifier.padding(12.dp).size(240.dp)) }
            Spacer(Modifier.height(8.dp)); Text(link, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        } },
    )
}

@Composable
internal fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        FilledTonalIconButton(onClick = onClick) { Icon(icon, label) }
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
internal fun StatusPill(text: String, color: Color) {
    Surface(shape = PillShape, color = color.copy(alpha = 0.14f)) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
internal fun NotConfiguredNote(modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudOff, null); Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.sh5_server_off), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Deadline / expiry chooser: preset chips (days) + a date picker. [value] 0 = none. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DateChooser(value: Long, onChange: (Long) -> Unit, presets: List<Int>, noneLabel: String) {
    var picker by remember { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = value == 0L, onClick = { onChange(0L) }, label = { Text(noneLabel) })
        presets.forEach { d ->
            val t = now + d * 24L * 3600_000
            FilterChip(selected = value != 0L && kotlin.math.abs(value - t) < 60_000, onClick = { onChange(t) }, label = { Text(stringResource(R.string.sh5_days_n, d)) })
        }
        AssistChip(onClick = { picker = true }, leadingIcon = { Icon(Icons.Default.Event, null, Modifier.size(18.dp)) },
            label = { Text(if (value > 0) S5Share.day(value) else stringResource(R.string.sh5_pick_date)) })
    }
    if (picker) {
        val st = rememberDatePickerState(initialSelectedDateMillis = if (value > 0) value else now + 7 * 24L * 3600_000)
        DatePickerDialog(
            onDismissRequest = { picker = false },
            confirmButton = { TextButton(onClick = {
                // end of the chosen day (local time)
                st.selectedDateMillis?.let { utc -> val cal = java.util.Calendar.getInstance(); cal.timeInMillis = utc
                    val local = java.util.Calendar.getInstance().apply { clear(); set(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH), 23, 59, 0) }
                    onChange(local.timeInMillis) }
                picker = false
            }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { picker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(st) }
    }
}
