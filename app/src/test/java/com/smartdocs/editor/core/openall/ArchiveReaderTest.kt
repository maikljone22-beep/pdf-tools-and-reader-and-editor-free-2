package com.smartdocs.editor.core.openall

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val f = tmp.newFile("a${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }

    @Test fun safePathNormalizes() {
        assertEquals("a/b.txt", ArchiveReader.safePath("./a//b.txt"))
        assertEquals("a/b.txt", ArchiveReader.safePath("/a/b.txt"))
        assertEquals("x/y.txt", ArchiveReader.safePath("C:\\x\\y.txt"))
        assertEquals("dir/", ArchiveReader.safePath("dir/"))
        assertNull(ArchiveReader.safePath("../evil.txt"))
        assertNull(ArchiveReader.safePath("a/../../evil.txt"))
        assertNull(ArchiveReader.safePath(""))
    }

    @Test fun zipListingAndZipSlipGuard() {
        val f = zip("a.txt" to "hello".toByteArray(), "dir/b.txt" to "bee".toByteArray(), "../evil.txt" to "x".toByteArray())
        assertEquals(FType.ZIP, FileSniffer.of(f))
        val all = ArchiveReader.list(f, ArchiveReader.Format.ZIP)
        assertEquals(3, all.size)
        val evil = all.first { it.unsafe }
        assertEquals("../evil.txt", evil.path)
        val out = tmp.newFolder("out")
        val a = ArchiveReader.extract(f, ArchiveReader.Format.ZIP, all.first { it.path == "a.txt" }, out)
        assertEquals("hello", a.readText())
        assertTrue(a.canonicalPath.startsWith(out.canonicalPath + File.separator))
        try { ArchiveReader.extract(f, ArchiveReader.Format.ZIP, evil, out); fail() } catch (e: ArchiveLimitException) { assertEquals("unsafe", e.reason) }
        assertFalse(File(out.parentFile, "evil.txt").exists())
        // root folder view: the implicit folder "dir/" is synthesized, unsafe entries stay listed but flagged
        val root = ArchiveReader.children(all.filter { !it.unsafe }, "")
        assertEquals(listOf("dir/", "a.txt"), root.map { it.path })
        assertEquals(listOf("dir/b.txt"), ArchiveReader.children(all, "dir/").map { it.path })
    }

    @Test fun zipBombGuards() {
        val zeros = ByteArray(3 * 1024 * 1024)
        val f = zip("bomb.bin" to zeros)
        val e = ArchiveReader.list(f, ArchiveReader.Format.ZIP).single()
        try { ArchiveReader.extract(f, ArchiveReader.Format.ZIP, e, tmp.newFolder("o1")); fail() } catch (x: ArchiveLimitException) { assertEquals("ratio", x.reason) }
        try { ArchiveReader.extract(f, ArchiveReader.Format.ZIP, e, tmp.newFolder("o2"), ArcLimits(maxEntryBytes = 1024 * 1024, maxRatio = 1_000_000)); fail() }
        catch (x: ArchiveLimitException) { assertEquals("size", x.reason) }
        try { ArchiveReader.list(zip("1" to ByteArray(1), "2" to ByteArray(1), "3" to ByteArray(1)), ArchiveReader.Format.ZIP, ArcLimits(maxEntries = 2)); fail() }
        catch (x: ArchiveLimitException) { assertEquals("entries", x.reason) }
    }

    @Test fun tarAndTgz() {
        val tar = tmp.newFile("t.tar")
        TarArchiveOutputStream(tar.outputStream()).use { t ->
            for ((n, s) in listOf("docs/readme.txt" to "read me", "x.csv" to "a,b")) {
                val e = TarArchiveEntry(n); val b = s.toByteArray(); e.size = b.size.toLong(); t.putArchiveEntry(e); t.write(b); t.closeArchiveEntry()
            }
        }
        assertEquals(FType.TAR, FileSniffer.of(tar))
        val list = ArchiveReader.list(tar, ArchiveReader.Format.TAR)
        assertEquals(listOf("docs/readme.txt", "x.csv"), list.map { it.path })
        assertEquals("a,b", ArchiveReader.extract(tar, ArchiveReader.Format.TAR, list[1], tmp.newFolder("t1")).readText())

        val tgz = tmp.newFile("t.bin")
        GZIPOutputStream(tgz.outputStream()).use { g -> tar.inputStream().use { it.copyTo(g) } }
        assertEquals(FType.GZIP, FileSniffer.of(tgz))
        assertEquals(ArchiveReader.Format.TGZ, ArchiveReader.formatOf(tgz, FType.GZIP))
        val l2 = ArchiveReader.list(tgz, ArchiveReader.Format.TGZ)
        assertEquals("read me", ArchiveReader.extract(tgz, ArchiveReader.Format.TGZ, l2[0], tmp.newFolder("t2")).readText())

        val gz = tmp.newFile("notes.txt.gz")
        GZIPOutputStream(gz.outputStream()).use { it.write("plain".toByteArray()) }
        assertEquals(ArchiveReader.Format.GZ, ArchiveReader.formatOf(gz, FType.GZIP))
        val one = ArchiveReader.list(gz, ArchiveReader.Format.GZ).single()
        assertEquals("notes.txt", one.path)
        assertEquals("plain", ArchiveReader.extract(gz, ArchiveReader.Format.GZ, one, tmp.newFolder("t3")).readText())
    }

    @Test fun sevenZip() {
        val f = tmp.newFile("s.7z")
        val src = tmp.newFile("page1.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }
        SevenZOutputFile(f).use { z ->
            val e = z.createArchiveEntry(src, "comic/page1.png"); z.putArchiveEntry(e); z.write(src.readBytes()); z.closeArchiveEntry()
        }
        assertEquals(FType.SEVENZ, FileSniffer.of(f))
        val list = ArchiveReader.list(f, ArchiveReader.Format.SEVENZ)
        assertEquals("comic/page1.png", list.single().path)
        assertEquals(4L, ArchiveReader.extract(f, ArchiveReader.Format.SEVENZ, list.single(), tmp.newFolder("s1")).length())
    }

    @Test fun naturalOrder() {
        assertEquals(listOf("p1.jpg", "p2.jpg", "p10.jpg", "P11.jpg"), listOf("p10.jpg", "P11.jpg", "p2.jpg", "p1.jpg").sortedWith(NaturalOrder))
    }
}
