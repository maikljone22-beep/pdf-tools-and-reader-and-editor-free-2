package com.smartdocs.editor.ui.openall

import com.smartdocs.editor.core.openall.FType
import com.smartdocs.editor.core.openall.FileSniffer
import com.smartdocs.editor.core.openall.OpenAllText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Central dispatch: which screen each detected type goes to (open-all). */
class OpenAllRoutingTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray) = File(tmp.root, name).apply { writeBytes(bytes) }
    private fun zip(name: String, vararg entries: String) = File(tmp.root, name).also { f ->
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { z.putNextEntry(ZipEntry(it)); z.write("x".toByteArray()); z.closeEntry() } }
    }

    private fun route(f: File) = OpenAll.screenFor(FileSniffer.of(f), f)

    @Test fun pdfGoesToReaderWhateverTheName() {
        assertEquals(OpenScreen.READER, route(file("a.pdf", "%PDF-1.7\n".toByteArray())))
        assertEquals(OpenScreen.READER, route(file("scan.bin", "%PDF-1.4\n".toByteArray())))
    }

    @Test fun editableOfficeGoesToEditors() {
        assertEquals(OpenScreen.OFFICE, route(zip("r.docx", "[Content_Types].xml", "word/document.xml")))
        assertEquals(OpenScreen.OFFICE, route(zip("t.xlsx", "xl/workbook.xml")))
        assertEquals(OpenScreen.OFFICE, route(file("t.csv", "a,b\n1,2\n".toByteArray())))
        assertEquals(OpenScreen.OFFICE, route(file("notes.txt", "hello".toByteArray())))
        assertEquals(OpenScreen.OFFICE, route(file("letter.rtf", "{\\rtf1 hi}".toByteArray())))
    }

    @Test fun everythingElseGoesToTheViewer() {
        assertEquals(OpenScreen.VIEWER, route(file("data.json", "{\"a\":1}".toByteArray())))
        assertEquals(OpenScreen.VIEWER, route(file("readme.md", "# t".toByteArray())))
        assertEquals(OpenScreen.VIEWER, route(file("pic", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))))
        assertEquals(OpenScreen.VIEWER, route(zip("book.epub", "META-INF/container.xml", "OEBPS/a.xhtml")))
        assertEquals(OpenScreen.VIEWER, route(zip("comic.cbz", "01.jpg", "02.jpg")))
        assertEquals(OpenScreen.VIEWER, route(zip("a.zip", "x/y.txt", "z.bin")))
        // an Office name whose content is a ZIP of something else is not sent to the editors
        assertEquals(OpenScreen.VIEWER, route(zip("fake.docx", "readme.txt", "x.bin")))
        assertEquals(OpenScreen.VIEWER, route(file("blob.dat", byteArrayOf(0, 1, 2, 3, 0, 0, 9))))
        // big text files use the streamed viewer, not the in-memory editor
        val big = file("huge.txt", ByteArray((OpenAll.TEXT_EDITOR_MAX + 10).toInt()) { 'a'.code.toByte() })
        assertEquals(OpenScreen.VIEWER, route(big))
    }

    @Test fun searchTextOfNonPdf() {
        assertEquals("مرحبا", OpenAllText.extract(file("a.txt", "مرحبا".toByteArray(charset("windows-1256")))))
        val vcf = file("c.vcf", "BEGIN:VCARD\nFN:Ali\nTEL:123\nEND:VCARD\n".toByteArray())
        assertTrue(OpenAllText.extract(vcf)!!.contains("123"))
        assertNull(OpenAllText.extract(file("p.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))))
        assertEquals(FType.VCF, FileSniffer.of(vcf))
    }
}
