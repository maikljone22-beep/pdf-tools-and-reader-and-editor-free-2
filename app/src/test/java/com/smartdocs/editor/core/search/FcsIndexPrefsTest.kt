package com.smartdocs.editor.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FcsIndexPrefsTest {

    @Test fun evictsOldestUntilUnderTheLimit() {
        val e = listOf(FcsIndexPrefs.Entry(1, 300, 400), FcsIndexPrefs.Entry(2, 100, 500), FcsIndexPrefs.Entry(3, 200, 300), FcsIndexPrefs.Entry(4, 50, 0))
        assertEquals(listOf(2L), FcsIndexPrefs.evict(e, 800))
        assertEquals(listOf(2L, 3L), FcsIndexPrefs.evict(e, 500))
        assertTrue(FcsIndexPrefs.evict(e, 0).isEmpty())          // no limit
        assertTrue(FcsIndexPrefs.evict(e, 5_000).isEmpty())      // already under
    }

    @Test fun xmpTextDropsMarkup() {
        val x = "<?xpacket begin=\"\"?><x:xmpmeta><dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">Annual &amp; Report</rdf:li></rdf:Alt></dc:title></x:xmpmeta>"
        assertEquals("Annual & Report", FcsIndexPrefs.xmpText(x))
    }

    @Test fun customPropsSkipStandardKeys() {
        val s = FcsIndexPrefs.customProps(mapOf("Title" to "T", "Author" to "A", "Project" to "Apollo", "Empty" to ""))
        assertEquals("Project: Apollo", s)
    }

    @Test fun widthFolding() {
        val o = NormOptions(ignoreWidth = true)
        assertEquals("abc 12", ArabicNormalizer.fold("ＡＢＣ　１２", o).text)
        assertEquals("ガ", ArabicNormalizer.fold("ｶﾞ", o).text)
        assertEquals("ａ", ArabicNormalizer.fold("Ａ", NormOptions()).text)   // off: only lower-cased
        // the index body always folds width, so a full-width document is found by a normal query
        assertEquals(IndexText.body("ｗｏｒｄ"), IndexText.body("word"))
        assertEquals(2, ArabicNormalizer.fold("ｶﾞx", o).map.size)
    }
}
