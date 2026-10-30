package com.smartdocs.editor.core.convert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** FINAL_CHECK_2 #5: PDF comments become Word comments anchored on the paragraph they sit on. */
class DocxCommentsTest {

    private fun ltrLine(text: String, x: Float, baseline: Float, fs: Float = 12f): List<Glyph> {
        val w = fs * 0.5f
        return text.mapIndexed { i, c -> Glyph(x + i * w, baseline - fs * 0.7f, w, fs * 0.7f, baseline, c.toString(), fs) }
    }

    private fun parts(comments: Boolean, layout: PageLayout): Map<String, String> {
        val bos = ByteArrayOutputStream()
        DocxBuilder(bos, "t", PageFurniture.NONE, false, comments = comments).use { b -> b.addPage(layout); b.finish() }
        val out = HashMap<String, String>()
        ZipInputStream(bos.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        return out
    }

    private fun layout(): PageLayout {
        val gs = ltrLine("First paragraph of the page.", 72f, 100f) + ltrLine("Second paragraph, with a highlight.", 72f, 300f)
        val c = listOf(
            PageComment(Rect(72f, 290f, 200f, 302f), "Ali", "2026-10-30T05:00:00Z", "Check this figure", "Highlight"),
            PageComment(Rect(400f, 700f, 420f, 720f), "", "", "Note far from text\nsecond line", "Text"),
        )
        return LayoutAnalyzer.analyze(PageContent(0, 595f, 842f, gs, comments = c))
    }

    @Test fun commentsAnchorOnTheOverlappedParagraph() {
        val p = parts(true, layout())
        val doc = p["word/document.xml"]!!
        val cm = p["word/comments.xml"]!!
        assertTrue(cm.contains("w:author=\"Ali\"") && cm.contains("[Highlight] Check this figure"))
        assertTrue(cm.contains("w:author=\"PDF\"") && cm.contains("second line"))
        // the highlight sits on the second paragraph: its range starts after the first paragraph's text
        val second = doc.indexOf("Second paragraph")
        val start0 = doc.indexOf("<w:commentRangeStart w:id=\"0\"/>")
        assertTrue(start0 in 0 until second && doc.indexOf("First paragraph") < start0)
        assertTrue(doc.contains("<w:commentReference w:id=\"0\"/>") && doc.contains("<w:commentReference w:id=\"1\"/>"))
        assertTrue(p["[Content_Types].xml"]!!.contains("/word/comments.xml"))
        assertTrue(p["word/_rels/document.xml.rels"]!!.contains("comments.xml"))
        assertTrue(p["word/styles.xml"]!!.contains("CommentReference"))
    }

    @Test fun switchedOffWritesNoCommentsPart() {
        val p = parts(false, layout())
        assertFalse(p.containsKey("word/comments.xml"))
        assertFalse(p["word/document.xml"]!!.contains("commentRangeStart"))
        assertEquals(2, p["word/document.xml"]!!.split("<w:p>").size - 1)
    }
}
