package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.forms.XfaTemplateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FINAL_CHECK_2: XFA validate (scriptTest, validate event) and the preSave / prePrint / preSubmit form events. */
class XfaFormEventsTest {
    private val xdp = """<?xml version="1.0" encoding="UTF-8"?>
<xdp:xdp xmlns:xdp="http://ns.adobe.com/xdp/">
<template xmlns="http://www.xfa.org/schema/xfa-template/3.3/"><subform name="form1" layout="tb">
  <pageSet><pageArea name="P1"><contentArea x="0.25in" y="0.25in" w="8in" h="10.5in"/><medium stock="letter" short="8.5in" long="11in"/></pageArea></pageSet>
  <event activity="preSave" ref="${'$'}form"><script contentType="application/x-javascript">S.Stamp.rawValue = "saved";</script></event>
  <event activity="prePrint" ref="${'$'}form"><script contentType="application/x-javascript">if (S.Age.rawValue == null) { xfa.host.messageBox("Fill in the age first"); xfa.event.cancelAction = true; }</script></event>
  <subform name="S" layout="tb" w="6in">
    <field name="Age" w="2in" h="20pt"><ui><numericEdit/></ui>
      <validate scriptTest="error"><script contentType="application/x-javascript">this.rawValue == null || (this.rawValue &gt;= 18 &amp;&amp; this.rawValue &lt;= 120)</script>
        <message><text name="scriptTest">Age must be between 18 and 120</text></message></validate>
      <event activity="validate"><script contentType="application/x-javascript">Note.rawValue = "checked " + this.rawValue;</script></event></field>
    <field name="Note" w="2in" h="20pt"><ui><textEdit/></ui></field>
    <field name="Stamp" w="2in" h="20pt"><ui><textEdit/></ui></field>
    <field name="Plain" w="2in" h="20pt"><ui><textEdit/></ui></field>
  </subform></subform></template>
<xfa:datasets xmlns:xfa="http://www.xfa.org/schema/xfa-data/1.0/"><xfa:data><form1><S><Age/><Note/><Stamp/><Plain/></S></form1></xfa:data></xfa:datasets>
</xdp:xdp>"""

    private fun runtime(): XfaEventRuntime {
        val doc = XfaTemplateParser.parseXml(xdp.toByteArray())
        val t = XfaTemplateParser.parse(doc)!!
        val data = XfaTemplateParser.findFirst(doc, "data")
        return XfaEventRuntime(t, data, mapOf(
            "age" to XfaEventRuntime.Entry("form1[0].S[0].Age[0]", "TEXT"),
            "note" to XfaEventRuntime.Entry("form1[0].S[0].Note[0]", "TEXT"),
            "stamp" to XfaEventRuntime.Entry("form1[0].S[0].Stamp[0]", "TEXT"),
            "plain" to XfaEventRuntime.Entry("form1[0].S[0].Plain[0]", "TEXT"),
        ))
    }

    private fun values(age: String) = mapOf("age" to age, "note" to "", "stamp" to "", "plain" to "")

    @Test fun parsesValidateScriptAndMessage() {
        val t = XfaTemplateParser.parse(XfaTemplateParser.parseXml(xdp.toByteArray()))!!
        val all = com.smartdocs.editor.core.forms.XfaMerge.walk(com.smartdocs.editor.core.forms.XfaMerge.merge(t, null).root).toList()
        val age = all.first { it.name == "Age" }.node.scripts
        assertTrue(age.validate!!.contains(">= 18"))
        assertEquals("Age must be between 18 and 120", age.validateMessage)
        assertEquals(1, age.events["validate"]?.size)
        val root = all.first { it.name == "form1" }.node.scripts
        assertEquals(setOf("preSave", "prePrint"), root.events.keys)
    }

    @Test fun validateFailsWithItsMessageAndRunsTheValidateEvent() {
        val rt = runtime()
        val bad = rt.validate("age", values("12"))!!
        assertEquals("Age must be between 18 and 120", bad.invalid["age"])
        assertEquals("checked 12", bad.values["note"])
        val ok = rt.validate("age", values("30"))!!
        assertTrue(ok.invalid.isEmpty())
        assertNull("no validate script", rt.validate("plain", values("30")))
    }

    @Test fun preSaveScriptsChangeValues() {
        val r = runtime().formEvent("preSave", values("30"))!!
        assertEquals("saved", r.values["stamp"]); assertFalse(r.cancelled)
    }

    @Test fun prePrintCanCancelWithAMessage() {
        val rt = runtime()
        val r = rt.formEvent("prePrint", values(""))!!
        assertTrue(r.cancelled); assertEquals(listOf("Fill in the age first"), r.messages)
        assertFalse(rt.formEvent("prePrint", values("40"))!!.cancelled)
    }

    @Test fun preSubmitValidatesEveryField() {
        val rt = runtime()
        assertTrue(rt.hasFormEvent("preSubmit"))           // no preSubmit script, but a validate test
        assertEquals("Age must be between 18 and 120", rt.formEvent("preSubmit", values("200"))!!.invalid["age"])
        assertTrue(rt.formEvent("preSubmit", values("25"))!!.invalid.isEmpty())
    }
}
