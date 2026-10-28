package com.smartdocs.editor.core.ai6

import com.smartdocs.editor.core.ai5.PiiKind
import com.smartdocs.editor.ui.ai6.ImagePlacement
import com.smartdocs.editor.ui.ai6.TranscriptText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

class AiDocToolsTest {

    // ---------------- B13 slides ----------------
    private val answer = "```json\n{\"title\":\"الطاقة الشمسية\",\"slides\":[{\"title\":\"مقدمة\",\"bullets\":[\"تعريف\",\"أهمية\"],\"notes\":\"نبدأ بالتعريف.\",\"page\":\"١\"}," +
        "{\"title\":\"\",\"bullets\":[]},{\"title\":\"Costs & <prices>\",\"points\":[\"a\",\"b\"],\"page\":[3,4]}]}\n```"

    @Test fun parsesSlidesLeniently() {
        val d = SlidesGen.parse(answer)!!
        assertEquals("الطاقة الشمسية", d.title)
        assertEquals(2, d.slides.size)                     // the empty slide is dropped
        assertEquals(listOf(1), d.slides[0].pages)          // Arabic digit
        assertEquals(listOf("a", "b"), d.slides[1].bullets) // "points" alias
        assertTrue(d.rtl)
        assertNull(SlidesGen.parse("no json here"))
        assertEquals("نبدأ بالتعريف.", SlidesGen.narration(d.slides[0]))
        assertEquals("Costs & <prices>. a. b", SlidesGen.narration(d.slides[1]))
    }

    @Test fun markdownHasHeadingsBulletsAndNotes() {
        val md = SlidesGen.markdown(SlidesGen.parse(answer)!!)
        assertTrue(md.startsWith("# الطاقة الشمسية\n"))
        assertTrue(md.contains("## 1. مقدمة"))
        assertTrue(md.contains("- تعريف"))
        assertTrue(md.contains("> نبدأ بالتعريف."))
        assertTrue(md.contains("_(p. 3, p. 4)_"))
    }

