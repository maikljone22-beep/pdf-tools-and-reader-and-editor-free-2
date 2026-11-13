package com.smartdocs.editor

import android.app.Application
import com.smartdocs.editor.core.data.AppDatabase
import com.smartdocs.editor.core.data.SettingsStore
import com.smartdocs.editor.core.files.DocumentRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SmartDocsApp : Application() {
    lateinit var db: AppDatabase; private set
    lateinit var settings: SettingsStore; private set
    lateinit var documents: DocumentRepository; private set

    override fun onCreate() {
        val T = com.smartdocs.editor.core.util.StartupTrace   // times every step below; one log line at the first frame
        T.appCreateBegins()
        super.onCreate()
        instance = this
        T.step("error-centre") { com.smartdocs.editor.core.errors.ErrorCenter.install(this) }   // error centre: crashes + reported failures, auto-send
        com.smartdocs.editor.core.errors.ErrorCenter.onNew = { com.smartdocs.editor.core.errors.ErrorReporter.schedule(this) }
        T.step("crash-log") { com.smartdocs.editor.core.util.CrashLog.install(this) }
        // form designer: Arabic captions and values use the bundled Naskh face
        com.smartdocs.editor.core.formsx.FxAppearance.arabicFontSource = { assets.open("fonts/NotoNaskhArabic-Regular.ttf") }
        T.step("pdfbox") { PDFBoxResourceLoader.init(applicationContext) }
        T.step("database") { db = AppDatabase.build(this) }
        T.step("settings") { settings = SettingsStore(this) }
        T.step("nav-prefs") { com.smartdocs.editor.core.data.NpfPrefs.init(this) }   // nav-prefs: "More preferences"
        T.step("reader-prefs") { com.smartdocs.editor.core.rpf.RpfPrefs.init(this) }    // reader-prefs: page display / full-screen preferences
        T.step("ai-consent") { com.smartdocs.editor.core.ai.AiConsent.init(this) }     // audit C7: AI content-sharing consent (default: not given)
        T.step("fc-prefs") { com.smartdocs.editor.core.data.FcsPrefs.init(this) }   // fc-settings: ConnectedPDF off, debug log
        T.step("debug-log") { com.smartdocs.editor.core.util.DebugLog.install(this) }
        T.step("documents") { documents = DocumentRepository(this, db) }
        T.step("index-worker") { com.smartdocs.editor.core.search.IndexWorker.schedule(this) }
        T.step("error-reporter") { com.smartdocs.editor.core.errors.ErrorReporter.schedule(this) }   // send errors left from the last run
        T.step("watched-folders") { com.smartdocs.editor.core.tools5.WatchedFolders.schedule(this) }   // FINAL_CHECK_2 #22: watched folders keep being checked
        T.step("save-folder") { runCatching { com.smartdocs.editor.core.files.NpfSaveFolder.start(this) } }   // nav-prefs: default save folder + index of new outputs
        // share6
        T.step("sync") { runCatching { com.smartdocs.editor.core.sync.SyncLive.install(this) } }                 // near-real-time sync, 15-min jobs, review notifications
        T.step("licence-check") { runCatching { com.smartdocs.editor.core.billing.LicenseCheckWorker.schedule(this) } }   // licence re-check (no-op without LICENSE_URL)
        T.step("webdav") { runCatching { com.smartdocs.editor.core.webdav.DavLinks.install(this) } }                // nav-attach: WebDAV working copies are uploaded back on save
        T.step("trust-lists") { runCatching { com.smartdocs.editor.core.sign.TrustLists.schedule(this) } }              // nitro-protect2: monthly EU trusted-list update (when on)
        T.step("convert-platform") { com.smartdocs.editor.core.convert6.Cv6Platform.install(this) }   // convert audit: picture decoder for the writers, localised labels
        T.step("alt-text") { runCatching { com.smartdocs.editor.core.tools5.AltTextVision.install(this, settings) } }  // pages-convert: AI alt text sees the figure
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            // convert audit C2 / C11 / A-M14: half-written outputs of a run the system killed, stale temp files
            runCatching { com.smartdocs.editor.core.convert6.Cv6Platform.cleanUp(this@SmartDocsApp, documents) }
            // review: an original left half-written by a write-back the system killed is written again from the working copy
            runCatching { documents.repairInterruptedWriteBacks() }
            // scanner: a PDF the app was adding pages to when it died gets its original back
            runCatching { com.smartdocs.editor.core.tools5.ScanAppendJournal.recover(this@SmartDocsApp) }
            // the library is in app storage (deleted with the app): its documents get a copy in Documents/SmartDocs once
            runCatching { com.smartdocs.editor.core.files.PublicCopies.publishExisting(this@SmartDocsApp, documents) }
            // nitro-fillsign2: temporary signatures of the previous session go first (they are never synced anyway)
            runCatching { com.smartdocs.editor.core.data.TemporarySignatures.cleanUp(this@SmartDocsApp) }
            runCatching { com.smartdocs.editor.core.share5.AppDataSync.autoSyncQuietly(this@SmartDocsApp) }
        }
        // nitro-protect: the script entry points read "Run JavaScript in PDF documents" through JsPolicy
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            // nitro-protect2: only a change of the switch resets it (a document's own decision is made when its forms load)
            runCatching { settings.settings.map { it.runJavaScript }.distinctUntilChanged().collect { com.smartdocs.editor.core.security.JsPolicy.enabled = it } }
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            runCatching { settings.settings.collect { com.smartdocs.editor.core.gaps6.AiGate.appEnabled = it.aiEnabled } }   // nav-prefs: AI switch mirror
        }
        T.appCreateEnds()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // the system is running short: drop rendered pages (they are drawn again on demand) before it kills the app
        if (level >= TRIM_MEMORY_RUNNING_LOW) com.smartdocs.editor.core.pdf.PdfSession.trimAll()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.smartdocs.editor.core.pdf.PdfSession.trimAll()
    }

    companion object {
        lateinit var instance: SmartDocsApp; private set
    }
}
