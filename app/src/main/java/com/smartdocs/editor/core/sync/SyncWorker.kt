package com.smartdocs.editor.core.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import com.smartdocs.editor.R
import com.smartdocs.editor.core.cloud.AuthRequiredException
import java.util.concurrent.TimeUnit

/**
 * Runs sync pairs through [SyncRuntime.runPair].
 *  - Periodic: every 15 minutes, two jobs (Wi‑Fi-only pairs on unmetered networks, the others on any network),
 *    battery not low. Disabled by the [SyncPrefs.autoSync] master switch.
 *  - [syncNow]: manual, expedited, retries failed files immediately.
 *  - [request]: automatic trigger (save, local change, cloud change, app opened); expedited, coalesced per pair.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val only = inputData.getString(KEY_PAIR)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val expedited = inputData.getBoolean(KEY_EXPEDITED, false)
        // automatic runs honour Settings → auto sync (AppSettings.autoSync) and the sync screen's master switch; "sync now" ignores both
        if (!force) {
            val app = runCatching { (ctx as com.smartdocs.editor.SmartDocsApp).settings.settings.first() }.getOrNull()
            if (app != null) { SyncPrefs.appAutoSync = app.autoSync; SyncPrefs.appTransferWifiOnly = app.transferNetwork != "any" }
            if (app?.autoSync == false || !SyncPrefs.autoSync(ctx)) return Result.success()
            if (app != null && !com.smartdocs.editor.core.gaps6.TransferPolicy.allowed(ctx, app)) return Result.retry()
        }
        val wifiGroup = if (inputData.keyValueMap.containsKey(KEY_WIFI)) inputData.getBoolean(KEY_WIFI, true) else null
        val targets = SyncStore.load(ctx).filter { p ->
            when {
                only != null -> p.id == only
                else -> p.auto && (wifiGroup == null || p.wifiOnly == wifiGroup)
            }
        }
        // expedited work cannot have a battery constraint: when the battery is low, hand over to a constrained job
        if (expedited && !force && !SyncRuntime.batteryOk(ctx)) {
            targets.forEach { enqueue(ctx, it, force = false, expedited = false, policy = ExistingWorkPolicy.KEEP) }
            return Result.success()
        }
        var retry = false
        for (p in targets) {
            try { SyncRuntime.runPair(ctx, p.id, force) }
            catch (e: AuthRequiredException) { /* status stored; needs the user */ }
            catch (e: SecurityException) { /* permission lost; needs the user */ }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { retry = retry || e is java.io.IOException }
        }
        return if (retry && runAttemptCount < 3) Result.retry() else Result.success()
    }

    /** Needed for expedited work on Android 11 and older, which runs as a short foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(NOTIF_ID, notification(applicationContext))

    companion object {
        const val KEY_PAIR = "pair"
        const val KEY_WIFI = "wifi"
        const val KEY_FORCE = "force"
        const val KEY_EXPEDITED = "exp"
        /** Message codes the UI translates. */
        const val MSG_AUTH = "@auth"
        const val MSG_PERMISSION = "@permission"
        const val MSG_FILES_FAILED = "@files"
        const val CHANNEL = "sync"
        private const val NOTIF_ID = 0x5906

        private fun constraints(wifi: Boolean, battery: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifi || SyncPrefs.appTransferWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(battery)
            .build()

        fun notification(c: Context): android.app.Notification {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = c.getSystemService(NotificationManager::class.java)
                if (nm?.getNotificationChannel(CHANNEL) == null)
                    nm?.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.s6_channel_sync), NotificationManager.IMPORTANCE_LOW))
            }
            return NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(c.getString(R.string.s6_notif_syncing)).setOngoing(true).setSilent(true)
                .setProgress(0, 0, true).build()
        }

        /** (Re)schedules the periodic jobs according to the configured pairs and the master switch. Call after any change. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val on = SyncPrefs.autoSync(context)
            val pairs = SyncStore.load(context).filter { it.auto }
            for (wifi in listOf(true, false)) {
                val name = if (wifi) "sync_periodic_wifi" else "sync_periodic_any"
                if (on && pairs.any { it.wifiOnly == wifi }) {
                    val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                        .setConstraints(constraints(wifi, battery = true))
                        .setInputData(workDataOf(KEY_WIFI to wifi))
                        .build()
                    wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
                } else wm.cancelUniqueWork(name)
            }
        }

        private fun enqueue(context: Context, pair: SyncPair, force: Boolean, expedited: Boolean, policy: ExistingWorkPolicy) {
            val b = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(pair.wifiOnly, battery = !expedited && !force))
                .setInputData(workDataOf(KEY_PAIR to pair.id, KEY_FORCE to force, KEY_EXPEDITED to expedited))
            if (expedited) b.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            val name = if (force) "sync_now_${pair.id}" else if (expedited) "sync_req_${pair.id}" else "sync_low_${pair.id}"
            WorkManager.getInstance(context).enqueueUniqueWork(name, policy, b.build())
        }

        /** Manual "sync now": runs as soon as the network allows, failed files are retried immediately. */
        fun syncNow(context: Context, pair: SyncPair) {
            SyncStore.update(context, pair.id) { it.copy(status = SyncStatus.PENDING) }
            SyncStore.retry(context, pair.id, null)
            enqueue(context, pair, force = true, expedited = true, policy = ExistingWorkPolicy.REPLACE)
        }

        fun syncAllNow(context: Context) = SyncStore.load(context).forEach { syncNow(context, it) }

        /** Automatic trigger for one pair; coalesced (KEEP), or folded into a rerun when the pair is already running. */
        fun request(context: Context, pairId: String) {
            if (!SyncPrefs.autoSync(context)) return
            val pair = SyncStore.load(context).firstOrNull { it.id == pairId && it.auto } ?: return
            if (SyncRuntime.isRunning(pairId)) { SyncRuntime.markRerun(pairId); return }
            enqueue(context, pair, force = false, expedited = true, policy = ExistingWorkPolicy.KEEP)
        }

        fun cancel(context: Context, pairId: String) {
            val wm = WorkManager.getInstance(context)
            listOf("sync_now_", "sync_req_", "sync_low_").forEach { wm.cancelUniqueWork(it + pairId) }
        }
    }
}
