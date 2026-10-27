package com.smartdocs.editor.core.formsx

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Form designer writers: create / update / geometry, saved with PdfBox and read back. */
class FxEditorTest {

    private fun doc(pages: Int = 2) = PDDocument().apply { repeat(pages) { addPage(PDPage()) } }

    private fun reload(d: PDDocument): PDDocument {
        val bos = ByteArrayOutputStream(); d.save(bos); d.close()
        return PDDocument.load(bos.toByteArray())
    }

    private fun text(name: String, r: FxRect = FxRect(50f, 600f, 250f, 620f), page: Int = 0) = FieldSpec(name = name, type = FxType.TEXT, widgets = listOf(FxWidget(page, r)))

    @Test fun textFieldPropertiesRoundTrip() {
        val d = doc()
        val spec = text("amount").copy(
            tooltip = "Amount due", visibility = FxVisibility.VISIBLE_NO_PRINT, readOnly = true, required = true, rotation = 90,
            font = FxFont.COURIER, fontSize = 11f, textColor = 0xFF0000, borderColor = 0x0000FF, fillColor = 0xEEEEEE, borderWidth = 2f,
            borderStyle = FxBorderStyle.DASHED, align = 2, comb = true, maxLen = 8, doNotScroll = true, doNotSpellCheck = true,
            format = FxFormat.Number(2, 1, 2, "$", true), validate = FxValidate.Range(0.0, 1000.0),
            calc = FxCalc.Simple(FxCalcOp.SUM, listOf("a", "b")),
            actions = mapOf(FxTrigger.FOCUS to listOf(FxAction.JavaScript("app.alert(1);")), FxTrigger.BLUR to listOf(FxAction.Named("NextPage"))),
        )
        val name = FxEditor.create(d, spec)
        assertEquals("amount", name)
        val back = reload(d).use { r -> FxEditor.readAll(r).single() }
        assertEquals(FxType.TEXT, back.type)
        assertEquals("Amount due", back.tooltip)
        assertEquals(FxVisibility.VISIBLE_NO_PRINT, back.visibility)
        assertTrue(back.readOnly); assertTrue(back.required)
        assertEquals(90, back.rotation)
        assertEquals(FxFont.COURIER, back.font); assertEquals(11f, back.fontSize, 0.01f); assertEquals(0xFF0000, back.textColor)
        assertEquals(0x0000FF, back.borderColor); assertEquals(0xEEEEEE, back.fillColor); assertEquals(2f, back.borderWidth, 0.01f)
        assertEquals(FxBorderStyle.DASHED, back.borderStyle)
        assertEquals(2, back.align); assertTrue(back.comb); assertEquals(8, back.maxLen); assertTrue(back.doNotScroll); assertTrue(back.doNotSpellCheck)
        assertEquals(FxFormat.Number(2, 1, 2, "$", true), back.format)
        assertEquals(FxValidate.Range(0.0, 1000.0), back.validate)
        assertEquals(FxCalc.Simple(FxCalcOp.SUM, listOf("a", "b")), back.calc)
        assertEquals(listOf(FxAction.JavaScript("app.alert(1);")), back.actions[FxTrigger.FOCUS])
        assertEquals(listOf(FxAction.Named("NextPage")), back.actions[FxTrigger.BLUR])
    }

    @Test fun calcOrderFollowsCalculatedFields() {
        val d = doc()
        FxEditor.create(d, text("a"))
        FxEditor.create(d, text("total", FxRect(50f, 500f, 250f, 520f)).copy(calc = FxCalc.Sfn("a * 2")))
        FxEditor.create(d, text("tax", FxRect(50f, 400f, 250f, 420f)).copy(calc = FxCalc.Custom("event.value = 1;")))
        assertEquals(listOf("total", "tax"), FxEditor.calcOrder(d))
        FxEditor.setCalcOrder(d, listOf("tax", "total"))
        reload(d).use { r ->
            assertEquals(listOf("tax", "total"), FxEditor.calcOrder(r))
            val total = FxEditor.readAll(r).first { it.name == "total" }
            assertEquals(FxCalc.Sfn("a * 2"), total.calc)
        }
    }

