package com.smartdocs.editor.ui.nve.shell

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartdocs.editor.R
import com.smartdocs.editor.SmartDocsApp
import com.smartdocs.editor.core.util.CrashLog

/** Crash reporter: optionally attach the PDF that was open when the app crashed (Foxit/Nitro "attach document"). */
object NveCrash {
    /** Share intent with the report text and, when [attachDoc], the crashed document as a stream. */
    fun shareIntent(ctx: Context, report: String, attachDoc: Boolean): Intent {
        val doc = if (attachDoc) CrashLog.crashedDocument(ctx) else null
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, "SmartDocs crash report"); putExtra(Intent.EXTRA_TEXT, report)
            type = "text/plain"
        }
        if (doc != null) runCatching {
            val uri = (ctx.applicationContext as SmartDocsApp).documents.shareUri(doc)
            send.type = "application/pdf"; send.putExtra(Intent.EXTRA_STREAM, uri); send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }
}

/** Checkbox "attach the PDF that was open" (shown only when one is known); for the crash dialog / feedback. */
@Composable
fun NveCrashAttachRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val doc = remember { CrashLog.crashedDocument(ctx) } ?: return
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(stringResource(R.string.nl_crash_attach_doc, doc.name), style = MaterialTheme.typography.bodySmall)
    }
}
