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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smartdocs.editor.R
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Background check of shared reviews every 30 minutes: posts "N new comments in <title>" for comments other
 * reviewers added since the last merge / notification. Tapping opens the review (smartdocs://review/<id>?c=<code>).
 * Nothing is written into the PDFs here; merging happens when the review or the document is opened.
 */
class ReviewCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (!CollabConfig.enabled) return Result.success()
        if (com.smartdocs.editor.core.share5.ReviewLocal.offline(c)) return Result.success()   // nav-attach: "work offline"
        val me = CollabStore.participantId(c)
        for (ref in CollabStore.load(c).filter { !it.closed }) {
            if (CollabLive.isAttached(java.io.File(ref.localPath))) continue
            val remote = try { CollabClient.comments(ref.id, ref.code) } catch (e: Exception) {
                if (CollabClient.isGone(e)) CollabStore.upsert(c, ref.copy(closed = true)); continue
            }
            val plan = CollabStore.planFor(c, ref, remote, emptySet())
            val known = notified(c, ref.id)
            val fresh = remote.filter { it.nm in plan.upserts && it.participant != me && it.nm !in known && !it.deleted }.map { it.nm }.distinct()
            if (fresh.isEmpty()) continue
            setNotified(c, ref.id, known + fresh)
            notify(c, ref, fresh.size)
        }
        return Result.success()
    }

    companion object {
        const val CHANNEL = "review"
        private const val NAME = "review_check"

        /** nav-attach: the interval is a preference ([com.smartdocs.editor.core.share5.ReviewLocal.interval], 0 = off); [update] applies a change. */
        fun schedule(c: Context, update: Boolean = false) {
            val minutes = com.smartdocs.editor.core.share5.ReviewLocal.interval(c)
            if (minutes <= 0) { WorkManager.getInstance(c).cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<ReviewCheckWorker>(minutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(NAME, if (update) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, req)
        }

        private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("collab_notified", Context.MODE_PRIVATE)
        private fun notified(c: Context, id: String): Set<String> = runCatching {
            val a = JSONArray(prefs(c).getString(id, "[]")); (0 until a.length()).map { a.getString(it) }.toSet()
        }.getOrDefault(emptySet())
        private fun setNotified(c: Context, id: String, s: Set<String>) =
            prefs(c).edit().putString(id, JSONArray(s.toList().takeLast(2000)).toString()).apply()

        fun notify(c: Context, ref: ReviewRef, count: Int) {
            runCatching { com.smartdocs.editor.core.share5.ReviewLocal.addNote(c, ref.id, ref.title, count) }   // nav-attach: in-app inbox
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = c.getSystemService(NotificationManager::class.java)
                if (nm?.getNotificationChannel(CHANNEL) == null)
                    nm?.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.s6_channel_review), NotificationManager.IMPORTANCE_DEFAULT))
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CollabClient.deepLink(ref.id, ref.code))).setPackage(c.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(c, ref.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(c.resources.getQuantityString(R.plurals.s6_new_comments, count, count))
                .setContentText(ref.title).setAutoCancel(true).setContentIntent(pi).build()
            runCatching { NotificationManagerCompat.from(c).notify(0x5600 + (ref.id.hashCode() and 0xff), n) }
        }
    }
}
