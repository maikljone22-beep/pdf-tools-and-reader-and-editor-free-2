package com.smartdocs.editor

import android.app.Application
import com.smartdocs.editor.core.data.AppDatabase
import com.smartdocs.editor.core.data.SettingsStore
import com.smartdocs.editor.core.files.DocumentRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

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
    }

    companion object {
        lateinit var instance: SmartDocsApp; private set
    }
}
