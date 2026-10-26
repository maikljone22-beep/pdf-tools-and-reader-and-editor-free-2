package com.smartdocs.editor.core.edit5

/**
 * Pure part of the live (JavaScript-updated) watermark: which tokens are live, conversion of the token template to
 * the script's template, Java date pattern → `util.printd` pattern, JS string escaping and the script itself.
 *
 * Live tokens (recomputed by the viewer on open and before printing): `{page} {pages} {user} {date[:fmt]}
 * {time[:fmt]} {datetime[:fmt]} {file}`. Other tokens (`{title} {author} {hijri} {bates} {label}`) are fixed at apply time.
 */
object LiveWmScript {
    /** Parent field name; each page gets the terminal field `SDE5WM.p<n>`. */
    const val FIELD = "SDE5WM"
    /** Value of the /SDE5 marker key on the fields, widgets and actions we add. */
    const val MARK = "wmlive"
    /** Name of the document-level script in /Names /JavaScript. */
    const val JS_NAME = "SDE5 live watermark"
    /** /DR font resource name used by the fields' /DA. */
    const val DA_FONT = "SDE5Wm"

    private const val SEP = '\u0001'
    private val re = Regex("""\{([a-z]+)(?::([^}]*))?\}""")
    private val LIVE = setOf("page", "n", "pages", "total", "user", "file", "date", "time", "datetime")

    fun hasLiveTokens(template: String): Boolean = re.findAll(template).any { it.groupValues[1] in LIVE }

    /**
     * Template used by the script: fixed tokens expanded with [ctx] (no digit conversion — the script converts
     * all digits at the end, like [Tokens.expand]); live tokens become markers `\u0001P\u0001` (page),
     * `\u0001N\u0001` (pages), `\u0001U\u0001` (user), `\u0001F\u0001` (file name), `\u0001D<printd>\u0001` (date/time).
     */
    fun scriptTemplate(template: String, ctx: TokenContext): String = re.replace(template) { m ->
        val key = m.groupValues[1]; val arg = m.groupValues[2].ifEmpty { null }
        when (key) {
            "page", "n" -> "${SEP}P$SEP"
            "pages", "total" -> "${SEP}N$SEP"
            "user" -> "${SEP}U$SEP"
            "file" -> "${SEP}F$SEP"
            "date" -> "${SEP}D" + printdPattern(arg ?: "yyyy/MM/dd") + SEP
            "time" -> "${SEP}D" + printdPattern(arg ?: "HH:mm") + SEP
            "datetime" -> "${SEP}D" + printdPattern(arg ?: "yyyy/MM/dd HH:mm") + SEP
            else -> Tokens.expand(m.value, ctx, false)
        }
    }

