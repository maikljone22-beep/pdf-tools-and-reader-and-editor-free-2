package com.smartdocs.editor.core.tools5

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What another app shared with "Share to PDF": files (copied locally), a URL, plain text or HTML. */
class SharePayload(val files: List<File>, val url: String?, val text: String?, val html: String?, val subject: String?) {
    val isEmpty: Boolean get() = files.isEmpty() && url.isNullOrBlank() && text.isNullOrBlank() && html.isNullOrBlank()
}

/** Reads ACTION_SEND / ACTION_SEND_MULTIPLE intents for the Share-to-PDF screen (see MODULE_tools5_r5.md hook M2). */
object ShareImport {

    /** Set by MainActivity.handleIntent, consumed by ShareToPdfScreen. */
    @Volatile var pending: SharePayload? = null

    /** True for share intents this module should take: several files, or shared text / links (one shared file is opened in-app). */
    fun handles(intent: Intent?): Boolean {
        intent ?: return false
        return when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> true
            // open-all: one shared file opens in the in-app viewer (which offers Convert to PDF); text / links still go here
            Intent.ACTION_SEND -> intent.type != "application/pdf" && streams(intent).isEmpty()
            else -> false
        }
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
        else -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM))
    }

    private fun nameOf(ctx: Context, u: Uri): String? = runCatching {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: u.lastPathSegment?.substringAfterLast('/')

    private fun extFor(mime: String?): String = when {
        mime == null -> "bin"
        mime.startsWith("image/") -> mime.substringAfter('/').let { if (it == "jpeg") "jpg" else it }
        mime == "text/html" -> "html"; mime == "text/markdown" -> "md"; mime.startsWith("text/") -> "txt"
        mime == "application/epub+zip" -> "epub"; mime == "message/rfc822" -> "eml"
        mime.contains("wordprocessingml") -> "docx"; mime.contains("spreadsheetml") -> "xlsx"; mime.contains("presentationml") -> "pptx"
        mime == "application/pdf" -> "pdf"
        else -> "bin"
    }

    /** Copy the shared streams into [dir] (the senders' grants are temporary) and collect the text extras. Blocking: call off the main thread. */
    fun read(ctx: Context, intent: Intent, dir: File): SharePayload {
        dir.mkdirs()
        val files = streams(intent).mapIndexedNotNull { i, u ->
            runCatching {
                val mime = ctx.contentResolver.getType(u) ?: intent.type
                var name = nameOf(ctx, u)?.replace(Regex("""[\\/:*?"<>|]"""), "_") ?: "shared_$i"
                if (!name.contains('.')) name += "." + extFor(mime)
                val f = File(dir, "${System.currentTimeMillis()}_$i" + "_" + name)
                ctx.contentResolver.openInputStream(u)?.use { s -> f.outputStream().use { s.copyTo(it) } } ?: return@runCatching null
                f
            }.getOrNull()
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
        val url = text?.trim()?.takeIf { t -> !t.contains('\n') && (t.startsWith("http://") || t.startsWith("https://")) }
            ?: text?.let { Regex("""https?://\S+""").find(it)?.value }?.takeIf { text.trim().length < 300 }
        return SharePayload(files, url, if (url != null && text?.trim() == url) null else text, html, intent.getStringExtra(Intent.EXTRA_SUBJECT))
    }
}

/**
 * Virtual printer "SmartDocs PDF": any app's Print → this printer saves the PDF the print framework produced into
 * Documents/Printed and posts a notification that opens it. Declared in the manifest (hook M3).
 */
class T5PrintService : PrintService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = object : PrinterDiscoverySession() {
        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) { addPrinters(listOf(printer())) }
        override fun onStopPrinterDiscovery() {}
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
        override fun onStartPrinterStateTracking(printerId: PrinterId) { addPrinters(listOf(printer())) }
        override fun onStopPrinterStateTracking(printerId: PrinterId) {}
        override fun onDestroy() {}
    }

    private fun printer(): PrinterInfo {
        val id = generatePrinterId("smartdocs_pdf")
        val caps = PrinterCapabilitiesInfo.Builder(id)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
            .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A3, false)
            .addMediaSize(PrintAttributes.MediaSize.NA_LEGAL, false)
            .addResolution(PrintAttributes.Resolution("r300", "300 dpi", 300, 300), true)
            .setColorModes(PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        return PrinterInfo.Builder(id, "SmartDocs PDF", PrinterInfo.STATUS_IDLE).setCapabilities(caps).setDescription("PDF").build()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        if (!printJob.start()) return
        val label = printJob.info.label ?: "print"
        val data = printJob.document.data ?: run { printJob.fail("no data"); return }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(File(getExternalFilesDir(null) ?: filesDir, "Documents"), "Printed").apply { mkdirs() }
                    val base = TextNorm.safeFileName(label.removeSuffix(".pdf"), 80).ifBlank { "print" } + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val out = File(dir, "$base.pdf")
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(data).use { input -> out.outputStream().use { input.copyTo(it) } }
                    // nav-batch: the printer's "conversion settings" are a recipe (security, watermark, header/footer, PDF/A…)
                    val recipe = RecipeStore.printerRecipe(this@T5PrintService)
                    if (recipe == null) out else {
                        val tmp = File(cacheDir, "t5_print_tmp").apply { mkdirs() }
                        val runner = RecipeRunner(this@T5PrintService, tmp)
                        val e = try { runner.run(out, null, recipe.copy(output = recipe.output.copy(pattern = "{name}", sameFolder = false, conflict = Conflict.RENAME)), recipe.name, dir, 0) } finally { runner.close() }
                        e.output?.let(::File)?.takeIf { e.ok && it.exists() }?.also { out.delete() } ?: out
                    }
                }
            }
            result.onSuccess { f -> printJob.complete(); notifySaved(f) }.onFailure { e -> printJob.fail(e.message ?: "error") }
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) { printJob.cancel() }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun notifySaved(f: File) = runCatching {
        val nm = getSystemService(NotificationManager::class.java) ?: return@runCatching
        if (!com.smartdocs.editor.core.data.NpfPrefs.notifyTasks) return@runCatching   // nav-prefs: notification toggle
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "SmartDocs PDF", NotificationManager.IMPORTANCE_DEFAULT))
        // a content:// URI (file:// in an intent trips FileUriExposedException); MainActivity copies it like any shared PDF
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").setClassName(packageName, "com.smartdocs.editor.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pi = PendingIntent.getActivity(this, f.name.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("SmartDocs PDF").setContentText(f.name).setContentIntent(pi).setAutoCancel(true).build()
        nm.notify(f.name.hashCode(), n)
    }

    companion object { const val CHANNEL = "t5_print" }
}
