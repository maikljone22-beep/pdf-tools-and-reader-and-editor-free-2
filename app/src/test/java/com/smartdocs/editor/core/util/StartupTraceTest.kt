package com.smartdocs.editor.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTraceTest {
    @Test fun summaryGivesEachPhaseAndTheSlowStepsSlowestFirst() {
        val s = StartupTrace.summary(
            processStart = 1000, appStart = 1100, appEnd = 1400, activityStart = 1450, firstFrame = 2000,
            steps = listOf("tiny" to 500L, "prefs" to 30_500L, "database" to 120_000L),
        )
        assertTrue(s, s.startsWith("first frame after 1000 ms"))
        assertTrue(s, s.contains("process to app 100"))
        assertTrue(s, s.contains("app setup 300"))
        assertTrue(s, s.contains("app to activity 50"))
        assertTrue(s, s.contains("activity to first frame 550"))
        assertTrue(s, s.contains("database 120.0, prefs 30.5"))
        assertFalse("steps under 1 ms are left out", s.contains("tiny"))
    }

    @Test fun noSlowStepIsSaidPlainly() {
        assertTrue(StartupTrace.summary(0, 1, 2, 3, 4, listOf("a" to 10L)).endsWith("setup steps over 1 ms: none"))
    }
}
