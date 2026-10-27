package com.smartdocs.editor.core.files

import com.smartdocs.editor.core.data.BookmarkDao
import com.smartdocs.editor.core.data.DocDao
import com.smartdocs.editor.core.data.DocTag
import com.smartdocs.editor.core.data.ReadingBookmark
import com.smartdocs.editor.core.data.RecentDoc
import com.smartdocs.editor.core.data.Tag
import com.smartdocs.editor.core.data.TagDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Audit DI-22: recents (last page, favourite), tags and bookmarks follow a rename / move; delete clears them. */
class DocMetadataTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeDocs : DocDao {
        val rows = LinkedHashMap<String, RecentDoc>()
        override fun recents(): Flow<List<RecentDoc>> = flowOf(rows.values.toList())
        override fun favorites(): Flow<List<RecentDoc>> = flowOf(rows.values.filter { it.favorite })
        override suspend fun get(uri: String) = rows[uri]
        override suspend fun upsert(doc: RecentDoc) { rows[doc.uri] = doc }
        override suspend fun updatePage(uri: String, page: Int) { rows[uri]?.let { rows[uri] = it.copy(lastPage = page) } }
        override suspend fun setFavorite(uri: String, fav: Boolean) { rows[uri]?.let { rows[uri] = it.copy(favorite = fav) } }
        override suspend fun remove(uri: String) { rows.remove(uri) }
        override suspend fun clear() = rows.clear()
        override suspend fun moveUri(from: String, to: String, name: String) { rows.remove(from)?.let { rows[to] = it.copy(uri = to, name = name) } }
    }

    private class FakeTags : TagDao {
        val links = LinkedHashSet<DocTag>()
        override fun tags(): Flow<List<Tag>> = flowOf(emptyList())
        override suspend fun insert(tag: Tag) = 0L
        override suspend fun delete(tag: Tag) {}
        override fun tagsForDoc(uri: String): Flow<List<Tag>> = flowOf(emptyList())
        override fun docsForTag(tagId: Long): Flow<List<RecentDoc>> = flowOf(emptyList())
        override suspend fun attach(docTag: DocTag) { links += docTag }
        override suspend fun detach(docTag: DocTag) { links -= docTag }
        // UPDATE OR IGNORE: a link the target already has is left behind (then removed by removeDoc)
        override suspend fun moveUri(from: String, to: String) {
            val moving = links.filter { it.uri == from }
            moving.forEach { l -> val n = l.copy(uri = to); if (n !in links) { links -= l; links += n } }
        }
        override suspend fun removeDoc(uri: String) { links.removeAll { it.uri == uri } }
    }

    private class FakeBookmarks : BookmarkDao {
        val rows = ArrayList<ReadingBookmark>()
        override fun forDoc(uri: String): Flow<List<ReadingBookmark>> = flowOf(rows.filter { it.uri == uri })
        override suspend fun insert(b: ReadingBookmark): Long { rows += b; return rows.size.toLong() }
        override suspend fun delete(b: ReadingBookmark) { rows -= b }
        override suspend fun moveUri(from: String, to: String) { rows.replaceAll { if (it.uri == from) it.copy(uri = to) else it } }
        override suspend fun removeDoc(uri: String) { rows.removeAll { it.uri == uri } }
    }

    private val docs = FakeDocs(); private val tags = FakeTags(); private val marks = FakeBookmarks()
    private val meta = DocMetadata(docs, tags, marks) { "file://" + it.absolutePath }
    private fun uri(f: File) = "file://" + f.absolutePath

    private fun seed(f: File) = runBlocking {
        docs.upsert(RecentDoc(uri(f), f.name, 10, 1, lastPage = 7, favorite = true))
        tags.attach(DocTag(uri(f), 1)); tags.attach(DocTag(uri(f), 2))
        marks.insert(ReadingBookmark(uri = uri(f), page = 3, title = "ch 2"))
    }

    @Test fun renameCarriesRecentsTagsBookmarks() = runBlocking {
        val a = tmp.newFile("a.pdf"); seed(a)
        val b = File(tmp.root, "b.pdf")
        // the target already has tag 2: it stays once, the old uri keeps nothing
        tags.attach(DocTag(uri(b), 2))
        meta.moved(DocMetadata.pairs(a, b, DocMetadata.contents(a)))
        assertNull(docs.get(uri(a)))
        val row = docs.get(uri(b))!!
        assertEquals(7, row.lastPage); assertTrue(row.favorite); assertEquals("b.pdf", row.name)
        assertEquals(setOf(DocTag(uri(b), 1), DocTag(uri(b), 2)), tags.links)
        assertEquals(listOf(uri(b)), marks.rows.map { it.uri })
    }

    @Test fun folderMoveCarriesEveryFileBelowIt() = runBlocking {
        val dir = tmp.newFolder("course"); val sub = File(dir, "week1").apply { mkdirs() }
        val f1 = File(dir, "x.pdf").apply { writeText("x") }; val f2 = File(sub, "y.pdf").apply { writeText("y") }
        seed(f1); seed(f2)
        val contents = DocMetadata.contents(dir)
        assertEquals(setOf("x.pdf", "week1${File.separator}y.pdf"), contents.toSet())
        val dest = File(tmp.newFolder("archive"), "course")
        assertTrue(dir.renameTo(dest))
        meta.moved(DocMetadata.pairs(dir, dest, contents))
        assertEquals(setOf(uri(File(dest, "x.pdf")), uri(File(dest, "week1/y.pdf"))), docs.rows.keys)
        assertTrue(tags.links.all { it.uri.contains("archive") })
        assertTrue(marks.rows.all { it.uri.contains("archive") })
    }

    @Test fun deleteDropsRecentsAndOnlyPermanentDeleteDropsUserData() = runBlocking {
        val a = tmp.newFile("a.pdf"); val b = tmp.newFile("b.pdf"); seed(a); seed(b)
        meta.deleted(listOf(a), keepUserData = true)
        assertNull(docs.get(uri(a))); assertEquals(2, tags.links.count { it.uri == uri(a) }); assertEquals(1, marks.rows.count { it.uri == uri(a) })
        meta.deleted(listOf(b), keepUserData = false)
        assertNull(docs.get(uri(b))); assertTrue(tags.links.none { it.uri == uri(b) }); assertTrue(marks.rows.none { it.uri == uri(b) })
    }
}
