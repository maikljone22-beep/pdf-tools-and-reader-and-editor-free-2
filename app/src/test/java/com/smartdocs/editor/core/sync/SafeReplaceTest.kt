package com.smartdocs.editor.core.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** DI-26: a sync download never leaves the local document truncated. */
class SafeReplaceTest {
    /** A folder of named documents in memory; a document is its (mutable) name. */
    private class FakeFolder(var canRename: Boolean = true, var failWriteOf: String? = null, var failAfterBytes: Int = 3) : SafeReplace.Ops<FakeFolder.Doc> {
        class Doc(var name: String)
        val files = LinkedHashMap<String, ByteArray>()
        val docs = HashMap<String, Doc>()
        fun put(name: String, bytes: ByteArray): Doc { files[name] = bytes; return Doc(name).also { docs[name] = it } }

        override fun create(name: String): Doc? = if (name in files) null else put(name, ByteArray(0))
        override fun write(doc: Doc, src: File) {
            val bytes = src.readBytes()
            if (doc.name == failWriteOf) { files[doc.name] = bytes.copyOf(failAfterBytes); throw java.io.IOException("disk full") }
            files[doc.name] = bytes
        }
        override fun copy(from: Doc, to: Doc) { files[to.name] = files.getValue(from.name).copyOf() }
        override fun length(doc: Doc): Long = files[doc.name]?.size?.toLong() ?: -1
        override fun rename(doc: Doc, name: String): Doc? {
            if (!canRename || name in files) return null
            files[name] = files.remove(doc.name)!!; doc.name = name; return doc
        }
        override fun delete(doc: Doc): Boolean = files.remove(doc.name) != null
    }

    private val original = "ORIGINAL-CONTENT".toByteArray()
    private val cloud = "NEW CLOUD CONTENT, LONGER".toByteArray()
    private fun src(): File = File.createTempFile("sync_dl", ".bin").apply { writeBytes(cloud); deleteOnExit() }

    @Test fun replacesThroughPartAndLeavesNoTempFiles() {
        val f = FakeFolder(); val t = f.put("a.pdf", original)
        val got = SafeReplace.replace(f, t, "a.pdf", src())
        assertEquals("a.pdf", got.name)
        assertArrayEquals(cloud, f.files["a.pdf"])
        assertEquals(setOf("a.pdf"), f.files.keys)
    }

    @Test fun failureMidCopyKeepsOriginalIntact() {
        val f = FakeFolder(failWriteOf = SafeReplace.partName("a.pdf")); val t = f.put("a.pdf", original)
        try { SafeReplace.replace(f, t, "a.pdf", src()); fail("must throw") } catch (_: IllegalStateException) {}
        assertArrayEquals(original, f.files["a.pdf"])
        assertEquals(setOf("a.pdf"), f.files.keys) // the partial part file is removed
    }

    @Test fun truncatedPartIsRejectedByLengthCheck() {
        val inner = FakeFolder()
        val short = object : SafeReplace.Ops<FakeFolder.Doc> by inner {
            override fun write(doc: FakeFolder.Doc, src: File) { inner.files[doc.name] = src.readBytes().copyOf(4) } // silent short write
        }
        val t = inner.put("a.pdf", original)
        try { SafeReplace.replace(short, t, "a.pdf", src()); fail("must throw") } catch (_: IllegalStateException) {}
        assertArrayEquals(original, inner.files["a.pdf"])
        assertEquals(setOf("a.pdf"), inner.files.keys)
    }

    @Test fun noRenameFallsBackToVerifiedInPlaceWrite() {
        val f = FakeFolder(canRename = false); val t = f.put("a.pdf", original)
        SafeReplace.replace(f, t, "a.pdf", src())
        assertArrayEquals(cloud, f.files["a.pdf"])
        assertEquals(setOf("a.pdf"), f.files.keys) // part and backup cleaned up
    }

    @Test fun noRenameFailureMidWriteRestoresOriginalFromBackup() {
        val f = FakeFolder(canRename = false, failWriteOf = "a.pdf"); val t = f.put("a.pdf", original)
        // the part write succeeds (different name), the in-place write fails half way
        try { SafeReplace.replace(f, t, "a.pdf", src()); fail("must throw") } catch (_: IllegalStateException) {}
        assertArrayEquals(original, f.files["a.pdf"])
        assertFalse(f.files.containsKey(SafeReplace.backupName("a.pdf")))
    }

    @Test fun newDocumentIsCreatedOnlyWhenComplete() {
        val f = FakeFolder(failWriteOf = SafeReplace.partName("n.pdf"))
        try { SafeReplace.replace(f, null, "n.pdf", src()); fail("must throw") } catch (_: IllegalStateException) {}
        assertTrue(f.files.isEmpty()) // no truncated "n.pdf" that the next pass would upload
        val ok = FakeFolder()
        SafeReplace.replace(ok, null, "n.pdf", src())
        assertArrayEquals(cloud, ok.files["n.pdf"])
    }

    @Test fun tempNamesAreIgnoredBySync() {
        assertTrue(SyncRules.ignored(SafeReplace.partName("a.pdf")))
        assertTrue(SyncRules.ignored(SafeReplace.backupName("a.pdf")))
    }
}
