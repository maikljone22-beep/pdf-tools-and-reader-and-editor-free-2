package com.smartdocs.editor.core.forms5

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Fill-time runtime: action chains, triggers, destinations, OCG state, document scripts, JS requests. */
class FillRuntimeTest {

    private fun n(s: String) = COSName.getPDFName(s)
    private fun action(type: String, block: COSDictionary.() -> Unit = {}) = COSDictionary().apply { setItem(COSName.S, n(type)); block() }
    private fun js(code: String) = action("JavaScript") { setItem(COSName.JS, COSString(code)) }

    private fun roundTrip(d: PDDocument): PDDocument { val o = ByteArrayOutputStream(); d.save(o); d.close(); return PDDocument.load(o.toByteArray()) }

    @Test fun nextChainIsFollowedInOrderAndCyclesStop() {
        val hide = action("Hide") { setItem(COSName.T, COSArray().apply { add(COSString("a")); add(COSString("b")) }); setItem(COSName.H, COSBoolean.FALSE) }
        val named = action("Named") { setItem(COSName.N, n("NextPage")) }
        val first = js("app.alert(1)")
        first.setItem(n("Next"), COSArray().apply { add(hide); add(named) })
        named.setItem(n("Next"), first)      // cycle back
        val list = PdfActions.chain(first, null)
        assertEquals(3, list.size)
        assertEquals(PdfAction.JavaScript("app.alert(1)"), list[0])
        assertEquals(PdfAction.Hide(listOf("a", "b"), hide = false), list[1])
        assertEquals(PdfAction.Named("NextPage"), list[2])
    }

    @Test fun submitFlagsAndResetAndImportData() {
        val sub = action("SubmitForm") {
            setItem(COSName.F, COSString("https://x.test/s")); setInt(COSName.FLAGS, FormSubmit.FLAG_XFDF or FormSubmit.FLAG_INCLUDE_ANNOTS or FormSubmit.FLAG_CANONICAL)
        }
        val s = PdfActions.parseOne(sub, null) as PdfAction.Submit
        assertEquals(SubmitFormat.XFDF, s.submit.format)
        assertTrue(s.includeAnnotations); assertTrue(s.canonicalDates)
        val r = PdfActions.parseOne(action("ResetForm") { setItem(COSName.FIELDS, COSArray().apply { add(COSString("x")) }); setInt(COSName.FLAGS, 1) }, null) as PdfAction.Reset
        assertEquals(listOf("x"), r.reset.fields); assertTrue(r.reset.exclude)
        val imp = PdfActions.parseOne(action("ImportData") { setItem(COSName.F, COSString("data.fdf")) }, null)
        assertEquals(PdfAction.ImportData("data.fdf"), imp)
    }

    @Test fun gotoResolvesExplicitAndNamedDestinations() {
        PDDocument().use { d ->
            repeat(3) { d.addPage(PDPage()) }
            val dest = COSArray().apply { add(d.getPage(2).cosObject); add(n("Fit")) }
            assertEquals(PdfAction.GoTo(2, null), PdfActions.parseOne(action("GoTo") { setItem(COSName.D, dest) }, d))
            val dests = COSDictionary().apply { setItem(n("chap2"), COSArray().apply { add(d.getPage(1).cosObject); add(n("Fit")) }) }
            d.documentCatalog.cosObject.setItem(COSName.DESTS, dests)
            assertEquals(PdfAction.GoTo(1, "chap2"), PdfActions.parseOne(action("GoTo") { setItem(COSName.D, COSString("chap2")) }, d))
            val r = PdfActions.parseOne(action("GoToR") { setItem(COSName.F, COSString("other.pdf")); setItem(COSName.D, COSArray().apply { add(COSInteger.get(4)); add(n("Fit")) }) }, d)
            assertEquals(PdfAction.GoToR("other.pdf", 4, null, false), r)
        }
    }

    @Test fun threadActionJumpsToFirstBead() {
        PDDocument().use { d ->
            repeat(3) { d.addPage(PDPage()) }
            val bead = COSDictionary().apply { setItem(COSName.P, d.getPage(2).cosObject) }
            val thread = COSDictionary().apply { setItem(COSName.F, bead); setItem(n("I"), COSDictionary().apply { setString(n("Title"), "Story") }) }
            d.documentCatalog.cosObject.setItem(n("Threads"), COSArray().apply { add(thread) })
            assertEquals(PdfAction.GoTo(2, null), PdfActions.parseOne(action("Thread") { setInt(COSName.D, 0) }, d))
            assertEquals(PdfAction.GoTo(2, null), PdfActions.parseOne(action("Thread") { setItem(COSName.D, COSString("Story")) }, d))
        }
    }

