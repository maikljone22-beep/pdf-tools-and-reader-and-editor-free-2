package com.smartdocs.editor.core.formsx

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Scripts, form data exchange, barcodes, recognition and layout math of the form designer. */
class FormDataTest {

    private fun formDoc(): PDDocument {
        val d = PDDocument(); d.addPage(PDPage())
        FxEditor.create(d, FieldSpec("person.name", FxType.TEXT, listOf(FxWidget(0, FxRect(50f, 700f, 250f, 720f)))))
        FxEditor.create(d, FieldSpec("agree", FxType.CHECKBOX, listOf(FxWidget(0, FxRect(50f, 650f, 64f, 664f))), exportValue = "Yes"))
        FxEditor.create(d, FieldSpec("size", FxType.RADIO, listOf(FxWidget(0, FxRect(50f, 600f, 62f, 612f), "S"), FxWidget(0, FxRect(80f, 600f, 92f, 612f), "L"))))
        FxEditor.create(d, FieldSpec("note", FxType.TEXT, listOf(FxWidget(0, FxRect(50f, 500f, 250f, 520f)))))
        return d
    }

    private fun setRaw(d: PDDocument, values: Map<String, String>) {
        // text values on the COS level (PdfBox's setValue needs font metrics that JVM tests do not have)
        values.forEach { (k, v) -> d.documentCatalog.getAcroForm(null).getField(k).cosObject.setItem(COSName.V, COSString(v)) }
    }

    @Test fun scriptsRoundTrip() {
        val fmts = listOf(FxFormat.Number(0, 2, 3, "SAR ", false), FxFormat.Percent(1, 1), FxFormat.Date("yyyy-mm-dd"), FxFormat.Time(3),
            FxFormat.Special(2), FxFormat.Mask("999-AAA"), FxFormat.Custom("event.value = 1;", "event.rc = true;"))
        fmts.forEach { f -> val (a, b) = FxScripts.format(f)!!; assertEquals(f, FxScripts.parseFormat(a, b)) }
        listOf(FxValidate.Range(null, 5.5), FxValidate.Range(-2.0, null), FxValidate.Custom("if (event.value < 0) event.rc = false;"))
            .forEach { v -> assertEquals(v, FxScripts.parseValidate(FxScripts.validate(v))) }
        listOf(FxCalc.Simple(FxCalcOp.AVERAGE, listOf("x.1", "y")), FxCalc.Simple(FxCalcOp.MAX, emptyList()), FxCalc.Sfn("(price * qty) - disc"), FxCalc.Custom("event.value = 2;"))
            .forEach { c -> assertEquals(c, FxScripts.parseCalc(FxScripts.calc(c))) }
        assertEquals(listOf("price", "qty", "my field"), FxScripts.sfnFields("price*qty + my\\ field - 2.5"))
        assertTrue(FxScripts.sfnToJs("a + b").contains("AFMakeNumber(getField(\"a\").value) + AFMakeNumber(getField(\"b\").value)"))
    }

    @Test fun exportAndParseEveryFormat() {
        formDoc().use { d ->
            setRaw(d, mapOf("person.name" to "Ali, \"the\" first", "note" to "line"))
            d.documentCatalog.getAcroForm(null).getField("agree").cosObject.setItem(COSName.V, COSName.getPDFName("Yes"))
            val expected = mapOf("person.name" to "Ali, \"the\" first", "agree" to "Yes", "size" to "Off", "note" to "line")
            assertEquals(expected, FormData.values(d))
            for (fmt in DataFormat.entries) {
                val bos = ByteArrayOutputStream(); FormData.export(d, fmt, bos, "form.pdf")
                val bytes = bos.toByteArray()
                assertEquals(fmt, FormData.detect("x.${fmt.ext}", bytes))
                assertEquals("format $fmt", expected, FormData.parse(bytes, fmt))
            }
        }
    }

    @Test fun detectByContent() {
        assertEquals(DataFormat.FDF, FormData.detect("data", "%FDF-1.2\n".toByteArray()))
        assertEquals(DataFormat.XFDF, FormData.detect("data", "<?xml version=\"1.0\"?><xfdf>".toByteArray()))
        assertEquals(DataFormat.CSV, FormData.detect("data", "a,b\n1,2".toByteArray()))
        assertEquals(DataFormat.XDP, FormData.detect("data", "<?xml version=\"1.0\"?><xdp:xdp xmlns:xdp=\"http://ns.adobe.com/xdp/\">".toByteArray()))
    }

    @Test fun fillSignTargetHit() {
        val big = FieldRecognition.Suggestion(FxType.TEXT, FxRect(50f, 500f, 250f, 520f), "a", "")
        val box = FieldRecognition.Suggestion(FxType.CHECKBOX, FxRect(60f, 505f, 70f, 515f), "b", "")
        assertEquals(box, FieldRecognition.at(listOf(big, box), 65f, 510f))
        assertEquals(big, FieldRecognition.at(listOf(big, box), 200f, 510f))
        assertEquals(null, FieldRecognition.at(listOf(big, box), 300f, 510f))
        assertEquals(null, FieldRecognition.at(null, 65f, 510f))
    }

