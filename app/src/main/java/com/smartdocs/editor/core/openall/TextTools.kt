package com.smartdocs.editor.core.openall

/** Delimiter detection for CSV / TSV / semicolon / pipe files (quotes respected, consistent column count wins). Pure. */
object CsvSniff {
    val CANDIDATES = charArrayOf(',', ';', '\t', '|')

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var q = false; var n = 0
        for (c in line) { if (c == '"') q = !q else if (c == d && !q) n++ }
        return n
    }

    fun delimiter(text: String, ext: String = ""): Char {
        when (ext.lowercase()) { "tsv", "tab" -> return '\t'; "psv" -> return '|' }
        val lines = text.lineSequence().filter { it.isNotBlank() }.take(30).toList()
        if (lines.isEmpty()) return ','
        var best = ','; var bestScore = -1.0
        for (d in CANDIDATES) {
            val counts = lines.map { countOutsideQuotes(it, d) }
            if (counts.all { it == 0 }) continue
            val mode = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
            if (mode.key == 0) continue
            // share of lines with the typical count, times the column count (more columns break ties)
            val score = mode.value.toDouble() / lines.size * 100 + minOf(mode.key, 50)
            if (score > bestScore) { bestScore = score; best = d }
        }
        return best
    }
}

/** Pretty printers for JSON and XML that never parse entities or fetch anything (plain tokenizers). Pure. */
object Pretty {

