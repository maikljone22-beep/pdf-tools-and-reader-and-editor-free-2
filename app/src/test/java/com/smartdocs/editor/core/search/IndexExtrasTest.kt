package com.smartdocs.editor.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fc-sync: metadata text, width folding, the index size limit and log lines. */
class IndexExtrasTest {
    private val xmp = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title><rdf:Alt><rdf:li xml:lang="x-default">Annual &amp; Q4 Report</rdf:li></rdf:Alt></dc:title>
<dc:creator><rdf:Seq><rdf:li>Lina</rdf:li></rdf:Seq></dc:creator></rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end="w"?>"""

    @Test fun xmpTextAndTitle() {
        assertEquals("Annual & Q4 Report Lina", DocMeta.xmpText(xmp))
        assertEquals("Annual & Q4 Report", DocMeta.xmpTitle(xmp))
        assertNull(DocMeta.xmpTitle("<x/>"))
    }

    @Test fun customPropertiesSkipStandardKeys() {
        assertEquals("Client: ACME — Project: Apollo", DocMeta.customText(mapOf("Title" to "x", "Project" to "Apollo", "Client" to " ACME ", "Empty" to " ", "Producer" to "p")))
        assertNull(DocMeta.customText(mapOf("Title" to "x")))
    }

    @Test fun widthFolding() {
        assertEquals('A', ArabicNormalizer.widthFold('Ａ'))
        assertEquals('1', ArabicNormalizer.widthFold('１'))
        assertEquals(' ', ArabicNormalizer.widthFold('　'))
        assertEquals('ア', ArabicNormalizer.widthFold('ｱ'))
        assertEquals('x', ArabicNormalizer.widthFold('x'))
        val on = NormOptions(ignoreWidth = true)
        assertEquals("abc 12", ArabicNormalizer.fold("ＡＢＣ　１２", on).text)
        assertEquals(ArabicNormalizer.fold("ＡＢＣ", NormOptions()).text, "ａｂｃ")   // off by default: unchanged behaviour
    }

    @Test fun sizeLimitDropsOldestShare() {
        assertEquals(0, IndexExtras.toDrop(100, 50, 100))
        assertEquals(0, IndexExtras.toDrop(100, 500, 0))
        assertEquals(55, IndexExtras.toDrop(100, 200, 100))   // keep 45 % (limit with a 10 % margin)
        assertEquals(1, IndexExtras.toDrop(1, 200, 100))
        assertTrue(IndexExtras.toDrop(10, 101, 100) >= 1)
    }

    @Test fun logLine() {
        val ok = IndexExtras.line(0, "/d/a.pdf", null)
        assertTrue(ok.contains("OK  /d/a.pdf"))
        assertTrue(IndexExtras.line(0, "/d/b.pdf", "broken xref").endsWith("ERR /d/b.pdf  (broken xref)"))
    }
}