    @Test fun xdpPackageAndIndexedNames() {
        val x = FormData.xdp(mapOf("form1[0].Page1[0].Name[0]" to "Sara & co", "form1[0].Page1[0].Age[0]" to "30"), "f.pdf")
        assertTrue(x.contains("<xfa:data>") && x.contains("<pdf href=\"f.pdf\""))
        assertTrue(x.contains("<Name>Sara &amp; co</Name>"))
        assertEquals(mapOf("form1.Page1.Name" to "Sara & co", "form1.Page1.Age" to "30"), FormData.parseXdp(x.toByteArray()))
        assertEquals("form1.Page1.Name", FormData.stripIndices("form1[0].Page1[0].Name[0]"))
        // an XFDF payload inside an XDP package
        val wrapped = "<xdp:xdp xmlns:xdp=\"http://ns.adobe.com/xdp/\"><xfdf><fields><field name=\"a\"><value>1</value></field></fields></xfdf></xdp:xdp>"
        assertEquals(mapOf("a" to "1"), FormData.parseXdp(wrapped.toByteArray()))
        formDoc().use { d ->
            assertEquals(1, FormData.importInto(d, mapOf("person[0].name[0]" to "Hind")))
            assertEquals("Hind", FormData.values(d)["person.name"])
        }
    }

    @Test fun importSetsTextChecksAndRadios() {
        formDoc().use { d ->
            val n = FormData.importInto(d, mapOf("agree" to "true", "size" to "L", "missing" to "x"))
            assertEquals(2, n)
            val form = d.documentCatalog.getAcroForm(null)
            assertEquals("Yes", form.getField("agree").cosObject.getCOSName(COSName.V).name)
            assertEquals("L", form.getField("size").cosObject.getCOSName(COSName.V).name)
            val radios = (form.getField("size") as com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton).widgets
            assertEquals(listOf("Off", "L"), radios.map { it.cosObject.getCOSName(COSName.AS).name })
            FormData.importInto(d, FormData.parseXml(FormData.xml(mapOf("note" to "hello")).toByteArray()))
            assertEquals("hello", form.getField("note").cosObject.getString(COSName.V))
        }
    }

    @Test fun acrobatStyleXmlAndNestedXfdf() {
        val xml = "<?xml version=\"1.0\"?><fields><person><name>Sara</name></person><note>n</note></fields>"
        assertEquals(mapOf("person.name" to "Sara", "note" to "n"), FormData.parseXml(xml.toByteArray()))
        val xfdf = FormData.xfdf(mapOf("a.b" to "1", "a.c" to "x\ny"), "f.pdf")
        assertEquals(mapOf("a.b" to "1", "a.c" to "x\ny"), FormData.parseXfdf(xfdf.toByteArray()))
    }

    @Test fun collectResponsesAppends() {
        val first = FormData.collect(listOf("a.pdf" to mapOf("x" to "1", "y" to "2")))
        val more = FormData.collect(listOf("b.pdf" to mapOf("x" to "3", "z" to "q,r")), append = first.toByteArray())
        val rows = FormData.parseTable(more.toByteArray(), ',')
        assertEquals(2, rows.size)
        assertEquals(mapOf("file" to "a.pdf", "x" to "1", "y" to "2", "z" to ""), rows[0])
        assertEquals(mapOf("file" to "b.pdf", "x" to "3", "y" to "", "z" to "q,r"), rows[1])
    }