    /**
     * Java `DateTimeFormatter` pattern → Acrobat `util.printd` pattern (month `m`, minute `M`, 24 h `H`, 12 h `h`,
     * am/pm `tt`, weekday `ddd`/`dddd`). Quoted text and letters printd would read as fields are backslash-escaped.
     */
    fun printdPattern(java: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < java.length) {
            val c = java[i]
            if (c == '\'') {
                val end = java.indexOf('\'', i + 1).let { if (it < 0) java.length else it }
                if (end == i + 1) out.append('\'') else java.substring(i + 1, end).forEach { appendLiteral(out, it) }
                i = end + 1; continue
            }
            var n = 1
            while (i + n < java.length && java[i + n] == c) n++
            when (c) {
                'y', 'u' -> out.append(if (n == 2) "yy" else "yyyy")
                'M', 'L' -> out.append(when { n >= 4 -> "mmmm"; n == 3 -> "mmm"; n == 2 -> "mm"; else -> "m" })
                'd' -> out.append(if (n >= 2) "dd" else "d")
                'E' -> out.append(if (n >= 4) "dddd" else "ddd")
                'H' -> out.append(if (n >= 2) "HH" else "H")
                'h' -> out.append(if (n >= 2) "hh" else "h")
                'm' -> out.append(if (n >= 2) "MM" else "M")
                's' -> out.append(if (n >= 2) "ss" else "s")
                'a' -> out.append("tt")
                else -> repeat(n) { appendLiteral(out, c) }
            }
            i += n
        }
        return out.toString()
    }

    private fun appendLiteral(out: StringBuilder, c: Char) {
        if (c in "mdyHhMstjz\\") out.append('\\')
        out.append(c)
    }

    /** JavaScript string literal (double quotes); every non-ASCII or control character as `\uXXXX`. */
    fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 8).append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c.code < 0x20 || c.code > 0x7E || c == '<' || c == '>' -> sb.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
            else -> sb.append(c)
        }
        return sb.append('"').toString()
    }

    /**
     * Self-contained script that refreshes every `SDE5WM.p<n>` field: user from `identity.name` /
     * `identity.loginName` (privileged in Acrobat, so it falls back to [fallbackUser]), date/time through
     * `util.printd`, `this.documentFileName`, the page of each widget and `numPages`. Lines are joined with CR (multi-line
     * field). The document's dirty flag is restored, so opening does not ask to save.
     */
    fun script(template: String, ctx: TokenContext, fallbackUser: String, arabicDigits: Boolean): String {
        val tpl = scriptTemplate(template, ctx).split('\n').filter { it.isNotBlank() }.joinToString("\r")
        val s = "\\u0001"
        return buildString {
            append("/* SmartDocs live watermark */\n")
            append("var SDE5wm=function(doc){try{\n")
            append("var T=").append(jsString(tpl)).append(",U=").append(jsString(fallbackUser)).append(",A=").append(arabicDigits).append(",u=\"\";\n")
            append("try{u=identity.name||identity.loginName||\"\";}catch(e){}\n")
            append("if(!u)u=U;\n")
            append("var dt=new Date(),n=doc.numPages,fn=\"\",dd=false;\n")
            append("try{fn=doc.documentFileName||\"\";}catch(e){}\n")
            append("try{dd=doc.dirty;}catch(e){}\n")
            append("var pd=function(f){try{return util.printd(f,dt);}catch(e){return dt.toString();}};\n")
            append("var r=function(p){var x=T.split(\"${s}P${s}\").join(String(p)).split(\"${s}N${s}\").join(String(n)).split(\"${s}U${s}\").join(u).split(\"${s}F${s}\").join(fn);\n")
            append("x=x.replace(/${s}D([^${s}]*)${s}/g,function(m,f){return pd(f);});\n")
            append("if(A)x=x.replace(/[0-9]/g,function(c){return String.fromCharCode(1632+c.charCodeAt(0)-48);});\n")
            append("return x;};\n")
            append("var f=doc.getField(\"$FIELD\");if(!f)return;\n")
            append("var a=f.getArray();\n")
            append("for(var i=0;i<a.length;i++){var g=a[i],pg=g.page;if(typeof pg!=\"number\")pg=pg[0];var v=r(pg+1);if(g.value!=v)g.value=v;}\n")
            append("try{doc.dirty=dd;}catch(e){}\n")
            append("}catch(e){}};\n")
            append("SDE5wm(this);\n")
        }
    }

    /** Colour [rgb] at [opacity] over white paper (form field text cannot be transparent once a viewer redraws it). */
    fun fadedColor(rgb: Int, opacity: Float): Int {
        val o = opacity.coerceIn(0f, 1f)
        fun ch(v: Int) = (255 - (255 - v) * o).toInt().coerceIn(0, 255)
        return (ch((rgb shr 16) and 0xFF) shl 16) or (ch((rgb shr 8) and 0xFF) shl 8) or ch(rgb and 0xFF)
    }

    /** Field rotations are multiples of 90°: the nearest one for [deg] (counter-clockwise), in 0..270. */
    fun snapRotation(deg: Float): Int = ((Math.round(deg / 90f) * 90) % 360 + 360) % 360

    /** Field flags: ReadOnly, DoNotSpellCheck, DoNotScroll, plus Multiline when [lines] > 1. */
    fun fieldFlags(lines: Int): Int = 1 or (1 shl 22) or (1 shl 23) or (if (lines > 1) 1 shl 12 else 0)

    /** Annotation flags: Print when printed, NoView when hidden on screen, ReadOnly + Locked always. */
    fun annotFlags(onScreen: Boolean, onPrint: Boolean): Int = (if (onPrint) 4 else 0) or (if (!onScreen) 32 else 0) or 64 or 128
}
