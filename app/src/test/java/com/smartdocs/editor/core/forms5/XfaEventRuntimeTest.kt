package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.forms.XfaTemplateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/pdf39-forms-3: interactive XFA events (exit / click / change) at fill time. */
class XfaEventRuntimeTest {
    private val xdp = """<?xml version="1.0" encoding="UTF-8"?>
<xdp:xdp xmlns:xdp="http://ns.adobe.com/xdp/">
<template xmlns="http://www.xfa.org/schema/xfa-template/3.3/"><subform name="form1" layout="tb">
  <pageSet><pageArea name="P1"><contentArea x="0.25in" y="0.25in" w="8in" h="10.5in"/><medium stock="letter" short="8.5in" long="11in"/></pageArea></pageSet>
  <subform name="S" layout="tb" w="6in">
    <field name="Name" w="2in" h="20pt"><ui><textEdit/></ui>
      <event activity="exit"><script contentType="application/x-javascript">Greeting.rawValue = "Hello " + this.rawValue; Extra.presence = this.rawValue == "" ? "hidden" : "visible";</script></event></field>
    <field name="Greeting" w="2in" h="20pt"><ui><textEdit/></ui></field>
    <field name="Extra" w="2in" h="20pt" presence="hidden"><ui><textEdit/></ui></field>
    <field name="Agree" w="2in" h="20pt"><ui><checkButton/></ui><items><integer>1</integer><integer>0</integer></items>
      <event activity="click"><script contentType="application/x-javascript">Greeting.rawValue = this.rawValue == 1 ? "agreed" : "not yet";</script></event></field>
  </subform></subform></template>
<xfa:datasets xmlns:xfa="http://www.xfa.org/schema/xfa-data/1.0/"><xfa:data><form1><S><Name/><Greeting/><Extra/><Agree>0</Agree></S></form1></xfa:data></xfa:datasets>
</xdp:xdp>"""

    private fun runtime(): XfaEventRuntime {
        val doc = XfaTemplateParser.parseXml(xdp.toByteArray())
        val t = XfaTemplateParser.parse(doc)!!
        val data = XfaTemplateParser.findFirst(doc, "data")
        val map = mapOf(
            "xfa_0" to XfaEventRuntime.Entry("form1[0].S[0].Name[0]", "TEXT"),
            "xfa_1" to XfaEventRuntime.Entry("form1[0].S[0].Greeting[0]", "TEXT"),
            "xfa_2" to XfaEventRuntime.Entry("form1[0].S[0].Extra[0]", "TEXT"),
            "xfa_3" to XfaEventRuntime.Entry("form1[0].S[0].Agree[0]", "CHECK", exports = listOf("1", "0")),
        )
        return XfaEventRuntime(t, data, map)
    }

    @Test fun exitScriptChangesOtherFieldsAndPresence() {
        val rt = runtime()
        assertTrue(rt.has("xfa_0", "exit")); assertFalse(rt.has("xfa_1", "exit"))
        val r = rt.fire("xfa_0", "exit", mapOf("xfa_0" to "Sara", "xfa_1" to "", "xfa_2" to "", "xfa_3" to "false"))!!
        assertEquals("Hello Sara", r.values["xfa_1"])
        assertEquals("visible", r.presence["xfa_2"])
        assertNull(rt.fire("xfa_1", "exit", emptyMap()))
    }

    @Test fun clickOnACheckBoxSeesItsNewState() {
        val r = runtime().fire("xfa_3", "click", mapOf("xfa_0" to "", "xfa_1" to "", "xfa_2" to "", "xfa_3" to "true"))!!
        assertEquals("agreed", r.values["xfa_1"])
    }

    @Test fun valueConversions() {
        val chk = XfaEventRuntime.Entry("p", "CHECK", exports = listOf("Y", "N"))
        assertEquals("Y", XfaEventRuntime.toXfa(chk, "true")); assertEquals("false", XfaEventRuntime.toAcro(chk, "N"))
        val ch = XfaEventRuntime.Entry("p", "CHOICE", listOf("One", "Two"), listOf("1", "2"))
        assertEquals("2", XfaEventRuntime.toXfa(ch, "Two")); assertEquals("One", XfaEventRuntime.toAcro(ch, "1"))
        assertEquals("Off", XfaEventRuntime.toAcro(XfaEventRuntime.Entry("p", "RADIO"), ""))
        assertEquals("xfa_9", XfaEventRuntime.parseMap("""{"xfa_9":{"p":"a.b","t":"TEXT","o":[],"e":[]}}""").keys.single())
    }
}
