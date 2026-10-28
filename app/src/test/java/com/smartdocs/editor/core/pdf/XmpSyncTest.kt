package com.smartdocs.editor.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** tab-view-core E-17: the quick properties update an existing XMP packet in place. */
class XmpSyncTest {
    private val v = XmpSync.Values("عنوان & new", "Author B", "Subj", "k1, k2", "Tool", "Prod")

    @Test fun elementsAreReplacedAndTheRestKept() {
        val x = """<x:xmpmeta><rdf:RDF><rdf:Description xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/" pdfaid:part="2">
<dc:title><rdf:Alt><rdf:li xml:lang="x-default">Old</rdf:li></rdf:Alt></dc:title>
<dc:creator><rdf:Seq><rdf:li>Old A</rdf:li></rdf:Seq></dc:creator>
<pdf:Producer>Old P</pdf:Producer></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val u = XmpSync.update(x, v)
        assertTrue(u.contains("<rdf:li xml:lang=\"x-default\">عنوان &amp; new</rdf:li>"))
        assertTrue(u.contains("<rdf:li>Author B</rdf:li>"))
        assertTrue(u.contains("<pdf:Producer>Prod</pdf:Producer>"))
        assertTrue(u.contains("pdfaid:part=\"2\""))
    }

    @Test fun attributesToo() {
        val x = """<rdf:Description pdf:Producer="Old" xmp:CreatorTool="OldTool"/>"""
        assertEquals("""<rdf:Description pdf:Producer="Prod" xmp:CreatorTool="Tool"/>""", XmpSync.update(x, v))
    }

    @Test fun missingValuesAreNotAdded() {
        val x = "<rdf:Description/>"
        assertEquals(x, XmpSync.update(x, v))
    }
}
