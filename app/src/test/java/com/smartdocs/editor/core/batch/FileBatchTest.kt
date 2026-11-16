package com.smartdocs.editor.core.batch

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileBatchTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun output(name: String) = tmp.newFile(name).apply { writeText("result") }

    @Test fun sequentialFilesContinueAfterFailureAndRetryPreservesSuccesses() = runBlocking {
        val batch = FileBatch(this)
        val order = mutableListOf<String>()
        val first = tmp.newFile("one.pdf"); val second = tmp.newFile("two.pdf"); val third = tmp.newFile("three.pdf")
        var failSecond = true
        batch.start(listOf(first, second, third)) { input ->
            synchronized(order) { order += input.name }
            if (input == second && failSecond) error("wrong password")
            listOf(output(input.name + ".out"))
        }!!.join()
        assertEquals(listOf("one.pdf", "two.pdf", "three.pdf"), order)
        assertEquals(listOf(FileTaskStatus.COMPLETED, FileTaskStatus.FAILED, FileTaskStatus.COMPLETED), batch.state.value.files.map { it.status })
        val successful = batch.state.value.files[0].outputs.single()
        failSecond = false
        batch.retryFailed()!!.join()
        assertEquals(listOf("one.pdf", "two.pdf", "three.pdf", "two.pdf"), order)
        assertTrue(batch.state.value.files.all { it.status == FileTaskStatus.COMPLETED })
        assertEquals(successful, batch.state.value.files[0].outputs.single())
    }

    @Test fun stopKeepsWaitingFilesAndResumeProcessesOnlyRemaining() = runBlocking {
        val batch = FileBatch(this)
        val entered = CompletableDeferred<Unit>()
        var pause = true
        val a = tmp.newFile("a.pdf"); val b = tmp.newFile("b.pdf")
        val job = batch.start(listOf(a, b)) { input ->
            if (pause) { entered.complete(Unit); awaitCancellation() }
            listOf(output(input.name + ".out"))
        }!!
        entered.await(); batch.stop(); job.join()
        assertFalse(batch.state.value.running)
        assertEquals(listOf(FileTaskStatus.STOPPED, FileTaskStatus.WAITING), batch.state.value.files.map { it.status })
        pause = false; batch.resume()!!.join()
        assertTrue(batch.state.value.files.all { it.status == FileTaskStatus.COMPLETED })
    }

    @Test fun onlyDuplicatePathsAreDroppedAndMissingOutputIsAFailure() = runBlocking {
        val batch = FileBatch(this)
        val a = File(tmp.newFolder("left"), "same.pdf").apply { writeText("a") }
        val b = File(tmp.newFolder("right"), "same.pdf").apply { writeText("b") }
        batch.start(listOf(a, a, b)) { emptyList() }!!.join()
        assertEquals(2, batch.state.value.files.size)
        assertTrue(batch.state.value.files.all { it.status == FileTaskStatus.FAILED })
    }

    @Test fun immediateStopAlwaysClearsRunningFlag() = runBlocking {
        val batch = FileBatch(this)
        val job = batch.start(listOf(tmp.newFile("input.pdf"))) { awaitCancellation() }!!
        batch.stop(); job.join()
        assertFalse(batch.state.value.running)
    }
}
