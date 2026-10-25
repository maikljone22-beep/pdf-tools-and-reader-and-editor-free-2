package com.smartdocs.editor.core.ai5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalTest {

    private fun para(word: String, n: Int) = (1..n).joinToString(" ") { "$word$it" } + "."

    @Test fun chunksNeverSpanPagesAndKeepOrder() {
        val pages = listOf("فقرة أولى عن المكتبات.\n\nفقرة ثانية عن الكتب.", "", "Third page text about rivers.")
        val chunks = Chunker.chunk(pages)
        assertTrue(chunks.isNotEmpty())
        assertEquals((0 until chunks.size).toList(), chunks.map { it.id })
        assertTrue(chunks.none { it.page == 1 })
        assertEquals(2, chunks.last().page)
        // small paragraphs on the same page are merged into one chunk
        assertEquals(1, chunks.count { it.page == 0 })
    }

    @Test fun longParagraphIsSplitUnderMax() {
        val long = (1..400).joinToString(" ") { "word$it" } + "."
        val chunks = Chunker.chunk(listOf(long))
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.text.length <= Chunker.MAX })
        // nothing lost
        assertEquals(400, chunks.sumOf { c -> c.text.split(' ').count { it.startsWith("word") } })
    }

    @Test fun paragraphsBreakOnBlankLinesAndSentenceEnds() {
        val p = Chunker.paragraphs("سطر أول\nيكمل الجملة.\nجملة جديدة\n\nفقرة ثالثة")
        assertEquals(listOf("سطر أول يكمل الجملة.", "جملة جديدة", "فقرة ثالثة"), p)
    }

    @Test fun arabicStemmerMatchesPrefixesAndTaMarbuta() {
        assertEquals(AiTokens.tokens("والمكتبات"), AiTokens.tokens("مكتبة"))
        assertTrue(AiTokens.tokens("في المكتبة").none { it == "في" })
        assertEquals(listOf("river"), AiTokens.tokens("the rivers"))
        // diacritics and alef forms are folded
        assertEquals(AiTokens.tokens("أَحْمَد"), AiTokens.tokens("احمد"))
    }

    @Test fun bm25FindsTheRightChunk() {
        val pages = listOf(
            "مقدمة عامة عن التاريخ والجغرافيا.",
            "تتحدث هذه الصفحة عن النباتات الصحراوية وكيف تخزن الماء في سيقانها.",
            "Chapter about volcanoes and lava flows.",
        )
        val idx = DocIndex("k", "doc.pdf", "/doc.pdf", pages.size, Chunker.chunk(pages))
        assertEquals(1, idx.search("كيف تخزن النباتات الماء؟").first().first.page)
        assertEquals(2, idx.search("lava").first().first.page)
        assertTrue(idx.search("كلمةغيرموجودة").isEmpty())
    }

    @Test fun retrieveSpecificQuestionCitesMatchingPageFirst() {
        val pages = (0 until 30).map { i -> if (i == 17) "سعر الصرف الرسمي للريال تحدده لجنة السياسة النقدية كل ربع سنة." else para("نص$i", 60) }
        val idx = DocIndex("k", "book.pdf", "/book.pdf", pages.size, Chunker.chunk(pages))
        val src = Retriever.retrieve(listOf(idx), "من يحدد سعر الصرف؟")
        assertTrue(src.isNotEmpty())
        assertTrue(src.any { it.page == 17 })
        assertEquals((1..src.size).toList(), src.map { it.n })
        assertTrue(src.sumOf { it.text.length } <= Retriever.MAX_CHARS)
    }

    @Test fun genericQuestionSpreadsOverWholeDocument() {
        val pages = (0 until 200).map { i -> para("كلمة$i", 80) }
        val idx = DocIndex("k", "big.pdf", "/big.pdf", pages.size, Chunker.chunk(pages))
        assertTrue(Retriever.isGeneric("لخص هذا الكتاب"))
        val src = Retriever.retrieve(listOf(idx), "لخص هذا الكتاب")
        val pagesHit = src.map { it.page }
        assertTrue(pagesHit.min() < 20)
        assertTrue(pagesHit.max() > 180)
        assertTrue(src.sumOf { it.text.length } <= Retriever.MAX_CHARS + src.size * 2)
    }

    @Test fun multiDocumentKeepsEveryRelevantDocument() {
        val a = DocIndex("a", "contract_a.pdf", "/a.pdf", 2, Chunker.chunk(listOf("مدة العقد سنتان قابلة للتجديد.", para("x", 50))))
        val b = DocIndex("b", "contract_b.pdf", "/b.pdf", 2, Chunker.chunk(listOf(para("y", 50), "مدة العقد ثلاث سنوات ولا يجدد تلقائياً.")))
        val src = Retriever.retrieve(listOf(a, b), "ما مدة العقد؟")
        assertTrue(src.any { it.docKey == "a" })
        assertTrue(src.any { it.docKey == "b" })
        val block = Retriever.sourcesBlock(src)
        assertTrue(block.contains("contract_a.pdf"))
        assertTrue(block.startsWith("[1]"))
    }

    @Test fun spreadClipsButCoversAllChunks() {
        val idx = DocIndex("k", "d", "/d", 10, Chunker.chunk((0 until 10).map { para("w$it", 150) }))
        val s = idx.spread(4000)
        assertTrue(s.isNotEmpty())
        assertTrue(s.sumOf { it.text.length } <= 4000 + s.size * 2)
        assertFalse(DocIndex.material(s).isBlank())
        assertTrue(DocIndex.material(s).contains("[صفحة 1]"))
    }
}
