package com.smartdocs.editor.core.ai6

import com.smartdocs.editor.core.ai5.Citation
import com.smartdocs.editor.core.ai5.Flashcard
import com.smartdocs.editor.core.ai5.SavedChat
import com.smartdocs.editor.core.ai5.Source
import com.smartdocs.editor.core.ai5.Space
import com.smartdocs.editor.core.ai5.StoredMsg
import com.smartdocs.editor.ui.ai6.ChatLinks
import com.smartdocs.editor.ui.ai6.RichPart
import com.smartdocs.editor.ui.ai6.RichText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HubChatTest {

    // ---- skills (AI-6 / AI-11) ----
    @Test fun skillCommandsParseAndApply() {
        val skills = Skills.PRESETS
        val (s, rest) = Skills.parseCommand("/latex  x^2 + 1", skills)
        assertEquals("latex", s?.id); assertEquals("x^2 + 1", rest)
        val (none, same) = Skills.parseCommand("/unknown hi", skills)
        assertNull(none); assertEquals("/unknown hi", same)
        assertEquals("plain", Skills.parseCommand(" plain ", skills).second)
        assertTrue(Skills.apply(s, "q").endsWith("\n\nq"))
        assertEquals("q", Skills.apply(null, "q"))
        val disabled = skills.map { if (it.id == "latex") it.copy(enabled = false) else it }
        assertNull(Skills.parseCommand("/latex x", disabled).first)
    }

    @Test fun skillsJsonRoundTripMergeAndPresets() {
        val custom = Skill("tldr", "TL;DR", "One line.", wantsImage = true)
        val json = Skills.toJson(listOf(custom, Skills.PRESETS[0].copy(enabled = false)))
        val back = Skills.fromJson(json)
        assertEquals(2, back.size); assertTrue(back[0].wantsImage); assertFalse(back[1].enabled)
        assertTrue(Skills.fromJson("not json").isEmpty())
        assertTrue(Skills.fromJson("[{\"name\":\"\",\"instruction\":\"x\"}]").isEmpty())
        val all = Skills.withPresets(back)
        assertEquals(Skills.PRESETS.size + 1, all.size)
        assertFalse(all.first { it.id == Skills.PRESETS[0].id }.enabled)
        // importing a preset id only changes its enabled flag
        val merged = Skills.merge(all, listOf(Skill(Skills.PRESETS[1].id, "hack", "hack", enabled = false)))
        val p = merged.first { it.id == Skills.PRESETS[1].id }
        assertEquals(Skills.PRESETS[1].instruction, p.instruction); assertFalse(p.enabled)
        assertEquals("my_skill", Skills.slug("My  Skill!"))
    }

    // ---- exports (AI-15 / AI-19) ----
    @Test fun ankiAndCsvAndTranscript() {
        val cards = listOf(Flashcard("Front\twith tab", "Back\nline 2", 3), Flashcard(" ", "skipped"))
        val anki = ChatExport.anki(cards, "My deck")
        val lines = anki.trimEnd().lines()
        assertEquals("#separator:tab", lines[0])
        assertTrue(lines.contains("#deck:My deck"))
        assertEquals(5, lines.size)   // 4 header lines + 1 card (blank front skipped)
        assertEquals(listOf("Front with tab", "Back<br>line 2<br><small>p. 3</small>", "SmartDocs"), lines[4].split('\t'))
        val csv = ChatExport.csv(listOf(Flashcard("a \"q\"", "b", null)))
        assertEquals("front,back,page\n\"a \"\"q\"\"\",\"b\",\n", csv)
        val t = ChatExport.transcript(listOf(ChatExport.Turn(true, "hi"), ChatExport.Turn(false, " yo "), ChatExport.Turn(false, "")), "U", "A")
        assertEquals("U: hi\n\nA: yo", t)
        assertEquals("Title\nbold\n\ncode", ChatExport.stripMarkdown("## Title\n**bold**\n\n```\ncode\n```"))
    }

    // ---- web text (B9) ----
    @Test fun htmlToReadableText() {
        val html = """<html><head><title>T &amp; Co</title><style>p{}</style></head><body><nav>menu</nav>
            <script>var x=1;</script><h1>Heading</h1><p>First&nbsp;para &#1575;&#x644;</p><!-- c --><ul><li>one</li><li>two</li></ul>
            <table><tr><td>a</td><td>b</td></tr></table><footer>foot</footer></body></html>"""
        assertEquals("T & Co", WebText.title(html))
        val text = WebText.htmlToText(html)
        assertFalse(text.contains("menu")); assertFalse(text.contains("var x")); assertFalse(text.contains("foot")); assertFalse(text.contains("p{}"))
        assertTrue(text.contains("Heading")); assertTrue(text.contains("First para ال")); assertTrue(text.lines().containsAll(listOf("one", "two")))
        assertEquals("https://example.com/a", WebText.normalizeUrl(" example.com/a "))
        assertNull(WebText.normalizeUrl("not a url")); assertNull(WebText.normalizeUrl("abc"))
    }

    // ---- Space sources and bundles (B9) ----
    @Test fun textSourcesAndBundleRoundTrip() {
        val src = TextSource(TextSource.URL, "Page", "https://x.org", "Body line\n\nsecond")
        assertEquals(src, SpaceSources.decode(SpaceSources.encode(src)))
        assertEquals("plain", SpaceSources.decode("plain text\nmore").title.take(5))
        val pages = SpaceSources.pages("a".repeat(2500) + "\n" + "b".repeat(2500) + "\n" + "c".repeat(7000), 3000)
        assertTrue(pages.size >= 4); assertTrue(pages.all { it.length <= 3000 })

        val dir = Files.createTempDirectory("sp").toFile()
        val f1 = SpaceSources.write(dir, src)
        val f2 = File(dir, "doc.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(SpaceSources.isSource(f1.path))
        val zip = File(dir, "s.sdspace")
        SpaceBundle.export(Space("id1", "My space", listOf(f1.path, f2.path, "/missing.pdf"), "be brief"), zip)
        val out = File(dir, "imp")
        val sp = SpaceBundle.import(zip, out, "new")!!
        assertEquals("My space", sp.name); assertEquals("be brief", sp.persona); assertEquals("new", sp.id)
        assertEquals(2, sp.files.size)
        assertEquals(src, SpaceSources.read(File(sp.files[0])))
        assertEquals(3, File(sp.files[1]).length())
        assertEquals("evil", SpaceBundle.safeName("../../evil"))
        assertNull(SpaceBundle.parseManifest("{\"name\":\"x\"}"))
        dir.deleteRecursively()
    }

    // ---- embeddings re-rank ----
    @Test fun rerankOrderAndSelect() {
        // candidate 2 is semantically best, candidate 0 lexically best
        val order = Reranker.order(listOf(0.1, 0.2, 0.9))
        assertEquals(2, order[0])
        assertEquals(listOf(0), Reranker.order(listOf(0.5)))
        val c = listOf(
            Source(1, "d1", "a.pdf", "/a.pdf", 5, "x".repeat(100)),
            Source(2, "d2", "b.pdf", "/b.pdf", 1, "y".repeat(100)),
            Source(3, "d1", "a.pdf", "/a.pdf", 2, "z".repeat(100)),
        )
        val sel = Reranker.select(c, listOf(2, 1, 0), maxChars = 250, k = 5)
        // budget keeps two, back in reading order (doc d1 first, then d2), renumbered
        assertEquals(listOf("d1", "d2"), sel.map { it.docKey })
        assertEquals(listOf(1, 2), sel.map { it.n })
        assertEquals(2, sel[0].page)
    }

    // ---- rich answers (AI-11) ----
    @Test fun splitsCodeFences() {
        val parts = RichText.split("Here:\n```latex\n\\frac{a}{b}\n```\nDone [1].")
        assertEquals(3, parts.size)
        assertEquals(RichPart.Code("latex", "\\frac{a}{b}"), parts[1])
        assertEquals(RichPart.Prose("Done [1]."), parts[2])
        assertEquals(1, RichText.split("unclosed ```latex x").size)
    }

    // ---- conversations (AI-5) ----
    @Test fun conversationLinks() {
        val cit = Citation(1, "k", "/docs/a.pdf", "a.pdf", 0, "")
        val old = SavedChat("c1", "key", "t", 0, listOf(StoredMsg("assistant", "x", listOf(cit))))
        assertEquals(listOf("/docs/a.pdf"), ChatLinks.files(old))
        val space = SavedChat("c2", "space:s9", "t", 0, emptyList())
        assertEquals("space:s9#tab=chat&chat=c2", ChatLinks.hubArg(space))
        val multi = SavedChat("c3", "key", "t", 0, emptyList(), listOf("/a.pdf", "/b.pdf", "/gone.pdf"))
        assertEquals("files:/a.pdf|/b.pdf#tab=chat&chat=c3", ChatLinks.hubArg(multi) { it != "/gone.pdf" })
        assertNull(ChatLinks.hubArg(multi) { false })
    }
}
