package com.smartdocs.editor.core.tools5

import com.smartdocs.editor.core.redact.SanitizeItem
import com.smartdocs.editor.core.search.PatternKind
import com.smartdocs.editor.core.view5.DocPrintPrefs
import com.smartdocs.editor.core.view5.PrintContent
import com.smartdocs.editor.core.view5.PrintLayout
import com.smartdocs.editor.core.view5.PrintSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavBatchTest {

    @Test fun recipeJsonKeepsNewFields() {
        val r = Recipe(
            "r9", "توزيع", listOf(Step(StepType.JAVASCRIPT, mapOf("code" to "this.info.Title='x';"), ask = true), Step(StepType.BATES)),
            OutputOptions("{name}", conflict = Conflict.ASK, folderUri = "content://tree/x", folderName = "Out"), description = "وصف",
        )
        assertEquals(r, RecipeJson.fromJson(RecipeJson.toJson(r)))
        // an older file: the overwrite switch seeds the conflict choice
        val old = RecipeJson.fromJson("""{"id":"a","name":"n","output":{"pattern":"{name}","overwrite":true},"steps":[{"type":"OCR"}]}""")
        assertEquals(Conflict.OVERWRITE, old.output.conflict)
        assertEquals("", old.description)
        assertFalse(old.steps[0].ask)
    }

    @Test fun cloneAndMove() {
        val a = Recipe("a", "A", emptyList()); val b = Recipe("b", "B", emptyList()); val c = Recipe("c", "C", emptyList())
        assertEquals(listOf("b", "a", "c"), RecipeOps.move(listOf(a, b, c), "b", -1).map { it.id })
        assertEquals(listOf("a", "b", "c"), RecipeOps.move(listOf(a, b, c), "a", -1).map { it.id })
        assertEquals(listOf("a", "c", "b"), RecipeOps.move(listOf(a, b, c), "b", 5).map { it.id })
        val copy = RecipeOps.clone(BuiltInRecipes.all.first(), "x")
        assertFalse(copy.builtIn); assertEquals("x", copy.name); assertTrue(copy.id != BuiltInRecipes.all.first().id)
        assertEquals(BuiltInRecipes.all.first().steps, copy.steps)
    }

    @Test fun nestedRecipesExpandWithoutCycles() {
        val inner = Recipe("in", "Inner", listOf(Step(StepType.OCR), Step(StepType.RUN_RECIPE, mapOf("recipe" to "out"))))
        val outer = Recipe("out", "Outer", listOf(Step(StepType.RUN_RECIPE, mapOf("recipe" to "Inner")), Step(StepType.OPTIMIZE), Step(StepType.PDFA, enabled = false)))
        val all = listOf(inner, outer)
        val steps = RecipeOps.expand(outer) { k -> all.firstOrNull { it.id == k || it.name.equals(k, true) } }
        assertEquals(listOf(StepType.OCR, StepType.OPTIMIZE), steps.map { it.type })
    }

    @Test fun askedStepsAndPairs() {
        val steps = listOf(Step(StepType.OCR), Step(StepType.PROTECT, ask = true), Step(StepType.NOTE, mapOf("text" to "اقرأ")), Step(StepType.NOTE), Step(StepType.PRINT, ask = true))
        assertEquals(listOf(1, 2), RecipeOps.asked(steps))
        assertEquals(mapOf("Dept" to "HR", "Code" to "a=b", "Flag" to ""), RecipeOps.parsePairs("Dept = HR\nCode=a=b; Flag\n =x"))
        assertEquals(listOf("a", "b", "ج"), RecipeOps.list("a, b،ج\n"))
    }

    @Test fun outputNamesAndConflicts() {
        val taken = setOf("a.pdf", "a_2.pdf")
        assertEquals("b.pdf", RecipeOps.resolveName("b", "pdf", Conflict.SKIP) { it in taken })
        assertEquals("a_3.pdf", RecipeOps.resolveName("a", "pdf", Conflict.RENAME) { it in taken })
        assertEquals("a.pdf", RecipeOps.resolveName("a", "pdf", Conflict.OVERWRITE) { it in taken })
        assertNull(RecipeOps.resolveName("a", "pdf", Conflict.SKIP) { it in taken })
        assertEquals("docx", RecipeOps.outputExt(listOf(Step(StepType.OCR), Step(StepType.CONVERT_OFFICE, mapOf("format" to "DOCX")))))
        assertEquals("pdf", RecipeOps.outputExt(listOf(Step(StepType.CONVERT_OFFICE, enabled = false))))
    }

    @Test fun stepOptionParsing() {
        val req = BatchSteps.redactRequest(Step(StepType.REDACT, mapOf("terms" to "سري, Secret", "patterns" to "email,iban,bogus", "regex" to "\\d{4}\n")))
        assertEquals(listOf("سري", "Secret"), req.words)
        assertEquals(setOf(PatternKind.EMAIL, PatternKind.IBAN), req.patterns)
        assertEquals(listOf("\\d{4}"), req.regexes)
        assertTrue(BatchSteps.redactRequest(Step(StepType.REDACT)).isEmpty)
        assertEquals(SanitizeItem.DEFAULTS, BatchSteps.sanitizeItems(""))
        assertEquals(SanitizeItem.entries.toSet(), BatchSteps.sanitizeItems("all"))
        assertEquals(setOf(SanitizeItem.METADATA, SanitizeItem.COMMENTS), BatchSteps.sanitizeItems("metadata,comments"))
        assertEquals("pdf_docx", BatchSteps.conversionId(".DOCX"))
        assertNull(BatchSteps.conversionId("exe"))
    }

    @Test fun pdfUaXmp() {
        val fresh = BatchSteps.withUaPart(null, "A & B")
        assertTrue(fresh.contains("<pdfuaid:part>1</pdfuaid:part>")); assertTrue(fresh.contains("A &amp; B")); assertFalse(fresh.contains("pdfaid:part"))
        val existing = "<x:xmpmeta><rdf:RDF><rdf:Description pdfaid:part=\"2\"/></rdf:RDF></x:xmpmeta>"
        val merged = BatchSteps.withUaPart(existing, "t")
        assertTrue(merged.contains("pdfaid:part=\"2\"")); assertTrue(merged.indexOf("pdfuaid:part") < merged.indexOf("</rdf:RDF>"))
        assertEquals(merged, BatchSteps.withUaPart(merged, "t"))
    }

    @Test fun javaScriptStep() {
        val d = JsBatch.DocData("a.pdf", 3, mapOf("Title" to "Old"), mapOf("name" to "Ali", "n" to "1"))
        val r = JsBatch.run(
            """
            this.title = this.documentFileName + ' / ' + this.numPages;
            this.info.Dept = 'HR';
            var f = this.getField('n'); f.value = Number(f.value) + 1;
            console.println(this.numFields + ' ' + this.getNthFieldName(0));
            app.alert({ cMsg: 'done' });
            """.trimIndent(), d,
        )
        assertEquals("a.pdf / 3", r.info["Title"]); assertEquals("HR", r.info["Dept"])
        assertEquals(mapOf("n" to "2"), JsBatch.changed(d.fields, r.fields))
        assertEquals(listOf("2 name", "done"), r.log)
        val err = runCatching { JsBatch.run("this.nope(", d) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException)
        // no Java access from the sandbox
        assertTrue(runCatching { JsBatch.run("java.lang.System.exit(0)", d) }.isFailure)
        // a runaway script is stopped by the time limit
        assertTrue(runCatching { JsBatch.run("while (true) {}", d, millis = 200) }.isFailure)
    }

    @Test fun printScaleAndPoster() {
        val (s, rot) = PrintLayout.fitScale(842f, 595f, 595f, 842f, PrintLayout.Scale.FIT)
        assertTrue(rot); assertEquals(1f, s, 1e-3f)
        assertEquals(1f, PrintLayout.fitScale(300f, 400f, 595f, 842f, PrintLayout.Scale.SHRINK).first, 1e-4f)
        assertEquals(0.5f, PrintLayout.fitScale(300f, 400f, 595f, 842f, PrintLayout.Scale.CUSTOM, 50).first, 1e-4f)
        assertFalse(PrintLayout.fitScale(842f, 595f, 595f, 842f, PrintLayout.Scale.ACTUAL, autoRotate = false).second)
        val tiles = PrintLayout.tiles(595f, 842f, 595f, 842f, 200, overlap = 0f)
        assertEquals(4, tiles.size)
        assertEquals(0f, tiles[0].x, 1e-3f); assertEquals(421f, tiles[0].y, 1e-3f)       // first tile: top left quarter
        assertEquals(1, PrintLayout.tiles(100f, 100f, 595f, 842f, 100).size)
    }

    @Test fun bookletSidesSectionsAndLabels() {
        val sides = PrintLayout.booklet((0 until 8).toList())          // 2 sheets → 4 sides
        assertEquals(listOf(sides[0], sides[2]), PrintLayout.bookletSides(sides, PrintLayout.Sides.FRONT))
        assertEquals(listOf(sides[3]), PrintLayout.bookletSides(sides, PrintLayout.Sides.BACK, "2"))
        val levels = listOf(0, 1, 1, 0); val pages = listOf(0, 1, 4, 7)
        assertEquals(0..6, PrintLayout.section(levels, pages, 0, 10))
        assertEquals(1..3, PrintLayout.section(levels, pages, 1, 10))
        assertEquals(7..9, PrintLayout.section(levels, pages, 3, 10))
        val labels = listOf("i", "ii", "iii", "1", "2", "A-1")
        assertEquals("1-3,6", PrintLayout.labelRange("i-iii, A-1", labels))
        assertEquals("2-", PrintLayout.labelRange("ii-", labels))
        val cells = PrintLayout.place(listOf(0, 1, 2), { 100f to 100f }, 0, 300f, 300f, margin = 0f, gap = 0f, cols0 = 2, rows0 = 2, vertical = true)
        assertTrue(cells[1].y < cells[0].y && cells[1].x == cells[0].x)                   // second page below the first
    }

    @Test fun printPresetsAndDocPrefs() {
        val s = PrintSettings(content = PrintContent.FORM_FIELDS, poster = true, posterZoom = 300, paper = "A3", grayscale = true, sides = PrintLayout.Sides.BACK, margin = 9f)
        assertEquals(s, PrintSettings.fromJson(s.toJson()))
        assertNull(PrintSettings.fromJson("{bad"))
        assertEquals("1-3,6", DocPrintPrefs.rangeSpec(listOf(0, 2, 5, 5, 9)))
        assertEquals(2, DocPrintPrefs.duplexMode("DuplexFlipLongEdge"))
        assertNull(DocPrintPrefs.duplexMode(null))
    }
}
