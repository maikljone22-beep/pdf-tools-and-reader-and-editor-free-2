package com.smartdocs.editor.core.gaps6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolWizardTest {
    private fun top(q: String) = ToolWizard.rank(q).firstOrNull()?.id

    @Test fun mergeAndCompress() {
        assertEquals("merge", top("دمج"))
        assertEquals("merge", top("أريد دمج ملفين"))
        assertEquals("compress", top("ضغط"))
        assertEquals("compress", top("ضغط الملف"))
        assertTrue(ToolWizard.rank("ضغط").any { it.id == "optimize" })
    }

    @Test fun signAndWord() {
        assertEquals("sign", top("توقيع"))
        assertTrue(ToolWizard.rank("توقيع").any { it.id == "digital_sign" })
        assertEquals("convert", top("تحويل إلى وورد"))
        assertEquals("convert", top("تحويل الى وورد"))   // without hamza
        assertEquals("digital_sign", top("توقيع رقمي"))
    }

    @Test fun scanOcrProtect() {
        assertEquals("scan", top("مسح"))
        assertTrue(top("OCR")!!.startsWith("ocr"))
        assertEquals("ocr", top("ocr"))
        assertEquals("protect", top("حماية"))
        assertEquals("protect", top("حمايه"))           // ta marbuta folded
        assertEquals("redact", top("تنقيح"))
    }

    @Test fun aiGoals() {
        assertEquals("ai", top("ترجمة"))
        assertEquals("ai", top("تلخيص"))
        assertEquals("ai", top("summarize"))
        assertFalse(ToolWizard.rank("ترجمة", allowed = ToolWizard.KEYWORDS.keys - ToolWizard.AI_TOOLS).any { it.id == "ai" })
    }

    @Test fun diacriticsAndEnglish() {
        assertEquals("merge", top("دَمْج"))
        assertEquals("merge", top("merge"))
        assertEquals("split", top("split pdf"))
        assertEquals("pdf_to_images", top("pdf to images"))
    }

    @Test fun noiseAndEmpty() {
        assertTrue(ToolWizard.rank("").isEmpty())
        assertTrue(ToolWizard.rank("   ").isEmpty())
        assertTrue(ToolWizard.rank("زززززز").isEmpty())
        assertTrue(ToolWizard.rank("دمج", limit = 2).size <= 2)
    }

    @Test fun suggestedGoalsAllMatch() {
        for (g in ToolWizard.SUGGESTED) assertTrue(g, ToolWizard.rank(g).isNotEmpty())
    }

    @Test fun everyToolHasKeywords() {
        val ids = listOf("scan", "images_to_pdf", "blank", "templates", "ocr", "convert", "organize", "merge", "split", "extract", "rotate",
            "compress", "page_numbers", "watermark", "doc_info", "attachments", "sign", "digital_sign", "forms", "form_builder", "protect",
            "cert_protect", "unlock", "redact", "sanitize", "protect_advanced", "flatten", "pdf_to_images", "pdf_to_text", "search_all",
            "search_highlight", "find_replace", "search_remove", "compare", "scan_studio", "to_pdf_plus", "mail_merge", "portfolio", "ocr_plus", "export_plus",
            "optimize", "pdfa", "accessibility", "recipes", "batch", "ai", "read_aloud", "podcast", "ai_spaces", "print", "batch_print", "e5_hub")
        assertEquals(ids.toSet(), ToolWizard.KEYWORDS.keys)
        for (id in ids) assertTrue(id, ToolWizard.KEYWORDS[id]!!.isNotEmpty())
    }
}
