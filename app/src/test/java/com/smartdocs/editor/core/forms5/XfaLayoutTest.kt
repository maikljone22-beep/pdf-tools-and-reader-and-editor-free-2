package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.forms.XItem
import com.smartdocs.editor.core.forms.XLayoutResult
import com.smartdocs.editor.core.forms.XMerged
import com.smartdocs.editor.core.forms.XTemplate
import com.smartdocs.editor.core.forms.XWKind
import com.smartdocs.editor.core.forms.XfaJs
import com.smartdocs.editor.core.forms.XfaLayout
import com.smartdocs.editor.core.forms.XfaMerge
import com.smartdocs.editor.core.forms.XfaTemplateParser
import com.smartdocs.editor.core.forms.XfaUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XfaLayoutTest {

    private fun xdp(template: String, data: String = "") = """<?xml version="1.0" encoding="UTF-8"?>
<xdp:xdp xmlns:xdp="http://ns.adobe.com/xdp/">
<template xmlns="http://www.xfa.org/schema/xfa-template/3.3/">$template</template>
<xfa:datasets xmlns:xfa="http://www.xfa.org/schema/xfa-data/1.0/"><xfa:data>$data</xfa:data></xfa:datasets>
</xdp:xdp>"""

    private class Run(val t: XTemplate, val m: XMerged, val r: XLayoutResult) {
        val widgets get() = r.widgets
        fun widget(path: String) = r.widgets.firstOrNull { it.second.dataPath == path }
    }

    private fun run(xml: String, scripts: Boolean = false): Run {
        val doc = XfaTemplateParser.parseXml(xml.toByteArray())
        val t = XfaTemplateParser.parse(doc)!!
        val data = XfaTemplateParser.findFirst(doc, "data")
        val m = XfaMerge.merge(t, data)
        if (scripts) XfaJs.run(m)
        return Run(t, m, XfaLayout(t).layout(m))
    }

    private val letter = """<pageSet><pageArea name="P1" id="P1"><contentArea x="0.25in" y="0.25in" w="8in" h="10.5in"/><medium stock="letter" short="8.5in" long="11in"/></pageArea>
        <pageArea name="P2" id="P2"><contentArea x="0.5in" y="0.5in" w="10in" h="7.5in"/><medium stock="letter" short="8.5in" long="11in" orientation="landscape"/></pageArea></pageSet>"""

    private fun near(e: Float, a: Float) = assertEquals(e, a, 0.05f)

    @Test fun units() {
        near(72f, XfaUnits.pt("1in")); near(72f, XfaUnits.pt("25.4mm")); near(72f, XfaUnits.pt("2.54cm"))
        near(12f, XfaUnits.pt("12pt")); near(36f, XfaUnits.pt("0.5")); near(0f, XfaUnits.pt("abc"))
    }

    @Test fun positionedPagesWithCaptionsAndData() {
        val r = run(xdp("""<subform name="form1" layout="tb">$letter
            <subform name="Page1" w="8in" h="10.5in">
              <draw name="Title" x="0" y="0" w="8in" h="12mm"><value><text>Application</text></value><font size="16pt" weight="bold"/><para hAlign="center"/></draw>
              <draw name="rule" x="0" y="14mm" w="8in" h="0"><value><line><edge thickness="1pt"/></line></value></draw>
              <field name="Name" x="10mm" y="20mm" w="80mm" h="9mm"><ui><textEdit><border><edge/></border></textEdit></ui>
                <caption reserve="25mm"><value><text>Name</text></value></caption></field>
              <field name="Agree" x="10mm" y="40mm" w="50mm" h="6mm"><ui><checkButton size="4mm"/></ui>
                <caption placement="right" reserve="40mm"><value><text>I agree</text></value></caption><items><integer>1</integer><integer>0</integer></items></field>
              <field name="Hidden" x="10mm" y="60mm" w="50mm" h="6mm" presence="hidden"><ui><textEdit/></ui></field>
            </subform>
            <subform name="Page2" w="8in" h="10.5in"><field name="Notes" x="0" y="0" w="8in" h="2in"><ui><textEdit multiLine="1"/></ui></field></subform>
            </subform>""", "<form1><Page1><Name>Ali</Name><Agree>1</Agree></Page1></form1>"))
        assertEquals(2, r.r.pages.size)
        val (pg, name) = r.widget("form1[0].Page1[0].Name[0]")!!
        assertEquals(0, pg)
        near(18f + XfaUnits.pt("10mm") + XfaUnits.pt("25mm"), name.x); near(18f + XfaUnits.pt("20mm"), name.y)
        near(XfaUnits.pt("55mm"), name.w); near(XfaUnits.pt("9mm"), name.h)
        assertEquals("Ali", name.value); assertEquals(XWKind.TEXT, name.kind)
        val agree = r.widget("form1[0].Page1[0].Agree[0]")!!.second
        assertEquals(XWKind.CHECK, agree.kind); assertEquals("1", agree.onValue); assertEquals("0", agree.offValue); assertEquals("1", agree.value)
        near(XfaUnits.pt("4mm"), agree.w)
        assertNull(r.widget("form1[0].Page1[0].Hidden[0]"))
        val notes = r.widget("form1[0].Page2[0].Notes[0]")!!
        assertEquals(1, notes.first); assertTrue(notes.second.multiline)
        val texts = r.r.pages[0].items.filterIsInstance<XItem.Text>().map { it.lines.joinToString(" ") }
        assertTrue("Application" in texts); assertTrue("Name" in texts); assertTrue("I agree" in texts)
        assertTrue(r.r.pages[0].items.any { it is XItem.Line })
        assertTrue(r.r.pages[0].items.any { it is XItem.Box })   // the textEdit border
    }

    @Test fun flowedRepeatingRowsOverflowToTheNextPage() {
        val rows = (0 until 40).joinToString("") { "<Row><Desc>Item $it</Desc><Qty>${it + 1}</Qty></Row>" }
        val r = run(xdp("""<subform name="form1" layout="tb">$letter
            <draw name="Head" w="8in" h="0.5in"><value><text>Items</text></value></draw>
            <subform name="Items" layout="tb" w="8in">
              <subform name="Row" layout="lr-tb" w="7in"><occur min="1" max="-1"/>
                <field name="Desc" w="5in" h="0.3in"><ui><textEdit/></ui></field>
                <field name="Qty" w="2in" h="0.3in"><ui><numericEdit/></ui></field>
              </subform>
            </subform></subform>""", "<form1><Items>$rows</Items></form1>"))
        assertEquals(80, r.widgets.size)
        // 0.5in head + 40 rows of 0.3in = 12.5in > 10.5in content area: two pages
        assertEquals(2, r.r.pages.size)
        val first = r.widget("form1[0].Items[0].Row[0].Desc[0]")!!.second
        val qty0 = r.widget("form1[0].Items[0].Row[0].Qty[0]")!!.second
        near(first.x + 360f, qty0.x); near(first.y, qty0.y)        // lr-tb: same row
        near(18f + 36f, first.y)
        val last = r.widget("form1[0].Items[0].Row[39].Qty[0]")!!
        assertEquals(1, last.first); assertEquals("40", last.second.value)
        // the row that does not fit starts at the top of the next content area
        val firstOnPage2 = r.widgets.filter { it.first == 1 }.minByOrNull { it.second.y }!!.second
        near(18f, firstOnPage2.y)
        val rowsOnPage1 = r.widgets.count { it.first == 0 && it.second.dataPath!!.endsWith("Desc[0]") }
        assertEquals(((756f - 36f) / 21.6f).toInt(), rowsOnPage1)       // 720pt left under the heading: 33 rows
        // an "add row" place after the last instance
        val add = r.r.pages[1].items.filterIsInstance<XItem.AddRow>().single()
        assertEquals("Row", add.repeat.name); assertEquals(40, add.repeat.count); assertTrue(add.repeat.canAdd)
        assertEquals("form1[0].Items[0]", add.repeat.dataParent)
    }

    @Test fun rightToLeftFlowAndArabicCaptions() {
        val r = run(xdp("""<subform name="form1" layout="tb">$letter
            <subform name="S" layout="rl-tb" w="6in">
              <field name="A" w="3in" h="20pt"><ui><textEdit/></ui><caption placement="right" reserve="1in"><value><text>ARABIC_NAME</text></value></caption></field>
              <field name="B" w="3in" h="20pt"><ui><textEdit/></ui></field>
              <field name="C" w="3in" h="20pt"><ui><textEdit/></ui></field>
            </subform></subform>""").replace("ARABIC_NAME", "\u0627\u0644\u0627\u0633\u0645"))
        val a = r.widget("form1[0].S[0].A[0]")!!.second; val b = r.widget("form1[0].S[0].B[0]")!!.second; val c = r.widget("form1[0].S[0].C[0]")!!.second
        near(18f + 216f, a.x)                 // first field on the right half, caption at its right end
        near(18f + 216f + 216f - 72f, a.x + a.w)
        near(18f, b.x); near(a.y, b.y)        // second field to its left, same row
        near(18f + 216f, c.x); near(a.y + 20f, c.y)   // wraps to the next row, again from the right
        val cap = r.r.pages[0].items.filterIsInstance<XItem.Text>().first { it.lines.first().contains('\u0627') }
        assertTrue(cap.rtl); assertEquals("right", cap.hAlign)
    }

    @Test fun presenceInvisibleKeepsSpaceHiddenDoesNot() {
        val r = run(xdp("""<subform name="form1" layout="tb">$letter
            <field name="A" w="2in" h="20pt"><ui><textEdit/></ui></field>
            <field name="B" w="2in" h="20pt" presence="hidden"><ui><textEdit/></ui></field>
            <field name="C" w="2in" h="20pt" presence="invisible"><ui><textEdit/></ui></field>
            <field name="D" w="2in" h="20pt"><ui><textEdit/></ui></field></subform>"""))
        assertNull(r.widget("form1[0].B[0]")); assertNull(r.widget("form1[0].C[0]"))
        near(r.widget("form1[0].A[0]")!!.second.y + 40f, r.widget("form1[0].D[0]")!!.second.y)
    }

    @Test fun breakBeforeTargetsAPageArea() {
        val r = run(xdp("""<subform name="form1" layout="tb">$letter
            <subform name="One" w="8in" h="1in"><field name="A" w="2in" h="20pt"><ui><textEdit/></ui></field></subform>
            <subform name="Two" w="8in" h="1in"><breakBefore targetType="pageArea" target="#P2"/><field name="B" w="2in" h="20pt"><ui><textEdit/></ui></field></subform>
            </subform>"""))
        assertEquals(2, r.r.pages.size)
        near(792f, r.r.pages[1].w); near(612f, r.r.pages[1].h)
        val b = r.widget("form1[0].Two[0].B[0]")!!
        assertEquals(1, b.first); near(36f, b.second.x); near(36f, b.second.y)
    }

    @Test fun occurInitialAndMaxWithoutData() {
        val r = run(xdp("""<subform name="form1" layout="tb">$letter
            <subform name="Line" layout="tb" w="4in"><occur min="1" max="3" initial="3"/><field name="X" w="2in" h="20pt"><ui><textEdit/></ui></field></subform>
            </subform>"""))
        assertEquals(3, r.widgets.size)
        assertNotNull(r.widget("form1[0].Line[2].X[0]"))
        assertTrue(r.r.pages.flatMap { it.items }.none { it is XItem.AddRow })      // max reached
        assertFalse(r.m.repeats.single().canAdd)
    }

    @Test fun exclusionGroupBecomesRadioWidgets() {
        val r = run(xdp("""<subform name="form1" layout="tb">$letter
            <exclGroup name="Gender" w="4in" h="20pt" layout="lr-tb">
              <field name="m" w="1in" h="20pt"><ui><checkButton shape="round"/></ui><caption placement="right"><value><text>Male</text></value></caption><items><text>M</text></items></field>
              <field name="f" w="1in" h="20pt"><ui><checkButton shape="round"/></ui><caption placement="right"><value><text>Female</text></value></caption><items><text>F</text></items></field>
            </exclGroup></subform>""", "<form1><Gender>F</Gender></form1>"))
        val radios = r.widgets.map { it.second }.filter { it.kind == XWKind.RADIO }
        assertEquals(listOf("M", "F"), radios.map { it.onValue })
        assertTrue(radios.all { it.dataPath == "form1[0].Gender[0]" && it.value == "F" && it.group == radios[0].group })
        assertTrue(radios[1].x > radios[0].x)
    }

    @Test fun javascriptInitializeAndCalculate() {
        val r = run(xdp("""<subform name="form1" layout="tb">$letter
            <subform name="Order" layout="tb" w="6in">
              <field name="Qty" w="2in" h="20pt"><ui><numericEdit/></ui></field>
              <field name="Price" w="2in" h="20pt"><ui><numericEdit/></ui></field>
              <field name="Total" w="2in" h="20pt"><ui><numericEdit/></ui>
                <calculate><script contentType="application/x-javascript">Qty.rawValue * Price.rawValue</script></calculate></field>
              <field name="Vat" w="2in" h="20pt"><ui><numericEdit/></ui>
                <calculate><script>Total * 0.15</script></calculate></field>
              <field name="Secret" w="2in" h="20pt"><ui><textEdit/></ui>
                <event activity="initialize"><script contentType="application/x-javascript">if (xfa.resolveNode("form1.Order.Qty").rawValue > 2) this.presence = "hidden";</script></event></field>
            </subform></subform>""", "<form1><Order><Qty>3</Qty><Price>2.5</Price><Vat>9</Vat></Order></form1>"), scripts = true)
        assertEquals("7.5", r.widget("form1[0].Order[0].Total[0]")!!.second.value)
        assertEquals("1.125", r.widget("form1[0].Order[0].Vat[0]")!!.second.value)      // FormCalc runs too (translated to JS): 7.5 × 0.15
        assertNull(r.widget("form1[0].Order[0].Secret[0]"))
    }
    @Test fun repeatPlacesSurviveTheDocumentInfoRoundTrip() {
        val list = listOf(XfaRepeat("form1[0].Items[0]/Row", "form1[0].Items[0]", "Row", 40, -1, 1, Box5(18f, 700.5f, 594f, 678.9f)),
            XfaRepeat("k2", "", "Line", 3, 3, 0, Box5(0f, 10f, 5f, 0f)))
        val back = XfaRepeat.parse(XfaRepeat.toJson(list))
        assertEquals(list, back)
        assertTrue(back[0].canAdd); assertFalse(back[1].canAdd)
        assertTrue(XfaRepeat.parse(null).isEmpty()); assertTrue(XfaRepeat.parse("[{\"bad\":1}]").isEmpty())
    }

    @Test fun pictureClauseReachesTheWidget() {
        val r = run(xdp("""<subform name="form1" layout="tb">$letter
            <subform name="Page1" w="8in" h="10.5in">
              <field name="Amount" x="10mm" y="20mm" w="60mm" h="9mm"><ui><numericEdit/></ui>
                <format><picture>num{z,zz9.99}</picture></format></field>
            </subform></subform>""", "<form1><Page1><Amount>1234.5</Amount></Page1></form1>"))
        val w = r.widgets.map { it.second }.first { it.dataPath?.contains("Amount") == true }
        assertEquals("num{z,zz9.99}", w.picture)
        assertEquals("1,234.50", com.smartdocs.editor.core.forms.XfaPicture.format(w.picture, w.value))
        assertEquals("date{MM/DD/YYYY}", com.smartdocs.editor.core.forms.XfaPicture.withCategory("MM/DD/YYYY", com.smartdocs.editor.core.forms.XfaPicture.Hint.DATE))
        assertEquals("num{9}", com.smartdocs.editor.core.forms.XfaPicture.withCategory("num{9}", com.smartdocs.editor.core.forms.XfaPicture.Hint.DATE))
    }

    @Test fun readyEventsRunAfterInitialize() {
        val r = run(xdp("""<subform name="form1" layout="tb">$letter
            <subform name="Order" layout="tb" w="6in">
              <field name="Stamp" w="2in" h="20pt"><ui><textEdit/></ui>
                <event activity="initialize"><script contentType="application/x-javascript">this.rawValue = "init";</script></event>
                <event activity="ready" ref="${'$'}form"><script contentType="application/x-javascript">this.rawValue = this.rawValue + "+ready";</script></event></field>
              <field name="Doc" w="2in" h="20pt"><ui><textEdit/></ui>
                <event activity="docReady" ref="${'$'}host"><script contentType="application/x-javascript">this.rawValue = "open";</script></event></field>
            </subform></subform>"""), scripts = true)
        assertEquals("init+ready", r.widget("form1[0].Order[0].Stamp[0]")!!.second.value)
        assertEquals("open", r.widget("form1[0].Order[0].Doc[0]")!!.second.value)
    }
}
