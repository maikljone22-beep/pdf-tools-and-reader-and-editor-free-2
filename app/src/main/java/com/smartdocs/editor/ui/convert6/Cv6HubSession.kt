package com.smartdocs.editor.ui.convert6

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert6.Conv
import com.smartdocs.editor.core.convert6.Cv6Failure
import com.smartdocs.editor.core.convert6.Cv6Opts
import com.smartdocs.editor.ui.tools5.T5Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * State of the Convert hub that outlives the screen (convert audit A-L16 / C11): the job runs in an app-wide scope,
 * so leaving the screen, switching the app language (activity recreated) or opening another tool does not cancel it,
 * and the sheet, inputs, options and results are still there when the user comes back. While a job runs a foreground
 * service with a progress notification keeps the process alive in the background; if the process is killed anyway,
 * the outputs journal removes the half-written files at the next start.
 */
object Cv6HubSession {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var jobRef: T5Job? = null

    fun job(ctx: Context): T5Job = jobRef ?: synchronized(this) {
        jobRef ?: T5Job(scope) { e -> Cv6ErrorText.of(ctx.applicationContext, e) }.also { jobRef = it }
    }

    var conv by mutableStateOf<Conv?>(null)
    var inputs by mutableStateOf<List<File>>(emptyList())
    var opts by mutableStateOf(Cv6Opts())
    var sheet by mutableStateOf(false)
    /** The protected input whose password the sheet asks for (one file at a time), and whether the last one was wrong. */
    var locked by mutableStateOf<File?>(null)
    var lockedWrong by mutableStateOf(false)
    var password by mutableStateOf("")
    /** Protected inputs still waiting for their password. */
    var pendingLocked by mutableStateOf<List<File>>(emptyList())
    /** Passwords typed for the inputs of this run (absolute path → password). */
    var passwords by mutableStateOf<Map<String, String>>(emptyMap())
    /** Outputs and failures of earlier passes of this run (before a password was asked). */
    var done by mutableStateOf<List<File>>(emptyList())
    var doneInfo by mutableStateOf<List<String>>(emptyList())
    var failures by mutableStateOf<List<Cv6Failure>>(emptyList())
    var multiResults by mutableStateOf<List<File>>(emptyList())
    var multiInfo by mutableStateOf<String?>(null)
    /** The hub is on screen (a finished job posts a notification otherwise). */
    @Volatile var visible = false

    /** A new conversion card was opened: forget the previous run's sheet state. */
    fun reset(c: Conv?, o: Cv6Opts) {
        conv = c; opts = o; locked = null; lockedWrong = false; password = ""; passwords = emptyMap(); pendingLocked = emptyList()
        done = emptyList(); doneInfo = emptyList(); failures = emptyList()
    }
}

/** Keeps the process in the foreground while a hub conversion runs (data-sync type), with its progress notification. */
class Cv6JobService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val ok = runCatching {
            channel(this)
            val n = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(getString(R.string.cvf_job_running))
                .setContentText(title)
                .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true)
                .setContentIntent(openApp(this))
                .build()
            ServiceCompat.startForeground(this, ID, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.isSuccess
        if (!ok) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "cv6_jobs"
        private const val ID = 0x6c76
        private const val EXTRA_TITLE = "title"

        private fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.cvf_job_channel), NotificationManager.IMPORTANCE_LOW))
        }

        private fun openApp(ctx: Context): PendingIntent? = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.let { PendingIntent.getActivity(ctx, ID, it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }

        fun start(ctx: Context, title: String) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, Cv6JobService::class.java).putExtra(EXTRA_TITLE, title)) }
        }

        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, Cv6JobService::class.java)) } }

        /** "Conversion finished / failed" when the hub is not on screen. */
        fun notifyDone(ctx: Context, ok: Boolean, text: String) = runCatching {
            if (!com.smartdocs.editor.core.data.NpfPrefs.notifyTasks) return@runCatching
            channel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(ctx.getString(if (ok) R.string.cvf_job_done else R.string.cvf_job_failed))
                .setContentText(text).setAutoCancel(true).setContentIntent(openApp(ctx)).build()
            ctx.getSystemService(NotificationManager::class.java)?.notify(ID + 1, n)
        }
    }
}
