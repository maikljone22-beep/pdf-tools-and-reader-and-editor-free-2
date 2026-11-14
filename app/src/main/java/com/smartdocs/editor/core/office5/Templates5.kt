package com.smartdocs.editor.core.office5

import java.io.File

/**
 * Ready-made documents for the template gallery of the start screen, built through the editors' own engine (no
 * binary template files): four Word documents, two workbooks and a short deck, each in Arabic (right-to-left) or
 * English (left-to-right). The wording is guidance the user replaces; the workbooks carry sample numbers so that
 * their formulas show results.
 */
object Templates5 {
    /** The templates and the extension of the file each one makes. */
    enum class Kind(val ext: String) { CV("docx"), LETTER("docx"), EXAM("docx"), MINUTES("docx"), INVOICE("xlsx"), BUDGET("xlsx"), SLIDES("pptx") }

    private const val NAVY = "1F3864"
    private const val TINT = "D9E2F3"
    private const val GOLD = "FFC000"
    private const val GRAY = "595959"

    /** Builds template [kind] in Arabic or English and saves it to [target]. */
    fun write(kind: Kind, arabic: Boolean, target: File) {
        when (kind) {
            Kind.CV -> cv(arabic).saveTo(target)
            Kind.LETTER -> letter(arabic).saveTo(target)
            Kind.EXAM -> exam(arabic).saveTo(target)
            Kind.MINUTES -> minutes(arabic).saveTo(target)
            Kind.INVOICE -> XlsxIO5.save(invoice(arabic), target)
            Kind.BUDGET -> XlsxIO5.save(budget(arabic), target)
            Kind.SLIDES -> slides(arabic).saveTo(target)
        }
    }

    // ================= Word =================

    private enum class Head { NONE, ROW, COLUMN }

    /** Appends blocks to a blank document; every paragraph and table follows the document direction. */
    private class Docx(val ar: Boolean) {
        val doc: WordDoc = WordDoc.blank(listOf(""), ar)
        private val initial: XElem = doc.blocks().first()

        init { doc.pkg.edit(doc.docPath) }

        fun s(a: String, e: String) = if (ar) a else e

        fun para(text: String = "", style: WordParts.StyleKind = WordParts.StyleKind.NORMAL, align: PAlign? = null, bold: Boolean = false, size: Float? = null, color: String? = null): XElem {
            val p = doc.insertParagraphAfter(null, text, like = null)
            if (style != WordParts.StyleKind.NORMAL) doc.applyStyle(p, style)
            if (ar) WordFormat.setBidi(p, true)
            if (align != null) WordFormat.setAlign(p, align)
            if (bold || size != null || color != null) WordRuns.formatRange(p, 0, text.length) { rp ->
                if (bold) WordFormat.setBold(rp, true)
                size?.let { WordFormat.setSize(rp, it) }
                color?.let { WordFormat.setColor(rp, it) }
            }
            return p
        }

        fun h1(text: String) = para(text, WordParts.StyleKind.HEADING1)
        fun h2(text: String) = para(text, WordParts.StyleKind.HEADING2)

        /** A section heading with a thin line under it. */
        fun section(text: String): XElem = h1(text).also { p ->
            val w = doc.w
            WordRuns.pPr(p).setOrdered(XDom.elem("<$w:pBdr><$w:bottom $w:val=\"single\" $w:sz=\"6\" $w:space=\"1\" $w:color=\"$NAVY\"/></$w:pBdr>"), WordRuns.PPR_ORDER)
        }

        /** A paragraph whose leading [label] is bold. */
        fun labeled(label: String, rest: String): XElem =
            para(label + rest).also { p -> WordRuns.formatRange(p, 0, label.length) { WordFormat.setBold(it, true) } }

        fun list(items: List<String>, bullet: Boolean): List<XElem> = items.map { para(it) }.also { doc.toggleList(it, bullet) }

