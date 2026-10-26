package com.smartdocs.editor.core.forms

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory

/** Thrown from the instruction observer when a script runs past its deadline (an Error: scripts cannot catch it). */
class JsTimeout : Error("script time limit")

/**
 * Rhino context factory for untrusted form scripts: interpreted mode (no bytecode generation, required on
 * Android), ES6 language level, a ClassShutter that denies every Java class, no E4X, a bounded interpreter
 * stack and a wall-clock deadline checked by the instruction observer. Scopes are created with
 * `initSafeStandardObjects` (no Packages / java / JavaImporter).
 */
class JsSandbox(private val maxStackDepth: Int = 400) : ContextFactory() {

    override fun makeContext(): Context = super.makeContext().apply {
        @Suppress("DEPRECATION") optimizationLevel = -1
        languageVersion = Context.VERSION_ES6
        setClassShutter { false }
        instructionObserverThreshold = 5_000
        maximumInterpreterStackDepth = maxStackDepth
    }

    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        val dl = cx.getThreadLocal(DEADLINE) as? Long ?: return
        if (System.nanoTime() > dl) throw JsTimeout()
    }

    override fun hasFeature(cx: Context, featureIndex: Int): Boolean = when (featureIndex) {
        Context.FEATURE_ENHANCED_JAVA_ACCESS, Context.FEATURE_E4X -> false
        else -> super.hasFeature(cx, featureIndex)
    }

    /** Run [block] in an entered context whose scripts must finish within [millis]. */
    fun <T> run(millis: Long, block: (Context) -> T): T {
        val cx = enterContext()
        try { deadline(cx, millis); return block(cx) } finally { Context.exit() }
    }

    companion object {
        private const val DEADLINE = "smartdocs.deadline"
        fun deadline(cx: Context, millis: Long) = cx.putThreadLocal(DEADLINE, System.nanoTime() + millis * 1_000_000L)

        /** Errors of a script (syntax, runtime, time-out, stack overflow) are not fatal; real VM failures are. */
        fun scriptError(e: Throwable): Boolean = !(e is VirtualMachineError && e !is StackOverflowError)
    }
}
