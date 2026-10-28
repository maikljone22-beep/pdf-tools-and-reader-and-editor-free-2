package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage E (Nitro 18 / Foxit 22): layer list, order groups, states, properties, usage, merge, delete, flatten. */
class LayersTest {

    /** 2 pages; layers A, B, C; page 1 uses A and B, page 2 uses C; /Order: A, ("Group", B, C). */
    private fun doc(): PDDocument {
        val d = PDDocument(); repeat(2) { d.addPage(PDPage().apply { resources = PDResources() }) }
        val a = Layers.create(d, "A"); val b = Layers.create(d, "B"); val c = Layers.create(d, "C")
        fun use(page: Int, key: String, g: COSDictionary) {
            val res = d.getPage(page).cosObject.getDictionaryObject(COSName.RESOURCES) as COSDictionary
            val pr = (res.getDictionaryObject(COSName.PROPERTIES) as? COSDictionary) ?: COSDictionary().also { res.setItem(COSName.PROPERTIES, it) }
            pr.setItem(key, g)
        }
        use(0, "oc1", a.cosObject); use(0, "oc2", b.cosObject); use(1, "oc3", c.cosObject)
        val dd = (d.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("OCProperties")) as COSDictionary).getDictionaryObject(COSName.D) as COSDictionary
        dd.setItem(COSName.getPDFName("Order"), COSArray().also { o -> o.add(a.cosObject); o.add(COSArray().also { g -> g.add(COSString("Group")); g.add(b.cosObject); g.add(c.cosObject) }) })
        return d
    }

    @Test fun listsWithGroupsPagesAndStates() {
        doc().use { d ->
            val rows = Layers.list(d)
            assertEquals(listOf("A", "B", "C"), rows.map { it.name })
            assertEquals(listOf(0, 1, 1), rows.map { it.depth })
            assertEquals(listOf(null, "Group", "Group"), rows.map { it.group })
            assertEquals(listOf(listOf(0), listOf(0), listOf(1)), rows.map { it.pages })
            assertTrue(rows.all { it.on })
            val initial = Layers.states(d)
            Layers.setOn(d, 1, false); Layers.showAll(d, false)
            assertTrue(Layers.list(d, initial).none { it.on })
            assertTrue(Layers.list(d, initial).all { it.initialOn })
            Layers.reset(d, initial)
            assertTrue(Layers.list(d).all { it.on })
        }
    }

    @Test fun propertiesAndUsage() {
        doc().use { d ->
            Layers.rename(d, 0, "Notes"); Layers.setLocked(d, 0, true); Layers.setIntent(d, 0, "Design")
            Layers.setUse(d, 0, "Print", LayerUse.NEVER); Layers.setUse(d, 1, "Print", LayerUse.ALWAYS); Layers.setUse(d, 2, "Export", LayerUse.NEVER)
            val r = Layers.list(d)
            assertEquals("Notes", r[0].name); assertTrue(r[0].locked); assertEquals("Design", r[0].intent)
            assertEquals(LayerUse.NEVER, r[0].print); assertEquals(LayerUse.ALWAYS, r[1].print); assertEquals(LayerUse.NEVER, r[2].export)
            Layers.setOn(d, 1, false)
            Layers.applyOverrides(d, "Print")
            assertEquals(listOf(false, true, true), Layers.list(d).map { it.on })
            // back to "when visible" removes the usage and its /AS entry
            Layers.setUse(d, 0, "Print", LayerUse.WHEN_ON); Layers.setUse(d, 1, "Print", LayerUse.WHEN_ON); Layers.setUse(d, 2, "Export", LayerUse.WHEN_ON)
            val dd = (d.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("OCProperties")) as COSDictionary).getDictionaryObject(COSName.D) as COSDictionary
            assertFalse(dd.containsKey(COSName.getPDFName("AS")))
        }
    }

    @Test fun mergeDeleteMoveFlatten() {
        doc().use { d ->
            Layers.move(d, 0, 1)
            Layers.merge(d, listOf(0), 1)                                   // A into B
            var r = Layers.list(d)
            assertEquals(listOf("B", "C"), r.map { it.name }.sorted())
            assertEquals(listOf(0), r.first { it.name == "B" }.pages)
            var removed = 0
            Layers.delete(d, r.first { it.name == "C" }.index) { removed++ }
            r = Layers.list(d)
            assertEquals(1, removed); assertEquals(listOf("B"), r.map { it.name })
            val props2 = (d.getPage(1).cosObject.getDictionaryObject(COSName.RESOURCES) as COSDictionary).getDictionaryObject(COSName.PROPERTIES) as COSDictionary
            assertTrue(props2.keySet().isEmpty())
            Layers.flatten(d) { }
            assertNull(d.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("OCProperties")))
        }
    }

    @Test fun stateBookmark() {
        doc().use { d ->
            Layers.addStateBookmark(d, "Only A", listOf(0), listOf(1, 2))
            val a = d.documentCatalog.documentOutline.firstChild.cosObject.getDictionaryObject(COSName.A) as COSDictionary
            assertEquals("SetOCGState", a.getNameAsString(COSName.S))
            val st = a.getDictionaryObject(COSName.getPDFName("State")) as COSArray
            assertEquals(COSName.getPDFName("OFF"), st.get(0)); assertEquals(5, st.size())
        }
    }
}