    private fun decodeQr(m: Array<BooleanArray>): String {
        val q = 4; val s = 4
        val w = (m[0].size + 2 * q) * s; val h = (m.size + 2 * q) * s
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in m.indices) for (x in m[y].indices) if (m[y][x]) for (dy in 0 until s) for (dx in 0 until s) px[((y + q) * s + dy) * w + (x + q) * s + dx] = 0xFF000000.toInt()
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px)))).text
    }

    @Test fun barcodeFieldEncodesOtherFields() {
        formDoc().use { d ->
            setRaw(d, mapOf("person.name" to "Ali", "note" to "n1"))
            val b = FxBarcode(FxBarcodeKind.QR, fields = listOf("person.name", "note"))
            FxEditor.create(d, FieldSpec("code", FxType.BARCODE, listOf(FxWidget(0, FxRect(300f, 300f, 420f, 420f))), barcode = b))
            val f = d.documentCatalog.getAcroForm(null).getField("code")
            assertEquals("person.name\tnote\nAli\tn1", f.cosObject.getString(COSName.V))
            val sym = FxBarcodes.encode(f.cosObject.getString(COSName.V), b)!!
            assertEquals("person.name\tnote\nAli\tn1", decodeQr(sym.modules))
            val ap = (f.widgets[0].cosObject.getDictionaryObject(COSName.AP) as COSDictionary).getDictionaryObject(COSName.N)
            assertNotNull(ap)
            assertTrue(f.cosObject.getDictionaryObject(COSName.getPDFName("PMD")) is COSDictionary)
            // values change -> refreshAll recomputes the payload
            setRaw(d, mapOf("person.name" to "Omar"))
            assertEquals(1, FxBarcodes.refreshAll(d))
            assertEquals("person.name\tnote\nOmar\tn1", f.cosObject.getString(COSName.V))
            val read = FxEditor.readAll(d).first { it.name == "code" }
            assertEquals(FxType.BARCODE, read.type); assertEquals(b, read.barcode)
        }
    }

    @Test fun otherSymbologiesEncode() {
        val p = FxBarcodes.encode("hello world 123", FxBarcode(FxBarcodeKind.PDF417, ecc = 2, yxRatio = 3f))!!
        assertTrue(p.modules.size >= 3); assertEquals(3f, p.rowHeight, 0f)
        val dm = FxBarcodes.encode("hello", FxBarcode(FxBarcodeKind.DATAMATRIX))!!
        assertEquals(dm.modules.size, dm.modules[0].size)
        val xml = FxBarcodes.payload(listOf("a" to "<1>"), FxBarcode(xml = true))
        assertEquals("<xfdf><fields><field name=\"a\"><value>&lt;1&gt;</value></field></fields></xfdf>", xml)
        val z = FxBarcodes.payload(listOf("a" to "1"), FxBarcode(compress = true))
        val inf = java.util.zip.Inflater(); inf.setInput(java.util.Base64.getDecoder().decode(z)); val out = ByteArray(100); val n = inf.inflate(out)
        assertEquals("a\n1", String(out, 0, n))
    }

    @Test fun recognitionFindsBlanksBoxesAndLines() {
        val g = ArrayList<FieldRecognition.Glyph>()
        fun word(s: String, x: Float, y: Float) { var cx = x; s.forEach { c -> g += FieldRecognition.Glyph(c.toString(), cx, y, cx + 6f, y + 8f); cx += 6f } }
        word("Name: ", 50f, 700f); word("__________", 86f, 700f)
        word("\u2610 Married", 50f, 650f)
        word("City:", 50f, 600f)
        val segs = listOf(
            FieldRecognition.Seg(90f, 600f, 300f, 600f),                       // fill-in line next to "City:"
            // empty box 200x20 at y 500
            FieldRecognition.Seg(50f, 500f, 250f, 500f), FieldRecognition.Seg(50f, 520f, 250f, 520f),
            FieldRecognition.Seg(50f, 500f, 50f, 520f), FieldRecognition.Seg(250f, 500f, 250f, 520f),
            // small square check box 10x10
            FieldRecognition.Seg(50f, 450f, 60f, 450f), FieldRecognition.Seg(50f, 460f, 60f, 460f),
            FieldRecognition.Seg(50f, 450f, 50f, 460f), FieldRecognition.Seg(60f, 450f, 60f, 460f),
        )
        word("Agree", 64f, 451f)
        val s = FieldRecognition.detect(segs, g, 612f, 792f)
        val byName = s.associateBy { it.name }
        assertEquals(FxType.TEXT, byName.getValue("Name").type)
        assertEquals(FxType.CHECKBOX, byName.getValue("Married").type)
        assertEquals(FxType.TEXT, byName.getValue("City").type)
        assertEquals(FxType.CHECKBOX, byName.getValue("Agree").type)
        assertTrue(s.any { it.type == FxType.TEXT && it.rect.bottom > 500f && it.rect.top < 520f })
        assertEquals(5, s.size)
        // already existing fields are not suggested again
        assertEquals(4, FieldRecognition.detect(segs, g, 612f, 792f, existing = listOf(byName.getValue("City").rect)).size)
        assertEquals("First_name", FieldRecognition.nameOf("First name:", "x"))
    }

    @Test fun layoutMath() {
        val rs = listOf(FxRect(0f, 0f, 10f, 10f), FxRect(50f, 20f, 70f, 40f), FxRect(20f, 5f, 25f, 8f))
        assertEquals(listOf(0f, 0f, 0f), FxLayout.align(rs, FxLayout.Align.LEFT).map { it.left })
        assertEquals(listOf(10f, 10f, 10f), FxLayout.align(rs, FxLayout.Align.TOP).map { it.top })
        val d = FxLayout.distribute(rs, horizontal = true)
        assertEquals(listOf(5f, 60f, 32.5f), d.map { it.cx })
        val m = FxLayout.matchSize(rs, FxLayout.Size.BOTH, anchor = 1)
        assertTrue(m.all { it.width == 20f && it.height == 20f })
        val c = FxLayout.centerOnPage(rs, 100f, 100f, horizontal = true, vertical = false)
        assertEquals(50f, (c.minOf { it.left } + c.maxOf { it.right }) / 2, 0.001f)
    }
}
