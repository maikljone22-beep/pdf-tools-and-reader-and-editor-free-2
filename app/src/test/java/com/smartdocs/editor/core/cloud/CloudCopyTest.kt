package com.smartdocs.editor.core.cloud

import com.smartdocs.editor.core.cloud.CloudCopy.OpenAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** DI-23 / DI-24: a cloud working copy with edits that are not uploaded is never overwritten or cleared. */
class CloudCopyTest {
    private class FakeProvider(var cloudBytes: ByteArray, var meta: CloudItem?, var failAfter: Int = -1) : CloudProvider {
        var downloads = 0
        override val id = ProviderId.DRIVE
        override val rootId = "root"
        override fun isConfigured() = true
        override fun isConnected() = true
        override suspend fun list(folderId: String?) = emptyList<CloudItem>()
        override suspend fun search(query: String) = emptyList<CloudItem>()
        override suspend fun download(item: CloudItem, dest: File, onProgress: ((Long, Long) -> Unit)?) {
            downloads++
            if (failAfter >= 0) { dest.writeBytes(cloudBytes.copyOf(failAfter)); throw java.io.IOException("connection lost") }
            dest.writeBytes(cloudBytes)
        }
        override suspend fun upload(local: File, parentId: String?, name: String): CloudItem = throw UnsupportedOperationException()
        override suspend fun update(item: CloudItem, local: File): CloudItem = throw UnsupportedOperationException()
        override suspend fun createFolder(parentId: String?, name: String): CloudItem = throw UnsupportedOperationException()
        override suspend fun disconnect() = Unit
        override suspend fun metadataOf(item: CloudItem): CloudItem? = meta
    }

    private val item = CloudItem("id1", "a.pdf", false, size = 5, modified = 100_000)
    private val cloud = "CLOUD".toByteArray()
    private val edited = "MY OFFLINE EDITS".toByteArray()
    private fun tmpDir() = Files.createTempDirectory("cc").toFile()

    @Test fun dirtyLocalCopySurvivesReopen() = runBlocking {
        val dir = tmpDir(); val dest = File(dir, "a.pdf").apply { writeBytes(edited) }
        val p = FakeProvider(cloud, meta = item)
        val link = CloudLinks.Link(ProviderId.DRIVE, item, dirty = true, uploadedHash = "hash-of-the-cloud-version", gen = 3)
        val r = CloudCopy.openInto(p, item, dest, link)
        assertEquals(OpenAction.KEEP_LOCAL, r.action)
        assertArrayEquals(edited, dest.readBytes())
        assertEquals(0, p.downloads)
        dir.deleteRecursively(); Unit
    }

    @Test fun editsWithoutDirtyFlagAreDetectedByHash() = runBlocking {
        // e.g. the process died between the save and marking the link dirty
        val dir = tmpDir(); val dest = File(dir, "a.pdf").apply { writeBytes(cloud) }
        val linkHash = CloudCopy.sha256(dest)
        dest.writeBytes(edited)
        val r = CloudCopy.openInto(FakeProvider(cloud, item), item, dest, CloudLinks.Link(ProviderId.DRIVE, item, dirty = false, uploadedHash = linkHash))
        assertEquals(OpenAction.KEEP_LOCAL, r.action)
        assertArrayEquals(edited, dest.readBytes())
        dir.deleteRecursively(); Unit
    }

    @Test fun bothSidesChangedIsAConflictAndLocalBytesStay() = runBlocking {
        val dir = tmpDir(); val dest = File(dir, "a.pdf").apply { writeBytes(edited) }
        val changed = item.copy(size = 9, modified = 900_000)
        val r = CloudCopy.openInto(FakeProvider(cloud, changed), item, dest, CloudLinks.Link(ProviderId.DRIVE, item, dirty = true))
        assertEquals(OpenAction.CONFLICT, r.action)
        assertArrayEquals(edited, dest.readBytes())
        dir.deleteRecursively(); Unit
    }

