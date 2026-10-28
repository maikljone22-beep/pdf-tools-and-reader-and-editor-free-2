package com.smartdocs.editor.ui.forms5

import android.content.Intent
import android.net.Uri
import com.smartdocs.editor.core.forms5.ButtonAction
import com.smartdocs.editor.core.forms5.FWidget
import com.smartdocs.editor.core.forms5.FieldDates
import com.smartdocs.editor.core.forms5.FormSubmit
import com.smartdocs.editor.core.forms5.FormSubmitter
import com.smartdocs.editor.core.forms5.JsRequest
import com.smartdocs.editor.core.forms5.OcgState
import com.smartdocs.editor.core.forms5.PdfAction
import com.smartdocs.editor.core.forms5.PdfActions
import com.smartdocs.editor.core.forms5.SubmitFormat
import com.smartdocs.editor.core.forms5.Trigger
import com.smartdocs.editor.core.forms5.WKind
import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.smartdocs.editor.ui.security.DocSecurityGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs form actions of the in-page form: push-button actions (after the user confirmed them in the field bar),
 * action chains of widget triggers (Mouse Up / Down, Enter, Exit, Focus, Blur, /Next chains), JavaScript on the
 * sandboxed runtime and the viewer requests its scripts make (submitForm, print, pageNum, launchURL, ...).
 */
object FormActions {

    /** Values as a form processor expects them: check boxes / radios carry their on-state or "Off". */
    fun exportValues(fs: FormFillState): Map<String, String> {
        val m = fs.model
        val out = LinkedHashMap<String, String>()
        m.byName.forEach { (name, ws) ->
            if (fs.isRemoved(name)) return@forEach      // 8.84: removed by this.removeField
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
            ButtonAction.Print -> print(fs)
            ButtonAction.ImportImage -> if (fs.reader.guard.check(com.smartdocs.editor.core.security.DocAction.FILL_FORMS)) fs.imageTarget = w.name
        }
    }

    // ------------------------------------------------------------------ action chains

    /** Run [list] (actions of [trigger] on [w]); lists that send data or leave the file wait for consent first. */
    fun fire(fs: FormFillState, w: FWidget?, trigger: Trigger?, list: List<PdfAction>) {
        if (list.isEmpty()) return
        if (PdfActions.needsConfirm(list)) { fs.pendingChain = Triple(w, trigger, list); return }
        fs.reader.scope.launch { runChain(fs, w, trigger, list) }
    }

    /** Run every action of [list] in order (the /Next chain is already flattened into it). */
    suspend fun runChain(fs: FormFillState, w: FWidget?, trigger: Trigger?, list: List<PdfAction>) {
        for (a in list) {
            try {
                runOne(fs, w, trigger, a)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                fs.message = FormMessage.Failed(e.message ?: e.toString())
            }
        }
    }

    private suspend fun runOne(fs: FormFillState, w: FWidget?, trigger: Trigger?, a: PdfAction) {
        when (a) {
            is PdfAction.JavaScript -> runJs(fs, w, trigger, a.code)
            is PdfAction.Submit -> submit(fs, a.submit, a.includeAnnotations, a.canonicalDates)
            is PdfAction.Reset -> if (fs.reader.guard.check(com.smartdocs.editor.core.security.DocAction.FILL_FORMS)) reset(fs, a.reset)
            is PdfAction.Uri -> openUri(fs, a.uri)
            is PdfAction.GoTo -> if (a.page >= 0) fs.reader.jumpTo(a.page) else fs.message = FormMessage.NotFound(a.named ?: "?")
            is PdfAction.GoToR -> openOther(fs, a.file, a.page, a.named)
            is PdfAction.Launch -> if (a.file.endsWith(".pdf", true)) openOther(fs, a.file, 0, null) else fs.message = FormMessage.Unsupported("Launch " + a.file.substringAfterLast('/'))
            is PdfAction.Hide -> fs.setHidden(a.fields, a.hide)
            is PdfAction.Named -> named(fs, a.name)
            is PdfAction.ImportData -> if (fs.reader.guard.check(com.smartdocs.editor.core.security.DocAction.FILL_FORMS)) importData(fs, a.file)
            is PdfAction.SetOcg -> setOcg(fs, a)
            is PdfAction.Unsupported -> fs.message = FormMessage.Unsupported(a.type)
        }
    }

