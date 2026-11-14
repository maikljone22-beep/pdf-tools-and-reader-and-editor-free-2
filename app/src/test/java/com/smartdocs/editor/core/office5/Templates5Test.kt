package com.smartdocs.editor.core.office5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The template gallery: every template in both languages is built, saved, and opened again through the engine. */
class Templates5Test {
    @get:Rule val tmp = TemporaryFolder()

    private val both = listOf(true, false)

    private fun file(kind: Templates5.Kind, arabic: Boolean): File =
        File(tmp.root, "${kind.name}-${if (arabic) "ar" else "en"}-${System.nanoTime()}.${kind.ext}").also { Templates5.write(kind, arabic, it) }

    private fun word(kind: Templates5.Kind, arabic: Boolean): WordDoc = WordDoc.open(file(kind, arabic))
    private fun sheet(kind: Templates5.Kind, arabic: Boolean): Workbook5 = XlsxIO5.load(file(kind, arabic))

    private fun hasArabic(s: String) = s.any { it in '؀'..'ۿ' }
    private fun pick(arabic: Boolean, a: String, e: String) = if (arabic) a else e

    private fun paras(d: WordDoc): List<WPara> = WordView.build(d).filterIsInstance<WBlock.Para>().map { it.p }
    private fun headings(d: WordDoc): List<String> = paras(d).filter { it.heading != 0 }.map { it.text }
    private fun tables(d: WordDoc): List<XElem> = d.blocks().filter { it.local == "tbl" }

    /** rows x columns of a table; every row must have the same number of cells. */
    private fun shape(t: XElem): Pair<Int, Int> {
        val rows = WordTables.rows(t)
        val cols = rows.map { WordTables.cells(it).size }.distinct()
        assertEquals("every row has the same cell count", 1, cols.size)
        return rows.size to cols[0]
    }

    private fun cell(t: XElem, r: Int, c: Int): String = WordTables.cellText(WordTables.cells(WordTables.rows(t)[r])[c])

    /** Arabic documents are right-to-left in every paragraph, table and section; English ones nowhere. */
    private fun assertDirection(d: WordDoc, arabic: Boolean) {
        val all = d.allParagraphs()
        assertTrue(all.size > 10)
        for (p in all) assertEquals(WordRuns.text(p), arabic, WordFormat.bidi(p) == true)
        for (p in all) for (r in WordRuns.runs(p)) assertEquals(WordRuns.runText(r), arabic, WordFormat.read(r.child("rPr")).rtl == true)
        for (t in tables(d)) assertEquals(arabic, WordTables.isRtl(t))
        assertEquals(arabic, d.sections().last().child("bidi") != null)
        // the wording is in one language: Arabic text in the Arabic variant, none in the English one
        val words = all.map { WordRuns.text(it) }.filter { t -> t.any { it.isLetter() } }
        if (arabic) assertTrue(words.count { hasArabic(it) } > words.size * 3 / 4) else assertTrue(words.none { hasArabic(it) })
        // a table is never the last block (Word needs a paragraph after it)
        assertEquals("p", d.blocks().last().local)
    }

    // ---------------- packages ----------------

    @Test fun everyTemplateIsAWellFormedPackage() {
        for (kind in Templates5.Kind.values()) for (ar in both) {
            val pkg = OPackage.open(file(kind, ar))
            val tag = "$kind ar=$ar"
            for (n in pkg.names) {
                if (n.endsWith(".xml") || n.endsWith(".rels")) assertNotNull("$tag: $n must parse", pkg.dom(n))
                if (n != OPackage.CT) assertNotNull("$tag: $n needs a content type", pkg.contentTypeOf(n))
            }
            // every internal relationship target exists
            for (n in pkg.names.filter { it.endsWith(".rels") }) {
                val owner = n.replace("_rels/", "").removeSuffix(".rels")
                pkg.rels(owner).filter { !it.external }.forEach { assertTrue("$tag: ${it.target} (from $n)", pkg.has(it.target)) }
            }
            assertTrue(tag, pkg.has(pkg.mainPart("")))
        }
    }

