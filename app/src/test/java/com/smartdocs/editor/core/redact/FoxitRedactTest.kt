package com.smartdocs.editor.core.redact

import com.smartdocs.editor.core.ai5.PiiEntities
import com.smartdocs.editor.core.ai5.PiiKind
import com.smartdocs.editor.core.search.PatternKind
import com.smartdocs.editor.core.search.RegionPatterns
import com.smartdocs.editor.core.search.SearchPatterns
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationUnknown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Foxit §29: country patterns, per-phrase overlays, Smart Redact entities, multimedia. */
class FoxitRedactTest {

    private fun kinds(text: String, k: PatternKind) = SearchPatterns.find(text, setOf(k)).map { it.value }

    @Test fun regionalPatternsWithChecksums() {
        assertEquals(listOf("123-45-6789"), kinds("SSN 123-45-6789 and 000-12-3456 or 666-12-3456", PatternKind.US_SSN))
        assertEquals(listOf("046 454 286"), kinds("SIN 046 454 286, bad 046 454 287", PatternKind.CA_SIN))
        assertEquals(listOf("12345678Z"), kinds("DNI 12345678Z / 12345678A", PatternKind.ES_DNI))
        assertTrue(RegionPatterns.dniValid("X1234567L"))
        assertEquals(listOf("529.982.247-25"), kinds("CPF 529.982.247-25 / 111.111.111-11", PatternKind.BR_CPF))
        assertTrue(RegionPatterns.cnIdValid("11010519491231002X")); assertFalse(RegionPatterns.cnIdValid("110105194912310021"))
        assertTrue(RegionPatterns.verhoeff("2363")); assertFalse(RegionPatterns.verhoeff("2364"))
        assertEquals(listOf("AB 12 34 56 C"), kinds("NI number AB 12 34 56 C", PatternKind.UK_NINO))
        assertEquals(listOf("SW1A 1AA"), kinds("London SW1A 1AA", PatternKind.UK_POSTCODE))
        assertEquals(listOf("ABCPE1234F"), kinds("PAN ABCPE1234F", PatternKind.IN_PAN))
        assertTrue(kinds("call (212) 555-0147 today", PatternKind.US_PHONE).isNotEmpty())
        assertTrue(kinds("tel 01 23 45 67 89", PatternKind.FR_PHONE).isNotEmpty())
        // regional kinds stay off unless asked for
        assertTrue(SearchPatterns.find("123-45-6789", PatternKind.values().toSet() - PatternKind.REGIONAL).none { it.kind == PatternKind.US_SSN })
    }

    @Test fun phraseListCarriesOverlays() {
        val (words, map) = PhraseList.parse("Ahmed | NAME\nsecret project\n  ACME Ltd |ORG \n|\n")
        assertEquals(listOf("Ahmed", "secret project", "ACME Ltd"), words)
        assertEquals(mapOf("Ahmed" to "NAME", "ACME Ltd" to "ORG"), map)
        assertEquals("Ahmed | NAME\nsecret project\nACME Ltd | ORG", PhraseList.format(words, map))
    }

    @Test fun smartRedactOffline() {
        val t = "حضر السيد أحمد محمد علي ممثلاً عن شركة النور للتجارة بصفته المدير العام. Dr. John Smith of Acme Holdings Inc. signed as CEO."
        val m = PiiEntities.detectOffline(t, PiiEntities.KINDS)
        val by = m.groupBy({ it.kind }, { it.value })
        assertTrue(by[PiiKind.PERSON].orEmpty().any { it.startsWith("أحمد محمد") })
        assertTrue(by[PiiKind.PERSON].orEmpty().contains("John Smith"))
        assertTrue(by[PiiKind.ORG].orEmpty().any { it.startsWith("شركة النور") })
        assertTrue(by[PiiKind.ORG].orEmpty().any { it.contains("Acme Holdings Inc") })
        assertTrue(by[PiiKind.ROLE].orEmpty().containsAll(listOf("المدير العام", "CEO")))
        assertTrue(PiiEntities.detectOffline(t, setOf(PiiKind.ROLE)).all { it.kind == PiiKind.ROLE })
    }

    @Test fun aiReplyIsParsedAndLocated() {
        val reply = "```json\n[{\"type\":\"PERSON\",\"text\":\"Sara Ali\"},{\"type\":\"org\",\"text\":\"وزارة الصحة\"},{\"type\":\"x\",\"text\":\"y\"},{\"type\":\"ROLE\",\"text\":\"Not in text\"}]\n```"
        val ents = PiiEntities.parseReply(reply)
        assertEquals(3, ents.size)
        val text = "Sara Ali wrote to وزارة الصحة; sara ali again."
        val found = PiiEntities.locate(text, ents, PiiEntities.KINDS)
        assertEquals(listOf(0, 18), found.map { it.start })        // the exact occurrence (case kept) and the ministry
        assertTrue(PiiEntities.parseReply("no json").isEmpty())
    }

    @Test fun multimediaIsFoundAndRemoved() {
        PDDocument().use { doc ->
            val p = PDPage(); doc.addPage(p)
            val screen = PDAnnotationUnknown(COSDictionary().apply { setName(COSName.SUBTYPE, "Screen") })
            val link = PDAnnotationLink().apply { cosObject.setItem(COSName.A, COSDictionary().apply { setName(COSName.S, "Rendition") }) }
            p.annotations = mutableListOf(screen, link)
            assertEquals(2, Multimedia.find(doc).size)
            val r = Sanitizer.clean(doc, setOf(SanitizeItem.MULTIMEDIA))
            assertEquals(2, r[SanitizeItem.MULTIMEDIA])
            val left = p.cosObject.getDictionaryObject(COSName.ANNOTS) as com.tom_roush.pdfbox.cos.COSArray
            assertEquals(1, left.size()); assertFalse((left.getObject(0) as COSDictionary).containsKey(COSName.A))
            assertTrue(SanitizeItem.OVERLAPPING in SanitizeItem.OPT_IN && SanitizeItem.OVERLAPPING !in SanitizeItem.DEFAULTS)
        }
    }
}
