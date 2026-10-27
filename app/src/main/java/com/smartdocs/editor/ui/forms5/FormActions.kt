package com.smartdocs.editor.ui.forms5

import android.content.Intent
import android.net.Uri
import com.smartdocs.editor.core.forms5.ButtonAction
import com.smartdocs.editor.core.forms5.FWidget
import com.smartdocs.editor.core.forms5.FormSubmit
import com.smartdocs.editor.core.forms5.FormSubmitter
import com.smartdocs.editor.core.forms5.SubmitFormat
import com.smartdocs.editor.core.forms5.WKind
import com.smartdocs.editor.ui.security.DocSecurityGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Runs push-button actions of the in-page form (after the user confirmed them in the field bar). */
object FormActions {

    /** Values as a form processor expects them: check boxes / radios carry their on-state or "Off". */
    fun exportValues(fs: FormFillState): Map<String, String> {
        val m = fs.model
        val out = LinkedHashMap<String, String>()
        m.byName.forEach { (name, ws) ->
            val w = ws.first()
            val v = fs.value(name)
            out[name] = when (w.kind) {
                WKind.CHECK -> if (v.equals("true", true)) (w.onValue ?: "Yes") else "Off"
                WKind.RADIO -> v.ifEmpty { "Off" }
                WKind.BUTTON, WKind.IMAGE, WKind.SIGNATURE -> return@forEach
                else -> v
            }
        }
        return out
    }

    suspend fun run(fs: FormFillState, w: FWidget, a: ButtonAction) {
        when (a) {
            is ButtonAction.Submit -> submit(fs, a)
            // nitro-protect2: changing field values follows the form-filling permission / Protected View
            is ButtonAction.Reset -> if (fs.reader.guard.check(com.smartdocs.editor.core.security.DocAction.FILL_FORMS)) reset(fs, a)
            is ButtonAction.OpenUri -> openUri(fs, a.uri)
            ButtonAction.Print -> fs.message = FormMessage.PrintHint
            ButtonAction.ImportImage -> if (fs.reader.guard.check(com.smartdocs.editor.core.security.DocAction.FILL_FORMS)) fs.imageTarget = w.name
        }
    }

    private fun reset(fs: FormFillState, a: ButtonAction.Reset) {
        fun listed(n: String) = a.fields.any { n == it || n.startsWith("$it.") }
        val target = fs.model.byName.filter { (n, ws) ->
            val k = ws.first().kind
            k != WKind.BUTTON && k != WKind.SIGNATURE && !ws.first().readOnly && (a.fields.isEmpty() || (if (a.exclude) !listed(n) else listed(n)))
        }
        val values = target.mapValues { (_, ws) -> if (ws.first().kind == WKind.CHECK) "false" else "" }
        fs.setValues(values)
        fs.message = FormMessage.Reset(values.size)
    }

    private fun openUri(fs: FormFillState, uri: String) {
        val u = uri.trim()
        if (!(FormSubmit.isHttp(u) || FormSubmit.isMail(u))) { fs.message = FormMessage.Failed(u); return }
        DocSecurityGate.web(fs.reader.context, u) {
            runCatching {
                fs.reader.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { fs.message = FormMessage.Failed(it.message ?: u) }
        }
    }

    private suspend fun submit(fs: FormFillState, a: ButtonAction.Submit) {
        // Acrobat refuses to submit while required fields are empty
        val missing = fs.model.byName.values.map { it.first() }.filter { w -> w.required && fs.validate(w, requiredToo = true) != null }
        if (missing.isNotEmpty()) {
            fs.message = FormMessage.MissingRequired(missing.size)
            fs.focus(missing.first().id)
            return
        }
        // an HTTP submission sends the form data to a server: "Manage Internet Access" decides first
        if (FormSubmit.isHttp(a.url) && !DocSecurityGate.allowWeb(fs.reader.context, a.url.trim())) return
        val values = FormSubmit.select(exportValues(fs), a)
        val file = fs.reader.file ?: return
        val name = file.name
        fs.busy = true
        try {
            val pdf: File? = if (a.format == SubmitFormat.PDF) fs.exportSource(file, fs.reader.password).let { f -> if (f == file) file else f } else null
            try {
                if (FormSubmit.isMail(a.url)) mail(fs, a, values, name, pdf)
                else if (FormSubmit.isHttp(a.url)) {
                    val r = FormSubmitter.send(a, values, name, pdf)
                    fs.message = if (r.code in 200..399) FormMessage.Submitted(r.code) else FormMessage.Failed("HTTP ${r.code} ${r.message}")
                } else fs.message = FormMessage.Failed(a.url.ifBlank { "?" })
            } finally { if (pdf != null && pdf != file) pdf.delete() }
        } catch (e: Exception) {
            fs.message = FormMessage.Failed(e.message ?: e.toString())
        } finally { fs.busy = false }
    }

    private suspend fun mail(fs: FormFillState, a: ButtonAction.Submit, values: Map<String, String>, name: String, pdf: File?) {
        val app = fs.reader.app
        val (ext, mime) = when (a.format) {
            SubmitFormat.PDF -> "pdf" to "application/pdf"
            SubmitFormat.XFDF -> "xfdf" to "application/vnd.adobe.xfdf"
            SubmitFormat.HTML -> "txt" to "text/plain"
            SubmitFormat.FDF -> "fdf" to "application/vnd.fdf"
        }
        val out = withContext(Dispatchers.IO) {
            val f = File(app.documents.tempDir, name.substringBeforeLast('.') + "_data." + ext)
            if (pdf != null && a.format == SubmitFormat.PDF) pdf.copyTo(f, overwrite = true) else f.writeBytes(FormSubmitter.payload(a, values, name, pdf))
            f
        }
        val mailto = Uri.parse(a.url.trim())
        val to = mailto.schemeSpecificPart.substringBefore('?').split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
        val subject = runCatching { Uri.parse("mailto://x?" + a.url.substringAfter('?', "")).getQueryParameter("subject") }.getOrNull() ?: name
        val send = app.documents.shareIntent(listOf(out), mime).apply {
            putExtra(Intent.EXTRA_EMAIL, to); putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        fs.reader.context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        fs.message = FormMessage.Submitted(0)
    }
}