    @Test fun checkBoxStyleExportAndDefault() {
        val d = doc()
        FxEditor.create(d, FieldSpec("agree", FxType.CHECKBOX, listOf(FxWidget(0, FxRect(50f, 50f, 64f, 64f))),
            checkStyle = FxCheckStyle.STAR, exportValue = "Accepted", checkedByDefault = true))
        reload(d).use { r ->
            val s = FxEditor.readAll(r).single()
            assertEquals(FxCheckStyle.STAR, s.checkStyle); assertEquals("Accepted", s.exportValue); assertTrue(s.checkedByDefault)
            val f = r.documentCatalog.getAcroForm(null).getField("agree") as PDCheckBox
            assertEquals("Accepted", f.cosObject.getCOSName(COSName.V).name)
            assertEquals("Accepted", f.widgets[0].cosObject.getCOSName(COSName.AS).name)
            val n = (f.widgets[0].cosObject.getDictionaryObject(COSName.AP) as COSDictionary).getDictionaryObject(COSName.N) as COSDictionary
            assertNotNull(n.getDictionaryObject(COSName.getPDFName("Accepted"))); assertNotNull(n.getDictionaryObject(COSName.Off))
        }
    }

    @Test fun radioGroupUnisonAndDefault() {
        val d = doc()
        FxEditor.create(d, FieldSpec("size", FxType.RADIO, listOf(
            FxWidget(0, FxRect(50f, 50f, 62f, 62f), "S"), FxWidget(0, FxRect(80f, 50f, 92f, 62f), "M"), FxWidget(0, FxRect(110f, 50f, 122f, 62f), "L")),
            checkStyle = FxCheckStyle.CIRCLE, radiosInUnison = true, defaultValue = "M"))
        reload(d).use { r ->
            val s = FxEditor.readAll(r).single()
            assertEquals(listOf("S", "M", "L"), s.widgets.map { it.export })
            assertTrue(s.radiosInUnison)
            assertEquals(FxCheckStyle.CIRCLE, s.checkStyle)
            assertEquals("M", r.documentCatalog.getAcroForm(null).getField("size").cosObject.getCOSName(COSName.V).name)
        }
    }

    @Test fun choiceItemsExportValuesAndFlags() {
        val d = doc()
        FxEditor.create(d, FieldSpec("country", FxType.COMBO, listOf(FxWidget(0, FxRect(50f, 300f, 200f, 320f))),
            items = listOf("Yemen" to "YE", "Egypt" to "EG", "Oman" to "OM"), sortItems = true, editable = true, commitOnSelChange = true,
            selectionChangeJs = "console.println(event.change);", defaultValue = "EG"))
        FxEditor.create(d, FieldSpec("langs", FxType.LIST, listOf(FxWidget(0, FxRect(50f, 200f, 200f, 280f))),
            items = listOf("Arabic" to "ar", "English" to "en"), multiSelect = true))
        reload(d).use { r ->
            val all = FxEditor.readAll(r).associateBy { it.name }
            val c = all.getValue("country")
            assertEquals(listOf("Egypt" to "EG", "Oman" to "OM", "Yemen" to "YE"), c.items)
            assertTrue(c.sortItems); assertTrue(c.editable); assertTrue(c.commitOnSelChange)
            assertEquals("console.println(event.change);", c.selectionChangeJs)
            assertEquals("EG", c.defaultValue)
            assertTrue(all.getValue("langs").multiSelect)
        }
    }

    @Test fun pushButtonLayoutHighlightAndActionChain() {
        val d = doc(3)
        val actions = listOf(
            FxAction.Submit("https://example.com/s", FxSubmitFormat.XFDF, listOf("a", "b"), exclude = true, includeEmpty = true, canonicalDates = true),
            FxAction.Reset(listOf("c"), exclude = false),
            FxAction.ShowHide(listOf("d", "e"), hide = true),
            FxAction.GoToPage(2, 150f),
            FxAction.GoToNamed("chapter1"),
            FxAction.GoToRemote("other.pdf", 3),
            FxAction.OpenUrl("https://example.org"),
            FxAction.ImportData("data.fdf"),
            FxAction.JavaScript("this.print();"),
        )
        FxEditor.create(d, FieldSpec("go", FxType.BUTTON, listOf(FxWidget(0, FxRect(50f, 50f, 150f, 80f))),
            caption = "Send", rolloverCaption = "Send now", downCaption = "Sending", layout = FxButtonLayout.LABEL_ABOVE_ICON,
            highlight = FxHighlight.PUSH, iconScale = FxIconScale.BIGGER, iconProportional = false,
            actions = mapOf(FxTrigger.MOUSE_UP to actions, FxTrigger.MOUSE_DOWN to listOf(FxAction.Named("Print")))))
        reload(d).use { r ->
            val s = FxEditor.readAll(r).single()
            assertEquals("Send", s.caption); assertEquals("Send now", s.rolloverCaption); assertEquals("Sending", s.downCaption)
            assertEquals(FxButtonLayout.LABEL_ABOVE_ICON, s.layout); assertEquals(FxHighlight.PUSH, s.highlight)
            assertEquals(FxIconScale.BIGGER, s.iconScale); assertFalse(s.iconProportional)
            assertEquals(actions, s.actions[FxTrigger.MOUSE_UP])
            assertEquals(listOf(FxAction.Named("Print")), s.actions[FxTrigger.MOUSE_DOWN])
            // Mouse Up lives in /A with a /Next chain
            val w = r.documentCatalog.getAcroForm(null).getField("go").widgets[0].cosObject
            val a = w.getDictionaryObject(COSName.A) as COSDictionary
            assertEquals("SubmitForm", a.getCOSName(COSName.S).name)
            assertEquals(FxActions.SUBMIT_EXCLUDE or FxActions.SUBMIT_INCLUDE_NO_VALUE or FxActions.SUBMIT_XFDF or FxActions.SUBMIT_CANONICAL, a.getInt(COSName.getPDFName("Flags")))
            assertNotNull(a.getDictionaryObject(COSName.getPDFName("Next")))
            val ap = w.getDictionaryObject(COSName.AP) as COSDictionary
            assertNotNull(ap.getDictionaryObject(COSName.N)); assertNotNull(ap.getDictionaryObject(COSName.D)); assertNotNull(ap.getDictionaryObject(COSName.R))
        }
    }