    /** Re-indents JSON; returns null when the text is not balanced JSON-like. */
    fun json(src: String, indent: String = "  ", maxLen: Int = 8_000_000): String? {
        if (src.length > maxLen) return null
        val t = src.trim()
        if (t.isEmpty() || (t[0] != '{' && t[0] != '[')) return null
        val sb = StringBuilder(t.length + t.length / 4)
        var depth = 0; var inStr = false; var esc = false
        fun nl() { sb.append('\n'); repeat(depth) { sb.append(indent) } }
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (inStr) {
                sb.append(c)
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else when (c) {
                '"' -> { inStr = true; sb.append(c) }
                '{', '[' -> {
                    // keep empty {} / [] on one line
                    var j = i + 1; while (j < t.length && t[j].isWhitespace()) j++
                    if (j < t.length && (t[j] == '}' || t[j] == ']')) { sb.append(c).append(t[j]); i = j } else { depth++; sb.append(c); nl() }
                }
                '}', ']' -> { depth--; if (depth < 0) return null; nl(); sb.append(c) }
                ',' -> { sb.append(c); nl() }
                ':' -> sb.append(": ")
                ' ', '\t', '\n', '\r' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return if (depth == 0 && !inStr) sb.toString() else null
    }

    /** Re-indents XML by tag depth. Text, comments, CDATA and processing instructions are kept as they are. */
    fun xml(src: String, indent: String = "  ", maxLen: Int = 8_000_000): String? {
        if (src.length > maxLen || !src.trimStart().startsWith("<")) return null
        val sb = StringBuilder(src.length + src.length / 4)
        var depth = 0; var i = 0
        fun line(s: String) { if (sb.isNotEmpty()) sb.append('\n'); repeat(depth.coerceAtLeast(0)) { sb.append(indent) }; sb.append(s) }
        while (i < src.length) {
            if (src[i] == '<') {
                val end = when {
                    src.startsWith("<!--", i) -> src.indexOf("-->", i).let { if (it < 0) src.length else it + 3 }
                    src.startsWith("<![CDATA[", i) -> src.indexOf("]]>", i).let { if (it < 0) src.length else it + 3 }
                    else -> {
                        var j = i + 1; var q = 0.toChar()
                        while (j < src.length) { val c = src[j]; if (q != 0.toChar()) { if (c == q) q = 0.toChar() } else if (c == '"' || c == '\'') q = c else if (c == '>') break; j++ }
                        (j + 1).coerceAtMost(src.length)
                    }
                }
                val tag = src.substring(i, end)
                when {
                    tag.startsWith("</") -> { depth--; line(tag) }
                    tag.startsWith("<?") || tag.startsWith("<!") || tag.endsWith("/>") -> line(tag)
                    else -> {
                        // <a>text</a> on one line
                        val close = src.indexOf('<', end)
                        val name = Regex("""^<([^\s/>]+)""").find(tag)?.groupValues?.get(1)
                        if (close > 0 && name != null && src.startsWith("</$name", close) && src.substring(end, close).let { it.isNotBlank() && !it.contains('\n') }) {
                            val closeEnd = src.indexOf('>', close).let { if (it < 0) src.length else it + 1 }
                            line(tag + src.substring(end, close).trim() + src.substring(close, closeEnd)); i = closeEnd; continue
                        }
                        line(tag); depth++
                    }
                }
                i = end
            } else {
                val next = src.indexOf('<', i).let { if (it < 0) src.length else it }
                val text = src.substring(i, next).trim()
                if (text.isNotEmpty()) line(text)
                i = next
            }
        }
        return sb.toString()
    }
}

/** Token kinds of the source / data viewer. */
enum class Tok { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, TAG, ATTR, KEY }

class Span(val start: Int, val end: Int, val kind: Tok)

/** Language family of a text file, chosen by extension. */
enum class Lang { NONE, CLIKE, HASH, XML, JSON, SQL, INI, MARKDOWN }

/** A small line-based syntax highlighter (keywords, strings, comments, numbers, tags). Pure. */
object Syntax {
    fun langOf(ext: String): Lang = when (ext.lowercase()) {
        "kt", "kts", "java", "gradle", "js", "mjs", "ts", "tsx", "jsx", "c", "h", "cpp", "cc", "hpp", "cs", "go", "rs", "swift", "m",
        "php", "dart", "scala", "groovy", "css", "scss", "less", "vue", "svelte" -> Lang.CLIKE
        "py", "sh", "bash", "zsh", "rb", "pl", "yaml", "yml", "toml", "r", "ps1", "cmake", "makefile", "dockerfile", "gitignore", "env", "properties" -> Lang.HASH
        "xml", "html", "htm", "xhtml", "svg", "xsd", "xsl", "xslt", "plist", "rss", "atom", "gpx", "kml", "opml", "csproj", "pom", "iml", "wsdl", "fb2" -> Lang.XML
        "json", "jsonl", "geojson" -> Lang.JSON
        "sql" -> Lang.SQL
        "ini", "cfg", "conf", "inf", "reg" -> Lang.INI
        "md", "markdown" -> Lang.MARKDOWN
        else -> Lang.NONE
    }

    private val KW = setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "import", "package", "public", "private", "protected", "internal", "static", "final", "const", "new", "this", "super", "null",
        "true", "false", "try", "catch", "finally", "throw", "throws", "void", "int", "long", "float", "double", "boolean", "char",
        "byte", "short", "struct", "enum", "switch", "case", "default", "function", "let", "def", "lambda", "in", "is", "as", "not",
        "and", "or", "from", "with", "yield", "async", "await", "export", "extends", "implements", "override", "abstract", "data",
        "sealed", "typeof", "instanceof", "None", "True", "False", "self", "elif", "pass", "nil", "func", "go", "defer", "chan", "map",
        "type", "impl", "pub", "mut", "use", "mod", "match", "echo", "then", "fi", "done", "esac", "local", "export", "unsigned", "namespace", "using",
    )
    private val SQL_KW = setOf("select", "from", "where", "insert", "into", "update", "delete", "create", "table", "drop", "alter", "join",
        "left", "right", "inner", "outer", "on", "group", "by", "order", "having", "limit", "values", "set", "and", "or", "not", "null",
        "as", "distinct", "union", "primary", "key", "index", "view", "begin", "commit", "rollback", "case", "when", "then", "else", "end")

    /** State carried between lines: inside a block comment, or inside a multi-line string. */
    class State(val blockComment: Boolean = false)

    fun line(s: String, lang: Lang, st: State = State()): Pair<List<Span>, State> {
        if (lang == Lang.NONE || s.length > 4000) return emptyList<Span>() to st
        val out = ArrayList<Span>()
        var i = 0
        var inBlock = st.blockComment
        val blockEnd = if (lang == Lang.XML) "-->" else "*/"
        while (i < s.length) {
            if (inBlock) {
                val e = s.indexOf(blockEnd, i)
                val end = if (e < 0) s.length else e + blockEnd.length
                out += Span(i, end, Tok.COMMENT); i = end
                if (e >= 0) inBlock = false
                continue
            }
            val c = s[i]
            when (lang) {
                Lang.XML -> {
                    if (s.startsWith("<!--", i)) { inBlock = true; continue }
                    if (c == '<') {
                        var j = i + 1
                        while (j < s.length && (s[j].isLetterOrDigit() || s[j] in "/?!:_-.")) j++
                        out += Span(i, j, Tok.TAG); i = j
                        // attributes up to '>'
                        while (i < s.length && s[i] != '>') {
                            val a = s[i]
                            if (a == '"' || a == '\'') { val e = s.indexOf(a, i + 1).let { if (it < 0) s.length else it + 1 }; out += Span(i, e, Tok.STRING); i = e }
                            else if (a.isLetter()) { var k = i; while (k < s.length && (s[k].isLetterOrDigit() || s[k] in ":_-.")) k++; out += Span(i, k, Tok.ATTR); i = k }
                            else i++
                        }
                        if (i < s.length) { out += Span(i, i + 1, Tok.TAG); i++ }
                        continue
                    }
                    if (c == '&') { val e = s.indexOf(';', i); if (e in (i + 1)..(i + 10)) { out += Span(i, e + 1, Tok.KEYWORD); i = e + 1; continue } }
                    i++
                }
                Lang.INI -> {
                    val t = s.trimStart()
                    if (t.startsWith(";") || t.startsWith("#")) { out += Span(0, s.length, Tok.COMMENT); i = s.length }
                    else if (t.startsWith("[")) { out += Span(0, s.length, Tok.TAG); i = s.length }
                    else { val eq = s.indexOf('='); if (eq > 0) { out += Span(0, eq, Tok.KEY); out += Span(eq + 1, s.length, Tok.STRING) }; i = s.length }
                }
                Lang.MARKDOWN -> {
                    val t = s.trimStart()
                    if (t.startsWith("#")) out += Span(0, s.length, Tok.KEYWORD)
                    else if (t.startsWith(">")) out += Span(0, s.length, Tok.COMMENT)
                    else if (t.startsWith("```") || t.startsWith("    ")) out += Span(0, s.length, Tok.STRING)
                    i = s.length
                }
                else -> {
                    val lineComment = when (lang) { Lang.CLIKE, Lang.JSON -> s.startsWith("//", i); Lang.HASH -> c == '#'; Lang.SQL -> s.startsWith("--", i); else -> false }
                    if (lineComment) { out += Span(i, s.length, Tok.COMMENT); i = s.length; continue }
                    if ((lang == Lang.CLIKE || lang == Lang.SQL) && s.startsWith("/*", i)) { inBlock = true; continue }
                    if (c == '"' || c == '\'' || c == '`') {
                        var j = i + 1
                        while (j < s.length && s[j] != c) { if (s[j] == '\\') j++; j++ }
                        val end = (j + 1).coerceAtMost(s.length)
                        // a JSON string followed by ':' is a key
                        var k = end; while (k < s.length && s[k] == ' ') k++
                        out += Span(i, end, if (lang == Lang.JSON && k < s.length && s[k] == ':') Tok.KEY else Tok.STRING); i = end; continue
                    }
                    if (c.isDigit() && (i == 0 || !s[i - 1].isLetterOrDigit() && s[i - 1] != '_')) {
                        var j = i; while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '.' || s[j] == '_')) j++
                        out += Span(i, j, Tok.NUMBER); i = j; continue
                    }
                    if (c.isLetter() || c == '_' || c == '@') {
                        var j = i + 1; while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                        val w = s.substring(i, j)
                        val kw = when (lang) { Lang.SQL -> w.lowercase() in SQL_KW; Lang.JSON -> w == "true" || w == "false" || w == "null"; else -> w in KW || w.startsWith("@") }
                        if (kw) out += Span(i, j, Tok.KEYWORD)
                        else if (lang == Lang.HASH && j < s.length && s[j] == ':' && s.substring(0, i).isBlank()) out += Span(i, j, Tok.KEY)
                        i = j; continue
                    }
                    i++
                }
            }
        }
        return out to State(inBlock)
    }
}

/** Hex + text dump of a file head for the file-info screen. Pure. */
object HexDump {
    fun dump(b: ByteArray, max: Int = 1024): String = buildString {
        val n = minOf(b.size, max)
        var off = 0
        while (off < n) {
            append(String.format("%06X  ", off))
            for (i in 0 until 16) {
                if (off + i < n) append(String.format("%02X ", b[off + i].toInt() and 0xFF)) else append("   ")
                if (i == 7) append(' ')
            }
            append(' ')
            for (i in 0 until 16) if (off + i < n) { val c = b[off + i].toInt() and 0xFF; append(if (c in 0x20..0x7E) c.toChar() else '.') }
            append('\n')
            off += 16
        }
    }
}
