package com.smartdocs.editor.core.office5

/**
 * AutoFill series detection from the seed cells of one row/column: linear numbers (2+ seeds), dates
 * (+1 day from one seed), text with a trailing number ("Item 1"), day/month names (English and Arabic),
 * otherwise the seeds are repeated. [value] takes the step (1, 2… after the seeds, -1, -2… before them).
 */
class FillSeries private constructor(private val gen: (Int, Int) -> FV) {
    fun value(step: Int, seedIndex: Int): FV = gen(step, seedIndex)

    companion object {
        val LISTS: List<List<String>> = listOf(
            listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"),
            listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"),
            listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"),
            listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
            // الأحد … السبت
            listOf("\u0627\u0644\u0623\u062D\u062F", "\u0627\u0644\u0627\u062B\u0646\u064A\u0646", "\u0627\u0644\u062B\u0644\u0627\u062B\u0627\u0621", "\u0627\u0644\u0623\u0631\u0628\u0639\u0627\u0621", "\u0627\u0644\u062E\u0645\u064A\u0633", "\u0627\u0644\u062C\u0645\u0639\u0629", "\u0627\u0644\u0633\u0628\u062A"),
            // يناير … ديسمبر
            listOf("\u064A\u0646\u0627\u064A\u0631", "\u0641\u0628\u0631\u0627\u064A\u0631", "\u0645\u0627\u0631\u0633", "\u0623\u0628\u0631\u064A\u0644", "\u0645\u0627\u064A\u0648", "\u064A\u0648\u0646\u064A\u0648", "\u064A\u0648\u0644\u064A\u0648", "\u0623\u063A\u0633\u0637\u0633", "\u0633\u0628\u062A\u0645\u0628\u0631", "\u0623\u0643\u062A\u0648\u0628\u0631", "\u0646\u0648\u0641\u0645\u0628\u0631", "\u062F\u064A\u0633\u0645\u0628\u0631"),
            // كانون الثاني … كانون الأول is uncommon in Yemen/Gulf; Q1..Q4
            listOf("Q1", "Q2", "Q3", "Q4"),
        )

        private val trailingNum = Regex("""^(.*?)(\d+)(\D*)$""")

        fun of(seeds: List<Cell5?>, numFmt: String?): FillSeries {
            val lits = seeds.map { it?.literal ?: FV.Blank }
            val n = lits.size
            val repeat = FillSeries { _, i -> lits[i] }
            if (seeds.any { it?.formula != null }) return repeat
            // numbers
            if (lits.all { it is FV.Num }) {
                val v = lits.map { (it as FV.Num).v }
                if (n == 1) {
                    return if (NumFmt5.isDateCode(numFmt)) FillSeries { step, _ -> FV.Num(v[0] + step) } else repeat
                }
                val d = (v.last() - v.first()) / (n - 1)
                return FillSeries { step, _ -> FV.of(if (step > 0) v.last() + d * step else v.first() + d * step) }
            }
            if (!lits.all { it is FV.Str }) return repeat
            val texts = lits.map { (it as FV.Str).v }
            // named lists
            for (list in LISTS) {
                val idx = texts.map { t -> list.indexOfFirst { it.equals(t.trim(), true) } }
                if (idx.all { it >= 0 }) {
                    val step = if (n == 1) 1 else (idx.last() - idx.first()) / (n - 1).coerceAtLeast(1)
                    val upper = texts[0].all { !it.isLetter() || it.isUpperCase() } && texts[0].any { it in 'A'..'Z' } && texts[0].length > 2
                    return FillSeries { s, _ ->
                        val base = if (s > 0) idx.last() else idx.first()
                        val w = list[(base + step * s).mod(list.size)]
                        FV.Str(if (upper) w.uppercase() else w)
                    }
                }
            }
            // text with a trailing number
            val ms = texts.map { trailingNum.matchEntire(it) }
            if (ms.all { it != null } && ms.map { it!!.groupValues[1] to it.groupValues[3] }.toSet().size == 1) {
                val nums = ms.map { it!!.groupValues[2].toLong() }
                val pad = ms[0]!!.groupValues[2].let { if (it.startsWith("0")) it.length else 0 }
                val d = if (n == 1) 1L else (nums.last() - nums.first()) / (n - 1)
                val pre = ms[0]!!.groupValues[1]; val post = ms[0]!!.groupValues[3]
                return FillSeries { s, _ ->
                    val x = (if (s > 0) nums.last() else nums.first()) + d * s
                    FV.Str(pre + (if (x < 0) "-" else "") + kotlin.math.abs(x).toString().padStart(pad, '0') + post)
                }
            }
            return repeat
        }
    }
}