    @Test fun pptxPackageIsCompleteAndEscaped() {
        val deck = SlidesGen.parse(answer)!!
        val bytes = ByteArrayOutputStream().also { SlidesPptx.write(deck, it) }.toByteArray()
        val entries = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes().toString(Charsets.UTF_8) }
        }
        for (n in listOf("[Content_Types].xml", "_rels/.rels", "ppt/presentation.xml", "ppt/slides/slide1.xml", "ppt/slides/slide3.xml",
            "ppt/slideMasters/slideMaster1.xml", "ppt/slideLayouts/slideLayout1.xml", "ppt/theme/theme1.xml")) assertTrue(n, entries.containsKey(n))
        assertTrue(!entries.containsKey("ppt/slides/slide4.xml"))   // cover + 2
        val s3 = entries["ppt/slides/slide3.xml"]!!
        assertTrue(s3.contains("Costs &amp; &lt;prices&gt;"))
        assertTrue(s3.contains("rtl=\"1\""))
        assertTrue(entries["[Content_Types].xml"]!!.contains("/ppt/slides/slide3.xml"))
        assertEquals(3, Regex("<p:sldId ").findAll(entries["ppt/presentation.xml"]!!).count())
        // aor (B13): speaker notes as a notes slide of slide 2 (the first content slide), on a notes master
        assertTrue(entries.containsKey("ppt/notesSlides/notesSlide2.xml"))
        assertTrue(!entries.containsKey("ppt/notesSlides/notesSlide3.xml"))   // that slide has no notes
        assertTrue(entries.containsKey("ppt/notesMasters/notesMaster1.xml"))
        assertTrue(entries.containsKey("ppt/theme/theme2.xml"))
        assertTrue(entries["ppt/presentation.xml"]!!.contains("<p:notesMasterIdLst>"))
        assertTrue(entries["ppt/slides/_rels/slide2.xml.rels"]!!.contains("notesSlide2.xml"))
        assertTrue(entries["[Content_Types].xml"]!!.contains("/ppt/notesSlides/notesSlide2.xml"))
        val doc = com.smartdocs.editor.core.office5.PptDoc.of(com.smartdocs.editor.core.office5.OPackage.create(entries.mapValues { it.value.toByteArray() }))
        assertEquals(3, doc.slideCount)
        assertEquals("نبدأ بالتعريف.", com.smartdocs.editor.core.office5.SlideFind.notesText(doc, 1))
        assertEquals("", com.smartdocs.editor.core.office5.SlideFind.notesText(doc, 2))
    }

    // ---------------- B17 reviews + tables ----------------
    @Test fun parsesReviewSectionsItemsLevelsAndPages() {
        val r = DocReview.parse(DocReview.Kind.CONTRACT, "Here: {\"summary\":\"عقد توريد\",\"sections\":[{\"title\":\"الأطراف\",\"items\":[{\"label\":\"المورد\",\"value\":\"شركة أ\",\"page\":1}," +
            "{\"label\":\"الإنهاء\",\"value\":\"بإشعار 30 يوماً\",\"level\":\"HIGH\",\"pages\":\"5-6\"}]},{\"title\":\"ملاحظات\",\"items\":[\"بند غامض\"]},{\"title\":\"\",\"items\":[]}]}")!!
        assertEquals("عقد توريد", r.summary)
        assertEquals(2, r.sections.size)
        assertEquals("high", r.sections[0].items[1].level)
        assertEquals(listOf(5, 6), r.sections[0].items[1].pages)
        assertEquals("بند غامض", r.sections[1].items[0].value)
        val text = DocReview.text(r)
        assertTrue(text.contains("■ الأطراف"))
        assertTrue(text.contains("• المورد: شركة أ (ص 1)"))
        val csv = DocReview.csv(r).split("\r\n")
        assertEquals("section,label,value,level,pages", csv[0])
        assertNull(DocReview.parse(DocReview.Kind.RISK, "nothing"))
    }

    @Test fun parsesTablesPadsRowsAndWritesCsv() {
        val t = DocReview.parseTables("{\"tables\":[{\"title\":\"Sales\",\"page\":\"2\",\"rows\":[[\"Year\",\"Amount\",\"Note\"],[\"2024\",\"1,200\"],[\"\",\"\"],[\"2025\",\"say \\\"hi\\\"\",\"x\"]]},{\"rows\":[]}]}")
        assertEquals(1, t.size)
        assertEquals(2, t[0].page)
        assertEquals(3, t[0].rows.size)                     // the blank row is dropped
        assertEquals(listOf("2024", "1,200", ""), t[0].rows[1])
        val csv = DocReview.tablesCsv(t)
        assertTrue(csv.startsWith("# Sales (p. 2)\r\n"))
        assertTrue(csv.contains("2024,\"1,200\",\r\n"))
        assertTrue(csv.contains("2025,\"say \"\"hi\"\"\",x\r\n"))
        assertEquals(emptyList<ExtractedTable>(), DocReview.parseTables("[]"))
        assertEquals("\" a\"", CsvText.cell(" a"))
    }

    // ---------------- B15 video helpers ----------------
    private fun wav(rate: Int, ch: Int, samples: ShortArray): ByteArray {
        val data = samples.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(ch.toShort()); b.putInt(rate); b.putInt(rate * ch * 2); b.putShort((ch * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data); samples.forEach { b.putShort(it) }
        return b.array()
    }

    @Test fun wavParseMonoAndResample() {
        val p = WavPcm.parse(wav(22050, 2, shortArrayOf(100, 300, -100, -300)))!!
        assertEquals(22050, p.sampleRate); assertEquals(2, p.channels)
        val mono = WavPcm.toMono(p)
        assertEquals(listOf<Short>(200, -200), mono.toList())
        val up = WavPcm.resample(shortArrayOf(0, 100, 200, 300), 22050, 44100)
        assertEquals(8, up.size)
        assertEquals(50.toShort(), up[1])
        assertNull(WavPcm.parse(ByteArray(10)))
        assertEquals(3.0, WavPcm.slideSeconds(0, 44100), 1e-9)
        assertEquals(10.7, WavPcm.slideSeconds(441000, 44100), 1e-9)
    }

    @Test fun argbToI420WhiteAndBlack() {
        val (y, u, v) = WavPcm.argbToI420(intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt()), 2, 2)
        assertEquals(235, y[0].toInt() and 0xFF)
        assertEquals(16, y[2].toInt() and 0xFF)
        assertEquals(128, u[0].toInt() and 0xFF); assertEquals(128, v[0].toInt() and 0xFF)
    }

    // ---------------- AI PII ----------------
    @Test fun aiPiiParseAndLocate() {
        val items = AiPii.parse("{\"items\":[{\"page\":2,\"kind\":\"name\",\"text\":\"محمد  أحمد\"},{\"page\":2,\"kind\":\"address\",\"text\":\"شارع الملك فهد، الرياض\"},{\"kind\":\"other\",\"text\":\"ab\"}]}")
        assertEquals(2, items.size)                           // too-short text dropped
        assertEquals(1, items[0].page)
        assertEquals(PiiKind.NAME, items[0].kind); assertEquals(PiiKind.ADDRESS, items[1].kind)
        val text = "السيد محمد أحمد يسكن في شارع الملك فهد، الرياض. اتصل بـ محمد\nأحمد"
        val found = AiPii.locate(text, items + AiPiiItem(1, PiiKind.ADDRESS, "الرياض"))
        assertEquals(3, found.size)                           // two name occurrences + the long address ("الرياض" inside it is not repeated)
        assertEquals("محمد أحمد", found[0].value)
        assertEquals("محمد\nأحمد", found[2].value)
        // a regex hit already covering the address wins
        val start = text.indexOf("شارع")
        assertEquals(2, AiPii.locate(text, items, listOf(start until start + 5)).size)
        assertEquals(PiiKind.NATIONAL_ID, AiPii.kindOf("account"))
    }

    @Test fun aiPiiBatchesStayUnderBudget() {
        val pages = (0 until 5).map { it to "x".repeat(2500) }
        val b = AiPii.batches(pages)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), b.map { it.first })
        assertTrue(b.all { it.second.length <= AiPii.BATCH_CHARS })
        assertTrue(b[0].second.startsWith("[صفحة 1]"))
    }

    // ---------------- AI-8 placement, transcription text, translation prompt ----------------
    @Test fun imagePlacementIsCentredAndFits() {
        val r = ImagePlacement.centred(1000, 500, 600f, 800f)
        assertEquals(360f, r[2], 0.01f); assertEquals(180f, r[3], 0.01f)
        assertEquals(120f, r[0], 0.01f); assertEquals(310f, r[1], 0.01f)
    }

    @Test fun transcriptParagraphs() {
        val t = TranscriptText.paragraphs("أولاً جملة. ثانياً جملة؟ Third one! fourth", chars = 25)
        assertTrue(t.contains("\n\n"))
        assertEquals("أولاً جملة. ثانياً جملة؟ Third one! fourth", t.replace("\n\n", " "))
    }

    @Test fun translationPromptCarriesGuidance() {
        val p = com.smartdocs.editor.core.ai5.DocTranslator.batchPrompt("en", "{\"1\":\"مرحبا\"}", "use British spelling")
        assertTrue(p.contains("use British spelling"))
        assertTrue(p.endsWith("{\"1\":\"مرحبا\"}"))
        assertNotNull(com.smartdocs.editor.core.ai5.DocTranslator.batchPrompt("en", "{}"))
    }
}
