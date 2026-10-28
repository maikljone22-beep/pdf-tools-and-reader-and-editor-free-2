package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.formsx.FieldSpec
import com.smartdocs.editor.core.formsx.FxEditor
import com.smartdocs.editor.core.formsx.FxRect
import com.smartdocs.editor.core.formsx.FxType
import com.smartdocs.editor.core.formsx.FxWidget
import com.smartdocs.editor.core.pdf.SignedGuard
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** fix/js-actions: doc.scroll x, setAction, buttonSetIcon, signatureSign, removeField, signed routing, named actions. */
class JsActionsTest {

    private fun runtime(vararg fields: JsField) =
        FormScripts(FormJs(fields.toList(), emptyMap(), hasActionJs = true), JsLimits(eventMillis = 2000, initMillis = 4000))

    @Test fun namedActionsOfTheReader() {
        assertEquals(NamedOp.GO_BACK, NamedActions.resolve("GoBack"))
        assertEquals(NamedOp.GO_FORWARD, NamedActions.resolve("goforward"))
        assertEquals(NamedOp.CLOSE, NamedActions.resolve("Close"))
        assertEquals(NamedOp.QUIT, NamedActions.resolve("Quit"))
        assertEquals(NamedOp.SAVE_AS, NamedActions.resolve("SaveAs"))
        assertEquals(NamedOp.UNSUPPORTED, NamedActions.resolve("GeneralPrefs"))
    }

    @Test fun scrollXBecomesAPan() {
        // page 600 pt shown 2000 px wide in a 1000 px view: x = 0 -> page left edge at the view's left edge
        assertEquals(500f, NamedActions.scrollPanX(0f, 600f, 2000f, 1000f), 0.01f)
        assertEquals(-500f, NamedActions.scrollPanX(300f, 600f, 2000f, 1000f), 0.01f)
        assertEquals(0f, NamedActions.scrollPanX(300f, 600f, 800f, 1000f), 0f)       // fits: nothing to pan
    }

    @Test fun scriptRequestsOfFieldMethods() {
        val s = runtime(JsField("b1", "button"), JsField("b2", "button"), JsField("sig", "signature"), JsField("t", "text", "x"))
        val r = s.action("b1", "Mouse Up", """
            var f = this.getField("t");
            f.setAction("MouseUp", "app.alert(1)");
            this.getField("b2").buttonSetIcon(this.getField("b1").buttonGetIcon());
            this.getField("b2").buttonSetIcon(this.getIcon("logo"));
            this.getField("sig").signatureSign();
            this.scroll(72, 500);
            app.goBack();
            this.removeField("t");
            this.getField("b1").value = (this.getField("t") === null) + "|" + this.numFields;
        """.trimIndent(), mapOf("t" to "x"), page = 0)!!
        assertTrue(r.error, r.ok)
        val q = r.requests
        assertEquals(listOf("setaction", "seticon", "seticon", "sign", "scroll", "menu", "remove"), q.map { it.kind })
        assertEquals("MouseUp", q[0]["t"]); assertEquals("app.alert(1)", q[0]["s"]); assertEquals("t", q[0]["n"])
        assertEquals("b1", q[1]["f"]); assertEquals("b2", q[1]["n"])
        assertEquals("logo", q[2]["nm"])
        assertEquals("sig", q[3]["n"])
        assertEquals("72", q[4]["x"]); assertEquals("500", q[4]["y"])
        assertEquals("GoBack", q[5]["n"])
        assertEquals("true|3", r.setValues["b1"])      // removed for the scripts at once
    }

    @Test fun setFieldScriptJoinsTheRuntime() {
        val s = runtime(JsField("a", "text", "2"), JsField("b", "text", ""))
        assertFalse(s.hasCalculate("b"))
        s.setFieldScript("b", "C", "event.value = this.getField(\"a\").value * 10;")
        assertTrue(s.hasCalculate("b"))
        assertTrue("b" in s.js.calcOrder)
        s.setFieldScript("b", "C", "")
        assertFalse(s.hasCalculate("b"))
        s.setFieldScript("b", "F", "event.value = 'x';")
        assertTrue(s.hasFormat("b"))
    }

    @Test fun triggerNames() {
        assertEquals("U", ScriptDocChanges.triggerKey("MouseUp"))
        assertEquals("Fo", ScriptDocChanges.triggerKey("OnFocus"))
        assertEquals("C", ScriptDocChanges.triggerKey("Calculate"))
        assertEquals("K", ScriptDocChanges.triggerKey("Keystroke"))
        assertNull(ScriptDocChanges.triggerKey("PageOpen"))
    }