        /** A bordered table of [rows]; the [head] row or column is shaded and bold. */
        fun table(rows: List<List<String>>, head: Head): XElem {
            val t = XDom.elem(WordParts.tableXml(doc.w, rows.size, rows[0].size, (doc.contentWidthEmu() / 635).toInt(), ar))
            val b = doc.body
            b.add(b.kids.indexOfLast { it is XElem && it.local == "sectPr" }.let { if (it < 0) b.kids.size else it }, t)
            WordTables.rows(t).forEachIndexed { r, tr ->
                WordTables.cells(tr).forEachIndexed { c, tc ->
                    val text = rows[r][c]
                    WordTables.setCellText(tc, text)
                    val p = WordTables.cellParagraphs(tc).first()
                    if (ar) WordFormat.setBidi(p, true)
                    if ((head == Head.ROW && r == 0) || (head == Head.COLUMN && c == 0)) {
                        WordTables.setShading(tc, TINT)
                        WordRuns.formatRange(p, 0, text.length) { WordFormat.setBold(it, true) }
                    }
                }
            }
            return t
        }

        /** The finished package, without the blank document's own empty first paragraph. */
        fun done(): OPackage { doc.deleteBlock(initial); return doc.pkg }
    }

    fun cv(arabic: Boolean): OPackage = Docx(arabic).run {
        para(s("الاسم الكامل", "Your Full Name"), WordParts.StyleKind.TITLE, PAlign.CENTER)
        para(s("المسمى الوظيفي", "Job Title"), align = PAlign.CENTER, size = 14f, color = GRAY)
        para(s("الهاتف: 0500000000  |  البريد الإلكتروني: name@example.com  |  المدينة، الدولة", "Phone: 000-000-0000  |  Email: name@example.com  |  City, Country"), align = PAlign.CENTER)

        section(s("الملخص المهني", "Professional Summary"))
        para(s("اكتب هنا نبذة موجزة من سطرين أو ثلاثة تعرّف بك، وتبرز سنوات خبرتك وأهم مهاراتك وما تطمح إليه في عملك القادم.",
            "Write two or three lines that introduce you: your years of experience, your strongest skills and what you are looking for in your next role."))

        section(s("الخبرة العملية", "Work Experience"))
        val dates = s("المدينة | من شهر وسنة البداية إلى شهر وسنة النهاية", "City | start month and year – end month and year")
        para(s("المسمى الوظيفي — اسم جهة العمل", "Job Title — Employer Name"), bold = true)
        para(dates, color = GRAY)
        list(listOf(
            s("ابدأ كل نقطة بفعل يصف إنجازاً محدداً، وادعمه بالأرقام كلما أمكن.", "Start each point with a verb that describes a specific achievement, and back it with numbers where you can."),
            s("اذكر أهم المهام التي توليتها والنتائج التي حققتها.", "List the main duties you handled and the results you delivered."),
            s("أضف مشروعاً أو مبادرة شاركت فيها وبيّن أثرها في العمل.", "Add a project or initiative you took part in and the difference it made."),
        ), bullet = true)
        para(s("المسمى الوظيفي السابق — اسم جهة العمل", "Previous Job Title — Employer Name"), bold = true)
        para(dates, color = GRAY)
        list(listOf(
            s("اذكر أبرز مسؤولياتك في هذه الوظيفة.", "Describe your main responsibilities in this role."),
            s("أضف إنجازاً يوضّح ما قدّمته لفريق العمل.", "Add an achievement that shows what you brought to the team."),
        ), bullet = true)

        section(s("التعليم", "Education"))
        para(s("الدرجة العلمية والتخصص — اسم الجامعة أو المعهد", "Degree and Major — University or Institute"), bold = true)
        para(s("سنة التخرج | التقدير أو المعدل (اختياري)", "Graduation year | Grade or GPA (optional)"), color = GRAY)

        section(s("المهارات", "Skills"))
        list(listOf(
            s("المهارات التقنية: اذكر البرامج والأدوات التي تتقنها.", "Technical skills: the software and tools you use well."),
            s("المهارات الشخصية: العمل ضمن فريق، وتنظيم الوقت، وحل المشكلات.", "Personal skills: teamwork, time management, problem solving."),
        ), bullet = true)

        section(s("اللغات", "Languages"))
        table(listOf(
            listOf(s("اللغة", "Language"), s("مستوى الإتقان", "Proficiency")),
            listOf(s("العربية", "Arabic"), s("اللغة الأم", "Native")),
            listOf(s("الإنجليزية", "English"), s("جيد جداً", "Very good")),
        ), Head.ROW)

        section(s("الدورات والشهادات", "Courses and Certificates"))
        list(listOf(
            s("اسم الدورة أو الشهادة — الجهة المانحة، السنة", "Course or certificate name — Issuer, Year"),
            s("دورة أو شهادة أخرى — الجهة المانحة، السنة", "Another course or certificate — Issuer, Year"),
        ), bullet = true)
        done()
    }