    @Test fun templatesAreRepeatable() {
        // nothing is shared between two builds: the second file of a kind equals the first
        for (kind in listOf(Templates5.Kind.CV, Templates5.Kind.BUDGET, Templates5.Kind.SLIDES)) {
            val a = OPackage.open(file(kind, true)); val b = OPackage.open(file(kind, true))
            assertEquals(a.names, b.names)
            for (n in a.names) assertTrue("$kind $n", a.bytes(n)!!.contentEquals(b.bytes(n)!!))
        }
    }

    // ---------------- Word ----------------

    @Test fun cvHasItsSectionsListsAndLanguageTable() {
        for (ar in both) {
            val d = word(Templates5.Kind.CV, ar)
            assertDirection(d, ar)
            assertEquals(
                if (ar) listOf("الاسم الكامل", "الملخص المهني", "الخبرة العملية", "التعليم", "المهارات", "اللغات", "الدورات والشهادات")
                else listOf("Your Full Name", "Professional Summary", "Work Experience", "Education", "Skills", "Languages", "Courses and Certificates"),
                headings(d),
            )
            val ps = paras(d)
            assertEquals(-1, ps[0].heading); assertEquals(PAlign.CENTER, ps[0].align)
            assertTrue(ps[2].text.contains("name@example.com"))
            // 3 + 2 experience points, 2 skills, 2 courses: all bullets
            val bullets = ps.filter { it.marker != null }
            assertEquals(9, bullets.size)
            assertTrue(bullets.all { it.marker == "•" })
            // the job lines are bold
            val job = ps.first { it.text.startsWith(pick(ar, "المسمى الوظيفي —", "Job Title —")) }
            assertTrue(job.spans.all { it.props.bold == true })
            val t = tables(d).single()
            assertEquals(3 to 2, shape(t))
            assertEquals(pick(ar, "اللغة", "Language"), cell(t, 0, 0))
            assertEquals(pick(ar, "الإنجليزية", "English"), cell(t, 2, 0))
            assertNotNull(WordTables.shading(WordTables.cells(WordTables.rows(t)[0])[1]))
            assertEquals(null, WordTables.shading(WordTables.cells(WordTables.rows(t)[1])[1]))
        }
    }

    @Test fun letterHasSenderSubjectBodyAndSignature() {
        for (ar in both) {
            val d = word(Templates5.Kind.LETTER, ar)
            assertDirection(d, ar)
            assertTrue(tables(d).isEmpty())
            val ps = paras(d)
            assertEquals(pick(ar, "اسم الجهة أو المرسل", "Sender or Organization Name"), ps[0].text)
            assertTrue(ps[0].spans.all { it.props.bold == true })
            val date = ps.first { it.text.startsWith(pick(ar, "التاريخ: ", "Date: ")) }
            assertEquals(listOf(true, false), date.spans.map { it.props.bold == true })   // bold label, plain value
            val subject = ps.first { it.text.startsWith(pick(ar, "الموضوع:", "Subject:")) }
            assertEquals(PAlign.CENTER, subject.align)
            assertTrue(subject.spans.all { it.props.bold == true })
            assertEquals(3, ps.count { it.align == PAlign.JUSTIFY })
            assertTrue(ps.indexOf(subject) < ps.indexOfFirst { it.align == PAlign.JUSTIFY })
            // an Arabic letter is signed at the far side, an English one at the start
            val signature = ps.last()
            assertTrue(signature.text.startsWith(pick(ar, "التوقيع:", "Signature:")))
            assertEquals(if (ar) PAlign.END else null, signature.align)
        }
    }

