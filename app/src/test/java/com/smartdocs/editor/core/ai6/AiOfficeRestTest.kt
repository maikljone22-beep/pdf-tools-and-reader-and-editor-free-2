package com.smartdocs.editor.core.ai6

import com.smartdocs.editor.core.ai5.AiLang
import com.smartdocs.editor.ui.ai5.PanelWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** aor (fix/ai-office-rest): crafts, Space craft list, image-edit request, panel width, PCM helpers, Danish. */
class AiOfficeRestTest {

    private val answer = "Sure:\n```json\n{\"title\":\"الطاقة الشمسية\",\"subtitle\":\"نظرة سريعة\",\"stats\":[{\"value\":\"40%\",\"label\":\"خفض التكلفة\"},{\"value\":\"\"}]," +
        "\"sections\":[{\"heading\":\"مقدمة\",\"text\":\"تعريف <قصير> & واضح\",\"bullets\":[\"أ\",\"ب\"],\"page\":\"٢\"},{\"heading\":\"\",\"text\":\"\"}," +
        "{\"title\":\"Costs\",\"body\":\"Para one.\\n\\nPara two.\",\"pages\":[3,4]}],\"takeaway\":\"الخلاصة هنا\"}\n```"

    private val labels = CraftLabels("Key figures", "Takeaway", "Contents")

    @Test fun parsesCraftsLeniently() {
        val c = Crafts.parse(CraftKind.INFOGRAPHIC, answer)!!
        assertEquals("الطاقة الشمسية", c.title)
        assertEquals("نظرة سريعة", c.subtitle)
        assertEquals(1, c.stats.size)                       // the empty value is dropped
        assertEquals(2, c.sections.size)                    // the empty section is dropped
        assertEquals(listOf(2), c.sections[0].pages)        // Arabic digit
        assertEquals("Costs", c.sections[1].heading)        // "title" alias
        assertEquals(listOf(3, 4), c.sections[1].pages)
        assertTrue(c.rtl)
        assertNull(Crafts.parse(CraftKind.WEB_PAGE, "no json"))
        assertTrue(Crafts.prompt(CraftKind.WEB_PAGE, "da", "", "x").contains("الدنماركية"))
    }

    @Test fun htmlIsEscapedSelfContainedAndRtl() {
        val info = Crafts.html(Crafts.parse(CraftKind.INFOGRAPHIC, answer)!!, labels, "Made from: a&b.pdf")
        assertTrue(info.startsWith("<!DOCTYPE html>"))
        assertTrue(info.contains("dir=\"rtl\""))
        assertTrue(info.contains("تعريف &lt;قصير&gt; &amp; واضح"))
        assertTrue(info.contains("<div class=\"n\">1</div>"))
        assertTrue(info.contains("<b>40%</b>"))
        assertTrue(info.contains("<footer>Made from: a&amp;b.pdf</footer>"))
        assertTrue(info.contains("<h2>Takeaway</h2>"))
        assertFalse(info.contains("<script"))
        assertFalse(info.contains("http://") || info.contains("https://"))
        val web = Crafts.html(Crafts.parse(CraftKind.WEB_PAGE, answer)!!, labels)
        assertTrue(web.contains("<p>Para one.</p><p>Para two.</p>"))
        assertFalse(web.contains("<nav>"))                  // contents only from three sections
        assertFalse(web.contains("<footer>"))
    }

    @Test fun spaceCraftsListAddRemove() {
        val dir = Files.createTempDirectory("crafts").toFile()
        try {
            val store = SpaceCrafts(File(dir, "store"))
            val a = File(dir, "a.pptx").apply { writeText("x") }
            val b = File(dir, "b.png").apply { writeText("y") }
            assertTrue(store.list("sp1").isEmpty())
            store.add("sp1", SpaceCraft.SLIDES, a, now = 1)
            store.add("sp1", SpaceCraft.INFOGRAPHIC, b, now = 2)
            store.add("sp1", SpaceCraft.SLIDES, a, now = 3)     // same file again: moved to the top, not duplicated
            assertEquals(listOf(a.absolutePath, b.absolutePath), store.list("sp1").map { it.path })
            assertTrue(store.list("other").isEmpty())
            b.delete()
            assertEquals(1, store.list("sp1").size)              // missing files are dropped
            store.remove("sp1", a.absolutePath, deleteFile = true)
            assertTrue(store.list("sp1").isEmpty())
            assertFalse(a.exists())
            store.add("../x", SpaceCraft.WEB_PAGE, File(dir, "c.html").apply { writeText("z") })
            assertEquals(1, store.list("../x").size)             // unsafe ids stay inside the folder
            store.clear("../x")
            assertTrue(store.list("../x").isEmpty())
        } finally { dir.deleteRecursively() }
    }

    @Test fun imageEditFields() {
        assertEquals(listOf("model" to "gpt-image-1", "prompt" to "white", "size" to "1024x1024", "n" to "1"), AiMedia.editFields("gpt-image-1", "white", "1024x1024"))
        assertEquals("prompt", AiMedia.editFields("", "p", "1024x1024").first().first)   // proxy: no model
        assertTrue(AiMedia.editFields("dall-e-2", "p", "512x512").contains("response_format" to "b64_json"))
    }

    @Test fun panelWidthDragAndSteps() {
        assertEquals(700f, PanelWidth.drag(640f, 30f, rtl = false, maxDp = 1200f), 0.01f)
        assertEquals(580f, PanelWidth.drag(640f, 30f, rtl = true, maxDp = 1200f), 0.01f)
        assertEquals(PanelWidth.MIN, PanelWidth.drag(400f, -200f, rtl = false, maxDp = 1200f), 0.01f)
        assertEquals(1000f, PanelWidth.drag(960f, 100f, rtl = false, maxDp = 1000f), 0.01f)
        assertEquals(640f, PanelWidth.nextStep(480f), 0.01f)
        assertEquals(960f, PanelWidth.nextStep(700f), 0.01f)
        assertEquals(480f, PanelWidth.nextStep(960f), 0.01f)
    }

    @Test fun pcmHelpers() {
        assertEquals(listOf<Short>(1, -2), WavPcm.shorts(byteArrayOf(1, 0, -2, -1, 9)).toList())
        assertEquals(listOf<Short>(1, 2, 3), WavPcm.concat(listOf(shortArrayOf(1), shortArrayOf(), shortArrayOf(2, 3))).toList())
    }

    @Test fun danishIsATranslationLanguage() {
        assertEquals("الدنماركية", AiLang.name("da"))
        assertTrue(AiLang.NAMES.containsKey("da"))
    }
}
