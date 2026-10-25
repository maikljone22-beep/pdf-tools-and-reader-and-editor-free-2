package com.smartdocs.editor.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.smartdocs.editor.core.cloud.AuthRequiredException
import java.util.concurrent.TimeUnit

/**
 * Runs [SyncEngine] for sync pairs. Two periodic jobs (hourly): one on unmetered networks for "Wi‑Fi only" pairs,
 * one on any network for the others. "Sync now" enqueues a one-time job for one pair.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val only = inputData.getString(KEY_PAIR)
        val wifiGroup = if (inputData.keyValueMap.containsKey(KEY_WIFI)) inputData.getBoolean(KEY_WIFI, true) else null
        val targets = SyncStore.load(ctx).filter { p ->
            when {
                only != null -> p.id == only
                else -> p.auto && (wifiGroup == null || p.wifiOnly == wifiGroup)
            }
        }
        var retry = false
        for (p in targets) {
            SyncStore.update(ctx, p.id) { it.copy(status = SyncStatus.RUNNING, message = null) }
            val engine = SyncEngine(ctx, p)
            try {
                engine.run()
                SyncStore.update(ctx, p.id) {
                    it.copy(status = SyncStatus.SYNCED, lastSync = System.currentTimeMillis(), message = null,
                        uploaded = engine.uploaded, downloaded = engine.downloaded, conflicts = engine.conflicts)
                }
            } catch (e: AuthRequiredException) {
                SyncStore.update(ctx, p.id) { it.copy(status = SyncStatus.FAILED, message = MSG_AUTH) }
            } catch (e: SecurityException) {
                SyncStore.update(ctx, p.id) { it.copy(status = SyncStatus.FAILED, message = MSG_PERMISSION) }
            } catch (e: Exception) {
                retry = retry || e is java.io.IOException
                SyncStore.update(ctx, p.id) { it.copy(status = SyncStatus.FAILED, message = e.message ?: e.toString()) }
            }
        }
        return if (retry && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_PAIR = "pair"
        const val KEY_WIFI = "wifi"
        /** Message codes the UI translates. */
        const val MSG_AUTH = "@auth"
        const val MSG_PERMISSION = "@permission"

        private fun constraints(wifi: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** (Re)schedules the periodic jobs according to the configured pairs. Call after any change. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            val pairs = SyncStore.load(context).filter { it.auto }
            for (wifi in listOf(true, false)) {
                val name = if (wifi) "sync_periodic_wifi" else "sync_periodic_any"
                if (pairs.any { it.wifiOnly == wifi }) {
                    val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                        .setConstraints(constraints(wifi))
                        .setInputData(workDataOf(KEY_WIFI to wifi))
                        .build()
                    wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
                } else wm.cancelUniqueWork(name)
            }
        }

        fun syncNow(context: Context, pair: SyncPair) {
            SyncStore.update(context, pair.id) { it.copy(status = SyncStatus.PENDING) }
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(pair.wifiOnly))
                .setInputData(workDataOf(KEY_PAIR to pair.id))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync_now_${pair.id}", ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context, pairId: String) = WorkManager.getInstance(context).cancelUniqueWork("sync_now_$pairId")
    }
}