    /** JavaScript action on the runtime; without one, the simple patterns (submit / reset / print / URL) still work. */
    private suspend fun runJs(fs: FormFillState, w: FWidget?, trigger: Trigger?, code: String) {
        val sc = fs.scripts
        if (sc == null) {
            FormSubmit.parseJs(code)?.let { b -> if (w != null) run(fs, w, b) }
            return
        }
        val values = fs.currentValues()
        val page = fs.reader.currentPage
        val r = withContext(Dispatchers.Default) { sc.action(w?.name, trigger?.jsName ?: "Mouse Up", code, values, page) } ?: return
        fs.applyActionResult(r)
        if (!r.ok && r.error != null) fs.consoleLog += "! " + r.error
        handleRequests(fs, w, r.requests)
    }

    /** Viewer requests of a script, in order; the ones that need consent are collected into one confirmation. */
    suspend fun handleRequests(fs: FormFillState, w: FWidget?, requests: List<JsRequest>) {
        val confirm = ArrayList<PdfAction>()
        val unsupported = ArrayList<String>()
        for (q in requests) when (q.kind) {
            "submit" -> {
                val fmt = when (q["f"].uppercase()) { "XFDF", "XFD", "XML" -> SubmitFormat.XFDF; "HTML" -> SubmitFormat.HTML; "PDF" -> SubmitFormat.PDF; else -> SubmitFormat.FDF }
                val fields = q["fl"].split('\n').filter { it.isNotBlank() }
                confirm += PdfAction.Submit(ButtonAction.Submit(q["u"], fmt, includeNoValue = q["e"] == "1", exclude = false, fields = fields,
                    get = q["g"] == "1" && fmt == SubmitFormat.HTML), includeAnnotations = q["an"] == "1", canonicalDates = q["cn"] == "1")
            }
            "url" -> confirm += PdfAction.Uri(q["u"])
            "page" -> q["p"].toIntOrNull()?.let { fs.reader.jumpTo(it) }
            "dest" -> gotoNamed(fs, q["n"])
            "print" -> print(fs)
            "mail" -> mailData(fs, q["to"], q["su"], wholeDoc = q["form"] != "1")
            "export" -> shareData(fs, if (q["f"] == "xfdf") SubmitFormat.XFDF else SubmitFormat.FDF)
            "import" -> confirm += PdfAction.ImportData(q["p"])
            "focus" -> fs.model.widgets.firstOrNull { it.name == q["n"] }?.let { fs.focus(it.id) }
            "image" -> fs.imageTarget = q["n"].ifBlank { w?.name }
            "file" -> fs.filePickFor = q["n"].ifBlank { w?.name }
            "flatten" -> flatten(fs)
            "remove" -> fs.removeFieldByScript(q["n"])
            "menu" -> named(fs, q["n"])
            // 8.84 document API
            "saveas" -> saveAs(fs, q["p"])
            "close" -> fs.reader.scriptClose = true
            "scroll" -> {
                val r = fs.reader
                val page = r.currentPage
                val h = runCatching { r.session?.pageSize(page)?.second }.getOrNull()
                val y = q["y"].toFloatOrNull() ?: 0f
                val x = q["x"].toFloatOrNull() ?: 0f
                if (h != null && h > 0f) r.jumpTo(page, com.smartdocs.editor.core.forms5.NamedActions.scrollFraction(y, h), x) else r.jumpTo(page)
            }
            // 27.10 Field.setAction / buttonSetIcon / signatureSign
            "setaction" -> if (!fs.setScriptAction(q["n"], q["t"], q["s"])) unsupported += "setAction(" + q["t"] + ")"
            "seticon" -> setIcon(fs, q["n"], com.smartdocs.editor.core.forms5.ScriptDocChanges.IconSrc(q["f"], q["nm"]))
            "sign" -> {
                val target = fs.model.widgets.firstOrNull { it.name == q["n"] && it.kind == WKind.SIGNATURE }
                if (target == null) fs.message = FormMessage.NotFound(q["n"]) else fs.reader.scriptSign = target.name
            }
            "addscript" -> fs.addDocScript(q["n"], q["s"])
            "lock" -> fs.setSigLock(q["n"], com.smartdocs.editor.core.forms5.ScriptDocChanges.lockOf(q["a"], q["f"].split('\n').filter { it.isNotBlank() }))
            "addfield" -> {
                val type = com.smartdocs.editor.core.forms5.ScriptDocChanges.typeOf(q["t"])
                val rect = com.smartdocs.editor.core.forms5.ScriptDocChanges.rectOf(q["r"])
                if (type != null && rect != null) fs.addScriptField(com.smartdocs.editor.core.forms5.ScriptDocChanges.NewField(q["n"], type, q["p"].toIntOrNull() ?: 0, rect))
                else unsupported += "addField(" + q["t"] + ")"
            }
            "dataobj" -> exportDataObject(fs, q["n"])
            "unsupported" -> unsupported += q["m"]
        }
        if (unsupported.isNotEmpty()) fs.message = FormMessage.Unsupported(unsupported.distinct().joinToString(", "))
        if (confirm.isNotEmpty()) fs.pendingChain = Triple(w, null, confirm)
    }

