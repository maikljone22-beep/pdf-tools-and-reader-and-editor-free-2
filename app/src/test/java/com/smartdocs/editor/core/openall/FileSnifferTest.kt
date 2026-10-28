package com.smartdocs.editor.core.openall

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSnifferTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun s(t: String) = t.toByteArray(Charsets.ISO_8859_1)
    private fun at(off: Int, t: String, size: Int = 600) = ByteArray(size).also { t.toByteArray(Charsets.ISO_8859_1).copyInto(it, off) }

    @Test fun pdfByMagicEvenWithWrongName() {
        assertEquals(FType.PDF, FileSniffer.detect("report.docx", s("%PDF-1.7\n")))
        assertEquals(FType.PDF, FileSniffer.detect("noext", s("%PDF-1.4")))
    }

    @Test fun images() {
        assertEquals(FType.IMAGE, FileSniffer.detect("x.bin", b(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(FType.IMAGE, FileSniffer.detect("x", b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(FType.IMAGE, FileSniffer.detect("anim", s("GIF89a....")))
        assertEquals(FType.IMAGE, FileSniffer.detect("a", s("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
        assertEquals(FType.TIFF, FileSniffer.detect("scan", b(0x49, 0x49, 0x2A, 0x00, 8, 0, 0, 0)))
        assertEquals(FType.RAW, FileSniffer.detect("photo.dng", b(0x49, 0x49, 0x2A, 0x00, 8, 0, 0, 0)))
        assertEquals(FType.HEIF, FileSniffer.detect("p", s("\u0000\u0000\u0000\u0018ftypheic\u0000\u0000\u0000\u0000mif1heic")))
        assertEquals(FType.AVIF, FileSniffer.detect("p", s("\u0000\u0000\u0000\u001cftypavif\u0000\u0000\u0000\u0000avifmif1miaf")))
        assertEquals(FType.AVIF, FileSniffer.detect("p", s("\u0000\u0000\u0000\u001cftypmif1\u0000\u0000\u0000\u0000avifmif1miaf")))
        assertEquals(FType.IMAGE, FileSniffer.detect("fav.ico", b(0, 0, 1, 0, 1, 0, 16, 16)))
        assertEquals(FType.SVG, FileSniffer.detect("drawing", s("<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")))
    }

    @Test fun media() {
        assertEquals(FType.VIDEO, FileSniffer.detect("clip", s("\u0000\u0000\u0000\u0018ftypisom\u0000\u0000\u0002\u0000isomiso2")))
        assertEquals(FType.AUDIO, FileSniffer.detect("song", s("\u0000\u0000\u0000\u0018ftypM4A \u0000\u0000\u0002\u0000M4A mp42")))
        assertEquals(FType.AUDIO, FileSniffer.detect("x.mp4", s("\u0000\u0000\u0000\u0018ftypM4A \u0000\u0000\u0002\u0000M4A mp42")))
        assertEquals(FType.AUDIO, FileSniffer.detect("a", s("ID3\u0004\u0000")))
        assertEquals(FType.AUDIO, FileSniffer.detect("a", s("fLaC\u0000")))
        assertEquals(FType.AUDIO, FileSniffer.detect("a", s("OggS\u0000")))
        assertEquals(FType.VIDEO, FileSniffer.detect("v.ogv", s("OggS\u0000")))
        assertEquals(FType.VIDEO, FileSniffer.detect("v", b(0x1A, 0x45, 0xDF, 0xA3, 0x9F)))
        assertEquals(FType.AUDIO, FileSniffer.detect("w", s("RIFF\u0000\u0000\u0000\u0000WAVEfmt ")))
    }

    @Test fun containers() {
        val zip = b(0x50, 0x4B, 0x03, 0x04, 0x14, 0)
        assertEquals(FType.DOCX, FileSniffer.detect("a.pdf", zip, zipEntries = setOf("[Content_Types].xml", "word/document.xml")))
        assertEquals(FType.XLSX, FileSniffer.detect("a", zip, zipEntries = setOf("xl/workbook.xml")))
        assertEquals(FType.PPTX, FileSniffer.detect("a", zip, zipEntries = setOf("ppt/presentation.xml")))
        assertEquals(FType.EPUB, FileSniffer.detect("a.zip", zip, zipEntries = setOf("mimetype"), zipMimetype = "application/epub+zip"))
        assertEquals(FType.ODS, FileSniffer.detect("a", zip, zipMimetype = "application/vnd.oasis.opendocument.spreadsheet"))
        assertEquals(FType.ODP, FileSniffer.detect("a", zip, zipMimetype = "application/vnd.oasis.opendocument.presentation"))
        assertEquals(FType.XPS, FileSniffer.detect("a", zip, zipEntries = setOf("FixedDocumentSequence.fdseq", "Documents/1/Pages/1.fpage")))
        assertEquals(FType.CBZ, FileSniffer.detect("comic.zip", zip, zipEntries = setOf("001.jpg", "002.jpg", "003.png")))
        assertEquals(FType.FB2, FileSniffer.detect("book.zip", zip, zipEntries = setOf("book.fb2")))
        assertEquals(FType.IWORK, FileSniffer.detect("a.numbers", zip, zipEntries = setOf("Index/Document.iwa", "preview.jpg")))
        assertEquals(FType.ZIP, FileSniffer.detect("a.zip", zip, zipEntries = setOf("readme.txt", "lib/x.so")))
        val ole = b(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
        assertEquals(FType.DOC, FileSniffer.detect("a", ole, oleStreams = listOf("Root Entry", "WordDocument", "1Table")))
        assertEquals(FType.XLS, FileSniffer.detect("a", ole, oleStreams = listOf("Workbook")))
        assertEquals(FType.PPT, FileSniffer.detect("a", ole, oleStreams = listOf("PowerPoint Document")))
        assertEquals(FType.MSG, FileSniffer.detect("a", ole, oleStreams = listOf("__substg1.0_0037001F", "__properties_version1.0")))
        assertEquals(FType.MSG, FileSniffer.detect("mail.msg", ole))
        assertEquals(FType.SEVENZ, FileSniffer.detect("a", b(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, 0, 4)))
        assertEquals(FType.CB7, FileSniffer.detect("a.cb7", b(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, 0, 4)))
        assertEquals(FType.RAR, FileSniffer.detect("a", s("Rar!\u001A\u0007\u0001\u0000")))
        assertEquals(FType.CBR, FileSniffer.detect("a.cbr", s("Rar!\u001A\u0007\u0000")))
        assertEquals(FType.GZIP, FileSniffer.detect("a", b(0x1F, 0x8B, 8, 0)))
        assertEquals(FType.TAR, FileSniffer.detect("a", at(257, "ustar")))
        assertEquals(FType.BZIP2, FileSniffer.detect("a", s("BZh91AY&SY")))
        assertEquals(FType.XZ, FileSniffer.detect("a", b(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)))
    }

    @Test fun booksAndOthers() {
        assertEquals(FType.MOBI, FileSniffer.detect("x", at(60, "BOOKMOBI", 100)))
        assertEquals(FType.DJVU, FileSniffer.detect("x", s("AT&TFORM\u0000\u0000\u0000\u0000DJVM")))
        assertEquals(FType.CHM, FileSniffer.detect("x", s("ITSF\u0003\u0000")))
        assertEquals(FType.FB2, FileSniffer.detect("x", s("<?xml version=\"1.0\" encoding=\"utf-8\"?><FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">")))
        assertEquals(FType.WOFF, FileSniffer.detect("x", s("wOFF\u0000\u0001\u0000\u0000")))
        assertEquals(FType.WOFF2, FileSniffer.detect("x", s("wOF2\u0000\u0001")))
        assertEquals(FType.FONT, FileSniffer.detect("x", s("OTTO\u0000\u000a")))
        assertEquals(FType.FONT, FileSniffer.detect("f.ttf", b(0, 1, 0, 0, 0, 12)))
        assertEquals(FType.DWG, FileSniffer.detect("x", s("AC1018\u0000\u0000")))
        assertEquals(FType.RTF, FileSniffer.detect("x.doc", s("{\\rtf1\\ansi")))
    }

    @Test fun textKinds() {
        assertEquals(FType.VCF, FileSniffer.detect("contacts", s("BEGIN:VCARD\r\nVERSION:3.0\r\n")))
        assertEquals(FType.ICS, FileSniffer.detect("invite", s("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n")))
        assertEquals(FType.HTML, FileSniffer.detect("page", s("<!DOCTYPE html><html><body>x</body></html>")))
        assertEquals(FType.EML, FileSniffer.detect("message", s("From: a@b.c\r\nSubject: hi\r\nMIME-Version: 1.0\r\n\r\nbody")))
        assertEquals(FType.CODE, FileSniffer.detect("data", s("{\"a\": 1}")))
        assertEquals(FType.CODE, FileSniffer.detect("a.json", s("{\"a\": 1}")))
        assertEquals(FType.MARKDOWN, FileSniffer.detect("readme.md", s("# Title\n")))
        assertEquals(FType.CSV, FileSniffer.detect("t.csv", s("a,b\n1,2\n")))
        assertEquals(FType.DXF, FileSniffer.detect("plan", s("  0\nSECTION\n  2\nHEADER\n")))
        assertEquals(FType.TEXT, FileSniffer.detect("notes", s("just some words\n")))
        // Windows-1256 Arabic is still text
        assertEquals(FType.TEXT, FileSniffer.detect("a.txt", "مرحبا بالعالم".toByteArray(charset("windows-1256"))))
        // binary behind a text name → file info
        assertEquals(FType.UNKNOWN, FileSniffer.detect("fake.txt", b(0, 1, 2, 3, 0, 0, 7, 8, 0)))
        assertEquals(FType.UNKNOWN, FileSniffer.detect("blob", b(0, 1, 2, 3, 0, 0, 7, 8, 0)))
        assertEquals(FType.VIDEO, FileSniffer.detect("x.mkv", ByteArray(0)).let { FileSniffer.byExtension("x.mkv") })
    }

    @Test fun mimeHintOnlyWhenNameSaysNothing() {
        assertEquals(FType.AUDIO, FileSniffer.byMime("audio/mpeg"))
        assertEquals(FType.EPUB, FileSniffer.byMime("application/epub+zip"))
        assertEquals(FType.HTML, FileSniffer.detect("download", s("<p>hello</p>"), mime = "text/html"))
    }

    @Test fun extensions() {
        assertEquals("gz", FileSniffer.extOf("a.tar.gz"))
        assertEquals(FType.FB2, FileSniffer.byExtension("book.fb2.zip"))
        assertEquals(FType.TEXT, FileSniffer.byExtension("README"))
        assertEquals("pdf", FileSniffer.extFor(FType.PDF))
    }
}
