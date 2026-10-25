package com.smartdocs.editor.core.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.smartdocs.editor.MainActivity
import com.smartdocs.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps [ReadAloudController] alive while reading and shows a media-style
 * notification with play/pause, stop and next-page actions.
 */
class ReadAloudService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Contract of startForegroundService(): startForeground() must be called on every start, even when we
        // are about to stop — otherwise the system kills the process ("did not then call startForeground").
        showForeground(buildNotification(ReadAloudController.status.value))
        when (intent?.action) {
            ACTION_TOGGLE -> ReadAloudController.toggle()
            ACTION_STOP -> { ReadAloudController.stop(); stopSelfSafely(); return START_NOT_STICKY }
            ACTION_NEXT -> ReadAloudController.nextPage()
            ACTION_PREV -> ReadAloudController.prevPage()
        }
        val st = ReadAloudController.status.value
        if (st.state == ReadAloudController.State.IDLE) { stopSelfSafely(); return START_NOT_STICKY }
        showForeground(buildNotification(st))
        if (watcher == null) watcher = scope.launch {
            ReadAloudController.status.collect { s ->
                if (s.state == ReadAloudController.State.IDLE) stopSelfSafely()
                else runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s)) }
            }
        }
        return START_NOT_STICKY
    }

    private fun showForeground(n: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIF_ID, n)
        }
    }

    private fun stopSelfSafely() {
        watcher?.cancel(); watcher = null
        runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) }
        stopSelf()
    }

    override fun onDestroy() { watcher?.cancel(); scope.cancel(); super.onDestroy() }

    private fun action(act: String): PendingIntent =
        PendingIntent.getService(this, act.hashCode(), Intent(this, ReadAloudService::class.java).setAction(act), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildNotification(s: ReadAloudController.Status): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playing = s.state == ReadAloudController.State.PLAYING
        val title = s.docTitle.ifBlank { getString(R.string.tts_notification) }
        val text = getString(R.string.page_of, s.page + 1, s.pageCount)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(android.R.drawable.ic_media_previous, getString(R.string.previous), action(ACTION_PREV))
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, getString(if (playing) R.string.tts_pause else R.string.tts_play), action(ACTION_TOGGLE))
            .addAction(android.R.drawable.ic_media_next, getString(R.string.next), action(ACTION_NEXT))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.tts_stop), action(ACTION_STOP))
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
            .build()
    }

    companion object {
        const val CHANNEL_ID = "read_aloud"
        const val NOTIF_ID = 4101
        const val ACTION_START = "com.smartdocs.editor.tts.START"
        const val ACTION_UPDATE = "com.smartdocs.editor.tts.UPDATE"
        const val ACTION_TOGGLE = "com.smartdocs.editor.tts.TOGGLE"
        const val ACTION_STOP = "com.smartdocs.editor.tts.STOP"
        const val ACTION_NEXT = "com.smartdocs.editor.tts.NEXT"
        const val ACTION_PREV = "com.smartdocs.editor.tts.PREV"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.tts_notification), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
            }
        }
    }
}