    @Test fun splitFollowsTheSignedGate() {
        val ch = ScriptDocChanges(scripts = mapOf("s" to "1"), locks = mapOf("sig" to ScriptDocChanges.lockOf("All", emptyList()), "signed" to ScriptDocChanges.lockOf("All", emptyList())),
            fields = listOf(ScriptDocChanges.NewField("n", FxType.SIGNATURE, 0, FxRect(0f, 0f, 10f, 10f))), removed = listOf("t", "signed"),
            actions = mapOf("t" to mapOf("U" to "x")), icons = mapOf("b" to ScriptDocChanges.IconSrc("a")))
        val info = SignedGuard.Info(signed = true, lockP = null)
        val (ok, rest) = ch.split(setOf("signed")) { c -> SignedGuard.decide(info, c, true) == SignedGuard.Decision.SAVE_INCREMENTAL }
        // signed, not certified: annotation-level and form-fill changes are appended, script / action changes are not
        assertEquals(setOf("sig"), ok.locks.keys); assertEquals(1, ok.fields.size); assertEquals(listOf("t"), ok.removed); assertEquals(setOf("b"), ok.icons.keys)
        assertTrue(ok.scripts.isEmpty() && ok.actions.isEmpty())
        assertEquals(setOf("s"), rest.scripts.keys); assertEquals(setOf("t"), rest.actions.keys); assertEquals(listOf("signed"), rest.removed); assertEquals(setOf("signed"), rest.locks.keys)
        // certified "form filling only" (P=2): new / removed fields are refused too
        val cert = SignedGuard.Info(signed = true, lockP = 2)
        val (ok2, _) = ch.split(emptySet()) { c -> SignedGuard.decide(cert, c, true) == SignedGuard.Decision.SAVE_INCREMENTAL }
        assertTrue(ok2.fields.isEmpty() && ok2.removed.isEmpty() && ok2.icons.isEmpty()); assertEquals(2, ok2.locks.size)
    }

    private fun form(): PDDocument = PDDocument().also { d ->
        d.addPage(PDPage())
        // signature fields: no standard-font metrics needed on the JVM
        FxEditor.create(d, FieldSpec("keep", FxType.SIGNATURE, listOf(FxWidget(0, FxRect(50f, 50f, 200f, 90f)))))
        FxEditor.create(d, FieldSpec("grp.gone", FxType.SIGNATURE, listOf(FxWidget(0, FxRect(50f, 150f, 200f, 190f)))))
    }

    private fun annotCount(d: PDDocument) = (d.getPage(0).cosObject.getDictionaryObject(COSName.ANNOTS) as COSArray).size()

    @Test fun removeFieldAndSetActionAreWritten() {
        form().use { d ->
            assertEquals(2, annotCount(d))
            val ch = ScriptDocChanges(removed = listOf("grp"), actions = mapOf("keep" to mapOf("U" to "app.alert(2)", "Fo" to "x()", "C" to "event.value = 1")))
            assertEquals(4, ch.apply(d))
            val acro = d.documentCatalog.getAcroForm(null)
            assertNull(acro.getField("grp.gone")); assertNull(acro.getField("grp"))
            assertEquals(1, annotCount(d))
            val field = acro.getField("keep") as com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
            val keep = field.cosObject
            val widget = field.widgets.first().cosObject
            assertEquals("app.alert(2)", (widget.getDictionaryObject(COSName.A) as COSDictionary).getString(COSName.JS))
            assertNotNull((widget.getDictionaryObject(COSName.AA) as COSDictionary).getDictionaryObject(COSName.getPDFName("Fo")))
            assertNotNull((keep.getDictionaryObject(COSName.AA) as COSDictionary).getDictionaryObject(COSName.getPDFName("C")))
            val co = acro.cosObject.getDictionaryObject(COSName.getPDFName("CO")) as COSArray
            assertSame(keep, co.getObject(0))
        }
    }

    @Test fun appendKeepsTheOriginalBytes() {
        val dir = File(System.getProperty("java.io.tmpdir"), "jsa_test_" + System.nanoTime()).apply { mkdirs() }
        try {
            val src = File(dir, "a.pdf")
            form().use { it.save(src) }
            val out = File(dir, "b.pdf")
            val n = PDDocument.load(src).use { d -> ScriptDocChanges(removed = listOf("grp.gone"), fields = listOf(ScriptDocChanges.NewField("added", FxType.SIGNATURE, 0, FxRect(300f, 300f, 400f, 340f)))).appendInto(d, out) }
            assertEquals(2, n)
            assertTrue(SignedGuard.isIncrementalOf(src, out))
            PDDocument.load(out).use { d ->
                val acro = d.documentCatalog.getAcroForm(null)
                assertNull(acro.getField("grp.gone")); assertNotNull(acro.getField("added")); assertNotNull(acro.getField("keep"))
                assertEquals(2, annotCount(d))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun iconsFromNamesAndPictures() {
        PDDocument().use { d ->
            val img = COSStream().apply { setItem(COSName.SUBTYPE, COSName.IMAGE) }
            val inner = COSStream().apply { setItem(COSName.SUBTYPE, COSName.FORM); setItem(COSName.RESOURCES, COSDictionary().apply { setItem(COSName.XOBJECT, COSDictionary().apply { setItem(COSName.getPDFName("Im0"), img) }) }) }
            val icon = COSStream().apply { setItem(COSName.SUBTYPE, COSName.FORM); setItem(COSName.RESOURCES, COSDictionary().apply { setItem(COSName.XOBJECT, COSDictionary().apply { setItem(COSName.getPDFName("Fm0"), inner) }) }) }
            assertSame(img, ScriptIcons.firstImage(icon))
            val leaf = COSDictionary().apply { setItem(COSName.NAMES, COSArray().apply { add(COSString("logo")); add(icon) }) }
            val tree = COSDictionary().apply { setItem(COSName.KIDS, COSArray().apply { add(leaf) }) }
            d.documentCatalog.cosObject.setItem(COSName.NAMES, COSDictionary().apply { setItem(COSName.getPDFName("AP"), tree) })
            assertSame(icon, ScriptIcons.find(d, ScriptDocChanges.IconSrc(named = "logo")))
            assertNull(ScriptIcons.find(d, ScriptDocChanges.IconSrc(named = "other")))
            assertNull(ScriptIcons.firstImage(COSStream()))
        }
    }
}
