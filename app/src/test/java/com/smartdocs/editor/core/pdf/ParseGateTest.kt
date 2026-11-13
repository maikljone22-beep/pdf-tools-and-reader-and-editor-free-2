package com.smartdocs.editor.core.pdf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ParseGateTest {
    @Test fun parsesNeverOverlapAndEachIsMeasured() = runBlocking<Unit> {
        val active = AtomicInteger(0); val mostAtOnce = AtomicInteger(0)
        val jobs = (1..6).map { i ->
            async(Dispatchers.Default) {
                ParseGate.run("t$i") {
                    val n = active.incrementAndGet()
                    mostAtOnce.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                    delay(15)
                    active.decrementAndGet()
                    i
                }
            }
        }
        assertEquals((1..6).toList(), jobs.awaitAll())
        assertEquals("two whole-file parses ran at the same time", 1, mostAtOnce.get())
        val stats = ParseGate.stats()
        assertTrue(stats.map { it.label }.containsAll((1..6).map { "t$it" }))
        assertTrue(stats.all { it.peakMb >= it.baselineMb && it.millis >= 0 })
    }

    @Test fun aFailingParseStillReleasesTheGate() = runBlocking<Unit> {
        runCatching { ParseGate.run("boom") { error("x") } }
        assertEquals(7, ParseGate.run("after") { 7 })
        assertTrue(ParseGate.stats().any { it.label == "boom" })
    }
}
