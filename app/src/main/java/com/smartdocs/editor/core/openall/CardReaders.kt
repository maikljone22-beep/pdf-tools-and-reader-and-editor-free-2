package com.smartdocs.editor.core.openall

import java.nio.charset.Charset

/** One content line of a vCard / iCalendar file: NAME;PARAM=V:VALUE (unfolded, decoded). */
class CardLine(val name: String, val params: Map<String, String>, val value: String)

/** A contact: fields in file order as (label key, value); [photo] = decoded image bytes. */
class Contact(val name: String, val fields: List<Pair<String, String>>, val photo: ByteArray?)

class CalEvent(val kind: String, val summary: String, val start: String, val end: String, val location: String, val description: String, val rule: String)

/** vCard 2.1 / 3.0 / 4.0 and iCalendar readers (open-all). Pure. */
object CardReaders {

    /** Unfolds continuation lines (leading space / tab, and vCard 2.1 quoted-printable soft breaks). */
    fun lines(text: String): List<CardLine> {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val joined = ArrayList<String>()
        for (l in raw) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && joined.isNotEmpty()) joined[joined.size - 1] = joined.last() + l.substring(1)
            else if (joined.isNotEmpty() && joined.last().endsWith("=") && joined.last().contains("QUOTED-PRINTABLE", true)) joined[joined.size - 1] = joined.last().dropLast(1) + l
            else joined += l
        }
        return joined.mapNotNull { l ->
            val colon = indexOfValueColon(l)
            if (colon <= 0) return@mapNotNull null
            val head = l.substring(0, colon)
            val parts = head.split(';')
            val name = parts[0].substringAfter('.').uppercase()   // "item1.TEL" → TEL
            val params = LinkedHashMap<String, String>()
            parts.drop(1).forEach { p ->
                val k = p.substringBefore('=', "TYPE").uppercase()
                val v = p.substringAfter('=', p).trim('"')
                params[k] = params[k]?.let { "$it,$v" } ?: v
            }
            var value = l.substring(colon + 1)
            if (params["ENCODING"].equals("QUOTED-PRINTABLE", true)) {
                val cs = params["CHARSET"]?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
                value = String(com.smartdocs.editor.core.tools5.EmlReader.quotedPrintable(value.toByteArray(Charsets.ISO_8859_1)), cs)
            }
            CardLine(name, params, value)
        }
    }

    /** The ':' that starts the value (a quoted parameter value may contain ':'). */
    private fun indexOfValueColon(l: String): Int {
        var q = false
        for (i in l.indices) { val c = l[i]; if (c == '"') q = !q else if (c == ':' && !q) return i }
        return -1
    }

    fun unescape(v: String): String = v.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\:", ":").replace("\\\\", "\\")

    fun contacts(text: String): List<Contact> {
        val out = ArrayList<Contact>()
        var cur: MutableList<CardLine>? = null
        for (l in lines(text)) {
            when {
                l.name == "BEGIN" && l.value.equals("VCARD", true) -> cur = ArrayList()
                l.name == "END" && l.value.equals("VCARD", true) -> { cur?.let { out += contact(it) }; cur = null }
                else -> cur?.add(l)
            }
        }
        return out
    }

    private fun contact(ls: List<CardLine>): Contact {
        val fields = ArrayList<Pair<String, String>>()
        var photo: ByteArray? = null
        var name = ls.firstOrNull { it.name == "FN" }?.value?.let(::unescape).orEmpty()
        for (l in ls) {
            val type = l.params["TYPE"]?.lowercase()?.replace("pref", "")?.split(',')?.filter { it.isNotBlank() && it != "voice" && it != "internet" }?.joinToString(", ")?.takeIf { it.isNotBlank() }
            fun put(key: String, v: String) { if (v.isNotBlank()) fields += (if (type != null) "$key|$type" else key) to v }
            when (l.name) {
                "N" -> if (name.isBlank()) name = l.value.split(';').map(::unescape).let { p -> listOfNotNull(p.getOrNull(3), p.getOrNull(1), p.getOrNull(2), p.getOrNull(0), p.getOrNull(4)).filter { it.isNotBlank() }.joinToString(" ") }
                "TEL" -> put("tel", unescape(l.value.removePrefix("tel:")))
                "EMAIL" -> put("email", unescape(l.value.removePrefix("mailto:")))
                "ADR" -> put("adr", l.value.split(';').map(::unescape).filter { it.isNotBlank() }.joinToString(", "))
                "ORG" -> put("org", l.value.split(';').map(::unescape).filter { it.isNotBlank() }.joinToString(" / "))
                "TITLE", "ROLE" -> put("title", unescape(l.value))
                "URL" -> put("url", unescape(l.value))
                "BDAY", "ANNIVERSARY" -> put("bday", unescape(l.value))
                "NOTE" -> put("note", unescape(l.value))
                "NICKNAME" -> put("nickname", unescape(l.value))
                "PHOTO" -> {
                    val v = l.value
                    photo = runCatching {
                        when {
                            v.startsWith("data:") -> java.util.Base64.getMimeDecoder().decode(v.substringAfter(","))
                            l.params["ENCODING"]?.lowercase() in setOf("b", "base64") || l.params.containsKey("BASE64") -> java.util.Base64.getMimeDecoder().decode(v.replace(Regex("""\s"""), ""))
                            else -> null
                        }
                    }.getOrNull()?.takeIf { it.size in 16..(8 shl 20) }
                }
            }
        }
        return Contact(name, fields, photo)
    }

    /** "20241005T120000Z" / "20241005" → "2024-10-05 12:00 UTC" / "2024-10-05". */
    fun icsDate(v: String, params: Map<String, String> = emptyMap()): String {
        val m = Regex("""^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})?(Z)?)?$""").find(v.trim()) ?: return v
        val g = m.groupValues
        val date = "${g[1]}-${g[2]}-${g[3]}"
        if (g[4].isEmpty()) return date
        val zone = if (g[7] == "Z") " UTC" else params["TZID"]?.let { " ($it)" } ?: ""
        return "$date ${g[4]}:${g[5]}$zone"
    }

    fun events(text: String): List<CalEvent> {
        val out = ArrayList<CalEvent>()
        var kind: String? = null
        var cur = HashMap<String, CardLine>()
        var depth = 0
        for (l in lines(text)) {
            if (l.name == "BEGIN") {
                if (l.value.uppercase() in setOf("VEVENT", "VTODO", "VJOURNAL") && kind == null) { kind = l.value.uppercase(); cur = HashMap(); depth = 0 }
                else if (kind != null) depth++   // VALARM etc. inside an event
                continue
            }
            if (l.name == "END") {
                if (kind != null && depth == 0 && l.value.uppercase() == kind) {
                    fun v(k: String) = cur[k]?.value?.let(::unescape).orEmpty()
                    out += CalEvent(kind, v("SUMMARY"), cur["DTSTART"]?.let { icsDate(it.value, it.params) } ?: cur["DUE"]?.let { icsDate(it.value, it.params) }.orEmpty(),
                        cur["DTEND"]?.let { icsDate(it.value, it.params) }.orEmpty(), v("LOCATION"), v("DESCRIPTION"), v("RRULE"))
                    kind = null
                } else if (depth > 0) depth--
                continue
            }
            if (kind != null && depth == 0 && !cur.containsKey(l.name)) cur[l.name] = l
        }
        return out
    }
}
