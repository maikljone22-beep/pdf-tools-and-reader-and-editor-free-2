package com.smartdocs.editor.core.security

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionInspectorTest {
    private fun action(s: String, vararg kv: Pair<String, Any>): COSDictionary = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.getPDFName("Action")); setItem(COSName.S, COSName.getPDFName(s))
        kv.forEach { (k, v) -> when (v) { is String -> setString(k, v); is COSDictionary -> setItem(k, v); else -> {} } }
    }

    private fun link(a: COSDictionary, widget: Boolean = false) = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.ANNOT); setItem(COSName.SUBTYPE, COSName.getPDFName(if (widget) "Widget" else "Link"))
        setItem(COSName.A, a)
    }

    @Test fun findsScriptsAndActionsEverywhere() {
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            val cat = doc.documentCatalog.cosObject
            // open action: a script followed (/Next) by a web address
            cat.setItem(COSName.OPEN_ACTION, action("JavaScript", "JS" to "app.alert(1)", "Next" to action("URI", "URI" to "https://x.org")))
            // document-level scripts: a name tree with two entries in a kid
            val kid = COSDictionary().apply {
                setItem(COSName.NAMES, COSArray().apply { add(COSString("a")); add(action("JavaScript", "JS" to "1")); add(COSString("b")); add(action("JavaScript", "JS" to "2")) })
            }
            cat.setItem(COSName.NAMES, COSDictionary().apply { setItem("JavaScript", COSDictionary().apply { setItem(COSName.KIDS, COSArray().apply { add(kid) }) }) })
            // document event (will close) and page open event
            cat.setItem(COSName.AA, COSDictionary().apply { setItem("WC", action("SubmitForm", "F" to "https://collect.example")) })
            page.cosObject.setItem(COSName.AA, COSDictionary().apply { setItem("O", action("Launch", "F" to "run.exe")) })
            // a link, a widget button and a harmless internal jump
            val shared = action("URI", "URI" to "https://y.org")
            page.cosObject.setItem(COSName.ANNOTS, COSArray().apply {
                add(link(shared)); add(link(shared))                       // the same action twice counts once
                add(link(action("ImportData", "F" to "data.fdf"), widget = true))
                add(link(action("GoTo").apply { setItem(COSName.D, COSArray().apply { add(page.cosObject); add(COSName.getPDFName("Fit")) }) }))
            })
            val f = ActionInspector.scan(doc)
            assertEquals(3, f.count(ActionKind.JAVASCRIPT))
            assertEquals(2, f.count(ActionKind.URI))
            assertEquals(1, f.count(ActionKind.LAUNCH))
            assertEquals(1, f.count(ActionKind.SUBMIT_FORM))
            assertEquals(1, f.count(ActionKind.IMPORT_DATA))
            assertEquals(2, f.count(ActionPlace.OPEN_ACTION))
            assertEquals(2, f.count(ActionPlace.DOCUMENT_SCRIPTS))
            assertEquals(1, f.count(ActionPlace.DOCUMENT_EVENTS))
            assertEquals(1, f.count(ActionPlace.PAGE_EVENTS))
            assertEquals(1, f.count(ActionPlace.LINKS))
            assertEquals(1, f.count(ActionPlace.FIELDS))
            assertEquals(8, f.total)
            assertTrue(f.hasJavaScript); assertTrue(f.hasOpenAction); assertTrue(f.needsBanner)
        }
    }

    @Test fun plainLinksAndJumpsNeedNoBanner() {
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            doc.documentCatalog.cosObject.setItem(COSName.OPEN_ACTION, COSArray().apply { add(page.cosObject); add(COSName.getPDFName("XYZ")); add(COSInteger.ZERO) })
            page.cosObject.setItem(COSName.ANNOTS, COSArray().apply { add(link(action("URI", "URI" to "https://a.org"))) })
            val f = ActionInspector.scan(doc)
            assertEquals(1, f.total)
            assertFalse(f.needsBanner); assertFalse(f.hasOpenAction)
        }
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val f = ActionInspector.scan(doc)
            assertEquals(0, f.total); assertFalse(f.needsBanner)
        }
    }

    @Test fun cyclicNextChainsTerminate() {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val a = action("JavaScript", "JS" to "1"); val b = action("Launch", "F" to "x.pdf")
            a.setItem("Next", b); b.setItem("Next", a)
            doc.documentCatalog.cosObject.setItem(COSName.OPEN_ACTION, a)
            val f = ActionInspector.scan(doc)
            assertEquals(2, f.total)
        }
    }
}