    fun letter(arabic: Boolean): OPackage = Docx(arabic).run {
        para(s("اسم الجهة أو المرسل", "Sender or Organization Name"), bold = true, size = 16f, color = NAVY)
        para(s("العنوان: المدينة، الشارع", "Address: Street, City"))
        para(s("الهاتف: 0500000000  |  البريد الإلكتروني: name@example.com", "Phone: 000-000-0000  |  Email: name@example.com"))
        para()
        labeled(s("الرقم: ", "Ref: "), "............")
        labeled(s("التاريخ: ", "Date: "), ".... / .... / ........")
        para()
        para(s("السيد/ ................................ المحترم", "To: Mr./Ms. ................................"), bold = true)
        para(s("المسمى الوظيفي — اسم الجهة", "Job Title — Organization"))
        para(s("السلام عليكم ورحمة الله وبركاته، وبعد:", "Dear Sir or Madam,"))
        para(s("الموضوع: اكتب موضوع الخطاب هنا", "Subject: Write the subject of the letter here"), align = PAlign.CENTER, bold = true)
        para(s("اكتب في هذه الفقرة سبب الخطاب بإيجاز ووضوح، وأشر إلى أي مراسلات أو اتفاقات سابقة تتصل بالموضوع.",
            "Use this paragraph to say briefly and clearly why you are writing, and mention any earlier letters or agreements on the matter."), align = PAlign.JUSTIFY)
        para(s("اعرض في هذه الفقرة التفاصيل التي يحتاج إليها المستلم: الوقائع والتواريخ والأرقام، وما تطلبه منه على وجه التحديد.",
            "Use this paragraph for the details the recipient needs: the facts, dates and figures, and exactly what you are asking for."), align = PAlign.JUSTIFY)
        para(s("اختم ببيان الإجراء المنتظر وموعده، ووسيلة التواصل معك عند الحاجة إلى أي إيضاح.",
            "Close with the action you expect and by when, and how to reach you if anything needs clarifying."), align = PAlign.JUSTIFY)
        // Arabic letters centre the closing and sign at the far side; English ones keep both at the start
        para(s("وتفضلوا بقبول فائق الاحترام والتقدير،", "Yours faithfully,"), align = if (ar) PAlign.CENTER else null)
        para()
        val sign = if (ar) PAlign.END else null
        para(s("الاسم الكامل", "Full Name"), align = sign, bold = true)
        para(s("المسمى الوظيفي", "Job Title"), align = sign)
        para(s("التوقيع: ....................", "Signature: ...................."), align = sign)
        done()
    }