    @Test fun widgetTriggersPutMouseUpFirstAndReadFocusBlur() {
        val w = COSDictionary()
        w.setItem(COSName.A, js("a()"))
        w.setItem(COSName.AA, COSDictionary().apply { setItem(n("U"), js("u()")); setItem(n("Fo"), js("fo()")); setItem(n("Bl"), js("bl()")); setItem(n("D"), js("d()")) })
        val t = PdfActions.triggers(w, null, null)
        assertEquals(listOf(PdfAction.JavaScript("a()"), PdfAction.JavaScript("u()")), t[Trigger.MOUSE_UP])
        assertEquals(listOf(PdfAction.JavaScript("fo()")), t[Trigger.FOCUS])
        assertEquals(listOf(PdfAction.JavaScript("bl()")), t[Trigger.BLUR])
        assertEquals(listOf(PdfAction.JavaScript("d()")), t[Trigger.MOUSE_DOWN])
        assertFalse(Trigger.ENTER in t)
        assertTrue(PdfActions.needsConfirm(listOf(PdfAction.Uri("https://a"))))
        assertFalse(PdfActions.needsConfirm(listOf(PdfAction.JavaScript("x"), PdfAction.GoTo(1))))
    }

    private fun ocg(name: String) = COSDictionary().apply { setItem(COSName.TYPE, n("OCG")); setString(COSName.NAME, name) }

    @Test fun setOcgStateChangesDefaultConfigAndSurvivesSave() {
        var d = PDDocument().apply { addPage(PDPage()) }
        val a = ocg("A"); val b = ocg("B"); val c = ocg("C")
        val cfg = COSDictionary().apply {
            setItem(n("OFF"), COSArray().apply { add(c) })
            setItem(n("RBGroups"), COSArray().apply { add(COSArray().apply { add(a); add(b) }) })
        }
        d.documentCatalog.cosObject.setItem(n("OCProperties"), COSDictionary().apply {
            setItem(n("OCGs"), COSArray().apply { add(a); add(b); add(c) }); setItem(COSName.D, cfg)
        })
        assertEquals(setOf("C"), OcgState.hiddenNames(d))
        assertTrue(OcgState.apply(d, listOf("OFF" to listOf("A"), "Toggle" to listOf("C"))))
        d = roundTrip(d)
        assertEquals(setOf("A"), OcgState.hiddenNames(d))
        // radio group: B on turns A off (already off) and with preserveRB=false, A on turns B off
        assertTrue(OcgState.apply(d, listOf("ON" to listOf("A")), preserveRB = false))
        assertEquals(setOf("B"), OcgState.hiddenNames(d))
        assertFalse(OcgState.apply(d, listOf("ON" to listOf("A"))))
        d.close()
    }

    @Test fun docScriptsAddEditRemoveEverywhere() {
        var d = PDDocument().apply { addPage(PDPage()); addPage(PDPage()) }
        val form = PDAcroForm(d); d.documentCatalog.acroForm = form
        val tf = PDTextField(form); tf.partialName = "total"
        val w = PDAnnotationWidget(); w.rectangle = PDRectangle(10f, 10f, 100f, 20f); w.page = d.getPage(0)
        tf.widgets = listOf(w); form.fields.add(tf); d.getPage(0).annotations.add(w)
        DocScripts.putNamed(d, "init", "var x = 1;")
        DocScripts.putNamed(d, "a", "var a = 2;")
        assertTrue(DocScripts.setCode(d, "doc:WS", "app.alert('save');"))
        assertTrue(DocScripts.setCode(d, "page:1:O", "console.println('open');"))
        assertTrue(DocScripts.setCode(d, "field:total:C", "AFSimple_Calculate(\"SUM\", [\"a\"]);"))
        assertTrue(DocScripts.setCode(d, "widget:0:0:Fo", "event.target.fillColor = color.red;"))
        assertFalse(DocScripts.setCode(d, "page:9:O", "x"))
        d = roundTrip(d)
        val all = DocScripts.listAll(d).associate { it.locator to it.code }
        assertEquals("var a = 2;", all["named:a"])
        assertEquals("var x = 1;", all["named:init"])
        assertEquals(listOf("a", "init"), DocScripts.named(d).map { it.first })        // sorted name tree
        assertEquals("app.alert('save');", all["doc:WS"])
        assertEquals("console.println('open');", all["page:1:O"])
        assertEquals("AFSimple_Calculate(\"SUM\", [\"a\"]);", all["field:total:C"])
        assertEquals("event.target.fillColor = color.red;", all["widget:0:0:Fo"])
        assertEquals(mapOf("WS" to "app.alert('save');"), FormModelLoader.docActions(d))
        DocScripts.removeNamed(d, "init")
        assertTrue(DocScripts.setCode(d, "doc:WS", ""))
        d = roundTrip(d)
        assertEquals(listOf("a"), DocScripts.named(d).map { it.first })
        assertTrue(FormModelLoader.docActions(d).isEmpty())
        d.close()
    }

