package com.smartdocs.editor.core.review

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.google.android.play.core.review.ReviewManagerFactory
import com.smartdocs.editor.core.cloud.awaitTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Google Play In-App Review after real use: at least [MIN_ACTIONS] successful saves/exports, ≥ [MIN_DAYS] days since
 * install, at most once every [COOLDOWN_DAYS] days. Play itself also rate-limits the dialog and may show nothing.
 * No "are you happy?" pre-filter (forbidden by Play policy).
 *
 * Hook for other modules (after a successful save / export / tool result):
 *     com.smartdocs.editor.core.review.ReviewPrompt.recordSuccess(context)
 * [ReviewPromptHost] (placed once in MainActivity) shows the dialog when due.
 */
object ReviewPrompt {
    const val MIN_ACTIONS = 15
    const val MIN_DAYS = 3
    const val COOLDOWN_DAYS = 90
    private const val DAY = 24L * 3600 * 1000

    private val _due = MutableStateFlow(false)
    val due: StateFlow<Boolean> = _due

    fun recordSuccess(context: Context) {
        val p = AcctPrefs.prefs(context)
        val n = p.getInt("rate_actions", 0) + 1
        p.edit().putInt("rate_actions", n).apply()
        if (isEligible(context)) _due.value = true
    }

    fun isEligible(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = AcctPrefs.prefs(context)
        if (p.getInt("rate_actions", 0) < MIN_ACTIONS) return false
        if (now - AcctPrefs.installTime(context) < MIN_DAYS * DAY) return false
        val last = p.getLong("rate_last_prompt", 0L)
        return last == 0L || now - last >= COOLDOWN_DAYS * DAY
    }

    private fun markShown(context: Context) {
        AcctPrefs.prefs(context).edit().putLong("rate_last_prompt", System.currentTimeMillis()).putInt("rate_actions", 0).apply()
        _due.value = false
    }

    /** Requests and launches the Play review flow. Safe to call anytime; failures are ignored. */
    suspend fun launch(activity: Activity) {
        markShown(activity) // counted even if Play decides not to show anything (quota)
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            val info = manager.requestReviewFlow().awaitTask()
            manager.launchReviewFlow(activity, info).awaitTask()
        }
    }
}

/** Place once inside the app content (MainActivity). Launches the review flow when [ReviewPrompt.due] becomes true. */
@Composable
fun ReviewPromptHost() {
    val ctx = LocalContext.current
    val due by ReviewPrompt.due.collectAsState()
    LaunchedEffect(due) {
        if (!due) return@LaunchedEffect
        var c: Context = ctx
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        (c as? Activity)?.let { kotlinx.coroutines.delay(1500); ReviewPrompt.launch(it) }
    }
}
