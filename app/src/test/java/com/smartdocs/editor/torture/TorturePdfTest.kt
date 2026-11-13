package com.smartdocs.editor.torture

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The torture PDFs are written by hand ([TorturePdf]); if they were malformed, an emulator test that "did not open"
 * would say nothing about the app. These tests prove the files are valid before the app is ever asked to open them.
 */
class TorturePdfTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun textDocumentHasEveryPageAndItsContent() {
        val f = tmp.newFile("book.pdf")
        TorturePdf.textPages(f, 120)
        PDDocument.load(f).use { d ->
            assertEquals(120, d.numberOfPages)
            val last = d.getPage(119)
            assertEquals(595f, last.mediaBox.width, 0.01f)
            val content = last.contents.use { String(it.readBytes(), Charsets.ISO_8859_1) }
            assertTrue(content.contains("(Page 120 of 120)"))
            assertTrue(last.resources.fontNames.iterator().hasNext())
        }
    }

    @Test fun scanReachesItsSizeAndEveryPageHoldsItsOwnImage() {
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(64, 90, BufferedImage.TYPE_INT_RGB), "jpg", it) }.toByteArray()
        val f = tmp.newFile("scan.pdf")
        val target = 200_000L
        val pages = TorturePdf.scan(f, jpeg, 64, 90, target)
        assertTrue("file is ${f.length()} bytes", f.length() >= target)
        PDDocument.load(f).use { d ->
            assertEquals(pages, d.numberOfPages)
            val streams = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<COSStream, Boolean>())   // by identity: the copies are equal in content
            for (p in d.pages) {
                val x = p.resources.cosObject.getDictionaryObject(COSName.XOBJECT) as com.tom_roush.pdfbox.cos.COSDictionary
                val im = x.getDictionaryObject(COSName.getPDFName("Im0")) as COSStream
                assertEquals(64, im.getInt(COSName.WIDTH)); assertEquals(90, im.getInt(COSName.HEIGHT))
                assertEquals(jpeg.size.toLong(), im.getLong(COSName.LENGTH))
                streams.add(im)
            }
            assertEquals("each page must carry its own image copy", pages, streams.size)
        }
    }
}
