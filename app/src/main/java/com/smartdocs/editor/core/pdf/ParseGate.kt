package com.smartdocs.editor.core.pdf

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One whole-file PdfBox parse at a time, process-wide. Every background read after a document opens (initial view,
 * page frames, existing annotations, action scan, signature check) parses the whole file into the Java heap; run
 * together on a large file they exhausted it and the next allocation anywhere (a touch event) crashed the app.
 *
 * Every parse is also measured ([stats]): how long it took and how far the Java heap rose while it ran. This is the
 * evidence for which parses are worth moving from PdfBox to PDFium (which reads from the file and keeps almost nothing on the heap).
 */
object ParseGate {
    private val mutex = Mutex()

    /** One finished parse. Heap figures are in MB; [peakMb] is sampled every 20 ms, so a very short spike can be missed. */
    data class Stat(val label: String, val millis: Long, val baselineMb: Long, val peakMb: Long) {
        val riseMb: Long get() = peakMb - baselineMb
        override fun toString() = "$label: ${millis} ms, heap $baselineMb -> $peakMb MB (+$riseMb)"
    }

    private val recent = ConcurrentLinkedQueue<Stat>()
    private const val KEEP = 64

    /** The latest parses, oldest first. */
    fun stats(): List<Stat> = recent.toList()

    suspend fun <T> run(label: String = "parse", block: suspend () -> T): T = mutex.withLock {
        val rt = Runtime.getRuntime()
        fun usedMb() = (rt.totalMemory() - rt.freeMemory()) shr 20
        val base = usedMb()
        val peak = AtomicLong(base)
        val stop = AtomicBoolean(false)
        val sampler = Thread {
            while (!stop.get()) { val u = usedMb(); peak.accumulateAndGet(u) { a, b -> maxOf(a, b) }; try { Thread.sleep(20) } catch (_: InterruptedException) { return@Thread } }
        }.apply { isDaemon = true; name = "ParseGate-sampler"; start() }
        val t0 = System.nanoTime()
        try { block() } finally {
            stop.set(true); sampler.interrupt()
            val s = Stat(label, (System.nanoTime() - t0) / 1_000_000, base, maxOf(peak.get(), usedMb()))
            recent.add(s); while (recent.size > KEEP) recent.poll()
            runCatching { Log.i("ParseGate", s.toString()) }
        }
    }
}
