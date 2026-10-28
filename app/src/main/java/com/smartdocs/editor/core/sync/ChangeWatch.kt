package com.smartdocs.editor.core.sync

import android.content.Context
import com.smartdocs.editor.core.cloud.AuthRequiredException
import com.smartdocs.editor.core.cloud.Clouds
import com.smartdocs.editor.core.cloud.ProviderId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * fc-sync: how cloud changes are detected while the app is open (replaces the fixed 60 s poll of share6).
 *
 * One watcher per provider that has auto pairs, only while an activity is started:
 *  - Dropbox and Box: change notifications. A long-poll ([com.smartdocs.editor.core.cloud.CloudProvider.waitForChange])
 *    is held open until the server reports a change; then the delta feed is read once and the pairs are requested.
 *  - Google Drive and OneDrive: their delta feeds (`changes.list`, `/root/delta`) are polled with a back-off:
 *    30 s after something changed, doubling while nothing does, up to 5 minutes. (Real push for these two needs an
 *    HTTPS webhook server: Drive `changes.watch`, Graph subscriptions. Not deployed: see FINAL_CHECK fc-sync.)
 *  - Errors back off from 30 s to 15 minutes; no usable network waits without any request.
 * Nothing runs when no pair is set to auto, when live sync is off, or in the background. The slower fallback is the
 * 15-minute periodic [SyncWorker] job, which first asks the same delta feeds and skips pairs that did not change
 * ([SyncSkip]).
 *
 * Only the detection changed: what a detected change does ([SyncWorker.request] per pair, which runs [SyncEngine]
 * with its DeleteGuard / SafeReplace / conflict-copy rules) is the same as before.
 */
object ChangeWatch {
    /** Pure timing rules (unit-tested). */
    object Plan {
        const val POLL_MIN_MS = 30_000L
        const val POLL_MAX_MS = 5 * 60_000L
        const val ERROR_MIN_MS = 30_000L
        const val ERROR_MAX_MS = 15 * 60_000L
        const val NO_NETWORK_MS = 60_000L
        /** Seconds a long-poll may be held open (Dropbox allows 30–480). */
        const val LONG_POLL_S = 240

        /** Delay before the next delta poll after [idleRounds] rounds in a row without a change. */
        fun pollDelay(idleRounds: Int): Long {
            val shift = idleRounds.coerceIn(0, 10)
            return (POLL_MIN_MS shl shift).coerceAtMost(POLL_MAX_MS)
        }

        /** Delay after the [errors]-th failure in a row (1 = first). */
        fun errorDelay(errors: Int): Long {
            val shift = (errors - 1).coerceIn(0, 10)
            return (ERROR_MIN_MS shl shift).coerceAtMost(ERROR_MAX_MS)
        }

        /** Which providers need a watcher: those with at least one auto pair. */
        fun wanted(pairs: List<SyncPair>): Set<ProviderId> = pairs.filter { it.auto }.map { it.provider }.toSet()
    }

    /** How a provider's changes are being found right now (shown on the Sync screen). */
    enum class Mode { NOTIFY, DELTA_POLL, WAITING_NETWORK, BACKING_OFF, SIGN_IN }

    private val jobs = ConcurrentHashMap<ProviderId, Job>()
    private val _modes = MutableStateFlow<Map<ProviderId, Mode>>(emptyMap())
    val modes: StateFlow<Map<ProviderId, Mode>> = _modes

    private fun setMode(p: ProviderId, m: Mode?) { _modes.value = if (m == null) _modes.value - p else _modes.value + (p to m) }

    /** Starts the watchers that are missing and stops those no longer needed. */
    @Synchronized fun start(a: Context, scope: CoroutineScope) {
        val wanted = Plan.wanted(SyncStore.load(a))
        jobs.keys.filter { it !in wanted }.forEach { stopOne(it) }
        for (pid in wanted) {
            if (jobs[pid]?.isActive == true) continue
            jobs[pid] = scope.launch { runCatching { watch(a, pid) }; setMode(pid, null) }
        }
    }

    @Synchronized fun stop() { jobs.keys.toList().forEach { stopOne(it) } }

    private fun stopOne(pid: ProviderId) { jobs.remove(pid)?.cancel(); setMode(pid, null) }

    private suspend fun watch(a: Context, pid: ProviderId) {
        val provider = Clouds.provider(a, pid)
        var idle = 0
        var errors = 0
        var notify = provider.notifies
        while (coroutineContext.isActive && SyncLive.foreground) {
            val pairs = SyncStore.load(a).filter { it.auto && it.provider == pid }
            if (pairs.isEmpty() || !provider.isConnected()) return
            if (!com.smartdocs.editor.core.data.NpfNet.allowed() || pairs.none { SyncRuntime.networkOk(a, it.wifiOnly) }) {
                setMode(pid, Mode.WAITING_NETWORK); delay(Plan.NO_NETWORK_MS); continue
            }
            try {
                val cursor = SyncStore.cursor(a, pid) ?: provider.changeCursor()?.also { SyncStore.setCursor(a, pid, it) }
                if (cursor == null) return   // no change feed: the periodic job is all there is
                if (notify) {
                    setMode(pid, Mode.NOTIFY)
                    when (provider.waitForChange(cursor, Plan.LONG_POLL_S)) {
                        null -> { notify = false; continue }     // no notification channel after all: poll the feed
                        false -> { errors = 0; continue }        // held open with no change: re-arm
                        true -> Unit
                    }
                } else setMode(pid, Mode.DELTA_POLL)
                val changed = SyncLive.checkProvider(a, pid)
                errors = 0
                if (!notify) { idle = if (changed) 0 else idle + 1; delay(Plan.pollDelay(idle)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: AuthRequiredException) {
                setMode(pid, Mode.SIGN_IN); return
            } catch (e: Exception) {
                errors++
                setMode(pid, Mode.BACKING_OFF)
                delay(Plan.errorDelay(errors))
            }
        }
    }
}
