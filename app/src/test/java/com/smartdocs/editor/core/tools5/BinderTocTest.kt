package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** FINAL_CHECK_2 Low: binder TOC line template with field codes. */
class BinderTocTest {
    @Test fun fieldsAreFilled() {
        assertEquals("2. Report (5)", CombinePlus.tocEntry("{n}. {title} ({pages})", "Report", "r.docx", 5, 2, "2026-10-30"))
        assertEquals("r.docx — 2026-10-30", CombinePlus.tocEntry("{file} — {date}", "Report", "r.docx", 5, 2, "2026-10-30"))
        assertEquals("Report", CombinePlus.tocEntry("", "Report", "r.docx", 5, 2, "x"))
    }

    @Test fun formatSurvivesTheProjectFile() {
        val p = BinderProject("b", CombineOpts(toc = true, tocTitle = "TOC", tocFormat = "{n}|{title}"), listOf(CombineEntry(File("a.pdf"), "A")))
        val back = BinderProject.decode(p.encode(), File("."))
        assertEquals("{n}|{title}", back.opts.tocFormat)
        assertEquals(true, back.opts.toc)
    }
}
