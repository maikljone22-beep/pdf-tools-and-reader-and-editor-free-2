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
        com.smartdocs.editor.core.data.NpfPrefs.init(this)   // nav-prefs: "More preferences"
        documents = DocumentRepository(this, db)
        com.smartdocs.editor.core.search.IndexWorker.schedule(this)
        runCatching { com.smartdocs.editor.core.files.NpfSaveFolder.start(this) }   // nav-prefs: default save folder + index of new outputs
        // share6
        runCatching { com.smartdocs.editor.core.sync.SyncLive.install(this) }                 // near-real-time sync, 15-min jobs, review notifications
        runCatching { com.smartdocs.editor.core.billing.LicenseCheckWorker.schedule(this) }   // licence re-check (no-op without LICENSE_URL)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { com.smartdocs.editor.core.share5.AppDataSync.autoSyncQuietly(this@SmartDocsApp) } }
        // nitro-protect: the script entry points read "Run JavaScript in PDF documents" through JsPolicy
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            runCatching { settings.settings.collect { com.smartdocs.editor.core.security.JsPolicy.enabled = it.runJavaScript; com.smartdocs.editor.core.gaps6.AiGate.appEnabled = it.aiEnabled } }
        }
    }

    companion object {
        lateinit var instance: SmartDocsApp; private set
    }
}
