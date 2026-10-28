package com.smartdocs.editor.core.convert6

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/pdf39-convert-2: creation presets and a tagged PDF when creating one (FINAL_CHECK_2 #3). */
class CreatePresetTest {
    @Test fun presetsAndCards() {
        assertEquals(88, CreatePreset.quality(CreatePreset.STANDARD, 88))
        assertEquals(95, CreatePreset.quality(CreatePreset.HIGH, 88))
        assertEquals(60, CreatePreset.quality(CreatePreset.SMALLEST, 88))
        assertEquals(88, CreatePreset.quality(CreatePreset.ARCHIVAL, 88))
        listOf("word_pdf", "excel_pdf", "ppt_pdf", "images_pdf", "any_pdf").forEach { assertTrue(it, Opt.CREATE in Cv6Catalog.byId(it)!!.opts) }
        assertEquals(false, Cv6Opts().tagged)
    }
}
