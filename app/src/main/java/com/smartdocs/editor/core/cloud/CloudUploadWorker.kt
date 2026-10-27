package com.smartdocs.editor.core.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Uploads one dirty cloud working copy back to its cloud item (audit DI-24). One unique job per local copy
 * ("cloud_up_<hash of path>"), needs a network, retried with exponential back-off. Idempotent: the bytes are hashed
 * first and nothing is sent when they are already the last uploaded ones. The dirty flag is cleared only when no newer
 * save happened during the upload ([CloudLinks.markUploaded]).
 */
class CloudUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val file = File(inputData.getString(KEY_PATH) ?: return Result.success())
        val link = CloudLinks.get(ctx, file) ?: return Result.success()
        if (!file.isFile) return Result.success()
        val gen = link.gen
        val hash = runCatching { CloudCopy.sha256(file) }.getOrElse { return Result.retry() }
        when (decide(link.dirty, link.uploadedHash, hash)) {
            Step.NOTHING -> return Result.success()
            Step.MARK_CLEAN -> { CloudLinks.markUploaded(ctx, file, link.item, hash, gen); return Result.success() }
            Step.UPLOAD -> Unit
        }
        val res = CloudLinks.writeBack(ctx, file) ?: return Result.success()
        return res.fold(
            onSuccess = { item ->
                CloudLinks.markUploaded(ctx, file, item, hash, gen)
                SaveHooks.emit(SaveHooks.Event.Uploaded(file, link.provider))
                Result.success()
            },
            onFailure = { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (runAttemptCount == 0) SaveHooks.emit(SaveHooks.Event.UploadFailed(file, link.provider, e))
                // sign-in needed or retried long enough: stop; the copy stays dirty (protected) and is re-queued by the next save / open
                if (e is AuthRequiredException || runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            },
        )
    }

    enum class Step { NOTHING, MARK_CLEAN, UPLOAD }

    companion object {
        const val KEY_PATH = "path"
        const val MAX_ATTEMPTS = 10

        /** Pure: what the job does with a copy whose current bytes hash to [localHash]. */
        fun decide(dirty: Boolean, uploadedHash: String?, localHash: String): Step = when {
            localHash == uploadedHash -> if (dirty) Step.MARK_CLEAN else Step.NOTHING // already in the cloud
            else -> Step.UPLOAD // saved here (dirty) or changed outside the reader (hash differs)
        }

        fun uniqueName(file: File): String = "cloud_up_" + CloudCopy.sha256Of(file.absolutePath).take(24)

        /**
         * Queues the upload of [file]. [replace] = a new save: appended after a running upload (which may have read
         * older bytes); otherwise an already queued job is kept.
         */
        fun enqueue(context: Context, file: File, replace: Boolean) {
            val req = OneTimeWorkRequestBuilder<CloudUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_PATH to file.absolutePath))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName(file), if (replace) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, req)
        }
    }
}
