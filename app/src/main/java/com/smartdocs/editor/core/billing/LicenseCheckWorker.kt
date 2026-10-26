package com.smartdocs.editor.core.billing

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Re-checks the signed licence with the licence server every 3 days (needs a connection). No-op in client mode. */
class LicenseCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!LicenseClient.configured) return Result.success()
        LicenseClient.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "license_check"

        /** Schedules (or cancels, in client mode) the periodic check. Idempotent. */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!LicenseClient.configured) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<LicenseCheckWorker>(3, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