    fun exam(arabic: Boolean): OPackage = Docx(arabic).run {
        table(listOf(
            listOf(s("المدرسة: ........................", "School: ........................"), s("المادة: ........................", "Subject: ........................")),
            listOf(s("الصف: ........................", "Grade: ........................"), s("التاريخ: .... / .... / ........", "Date: .... / .... / ........")),
            listOf(s("اسم الطالب: ........................", "Student name: ........................"), s("الزمن: 45 دقيقة", "Time: 45 minutes")),
        ), Head.NONE)
        para(s("اختبار نهاية الفصل الدراسي", "End-of-Term Exam"), WordParts.StyleKind.TITLE, PAlign.CENTER)
        labeled(s("التعليمات: ", "Instructions: "), s("أجب عن جميع الأسئلة. الدرجة الكلية 20 درجة.", "Answer all questions. Total marks: 20."))

        // one numbered list runs through the three parts: questions 1 to 10, each with its marks
        val questions = ArrayList<XElem>()
        h2(s("أولاً: اختر الإجابة الصحيحة", "Part One: Choose the correct answer"))
        repeat(4) {
            questions += para(s("اكتب نص السؤال هنا. (درجتان)", "Write the question here. (2 marks)"))
            WordFormat.setIndent(para(s("أ) الخيار الأول    ب) الخيار الثاني    ج) الخيار الثالث    د) الخيار الرابع", "a) First option    b) Second option    c) Third option    d) Fourth option")), 720)
        }
        h2(s("ثانياً: اكتب (صح) أمام العبارة الصحيحة و(خطأ) أمام العبارة الخاطئة", "Part Two: Write (True) or (False) next to each statement"))
        repeat(4) { questions += para(s("(        ) اكتب العبارة هنا. (درجة واحدة)", "(        ) Write the statement here. (1 mark)")) }
        h2(s("ثالثاً: أجب عن الأسئلة الآتية", "Part Three: Answer the following questions"))
        repeat(2) {
            questions += para(s("اكتب نص السؤال هنا. (4 درجات)", "Write the question here. (4 marks)"))
            repeat(2) { para(".".repeat(110), color = GRAY) }
        }
        doc.toggleList(questions, bullet = false)

        h2(s("توزيع الدرجات", "Marks"))
        table(listOf(
            listOf(s("السؤال", "Part"), s("الدرجة", "Marks"), s("درجة الطالب", "Student's mark")),
            listOf(s("أولاً", "One"), "8", ""),
            listOf(s("ثانياً", "Two"), "4", ""),
            listOf(s("ثالثاً", "Three"), "8", ""),
            listOf(s("المجموع", "Total"), "20", ""),
        ), Head.ROW)
        para(s("انتهت الأسئلة، مع أطيب التمنيات بالتوفيق", "End of questions. Good luck!"), align = PAlign.CENTER, bold = true)
        done()
    }

    fun minutes(arabic: Boolean): OPackage = Docx(arabic).run {
        para(s("محضر اجتماع", "Meeting Minutes"), WordParts.StyleKind.TITLE, PAlign.CENTER)
        table(listOf(
            s("موضوع الاجتماع", "Meeting subject"), s("التاريخ والوقت", "Date and time"), s("المكان", "Location"),
            s("رئيس الاجتماع", "Chairperson"), s("مقرر الاجتماع", "Minute taker"),
        ).map { listOf(it, "") }, Head.COLUMN)

        h1(s("الحاضرون", "Attendees"))
        list(List(3) { s("الاسم — الصفة", "Name — Position") }, bullet = true)
        h1(s("المعتذرون", "Apologies"))
        list(listOf(s("الاسم — الصفة", "Name — Position")), bullet = true)

        h1(s("جدول الأعمال", "Agenda"))
        list(listOf(
            s("اكتب البند الأول من جدول الأعمال.", "Write the first agenda item."),
            s("اكتب البند الثاني.", "Write the second item."),
            s("ما يستجد من أعمال.", "Any other business."),
        ), bullet = false)

        h1(s("المناقشات والقرارات", "Discussion and Decisions"))
        for (item in listOf(s("البند الأول", "Item One"), s("البند الثاني", "Item Two"))) {
            h2(item)
            labeled(s("خلاصة النقاش: ", "Discussion summary: "), s("اكتب أهم ما طُرح من آراء وملاحظات.", "Note the main views and remarks that were raised."))
            labeled(s("القرار: ", "Decision: "), s("اكتب ما اتُّفق عليه بصيغة واضحة ومحددة.", "State what was agreed in clear, specific terms."))
        }

        h1(s("المهام والتكليفات", "Action Items"))
        table(listOf(
            listOf(s("المهمة", "Task"), s("المسؤول", "Owner"), s("موعد الإنجاز", "Due date"), s("الحالة", "Status")),
            listOf(s("وصف المهمة", "Describe the task"), s("اسم المسؤول", "Owner's name"), ".... / .... / ........", s("قيد التنفيذ", "In progress")),
            listOf("", "", "", ""),
            listOf("", "", "", ""),
        ), Head.ROW)

        h1(s("الاجتماع القادم", "Next Meeting"))
        para(s("يُعقد الاجتماع القادم يوم ........ الموافق .... / .... / ........ الساعة ........ في ........",
            "The next meeting will be held on ........, .... / .... / ........, at ........ in ........"))
        para()
        labeled(s("مقرر الاجتماع: ", "Minute taker: "), "........................")
        labeled(s("رئيس الاجتماع: ", "Chairperson: "), "........................")
        done()
    }

