package com.smartdocs.editor.core.forms5

import com.smartdocs.editor.core.forms.XfaFormCalc
import com.smartdocs.editor.core.forms.XfaJs
import com.smartdocs.editor.core.forms.XfaMerge
import com.smartdocs.editor.core.forms.XfaTemplateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** FINAL_CHECK_2: FormCalc calculate / validate / event scripts run through the FormCalc → JavaScript translation. */
class XfaFormCalcTest {
    private val xdp = """<?xml version="1.0" encoding="UTF-8"?>
<xdp:xdp xmlns:xdp="http://ns.adobe.com/xdp/">
<template xmlns="http://www.xfa.org/schema/xfa-template/3.3/"><subform name="form1" layout="tb">
  <pageSet><pageArea name="P1"><contentArea x="0.25in" y="0.25in" w="8in" h="10.5in"/><medium stock="letter" short="8.5in" long="11in"/></pageArea></pageSet>
  <subform name="S" layout="tb" w="6in">
    <subform name="Row" layout="lr-tb" w="6in"><occur min="3" max="3"/>
      <field name="Qty" w="1in" h="20pt"><ui><numericEdit/></ui></field>
      <field name="Price" w="1in" h="20pt"><ui><numericEdit/></ui></field>
      <field name="Line" w="1in" h="20pt"><ui><numericEdit/></ui>
        <calculate><script>Qty * Price</script></calculate></field>
    </subform>
    <field name="Total" w="2in" h="20pt"><ui><numericEdit/></ui>
      <calculate><script contentType="application/x-formcalc">Sum(Row[*].Line)</script></calculate></field>
    <field name="Grade" w="2in" h="20pt"><ui><textEdit/></ui>
      <calculate><script>if (Total &gt;= 100) then "A" elseif (Total &gt; 0) then "B" else "" endif</script></calculate></field>
    <field name="Name" w="2in" h="20pt"><ui><textEdit/></ui>
      <validate><script>HasValue(${'$'})</script><message><text name="scriptTest">Name is required</text></message></validate>
      <event activity="exit"><script>Upper2.rawValue = Upper(${'$'}.rawValue)</script></event></field>
    <field name="Upper2" w="2in" h="20pt"><ui><textEdit/></ui></field>
  </subform></subform></template>
<xfa:datasets xmlns:xfa="http://www.xfa.org/schema/xfa-data/1.0/"><xfa:data><form1><S>
  <Row><Qty>2</Qty><Price>10</Price><Line/></Row><Row><Qty>3</Qty><Price>20</Price><Line/></Row><Row><Qty/><Price/><Line/></Row>
  <Total/><Grade/><Name/><Upper2/></S></form1></xfa:data></xfa:datasets>
</xdp:xdp>"""

    private fun parse() = XfaTemplateParser.parseXml(xdp.toByteArray()).let { d -> XfaTemplateParser.parse(d)!! to XfaTemplateParser.findFirst(d, "data") }

    @Test fun translatesCommonConstructs() {
        assertNotNull(XfaFormCalc.toJs("var x = 0\nfor i = 1 upto 3 do x = x + i endfor\nx"))
        assertNotNull(XfaFormCalc.toJs("if (A eq \"x\" and B > 2) then 1 else 0 endif"))
        assertNull("user functions are not supported", XfaFormCalc.toJs("func f(a) do a endfunc"))
    }

    @Test fun calculateScriptsInFormCalcRun() {
        val (t, data) = parse()
        val m = XfaMerge.merge(t, data)
        XfaJs.run(m)
        val all = XfaMerge.walk(m.root).toList()
        assertEquals(listOf("20", "60", "0"), all.filter { it.name == "Line" }.map { it.value })
        assertEquals("80", all.first { it.name == "Total" }.value)
        assertEquals("B", all.first { it.name == "Grade" }.value)
    }

    @Test fun validateAndEventScriptsInFormCalcRun() {
        val (t, data) = parse()
        val rt = XfaEventRuntime(t, data, mapOf(
            "name" to XfaEventRuntime.Entry("form1[0].S[0].Name[0]", "TEXT"),
            "upper" to XfaEventRuntime.Entry("form1[0].S[0].Upper2[0]", "TEXT"),
        ))
        assertEquals("Name is required", rt.validate("name", mapOf("name" to "", "upper" to ""))!!.invalid["name"])
        assertEquals(0, rt.validate("name", mapOf("name" to "Ali", "upper" to ""))!!.invalid.size)
        assertEquals("ALI", rt.fire("name", "exit", mapOf("name" to "ali", "upper" to ""))!!.values["upper"])
    }
}
