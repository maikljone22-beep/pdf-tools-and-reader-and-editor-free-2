package com.smartdocs.editor.core.forms5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormScriptsTest {

    private fun scripts(fields: List<JsField>, js: Map<String, FieldJs>, order: List<String> = js.filterValues { it.calculate != null }.keys.toList(),
                        doc: List<String> = emptyList(), limits: JsLimits = JsLimits(eventMillis = 2000, initMillis = 4000)) =
        FormScripts(FormJs(fields, js, order, doc), limits)

    private fun text(n: String, v: String = "") = JsField(n, "text", v)

    @Test fun rangeValidateRejectsAndReportsBounds() {
        val s = scripts(listOf(text("age")), mapOf("age" to FieldJs(validate = "AFRange_Validate(true, 18, true, 99);")))
        val bad = s.commit("age", "150", mapOf("age" to ""))
        assertTrue(bad.ran); assertFalse(bad.accepted)
        assertEquals(Issue.OutOfRange(18.0, 99.0), bad.issue)
        val ok = s.commit("age", "42", mapOf("age" to ""))
        assertTrue(ok.ran); assertTrue(ok.accepted)
        assertTrue(s.commit("age", "", mapOf("age" to "")).accepted)
    }

    @Test fun regexValidationWithAlert() {
        val js = """
            if (event.value != "" && !/^\d{5}(-\d{4})?$/.test(event.value)) {
                app.alert("Please enter a valid ZIP code (12345 or 12345-6789).");
                event.rc = false;
            }
        """.trimIndent()
        val s = scripts(listOf(text("zip")), mapOf("zip" to FieldJs(validate = js)))
        val r = s.commit("zip", "1234", emptyMap())
        assertFalse(r.accepted)
        assertEquals("Please enter a valid ZIP code (12345 or 12345-6789).", r.alertText)
        assertEquals(Issue.Script("Please enter a valid ZIP code (12345 or 12345-6789)."), r.issue)
        assertTrue(s.commit("zip", "01234", emptyMap()).accepted)      // leading zero kept: event.value is a string
        assertTrue(s.commit("zip", "12345-6789", emptyMap()).accepted)
    }

    @Test fun customValidationUsingOtherFieldsAndObjectAlert() {
        val js = """
            var start = util.scand("dd/mm/yyyy", this.getField("start").value);
            var end = util.scand("dd/mm/yyyy", event.value);
            if (start && end && end < start) {
                app.alert({ cMsg: "End date must be after the start date", cTitle: "Dates", nIcon: 0 });
                event.rc = false;
            }
        """.trimIndent()
        val s = scripts(listOf(text("start", "10/05/2024"), text("end")), mapOf("end" to FieldJs(validate = js)))
        val r = s.commit("end", "01/05/2024", mapOf("start" to "10/05/2024", "end" to ""))
        assertFalse(r.accepted); assertEquals("End date must be after the start date", r.alertText)
        assertTrue(s.commit("end", "20/05/2024", mapOf("start" to "10/05/2024", "end" to "")).accepted)
    }

    @Test fun numberFormatWithCurrencyAndNegativeStyles() {
        val s = scripts(listOf(text("amt"), text("eur"), text("neg")), mapOf(
            "amt" to FieldJs(format = "AFNumber_Format(2, 0, 0, 0, \"$\", true);", keystroke = "AFNumber_Keystroke(2, 0, 0, 0, \"$\", true);"),
            "eur" to FieldJs(format = "AFNumber_Format(2, 2, 0, 0, \" \u20AC\", false);"),
            "neg" to FieldJs(format = "AFNumber_Format(0, 0, 3, 0, \"SAR \", true);"),
        ))
        assertEquals("$1,234.50" to false, s.format("amt", "1234.5"))
        assertEquals("-$0.25" to false, s.format("amt", "-0.25"))
        assertEquals("1.234.567,89 \u20AC" to false, s.format("eur", "1234567.891"))
        assertEquals("(SAR 1,500)" to true, s.format("neg", "-1500"))
        assertEquals("SAR 1,500" to false, s.format("neg", "1500"))
        assertEquals("" to false, s.format("amt", ""))
        // keystroke: letters rejected while typing, a full invalid value rejected on commit
        assertNull(s.keystroke("amt", "12", "x", 2, 2, emptyMap())!!.value)
        assertEquals("12.5", s.keystroke("amt", "12", ".5", 2, 2, emptyMap())!!.value)
        assertEquals("12", s.keystroke("amt", "", "\u0661\u0662", 0, 0, emptyMap())!!.value)
        val c = s.commit("amt", "12a", emptyMap()); assertFalse(c.accepted); assertEquals(Issue.NotNumber, c.issue)
    }

    @Test fun dateKeystrokeAndFormat() {
        val s = scripts(listOf(text("d")), mapOf("d" to FieldJs(keystroke = "AFDate_KeystrokeEx(\"dd/mm/yyyy\");", format = "AFDate_FormatEx(\"dd/mm/yyyy\");")))
        val bad = s.commit("d", "31/02/2024", emptyMap())
        assertFalse(bad.accepted); assertEquals(Issue.NotDate("dd/mm/yyyy"), bad.issue)
        assertTrue(s.commit("d", "29/02/2024", emptyMap()).accepted)
        assertEquals("05/03/2024", s.format("d", "5/3/2024")?.first)
        assertEquals("05/03/2024", s.format("d", "2024-03-05")?.first)      // ISO typed into a d/m/y field
        assertNotNull(s.keystroke("d", "0", "5", 1, 1, emptyMap())!!.value)  // partial input is allowed
    }

    @Test fun keystrokeCanRewriteTheChange() {
        val s = scripts(listOf(text("code")), mapOf("code" to FieldJs(keystroke = "if (!event.willCommit) event.change = event.change.toUpperCase();")))
        assertEquals("ABc", s.keystroke("code", "Ac", "b", 1, 1, emptyMap())!!.value)
    }

    @Test fun calculationChainInCalculationOrder() {
        val fields = listOf(text("qty1", "2"), text("price1", "10"), text("line1"), text("qty2", "3"), text("price2", "2.5"), text("line2"),
            text("subtotal"), text("tax"), text("total"), JsField("vip", "checkbox", "true", onValue = "Yes"))
        val line = { i: Int -> "event.value = this.getField(\"qty$i\").value * this.getField(\"price$i\").value;" }
        val js = mapOf(
            "line1" to FieldJs(calculate = line(1)), "line2" to FieldJs(calculate = line(2)),
            "subtotal" to FieldJs(calculate = "AFSimple_Calculate(\"SUM\", new Array(\"line1\", \"line2\"));"),
            "tax" to FieldJs(calculate = "var s = this.getField('subtotal').value;\nevent.value = s * 0.15;"),
            // discount for VIP customers, hides the tax field when the total is zero
            "total" to FieldJs(calculate = """
                var t = getField("subtotal").value + getField("tax").value;
                if (getField("vip").value == "Yes") t = t * 0.9;
                event.value = util.printf("%.2f", t);
                getField("tax").display = t == 0 ? display.hidden : display.visible;
            """.trimIndent()),
        )
        val s = scripts(fields, js, order = listOf("line1", "line2", "subtotal", "tax", "total"))
        val values = fields.associate { it.name to it.value }
        val out = s.calculate(values)!!
        assertEquals("20", out.changed["line1"]); assertEquals("7.5", out.changed["line2"])
        assertEquals("27.5", out.changed["subtotal"]); assertEquals("4.125", out.changed["tax"])
        assertEquals("28.46", out.changed["total"])
        assertTrue(out.failed.isEmpty())
        // a new quantity flows through the chain; zero hides the tax field
        val zero = s.calculate(values + mapOf("qty1" to "0", "qty2" to "0"))!!
        assertEquals("0.00", zero.changed["total"]); assertEquals(true, s.props["tax"]?.hidden)
    }

    @Test fun documentLevelFunctionsAreAvailable() {
        val doc = listOf("function pct(a, b) { return b == 0 ? 0 : Math.round(a / b * 100); }")
        val s = scripts(listOf(text("a", "1"), text("b", "4"), text("p")), mapOf("p" to FieldJs(calculate = "event.value = pct(getField('a').value, getField('b').value);")), doc = doc)
        assertEquals("25", s.calculate(mapOf("a" to "1", "b" to "4", "p" to ""))!!.changed["p"])
    }

    @Test fun sandboxDeniesJavaAndStopsRunawayScripts() {
        val s = scripts(listOf(text("a"), text("b"), text("c")), mapOf(
            "a" to FieldJs(validate = "java.lang.System.exit(1);"),
            "b" to FieldJs(validate = "while (true) {}"),
            "c" to FieldJs(validate = "event.rc = (typeof Packages === 'undefined' && typeof java === 'undefined');"),
        ), limits = JsLimits(eventMillis = 150, initMillis = 4000))
        val a = s.commit("a", "x", emptyMap()); assertFalse(a.ran); assertTrue(a.accepted)
        val t0 = System.nanoTime()
        val b = s.commit("b", "x", emptyMap()); assertFalse(b.ran); assertTrue(b.accepted)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000)
        assertTrue(s.commit("c", "x", emptyMap()).accepted)
    }

    @Test fun syntaxErrorsAcceptTheValue() {
        val s = scripts(listOf(text("a")), mapOf("a" to FieldJs(validate = "if (event.value > {")))
        val r = s.commit("a", "5", emptyMap()); assertFalse(r.ran); assertTrue(r.accepted)
    }

    @Test fun fieldObjectApi() {
        val fields = listOf(JsField("list", "combobox", "b", items = listOf("Alpha" to "a", "Beta" to "b")), JsField("r", "radiobutton", "", exports = listOf("m", "f")),
            text("out"))
        val js = mapOf("out" to FieldJs(calculate = """
            var l = getField("list");
            var r = getField("r"); r.checkThisBox(1, true);
            event.value = [l.numItems, l.getItemAt(1, false), l.currentValueIndices, r.value, r.isBoxChecked(1), getField("nope") === null].join("|");
        """.trimIndent()))
        val s = scripts(fields, js)
        val out = s.calculate(mapOf("list" to "b", "r" to "", "out" to ""))!!
        assertEquals("2|Beta|1|f|true|true", out.changed["out"])
        assertEquals("f", out.changed["r"])
    }

    @Test fun utilFunctions() {
        val s = scripts(listOf(text("o")), mapOf("o" to FieldJs(calculate = """
            var d = util.scand("yyyy-mm-dd", "2024-01-09");
            event.value = util.printd("dd mmm yyyy", d) + "|" + util.printf("%,0.2f", 1234567.891) + "|" + util.printx("(999) 999-9999", "5551234567");
        """.trimIndent())))
        assertEquals("09 Jan 2024|1,234,567.89|(555) 123-4567", s.calculate(mapOf("o" to ""))!!.changed["o"])
    }
}
