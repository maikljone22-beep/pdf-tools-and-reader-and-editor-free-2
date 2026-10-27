package com.smartdocs.editor

import android.app.Application
import com.smartdocs.editor.core.data.AppDatabase
import com.smartdocs.editor.core.data.SettingsStore
import com.smartdocs.editor.core.files.DocumentRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.launch

class SmartDocsApp : Application() {
    lateinit var db: AppDatabase; private set
    lateinit var settings: SettingsStore; private set
    lateinit var documents: DocumentRepository; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.smartdocs.editor.core.util.CrashLog.install(this)
        PDFBoxResourceLoader.init(applicationContext)
        db = AppDatabase.build(this)
        settings = SettingsStore(this)
        documents = DocumentRepository(this, db)
        com.smartdocs.editor.core.search.IndexWorker.schedule(this)
        // share6
        runCatching { com.smartdocs.editor.core.sync.SyncLive.install(this) }                 // near-real-time sync, 15-min jobs, review notifications
        runCatching { com.smartdocs.editor.core.billing.LicenseCheckWorker.schedule(this) }   // licence re-check (no-op without LICENSE_URL)
        runCatching { com.smartdocs.editor.core.webdav.DavLinks.install(this) }                // nav-attach: WebDAV working copies are uploaded back on save
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { com.smartdocs.editor.core.share5.AppDataSync.autoSyncQuietly(this@SmartDocsApp) } }
        // nitro-protect: the script entry points read "Run JavaScript in PDF documents" through JsPolicy
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            runCatching { settings.settings.collect { com.smartdocs.editor.core.security.JsPolicy.enabled = it.runJavaScript } }
        }
    }

    companion object {
        lateinit var instance: SmartDocsApp; private set
    }
}
