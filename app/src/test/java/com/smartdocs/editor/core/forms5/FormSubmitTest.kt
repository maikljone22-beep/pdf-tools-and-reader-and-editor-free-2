package com.smartdocs.editor.core.forms5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormSubmitTest {

    @Test fun flags() {
        assertEquals(SubmitFormat.FDF, FormSubmit.formatOf(0))
        assertEquals(SubmitFormat.HTML, FormSubmit.formatOf(4))
        assertEquals(SubmitFormat.XFDF, FormSubmit.formatOf(32))
        assertEquals(SubmitFormat.PDF, FormSubmit.formatOf(256 or 4))
        val a = FormSubmit.fromFlags("https://x.example/s", 4 or 8 or 2, listOf("a"))
        assertTrue(a.get); assertTrue(a.includeNoValue); assertEquals(false, a.exclude)
    }

    @Test fun selection() {
        val values = linkedMapOf("a" to "1", "b.c" to "2", "b.d" to "", "e" to "3")
        assertEquals(listOf("a", "b.c", "e"), FormSubmit.select(values, FormSubmit.fromFlags("u", 0, emptyList())).keys.toList())
        assertEquals(listOf("b.c", "b.d"), FormSubmit.select(values, FormSubmit.fromFlags("u", 2, listOf("b"))).keys.toList())
        assertEquals(listOf("a", "e"), FormSubmit.select(values, FormSubmit.fromFlags("u", 1, listOf("b"))).keys.toList())
    }

    @Test fun bodies() {
        assertEquals("name=Ali+Hassan&city=%D8%AC%D8%AF%D8%A9", FormSubmit.htmlBody(linkedMapOf("name" to "Ali Hassan", "city" to "جدة")))
        val x = FormSubmit.xfdf(mapOf("a<b" to "x&y"), "f.pdf")
        assertTrue(x.contains("<field name=\"a&lt;b\"><value>x&amp;y</value></field>"))
        val fdf = String(FormSubmit.fdf(linkedMapOf("n" to "A(1)", "ar" to "ب"), "f.pdf"), Charsets.ISO_8859_1)
        assertTrue(fdf.startsWith("%FDF-1.2"))
        assertTrue(fdf.contains("/T (n) /V (A\\(1\\))"))
        assertTrue(fdf.contains("/V <FEFF0628>"))
    }

    @Test fun javascriptActions() {
        val s = FormSubmit.parseJs("this.submitForm({cURL: \"https://forms.example/in\", cSubmitAs: \"XFDF\"});") as ButtonAction.Submit
        assertEquals("https://forms.example/in", s.url); assertEquals(SubmitFormat.XFDF, s.format)
        val m = FormSubmit.parseJs("this.submitForm('mailto:hr@example.com?subject=Form')") as ButtonAction.Submit
        assertTrue(FormSubmit.isMail(m.url))
        assertEquals(ButtonAction.Reset(emptyList(), false), FormSubmit.parseJs("this.resetForm();"))
        assertEquals(ButtonAction.ImportImage, FormSubmit.parseJs("event.target.buttonImportIcon();"))
        assertEquals(ButtonAction.OpenUri("https://a.example"), FormSubmit.parseJs("app.launchURL('https://a.example', true);"))
        assertEquals(ButtonAction.Print, FormSubmit.parseJs("this.print();"))
        assertNull(FormSubmit.parseJs("var a = 1;"))
        assertEquals("forms.example", FormSubmit.host("https://forms.example/in?x=1"))
    }
}