    // ================= Excel =================

    /** Typed input and formatting by position on the single sheet of a new workbook. */
    private class Book(val ar: Boolean, sheetName: String) {
        val w: Workbook5 = XlsxIO5.blank(sheetName, ar)
        val sh: Sheet5 = w.book.sheets[0]

        /** Currency format: the ribbon's own codes (riyal for Arabic, dollar for English). */
        val money = if (ar) "#,##0.00 \"ر.س\"" else "\"$\"#,##0.00"
        /** Currency with negative amounts in red. */
        val signed = "$money;[Red]-$money"

        fun s(a: String, e: String) = if (ar) a else e
        fun text(r: Int, c: Int, v: String) { w.setInput(sh, r, c, "'$v") }
        fun num(r: Int, c: Int, v: Int) { w.setInput(sh, r, c, v.toString()) }
        fun formula(r: Int, c: Int, f: String) { check(w.setInput(sh, r, c, "=$f") == null) { f } }
        fun fmt(r1: Int, c1: Int, r2: Int, c2: Int, f: (CellFormat) -> CellFormat) = w.applyFormat(sh, CRange(r1, c1, r2, c2), f)
        fun mergeRow(r: Int, c1: Int, c2: Int) = w.merge(sh, CRange(r, c1, r, c2))
        fun widths(vararg chars: Float) = chars.forEachIndexed { c, v -> w.setColWidth(sh, c..c, v) }

        fun grid(f: CellFormat) = f.copy(left = "thin", right = "thin", top = "thin", bottom = "thin", borderColor = "808080")
        fun banner(f: CellFormat) = f.copy(bold = true, fontColor = "FFFFFF", fill = NAVY, hAlign = "center", vAlign = "center")

        /** The sheet title across columns 0..[lastCol]. */
        fun title(label: String, lastCol: Int) {
            text(0, 0, label)
            fmt(0, 0, 0, lastCol) { banner(it).copy(fontSize = 20f) }
            mergeRow(0, 0, lastCol)
            w.setRowHeight(sh, 0..0, 36f)
        }
    }