    @Test fun examHasHeaderBlockNumberedQuestionsAndMarks() {
        for (ar in both) {
            val d = word(Templates5.Kind.EXAM, ar)
            assertDirection(d, ar)
            val (header, marks) = tables(d).also { assertEquals(2, it.size) }
            // the header block comes first: school, subject, date, student name
            assertTrue(d.blocks().first() === header)
            assertEquals(3 to 2, shape(header))
            val fields = (0..2).flatMap { r -> (0..1).map { c -> cell(header, r, c).substringBefore(':') } }
            assertEquals(
                if (ar) listOf("المدرسة", "المادة", "الصف", "التاريخ", "اسم الطالب", "الزمن") else listOf("School", "Subject", "Grade", "Date", "Student name", "Time"),
                fields,
            )
            assertEquals(5, headings(d).size)
            assertEquals(pick(ar, "اختبار نهاية الفصل الدراسي", "End-of-Term Exam"), headings(d)[0])
            // ten questions numbered 1 to 10 through the three parts, each ending with its marks
            val questions = paras(d).filter { it.marker != null }
            assertEquals((1..10).map { "$it." }, questions.map { it.marker })
            assertTrue(questions.all { it.text.endsWith(")") })
            val numId = WordFormat.numbering(questions[0].elem)!!.first
            assertFalse(d.numbering.isBullet(numId, 0))
            assertTrue(questions.all { WordFormat.numbering(it.elem)!!.first == numId })
            // the multiple-choice options sit under their question, indented
            assertEquals(4, paras(d).count { it.indentTwips == 720 && it.marker == null })
            // the marks table adds up: 4 x 2 + 4 x 1 + 2 x 4 = 20
            assertEquals(5 to 3, shape(marks))
            assertEquals(listOf("8", "4", "8"), (1..3).map { cell(marks, it, 1) })
            assertEquals("20", cell(marks, 4, 1))
            assertEquals(20, (1..3).sumOf { cell(marks, it, 1).toInt() })
            assertEquals(pick(ar, "المجموع", "Total"), cell(marks, 4, 0))
        }
    }

    @Test fun minutesHaveInfoTableAgendaAndActionItems() {
        for (ar in both) {
            val d = word(Templates5.Kind.MINUTES, ar)
            assertDirection(d, ar)
            assertEquals(
                if (ar) listOf("محضر اجتماع", "الحاضرون", "المعتذرون", "جدول الأعمال", "المناقشات والقرارات", "البند الأول", "البند الثاني", "المهام والتكليفات", "الاجتماع القادم")
                else listOf("Meeting Minutes", "Attendees", "Apologies", "Agenda", "Discussion and Decisions", "Item One", "Item Two", "Action Items", "Next Meeting"),
                headings(d),
            )
            val (info, actions) = tables(d).also { assertEquals(2, it.size) }
            assertEquals(5 to 2, shape(info))
            assertEquals(pick(ar, "رئيس الاجتماع", "Chairperson"), cell(info, 3, 0))
            assertTrue((0..4).all { cell(info, it, 1).isEmpty() })
            assertNotNull(WordTables.shading(WordTables.cells(WordTables.rows(info)[2])[0]))   // the label column is shaded
            assertEquals(4 to 4, shape(actions))
            assertEquals(if (ar) listOf("المهمة", "المسؤول", "موعد الإنجاز", "الحالة") else listOf("Task", "Owner", "Due date", "Status"), (0..3).map { cell(actions, 0, it) })
            // attendees and apologies are bullets, the agenda is numbered
            val marked = paras(d).filter { it.marker != null }
            assertEquals(listOf("•", "•", "•", "•", "1.", "2.", "3."), marked.map { it.marker })
        }
    }

    // ---------------- Excel ----------------

    private fun num(w: Workbook5, a1: String): Double {
        val ref = A1.parseCell(a1)!!
        return (w.book.value(w.book.sheets[0], ref.row, ref.col) as FV.Num).v
    }

    private fun shown(w: Workbook5, a1: String): String = A1.parseCell(a1)!!.let { w.book.display(w.book.sheets[0], it.row, it.col).text }
    private fun formula(w: Workbook5, a1: String): String? = A1.parseCell(a1)!!.let { w.book.sheets[0].cell(it.row, it.col)?.formula }