    @Test fun fileSelectFlagAndButtonTriggersReachTheModel() {
        PDDocument().use { d ->
            d.addPage(PDPage(PDRectangle(200f, 200f)))
            val form = PDAcroForm(d); d.documentCatalog.acroForm = form
            val tf = PDTextField(form); tf.partialName = "upload"
            tf.cosObject.setInt(COSName.FF, FormModelLoader.FF_FILE_SELECT)
            val w = PDAnnotationWidget(); w.rectangle = PDRectangle(10f, 10f, 100f, 20f); w.page = d.getPage(0)
            tf.widgets = listOf(w); form.fields.add(tf); d.getPage(0).annotations.add(w)
            val pb = PDPushButton(form); pb.partialName = "go"
            val bw = PDAnnotationWidget(); bw.rectangle = PDRectangle(10f, 50f, 60f, 20f); bw.page = d.getPage(0)
            bw.cosObject.setItem(COSName.A, js("this.pageNum = 0;").apply { setItem(n("Next"), action("Named") { setItem(COSName.N, n("LastPage")) }) })
            pb.widgets = listOf(bw); form.fields.add(pb); d.getPage(0).annotations.add(bw)
            val m = FormModelLoader.load(d)
            assertTrue(m.widgets.first { it.name == "upload" }.fileSelect)
            val go = m.widgets.first { it.name == "go" }
            assertEquals(listOf(PdfAction.JavaScript("this.pageNum = 0;"), PdfAction.Named("LastPage")), go.mouseUp)
            assertTrue(m.js.hasScripts)        // action JavaScript starts the runtime
        }
    }

    @Test fun scriptRequestsAreReported() {
        val s = FormScripts(FormJs(listOf(JsField("a", "text", "1")), emptyMap(), hasActionJs = true, numPages = 3,
            pageWords = listOf(listOf("Hello,", "world"))), JsLimits(eventMillis = 2000, initMillis = 4000))
        val r = s.action("a", "Mouse Up", """
            this.submitForm({cURL: "https://x.test/s", cSubmitAs: "XFDF", bEmpty: true});
            this.pageNum = 2; app.launchURL("https://y.test"); this.print();
            this.getField("a").value = this.getPageNthWord(0, 0) + "|" + this.getPageNumWords(0);
            console.println("hi"); this.saveAs("/x.pdf");
        """.trimIndent(), mapOf("a" to "1"), page = 0)!!
        assertTrue(r.ok)
        val kinds = r.requests.map { it.kind }
        assertEquals(listOf("submit", "page", "url", "print", "unsupported"), kinds)
        assertEquals("https://x.test/s", r.requests[0]["u"]); assertEquals("XFDF", r.requests[0]["f"]); assertEquals("1", r.requests[0]["e"])
        assertEquals("2", r.requests[1]["p"])
        assertEquals("Hello|2", r.setValues["a"])
        assertEquals(listOf("hi"), s.drainConsole())
        assertTrue(s.drainConsole().isEmpty())
    }

    @Test fun canonicalDatesUsePdfDateFormat() {
        val out = FormSubmit.canonicalDates(mapOf("d" to "25/12/2024", "n" to "x", "e" to ""), mapOf("d" to "dd/mm/yyyy", "e" to "dd/mm/yyyy"))
        assertEquals("D:20241225000000", out["d"]); assertEquals("x", out["n"]); assertEquals("", out["e"])
        val fdf = String(FormSubmit.fdfWithAnnots(mapOf("a" to "1"), "f.pdf", listOf(FormSubmit.fdfAnnot("Text", 0, floatArrayOf(1f, 2f, 3f, 4f), "note", "me", null))), Charsets.ISO_8859_1)
        assertTrue(fdf.contains("/Annots [")); assertTrue(fdf.contains("/Subtype /Text /Page 0 /Rect [1.00 2.00 3.00 4.00] /Contents (note) /T (me)"))
        // the FDF with comments still parses
        com.tom_roush.pdfbox.pdmodel.fdf.FDFDocument.load(java.io.ByteArrayInputStream(fdf.toByteArray(Charsets.ISO_8859_1))).use { f ->
            assertEquals(1, f.catalog.fdf.fields.size); assertEquals(1, f.catalog.fdf.annotations.size)
        }
    }

    @Test fun autocompleteEntriesCanBeEditedAndDeleted() {
        val st = AutocompleteStore()
        st.record("FirstName", "Ali"); st.record("FirstName", "Omar"); st.record("City", "Sanaa")
        assertEquals(setOf("firstname", "city"), st.entries().keys)
        st.editEntry("firstname", "Ali", "Alia")
        assertEquals(listOf("Alia", "Omar").toSet(), st.entries()["firstname"]!!.map { it.value }.toSet())
        assertEquals(listOf("Alia"), st.suggestions("FirstName", "Al"))
        st.removeEntry("city", "Sanaa")
        assertFalse("city" in st.entries())
        assertTrue(st.suggestions("other", "San").isEmpty())      // gone from the global pool too
    }
}