    /**
     * An invoice: seller and customer blocks, eight item rows (quantity × unit price), subtotal, tax and total due.
     * Items are rows 12 to 19 of the sheet, the totals are in E20 to E23.
     */
    fun invoice(arabic: Boolean, today: java.time.LocalDate = java.time.LocalDate.now()): Workbook5 = Book(arabic, if (arabic) "الفاتورة" else "Invoice").run {
        widths(6f, 34f, 10f, 18f, 18f)
        title(s("فاتورة", "INVOICE"), 4)

        text(2, 0, s("اسم الشركة أو المتجر", "Company or Shop Name"))
        text(3, 0, s("العنوان: المدينة، الشارع", "Address: Street, City"))
        text(4, 0, s("الهاتف: 0500000000", "Phone: 000-000-0000"))
        text(5, 0, s("الرقم الضريبي: 000000000000000", "Tax ID: 000000000"))
        for (r in 2..5) mergeRow(r, 0, 2)
        fmt(2, 0, 2, 2) { it.copy(bold = true, fontSize = 14f, fontColor = NAVY) }
        text(2, 3, s("رقم الفاتورة", "Invoice no.")); text(2, 4, "INV-0001")
        text(3, 3, s("تاريخ الفاتورة", "Invoice date")); w.setInput(sh, 3, 4, today.toString())
        text(4, 3, s("تاريخ الاستحقاق", "Due date")); formula(4, 4, "E4+30")
        fmt(2, 3, 4, 3) { it.copy(bold = true) }
        fmt(2, 4, 4, 4) { it.copy(hAlign = "center") }
        fmt(3, 4, 4, 4) { it.copy(numFmt = "yyyy-mm-dd") }

        text(7, 0, s("فاتورة إلى: اسم العميل أو الجهة", "Bill to: Customer or organization name"))
        text(8, 0, s("العنوان: المدينة، الشارع  |  الهاتف: 0500000000", "Address: Street, City  |  Phone: 000-000-0000"))
        for (r in 7..8) mergeRow(r, 0, 4)
        fmt(7, 0, 7, 4) { it.copy(bold = true, fill = TINT) }

        listOf(s("م", "#"), s("الوصف", "Description"), s("الكمية", "Qty"), s("سعر الوحدة", "Unit price"), s("الإجمالي", "Total")).forEachIndexed { c, h -> text(10, c, h) }
        fmt(10, 0, 10, 4) { grid(banner(it)) }
        val samples = listOf(
            Triple(s("الصنف أو الخدمة الأولى", "First item or service"), 2, 150),
            Triple(s("الصنف أو الخدمة الثانية", "Second item or service"), 1, 400),
            Triple(s("الصنف أو الخدمة الثالثة", "Third item or service"), 5, 20),
        )
        for (i in 0 until 8) {
            val r = 11 + i
            num(r, 0, i + 1)
            samples.getOrNull(i)?.let { (name, qty, price) -> text(r, 1, name); num(r, 2, qty); num(r, 3, price) }
            formula(r, 4, "C${r + 1}*D${r + 1}")
        }
        fmt(11, 0, 18, 4) { grid(it) }
        fmt(11, 0, 18, 0) { it.copy(hAlign = "center") }
        fmt(11, 2, 18, 2) { it.copy(hAlign = "center") }
        fmt(11, 3, 18, 3) { it.copy(numFmt = money) }
        // the line total of a row that is still empty shows nothing instead of a zero
        fmt(11, 4, 18, 4) { it.copy(numFmt = "$money;-$money;") }

        text(19, 3, s("المجموع الفرعي", "Subtotal")); formula(19, 4, "SUM(E12:E19)")
        text(20, 3, s("نسبة الضريبة", "Tax rate")); w.setInput(sh, 20, 4, "15%")
        text(21, 3, s("قيمة الضريبة", "Tax")); formula(21, 4, "E20*E21")
        text(22, 3, s("الإجمالي المستحق", "Total due")); formula(22, 4, "E20+E22")
        fmt(19, 3, 22, 4) { grid(it) }
        fmt(19, 3, 22, 3) { it.copy(bold = true) }
        for (r in listOf(19, 21, 22)) fmt(r, 4, r, 4) { it.copy(numFmt = money) }
        fmt(22, 3, 22, 4) { it.copy(bold = true, fill = TINT) }

        text(24, 0, s("ملاحظات: يُسدَّد المبلغ خلال 30 يوماً من تاريخ الفاتورة.", "Notes: Payment is due within 30 days of the invoice date."))
        text(25, 0, s("شكراً لتعاملكم معنا", "Thank you for your business"))
        for (r in 24..25) mergeRow(r, 0, 4)
        fmt(24, 0, 24, 4) { it.copy(fontColor = GRAY) }
        fmt(25, 0, 25, 4) { it.copy(bold = true, fontColor = NAVY, hAlign = "center") }
        w
    }