    /** this.saveAs / named action SaveAs: save, then "Save a copy" under the script's file name (or the document's). */
    private fun saveAs(fs: FormFillState, path: String) {
        val r = fs.reader
        val name = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { r.file?.name ?: "document.pdf" }
            .let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        r.scope.launch { if (r.save()) r.scriptSaveAs = name }
    }

    /**
     * 27.10 `field.buttonSetIcon(icon)`: the picture inside the icon becomes the button's pending image (shown at
     * once, written on save like an imported image); an icon without a picture is copied as it is on save.
     */
    private suspend fun setIcon(fs: FormFillState, target: String, src: com.smartdocs.editor.core.forms5.ScriptDocChanges.IconSrc) {
        if (fs.model.widgets.none { it.name == target && it.kind == WKind.BUTTON }) { fs.message = FormMessage.NotFound(target); return }
        if (!fs.reader.guard.check(com.smartdocs.editor.core.security.DocAction.FILL_FORMS)) return
        // a button whose picture was chosen while filling: its pending image is the icon
        fs.images[src.field]?.takeIf { src.field.isNotBlank() && it.exists() }?.let { picked ->
            val copy = File(fs.reader.app.documents.tempDir, "jsa_icon_${System.nanoTime()}." + picked.extension.ifBlank { "png" })
            if (runCatching { withContext(Dispatchers.IO) { picked.copyTo(copy, overwrite = true) } }.isSuccess) { fs.setImage(target, copy); return }
        }
        val file = fs.reader.file ?: return
        val found = withContext(Dispatchers.IO) {
            runCatching {
                PdfOps.loadRaw(file, fs.reader.password).use { d ->
                    val icon = com.smartdocs.editor.core.forms5.ScriptIcons.find(d, src) ?: return@use null
                    val png = com.smartdocs.editor.core.forms5.ScriptIcons.firstImage(icon)?.let { img ->
                        runCatching {
                            val bmp = (com.tom_roush.pdfbox.pdmodel.graphics.PDXObject.createXObject(img, null) as? com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject)?.image
                            bmp?.let { b -> File(fs.reader.app.documents.tempDir, "jsa_icon_${System.nanoTime()}.png").also { f -> f.outputStream().use { o -> b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o) } } }
                        }.getOrNull()
                    }
                    png ?: file      // an icon without a picture: copied on save
                }
            }.getOrNull()
        }
        when {
            found == null -> fs.message = FormMessage.NotFound(src.field.ifBlank { src.named })
            found == file -> fs.setIconCopy(target, src)
            else -> fs.setImage(target, found)
        }
    }

    // ------------------------------------------------------------------ navigation / named actions

    private fun named(fs: FormFillState, name: String) {
        val r = fs.reader
        when (com.smartdocs.editor.core.forms5.NamedActions.resolve(name)) {
            com.smartdocs.editor.core.forms5.NamedOp.NEXT -> r.jumpTo(r.currentPage + 1)
            com.smartdocs.editor.core.forms5.NamedOp.PREV -> r.jumpTo(r.currentPage - 1)
            com.smartdocs.editor.core.forms5.NamedOp.FIRST -> r.jumpTo(0)
            com.smartdocs.editor.core.forms5.NamedOp.LAST -> r.jumpTo(r.pageCount - 1)
            com.smartdocs.editor.core.forms5.NamedOp.PRINT -> r.scope.launch { print(fs) }
            com.smartdocs.editor.core.forms5.NamedOp.SAVE -> r.scope.launch { r.save() }
            // 8.69: the other common viewer commands
            com.smartdocs.editor.core.forms5.NamedOp.FIND -> r.searchOpen = true
            com.smartdocs.editor.core.forms5.NamedOp.ZOOM_IN -> r.zoom = com.smartdocs.editor.core.forms5.NamedActions.zoomStep(r.zoom, true, com.smartdocs.editor.ui.reader.MIN_ZOOM, com.smartdocs.editor.ui.reader.MAX_ZOOM)
            com.smartdocs.editor.core.forms5.NamedOp.ZOOM_OUT -> r.zoom = com.smartdocs.editor.core.forms5.NamedActions.zoomStep(r.zoom, false, com.smartdocs.editor.ui.reader.MIN_ZOOM, com.smartdocs.editor.ui.reader.MAX_ZOOM)
            com.smartdocs.editor.core.forms5.NamedOp.FIT_WIDTH -> { r.fitPage = false; r.zoom = 1f }
            com.smartdocs.editor.core.forms5.NamedOp.FIT_PAGE -> { r.fitPage = true; r.zoom = 1f }
            // the reader turns its chrome off for /PageMode FullScreen; the same request is reused here
            com.smartdocs.editor.core.forms5.NamedOp.FULL_SCREEN -> r.initialPageMode = "FullScreen"
            com.smartdocs.editor.core.forms5.NamedOp.SINGLE_PAGE -> r.viewMode = com.smartdocs.editor.ui.reader.ViewMode.SINGLE
            com.smartdocs.editor.core.forms5.NamedOp.ONE_COLUMN -> r.viewMode = com.smartdocs.editor.ui.reader.ViewMode.CONTINUOUS
            com.smartdocs.editor.core.forms5.NamedOp.TWO_PAGES, com.smartdocs.editor.core.forms5.NamedOp.TWO_COLUMNS -> r.viewMode = com.smartdocs.editor.ui.reader.ViewMode.FACING
            // 8.69: the reader's position history, closing the tab / leaving (unsaved-changes guard of the screen), save as
            com.smartdocs.editor.core.forms5.NamedOp.GO_BACK -> com.smartdocs.editor.ui.view5.V5State.of(r).back()
            com.smartdocs.editor.core.forms5.NamedOp.GO_FORWARD -> com.smartdocs.editor.ui.view5.V5State.of(r).forward()
            com.smartdocs.editor.core.forms5.NamedOp.CLOSE -> r.scriptClose = true
            com.smartdocs.editor.core.forms5.NamedOp.QUIT -> r.scriptQuit = true
            com.smartdocs.editor.core.forms5.NamedOp.SAVE_AS -> saveAs(fs, "")
            com.smartdocs.editor.core.forms5.NamedOp.UNSUPPORTED -> fs.message = FormMessage.Unsupported(name)
        }
    }

    private suspend fun gotoNamed(fs: FormFillState, name: String) {
        val f = fs.reader.file ?: return
        val page = withContext(Dispatchers.IO) {
            runCatching { PdfOps.load(f, fs.reader.password).use { d -> PdfActions.dest(d, COSString(name)).first } }.getOrDefault(-1)
        }
        if (page >= 0) fs.reader.jumpTo(page) else fs.message = FormMessage.NotFound(name)
    }

    /** A file named by an action: absolute, or relative to the open PDF's folder. */
    private fun resolve(fs: FormFillState, name: String): File? {
        if (name.isBlank()) return null
        val clean = name.removePrefix("file://").replace('\\', '/')
        val abs = File(clean)
        if (abs.isAbsolute && abs.exists()) return abs
        val dir = fs.reader.file?.parentFile ?: return null
        return File(dir, clean.trimStart('/')).takeIf { it.exists() } ?: File(dir, clean.substringAfterLast('/')).takeIf { it.exists() }
    }

    private suspend fun openOther(fs: FormFillState, name: String, page: Int, named: String?) {
        val f = resolve(fs, name)
        if (f == null || !f.extension.equals("pdf", true)) { fs.message = FormMessage.NotFound(name.substringAfterLast('/')); return }
        val p = if (page >= 0) page else if (named != null) withContext(Dispatchers.IO) {
            runCatching { PdfOps.load(f, null).use { d -> PdfActions.dest(d, COSString(named)).first } }.getOrDefault(0)
        } else 0
        fs.openRequest = f to p.coerceAtLeast(0)
    }

    // ------------------------------------------------------------------ reset / URI / submit

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

    /** Date fields (Acrobat date format scripts) and their patterns, for CanonicalFormat. */
    private fun datePatterns(fs: FormFillState): Map<String, String> =
        fs.model.byName.keys.mapNotNull { n ->
            val sc = fs.model.calc.scripts[n]
            if (FieldDates.isDateField(sc, n)) n to FieldDates.patternOf(sc) else null
        }.toMap()

    /** The document's comments as FDF /Annots entries (IncludeAnnotations). */
    private suspend fun annotsForFdf(fs: FormFillState): List<String> = withContext(Dispatchers.IO) {
        val f = fs.reader.file ?: return@withContext emptyList()
        runCatching {
            PdfOps.load(f, fs.reader.password).use { d ->
                val out = ArrayList<String>()
                d.pages.forEachIndexed { pi, page ->
                    runCatching { page.annotations }.getOrNull()?.forEach { an ->
                        val sub = an.subtype ?: return@forEach
                        if (sub == "Widget" || sub == "Link" || sub == "Popup") return@forEach
                        val r = an.rectangle ?: return@forEach
                        out += FormSubmit.fdfAnnot(sub, pi, floatArrayOf(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY),
                            an.contents.orEmpty(), (an as? PDAnnotationMarkup)?.titlePopup.orEmpty(), an.color?.components?.takeIf { it.size == 3 })
                    }
                }
                out
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun submit(fs: FormFillState, a: ButtonAction.Submit, includeAnnotations: Boolean = false, canonical: Boolean = false) {
        // XFA preSubmit: every field's validate test, then the preSubmit scripts (either can stop the submission)
        if (!fs.xfaFormEvent("preSubmit")) return
        // Acrobat refuses to submit while required fields are empty
        val missing = fs.model.byName.values.map { it.first() }.filter { w -> w.required && !fs.isHidden(w) && fs.validate(w, requiredToo = true) != null }
        if (missing.isNotEmpty()) {
            fs.message = FormMessage.MissingRequired(missing.size)
            fs.focus(missing.first().id)
            return
        }
        // an HTTP submission sends the form data to a server: "Manage Internet Access" decides first
        if (FormSubmit.isHttp(a.url) && !DocSecurityGate.allowWeb(fs.reader.context, a.url.trim())) return
        var values = FormSubmit.select(exportValues(fs), a)
        if (canonical) values = FormSubmit.canonicalDates(values, datePatterns(fs))
        val file = fs.reader.file ?: return
        val name = file.name
        fs.busy = true
        try {
            val pdf: File? = if (a.format == SubmitFormat.PDF) fs.exportSource(file, fs.reader.password).let { f -> if (f == file) file else f } else null
            val annots = if (includeAnnotations && a.format == SubmitFormat.FDF) annotsForFdf(fs) else emptyList()
            try {
                if (FormSubmit.isMail(a.url)) mail(fs, a, values, name, pdf, annots)
                else if (FormSubmit.isHttp(a.url)) {
                    val r = if (annots.isEmpty()) FormSubmitter.send(a, values, name, pdf) else FormSubmitter.sendBytes(a, FormSubmit.fdfWithAnnots(values, name, annots))
                    fs.message = if (r.code in 200..399) FormMessage.Submitted(r.code) else FormMessage.Failed("HTTP ${r.code} ${r.message}")
                    // the server's answer (Acrobat shows an HTML response in a browser window)
                    if (r.body.isNotBlank()) fs.response = r.mime.ifBlank { "text/plain" } to r.body
                } else fs.message = FormMessage.Failed(a.url.ifBlank { "?" })
            } finally { if (pdf != null && pdf != file) pdf.delete() }
        } catch (e: Exception) {
            fs.message = FormMessage.Failed(e.message ?: e.toString())
        } finally { fs.busy = false }
    }

    private suspend fun mail(fs: FormFillState, a: ButtonAction.Submit, values: Map<String, String>, name: String, pdf: File?, annots: List<String> = emptyList()) {
        val app = fs.reader.app
        val (ext, mime) = when (a.format) {
            SubmitFormat.PDF -> "pdf" to "application/pdf"
            SubmitFormat.XFDF -> "xfdf" to "application/vnd.adobe.xfdf"
            SubmitFormat.HTML -> "txt" to "text/plain"
            SubmitFormat.FDF -> "fdf" to "application/vnd.fdf"
        }
        val out = withContext(Dispatchers.IO) {
            val f = File(app.documents.tempDir, name.substringBeforeLast('.') + "_data." + ext)
            when {
                pdf != null && a.format == SubmitFormat.PDF -> pdf.copyTo(f, overwrite = true)
                annots.isNotEmpty() -> f.writeBytes(FormSubmit.fdfWithAnnots(values, name, annots))
                else -> f.writeBytes(FormSubmitter.payload(a, values, name, pdf))
            }
            f
        }
        val mailto = Uri.parse(a.url.trim())
        val to = mailto.schemeSpecificPart.substringBefore('?').split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
        val subject = runCatching { Uri.parse("mailto://x?" + a.url.substringAfter('?', "")).getQueryParameter("subject") }.getOrNull() ?: name
        sendMail(fs, out, mime, to, subject)
        fs.message = FormMessage.Submitted(0)
    }

    private fun sendMail(fs: FormFillState, file: File, mime: String, to: Array<String>, subject: String) {
        val send = fs.reader.app.documents.shareIntent(listOf(file), mime).apply {
            if (to.isNotEmpty()) putExtra(Intent.EXTRA_EMAIL, to)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        fs.reader.context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** this.mailDoc / mailForm: the filled PDF, or the form data as FDF, to an e-mail app. */
    private suspend fun mailData(fs: FormFillState, to: String, subject: String, wholeDoc: Boolean) {
        val file = fs.reader.file ?: return
        val name = file.name
        val out = withContext(Dispatchers.IO) {
            if (wholeDoc) {
                val src = fs.exportSource(file, fs.reader.password)
                File(fs.reader.app.documents.tempDir, name).also { src.copyTo(it, overwrite = true); if (src != file) src.delete() }
            } else File(fs.reader.app.documents.tempDir, name.substringBeforeLast('.') + "_data.fdf").also { it.writeBytes(FormSubmit.fdf(exportValues(fs), name)) }
        }
        runCatching { sendMail(fs, out, if (wholeDoc) "application/pdf" else "application/vnd.fdf", to.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray(), subject.ifBlank { name }) }
            .onFailure { fs.message = FormMessage.Failed(it.message ?: it.toString()) }
    }

    /** this.exportAsFDF / exportAsXFDF and the field bar's "send data": the values as a data file to share. */
    suspend fun shareData(fs: FormFillState, format: SubmitFormat) {
        val file = fs.reader.file ?: return
        val name = file.name
        val (ext, mime) = if (format == SubmitFormat.XFDF) "xfdf" to "application/vnd.adobe.xfdf" else "fdf" to "application/vnd.fdf"
        val out = withContext(Dispatchers.IO) {
            File(fs.reader.app.documents.tempDir, name.substringBeforeLast('.') + "_data." + ext).also {
                if (format == SubmitFormat.XFDF) it.writeText(FormSubmit.xfdf(exportValues(fs), name)) else it.writeBytes(FormSubmit.fdf(exportValues(fs), name))
            }
        }
        runCatching { sendMail(fs, out, mime, emptyArray(), name) }.onFailure { fs.message = FormMessage.Failed(it.message ?: it.toString()) }
    }

    /** 8.84 exportDataObject: the named embedded file of the document, handed to a share target. */
    private suspend fun exportDataObject(fs: FormFillState, name: String) {
        val file = fs.reader.file ?: return
        val out = withContext(Dispatchers.IO) {
            runCatching {
                val src = fs.exportSource(file, fs.reader.password)      // COPY-gated like every export
                com.smartdocs.editor.core.pdf.PdfOps.loadRaw(src, fs.reader.password).use { d ->
                    com.smartdocs.editor.core.forms5.DataObjects.find(d, name)?.let { o ->
                        File(fs.reader.app.documents.tempDir, com.smartdocs.editor.core.forms5.DataObjects.safeName(o.fileName)).also { it.writeBytes(o.bytes) }
                    }
                }
            }
        }
        val f = out.getOrNull()
        if (f == null) { fs.message = out.exceptionOrNull()?.let { FormMessage.Failed(it.message ?: it.toString()) } ?: FormMessage.Unsupported("exportDataObject: $name"); return }
        val send = fs.reader.app.documents.shareIntent(listOf(f), com.smartdocs.editor.core.annot.MediaAnnots.mimeOf(f.name))
        runCatching { fs.reader.context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
    }

    // ------------------------------------------------------------------ print / flatten / layers

    suspend fun print(fs: FormFillState) {
        val file = fs.reader.file ?: return
        fs.runDocAction("WP")
        if (!fs.xfaFormEvent("prePrint")) return
        val src = runCatching { fs.exportSource(file, fs.reader.password) }.getOrNull() ?: file
        val ok = runCatching { com.smartdocs.editor.core.util.PdfPrint.print(fs.reader.context, src, file.nameWithoutExtension, fs.reader.pageCount) }.isSuccess
        if (!ok) fs.message = FormMessage.PrintHint
        fs.runDocAction("DP")
    }

    /** this.flattenPages(): the form is flattened in place (pending values saved first; one undo step). */
    private suspend fun flatten(fs: FormFillState) {
        val r = fs.reader
        runCatching {
            r.applyInPlace(label = { r.context.getString(com.smartdocs.editor.R.string.f8_hi_flatten) }) { src, out, pw -> PdfOps.flattenForms(src, out, pw) }
        }.onFailure { fs.message = FormMessage.Failed(it.message ?: it.toString()) }
    }

    /** SetOCGState: the default layer visibility is written into the file (one undo step) and the pages redrawn. */
    private suspend fun setOcg(fs: FormFillState, a: PdfAction.SetOcg) {
        val r = fs.reader
        runCatching {
            r.applyInPlace(label = { r.context.getString(com.smartdocs.editor.R.string.f8_hi_layers) }) { src, out, pw ->
                withContext(Dispatchers.IO) { OcgState.applyFile(src, out, pw, a.ops, a.preserveRB) }
            }
        }.onFailure { fs.message = FormMessage.Failed(it.message ?: it.toString()) }
    }

    // ------------------------------------------------------------------ import data

    /** ImportData action / importAnFDF: a data file next to the PDF, else the bar asks for one. */
    private suspend fun importData(fs: FormFillState, name: String) {
        val f = resolve(fs, name)
        if (f == null) { fs.importPick = true; return }
        importFile(fs, f)
    }

    /** Import FDF / XFDF / XML / CSV values from [f] into the pending values (one undo step). */
    suspend fun importFile(fs: FormFillState, f: File) {
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = f.readBytes()
                val fmt = com.smartdocs.editor.core.formsx.FormData.detect(f.name, bytes.copyOf(minOf(bytes.size, 512))) ?: return@runCatching null
                com.smartdocs.editor.core.formsx.FormData.parse(bytes, fmt)
            }.getOrNull()
        }
        if (parsed == null) { fs.message = FormMessage.Failed(f.name); return }
        val values = toFillValues(fs, parsed)
        fs.setValues(values)
        fs.message = FormMessage.Imported(values.size)
    }

    /** Raw imported values (button on-state names, "Off") -> the fill conventions ("true"/"false", "" for no radio). */
    fun toFillValues(fs: FormFillState, raw: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        raw.forEach { (k, v) ->
            val w = fs.model.byName[k]?.firstOrNull() ?: return@forEach
            out[k] = when (w.kind) {
                WKind.CHECK -> if (v.isBlank() || v == "Off" || v.equals("false", true)) "false" else "true"
                WKind.RADIO -> if (v == "Off") "" else v
                WKind.BUTTON, WKind.IMAGE, WKind.SIGNATURE -> return@forEach
                else -> v
            }
        }
        return out
    }
}