    @Test fun updateRenamesMovesAndChangesFlags() {
        val d = doc()
        FxEditor.create(d, text("first"))
        val s = FxEditor.readAll(d).single()
        val newName = FxEditor.update(d, "first", s.copy(name = "last", readOnly = true, visibility = FxVisibility.HIDDEN, locked = true,
            widgets = listOf(FxWidget(0, FxRect(10f, 10f, 110f, 30f)))))
        assertEquals("last", newName)
        reload(d).use { r ->
            val b = FxEditor.readAll(r).single()
            assertEquals("last", b.name); assertTrue(b.readOnly); assertEquals(FxVisibility.HIDDEN, b.visibility); assertTrue(b.locked)
            assertEquals(FxRect(10f, 10f, 110f, 30f), b.rect)
            // a locked widget refuses to move unless forced
            assertFalse(FxEditor.setRect(r, FxRef("last"), FxRect(0f, 0f, 50f, 20f)))
            assertTrue(FxEditor.setRect(r, FxRef("last"), FxRect(0f, 0f, 50f, 20f), force = true))
        }
    }

    @Test fun typeChangeReplacesTheField() {
        val d = doc()
        FxEditor.create(d, text("x"))
        val s = FxEditor.readAll(d).single()
        FxEditor.update(d, "x", s.copy(type = FxType.CHECKBOX))
        assertEquals(FxType.CHECKBOX, FxEditor.readAll(d).single().type)
        assertEquals(1, d.getPage(0).annotations.size)
    }

    @Test fun deleteRemovesFieldAndWidgets() {
        val d = doc()
        FxEditor.create(d, text("a")); FxEditor.create(d, text("b", FxRect(50f, 500f, 250f, 520f)))
        assertEquals(1, FxEditor.delete(d, listOf("a")))
        reload(d).use { r ->
            assertEquals(listOf("b"), FxEditor.readAll(r).map { it.name })
            assertEquals(1, r.getPage(0).annotations.size)
        }
    }

    @Test fun duplicateAcrossPagesSharesTheField() {
        val d = doc(3)
        // a merged field/widget (as many PDFs have) is split before widgets are added
        val form = FxEditor.acroForm(d)
        val tf = PDTextField(form); tf.partialName = "merged"
        val w = tf.widgets[0]; w.rectangle = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(10f, 10f, 100f, 20f); w.page = d.getPage(0)
        d.getPage(0).annotations.add(w); (form.cosObject.getDictionaryObject(COSName.FIELDS) as COSArray).add(tf.cosObject)
        assertEquals(2, FxEditor.duplicateToPages(d, FxRef("merged"), listOf(0, 1, 2)))
        reload(d).use { r ->
            val s = FxEditor.read(r, r.documentCatalog.getAcroForm(null).getField("merged") as com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField)
            assertEquals(listOf(0, 1, 2), s.widgets.map { it.page })
            assertTrue(s.widgets.all { it.rect == FxRect(10f, 10f, 110f, 30f) })
            val f = r.documentCatalog.getAcroForm(null).getField("merged")
            assertNotNull(f.cosObject.getDictionaryObject(COSName.KIDS))
            assertNull(f.cosObject.getDictionaryObject(COSName.SUBTYPE))
        }
    }