    /**
     * A monthly budget: planned and actual amounts with their difference for three income rows (5 to 7, total in
     * row 8) and nine expense rows (11 to 19, total in row 20); row 22 is what remains of the income.
     */
    fun budget(arabic: Boolean): Workbook5 = Book(arabic, if (arabic) "الميزانية" else "Budget").run {
        widths(32f, 16f, 16f, 16f)
        title(s("الميزانية الشهرية", "Monthly Budget"), 3)
        text(1, 0, s("الشهر: ........", "Month: ........"))
        mergeRow(1, 0, 3)
        fmt(1, 0, 1, 3) { it.copy(bold = true) }

        /** A block of rows: heading row [top], its items below, then a total row; returns the total's row. */
        fun block(top: Int, name: String, fill: String, items: List<Triple<String, Int, Int>>, total: String, difference: (Int) -> String): Int {
            listOf(name, s("المخطط", "Planned"), s("الفعلي", "Actual"), s("الفرق", "Difference")).forEachIndexed { c, h -> text(top, c, h) }
            fmt(top, 0, top, 3) { grid(it).copy(bold = true, fill = fill) }
            fmt(top, 1, top, 3) { it.copy(hAlign = "center") }
            items.forEachIndexed { i, (label, planned, actual) ->
                val r = top + 1 + i
                text(r, 0, label); num(r, 1, planned); num(r, 2, actual); formula(r, 3, difference(r + 1))
            }
            val end = top + 1 + items.size
            text(end, 0, total)
            for (c in 1..2) formula(end, c, "SUM(${A1.name(top + 1, c)}:${A1.name(end - 1, c)})")
            formula(end, 3, difference(end + 1))
            fmt(top + 1, 0, end, 3) { grid(it) }
            fmt(top + 1, 1, end, 2) { it.copy(numFmt = money) }
            fmt(top + 1, 3, end, 3) { it.copy(numFmt = signed) }
            fmt(end, 0, end, 3) { it.copy(bold = true, fill = "F2F2F2") }
            return end
        }

        // income: more than planned is positive; expenses: less than planned is positive
        val income = block(3, s("الدخل", "Income"), "E2EFDA", listOf(
            Triple(s("الراتب", "Salary"), 5000, 5000),
            Triple(s("دخل إضافي", "Additional income"), 800, 650),
            Triple(s("مصادر أخرى", "Other sources"), 200, 150),
        ), s("إجمالي الدخل", "Total income")) { n -> "C$n-B$n" }
        val expenses = block(income + 2, s("المصروفات", "Expenses"), "FCE4D6", listOf(
            Triple(s("السكن (إيجار أو قسط)", "Housing (rent or mortgage)"), 1500, 1500),
            Triple(s("الكهرباء والماء والإنترنت", "Electricity, water and internet"), 400, 430),
            Triple(s("الطعام والبقالة", "Food and groceries"), 1200, 1310),
            Triple(s("المواصلات والوقود", "Transport and fuel"), 400, 380),
            Triple(s("التعليم", "Education"), 300, 300),
            Triple(s("الصحة والعلاج", "Health"), 200, 150),
            Triple(s("الترفيه والتسوق", "Entertainment and shopping"), 300, 420),
            Triple(s("الادخار", "Savings"), 800, 800),
            Triple(s("مصروفات أخرى", "Other expenses"), 200, 260),
        ), s("إجمالي المصروفات", "Total expenses")) { n -> "B$n-C$n" }

        val rest = expenses + 2
        text(rest, 0, s("المتبقي من الدخل", "Remaining balance"))
        for (c in 1..2) formula(rest, c, "${A1.name(income, c)}-${A1.name(expenses, c)}")
        formula(rest, 3, "C${rest + 1}-B${rest + 1}")
        fmt(rest, 0, rest, 3) { grid(it).copy(bold = true, fontSize = 12f, fill = TINT) }
        fmt(rest, 1, rest, 2) { it.copy(numFmt = money) }
        fmt(rest, 3, rest, 3) { it.copy(numFmt = signed) }

        text(rest + 2, 0, s("غيّر الأرقام في عمودَي «المخطط» و«الفعلي»، وستُحسب المجاميع والفروق تلقائياً.", "Change the numbers in the Planned and Actual columns; the totals and differences update by themselves."))
        mergeRow(rest + 2, 0, 3)
        fmt(rest + 2, 0, rest + 2, 3) { it.copy(fontColor = GRAY) }
        w
    }

    // ================= PowerPoint =================

    /** Fills the title and content placeholders of the slides of a new deck. */
    private class Deck(val ar: Boolean) {
        val d: PptDoc = PptDoc.of(PptParts.blankPptx(ar))
        private val start = if (ar) "r" else "l"

        fun s(a: String, e: String) = if (ar) a else e

        private fun placeholder(slide: Int, title: Boolean): XElem =
            d.spTree(d.editSlide(slide)!!)!!.children("sp").first { sp -> sp.find("ph")?.let { (it.attr("type") == "title") == title } == true }

