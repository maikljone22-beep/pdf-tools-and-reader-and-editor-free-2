package com.smartdocs.editor.core.sync

import com.smartdocs.editor.core.sync.SyncRules.Action
import com.smartdocs.editor.core.sync.SyncRules.Side
import com.smartdocs.editor.core.sync.SyncRules.Snap
import com.smartdocs.editor.core.view5.Trash
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** DI-25: a suspicious set of deletions never runs without the user's confirmation; local deletes go to the trash. */
class DeleteGuardTest {
    private val snap = Snap(localMod = 10_000, localSize = 100, remoteMod = 50_000, remoteSize = 100)

    @Test fun emptyLocalListingWithFiftySyncedFilesDeletesNothing() {
        // an unmounted SD card: the local folder lists nothing, the cloud still has the 50 synced files
        val actions = (1..50).map { SyncRules.decide(null, Side(50_000, 100), snap) }
        val deletes = actions.filter(DeleteGuard::isDelete)
        assertEquals(50, deletes.size)
        assertTrue(deletes.all { it == Action.DELETE_REMOTE })
        assertTrue(DeleteGuard.toRun(deletes, snapshotSize = 50, policy = DeleteGuard.Policy.GUARD).isEmpty())
    }

    @Test fun emptyCloudListingDeletesNothingLocally() {
        val deletes = (1..10).map { SyncRules.decide(Side(10_000, 100), null, snap) }
        assertTrue(deletes.all { it == Action.DELETE_LOCAL })
        assertTrue(DeleteGuard.toRun(deletes, snapshotSize = 10, policy = DeleteGuard.Policy.GUARD).isEmpty())
    }

    @Test fun thresholds() {
        assertTrue(DeleteGuard.tooMany(21, 1000))  // more than 20
        assertFalse(DeleteGuard.tooMany(20, 1000))
        assertTrue(DeleteGuard.tooMany(4, 10))     // 40 % > 30 %
        assertFalse(DeleteGuard.tooMany(3, 10))    // exactly 30 %
        assertFalse(DeleteGuard.tooMany(1, 3))     // a single deletion is a normal edit
        assertTrue(DeleteGuard.tooMany(2, 3))
        assertFalse(DeleteGuard.tooMany(0, 0))
    }

    @Test fun normalDeletesRunAndConfirmedOnesRunToo() {
        val few = listOf(Action.DELETE_REMOTE, Action.DELETE_LOCAL)
        assertEquals(few, DeleteGuard.toRun(few, snapshotSize = 100, policy = DeleteGuard.Policy.GUARD))
        val many = List(50) { Action.DELETE_REMOTE }
        assertEquals(many, DeleteGuard.toRun(many, snapshotSize = 50, policy = DeleteGuard.Policy.ALLOW))
    }

    @Test fun restoreCopiesBackInsteadOfDeleting() {
        assertEquals(Action.UPLOAD_NEW, DeleteGuard.restored(Action.DELETE_LOCAL))
        assertEquals(Action.DOWNLOAD_NEW, DeleteGuard.restored(Action.DELETE_REMOTE))
        assertEquals(Action.UPLOAD, DeleteGuard.restored(Action.UPLOAD))
    }

    @Test fun localDeleteCopyIsAReadableTrashEntry() {
        val root = Files.createTempDirectory("t").toFile()
        val trashDir = File(root, "trash"); val docs = File(root, "Documents").apply { mkdirs() }
        val bytes = "PDF-BYTES".toByteArray()
        SyncTrash.add(trashDir, "a.pdf", "content://tree/doc/a.pdf", bytes.size.toLong(), now = 1_000L) { it.writeBytes(bytes) }
        val trash = Trash(trashDir)
        val e = trash.list().single()
        assertEquals("a.pdf", e.name)
        assertEquals(bytes.size.toLong(), e.size)
        val restored = trash.restore(e, docs)!!
        assertEquals(docs, restored.parentFile) // no file path for a SAF document: restored into Documents
        assertArrayEquals(bytes, restored.readBytes())
        root.deleteRecursively()
    }

    @Test fun incompleteTrashCopyFailsAndLeavesNothing() {
        val dir = Files.createTempDirectory("t").toFile()
        try { SyncTrash.add(dir, "a.pdf", "u", expectedSize = 100) { it.writeBytes(ByteArray(10)) }; fail("must throw") }
        catch (_: IllegalStateException) {}
        assertTrue(Trash(dir).list().isEmpty())
        assertEquals(0, dir.listFiles()!!.size)
        dir.deleteRecursively()
    }
}