    @Test fun invoiceMultipliesSumsAndTaxes() {
        for (ar in both) {
            val w = sheet(Templates5.Kind.INVOICE, ar)
            val s = w.book.sheets.single()
            assertEquals(ar, s.rtl)
            assertEquals(pick(ar, "الفاتورة", "Invoice"), s.name)
            assertEquals(pick(ar, "فاتورة", "INVOICE"), shown(w, "A1"))
            assertTrue(CRange(0, 0, 0, 4) in s.merges)
            assertEquals(if (ar) listOf("م", "الوصف", "الكمية", "سعر الوحدة", "الإجمالي") else listOf("#", "Description", "Qty", "Unit price", "Total"), (0..4).map { w.book.display(s, 10, it).text })
            assertTrue(w.format(s, 10, 0).bold); assertEquals("1F3864", w.format(s, 10, 0).fill)
            // eight item rows, each quantity x unit price
            for (r in 12..19) assertEquals("C$r*D$r", formula(w, "E$r"))
            assertEquals(listOf(300.0, 400.0, 100.0, 0.0), listOf("E12", "E13", "E14", "E15").map { num(w, it) })
            assertEquals("SUM(E12:E19)", formula(w, "E20"))
            assertEquals(800.0, num(w, "E20"), 1e-9)
            assertEquals(0.15, num(w, "E21"), 1e-9); assertEquals("15%", shown(w, "E21"))
            assertEquals(120.0, num(w, "E22"), 1e-9)
            assertEquals(920.0, num(w, "E23"), 1e-9)
            // currency format; the total of an empty row shows nothing
            assertEquals(pick(ar, "150.00 ر.س", "$150.00"), shown(w, "D12"))
            assertEquals(pick(ar, "920.00 ر.س", "$920.00"), shown(w, "E23"))
            assertEquals("", shown(w, "E19"))
            // the due date is 30 days after the invoice date
            assertEquals(num(w, "E4") + 30, num(w, "E5"), 1e-9)
            assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(shown(w, "E5")))
            // the cached results are in the file for other apps
            assertTrue(String(w.pkg!!.bytes(s.partPath!!)!!).contains("<f>E20+E22</f><v>920</v>"))
            // a new line item flows through to the total
            w.setInput(s, 14, 1, "x"); w.setInput(s, 14, 2, "3"); w.setInput(s, 14, 3, "100")
            assertEquals(300.0, num(w, "E15"), 1e-9)
            assertEquals(1100.0, num(w, "E20"), 1e-9)
            assertEquals(1265.0, num(w, "E23"), 1e-9)
            assertEquals(pick(ar, "1,265.00 ر.س", "$1,265.00"), shown(w, "E23"))
        }
    }

    @Test fun invoiceCarriesTheGivenDate() {
        val w = Templates5.invoice(arabic = false, today = java.time.LocalDate.of(2026, 3, 10))
        assertEquals("2026-03-10", shown(w, "E4"))
        assertEquals("2026-04-09", shown(w, "E5"))
    }

    @Test fun budgetTotalsAndDifferences() {
        for (ar in both) {
            val w = sheet(Templates5.Kind.BUDGET, ar)
            val s = w.book.sheets.single()
            assertEquals(ar, s.rtl)
            assertEquals(pick(ar, "الميزانية الشهرية", "Monthly Budget"), shown(w, "A1"))
            assertEquals(if (ar) listOf("الدخل", "المخطط", "الفعلي", "الفرق") else listOf("Income", "Planned", "Actual", "Difference"), listOf("A4", "B4", "C4", "D4").map { shown(w, it) })
            assertEquals(pick(ar, "المصروفات", "Expenses"), shown(w, "A10"))
            // income: three rows and their total
            assertEquals("SUM(B5:B7)", formula(w, "B8")); assertEquals("SUM(C5:C7)", formula(w, "C8"))
            assertEquals(6000.0, num(w, "B8"), 1e-9); assertEquals(5800.0, num(w, "C8"), 1e-9); assertEquals(-200.0, num(w, "D8"), 1e-9)
            assertEquals(-150.0, num(w, "D6"), 1e-9)
            // expenses: nine rows and their total
            assertEquals("SUM(B11:B19)", formula(w, "B20")); assertEquals("SUM(C11:C19)", formula(w, "C20"))
            assertEquals(5300.0, num(w, "B20"), 1e-9); assertEquals(5550.0, num(w, "C20"), 1e-9); assertEquals(-250.0, num(w, "D20"), 1e-9)
            assertEquals(20.0, num(w, "D14"), 1e-9)   // transport: 400 planned, 380 spent
            // what remains: income minus expenses, and its difference from the plan
            assertEquals("B8-B20", formula(w, "B22")); assertEquals("C8-C20", formula(w, "C22"))
            assertEquals(700.0, num(w, "B22"), 1e-9); assertEquals(250.0, num(w, "C22"), 1e-9); assertEquals(-450.0, num(w, "D22"), 1e-9)
            assertEquals(pick(ar, "5,000.00 ر.س", "$5,000.00"), shown(w, "B5"))
            // a negative difference is shown in red
            val d = w.book.display(s, 21, 3)
            assertEquals(pick(ar, "-450.00 ر.س", "-$450.00"), d.text); assertEquals("FF0000", d.color)
            assertEquals(null, w.book.display(s, 13, 3).color)
            // changing an amount updates the totals
            w.setInput(s, 4, 2, "6000")
            assertEquals(6800.0, num(w, "C8"), 1e-9)
            assertEquals(1250.0, num(w, "C22"), 1e-9)
        }
    }

    // ---------------- PowerPoint ----------------

    @Test fun deckHasFiveSlidesWithTitlesAndBullets() {
        for (ar in both) {
            val d = PptDoc.open(file(Templates5.Kind.SLIDES, ar))
            assertEquals(5, d.slideCount)
            assertEquals(if (ar) "1" else null, d.pres.root.attr("rtl"))
            val slides = (0 until 5).map { PptView.build(d, it) }
            fun title(i: Int) = slides[i].shapes.single { it.placeholder == "title" }
            fun body(i: Int) = slides[i].shapes.single { it.placeholder == "body" }
            assertEquals(
                if (ar) listOf("عنوان العرض التقديمي", "محاور العرض", "المحور الأول: الفكرة الرئيسة", "المحور الثاني: النتائج والتوصيات", "شكراً لحسن استماعكم")
                else listOf("Presentation Title", "Agenda", "Part One: The Main Idea", "Part Two: Results and Recommendations", "Thank You"),
                (0 until 5).map { title(it).paragraphs.single().text },
            )
            // every text paragraph carries the direction of its language
            for (i in 0 until 5) for (sh in listOf(title(i), body(i))) {
                assertTrue(sh.paragraphs.isNotEmpty())
                for (p in sh.paragraphs) { assertEquals(ar, p.rtl); assertEquals(ar, hasArabic(p.text)) }
            }
            // opening and closing slides: dark background, centred white title, a subtitle without bullets
            for (i in listOf(0, 4)) {
                assertEquals("1F3864", slides[i].background)
                assertEquals("ctr", title(i).paragraphs[0].align); assertEquals("FFFFFF", title(i).paragraphs[0].runs[0].color)
                assertEquals(1, body(i).paragraphs.size); assertEquals(null, body(i).paragraphs[0].bullet)
                assertTrue(title(i).y < body(i).y)
            }
            // agenda and content slides: white background, bullet points aligned to the start
            assertEquals(listOf(4, 3, 3), (1..3).map { body(it).paragraphs.size })
            for (i in 1..3) {
                assertEquals("FFFFFF", slides[i].background)
                assertTrue(body(i).paragraphs.all { it.bullet == "•" && it.align == (if (ar) "r" else "l") })
                assertTrue(title(i).paragraphs[0].runs[0].bold)
                assertEquals(24f, body(i).paragraphs[0].runs[0].sizePt)
            }
            assertTrue((0 until 5).all { d.transition(it) == "fade" })
            // the deck stays editable: a new slide after the last one takes the layout's placeholders
            assertEquals(5, d.change { d.newSlide(4) })
            assertEquals(6, d.slideCount)
        }
    }
}
