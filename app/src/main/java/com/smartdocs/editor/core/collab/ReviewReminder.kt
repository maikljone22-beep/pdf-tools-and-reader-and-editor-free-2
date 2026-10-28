package com.smartdocs.editor.core.collab

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.smartdocs.editor.R
import java.util.concurrent.TimeUnit

/**
 * fc-sync (Foxit §37 "remind me before a review expires"): a local notification before the deadline of a shared
 * review. Purely on the device: one WorkManager job per review, due [LEAD_MS] before the deadline, no network and no
 * server. Re-scheduled whenever a review is stored ([CollabStore.upsert]), cancelled when it is closed or removed.
 */
object ReviewReminder {
    const val LEAD_MS = 24 * 3600_000L
    /** A deadline closer than this is not announced any more (the user is looking at it anyway). */
    const val MIN_AHEAD_MS = 30 * 60_000L

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("review_reminder", Context.MODE_PRIVATE)

    /** Reminders on (default) or off for every review. */
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("on", on).apply()
        CollabStore.load(c).forEach { schedule(c, it) }
    }

    /** Pure: when to remind for [deadline] (null = no reminder: no deadline, closed, or too close / past). */
    fun fireAt(deadline: Long, closed: Boolean, now: Long, lead: Long = LEAD_MS): Long? {
        if (deadline <= 0 || closed || deadline - now < MIN_AHEAD_MS) return null
        return maxOf(now, deadline - lead)
    }

    private fun name(id: String) = "review_remind_$id"

    fun schedule(c: Context, ref: ReviewRef) {
        val wm = runCatching { WorkManager.getInstance(c.applicationContext) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val at = if (enabled(c)) fireAt(ref.deadline, ref.closed, now) else null
        if (at == null) { prefs(c).edit().remove("d_${ref.id}").apply(); wm.cancelUniqueWork(name(ref.id)); return }
        // same deadline already scheduled: keep the job (KEEP would also keep a stale one after a deadline change)
        val key = "${ref.deadline}"
        if (prefs(c).getString("d_${ref.id}", null) == key) return
        prefs(c).edit().putString("d_${ref.id}", key).apply()
        val req = OneTimeWorkRequestBuilder<ReviewReminderWorker>()
            .setInitialDelay((at - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("id" to ref.id))
            .build()
        wm.enqueueUniqueWork(name(ref.id), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(c: Context, id: String) {
        prefs(c).edit().remove("d_$id").apply()
        runCatching { WorkManager.getInstance(c.applicationContext).cancelUniqueWork(name(id)) }
    }

    internal fun notify(c: Context, ref: ReviewRef) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(ReviewCheckWorker.CHANNEL) == null)
                nm?.createNotificationChannel(NotificationChannel(ReviewCheckWorker.CHANNEL, c.getString(R.string.s6_channel_review), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CollabClient.deepLink(ref.id, ref.code))).setPackage(c.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(c, ref.id.hashCode() xor 0x5a, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, ReviewCheckWorker.CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(c.getString(R.string.fcy_remind_title, ref.title))
            .setContentText(c.getString(R.string.fcy_remind_text, com.smartdocs.editor.core.files.DocumentRepository.formatDate(ref.deadline)))
            .setAutoCancel(true).setContentIntent(pi).build()
        runCatching { NotificationManagerCompat.from(c).notify(0x5700 + (ref.id.hashCode() and 0xff), n) }
    }
}

/** Posts the reminder of one review (if it still exists, is open and has the same deadline). */
class ReviewReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val ref = CollabStore.get(c, inputData.getString("id") ?: return Result.success()) ?: return Result.success()
        if (!ReviewReminder.enabled(c) || ref.closed || ref.deadline <= System.currentTimeMillis()) return Result.success()
        ReviewReminder.notify(c, ref)
        return Result.success()
    }
}
