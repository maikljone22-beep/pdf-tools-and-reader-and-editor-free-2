package com.smartdocs.editor.core.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import com.smartdocs.editor.core.cloud.Clouds
import com.smartdocs.editor.core.cloud.ProviderId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Near-real-time sync while the app is open (share6):
 *  - local: a ContentObserver on each auto pair's SAF tree (documents-provider change notifications); a change
 *    requests a run after 5 s of quiet; our own writes during and 4 s after a run are ignored;
 *  - cloud (fc-sync): while an activity is started, [ChangeWatch] long-polls Dropbox / Box for change notifications and
 *    polls the Drive / OneDrive delta feeds with a back-off (30 s → 5 min); pairs of a provider that changed are
 *    requested ([checkProvider]). The old fixed 60 s poll is gone;
 *  - WebDAV (fc-sync): [com.smartdocs.editor.core.webdav.DavWatch] compares folder ctags / file ETags of linked copies;
 *  - app folders (fc-sync): [AppFolderWatch] (FileObserver, no wake-ups) sees cloud / WebDAV working copies that other
 *    apps changed and queues their upload;
 *  - app opened: pairs not synced in the last 2 minutes are requested;
 *  - [onFileSaved]: a document saved in the reader whose source lies inside a paired folder is synced right away.
 * [install] is idempotent; it runs from SmartDocsApp (hook) and, as a fallback, the first time the Sync screen opens
 * or a document is saved.
 */
object SyncLive {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var installed = false
    @Volatile private var started = 0
    private var app: Context? = null
    private val observers = ConcurrentHashMap<String, ContentObserver>()
    private val debounce = ConcurrentHashMap<String, Job>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    const val LOCAL_DEBOUNCE_MS = 5_000L
    const val STALE_ON_OPEN_MS = 2 * 60_000L

    val foreground: Boolean get() = started > 0