    @Test fun makeCopiesGrid() {
        val d = doc()
        FxEditor.create(d, text("cell", FxRect(50f, 700f, 100f, 720f)))
        val names = FxEditor.makeCopies(d, "cell", rows = 2, cols = 3, dx = 60f, dy = 30f)
        assertEquals(5, names.size)
        val all = FxEditor.readAll(d).associateBy { it.name }
        assertEquals(FxRect(170f, 670f, 220f, 690f), all.getValue("cell_1_2").rect)
        val same = FxEditor.makeCopies(d, "cell", rows = 1, cols = 2, sameName = true)
        assertEquals(listOf("cell"), same)
        assertEquals(2, FxEditor.readAll(d).first { it.name == "cell" }.widgets.size)
    }

    @Test fun manualTabOrderReordersAnnots() {
        val d = doc()
        FxEditor.create(d, text("a")); FxEditor.create(d, text("b", FxRect(50f, 500f, 250f, 520f))); FxEditor.create(d, text("c", FxRect(50f, 400f, 250f, 420f)))
        FxEditor.setTabs(d, 0, "R")
        FxEditor.setManualTabOrder(d, 0, listOf(FxRef("c"), FxRef("a"), FxRef("b")))
        reload(d).use { r ->
            assertNull(FxEditor.tabsOf(r, 0))
            assertEquals(listOf("c", "a", "b"), FxEditor.tabOrder(r, 0).map { it.name })
            FxEditor.setTabs(r, 0, "R")
            assertEquals(listOf("a", "b", "c"), FxEditor.tabOrder(r, 0).map { it.name })
        }
    }

    @Test fun signatureFieldLock() {
        val d = doc()
        FxEditor.create(d, text("name")); FxEditor.create(d, text("date", FxRect(50f, 500f, 250f, 520f)))
        FxEditor.create(d, FieldSpec("sig", FxType.SIGNATURE, listOf(FxWidget(0, FxRect(50f, 100f, 250f, 150f))),
            sigLock = FxSigLock(FxSigLock.Mode.EXCLUDE, listOf("date"), "app.alert('signed');")))
        reload(d).use { r ->
            val s = FxEditor.readAll(r).first { it.name == "sig" }
            assertEquals(FxSigLock(FxSigLock.Mode.EXCLUDE, listOf("date"), "app.alert('signed');"), s.sigLock)
            val f = r.documentCatalog.getAcroForm(null).getField("sig").cosObject
            val lock = f.getDictionaryObject(COSName.getPDFName("Lock")) as COSDictionary
            assertEquals("SigFieldLock", lock.getCOSName(COSName.TYPE).name)
            assertEquals("Exclude", lock.getCOSName(COSName.getPDFName("Action")).name)
            assertEquals(1, (lock.getDictionaryObject(COSName.getPDFName("Fields")) as COSArray).size())
            assertEquals(listOf("name"), FxSigLocks.lockedFields(f, listOf("name", "date", "sig"), "sig"))
        }
    }

    @Test fun buttonIconAndRadioPlacedOneByOne() {
        val d = doc()
        FxEditor.create(d, FieldSpec("pic", FxType.BUTTON, listOf(FxWidget(0, FxRect(50f, 50f, 150f, 150f))), layout = FxButtonLayout.ICON_ONLY))
        val icon = FxAppearance.stream(d, 20f, 10f, "0 0 20 10 re f")
        assertTrue(FxEditor.setIcon(d, "pic", icon))
        FxEditor.create(d, FieldSpec("g", FxType.RADIO, listOf(FxWidget(0, FxRect(50f, 300f, 62f, 312f), "A"))))
        assertTrue(FxEditor.addRadioWidget(d, "g", 0, FxRect(80f, 300f, 92f, 312f), "B"))
        reload(d).use { r ->
            val w = r.documentCatalog.getAcroForm(null).getField("pic").widgets[0].cosObject
            val mk = w.getDictionaryObject(COSName.getPDFName("MK")) as COSDictionary
            assertNotNull(mk.getDictionaryObject(COSName.getPDFName("I")))
            val n = (w.getDictionaryObject(COSName.AP) as COSDictionary).getDictionaryObject(COSName.N) as com.tom_roush.pdfbox.cos.COSStream
            val xo = (n.getDictionaryObject(COSName.RESOURCES) as COSDictionary).getDictionaryObject(COSName.XOBJECT) as COSDictionary
            assertNotNull(xo.getDictionaryObject(COSName.getPDFName("FxIcon")))
            assertEquals(listOf("A", "B"), FxEditor.readAll(r).first { it.name == "g" }.widgets.map { it.export })
        }
        // icon placement math: proportional fit centred in the box
        val m = FxAppearance.iconMatrix(20f, 10f, floatArrayOf(0f, 0f, 100f, 100f), FxIconScale.ALWAYS, true)
        assertEquals(5f, m[0], 0.001f); assertEquals(25f, m[5], 0.001f)
    }