    @Test fun cleanCopyIsRefreshedFromTheCloud() = runBlocking {
        val dir = tmpDir(); val dest = File(dir, "a.pdf").apply { writeBytes("OLD".toByteArray()) }
        val link = CloudLinks.Link(ProviderId.DRIVE, item, dirty = false, uploadedHash = CloudCopy.sha256(dest))
        val r = CloudCopy.openInto(FakeProvider(cloud, item), item, dest, link)
        assertEquals(OpenAction.DOWNLOAD, r.action)
        assertArrayEquals(cloud, dest.readBytes())
        assertEquals(CloudCopy.sha256(dest), r.hash)
        assertEquals(listOf("a.pdf"), dir.list()!!.toList()) // no leftover .part
        dir.deleteRecursively(); Unit
    }

    @Test fun failedDownloadLeavesTheOldCopyIntact() = runBlocking {
        val dir = tmpDir(); val dest = File(dir, "a.pdf").apply { writeBytes("OLD-GOOD".toByteArray()) }
        val link = CloudLinks.Link(ProviderId.DRIVE, item, dirty = false, uploadedHash = CloudCopy.sha256(dest))
        try { CloudCopy.openInto(FakeProvider(cloud, item, failAfter = 2), item, dest, link); fail("must throw") } catch (_: java.io.IOException) {}
        assertArrayEquals("OLD-GOOD".toByteArray(), dest.readBytes())
        assertEquals(listOf("a.pdf"), dir.list()!!.toList())
        dir.deleteRecursively(); Unit
    }

    @Test fun decisions() {
        assertEquals(OpenAction.DOWNLOAD, CloudCopy.openAction(localExists = false, dirty = true, remoteChanged = true))
        assertEquals(OpenAction.DOWNLOAD, CloudCopy.openAction(localExists = true, dirty = false, remoteChanged = true))
        assertEquals(OpenAction.KEEP_LOCAL, CloudCopy.openAction(localExists = true, dirty = true, remoteChanged = false))
        assertEquals(OpenAction.CONFLICT, CloudCopy.openAction(localExists = true, dirty = true, remoteChanged = true))
        assertFalse(CloudCopy.remoteChanged(item, null))
        assertFalse(CloudCopy.remoteChanged(item, item.copy(modified = item.modified + 1_500)))
        assertTrue(CloudCopy.remoteChanged(item, item.copy(modified = item.modified + 60_000))) // same size, edited
        assertTrue(CloudCopy.remoteChanged(item, item.copy(size = 6)))
        assertFalse(CloudCopy.remoteChanged(item.copy(modified = 0), item.copy(modified = 900_000))) // no known time: size only
        assertTrue(CloudCopy.isDirty(true, null, null))
        assertFalse(CloudCopy.isDirty(false, null, "x"))   // links saved before this fix: no hash, not dirty
        assertTrue(CloudCopy.isDirty(false, "a", "b"))
        assertFalse(CloudCopy.isDirty(false, "a", "a"))
    }

    @Test fun uploadJobIsIdempotent() {
        assertEquals(CloudUploadWorker.Step.UPLOAD, CloudUploadWorker.decide(dirty = true, uploadedHash = "old", localHash = "new"))
        assertEquals(CloudUploadWorker.Step.UPLOAD, CloudUploadWorker.decide(dirty = true, uploadedHash = null, localHash = "new"))
        assertEquals(CloudUploadWorker.Step.MARK_CLEAN, CloudUploadWorker.decide(dirty = true, uploadedHash = "same", localHash = "same"))
        assertEquals(CloudUploadWorker.Step.NOTHING, CloudUploadWorker.decide(dirty = false, uploadedHash = "same", localHash = "same"))
        val f = File("/x/Cloud/drive/a.pdf")
        assertEquals(CloudUploadWorker.uniqueName(f), CloudUploadWorker.uniqueName(File("/x/Cloud/drive/a.pdf")))
        assertTrue(CloudUploadWorker.uniqueName(f).startsWith("cloud_up_"))
    }

    @Test fun clearingCloudCopiesKeepsUnsyncedOnes() {
        val dir = tmpDir()
        val dirty = File(dir, "edited.pdf").apply { writeBytes(edited) }
        File(dir, "clean.pdf").writeBytes(cloud)
        File(dir, "sub").mkdirs(); File(dir, "sub/old.pdf").writeBytes(cloud)
        val freed = CloudCopy.clearExcept(dir, setOf(dirty.absolutePath))
        assertEquals(2L * cloud.size, freed)
        assertEquals(listOf("edited.pdf"), dir.list()!!.toList())
        assertArrayEquals(edited, dirty.readBytes())
        dir.deleteRecursively()
    }
}
