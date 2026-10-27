package com.smartdocs.editor.core.openall

import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/** The XML was refused before parsing (DOCTYPE with entities / external references: XXE, billion laughs). */
class UnsafeXmlException(msg: String) : Exception(msg)

/**
 * XML hardening for every viewer that parses untrusted XML (SVG, FB2, XPS, OPF…): a DOCTYPE that declares entities
 * or points to an external DTD is refused up front, and the SAX parser has external entities / DTD loading off.
 * Pure JVM.
 */
object SafeXml {

    private val DOCTYPE = Regex("""<!DOCTYPE[\s\S]*?(\[[\s\S]*?]\s*)?>""", RegexOption.IGNORE_CASE)

    /** Throws [UnsafeXmlException] for entity declarations or external DTD / entity references. */
    fun check(text: String) {
        val head = text.take(200_000)
        if (Regex("""<!ENTITY""", RegexOption.IGNORE_CASE).containsMatchIn(head)) throw UnsafeXmlException("entity declaration")
        val dt = DOCTYPE.find(head) ?: return
        val v = dt.value
        if (Regex("""\b(SYSTEM|PUBLIC)\b""").containsMatchIn(v) && !isKnownPublicDtd(v)) throw UnsafeXmlException("external DTD")
    }

    /** The well-known W3C SVG / XHTML public DTDs are harmless as long as they are never fetched (they are stripped). */
    private fun isKnownPublicDtd(v: String): Boolean =
        Regex("""PUBLIC\s+"-//W3C//DTD (SVG|XHTML)[^"]*"\s+"https?://www\.w3\.org/[^"]*"""").containsMatchIn(v) && !v.contains('[')

    /** [check]s and removes the DOCTYPE so no parser ever sees it. */
    fun sanitize(text: String): String { check(text); return DOCTYPE.replaceFirst(text, "") }

    fun sanitize(bytes: ByteArray): ByteArray {
        val (cs, bom) = TextDecode.detect(bytes)
        val s = String(bytes, bom, bytes.size - bom, cs)
        val clean = sanitize(s)
        // keep the declared encoding meaningful: re-encode as UTF-8 and fix the XML declaration
        return clean.replaceFirst(Regex("""^\s*<\?xml[^>]*\?>"""), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>").toByteArray(Charsets.UTF_8)
    }

    fun saxFactory(): SAXParserFactory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
        for ((k, v) in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            "http://javax.xml.XMLConstants/feature/secure-processing" to true,
        )) runCatching { setFeature(k, v) }
    }

    /** Parses [text] with [handler] after [sanitize]; entity resolution always answers with an empty stream. */
    fun parse(text: String, handler: DefaultHandler) {
        val clean = sanitize(text)
        val reader = saxFactory().newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
        reader.parse(InputSource(StringReader(clean)))
    }

    fun parse(bytes: ByteArray, handler: DefaultHandler) {
        val (cs, bom) = TextDecode.detect(bytes)
        // honour an explicit encoding declaration in the prolog
        val decl = Regex("""^\s*<\?xml[^>]*encoding\s*=\s*["']([\w.-]+)["']""").find(String(bytes, bom, minOf(200, bytes.size - bom), Charsets.ISO_8859_1))?.groupValues?.get(1)
        val charset = decl?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() }?.takeIf { bom == 0 && cs != Charsets.UTF_16LE && cs != Charsets.UTF_16BE } ?: cs
        parse(String(bytes, bom, bytes.size - bom, charset).replaceFirst(Regex("""^\s*<\?xml[^>]*\?>"""), ""), handler)
    }
}

/** Hardened DOM parsing (same rules as [SafeXml.parse]). */
object SafeDom {
    fun parse(text: String): org.w3c.dom.Document {
        val clean = SafeXml.sanitize(text)
        val f = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            runCatching { isExpandEntityReferences = false }
            runCatching { isXIncludeAware = false }
            for ((k, v) in listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
                "http://javax.xml.XMLConstants/feature/secure-processing" to true,
            )) runCatching { setFeature(k, v) }
        }
        val b = f.newDocumentBuilder()
        b.setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
        b.setErrorHandler(null)
        return b.parse(InputSource(StringReader(clean)))
    }

    fun parse(bytes: ByteArray): org.w3c.dom.Document {
        val (cs, bom) = TextDecode.detect(bytes)
        return parse(String(bytes, bom, bytes.size - bom, cs).replaceFirst(Regex("""^\s*<\?xml[^>]*\?>"""), ""))
    }
}