    @Test fun layerAndArticleActions() {
        val d = doc()
        val ocg1 = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("OCG")); setString(COSName.NAME, "Prices") }
        val ocg2 = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("OCG")); setString(COSName.NAME, "Notes") }
        d.documentCatalog.cosObject.setItem(COSName.getPDFName("OCProperties"), COSDictionary().apply {
            setItem(COSName.getPDFName("OCGs"), COSArray().apply { add(ocg1); add(ocg2) })
            setItem(COSName.D, COSDictionary())
        })
        d.documentCatalog.cosObject.setItem(COSName.getPDFName("Threads"), COSArray().apply { add(COSDictionary()); add(COSDictionary()) })
        val acts = listOf(FxAction.SetLayers(listOf("Prices" to "OFF", "Notes" to "Toggle")), FxAction.ReadArticle(1))
        FxEditor.create(d, FieldSpec("b", FxType.BUTTON, listOf(FxWidget(0, FxRect(0f, 0f, 50f, 20f))), actions = mapOf(FxTrigger.MOUSE_UP to acts)))
        reload(d).use { r -> assertEquals(acts, FxEditor.readAll(r).single().actions[FxTrigger.MOUSE_UP]) }
    }

    @Test fun multiFieldMergeOnlyCopiesChanges() {
        val a = text("a").copy(fontSize = 10f, required = false, maxLen = 4)
        val b = text("b", FxRect(1f, 1f, 9f, 9f)).copy(fontSize = 14f, required = true, maxLen = 7)
        val edited = a.copy(fillColor = 0xFF0000, maxLen = 9)
        val r = FxMerge.apply(a, edited, b)
        assertEquals("b", r.name); assertEquals(14f, r.fontSize, 0f); assertTrue(r.required); assertEquals(0xFF0000, r.fillColor); assertEquals(9, r.maxLen)
        val look = FxMerge.encodeLook(edited)
        assertEquals(FxMerge.look(edited), FxMerge.decodeLook(FxType.TEXT, look))
    }

    @Test fun designerLockIsReadBySigningCode() {
        for (lock in listOf(FxSigLock(FxSigLock.Mode.ALL), FxSigLock(FxSigLock.Mode.INCLUDE, listOf("a"), docPermission = 2), FxSigLock(FxSigLock.Mode.EXCLUDE, listOf("b")))) {
            val d = doc()
            FxEditor.create(d, text("a")); FxEditor.create(d, text("b", FxRect(50f, 500f, 250f, 520f)))
            FxEditor.create(d, FieldSpec("sig", FxType.SIGNATURE, listOf(FxWidget(0, FxRect(50f, 100f, 250f, 150f))), sigLock = lock))
            reload(d).use { r ->
                val f = r.documentCatalog.getAcroForm(null).getField("sig").cosObject
                val read = com.smartdocs.editor.core.sign.SigFields.readLock(f.getDictionaryObject(COSName.getPDFName("Lock")) as COSDictionary)!!
                assertEquals(lock.mode.name, read.action.name)
                assertEquals(if (lock.mode == FxSigLock.Mode.ALL) emptyList<String>() else lock.fields, read.fields)
                assertEquals(lock.docPermission, read.docPermission)
                val expected = listOf("a", "b").filter { read.locks(it) }
                assertEquals(expected, FxSigLocks.lockedFields(f, listOf("a", "b", "sig"), "sig"))
                assertEquals(lock.copy(script = ""), FxEditor.readAll(r).first { it.name == "sig" }.sigLock)
            }
        }
    }

    @Test fun inheritKeepsIdentity() {
        val src = text("src").copy(fontSize = 9f, fillColor = 0x123456, comb = true, maxLen = 5, format = FxFormat.Percent(1, 0))
        val dst = text("dst", FxRect(1f, 1f, 2f, 2f)).copy(tooltip = "keep")
        val r = FxEditor.inherit(src, dst)
        assertEquals("dst", r.name); assertEquals(dst.widgets, r.widgets); assertEquals("keep", r.tooltip)
        assertEquals(9f, r.fontSize, 0f); assertEquals(0x123456, r.fillColor); assertTrue(r.comb); assertEquals(FxFormat.Percent(1, 0), r.format)
        val cb = FxEditor.inherit(src, FieldSpec("c", FxType.CHECKBOX, emptyList()))
        assertEquals(0x123456, cb.fillColor); assertFalse(cb.comb)
    }
}
