package com.smartdocs.editor.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.smartdocs.editor.core.cloud.AuthRequiredException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/** Live progress of a running pair. */
data class SyncProgress(val pairId: String, val currentFile: String?, val done: Int, val startedAt: Long)

/**
 * Shared runtime state of sync: one lock per pair (runs never overlap), a "run again" flag when a change arrives
 * during a run, the quiet period after our own writes (so the local watcher ignores them), live progress, and
 * network / battery checks for expedited work.
 */
object SyncRuntime {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val rerun = ConcurrentHashMap.newKeySet<String>()
    private val quietUntil = ConcurrentHashMap<String, Long>()
    private val _progress = MutableStateFlow<Map<String, SyncProgress>>(emptyMap())
    val progress: StateFlow<Map<String, SyncProgress>> = _progress

    fun lock(pairId: String): Mutex = locks.getOrPut(pairId) { Mutex() }
    fun isRunning(pairId: String) = locks[pairId]?.isLocked == true

    /** A change arrived while [pairId] runs: do one more pass afterwards. */
    fun markRerun(pairId: String) { rerun += pairId }
    fun takeRerun(pairId: String): Boolean = rerun.remove(pairId)

    fun quiet(pairId: String, ms: Long = 4_000) { quietUntil[pairId] = System.currentTimeMillis() + ms }
    fun isQuiet(pairId: String) = isRunning(pairId) || (quietUntil[pairId] ?: 0L) > System.currentTimeMillis()

    fun setProgress(p: SyncProgress?, pairId: String) {
        _progress.value = if (p == null) _progress.value - pairId else _progress.value + (pairId to p)
    }

    /** Network usable for [wifiOnly] pairs right now (Wi‑Fi / Ethernet = unmetered). */
    fun networkOk(context: Context, wifiOnly: Boolean): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !(wifiOnly || SyncPrefs.appTransferWifiOnly) || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Battery not low (≥ 15 %) or charging. Expedited work cannot carry a battery constraint, so it is checked by hand. */
    fun batteryOk(context: Context): Boolean {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return true
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return bm.isCharging || level <= 0 || level >= 15
    }

    /**
     * Runs one pair under its lock, updating its stored status and live progress. Returns false when the pair
     * was already running (the caller's request is then folded into a rerun).
     */
    suspend fun runPair(context: Context, pairId: String, force: Boolean, deletes: DeleteGuard.Policy = DeleteGuard.Policy.GUARD): Boolean {
        val mutex = lock(pairId)
        if (!mutex.tryLock()) { markRerun(pairId); return false }
        try {
            var passes = 0
            do {
                val p = SyncStore.load(context).firstOrNull { it.id == pairId } ?: return true
                SyncStore.update(context, p.id) { it.copy(status = SyncStatus.RUNNING, message = null) }
                val started = System.currentTimeMillis()
                setProgress(SyncProgress(p.id, null, 0, started), p.id)
                val engine = SyncEngine(context, p, force = force && passes == 0, onProgress = { name, done -> setProgress(SyncProgress(p.id, name, done, started), p.id) },
                    deletes = if (passes == 0) deletes else DeleteGuard.Policy.GUARD)
                try {
                    engine.run()
                    SyncStore.update(context, p.id) {
                        val held = engine.withheldDeletes
                        it.copy(status = when { held > 0 -> SyncStatus.NEEDS_CONFIRMATION; engine.failed > 0 -> SyncStatus.FAILED; else -> SyncStatus.SYNCED },
                            lastSync = System.currentTimeMillis(),
                            message = when { held > 0 -> SyncWorker.MSG_CONFIRM_DELETES; engine.failed > 0 -> SyncWorker.MSG_FILES_FAILED; else -> null },
                            uploaded = engine.uploaded, downloaded = engine.downloaded, conflicts = engine.conflicts, failed = engine.failed, pendingDeletes = held)
                    }
                } catch (e: AuthRequiredException) {
                    SyncStore.update(context, p.id) { it.copy(status = SyncStatus.FAILED, message = SyncWorker.MSG_AUTH) }; throw e
                } catch (e: SecurityException) {
                    SyncStore.update(context, p.id) { it.copy(status = SyncStatus.FAILED, message = SyncWorker.MSG_PERMISSION) }; throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    SyncStore.update(context, p.id) { it.copy(status = SyncStatus.PENDING) }; throw e
                } catch (e: Exception) {
                    SyncStore.update(context, p.id) { it.copy(status = SyncStatus.FAILED, message = e.message ?: e.toString()) }; throw e
                } finally {
                    setProgress(null, p.id)
                    quiet(p.id)
                }
                passes++
            } while (takeRerun(pairId) && passes < 3)
        } finally {
            mutex.unlock()
        }
        return true
    }
}