        /** Text with explicit direction, size and colour, so that the app's own renderer and PowerPoint agree. */
        private fun fill(shape: XElem, text: String, algn: String, sizePt: Int, color: String, bold: Boolean) {
            d.setShapeText(shape, text)
            d.setParagraphAlign(shape, algn, ar)
            d.formatShapeText(shape) { rp ->
                rp.set("lang", if (ar) "ar-SA" else "en-US").set("sz", (sizePt * 100).toString())
                if (bold) rp.set("b", "1")
                d.setRunColor(rp, color)
            }
        }

        /** An opening or closing slide: dark background, centred title and subtitle, an accent line between them. */
        fun cover(slide: Int, title: String, subtitle: String) {
            d.setBackground(slide, NAVY)
            val t = placeholder(slide, true); val b = placeholder(slide, false)
            fill(t, title, "ctr", 48, "FFFFFF", true)
            d.setBounds(t, 838200, 2057400, 10515600, 1371600)
            fill(b, subtitle, "ctr", 24, TINT, false)
            d.setBounds(b, 838200, 3703320, 10515600, 914400)
            // the content placeholder is a plain subtitle here: no bullet, no hanging indent
            d.paragraphs(b).forEach { it.child("pPr")?.set(if (ar) "marR" else "marL", "0")?.set("indent", "0")?.add(XElem("a:buNone")) }
            d.addShape(slide, "rect", 5181600, 3520440, 1828800, 54864, GOLD, null)
            d.setTransition(slide, "fade")
        }

        /** A content slide: coloured title with a short accent bar under its start, then bullet points. */
        fun content(slide: Int, title: String, points: List<String>) {
            val t = placeholder(slide, true); val b = placeholder(slide, false)
            fill(t, title, start, 36, NAVY, true)
            fill(b, points.joinToString("\n"), start, 24, "262626", false)
            d.paragraphs(b).forEach { it.child("pPr")?.add(XElem("a:buFont").set("typeface", "Arial"))?.add(XElem("a:buChar").set("char", "•")) }
            d.addShape(slide, "rect", if (ar) 9982200 else 838200, 1709928, 1371600, 54864, GOLD, null)
            d.setTransition(slide, "fade")
        }
    }

    /** A five-slide deck: title, agenda, two content slides and a closing slide. */
    fun slides(arabic: Boolean): OPackage = Deck(arabic).run {
        repeat(4) { d.newSlide(d.slideCount - 1) }
        cover(0, s("عنوان العرض التقديمي", "Presentation Title"), s("اسم مقدّم العرض — التاريخ", "Presenter name — Date"))
        content(1, s("محاور العرض", "Agenda"), listOf(
            s("مقدمة: لماذا هذا الموضوع؟", "Introduction: why this topic?"),
            s("المحور الأول: الفكرة الرئيسة", "Part one: the main idea"),
            s("المحور الثاني: النتائج والتوصيات", "Part two: results and recommendations"),
            s("الخلاصة والأسئلة", "Summary and questions"),
        ))
        content(2, s("المحور الأول: الفكرة الرئيسة", "Part One: The Main Idea"), listOf(
            s("اكتب الفكرة الرئيسة في جملة واحدة واضحة.", "State the main idea in one clear sentence."),
            s("ادعمها بمعلومة أو رقم أو مثال من الواقع.", "Support it with a fact, a number or a real example."),
            s("اكتفِ بثلاث إلى خمس نقاط في الشريحة الواحدة.", "Keep each slide to three to five points."),
        ))
        content(3, s("المحور الثاني: النتائج والتوصيات", "Part Two: Results and Recommendations"), listOf(
            s("اعرض أهم نتيجة توصلت إليها.", "Show the most important result you reached."),
            s("بيّن ما تعنيه هذه النتيجة للحاضرين.", "Explain what it means for your audience."),
            s("اقترح الخطوة التالية ومن يتولاها.", "Suggest the next step and who will take it."),
        ))
        cover(4, s("شكراً لحسن استماعكم", "Thank You"), s("نرحب بأسئلتكم وملاحظاتكم", "Questions and comments are welcome"))
        d.pkg
    }
}