    /** [inForeground]: true when called from a visible screen (the activity already started before this install). */
    fun install(context: Context, inForeground: Boolean = false) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            if (inForeground) started = 1
            val a = context.applicationContext
            app = a
            (a as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) { if (started++ == 0) onForeground(a) }
                override fun onActivityStopped(activity: Activity) { if (--started <= 0) { started = 0; onBackground() } }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
            scope.launch { SyncStore.pairs.collect { refresh(a) } }
            // Settings screen switches (AppSettings.autoSync / transferNetwork)
            (a as? com.smartdocs.editor.SmartDocsApp)?.let { sd -> scope.launch {
                sd.settings.settings.collect { s ->
                    val changed = SyncPrefs.appAutoSync != s.autoSync || SyncPrefs.appTransferWifiOnly != (s.transferNetwork != "any")
                    SyncPrefs.appAutoSync = s.autoSync; SyncPrefs.appTransferWifiOnly = s.transferNetwork != "any"
                    if (changed) { SyncWorker.schedule(a); refresh(a) }
                }
            } }
            SyncStore.load(a)
            com.smartdocs.editor.core.webdav.DavWatch.load(a)
            SyncWorker.schedule(a)
            if (com.smartdocs.editor.core.collab.CollabConfig.enabled && com.smartdocs.editor.core.collab.CollabStore.load(a).isNotEmpty())
                com.smartdocs.editor.core.collab.ReviewCheckWorker.schedule(a)
        }
    }

    /** Re-reads pairs and preferences: (un)registers folder observers and the cloud watcher. */
    fun refresh(context: Context) {
        val a = context.applicationContext
        val live = SyncPrefs.autoSync(a) && SyncPrefs.liveSync(a)
        val pairs = if (live) SyncStore.load(a).filter { it.auto } else emptyList()
        val wanted = pairs.associateBy { it.id }
        observers.keys.filter { !wanted.containsKey(it) }.forEach { id -> observers.remove(id)?.let { runCatching { a.contentResolver.unregisterContentObserver(it) } } }
        for (p in pairs) if (!observers.containsKey(p.id)) watchLocal(a, p)
        if (foreground && live) startRemote(a) else stopRemote()
        AppFolderWatch.refresh(a)
        // fc-sync: WebDAV working copies: ctag / ETag check (needs no sync pair, only the live switch)
        if (foreground && SyncPrefs.liveSync(a)) com.smartdocs.editor.core.webdav.DavWatch.start(a, scope) { foreground }
        else com.smartdocs.editor.core.webdav.DavWatch.stop()
    }

    private fun watchLocal(a: Context, p: SyncPair) {
        val tree = Uri.parse(p.treeUri)
        val children = runCatching { DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)) }.getOrNull() ?: return
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = onLocalChange(a, p.id)
            override fun onChange(selfChange: Boolean, uri: Uri?) = onLocalChange(a, p.id)
        }
        runCatching { a.contentResolver.registerContentObserver(children, true, obs) }.onSuccess { observers[p.id] = obs }
    }

    private fun onLocalChange(a: Context, pairId: String) {
        if (SyncRuntime.isQuiet(pairId)) return
        debounce.remove(pairId)?.cancel()
        debounce[pairId] = scope.launch {
            delay(LOCAL_DEBOUNCE_MS)
            if (!SyncRuntime.isQuiet(pairId)) SyncWorker.request(a, pairId)
        }
    }

    private fun onForeground(a: Context) {
        refresh(a)
        if (!SyncPrefs.autoSync(a)) return
        val now = System.currentTimeMillis()
        SyncStore.load(a).filter { it.auto && now - it.lastSync > STALE_ON_OPEN_MS }.forEach { SyncWorker.request(a, it.id) }
    }

    private fun onBackground() { stopRemote(); com.smartdocs.editor.core.webdav.DavWatch.stop() }

    private fun startRemote(a: Context) = ChangeWatch.start(a, scope)

    private fun stopRemote() = ChangeWatch.stop()

    /**
     * Reads [pid]'s delta feed once and requests its auto pairs when something changed. Returns true when the feed
     * reported a change. (Called by [ChangeWatch] after a notification or on its poll schedule.)
     */
    suspend fun checkProvider(a: Context, pid: ProviderId): Boolean {
        val pairs = SyncStore.load(a).filter { it.auto && it.provider == pid }
        if (pairs.isEmpty()) return false
        val provider = Clouds.provider(a, pid)
        if (!provider.isConnected()) return false
        if (pairs.all { SyncRuntime.networkOk(a, it.wifiOnly).not() }) return false
        val cursor = SyncStore.cursor(a, pid)
        if (cursor == null) { SyncStore.setCursor(a, pid, runCatching { provider.changeCursor() }.getOrNull()); return false }
        val (changed, next) = provider.changesSince(cursor)
        SyncStore.setCursor(a, pid, next)
        if (!changed) return false
        val now = System.currentTimeMillis()
        // our own uploads also show up in the feed: skip pairs that just finished
        pairs.filter { !SyncRuntime.isQuiet(it.id) && now - it.lastSync > 20_000 && SyncRuntime.networkOk(a, it.wifiOnly) }
            .forEach { SyncWorker.request(a, it.id) }
        return true
    }

    /** One round of change-feed checks, one per provider that has auto pairs. */
    suspend fun checkRemote(a: Context) {
        for (pid in ChangeWatch.Plan.wanted(SyncStore.load(a))) runCatching { checkProvider(a, pid) }
    }

    /** Called by SaveHooks after every save: syncs the pair whose folder contains the document's source. */
    fun onFileSaved(context: Context, file: File) {
        val a = context.applicationContext
        install(a, inForeground = true)
        if (!SyncPrefs.autoSync(a) || !SyncPrefs.syncOnSave(a)) return
        val origin = runCatching { (a as com.smartdocs.editor.SmartDocsApp).documents.originOf(file) }.getOrNull() ?: return
        SyncStore.load(a).filter { it.auto && contains(Uri.parse(it.treeUri), origin) }.forEach { SyncWorker.request(a, it.id) }
    }

    /** True when [doc] is a document inside the SAF tree [tree] (same provider, document id below the tree's root id). */
    fun contains(tree: Uri, doc: Uri): Boolean {
        if (tree.authority != doc.authority) return false
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return false
        val docId = runCatching { DocumentsContract.getDocumentId(doc) }.getOrNull() ?: return false
        return SyncRules.isBelow(treeId, docId)
    }

    /** Providers that currently have at least one pair (for the UI). */
    fun providersInUse(context: Context): Set<ProviderId> = SyncStore.load(context).map { it.provider }.toSet()
}
